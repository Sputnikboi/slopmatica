"""0.10.0: My files shows what is on the board, My listings, favourites, saved board builds as references that cost the
saver nothing, the whole-site storage cap and the admin's Site storage tab, bug reports with a log and a schematic
attached, the in-game build browser's board API (search, sort by downloads, ?limit=), and the schema 6 -> 7 migration."""

import gzip
import hashlib
import json
import os
import re
import sqlite3
import time

import pytest
from fastapi.testclient import TestClient

from slopmatica_cloud import __version__, images
from slopmatica_cloud.app import (
    FEEDBACK_ATTACHMENT_DAYS, FEEDBACK_IP_LIMIT, MAX_BOARD_LIMIT, MAX_FEEDBACK_LOG, MAX_FEEDBACK_SCHEMATIC, MIGRATIONS,
    SCHEMA_VERSION, Settings, sha256_hex,
)

from conftest import PNG, bearer, gz
from test_board import CSRF, Who, blob, make_post, schem, upload
from test_v070 import ADMIN, PLAYER, columns, not_whitelisted
from test_v080 import post_json, v5_data_dir, variety

INLINE_SCRIPT = re.compile(r"<script(?![^>]*\bsrc=)[^>]*>")


@pytest.fixture
def gated(make_client):
    return make_client(whitelist_enforced=True)


@pytest.fixture
def ppl(gated, mojang):
    class P:
        admin = Who(gated, mojang, ADMIN)
        alice = Who(gated, mojang, "Alice")
        bob = Who(gated, mojang, "Bob")
        carol = Who(gated, mojang, "Carol")
        outsider = Who(gated, mojang, "Outsider")
    for who in (P.alice, P.bob, P.carol):
        gated.app.state.store.whitelist_add(who.uuid, who.name, "manual", None)
    return P


def files_of(c, h) -> dict[str, dict]:
    r = c.get("/api/files", headers=h)
    assert r.status_code == 200, r.text
    return {f["path"]: f for f in r.json()["files"]}


def used(c, h) -> int:
    return c.get("/api/me", headers=h).json()["usedBytes"]


def test_version_bumped():
    assert tuple(map(int, __version__.split("."))) >= (0, 10, 0) and SCHEMA_VERSION >= 7


def test_settings_from_env():
    assert Settings.from_env({}).site_quota_bytes == 100 * 1024 ** 3
    assert Settings.from_env({"SLOPMATICA_SITE_QUOTA_BYTES": "5000"}).site_quota_bytes == 5000


# ------------------------------------------------------------------ migration 6 -> 7

BOB = "b0b0b0b0-1234-1234-1234-123456789abc"


def v6_data_dir(data_dir):
    """A 0.9.0 data directory: 0.7.0's (a player with house.litematic and the post HousePost1 made from it) plus
    migration 6, the same bytes again as renamed.litematic, a file on no post, and Bob, who saved HousePost1 to his
    cloud with 0.9.0 (a real copy, which then counted toward his quota)."""
    body = v5_data_dir(data_dir)
    files = data_dir / "users" / PLAYER / "files"
    (files / "renamed.litematic").write_bytes(body)
    other = schem(two_regions=True)
    (files / "other.litematic").write_bytes(other)
    saved = data_dir / "users" / BOB / "files" / "board"
    saved.mkdir(parents=True)
    (saved / "House.litematic").write_bytes(body)
    digest, ms, t = hashlib.sha256(body).hexdigest(), int(time.time() * 1000), int(time.time())
    db = sqlite3.connect(data_dir / "cloud.db")
    db.executescript(MIGRATIONS[6])
    db.execute("UPDATE users SET display_name = name, display_key = lower(name)")
    db.execute("INSERT INTO users(uuid, name, is_admin, created, last_login, last_seen, display_name, display_key)"
               " VALUES(?, 'Bob', 0, ?, ?, ?, 'Bob', 'bob')", (BOB, t - 100, t - 100, t - 100))
    db.execute("INSERT INTO tokens VALUES(?,?,?,?,?,NULL)", (sha256_hex("bobtok"), "api", BOB, t, t + 86400))
    db.execute("INSERT INTO files VALUES(?,?,?,?,?,0,NULL,NULL,NULL,NULL)",
               (PLAYER, "renamed.litematic", len(body), digest, ms))
    db.execute("INSERT INTO files VALUES(?,?,?,?,?,0,NULL,NULL,NULL,NULL)",
               (PLAYER, "other.litematic", len(other), hashlib.sha256(other).hexdigest(), ms))
    db.execute("INSERT INTO files VALUES(?,?,?,?,?,0,'HousePost1','House',?,'Player')",
               (BOB, "board/House.litematic", len(body), digest, ms - 5000, PLAYER))
    db.execute("PRAGMA user_version = 6")
    db.commit()
    db.close()
    return body, other


def test_migrates_a_v6_database(make_client, data_dir):
    body, other = v6_data_dir(data_dir)
    c = make_client()
    db = c.app.state.store.db
    assert db.execute("PRAGMA user_version").fetchone()[0] == SCHEMA_VERSION >= 7
    assert columns(db, "files")[-1] == "listing_post"
    assert columns(db, "favorites") == ["post_id", "uuid", "created"]
    assert columns(db, "post_saves") == ["post_id", "uuid", "at"]
    assert columns(db, "feedback_files") == ["feedback_id", "kind", "filename", "size", "sha256", "created"]
    # the files are linked to the post: by the path it was posted from, then by the same bytes; the rest isn't
    player = bearer("playertok")
    mine = files_of(c, player)
    house = {"postId": "HousePost1", "title": "House", "visibility": "public", "hidden": False}
    assert mine["house.litematic"]["listing"] == house and mine["renamed.litematic"]["listing"] == house
    assert mine["other.litematic"]["listing"] is None
    stored = dict(db.execute("SELECT path, listing_post FROM files WHERE uuid=?", (PLAYER,)).fetchall())
    assert stored == {"house.litematic": "HousePost1", "renamed.litematic": "HousePost1", "other.litematic": None}
    # Bob's 0.9.0 copy is a reference now: it costs him nothing (the post counts for its author), and it counts as a save
    bob = bearer("bobtok")
    assert files_of(c, bob)["board/House.litematic"]["reference"] is True
    assert c.get("/api/me", headers=bob).json()["usedBytes"] == 0
    assert c.get("/api/me", headers=player).json()["usedBytes"] == 3 * len(body) + len(other)  # 2 files + post + other
    listing = c.get("/api/listings", headers=player).json()["listings"]
    assert [(p["id"], p["saves"], p["favorites"]) for p in listing] == [("HousePost1", 1, 0)]
    # the new tables take data, and a restart migrates nothing twice
    assert c.post("/api/board/HousePost1/favorite", headers=bob).json() == {"favorites": 1, "favorited": True}
    c2 = make_client()
    assert c2.app.state.store.db.execute("PRAGMA user_version").fetchone()[0] == SCHEMA_VERSION
    assert c2.get("/api/board/HousePost1", headers=bob).json()["favorited"] is True
    assert files_of(c2, player)["house.litematic"]["listing"] == house


def test_migration_7_is_one_transaction(make_client, data_dir, monkeypatch):
    v6_data_dir(data_dir)
    monkeypatch.setitem(MIGRATIONS, 7, MIGRATIONS[7] + "\nCREATE TABLE favorites (x);")  # fails at the end
    with pytest.raises(sqlite3.OperationalError):
        make_client()
    db = sqlite3.connect(data_dir / "cloud.db")
    assert db.execute("PRAGMA user_version").fetchone()[0] == 6
    assert "listing_post" not in columns(db, "files")
    assert db.execute("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('favorites', 'post_saves', 'feedback_files')").fetchone()[0] == 0
    db.close()
    monkeypatch.undo()
    assert make_client().app.state.store.db.execute("PRAGMA user_version").fetchone()[0] == SCHEMA_VERSION


# ------------------------------------------------------------------ My files: what is on the board


def test_files_show_their_listing(gated, ppl):
    c, a, b = gated, ppl.alice, ppl.bob
    upload(c, a.h, "builds/a.litematic")
    upload(c, a.h, "loose.litematic", schem(two_regions=True))
    assert all(f["listing"] is None for f in files_of(c, a.h).values())
    p = post_json(c, a.h, path="builds/a.litematic", title="Tower")
    want = {"postId": p["id"], "title": "Tower", "visibility": "public", "hidden": False}
    assert files_of(c, a.h)["builds/a.litematic"]["listing"] == want
    assert c.put("/api/files/builds/a.litematic", headers=a.h, content=schem()).json()["listing"] == want  # PUT answers too
    # the link follows renames and content changes (the post can still be updated from it), and the post's state
    assert c.post("/api/files/move", headers=a.h, json={"from": "builds/a.litematic", "to": "t.litematic"}).status_code == 200
    upload(c, a.h, "t.litematic", schem(two_regions=True))
    c.patch(f"/api/board/{p['id']}", headers=a.h, json={"visibility": "unlisted", "title": "Tall tower"})
    assert files_of(c, a.h)["t.litematic"]["listing"] == {**want, "visibility": "unlisted", "title": "Tall tower"}
    c.post(f"/api/admin/posts/{p['id']}/hide", headers=ppl.admin.h, json={"hidden": True})
    assert files_of(c, a.h)["t.litematic"]["listing"]["hidden"] is True
    # a version added from another file links that one too
    r = c.post(f"/api/board/{p['id']}/versions", headers=a.h, json={"path": "loose.litematic"})
    assert r.status_code == 201
    assert files_of(c, a.h)["loose.litematic"]["listing"]["postId"] == p["id"]
    # .slopmatic is posted byte for byte: another file with the same bytes (any version) is recognised by its content
    body = schem("slopmatic")
    upload(c, a.h, "s.slopmatic", body)
    q = post_json(c, a.h, path="s.slopmatic", title="Slop")
    upload(c, a.h, "copy.slopmatic", body)
    assert files_of(c, a.h)["copy.slopmatic"]["listing"]["postId"] == q["id"]
    c.post(f"/api/board/{q['id']}/versions?filename=v2.slopmatic", headers=a.h, content=schem("slopmatic", two_regions=True))
    assert files_of(c, a.h)["copy.slopmatic"]["listing"]["postId"] == q["id"]  # an earlier version's bytes
    # never someone else's post
    upload(c, b.h, "same.slopmatic", body)
    assert files_of(c, b.h)["same.slopmatic"]["listing"] is None
    # the files page: a badge per file, linking to the post
    html = a.cookie_client(c.app).get("/").text
    assert html.count(f'data-listing="{q["id"]}"') == 2 and f'href="/board/{q["id"]}"' in html
    assert "On the board &middot; Public" in html and "On the board &middot; Unlisted &middot; hidden" in html
    # deleting the post ends it
    assert c.delete(f"/api/board/{q['id']}", headers=a.h).status_code == 204
    assert files_of(c, a.h)["copy.slopmatic"]["listing"] is None and files_of(c, a.h)["s.slopmatic"]["listing"] is None


# ------------------------------------------------------------------ My listings


def test_listings_stats(gated, ppl):
    c, a, b, d = gated, ppl.alice, ppl.bob, ppl.carol
    upload(c, a.h, "a.litematic")
    upload(c, a.h, "b.litematic", schem(two_regions=True))
    first = post_json(c, a.h, path="a.litematic", title="Alpha")
    second = post_json(c, a.h, path="b.litematic", title="beta", visibility="unlisted")
    c.post(f"/api/board/{first['id']}/versions", headers=a.h, json={"path": "b.litematic"})
    # Bob: upvote, download, favourite, save twice (one save counted); Carol: downvote, favourite; Alice's own don't count
    c.post(f"/api/board/{first['id']}/vote", headers=b.h, json={"value": 1})
    c.post(f"/api/board/{first['id']}/vote", headers=d.h, json={"value": -1})
    c.post(f"/api/board/{second['id']}/vote", headers=d.h, json={"value": 1})
    assert c.get(f"/api/board/{second['id']}/file", headers=b.h).status_code == 200
    for who in (b, d):
        c.post(f"/api/board/{first['id']}/favorite", headers=who.h)
    for _ in range(2):
        assert c.post(f"/api/board/{first['id']}/save", headers=b.h).status_code == 201
    c.post(f"/api/board/{first['id']}/save", headers=a.h)
    r = c.get("/api/listings", headers=a.h)
    assert r.status_code == 200 and r.json()["sort"] == "created" and r.json()["order"] == "desc"
    got = {p["title"]: p for p in r.json()["listings"]}
    assert [p["title"] for p in r.json()["listings"]] == ["beta", "Alpha"]  # newest first
    alpha, beta = got["Alpha"], got["beta"]
    assert (alpha["ups"], alpha["downs"], alpha["score"], alpha["downloads"], alpha["favorites"], alpha["saves"],
            alpha["versionCount"]) == (1, 1, 0, 1, 2, 1, 2)
    assert (beta["visibility"], beta["hidden"], beta["score"], beta["downloads"], beta["favorites"], beta["saves"]) == \
        ("unlisted", False, 1, 1, 0, 0)
    assert alpha["page"] == f"http://127.0.0.1:8095/board/{first['id']}"
    assert alpha["editPage"] == alpha["page"] + "#manage"
    assert alpha["bytes"] == alpha["size"] + c.get(f"/api/board/{first['id']}/versions").json()["versions"][1]["size"]
    assert alpha["created"] <= alpha["updated"] and alpha["favorited"] is False
    # sortable by each number, either way
    def order(**q):
        r = c.get("/api/listings", headers=a.h, params=q)
        assert r.status_code == 200, r.text
        return [p["title"] for p in r.json()["listings"]]
    assert order(sort="favorites") == ["Alpha", "beta"] and order(sort="favorites", order="asc") == ["beta", "Alpha"]
    assert order(sort="score") == ["beta", "Alpha"] and order(sort="downs") == ["Alpha", "beta"]
    assert order(sort="title") == ["Alpha", "beta"] and order(sort="title", order="desc") == ["beta", "Alpha"]
    assert order(sort="versions") == ["Alpha", "beta"] and order(sort="size") == ["Alpha", "beta"]
    assert order(sort="visibility") == ["Alpha", "beta"] and order(sort="updated")[0] == "Alpha"
    for bad in ({"sort": "nope"}, {"order": "up"}):
        assert c.get("/api/listings", headers=a.h, params=bad).status_code == 400
    # hidden posts are listed for their author, marked
    c.post(f"/api/admin/posts/{second['id']}/hide", headers=ppl.admin.h, json={"hidden": True, "reason": "dup"})
    assert {p["title"]: p["hidden"] for p in c.get("/api/listings", headers=a.h).json()["listings"]} == {"Alpha": False, "beta": True}
    # members only (the gate), logged in
    not_whitelisted(c.get("/api/listings", headers=ppl.outsider.h))
    assert TestClient(c.app).get("/api/listings").status_code == 401
    assert c.get("/api/listings", headers=b.h).json()["listings"] == []
    # the page: sortable columns, links to view and edit, cards on phones (data-label), no inline script
    web = a.cookie_client(c.app)
    html = web.get("/listings?sort=saves").text
    assert '<a class="tab on" href="/listings" aria-current="page">My listings <span class="count">2</span></a>' in html
    assert 'aria-sort="descending"><a class="sort-link on" href="/listings?sort=saves&amp;order=asc">Saves' in html
    assert 'href="/listings?sort=title&amp;order=asc">Title' in html
    assert f'href="/board/{first["id"]}#manage">Edit</a>' in html and 'data-label="Favourites">2</td>' in html
    assert html.index("Alpha") < html.index("beta") and 'class="badge mod-hidden"' in html
    assert not INLINE_SCRIPT.search(html) and '<option value="saves" selected>' in html
    assert web.get("/listings?sort=bogus&order=x").status_code == 200  # the page falls back instead
    assert 'id="manage"' in web.get(f"/board/{first['id']}").text
    assert 'href="/listings">My listings' in web.get("/").text
    assert TestClient(c.app).get("/listings", follow_redirects=False).headers["location"] == "/"
    assert ppl.outsider.cookie_client(c.app).get("/listings", follow_redirects=False).headers["location"] == "/apply"


# ------------------------------------------------------------------ favourites


def test_favourites(gated, ppl):
    c, a, b, d = gated, ppl.alice, ppl.bob, ppl.carol
    upload(c, a.h, "a.litematic", thumb=True)
    pub = post_json(c, a.h, title="Public")
    unl = post_json(c, a.h, title="Unlisted", visibility="unlisted")
    priv = post_json(c, a.h, title="Private", visibility="private", allowed=["Bob"])
    fav = lambda who, pid, method="post": getattr(c, method)(f"/api/board/{pid}/favorite", headers=who.h)  # noqa: E731
    r = fav(b, pub["id"])
    assert r.status_code == 200 and r.json() == {"favorites": 1, "favorited": True}
    assert fav(b, pub["id"]).json() == {"favorites": 1, "favorited": True}  # once per account
    assert fav(d, pub["id"]).json() == {"favorites": 2, "favorited": True}
    assert fav(b, unl["id"]).status_code == 200
    # private posts: not by others, not even those on the access list; the author may
    r = fav(b, priv["id"])
    assert r.status_code == 403 and r.json()["code"] == "private"
    assert fav(d, priv["id"]).status_code == 404  # can't see it at all
    assert fav(a, priv["id"]).json() == {"favorites": 1, "favorited": True}
    # in post objects: the count, and for the caller whether they did
    assert c.get(f"/api/board/{pub['id']}", headers=b.h).json()["favorited"] is True
    assert c.get(f"/api/board/{pub['id']}", headers=a.h).json()["favorited"] is False
    anon = TestClient(c.app).get(f"/api/board/{pub['id']}").json()
    assert anon["favorites"] == 2 and anon["favorited"] is None
    board = {p["id"]: p for p in c.get("/api/board", headers=b.h).json()["posts"]}
    assert board[pub["id"]]["favorited"] is True and board[pub["id"]]["favorites"] == 2
    # the list: most recent first, post objects
    listed = c.get("/api/favorites", headers=b.h).json()["posts"]
    assert [p["id"] for p in listed] == [unl["id"], pub["id"]] and all(p["favorited"] for p in listed)
    assert [p["id"] for p in c.get("/api/favorites", headers=a.h).json()["posts"]] == [priv["id"]]
    # a post that becomes private (or hidden) drops out of others' lists, and comes back if it is opened again
    c.patch(f"/api/board/{unl['id']}", headers=a.h, json={"visibility": "private"})
    assert [p["id"] for p in c.get("/api/favorites", headers=b.h).json()["posts"]] == [pub["id"]]
    c.patch(f"/api/board/{unl['id']}", headers=a.h, json={"visibility": "unlisted"})
    assert len(c.get("/api/favorites", headers=b.h).json()["posts"]) == 2
    c.post(f"/api/admin/posts/{unl['id']}/hide", headers=ppl.admin.h, json={"hidden": True})
    assert [p["id"] for p in c.get("/api/favorites", headers=b.h).json()["posts"]] == [pub["id"]]
    assert fav(d, unl["id"]).status_code == 404  # hidden: invisible to others
    # ...but can still be taken off the list
    assert fav(b, unl["id"], "delete").json() == {"favorites": 0, "favorited": False}
    c.post(f"/api/admin/posts/{unl['id']}/hide", headers=ppl.admin.h, json={"hidden": False})
    assert len(c.get("/api/favorites", headers=b.h).json()["posts"]) == 1
    # unfavourite
    assert fav(d, pub["id"], "delete").json() == {"favorites": 1, "favorited": False}
    assert fav(d, pub["id"], "delete").json() == {"favorites": 1, "favorited": False}
    assert fav(d, "NoSuchPost", "delete").status_code == 404
    # deleted posts are gone, with their favourites
    c.delete(f"/api/board/{pub['id']}", headers=a.h)
    assert c.get("/api/favorites", headers=b.h).json()["posts"] == []
    assert c.app.state.store.db.execute("SELECT COUNT(*) FROM favorites WHERE post_id=?", (pub["id"],)).fetchone()[0] == 0
    # logged in and whitelisted; cookie requests need the header
    again = post_json(c, a.h, title="Again")
    assert TestClient(c.app).post(f"/api/board/{again['id']}/favorite").status_code == 401
    not_whitelisted(fav(ppl.outsider, again["id"]))
    not_whitelisted(c.get("/api/favorites", headers=ppl.outsider.h))
    web = b.cookie_client(c.app)
    assert web.post(f"/api/board/{again['id']}/favorite").status_code == 403
    assert web.post(f"/api/board/{again['id']}/favorite", headers=CSRF).json()["favorited"] is True


def test_favourite_hearts_on_the_pages(gated, ppl):
    c, a, b = gated, ppl.alice, ppl.bob
    upload(c, a.h, "a.litematic", thumb=True)
    p = post_json(c, a.h, title="Heart me")
    c.post(f"/api/board/{p['id']}/favorite", headers=b.h)
    web = b.cookie_client(c.app)
    on = f'<button type="button" class="fav-btn on" data-fav="{p["id"]}" aria-pressed="true" aria-label="Favourite"'
    for url in ("/board", f"/board/{p['id']}", f"/u/{a.uuid}"):
        html = web.get(url).text
        assert on in html, url
        assert '<script src="/static/board.js?v=' in html and not INLINE_SCRIPT.search(html), url
    off = f'class="fav-btn" data-fav="{p["id"]}" aria-pressed="false"'
    assert off in a.cookie_client(c.app).get("/board").text
    # logged out or without access: only the count
    for viewer in (TestClient(c.app), ppl.outsider.cookie_client(c.app)):
        html = viewer.get("/board").text
        assert 'data-fav="' not in html and '<span class="fav-count" title="Favourites"><span aria-hidden="true">&#9829;</span> 1</span>' in html
    # My files: the Favourites section, with download and "Add to my cloud"
    files = web.get("/").text
    assert 'id="favourites"' in files and 'Favourites <span class="count">1</span>' in files
    assert f'href="/api/board/{p["id"]}/file" download="a.slopmatic"' in files and f'data-fav-save="{p["id"]}"' in files
    assert "Nothing yet." in a.cookie_client(c.app).get("/").text
    js = TestClient(c.app).get("/static/board.js").text
    assert '"/favorite"' in js and "data-fav-save" in js


# ------------------------------------------------------------------ saved board builds are references


def test_saved_builds_cost_the_saver_nothing(make_client, mojang):
    body = schem("slopmatic")
    a = len(body)
    c = make_client(quota_bytes=3 * a)
    alice, bob = Who(c, mojang, "Alice"), Who(c, mojang, "Bob")
    store = c.app.state.store
    upload(c, alice.h, "a.slopmatic", body, thumb=True)
    p = make_post(c, alice.h, "a.slopmatic")
    assert used(c, alice.h) == 2 * a  # the file and the post (unchanged)
    upload(c, bob.h, "pad.litematic", blob(3 * a))  # Bob is full
    # still, he can add builds from the board (the in-game browser uses the bearer token, as here)
    for _ in range(2):
        r = c.post(f"/api/board/{p['id']}/save", headers=bob.h)
        assert r.status_code == 201 and r.json()["reference"] is True and r.json()["size"] == a
    saved = files_of(c, bob.h)
    assert set(saved) == {"pad.litematic", "board/My build.slopmatic", "board/My build (2).slopmatic"}
    assert used(c, bob.h) == 3 * a and used(c, alice.h) == 2 * a
    assert c.get("/api/files", headers=bob.h).json()["usedBytes"] == 3 * a
    # on disk it is the post's snapshot itself (a hard link), not a second copy
    snap = store.posts_root / p["id"] / "a.slopmatic"
    mine = store.file_path(bob.uuid, "board/My build.slopmatic")
    assert os.stat(mine).st_ino == os.stat(snap).st_ino and mine.read_bytes() == body
    assert c.get("/api/files/board/My build.slopmatic", headers=bob.h).content == body
    # the same bytes again keep it a (linked) reference; a move too
    assert c.put("/api/files/board/My build (2).slopmatic", headers=bob.h, content=body).json()["reference"] is True
    assert os.stat(store.file_path(bob.uuid, "board/My build (2).slopmatic")).st_ino == os.stat(snap).st_ino
    assert c.post("/api/files/move", headers=bob.h, json={"from": "board/My build (2).slopmatic", "to": "kept.slopmatic"}).json()["reference"] is True
    assert used(c, bob.h) == 3 * a
    # changing it makes it his: then it counts, and the quota sees it
    changed = schem("slopmatic", two_regions=True)
    r = c.put("/api/files/kept.slopmatic", headers=bob.h, content=changed)
    assert r.status_code == 413 and r.json()["usedBytes"] == 3 * a
    c.delete("/api/files/pad.litematic", headers=bob.h)
    r = c.put("/api/files/kept.slopmatic", headers=bob.h, content=changed)
    assert r.status_code == 200 and r.json()["reference"] is False and r.json()["source"] is None
    assert used(c, bob.h) == len(changed)
    assert snap.read_bytes() == body  # the post is untouched
    # the author deleting the post: the saved copy stays, and now counts for Bob
    assert c.delete(f"/api/board/{p['id']}", headers=alice.h).status_code == 204
    assert not snap.exists() and used(c, alice.h) == a
    f = files_of(c, bob.h)["board/My build.slopmatic"]
    assert f["reference"] is False and f["source"]["postId"] == p["id"]  # still says where it came from
    assert c.get("/api/files/board/My build.slopmatic", headers=bob.h).content == body
    assert used(c, bob.h) == len(changed) + a
    html = bob.cookie_client(c.app).get("/").text
    assert "(not counted)" not in html


def test_reference_follows_the_versions(make_client, mojang):
    v1, v2 = schem("slopmatic"), schem("slopmatic", two_regions=True)
    c = make_client()
    alice, bob = Who(c, mojang, "Alice"), Who(c, mojang, "Bob")
    upload(c, alice.h, "a.slopmatic", v1)
    p = make_post(c, alice.h, "a.slopmatic")
    assert c.post(f"/api/board/{p['id']}/save", headers=bob.h).json()["reference"] is True
    # a new version: v1 is still one of the post's versions, so still free
    c.post(f"/api/board/{p['id']}/versions?filename=v2.slopmatic", headers=alice.h, content=v2)
    assert used(c, bob.h) == 0
    r = c.post(f"/api/board/{p['id']}/save?version=1", headers=bob.h)
    assert r.status_code == 201 and r.json()["sha256"] == hashlib.sha256(v1).hexdigest() and r.json()["reference"]
    assert c.post(f"/api/board/{p['id']}/save", headers=bob.h).json()["sha256"] == hashlib.sha256(v2).hexdigest()
    assert used(c, bob.h) == 0
    # the author deletes version 1: Bob's two v1 copies are his now
    assert c.delete(f"/api/board/{p['id']}/versions/1", headers=alice.h).status_code == 204
    assert used(c, bob.h) == 2 * len(v1)
    refs = {path: f["reference"] for path, f in files_of(c, bob.h).items()}
    assert sorted(refs.values()) == [False, False, True]
    # the page says which files don't count
    html = bob.cookie_client(c.app).get("/").text
    assert html.count("(not counted)") == 1 and "1 build added from the board doesn’t count" in html
    # admins' user list follows the same accounting
    admin = Who(c, mojang, ADMIN)
    row = c.get("/api/admin/users", headers=admin.h, params={"q": "Bob"}).json()["users"][0]
    assert row["usedBytes"] == 2 * len(v1)


# ------------------------------------------------------------------ the site's storage cap


def cap_at(c, room):
    """Set the site cap to what the data directory holds now plus room bytes."""
    store = c.app.state.store
    store.settings.site_quota_bytes = store.site_used(fresh=True) + room


def site_full(r):
    assert r.status_code == 507, r.text
    assert r.json() == {"error": r.json()["error"], "code": "site_full"} and "full" in r.json()["error"]


def test_site_cap(gated, ppl):
    c, a = gated, ppl.alice
    upload(c, a.h, "a.litematic")
    p = post_json(c, a.h, title="Before")
    # (room for the database's write-ahead log, which grows by a few pages with every write and counts too)
    cap_at(c, 300_000)
    site_full(c.put("/api/files/big.litematic", headers=a.h, content=blob(400_000)))
    site_full(c.put("/api/files/big.litematic", headers=ppl.admin.h, content=blob(400_000)))  # admins too
    assert not files_of(c, a.h).get("big.litematic")
    # within the cap it works; the estimate errs high, so a full-looking site is measured again before refusing
    for _ in range(3):
        assert c.put("/api/files/mid.litematic", headers=a.h, content=blob(200_000)).status_code in (200, 201)
        c.delete("/api/files/mid.litematic", headers=a.h)
    # every kind of upload
    cap_at(c, 100)
    site_full(c.put("/api/files/x.litematic", headers=a.h, content=blob(1000)))
    site_full(c.put("/api/thumbnails/a.litematic", headers=a.h, content=PNG + b"\x00" * 1000))
    site_full(c.post("/api/board", headers=a.h, json={"path": "a.litematic", "title": "T", "visibility": "public"}))
    site_full(c.post(f"/api/board/{p['id']}/versions", headers=a.h, json={"path": "a.litematic"}))
    site_full(c.put("/api/account/avatar", headers=a.h, content=images.encode_png(64, 64, [(9, 9, 9, 255)] * 4096)))
    jar = {"jar": ("m.jar", b"PK\x03\x04" + b"\x00" * 1000, "application/java-archive")}
    site_full(c.post("/api/releases", headers=ppl.admin.h, files=jar, data={"version": "1.0.0", "mc": "26.3"}))
    site_full(c.post("/api/feedback", headers=a.h, data={"type": "bug", "message": "x"},
                     files={"log": ("latest.log", b"line\n" * 300, "text/plain")}))
    # what doesn't add bytes still works: deleting, plain feedback, saving a post (a hard link)
    assert c.post("/api/feedback", headers=a.h, json={"type": "bug", "message": "full?"}).status_code == 201
    assert c.post(f"/api/board/{p['id']}/save", headers=ppl.bob.h).status_code == 201
    assert c.delete("/api/files/a.litematic", headers=a.h).status_code == 204


def test_admin_storage_view(gated, ppl):
    c, a, b = gated, ppl.alice, ppl.bob
    store = c.app.state.store
    body = variety(40)
    upload(c, a.h, "big.litematic", body, thumb=True)
    upload(c, a.h, "small.litematic")
    p = post_json(c, a.h, path="big.litematic", title="Biggest")
    upload(c, b.h, "mine.litematic")
    upload(c, a.h, "huge.litematic", blob(60_000))
    before = {x["key"]: x for x in c.get("/api/admin/storage", headers=ppl.admin.h).json()["categories"]}
    assert c.post(f"/api/board/{p['id']}/save", headers=b.h).status_code == 201
    r = c.get("/api/admin/storage", headers=ppl.admin.h)
    assert r.status_code == 200
    st = r.json()
    assert st["capBytes"] == 100 * 1024 ** 3 and st["freeBytes"] == st["capBytes"] - st["usedBytes"]
    cats = {x["key"]: x for x in st["categories"]}
    assert sum(x["bytes"] for x in cats.values()) == st["usedBytes"] and cats["database"]["bytes"] > 0
    assert cats["posts"]["bytes"] == p["size"] and cats["post_thumbs"]["files"] == 1
    assert cats["thumbs"]["files"] == 2  # Alice's, and the copy of the post's that came with Bob's save
    # Bob's saved copy is the post's snapshot on disk: it doesn't add to the files
    assert cats["files"] == before["files"] and cats["files"]["files"] == 4
    users = {u["name"]: u for u in st["topUsers"]}
    assert users["Alice"]["usedBytes"] == users["Alice"]["fileBytes"] + users["Alice"]["postBytes"] > 0
    bob_file = files_of(c, b.h)["mine.litematic"]["size"]
    assert (users["Bob"]["usedBytes"], users["Bob"]["fileBytes"], users["Bob"]["referenceBytes"]) == (bob_file, bob_file, p["size"])
    assert st["largestPosts"][0]["id"] == p["id"] and st["largestPosts"][0]["page"].endswith(f"/board/{p['id']}")
    biggest = st["largestFiles"][0]
    assert biggest["path"] == "huge.litematic" and biggest["owner"]["uuid"] == a.uuid and biggest["reference"] is False
    assert any(f["reference"] and f["sourcePost"] == p["id"] for f in st["largestFiles"])
    # admins only, like the rest of the admin API and pages
    assert TestClient(c.app).get("/api/admin/storage").status_code == 401
    assert c.get("/api/admin/storage", headers=a.h).json() == {"error": "only admins can do that"}
    for viewer in (TestClient(c.app), a.cookie_client(c.app)):
        assert viewer.get("/admin?tab=disk").status_code == 404
    html = ppl.admin.cookie_client(c.app).get("/admin?tab=disk").text
    assert 'aria-current="page">Site storage' in html and "SLOPMATICA_SITE_QUOTA_BYTES" in html
    assert f'href="/board/{p["id"]}">Biggest</a>' in html and "<code>huge.litematic</code>" in html
    assert 'class="bar-fill" data-pct="' in html and not INLINE_SCRIPT.search(html)
    assert store.site_used(fresh=True) == sum(v[0] for v in store.measure_site().values())


# ------------------------------------------------------------------ bug reports with attachments


def send_report(c, headers, status=201, data=None, files=None):
    r = c.post("/api/feedback", headers=headers, data={"type": "bug", "message": "It crashed", **(data or {})}, files=files)
    assert r.status_code == status, r.text
    return r.json()


LOG = b"[12:00:00] [Render thread/INFO]: Loading Slopmatica 0.17.0\n" * 50


def test_feedback_with_attachments(gated, ppl):
    c, a = gated, ppl.alice
    store = c.app.state.store
    castle = schem("litematic")
    # what the mod sends: multipart with the text fields, latest.log and the selected schematic, by bearer token
    f = send_report(c, a.h, data={"modVersion": "0.17.0", "mcVersion": "26.3", "contact": ""}, files={
        "log": ("latest.log", LOG, "text/plain"), "schematic": ("builds/Castle.litematic", castle, "application/octet-stream")})
    assert (f["type"], f["message"], f["modVersion"], f["mcVersion"], f["contact"]) == ("bug", "It crashed", "0.17.0", "26.3", None)
    assert f["user"] == {"uuid": a.uuid, "name": "Alice"}
    url = f"http://127.0.0.1:8095/api/feedback/{f['id']}/attachments/"
    assert f["attachments"] == [
        {"kind": "log", "filename": "latest.log", "size": len(LOG), "sha256": hashlib.sha256(LOG).hexdigest(), "url": url + "log"},
        {"kind": "schematic", "filename": "Castle.litematic", "size": len(castle),
         "sha256": hashlib.sha256(castle).hexdigest(), "url": url + "schematic"},
    ]
    assert (store.feedback_root / str(f["id"]) / "schematic").read_bytes() == castle
    # gzipped logs; logged out with the header (anonymous); a log sent as a text field; empty file parts are no file
    packed = gzip.compress(LOG)
    anon = TestClient(c.app)
    g = send_report(anon, CSRF, files={"log": ("latest.log", packed, "application/gzip"), "schematic": ("", b"", "x")})
    assert g["user"] is None and [(x["kind"], x["filename"], x["size"]) for x in g["attachments"]] == [("log", "latest.log.gz", len(packed))]
    text_log = send_report(c, a.h, data={"log": "plain text log"}, files={"schematic": ("", b"", "x")})  # multipart
    assert [(x["kind"], x["filename"], x["size"]) for x in text_log["attachments"]] == [("log", "latest.log", 14)]
    assert send_report(anon, {}, status=403)["error"].startswith("missing X-Requested-With")
    assert send_report(c, bearer("nope"), status=401)
    # plain JSON keeps working, and has no attachments
    assert c.post("/api/feedback", headers=a.h, json={"type": "suggestion", "message": "hi"}).json()["attachments"] == []
    assert c.post("/api/feedback", headers=a.h, content=b"{nope").status_code == 400
    # checks
    errors = {
        "log": [(("latest.log", b"\x00\x01binary", "x"), 400), (("l.gz", b"\x1f\x8bnot gzip", "x"), 400),
                (("latest.log", b"x" * (MAX_FEEDBACK_LOG + 1), "x"), 413)],
        "schematic": [(("notes.txt", castle, "x"), 400), (("plain.nbt", b"not gzip", "x"), 400),
                      (("big.schem", b"\x1f\x8b" + b"x" * MAX_FEEDBACK_SCHEMATIC, "x"), 413)],
    }
    for kind, cases in errors.items():
        for part, status in cases:
            r = send_report(c, a.h, status=status, files={kind: part})
            if status == 413:
                assert r["maxBytes"] in (MAX_FEEDBACK_LOG, MAX_FEEDBACK_SCHEMATIC)
    bomb = gzip.compress(b"a" * (65 * 1024 * 1024))  # unpacks past the limit
    send_report(c, a.h, status=400, files={"log": ("latest.log.gz", bomb, "x")})
    assert send_report(c, a.h, status=400, data={"type": "rant"}, files={"log": ("l.log", LOG, "x")})["error"].startswith("type must")
    assert len([d for d in store.feedback_root.iterdir()]) == 3  # nothing left behind by refused ones
    # admins see them, and only admins can download them; always as a download
    items = {x["id"]: x for x in c.get("/api/feedback?status=all", headers=ppl.admin.h).json()["feedback"]}
    assert items[f["id"]]["attachments"] == f["attachments"]
    dl = f"/api/feedback/{f['id']}/attachments/log"
    assert anon.get(dl).status_code == 401 and c.get(dl, headers=a.h).status_code == 403
    r = c.get(dl, headers=ppl.admin.h)
    assert r.status_code == 200 and r.content == LOG and r.headers["content-type"].startswith("text/plain")
    assert r.headers["content-disposition"].startswith("attachment") and 'filename="latest.log"' in r.headers["content-disposition"]
    r = c.get(f"/api/feedback/{f['id']}/attachments/schematic", headers=ppl.admin.h)
    assert r.content == castle and r.headers["content-type"] == "application/octet-stream"
    assert c.get(f"/api/feedback/{g['id']}/attachments/log", headers=ppl.admin.h).content == packed
    for bad in (f"/api/feedback/{f['id']}/attachments/other", f"/api/feedback/{g['id']}/attachments/schematic",
                "/api/feedback/9999/attachments/log"):
        assert c.get(bad, headers=ppl.admin.h).status_code == 404, bad
    web = ppl.admin.cookie_client(c.app)
    html = web.get("/admin/feedback").text
    assert f'href="/api/feedback/{f["id"]}/attachments/schematic" download="Castle.litematic"' in html
    assert f'data-delete-attachments="{f["id"]}"' in html and not INLINE_SCRIPT.search(html)
    # admins delete them (one kind, or all); the report stays
    assert c.delete(f"/api/feedback/{f['id']}/attachments/log", headers=a.h).status_code == 403
    assert web.delete(f"/api/feedback/{f['id']}/attachments/log").status_code == 403  # the header
    assert web.delete(f"/api/feedback/{f['id']}/attachments/log", headers=CSRF).status_code == 204
    assert c.delete(f"/api/feedback/{f['id']}/attachments/log", headers=ppl.admin.h).status_code == 404
    left = {x["id"]: x for x in c.get("/api/feedback?status=all", headers=ppl.admin.h).json()["feedback"]}
    assert [x["kind"] for x in left[f["id"]]["attachments"]] == ["schematic"]
    assert c.delete(f"/api/feedback/{f['id']}/attachments", headers=ppl.admin.h).status_code == 204
    assert not (store.feedback_root / str(f["id"])).exists()
    assert items[f["id"]]["message"] == "It crashed"
    assert c.delete("/api/feedback/9999/attachments", headers=ppl.admin.h).status_code == 404
    # the website's form can attach them too
    form = anon.get("/feedback").text
    assert '<input type="file" name="log"' in form and '<input type="file" name="schematic"' in form
    assert "FormData" in anon.get("/static/feedback.js").text


def test_feedback_rate_limit_counts_reports_with_attachments(gated):
    anon = TestClient(gated.app, client=("203.0.113.9", 1000))
    send_report(anon, CSRF, status=400, files={"log": ("x.log", b"\x00", "x")})  # refused ones don't count
    for _ in range(FEEDBACK_IP_LIMIT):
        send_report(anon, CSRF, files={"log": ("latest.log", LOG, "text/plain")})
    assert send_report(anon, CSRF, status=429, files={"log": ("latest.log", LOG, "text/plain")})["error"].startswith("too many messages")


def test_old_attachments_are_pruned_at_startup(make_client, mojang):
    c = make_client()
    admin = Who(c, mojang, ADMIN)
    alice = Who(c, mojang, "Alice")
    store = c.app.state.store
    ids = [send_report(c, alice.h, files={"log": ("latest.log", LOG, "text/plain")})["id"] for _ in range(3)]
    old, recent, still_open = ids
    for fid in (old, recent):
        assert c.post(f"/api/feedback/{fid}/resolve", headers=admin.h, json={"resolved": True}).status_code == 200
    day = 86400 * 1000
    store.db.execute("UPDATE feedback SET resolved = resolved - ? WHERE id=?", ((FEEDBACK_ATTACHMENT_DAYS + 1) * day, old))
    store.db.execute("UPDATE feedback SET resolved = resolved - ? WHERE id=?", ((FEEDBACK_ATTACHMENT_DAYS - 1) * day, recent))
    store.db.execute("UPDATE feedback SET created = created - ? WHERE id=?", (90 * day, still_open))
    (store.feedback_root / "777").mkdir()  # an interrupted submission
    (store.feedback_root / "777" / "log").write_bytes(b"x")
    c2 = make_client()
    store2 = c2.app.state.store
    left = {x["id"]: [a["kind"] for a in x["attachments"]] for x in c2.get("/api/feedback?status=all", headers=admin.h).json()["feedback"]}
    assert left == {old: [], recent: ["log"], still_open: ["log"]}  # the report itself stays
    assert not (store2.feedback_root / str(old)).exists() and not (store2.feedback_root / "777").exists()
    assert (store2.feedback_root / str(recent) / "log").read_bytes() == LOG


# ------------------------------------------------------------------ the in-game build browser


def test_board_browser_api(gated, ppl):
    c, a, b, d = gated, ppl.alice, ppl.bob, ppl.carol
    upload(c, a.h, "small.litematic")
    upload(c, a.h, "row.litematic", variety(40))
    red = post_json(c, a.h, path="small.litematic", title="Redstone clock", tags=["redstone"], description="ticks")
    farm = post_json(c, a.h, path="row.litematic", title="Iron farm", tags=["farm", "technical"])
    plain = post_json(c, a.h, path="small.litematic", title="Plain hut")
    private = post_json(c, a.h, path="small.litematic", title="Secret clock", visibility="private", allowed=["Bob"])
    # downloads: the farm twice, the clock once; votes: the hut on top
    for who in (b, d):
        c.get(f"/api/board/{farm['id']}/file", headers=who.h)
        c.post(f"/api/board/{plain['id']}/vote", headers=who.h, json={"value": 1})
    c.get(f"/api/board/{red['id']}/file", headers=b.h)

    def browse(h=None, status=200, **q):
        r = c.get("/api/board", headers=h or {}, params=q)
        assert r.status_code == status, r.text
        return r.json()

    ids = lambda res: [p["id"] for p in res["posts"]]  # noqa: E731
    assert ids(browse(sort="downloads")) == [farm["id"], red["id"], plain["id"]]
    assert ids(browse(sort="top")) == [plain["id"], farm["id"], red["id"]]
    assert ids(browse(sort="new")) == [plain["id"], farm["id"], red["id"]]
    # search, filters
    assert ids(browse(q="clock")) == [red["id"]] and ids(browse(q="CLOCK", h=b.h)) == [red["id"]]  # never private ones
    assert ids(browse(q="ticks alice")) == [red["id"]]
    assert ids(browse(tag="farm")) == [farm["id"]] and ids(browse(tag=["farm", "redstone"])) == []
    assert ids(browse(complexity="complex")) == [farm["id"]] and farm["distinctTypes"] == 40
    assert set(ids(browse(size="small"))) == {red["id"], farm["id"], plain["id"]}
    # paging with ?limit=
    page = browse(sort="downloads", limit=2, page=2)
    assert ids(page) == [plain["id"]] and (page["pageSize"], page["total"], page["pages"], page["page"]) == (2, 3, 2, 2)
    assert browse(limit=MAX_BOARD_LIMIT)["pageSize"] == MAX_BOARD_LIMIT
    for bad in ({"limit": "0"}, {"limit": str(MAX_BOARD_LIMIT + 1)}, {"limit": "x"}, {"sort": "random"}, {"page": "0"},
                {"tag": "nope"}, {"size": "huge"}, {"complexity": "x"}):
        assert "error" in browse(status=400, **bad), bad
    # the mod's bearer token: favourited flags, its own posts, posts shared with it, thumbnails and files of those
    res = browse(h=b.h, sort="new")
    assert all(p["favorited"] is False for p in res["posts"]) and all("favorites" in p for p in res["posts"])
    assert ids(browse(h=b.h, tab="shared")) == [private["id"]]
    assert len(ids(browse(h=a.h, tab="mine", limit=10))) == 4
    upload(c, a.h, "thumbed.litematic", thumb=True)
    t = post_json(c, a.h, path="thumbed.litematic", title="Pic", visibility="private", allowed=["Bob"])
    assert c.get(f"/api/board/{t['id']}/thumbnail", headers=b.h).content == PNG
    assert c.get(f"/api/board/{t['id']}/thumbnail", headers=d.h).status_code == 404
    assert c.get(f"/api/board/{private['id']}/file", headers=b.h).status_code == 200
    assert c.post(f"/api/board/{private['id']}/save", headers=b.h).status_code == 201
    assert c.post(f"/api/board/{farm['id']}/favorite", headers=b.h).status_code == 200
    assert ids(browse(h=b.h, sort="downloads"))[0] == farm["id"] and browse(h=b.h, sort="downloads")["posts"][0]["favorited"]
    # the website's board offers the new sort
    assert 'href="/board?sort=downloads">Downloads</a>' in TestClient(c.app).get("/board").text


def test_new_pages_under_a_root_path(make_client, mojang):
    prefix = "/slopmatica"
    c = make_client(root_path=prefix, public_url="http://box.example.ts.net")  # (https would make the cookie Secure)
    alice, bob, admin = Who(c, mojang, "Alice"), Who(c, mojang, "Bob"), Who(c, mojang, ADMIN)
    upload(c, alice.h, "a.litematic", thumb=True)
    p = make_post(c, alice.h, title="Pub")
    c.post(f"{prefix}/api/board/{p['id']}/favorite", headers=bob.h)
    c.post(f"{prefix}/api/board/{p['id']}/save", headers=bob.h)
    f = send_report(c, bob.h, files={"log": ("latest.log", LOG, "text/plain")})
    assert f["attachments"][0]["url"] == f"http://box.example.ts.net{prefix}/api/feedback/{f['id']}/attachments/log"
    pages = [
        alice.cookie_client(c.app, prefix).get(f"{prefix}/").text,
        alice.cookie_client(c.app, prefix).get(f"{prefix}/listings").text,
        bob.cookie_client(c.app, prefix).get(f"{prefix}/").text,
        bob.cookie_client(c.app, prefix).get(f"{prefix}/board").text,
        admin.cookie_client(c.app, prefix).get(f"{prefix}/admin?tab=disk").text,
        admin.cookie_client(c.app, prefix).get(f"{prefix}/admin/feedback").text,
        TestClient(c.app).get(f"{prefix}/feedback").text,
    ]
    assert "On the board" in pages[0] and 'data-fav="' in pages[2] and "Site storage" in pages[4]
    for html in pages:
        for attr in re.findall(r'(?:href|src|action)="([^"]*)"', html):
            assert attr.startswith(prefix + "/") or attr.startswith(("https://", "http://box.")), attr
