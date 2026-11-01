"""0.9.0: WorldEdit / Sponge .schem files (read v1 to v3, written as v3) for uploads, the board and exports; hover cards
on players' names (GET /api/users/{uuid}/card); WebP pictures, which the account page's crop step can send."""

import dataclasses
import json
import random
import re
import struct
from array import array

import pytest
from fastapi.testclient import TestClient

from slopmatica_cloud import __version__, convert, images, nbt
from slopmatica_cloud.app import CSP, SCHEMA_VERSION, USER_CARD_MAX_AGE
from slopmatica_cloud.convert import ConversionError, parse_state, state_string, varint_array
from slopmatica_cloud.nbt import ByteArray, Compound, Double, Float, Int, IntArray, List, Long, Short, String

from conftest import PNG, gz
from test_board import CSRF, Who, make_post, schem, upload
from test_convert import NEW_DV, OLD_DV, assert_same_content, chest_data, entity_data, state, synthetic, through
from test_v070 import ADMIN, not_whitelisted
from test_v080 import jpeg, post_json, set_name


@pytest.fixture
def gated(make_client):
    return make_client(whitelist_enforced=True)


@pytest.fixture
def ppl(gated, mojang):
    class P:
        admin = Who(gated, mojang, ADMIN)
        alice = Who(gated, mojang, "Alice")
        bob = Who(gated, mojang, "Bob")
        outsider = Who(gated, mojang, "Outsider")
    for who in (P.alice, P.bob):
        gated.app.state.store.whitelist_add(who.uuid, who.name, "manual", None)
    return P


def test_version_bumped():
    assert __version__ == "0.9.0" and SCHEMA_VERSION == 6  # no migration in 0.9.0


# ------------------------------------------------------------------ block state strings and varints


@pytest.mark.parametrize("dv", [OLD_DV, NEW_DV])
def test_parse_state(dv):
    stairs = parse_state("minecraft:oak_stairs[facing=east,half=bottom]", dv)
    assert nbt.canonical(stairs) == nbt.canonical(state(dv, "minecraft:oak_stairs", facing="east", half="bottom"))
    assert list(stairs["properties" if dv >= convert.NEW_STATE_KEYS else "Properties"]) == ["facing", "half"]  # in order
    assert nbt.canonical(parse_state("minecraft:stone", dv)) == nbt.canonical(state(dv, "minecraft:stone"))
    assert nbt.canonical(parse_state(" minecraft:stone[] ", dv)) == nbt.canonical(state(dv, "minecraft:stone"))
    assert nbt.canonical(parse_state("chest[ facing = north ]", dv)) == nbt.canonical(state(dv, "chest", facing="north"))
    for bad in ("", "[a=b]", "minecraft:stone[facing]", "minecraft:stone[a=b", "minecraft:stone[=x]", "a[b=c]d", "x[a=]"):
        with pytest.raises(ConversionError):
            parse_state(bad, dv)
    # and back
    assert state_string(stairs) == "minecraft:oak_stairs[facing=east,half=bottom]"
    assert state_string(state(dv, "minecraft:air")) == "minecraft:air"
    with pytest.raises(ConversionError):
        state_string(Compound())


def leb128(values) -> bytes:
    return b"".join(convert._varint(v) for v in values)


def test_varint_array():
    for top in (100, 300, 20000, 3_000_000):  # one, two, three and four byte varints
        values = [random.randrange(top) for _ in range(5000)] + [top - 1, 0]
        assert list(varint_array(leb128(values), "x")) == values
    assert list(varint_array(b"", "x")) == []
    for bad in (b"\x01\x80", b"\xff" * 12 + b"\x01", b"\x80"):  # cut off at the end; overlong
        with pytest.raises(ConversionError):
            varint_array(bad, "schematic")


# ------------------------------------------------------------------ reading hand-built files


def v2_root(version=2):
    """A WorldEdit-style v2 file (v1 when version is 1): 3x2x2, copied at (100, 64, -20) with the paste origin
    two blocks below the min corner; 130 palette entries so that one index needs a two-byte varint; a chest,
    and an armor stand at world position (101.5, 65, -19.5)."""
    palette = {"minecraft:air": 0, "minecraft:stone": 1, "minecraft:oak_stairs[facing=north,half=bottom]": 2,
               "minecraft:chest[facing=east,type=single,waterlogged=false]": 3}
    palette.update({f"minecraft:filler_{i}": i for i in range(4, 130)})
    blocks = [1, 1, 1, 1, 3, 129, 0, 2, 0, 0, 0, 1]
    chest = Compound({"Pos": IntArray([1, 0, 1]), "Id": String("minecraft:chest"),
                      "CustomName": String('{"text":"Loot"}'), "Items": List([Compound({"Slot": nbt.Byte(0)})])})
    stand = Compound({"Pos": List([Double(101.5), Double(65.0), Double(-19.5)]), "Id": String("minecraft:armor_stand"),
                      "Rotation": List([Float(90.0), Float(0.0)]), "Invisible": nbt.Byte(1)})
    root = Compound({
        "Version": Int(version),
        "Metadata": Compound({"Name": String("Hand v2"), "Author": String("Alice"), "Date": Long(1700000000000),
                              "WEOffsetX": Int(0), "WEOffsetY": Int(2), "WEOffsetZ": Int(-1)}),
        "Width": Short(3), "Height": Short(2), "Length": Short(2),
        "Offset": IntArray([100, 64, -20]),
        "PaletteMax": Int(len(palette)),
        "Palette": Compound({k: Int(v) for k, v in palette.items()}),
        "BlockData": ByteArray(leb128(blocks)),
    })
    if version == 1:
        chest["ContentVersion"] = Int(1)
        root["TileEntities"] = List([chest])
    else:
        root["DataVersion"] = Int(OLD_DV)
        root["BlockEntities"] = List([chest])
        root["Entities"] = List([stand])
    return root


def test_read_sponge_v2():
    data = nbt.dumps(v2_root(), name="Schematic")
    s = convert.read(nbt.loads(data)[1], "schem", "fallback")
    assert (s.name, s.author, s.data_version, s.created) == ("Hand v2", "Alice", OLD_DV, 1700000000000)
    [r] = s.regions
    assert r.pos == (0, 2, -1) and r.size == (3, 2, 2) and len(r.palette) == 130
    assert list(r.blocks) == [1, 1, 1, 1, 3, 129, 0, 2, 0, 0, 0, 1]
    assert nbt.canonical(r.palette[2]) == nbt.canonical(state(OLD_DV, "minecraft:oak_stairs", facing="north", half="bottom"))
    assert convert.state_name(r.palette[129]) == "minecraft:filler_129"
    # the block entity: its tags with an id, no position
    be = r.block_entities[(1, 0, 1)]
    assert be["id"] == "minecraft:chest" and be["CustomName"] == '{"text":"Loot"}' and type(be["Items"][0]["Slot"]) is nbt.Byte
    assert "Pos" not in be and "Id" not in be
    # the entity: world position made relative to the min corner (Offset)
    [e] = r.entities
    assert e.pos == (1.5, 1.0, 0.5) and e.data["id"] == "minecraft:armor_stand" and e.data["Invisible"] == 1
    assert "Id" not in e.data
    assert s.block_count() == 8 and s.distinct_types() == 4 + 1  # stone, chest, stairs, filler_129 + the stand


def test_read_sponge_v1():
    s = convert.read(v2_root(version=1), "schem", "fallback")
    assert s.data_version == convert.SPONGE_V1_DATA_VERSION  # v1 has no DataVersion
    be = s.regions[0].block_entities[(1, 0, 1)]
    assert be["id"] == "minecraft:chest" and "ContentVersion" not in be
    assert s.regions[0].entities == []


def test_read_sponge_v2_relative_entities():
    """Some v2 writers store entity positions relative to the min corner already."""
    root = v2_root()
    root["Entities"][0]["Pos"] = List([Double(1.5), Double(1.0), Double(0.5)])
    assert convert.read(root, "schem", "x").regions[0].entities[0].pos == (1.5, 1.0, 0.5)


def v3_root():
    """A WorldEdit 7.3-style v3 file: 2x1x2 (one side over 32767 would be a negative Short), everything relative."""
    chest = Compound({"Pos": IntArray([1, 0, 0]), "Id": String("minecraft:chest"), "Data": chest_data()})
    stand = Compound({"Pos": List([Double(0.5), Double(0.0), Double(1.5)]), "Id": String("minecraft:armor_stand"),
                      "Data": Compound({"Rotation": List([Float(90.0), Float(0.0)]), "Tags": List([String("a")])})})
    return Compound({"Schematic": Compound({
        "Version": Int(3),
        "DataVersion": Int(NEW_DV),
        "Metadata": Compound({"Name": String("Hand v3"), "Date": Long(5)}),
        "Width": Short(2), "Height": Short(1), "Length": Short(2),
        "Offset": IntArray([-1, 0, -2]),
        "Blocks": Compound({
            "Palette": Compound({"minecraft:air": Int(0), "minecraft:chest[facing=east]": Int(1), "minecraft:glass": Int(2)}),
            "Data": ByteArray(bytes([2, 1, 0, 2])),
            "BlockEntities": List([chest]),
        }),
        "Biomes": Compound({"Palette": Compound({"minecraft:plains": Int(0)}), "Data": ByteArray(bytes(4))}),
        "Entities": List([stand]),
    })})


def test_read_sponge_v3():
    s = convert.read(nbt.loads(nbt.dumps(v3_root()))[1], "schem", "fallback")
    assert (s.name, s.author, s.data_version) == ("Hand v3", "", NEW_DV)
    [r] = s.regions
    assert r.pos == (-1, 0, -2) and r.size == (2, 1, 2) and list(r.blocks) == [2, 1, 0, 2]
    assert nbt.canonical(r.palette[1]) == nbt.canonical(state(NEW_DV, "minecraft:chest", facing="east"))
    assert nbt.canonical(r.block_entities[(1, 0, 0)]) == nbt.canonical(chest_data())  # Data, plus the id
    [e] = r.entities
    assert e.pos == (0.5, 0.0, 1.5) and e.data["id"] == "minecraft:armor_stand" and list(e.data["Tags"]) == ["a"]
    # no name in the file: the file name
    root = v3_root()
    del root["Schematic"]["Metadata"]
    assert convert.read(root, "schem", "fallback").name == "fallback"


def test_corrupt_schematics():
    def broken(change):
        root = v3_root()
        change(root["Schematic"])
        with pytest.raises(ConversionError):
            convert.read(root, "schem", "x")

    broken(lambda s: s.update(Version=Int(4)))  # unknown version
    broken(lambda s: s.pop("Version"))
    broken(lambda s: s.pop("Width"))
    broken(lambda s: s.update(Width=Short(0)))
    broken(lambda s: s.pop("Blocks"))
    broken(lambda s: s["Blocks"].pop("Data"))
    broken(lambda s: s["Blocks"].update(Data=ByteArray(bytes([2, 1, 0]))))  # one block short
    broken(lambda s: s["Blocks"].update(Data=ByteArray(bytes([2, 1, 0, 3]))))  # outside the palette
    broken(lambda s: s["Blocks"].update(Data=ByteArray(bytes([2, 1, 0, 0x82]))))  # cut-off varint
    broken(lambda s: s["Blocks"].update(Palette=Compound()))
    broken(lambda s: s["Blocks"].update(Palette=Compound({"minecraft:air": Int(0), "minecraft:stone": Int(0)})))
    broken(lambda s: s["Blocks"].update(Palette=Compound({"minecraft:air": Int(0), "stone[x": Int(1)})))
    broken(lambda s: s["Blocks"].update(Palette=Compound({"minecraft:air": String("0")})))
    with pytest.raises(ConversionError):
        convert.read(Compound({"Schematic": String("no")}), "schem", "x")
    with pytest.raises(convert.TooLarge):
        convert.read(v3_root(), "schem", "x", max_volume=3)


# ------------------------------------------------------------------ writing


def test_schem_layout():
    root = convert.write(synthetic(two_regions=True), "schem")
    s = root["Schematic"]
    assert set(root) == {"Schematic"} and s["Version"] == 3 and type(s["Version"]) is Int and s["DataVersion"] == OLD_DV
    assert [(type(s[k]), s[k]) for k in ("Width", "Height", "Length")] == [(Short, 7), (Short, 2), (Short, 2)]
    assert list(s["Offset"]) == [0, 0, 0] and type(s["Offset"]) is IntArray
    assert s["Metadata"]["Name"] == "Synthetic" and s["Metadata"]["Author"] == "Alice" and type(s["Metadata"]["Date"]) is Long
    palette = s["Blocks"]["Palette"]
    assert palette["minecraft:oak_stairs[facing=north,half=bottom]"] < len(palette)
    assert sorted(palette.values()) == list(range(len(palette)))
    assert "minecraft:structure_void" in palette  # the gap between the two regions
    be = s["Blocks"]["BlockEntities"][0]
    assert list(be["Pos"]) == [1, 0, 1] and be["Id"] == "minecraft:chest" and be["Data"]["CustomName"] == '{"text":"Loot"}'
    assert "id" not in be["Data"]
    ents = s["Entities"]
    assert [list(e["Pos"]) for e in ents] == [[1.5, 1.0, 0.5], [5.25, 0.0, 0.75]]
    assert ents[0]["Id"] == "minecraft:armor_stand" and "Pos" not in ents[0]["Data"] and "id" not in ents[0]["Data"]
    assert type(s["Blocks"]["Data"]) is ByteArray and len(s["Blocks"]["Data"]) == 7 * 2 * 2


@pytest.mark.parametrize("dv", [OLD_DV, NEW_DV])
def test_schem_round_trips(dv):
    for src in (synthetic(dv), through(synthetic(dv, two_regions=True), "litematic"), convert.read(v2_root(), "schem", "x"),
                convert.read(v3_root(), "schem", "x")):
        once = through(src, "schem")
        assert_same_content(src, once)
        assert_same_content(once, through(once, "schem"))
        for fmt in ("slopmatic", "litematic"):  # and through every other format
            assert_same_content(once, through(through(once, fmt), "schem"))
        flat = dataclasses.replace(once, regions=[dataclasses.replace(once.regions[0], pos=(0, 0, 0))])
        assert_same_content(flat, through(through(once, "nbt"), "schem"))  # .nbt has no offset
    # the Offset keeps the min corner where it was relative to the origin
    moved = synthetic(dv)
    moved.regions[0].pos = (4, -3, 7)
    assert through(moved, "schem").regions[0].pos == (4, -3, 7)


def test_schem_long_sides():
    """Width, Height and Length are unsigned shorts: up to 65535."""
    air, stone = state(OLD_DV, "minecraft:air"), state(OLD_DV, "minecraft:stone")
    n = 40000
    s = convert.Schematic("Line", "", OLD_DV, [convert.Region("Line", (0, 0, 0), (n, 1, 1), [air, stone], array("I", [1]) * n)])
    root = convert.write(s, "schem")
    assert root["Schematic"]["Width"] == n - 0x10000  # stored negative
    assert through(s, "schem").regions[0].size == (n, 1, 1)
    s.regions[0] = convert.Region("Line", (0, 0, 0), (70000, 1, 1), [air, stone], array("I", [1]) * 70000)
    with pytest.raises(ConversionError):
        convert.write(s, "schem")


# ------------------------------------------------------------------ through the API


def schem_bytes(**kw) -> bytes:
    return nbt.dumps(convert.write(synthetic(**kw), "schem"))


def test_schem_files_and_exports(gated, ppl):
    c, h = gated, ppl.alice.h
    body = schem_bytes()
    # the mod's sync PUT and listing
    r = c.put("/api/files/builds/Tower.schem", content=body, headers=h)
    assert r.status_code == 201 and r.json()["path"] == "builds/Tower.schem"
    assert c.get("/api/me", headers=h).json()["usedBytes"] == len(body)
    assert c.put("/api/thumbnails/builds/Tower.schem", content=PNG, headers=h).status_code == 200
    assert c.get("/api/files/builds/Tower.schem", headers=h).content == body
    # exported to every other format, and other formats to .schem
    for fmt in ("slopmatic", "nbt", "litematic"):
        e = c.get(f"/api/export/builds/Tower.schem?format={fmt}", headers=h)
        assert e.status_code == 200 and f'filename="Tower.{fmt}"' in e.headers["content-disposition"]
        assert_same_content(convert.read(nbt.loads(e.content)[1], fmt, "x"), synthetic())
    upload(c, h, "two.litematic", schem("litematic", two_regions=True))
    e = c.get("/api/export/two.litematic?format=schem", headers=h)
    assert e.status_code == 200 and 'filename="two.schem"' in e.headers["content-disposition"]
    assert nbt.loads(e.content)[1]["Schematic"]["Version"] == 3
    assert c.get("/api/export/builds/Tower.schem?format=schem", headers=h).status_code == 400  # same format
    # moved and renamed like the rest
    assert c.post("/api/files/move", json={"from": "builds/Tower.schem", "to": "Tower2.schem"}, headers=h).status_code == 200
    # a folder may not be named like one
    assert c.put("/api/files/x.schem/a.litematic", content=body, headers=h).status_code == 400
    # the website lists the format everywhere it lists the others
    web = ppl.alice.cookie_client(c.app)
    html = web.get("/").text
    assert 'accept=".slopmatic,.nbt,.litematic,.schem"' in html and '<option value="schem">.schem</option>' in html


def test_schem_mod_check_and_corrupt_files(gated, ppl):
    c, h = gated, ppl.alice.h
    modded = synthetic()
    modded.regions[0].palette[1] = state(OLD_DV, "create:shaft")
    r = c.put("/api/files/m.schem", content=nbt.dumps(convert.write(modded, "schem")), headers=h)
    assert r.status_code == 400 and r.json()["code"] == "modded" and r.json()["namespaces"] == ["create"]
    # a corrupt file is stored (the game judges its own files), but can't be exported or posted
    root = v3_root()
    root["Schematic"]["Blocks"]["Data"] = ByteArray(b"\x02\x01")
    for name, data in (("bad.schem", nbt.dumps(root)), ("junk.schem", gz(b"not nbt"))):
        upload(c, h, name, data)
        r = c.get(f"/api/export/{name}?format=litematic", headers=h)
        assert r.status_code == 400 and r.json()["error"].startswith(f"cannot convert {name}")
        assert post_json(c, h, path=name, status=400)["error"]
    assert c.put("/api/files/plain.schem", content=b"PK not gzip", headers=h).status_code == 400
    assert list((c.app.state.store.root / "tmp").iterdir()) == []


def test_schem_on_the_board(gated, ppl):
    c, h = gated, ppl.alice.h
    body = schem_bytes(two_regions=True)
    upload(c, h, "Castle.schem", body, thumb=True)
    used = c.get("/api/me", headers=h).json()["usedBytes"]
    p = post_json(c, h, path="Castle.schem", title="Castle")
    # stored as .slopmatic, with the same blocks; size, counts and complexity read from it
    assert p["filename"] == "Castle.slopmatic" and p["format"] == "slopmatic" and p["hasThumbnail"]
    assert p["dimensions"] == [7, 2, 2] and p["blockCount"] == 10 and p["sizeClass"] == "small"
    assert p["distinctTypes"] == 5 and p["complexity"] == "simple"
    assert c.get("/api/me", headers=h).json()["usedBytes"] == used + p["size"]  # the snapshot counts
    snap = convert.read(nbt.loads(c.get(f"/api/board/{p['id']}/file").content)[1], "slopmatic", "x")
    assert_same_content(snap, convert.read(nbt.loads(body)[1], "schem", "x"))
    anon = TestClient(c.app)
    e = anon.get(f"/api/board/{p['id']}/export?format=schem")
    assert e.status_code == 200 and 'filename="Castle.schem"' in e.headers["content-disposition"]
    model = anon.get(f"/api/board/{p['id']}/model")
    assert model.status_code == 200 and model.json()["size"] == [7, 2, 2]
    assert '<option value="schem">.schem</option>' in anon.get(f"/board/{p['id']}").text
    # a new version uploaded directly as .schem
    r = c.post(f"/api/board/{p['id']}/versions?filename=v2.schem&label=Second", content=schem_bytes(), headers=h)
    assert r.status_code == 201 and r.json()["filename"] == "v2.slopmatic" and r.json()["blockCount"] == 8
    # modded .schem files are refused on the board too
    modded = synthetic()
    modded.regions[0].palette[2] = state(OLD_DV, "mekanism:pipe")
    r = c.post(f"/api/board/{p['id']}/versions?filename=m.schem", content=nbt.dumps(convert.write(modded, "schem")), headers=h)
    assert r.status_code == 400 and r.json()["code"] == "modded"


# ------------------------------------------------------------------ hover cards


def card(c, uuid, h=None, status=200):
    r = c.get(f"/api/users/{uuid}/card", headers=h or {})
    assert r.status_code == status, r.text
    return r


def test_user_card(gated, ppl):
    c, a, b = gated, ppl.alice, ppl.bob
    for name in ("a.litematic", "b.litematic"):
        upload(c, a.h, name)
    upload(c, b.h, "b.litematic")
    make_post(c, a.h, title="Public")
    make_post(c, a.h, path="b.litematic", title="Unlisted", visibility="unlisted")
    make_post(c, a.h, title="Private", visibility="private")
    hidden = make_post(c, a.h, title="Hidden")
    assert c.post(f"/api/admin/posts/{hidden['id']}/hide", headers=ppl.admin.h, json={"hidden": True}).status_code == 200
    post_json(c, b.h, path="b.litematic", title="Together", coauthors=[{"player": "Alice"}, {"name": "Free Text"}])
    tag = set_name(c, b, name="Alice")["tag"]  # Bob takes Alice's name: tagged
    set_name(c, a, name="The Builder")

    r = card(c, a.uuid)  # logged out is fine: the board is public
    joined = c.app.state.store.db.execute("SELECT created FROM users WHERE uuid=?", (a.uuid,)).fetchone()[0]
    assert r.json() == {"uuid": a.uuid, "name": "Alice", "displayName": "The Builder", "tag": None, "badges": [],
                        "avatarVersion": 0, "contributions": 2, "joined": joined * 1000}  # Public and Together
    assert r.headers["cache-control"] == f"private, max-age={USER_CARD_MAX_AGE}" and "Cookie" in r.headers["vary"]
    assert card(c, a.uuid.replace("-", "").upper()).json()["uuid"] == a.uuid
    # the tag only for admins
    assert card(c, b.uuid).json()["tag"] is None
    assert card(c, b.uuid, a.h).json()["tag"] is None
    assert card(c, b.uuid, ppl.admin.h).json()["tag"] == tag
    assert card(ppl.admin.cookie_client(c.app), b.uuid).json()["tag"] == tag
    assert card(c, b.uuid).json()["contributions"] == 1
    # badges and pictures follow
    assert c.put(f"/api/admin/users/{a.uuid}/badges/verified", headers=ppl.admin.h).status_code == 200
    assert c.put("/api/account/avatar", headers=a.h, content=jpeg(256, 256)).status_code == 200
    got = card(c, a.uuid).json()
    assert got["badges"] == ["verified"] and got["avatarVersion"] == 1
    # unhiding counts the post again; private ones never count
    c.post(f"/api/admin/posts/{hidden['id']}/hide", headers=ppl.admin.h, json={"hidden": False})
    assert card(c, a.uuid).json()["contributions"] == 3
    # a whitelisted player who never logged in, an unknown uuid, garbage: 404
    store = c.app.state.store
    store.whitelist_add("0f0f0f0f-0000-0000-0000-000000000000", "Ghost", "manual", None)
    for bad in ("0f0f0f0f-0000-0000-0000-000000000000", "12345678-1234-1234-1234-123456789abc", "nope"):
        assert card(c, bad, status=404).json() == {"error": "no such user"}
    # a bad token is still a 401, as for the other optional-login reads
    assert card(c, a.uuid, {"Authorization": "Bearer nope"}, status=401)


def test_names_carry_hover_cards(gated, ppl, mojang):
    c, a, b = gated, ppl.alice, ppl.bob
    upload(c, a.h, "a.litematic")
    p = post_json(c, a.h, title="Shared", coauthors=[{"player": "Bob"}, {"name": "Free Text"}])
    anon = TestClient(c.app)
    board = anon.get("/board").text
    assert f'<a class="author-link" href="/u/{a.uuid}" data-user="{a.uuid}">Alice</a>' in board
    page = anon.get(f"/board/{p['id']}").text
    assert f'href="/u/{a.uuid}" data-user="{a.uuid}"><strong>Alice</strong>' in page
    assert f'<a href="/u/{b.uuid}" data-user="{b.uuid}">Bob</a>' in page and "<span>Free Text</span>" in page  # no card
    assert f'by <a href="/u/{a.uuid}" data-user="{a.uuid}"><strong>Alice</strong></a>' in anon.get(f"/board?author={a.uuid}").text
    # every page carries the card's (empty) box and its script, and no inline script
    for html in (board, page, anon.get(f"/u/{a.uuid}").text):
        assert '<div class="ucard" id="user-card" role="tooltip" hidden data-badges="' in html
        assert '<script src="/static/usercard.js?v=' in html
        assert not re.search(r"<script(?![^>]*\bsrc=)[^>]*>", html)
    labels = re.search(r'data-badges="([^"]*)"', board).group(1)
    assert json.loads(labels.replace("&#34;", '"')) == {"verified": "Verified", "mod": "Mod", "dev": "Dev"}
    js = anon.get("/static/usercard.js")
    assert js.status_code == 200 and "/card" in js.text
    # "My files": the author of a saved post
    assert c.post(f"/api/board/{p['id']}/save", headers=b.h).status_code == 201
    files = b.cookie_client(c.app).get("/").text
    assert f'by <span class="uname" data-user="{a.uuid}" tabindex="0">Alice</span>' in files
    # the admin lists, and feedback from a logged-in player
    assert c.post("/api/feedback", headers=b.h, json={"type": "bug", "message": "It broke"}).status_code in (200, 201)
    admin = ppl.admin.cookie_client(c.app)
    assert f'data-user="{a.uuid}">Alice</a>' in admin.get("/admin?tab=users").text
    assert f'<span class="uname" data-user="{b.uuid}" tabindex="0">Bob</span>' in admin.get("/admin/feedback").text
    wl = admin.get("/admin?tab=whitelist").text
    assert f'<span class="uname" data-user="{a.uuid}" tabindex="0">Alice</span>' in wl
    c.app.state.store.whitelist_add("0f0f0f0f-0000-0000-0000-000000000000", "Ghost", "manual", None)
    assert 'data-user="0f0f0f0f-0000-0000-0000-000000000000"' not in admin.get("/admin?tab=whitelist").text  # no account


# ------------------------------------------------------------------ pictures: WebP, and the crop step


def webp(width, height, kind="VP8X"):
    if kind == "VP8X":
        body = b"\x10\x00\x00\x00" + (width - 1).to_bytes(3, "little") + (height - 1).to_bytes(3, "little")
    elif kind == "VP8L":
        body = b"\x2f" + ((width - 1) | (height - 1) << 14 | 1 << 28).to_bytes(4, "little") + b"\x00" * 5
    else:  # a lossy key frame
        body = b"\x50\x02\x00" + b"\x9d\x01\x2a" + struct.pack("<HH", width, height)
    chunk = kind.encode() + struct.pack("<I", len(body)) + body
    return b"RIFF" + struct.pack("<I", 4 + len(chunk)) + b"WEBP" + chunk


def test_webp_size():
    for kind, most in (("VP8X", 1 << 24), ("VP8L", 1 << 14), ("VP8 ", (1 << 14) - 1)):
        assert images.webp_size(webp(256, 200, kind)) == (256, 200), kind
        assert images.webp_size(webp(1, most, kind)) == (1, most), kind
    assert images.webp_size(webp(256, 256)[:29]) is None  # too short
    assert images.webp_size(b"RIFF\x00\x00\x00\x00WEBPALPH" + b"\x00" * 20) is None  # no image chunk first
    lossy = bytearray(webp(10, 10, "VP8 "))
    lossy[20] |= 1  # not a key frame
    assert images.webp_size(bytes(lossy)) is None
    lossless = bytearray(webp(10, 10, "VP8L"))
    lossless[20] = 0
    assert images.webp_size(bytes(lossless)) is None
    assert images.webp_size(PNG) is None and images.png_size(webp(5, 5)) is None


def test_webp_avatar(gated, ppl):
    c, h, uuid = gated, ppl.alice.h, ppl.alice.uuid
    pic = webp(256, 256, "VP8L")
    r = c.put("/api/account/avatar", headers=h, content=pic)
    assert r.status_code == 200 and r.json() == {"avatar": f"http://127.0.0.1:8095/avatar/{uuid}?v=1", "custom": True}
    got = c.get(f"/avatar/{uuid}")
    assert got.content == pic and got.headers["content-type"] == "image/webp"
    folder = c.app.state.store.root / "users" / uuid
    assert [p.name for p in folder.glob("avatar.*")] == ["avatar.webp"]
    # a PNG replaces it, and the WebP file goes
    png = images.encode_png(256, 256, [(1, 2, 3, 255)] * 65536)
    assert c.put("/api/account/avatar", headers=h, content=png).status_code == 200
    assert [p.name for p in folder.glob("avatar.*")] == ["avatar.png"]
    assert c.put("/api/account/avatar", headers=h, content=webp(256, 256)).status_code == 200
    assert c.delete("/api/account/avatar", headers=h).json()["custom"] is False
    assert not any(folder.glob("avatar.*"))
    # the same limits as for PNG and JPEG
    r = c.put("/api/account/avatar", headers=h, content=webp(513, 100, "VP8 "))
    assert r.status_code == 400 and "513 x 100" in r.json()["error"]
    r = c.put("/api/account/avatar", headers=h, content=b"RIFF\x00\x00\x00\x00WEBPVP8Z" + b"\x00" * 30)
    assert r.status_code == 400 and "WebP" in r.json()["error"]
    assert c.put("/api/account/avatar", headers=h, content=webp(9, 9) + b"\x00" * (256 * 1024)).status_code == 413
    not_whitelisted(c.put("/api/account/avatar", headers=ppl.outsider.h, content=pic))


def test_account_page_has_the_crop_step(gated, ppl):
    html = ppl.alice.cookie_client(gated.app).get("/account").text
    assert 'accept="image/png,image/jpeg,image/webp,image/gif"' in html
    assert '<div class="crop" data-crop hidden>' in html and 'data-crop-preview width="256" height="256"' in html
    for fmt in ("image/png", "image/jpeg", "image/webp"):
        assert f'name="crop-format" value="{fmt}"' in html
    assert 'data-crop-quality min="0.3" max="1" step="0.01" value="0.85"' in html
    assert '<script src="/static/avatar.js?v=' in html and not re.search(r"<script(?![^>]*\bsrc=)[^>]*>", html)
    js = TestClient(gated.app).get("/static/avatar.js").text
    assert "toBlob" in js and "/api/account/avatar" in js and "blob:" not in CSP  # pictures load as data: URLs
