"""0.5.0: posts stored as .slopmatic, the 3D view's model endpoint, tags, size categories,
where a cloud file came from, the vendored three.js, and the schema 2 -> 3 migration."""

import gzip
import hashlib
import json
import logging
import re
import sqlite3
import time
from array import array

import pytest
from fastapi.testclient import TestClient

from slopmatica_cloud import convert, model3d, nbt
from slopmatica_cloud.app import (
    CSP, PKG_DIR, SCHEMA_VERSION, SIZE_CLASSES, TAGS, sha256_hex, size_class, size_class_sql,
)
from slopmatica_cloud.convert import Region, Schematic

from conftest import PNG, bearer, gz
from test_board import CSRF, Who, ids, make_post, private_endpoints, schem, upload
from test_convert import OLD_DV, assert_same_content, state, synthetic

DV = OLD_DV


def build(blocks: dict, size, palette_extra=(), dv=DV) -> Schematic:
    """A one-region schematic of size from {(x, y, z): state}; everything else air."""
    sx, sy, sz = size
    palette = [state(dv, "minecraft:air")]
    keys = {}
    data = array("I", [0]) * (sx * sy * sz)
    for (x, y, z), st in blocks.items():
        k = nbt.canonical(st)
        if k not in keys:
            keys[k] = len(palette)
            palette.append(st)
        data[(y * sz + z) * sx + x] = keys[k]
    return Schematic("Test", "Alice", dv, [Region("Main", (0, 0, 0), size, palette, data)])


def cube(n=3, name="minecraft:stone") -> dict:
    return {(x, y, z): state(DV, name) for x in range(n) for y in range(n) for z in range(n)}


def file_of(s: Schematic, fmt="slopmatic") -> bytes:
    return nbt.dumps(convert.write(s, fmt))


def model_of(client, pid, h=None):
    r = client.get(f"/api/board/{pid}/model", headers=h or {})
    assert r.status_code == 200, r.text
    return model3d.decode_positions(r.json()), r.json()


@pytest.fixture
def ppl(client, mojang):
    class P:
        alice = Who(client, mojang, "Alice")
        bob = Who(client, mojang, "Bob")
        carol = Who(client, mojang, "Carol")
    return P


def post_schematic(client, who, s: Schematic, path="m.slopmatic", **kw):
    upload(client, who.h, path, file_of(s, path.rsplit(".", 1)[1]))
    return make_post(client, who.h, path, **kw)


# ------------------------------------------------------------------ conversion on post and refresh


@pytest.mark.parametrize("fmt", ["litematic", "nbt"])
def test_posts_are_converted_to_slopmatic(client, ppl, data_dir, fmt):
    source = synthetic(two_regions=(fmt == "litematic"))
    body = file_of(source, fmt)
    upload(client, ppl.alice.h, f"builds/Thing.{fmt}", body, thumb=True)
    p = make_post(client, ppl.alice.h, f"builds/Thing.{fmt}", title="Thing")
    assert p["filename"] == "Thing.slopmatic" and p["format"] == "slopmatic"
    stored = (data_dir / "posts" / p["id"] / "Thing.slopmatic").read_bytes()
    assert sorted(f.name for f in (data_dir / "posts" / p["id"]).iterdir()) == ["Thing.slopmatic", "thumb.png"]
    assert p["size"] == len(stored) and p["sha256"] == hashlib.sha256(stored).hexdigest()
    # block for block (states, block entities, entities, data version) the same as the source
    original = convert.read(nbt.loads(body)[1], fmt, "Thing")
    assert_same_content(convert.read(nbt.loads(stored)[1], "slopmatic", "x"), original)
    assert p["blockCount"] == source.block_count() and p["dimensions"] == list(source.bounds()[1])
    # the user's own file is untouched
    assert client.get(f"/api/files/builds/Thing.{fmt}", headers=ppl.alice.h).content == body
    # quota counts the converted size
    me = client.get("/api/me", headers=ppl.alice.h).json()
    assert me["usedBytes"] == len(body) + len(stored)
    assert client.get("/api/board?tab=mine", headers=ppl.alice.h).json()["postBytes"] == len(stored)
    # downloads are .slopmatic; .nbt and .litematic are still offered as exports
    anon = TestClient(client.app)
    d = anon.get(f"/api/board/{p['id']}/file")
    assert d.content == stored and 'filename="Thing.slopmatic"' in d.headers["content-disposition"]
    for other in ("nbt", "litematic"):
        e = anon.get(f"/api/board/{p['id']}/export?format={other}")
        assert e.status_code == 200 and f'filename="Thing.{other}"' in e.headers["content-disposition"]
        assert convert.read(nbt.loads(e.content)[1], other, "x").block_count() == source.block_count()
    page = anon.get(f"/board/{p['id']}").text
    assert '<option value="nbt">.nbt</option>' in page and '<option value="litematic">.litematic</option>' in page
    assert "<dd>.slopmatic</dd>" in page


def test_quota_counts_the_converted_size(make_client, mojang):
    body = schem("litematic", two_regions=True)
    expected = nbt.dumps(convert.write(convert.read(nbt.loads(body)[1], "litematic", "a"), "slopmatic"))
    assert len(expected) < len(body)
    # room for the file plus its converted copy, not for two copies of the file
    c = make_client(quota_bytes=len(body) + len(expected))
    h = Who(c, mojang, "Alice").h
    upload(c, h, "a.litematic", body)
    p = make_post(c, h)
    # byte for byte what the converter writes: no temporary file name in the gzip header
    # (only header byte 9, the OS field, differs between GzipFile and gzip.compress)
    stored = TestClient(c.app).get(f"/api/board/{p['id']}/file").content
    assert stored[:9] == expected[:9] and stored[10:] == expected[10:]
    assert p["size"] == len(expected)
    assert c.get("/api/me", headers=h).json()["usedBytes"] == len(body) + len(expected)
    assert c.post("/api/board", json={"path": "a.litematic", "title": "T", "visibility": "public"}, headers=h).status_code == 413


def test_refresh_converts_and_refuses_unconvertible(client, ppl, data_dir):
    h = ppl.alice.h
    upload(client, h, "a.litematic", schem("litematic"))
    p = make_post(client, h)
    pdir = data_dir / "posts" / p["id"]
    # the source becomes an .nbt of something else: the snapshot stays .slopmatic, named after the post's file
    upload(client, h, "a.litematic", file_of(build(cube(2), (2, 2, 2)), "litematic"))
    q = client.post(f"/api/board/{p['id']}/refresh", headers=h).json()
    assert q["filename"] == "a.slopmatic" and q["blockCount"] == 8
    assert sorted(f.name for f in pdir.iterdir()) == ["a.slopmatic"]
    # an unconvertible file is refused and the post is unchanged
    upload(client, h, "a.litematic", gz(b"junk"))
    r = client.post(f"/api/board/{p['id']}/refresh", headers=h)
    assert r.status_code == 400 and r.json()["error"].startswith("cannot convert a.litematic to .slopmatic")
    assert client.get(f"/api/board/{p['id']}").json()["sha256"] == q["sha256"]
    assert list((data_dir / "tmp").iterdir()) == []


# ------------------------------------------------------------------ 3D model


def test_model_hidden_blocks_are_culled(client, ppl):
    # a solid 3x3x3 stone cube: only the centre is hidden
    p = post_schematic(client, ppl.alice, build(cube(3), (3, 3, 3)))
    pos, m = model_of(client, p["id"])
    assert m["v"] == 1 and m["size"] == [3, 3, 3] and m["count"] == 26
    assert set(pos) == {k for k in cube(3) if k != (1, 1, 1)}
    assert m["palette"] == [{"id": "minecraft:stone", "shape": "full"}]

    # glass (no map colour) doesn't hide what is behind it; a slab doesn't either; air and voids are not sent
    blocks = cube(3)
    blocks[(1, 2, 1)] = state(DV, "minecraft:glass")
    blocks[(1, 1, 0)] = state(DV, "minecraft:oak_slab", type="bottom")
    blocks[(0, 0, 0)] = state(DV, "minecraft:air")
    blocks[(2, 0, 0)] = state(DV, "minecraft:structure_void")
    blocks.update({(x, y, 4): state(DV, "minecraft:oak_stairs", facing=f, half="bottom")
                   for x, y, f in ((0, 0, "north"), (1, 0, "east"))})
    p2 = post_schematic(client, ppl.alice, build(blocks, (3, 3, 5)), "m2.slopmatic")
    pos, m = model_of(client, p2["id"])
    assert (1, 1, 1) in pos and pos[(1, 1, 1)] == ("minecraft:stone", "full")
    assert pos[(1, 2, 1)] == ("minecraft:glass", "full") and pos[(1, 1, 0)] == ("minecraft:oak_slab", "bottom")
    assert (0, 0, 0) not in pos and (2, 0, 0) not in pos
    # properties are stripped: both stairs share one palette entry
    assert pos[(0, 0, 4)] == pos[(1, 0, 4)] == ("minecraft:oak_stairs", "full")
    assert sorted(e["id"] for e in m["palette"]) == sorted({v[0] for v in pos.values()})
    assert m["count"] == len(pos) == 3 * 3 * 3 - 2 + 2  # everything but air and void; nothing hidden

    # a block inside a big solid box: only the shell is sent
    p3 = post_schematic(client, ppl.alice, build(cube(10), (10, 10, 10)), "m3.slopmatic")
    _, m = model_of(client, p3["id"])
    assert m["count"] == 10 ** 3 - 8 ** 3


def test_model_across_regions(client, ppl):
    # two litematica regions side by side: the enclosing box, culled across the region boundary
    s = synthetic(two_regions=True)
    upload(client, ppl.alice.h, "two.litematic", file_of(s, "litematic"))
    p = make_post(client, ppl.alice.h, "two.litematic")
    pos, m = model_of(client, p["id"])
    assert m["size"] == [7, 2, 2]
    lo, _ = s.bounds()
    solid = set()
    for r in s.regions:
        for i, idx in enumerate(r.blocks):
            if convert.block_kind(r.palette[idx]) == convert.SOLID:
                x, z, y = i % r.size[0], (i // r.size[0]) % r.size[2], i // (r.size[0] * r.size[2])
                solid.add((r.pos[0] + x - lo[0], r.pos[1] + y - lo[1], r.pos[2] + z - lo[2]))
    assert set(pos) == solid  # too small for anything to be hidden


def test_model_visibility(client, ppl, mojang):
    s = build(cube(2), (2, 2, 2))
    pub = post_schematic(client, ppl.alice, s)
    unl = post_schematic(client, ppl.alice, s, visibility="unlisted")
    priv = post_schematic(client, ppl.alice, s, visibility="private", allowed=["Bob"])
    anon = TestClient(client.app)
    assert anon.get(f"/api/board/{pub['id']}/model").status_code == 200
    assert anon.get(f"/api/board/{unl['id']}/model").status_code == 200
    for c, h in ((anon, {}), (client, ppl.carol.h), (ppl.carol.cookie_client(client.app), {})):
        r = c.get(f"/api/board/{priv['id']}/model", headers=h)
        assert r.status_code == 404 and r.json() == {"error": "no such post"}
    assert client.get(f"/api/board/{priv['id']}/model", headers=ppl.bob.h).status_code == 200
    assert client.get(f"/api/board/{priv['id']}/model", headers=ppl.alice.h).status_code == 200
    assert anon.get("/api/board/AAAAAAAAAA/model").status_code == 404
    assert ("GET", f"/api/board/{priv['id']}/model", None) in private_endpoints(priv["id"])


def test_model_cache_and_refresh(client, ppl, data_dir):
    h = ppl.alice.h
    upload(client, h, "m.slopmatic", file_of(build(cube(3), (3, 3, 3))))
    p = make_post(client, h, "m.slopmatic")
    pdir = data_dir / "posts" / p["id"]
    anon = TestClient(client.app)
    r = anon.get(f"/api/board/{p['id']}/model")
    assert r.headers["content-encoding"] == "gzip" and r.headers["content-type"] == "application/json"
    assert r.headers["cache-control"] == "private, max-age=86400"
    cache = pdir / f"model-{p['sha256'][:16]}.json.gz"
    assert cache.is_file() and json.loads(gzip.decompress(cache.read_bytes()))["count"] == 26
    # served from the cache: a tampered cache file is what comes back
    cache.write_bytes(gzip.compress(json.dumps({"v": 1, "size": [1, 1, 1], "count": 0, "palette": [], "groups": []}).encode()))
    assert anon.get(f"/api/board/{p['id']}/model").json()["count"] == 0
    # without gzip it's plain JSON
    plain = anon.get(f"/api/board/{p['id']}/model", headers={"Accept-Encoding": "identity"})
    assert "content-encoding" not in plain.headers and json.loads(plain.content)["count"] == 0

    # refresh drops the cache; the next request sees the new snapshot
    upload(client, h, "m.slopmatic", file_of(build(cube(2), (2, 2, 2))))
    q = client.post(f"/api/board/{p['id']}/refresh", headers=h).json()
    assert not cache.exists() and not list(pdir.glob("model-*"))
    pos, m = model_of(anon, p["id"])
    assert m["count"] == 8 and m["size"] == [2, 2, 2]
    assert (pdir / f"model-{q['sha256'][:16]}.json.gz").is_file()
    # a stale cache left behind is removed at startup
    (pdir / "model-0123456789abcdef.json.gz").write_bytes(b"x")
    (pdir / "leftover.litematic").write_bytes(b"x")
    client.app.state.store.reconcile_posts()
    assert sorted(f.name for f in pdir.iterdir()) == ["m.slopmatic", f"model-{q['sha256'][:16]}.json.gz"]


def test_model_cap(client, ppl, data_dir, monkeypatch):
    monkeypatch.setattr(model3d, "MAX_VISIBLE", 25)
    p = post_schematic(client, ppl.alice, build(cube(3), (3, 3, 3)))  # 26 visible
    anon = TestClient(client.app)
    r = anon.get(f"/api/board/{p['id']}/model")
    assert r.status_code == 413
    assert r.json() == {"error": "too big for the 3D view: 26 visible blocks (the limit is 25)",
                        "visibleBlocks": 26, "maxVisibleBlocks": 25}
    assert (data_dir / "posts" / p["id"] / f"model-{p['sha256'][:16]}.toobig").read_text() == "26"
    assert anon.get(f"/api/board/{p['id']}/model").status_code == 413  # cached
    # hidden blocks don't count: a 4x4x4 cube has 56 visible of 64
    monkeypatch.setattr(model3d, "MAX_VISIBLE", 56)
    big = post_schematic(client, ppl.alice, build(cube(4), (4, 4, 4)), "big.slopmatic")
    assert big["blockCount"] == 64
    assert anon.get(f"/api/board/{big['id']}/model").json()["count"] == 56
    # the page says so
    js = (PKG_DIR / "static" / "board.js").read_text()
    assert "Too big for the 3D view." in js
    monkeypatch.undo()
    assert model3d.MAX_VISIBLE == 300_000


def test_model_unit():
    s = build(cube(3), (3, 3, 3))
    m = model3d.visible_blocks(s)
    assert m["count"] == 26
    with pytest.raises(model3d.TooManyBlocks) as e:
        model3d.visible_blocks(s, max_visible=10)
    assert e.value.visible == 26
    # degenerate shapes: a 1-wide wall shows everything
    wall = build({(0, y, z): state(DV, "minecraft:stone") for y in range(4) for z in range(4)}, (1, 4, 4))
    assert model3d.visible_blocks(wall)["count"] == 16
    # transparent ids (from blockcolors.json) don't hide a neighbour
    glassy = cube(3)
    glassy[(1, 0, 1)] = state(DV, "minecraft:glass")
    assert model3d.visible_blocks(build(glassy, (3, 3, 3)), {"minecraft:glass"})["count"] == 27
    assert model3d.visible_blocks(build(glassy, (3, 3, 3)), set())["count"] == 26


def test_post_page_has_3d_toggle(client, ppl):
    p = post_schematic(client, ppl.alice, build(cube(2), (2, 2, 2)))
    html = TestClient(client.app).get(f"/board/{p['id']}").text
    assert 'data-view="picture"' in html and 'data-view="model"' in html and ">3D model</button>" in html
    assert "data-model-reset" in html and f'data-model-v="{p["sha256"][:12]}"' in html


# ------------------------------------------------------------------ tags


def test_tags(client, ppl):
    h = ppl.alice.h
    upload(client, h, "a.litematic")
    p = make_post(client, h, title="Farm")
    assert p["tags"] == []
    body = {"path": "a.litematic", "title": "T", "visibility": "public"}
    r = client.post("/api/board", json={**body, "tags": ["redstone", "nope"]}, headers=h)
    assert r.status_code == 400 and r.json()["error"].startswith("unknown tag: nope")
    r = client.post("/api/board", json={**body, "tags": ["x", "y"]}, headers=h)
    assert r.status_code == 400 and r.json()["error"].startswith("unknown tags: x, y")
    for bad in ("redstone", [1], {"a": 1}):
        assert client.post("/api/board", json={**body, "tags": bad}, headers=h).status_code == 400
    assert ids(client.get("/api/board?tab=mine", headers=h)) == [p["id"]]  # nothing else was posted
    # duplicates collapse, order is the canonical one
    r = client.post("/api/board", json={**body, "title": "Machine", "tags": ["technical", "redstone", "redstone"]}, headers=h)
    assert r.status_code == 201 and r.json()["tags"] == ["redstone", "technical"]
    q = r.json()
    assert client.get(f"/api/board/{q['id']}").json()["tags"] == ["redstone", "technical"]
    # edit: replace, keep when not given, clear with []
    r = client.patch(f"/api/board/{q['id']}", json={"tags": ["farm", "survival-friendly"]}, headers=h)
    assert r.json()["tags"] == ["farm", "survival-friendly"]
    assert client.patch(f"/api/board/{q['id']}", json={"title": "Machine 2"}, headers=h).json()["tags"] == ["farm", "survival-friendly"]
    assert client.patch(f"/api/board/{q['id']}", json={"tags": ["bogus"]}, headers=h).status_code == 400
    assert client.patch(f"/api/board/{q['id']}", json={"tags": None}, headers=h).status_code == 400
    assert client.patch(f"/api/board/{q['id']}", json={"tags": ["farm"]}, headers=ppl.bob.h).status_code == 403
    assert client.patch(f"/api/board/{q['id']}", json={"tags": []}, headers=h).json()["tags"] == []
    # every tag in the brief, with its display name
    assert list(TAGS) == ["redstone", "farm", "decoration", "building", "survival-friendly", "creative-only",
                          "map-art", "transport", "technical", "terrain", "statue", "interior"]
    assert TAGS["survival-friendly"] == "Survival friendly" and TAGS["map-art"] == "Map art"
    # the website's form posts tags (cookie + CSRF header)
    web = ppl.alice.cookie_client(client.app)
    r = web.post("/api/board", json={**body, "tags": ["statue"]}, headers=CSRF)
    assert r.status_code == 201 and r.json()["tags"] == ["statue"]


def test_tag_and_size_filters(client, ppl):
    a, b = ppl.alice, ppl.bob
    small = build({(x, 0, 0): state(DV, "minecraft:stone") for x in range(999)}, (999, 1, 1))
    medium = build({(x, 0, 0): state(DV, "minecraft:stone") for x in range(1000)}, (1000, 1, 1))
    upload(client, a.h, "s.slopmatic", file_of(small))
    upload(client, a.h, "m.slopmatic", file_of(medium))
    upload(client, b.h, "s.slopmatic", file_of(small))

    def post(who, path, title, tags, visibility="public", **kw):
        r = client.post("/api/board", json={"path": path, "title": title, "visibility": visibility, "tags": tags, **kw}, headers=who.h)
        assert r.status_code == 201, r.text
        return r.json()["id"]

    farm = post(a, "s.slopmatic", "Iron farm", ["farm", "redstone", "survival-friendly"])
    house = post(a, "m.slopmatic", "Oak house", ["building", "interior"])
    sugar = post(b, "s.slopmatic", "Sugar cane farm", ["farm"])
    secret = post(a, "m.slopmatic", "Secret farm", ["farm", "redstone"], "private", allowed=["Bob"])
    hidden = post(a, "s.slopmatic", "Unlisted farm", ["farm"], "unlisted")
    g = client.get(f"/api/board/{farm}").json()
    assert g["sizeClass"] == "small" and g["blockCount"] == 999
    assert client.get(f"/api/board/{house}").json()["sizeClass"] == "medium"

    def q(url, h=None):
        return set(ids(client.get(url, headers=h or {})))

    assert q("/api/board?tag=farm") == {farm, sugar}  # never the private or unlisted ones
    assert q("/api/board?tag=farm", b.h) == {farm, sugar}
    assert q("/api/board?tag=farm&tag=redstone") == {farm}  # all must match
    assert q("/api/board?tag=farm&tag=building") == set()
    assert q("/api/board?size=small") == {farm, sugar}
    assert q("/api/board?size=medium") == {house}
    assert q("/api/board?size=big") == set()
    assert q("/api/board?tag=farm&size=small&q=iron") == {farm}
    assert q("/api/board?tag=farm&q=alice") == {farm}
    assert ids(client.get("/api/board?tag=farm&sort=new")) == [sugar, farm]
    assert q("/api/board?tab=mine&tag=farm", a.h) == {farm, secret, hidden}
    assert q("/api/board?tab=shared&tag=redstone", b.h) == {secret}
    assert q("/api/board?tab=shared&tag=building", b.h) == set()
    assert q("/api/board?tab=mine&size=medium&tag=redstone", a.h) == {secret}
    r = client.get("/api/board?tag=farm&tag=redstone&size=small").json()
    assert r["tags"] == [t for t in TAGS if t in ("farm", "redstone")] and r["size"] == "small" and r["total"] == 1
    for bad in ("tag=nope", "tag=farm&tag=nope", "size=huge", "size=Small"):
        r = client.get(f"/api/board?{bad}")
        assert r.status_code == 400 and "error" in r.json(), bad
    assert "nope" in client.get("/api/board?tag=nope").json()["error"]

    # the board page: filter bar, chips on cards, combined with search and sort
    anon = TestClient(client.app)
    html = anon.get("/board?tag=farm&sort=new").text
    assert "Iron farm" in html and "Sugar cane farm" in html and "Oak house" not in html
    assert "Secret farm" not in html and "Unlisted farm" not in html
    assert 'class="chip tag-chip on" href="/board?sort=new"' in html  # clicking the active tag removes it
    assert 'href="/board?sort=new&amp;tag=redstone&amp;tag=farm"' in html  # adds a tag (canonical order)
    assert 'href="/board?sort=new&amp;tag=farm&amp;size=small"' in html  # the size control keeps the tag
    assert '<input type="hidden" name="tag" value="farm">' in html  # the search form keeps it too
    assert ">Survival friendly</a>" in html and ">Small</a>" in html
    assert "2 posts tagged <strong>Farm</strong>" in html
    html = anon.get("/board?tag=bogus&size=bogus").text  # the page ignores unknown filters
    assert "Oak house" in html and "Iron farm" in html
    html = anon.get("/board?q=farm&tag=building").text
    assert "Nothing matches." in html
    html = anon.get("/board?size=medium").text
    assert "Oak house" in html and "Iron farm" not in html and "size <strong>Medium</strong>" in html
    # the post page shows chips linking to the board
    page = anon.get(f"/board/{farm}").text
    assert 'href="/board?tag=farm">Farm</a>' in page and 'href="/board?size=small"' in page
    assert ">Survival friendly</a>" in page


def test_size_class_boundaries():
    cases = {0: "small", 1: "small", 999: "small", 1000: "medium", 9999: "medium", 10_000: "big",
             99_999: "big", 100_000: "very-big", 10 ** 9: "very-big", None: None}
    db = sqlite3.connect(":memory:")
    for n, expected in cases.items():
        assert size_class(n) == expected, n
        assert db.execute(f"SELECT {size_class_sql('?')}".replace("?", "NULL" if n is None else str(n))).fetchone()[0] == expected
    assert [label for _, label, _ in SIZE_CLASSES] == ["Small", "Medium", "Big", "Very big"]


# ------------------------------------------------------------------ where a cloud file came from


def test_file_source(client, ppl, mojang):
    a, b = ppl.alice, ppl.bob
    upload(client, a.h, "a.litematic", thumb=True)
    pid = make_post(client, a.h, title="Castle")["id"]
    saved = client.post(f"/api/board/{pid}/save", headers=b.h).json()
    expected = {"postId": pid, "title": "Castle", "author": "Alice", "authorUuid": a.uuid}
    assert saved["source"] == expected
    upload(client, b.h, "own.litematic")

    def listing():
        return {f["path"]: f["source"] for f in client.get("/api/files", headers=b.h).json()["files"]}

    assert listing() == {"board/Castle.slopmatic": expected, "own.litematic": None}
    # survives a move
    r = client.post("/api/files/move", json={"from": "board/Castle.slopmatic", "to": "mine/C.slopmatic"}, headers=b.h)
    assert r.status_code == 200 and r.json()["source"] == expected
    # re-uploading the same bytes keeps it
    same = client.get("/api/files/mine/C.slopmatic", headers=b.h).content
    assert client.put("/api/files/mine/C.slopmatic", content=same, headers=b.h).json()["source"] == expected
    # the post's title changes: the file keeps the title it was saved with; the author name follows renames
    client.patch(f"/api/board/{pid}", json={"title": "Big castle"}, headers=a.h)
    mojang.rename("Alice", "Alicia")
    Who(client, mojang, "Alicia")
    assert listing()["mine/C.slopmatic"] == {**expected, "author": "Alicia"}
    # the page shows where it came from, linking to the post
    web = b.cookie_client(client.app)
    html = web.get("/").text
    # (0.9.0: the author's name is outside the link, with a hover card)
    assert f'<a href="/board/{pid}" title="Castle">From the board</a> &middot; by <span class="uname" data-user="{a.uuid}" tabindex="0">Alicia</span>' in html
    assert html.count("From the board") == 1  # not on own.litematic
    # a PUT with different bytes makes it the user's own file
    assert client.put("/api/files/mine/C.slopmatic", content=schem("slopmatic"), headers=b.h).json()["source"] is None
    assert listing() == {"mine/C.slopmatic": None, "own.litematic": None}


def test_file_source_link_needs_a_visible_post(client, ppl):
    a, b = ppl.alice, ppl.bob
    upload(client, a.h, "a.litematic")
    pid = make_post(client, a.h, title="Secret", visibility="private", allowed=["Bob"])["id"]
    gone = make_post(client, a.h, title="Gone")["id"]
    client.post(f"/api/board/{pid}/save", headers=b.h)
    client.post(f"/api/board/{gone}/save", headers=b.h)
    web = b.cookie_client(client.app)
    html = web.get("/").text
    by = f' &middot; by <span class="uname" data-user="{a.uuid}" tabindex="0">Alice</span></div>'  # 0.9.0: a hover card
    assert f'<a href="/board/{pid}" title="Secret">From the board</a>' + by in html
    assert f'<a href="/board/{gone}" ' in html
    client.patch(f"/api/board/{pid}", json={"allowed": []}, headers=a.h)  # Bob can't see it any more
    client.delete(f"/api/board/{gone}", headers=a.h)
    html = web.get("/").text
    assert f"/board/{pid}" not in html and f"/board/{gone}" not in html
    assert html.count("From the board</span>" + by) == 2  # plain text, no link
    # the API still says where they came from
    assert {f["source"]["postId"] for f in client.get("/api/files", headers=b.h).json()["files"]} == {pid, gone}


# ------------------------------------------------------------------ vendored three.js and the CSP


def test_vendored_scripts_load_under_the_csp(client, ppl):
    static = PKG_DIR / "static"
    vendor = static / "vendor" / "three"
    assert (vendor / "LICENSE").read_text().startswith("The MIT License")
    orbit = (vendor / "OrbitControls.js").read_text()
    assert "from './three.module.js';" in orbit and "from 'three'" not in orbit
    assert "from './three.core.js'" in (vendor / "three.module.js").read_text()
    viewer = (static / "model3d.js").read_text()
    assert set(re.findall(r'^import .* from "([^"]+)";', viewer, re.M)) == {
        "./vendor/three/three.module.js", "./vendor/three/OrbitControls.js"}
    for f in [*vendor.glob("*.js"), static / "model3d.js", static / "board.js"]:
        text = f.read_text()
        assert not re.search(r"\beval\(|new Function\(", text), f.name  # no 'unsafe-eval' needed
    # the CSP is unchanged: only the site's own scripts, nothing inline or evaluated
    assert "script-src 'self';" in CSP and "unsafe" not in CSP
    p = post_schematic(client, ppl.alice, build(cube(2), (2, 2, 2)))
    anon = TestClient(client.app)
    page = anon.get(f"/board/{p['id']}")
    assert page.headers["content-security-policy"] == CSP
    scripts = re.findall(r"<script\b([^>]*)>(.*?)</script>", page.text, re.S)
    assert scripts and all('src="/static/' in attrs and not body.strip() for attrs, body in scripts)
    assert not re.search(r"<[^>]+\son[a-z]+=", page.text)  # no inline event handlers
    assert 'style="' not in page.text
    # the scripts are served as JavaScript (module scripts need it), gzip-compressed when accepted
    for name in ("vendor/three/three.module.js", "vendor/three/three.core.js", "vendor/three/OrbitControls.js",
                 "model3d.js", "blockcolors.json"):
        r = anon.get(f"/static/{name}")
        assert r.status_code == 200, name
        assert r.headers["content-encoding"] == "gzip" and r.headers["vary"] == "Accept-Encoding"
        assert r.content == (static / name).read_bytes()
        assert ("javascript" if name.endswith(".js") else "json") in r.headers["content-type"]
        plain = anon.get(f"/static/{name}", headers={"Accept-Encoding": "identity"})
        assert "content-encoding" not in plain.headers and plain.content == (static / name).read_bytes()
    assert anon.get("/static/icon.svg").status_code == 200
    assert anon.get("/static/vendor/three/LICENSE").status_code == 200


# ------------------------------------------------------------------ migration 2 -> 3

# The schema exactly as 0.4.0 (user_version 2) left it, as dumped from the live database.
SCHEMA_V2 = """
CREATE TABLE users (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, is_admin INTEGER NOT NULL DEFAULT 0,
    created INTEGER NOT NULL, last_login INTEGER NOT NULL, password_hash TEXT, password_set INTEGER);
CREATE TABLE tokens (hash TEXT PRIMARY KEY, kind TEXT NOT NULL, uuid TEXT NOT NULL REFERENCES users(uuid),
    created INTEGER NOT NULL, expires INTEGER NOT NULL, next TEXT);
CREATE INDEX tokens_uuid ON tokens(uuid);
CREATE TABLE challenges (server_id TEXT PRIMARY KEY, expires INTEGER NOT NULL);
CREATE TABLE files (uuid TEXT NOT NULL, path TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL,
    modified INTEGER NOT NULL, has_thumb INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (uuid, path));
CREATE TABLE posts (id TEXT PRIMARY KEY, uuid TEXT NOT NULL REFERENCES users(uuid), title TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    visibility TEXT NOT NULL CHECK (visibility IN ('public', 'unlisted', 'private')),
    source_path TEXT NOT NULL, filename TEXT NOT NULL, format TEXT NOT NULL, size INTEGER NOT NULL,
    sha256 TEXT NOT NULL, has_thumb INTEGER NOT NULL DEFAULT 0, size_x INTEGER, size_y INTEGER, size_z INTEGER,
    block_count INTEGER, created INTEGER NOT NULL, updated INTEGER NOT NULL);
CREATE INDEX posts_uuid ON posts(uuid);
CREATE INDEX posts_visibility ON posts(visibility, created);
CREATE TABLE post_access (post_id TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE, uuid TEXT NOT NULL,
    name TEXT NOT NULL, PRIMARY KEY (post_id, uuid));
CREATE INDEX post_access_uuid ON post_access(uuid);
CREATE TABLE votes (post_id TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE, uuid TEXT NOT NULL REFERENCES users(uuid),
    value INTEGER NOT NULL CHECK (value IN (-1, 1)), created INTEGER NOT NULL, PRIMARY KEY (post_id, uuid));
CREATE TABLE login_failures (key TEXT NOT NULL, at INTEGER NOT NULL);
CREATE INDEX login_failures_key ON login_failures(key, at);
PRAGMA user_version = 2;
"""

UUID = "12345678-1234-1234-1234-123456789abc"
VOTER = "87654321-4321-4321-4321-cba987654321"


def v2_data_dir(data_dir):
    """A 0.4.0 data directory like the live one: one user with a cloud file, a public .litematic post
    with a thumbnail and a vote, plus an .nbt post, an unconvertible post and an existing .slopmatic one."""
    t = int(time.time())
    ms = t * 1000
    lit = file_of(synthetic(two_regions=True), "litematic")
    nbt_body = file_of(build(cube(3), (3, 3, 3)), "nbt")
    slop = file_of(build(cube(2), (2, 2, 2)))
    files = data_dir / "users" / UUID / "files"
    files.mkdir(parents=True)
    (files / "Ghost Ship.litematic").write_bytes(lit)
    db = sqlite3.connect(data_dir / "cloud.db")
    db.executescript(SCHEMA_V2)
    db.execute("INSERT INTO users VALUES(?,?,?,?,?,NULL,NULL)", (UUID, "Veteran", 0, t - 1000, t - 10))
    db.execute("INSERT INTO users VALUES(?,?,?,?,?,NULL,NULL)", (VOTER, "Voter", 0, t - 1000, t - 10))
    db.execute("INSERT INTO tokens VALUES(?,?,?,?,?,NULL)", (sha256_hex("tok"), "api", UUID, t, t + 86400))
    db.execute("INSERT INTO files VALUES(?,?,?,?,?,0)",
               (UUID, "Ghost Ship.litematic", len(lit), hashlib.sha256(lit).hexdigest(), ms))
    posts = {
        "GhostShip1": ("Halloween Ghost Ship.litematic", "litematic", lit, (7, 2, 2), synthetic(two_regions=True).block_count(), "public"),
        "NbtPost001": ("Cube.nbt", "nbt", nbt_body, (3, 3, 3), 27, "private"),
        "JunkPost01": ("Junk.nbt", "nbt", gz(b"junk"), None, None, "unlisted"),
        "SlopPost01": ("Small.slopmatic", "slopmatic", slop, (2, 2, 2), 8, "public"),
    }
    for pid, (fname, fmt, body, dims, blocks, vis) in posts.items():
        pdir = data_dir / "posts" / pid
        pdir.mkdir(parents=True)
        (pdir / fname).write_bytes(body)
        (pdir / "thumb.png").write_bytes(PNG)
        d = dims or (None, None, None)
        db.execute("INSERT INTO posts VALUES(?,?,?,?,?,?,?,?,?,?,1,?,?,?,?,?,?)",
                   (pid, UUID, fname.rsplit(".", 1)[0], "", vis, "Ghost Ship.litematic", fname, fmt, len(body),
                    hashlib.sha256(body).hexdigest(), *d, blocks, ms, ms))
    db.execute("INSERT INTO post_access VALUES(?,?,?)", ("NbtPost001", VOTER, "Voter"))
    db.execute("INSERT INTO votes VALUES(?,?,?,?)", ("GhostShip1", VOTER, 1, ms))
    db.commit()
    db.close()
    return posts


def test_migrates_a_v2_database(make_client, mojang, data_dir, caplog):
    posts = v2_data_dir(data_dir)
    before_used = sum(len(p[2]) for p in posts.values()) + len(posts["GhostShip1"][2])  # files + posts
    caplog.set_level(logging.INFO, logger="slopmatica_cloud")
    c = make_client()
    h = bearer("tok")
    store = c.app.state.store
    assert store.db.execute("PRAGMA user_version").fetchone()[0] == SCHEMA_VERSION

    # the .litematic post (like the live one) is now .slopmatic, block for block
    ghost = c.get("/api/board/GhostShip1").json()
    pdir = data_dir / "posts" / "GhostShip1"
    assert ghost["filename"] == "Halloween Ghost Ship.slopmatic" and ghost["format"] == "slopmatic"
    stored = (pdir / "Halloween Ghost Ship.slopmatic").read_bytes()
    assert sorted(f.name for f in pdir.iterdir()) == ["Halloween Ghost Ship.slopmatic", "thumb.png"]
    assert ghost["size"] == len(stored) and ghost["sha256"] == hashlib.sha256(stored).hexdigest()
    assert_same_content(convert.read(nbt.loads(stored)[1], "slopmatic", "x"),
                        convert.read(nbt.loads(posts["GhostShip1"][2])[1], "litematic", "x"))
    assert ghost["blockCount"] == posts["GhostShip1"][4] and ghost["sizeClass"] == "small"
    assert ghost["score"] == 1 and ghost["hasThumbnail"] and ghost["tags"] == []
    assert c.get("/api/board/GhostShip1/file").content == stored
    assert c.get("/api/board/GhostShip1/export?format=litematic").status_code == 200
    assert c.get("/api/board/GhostShip1/model").status_code == 200
    # the .nbt post too, keeping its access list
    cube_post = c.get("/api/board/NbtPost001", headers=h).json()
    assert cube_post["format"] == "slopmatic" and cube_post["filename"] == "Cube.slopmatic"
    assert cube_post["allowed"] == [{"uuid": VOTER, "name": "Voter"}] and cube_post["blockCount"] == 27
    assert c.get("/api/board/NbtPost001").status_code == 404  # still private
    # the unconvertible one is left as it is, and logged
    junk = c.get("/api/board/JunkPost01").json()
    assert junk["format"] == "nbt" and junk["filename"] == "Junk.nbt" and junk["sizeClass"] is None
    assert c.get("/api/board/JunkPost01/file").content == gz(b"junk")
    assert c.get("/api/board/JunkPost01/model").status_code == 400
    assert any("JunkPost01" in r.message and "left as it is" in r.message for r in caplog.records)
    # the .slopmatic one is untouched
    slop = c.get("/api/board/SlopPost01").json()
    assert slop["sha256"] == hashlib.sha256(posts["SlopPost01"][2]).hexdigest() and slop["sizeClass"] == "small"
    # quota usage follows the converted sizes
    used = c.get("/api/me", headers=h).json()["usedBytes"]
    sizes = {pid: c.get(f"/api/board/{pid}", headers=h).json()["size"] for pid in posts}
    assert used == len(posts["GhostShip1"][2]) + sum(sizes.values())
    assert used < before_used
    # files got their (empty) source, and everything new works on the migrated database
    assert c.get("/api/files", headers=h).json()["files"][0]["source"] is None
    assert c.patch("/api/board/GhostShip1", json={"tags": ["statue"]}, headers=h).json()["tags"] == ["statue"]
    assert ids(c.get("/api/board?tag=statue&size=small")) == ["GhostShip1"]
    log_lines = [r.message for r in caplog.records if "converted board post" in r.message]
    assert len(log_lines) == 2

    # a restart migrates nothing twice
    mtime = (pdir / "Halloween Ghost Ship.slopmatic").stat().st_mtime_ns
    caplog.clear()
    c2 = make_client()
    assert c2.app.state.store.db.execute("PRAGMA user_version").fetchone()[0] == SCHEMA_VERSION
    assert (pdir / "Halloween Ghost Ship.slopmatic").stat().st_mtime_ns == mtime
    # (the anonymous download and export above count once: 0.8.0's download counter)
    assert c2.get("/api/board/GhostShip1").json() == {**ghost, "tags": ["statue"], "downloads": 1}
    assert not any("convert" in r.message for r in caplog.records)
    assert c2.get("/api/me", headers=h).json()["usedBytes"] == used


def test_v2_migration_resumes_after_an_interruption(make_client, mojang, data_dir):
    """A crash after writing the converted file but before recording it leaves the database at
    version 2 with a stray .slopmatic next to the .litematic: the next start converts again."""
    v2_data_dir(data_dir)
    stray = data_dir / "posts" / "GhostShip1" / "Halloween Ghost Ship.slopmatic"
    stray.write_bytes(b"half-written")
    c = make_client()
    ghost = c.get("/api/board/GhostShip1").json()
    assert ghost["format"] == "slopmatic" and stray.read_bytes() != b"half-written"
    assert ghost["sha256"] == hashlib.sha256(stray.read_bytes()).hexdigest()
    # and an old file left behind after the database was updated is cleaned up at startup
    leftover = data_dir / "posts" / "GhostShip1" / "Halloween Ghost Ship.litematic"
    leftover.write_bytes(b"old")
    make_client()
    assert not leftover.exists()


def test_v2_migration_size_classes(make_client, mojang, data_dir):
    v2_data_dir(data_dir)
    db = sqlite3.connect(data_dir / "cloud.db")
    # .slopmatic posts aren't converted, so their stored block counts go straight into the size class
    for i, n in enumerate((999, 1000, 9999, 10_000, 99_999, 100_000)):
        pid = f"SizePost{i:02d}"
        pdir = data_dir / "posts" / pid
        pdir.mkdir()
        (pdir / "s.slopmatic").write_bytes(gz(b"x"))
        db.execute("INSERT INTO posts VALUES(?,?,?,?,?,?,?,?,?,?,0,1,1,1,?,1,1)",
                   (pid, UUID, "S", "", "public", "s.slopmatic", "s.slopmatic", "slopmatic", 22,
                    hashlib.sha256(gz(b"x")).hexdigest(), n))
    db.commit()
    db.close()
    c = make_client()
    got = [c.get(f"/api/board/SizePost{i:02d}").json()["sizeClass"] for i in range(6)]
    assert got == ["small", "medium", "medium", "big", "big", "very-big"]
