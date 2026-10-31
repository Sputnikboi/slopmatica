"""The schematics board: posts, visibility, votes, snapshots, quota, pages and the 0.3.0 -> 0.4.0 migration."""

import hashlib
import re
import socket
import sqlite3
import time

import pytest
from fastapi.testclient import TestClient

from slopmatica_cloud import convert, nbt
from slopmatica_cloud.app import SCHEMA_VERSION, Settings, sha256_hex

from conftest import PNG, bearer, gz, login
from test_convert import synthetic
from test_web import web_login

CSRF = {"X-Requested-With": "slopmatica"}


def schem(fmt: str = "litematic", **kw) -> bytes:
    return nbt.dumps(convert.write(synthetic(**kw), fmt))


def blob(n: int) -> bytes:
    return b"\x1f\x8b" + b"x" * (n - 2)


class Who:
    def __init__(self, client, mojang, name):
        data = login(client, mojang, name)
        self.name = name
        self.token = data["token"]
        self.uuid = data["uuid"]
        self.h = bearer(data["token"])

    def cookie_client(self, app, prefix=""):
        """A browser-like client logged in to the website with a session cookie."""
        c = TestClient(app)
        _, path = web_login(c, self.token, prefix=prefix)
        assert c.get(path, follow_redirects=False).status_code == 303
        return c


@pytest.fixture
def ppl(client, mojang):
    class P:
        alice = Who(client, mojang, "Alice")
        bob = Who(client, mojang, "Bob")
        carol = Who(client, mojang, "Carol")
        admin = Who(client, mojang, "sputnikboi")
    return P


def upload(client, h, path, body=None, thumb=False):
    r = client.put(f"/api/files/{path}", content=body if body is not None else schem(), headers=h)
    assert r.status_code in (200, 201), r.text
    if thumb:
        assert client.put(f"/api/thumbnails/{path}", content=PNG, headers=h).status_code == 200
    return r.json()


def make_post(client, h, path="a.litematic", title="My build", visibility="public", allowed=None, description="", status=201):
    body = {"path": path, "title": title, "visibility": visibility, "description": description}
    if allowed is not None:
        body["allowed"] = allowed
    r = client.post("/api/board", json=body, headers=h)
    assert r.status_code == status, r.text
    return r.json()


def ids(resp) -> list[str]:
    assert resp.status_code == 200, resp.text
    return [p["id"] for p in resp.json()["posts"]]


# ------------------------------------------------------------------ posting


def test_post_public(client, ppl, data_dir):
    body = schem("litematic", two_regions=True)
    upload(client, ppl.alice.h, "builds/Castle.litematic", body, thumb=True)
    p = make_post(client, ppl.alice.h, "builds/Castle.litematic", "  Castle  ", description="Line one\r\nLine two")
    assert re.fullmatch(r"[A-Za-z0-9_-]{10}", p["id"])
    assert p["title"] == "Castle" and p["description"] == "Line one\nLine two"
    assert p["visibility"] == "public"
    assert p["author"] == {"uuid": ppl.alice.uuid, "name": "Alice"}
    assert p["filename"] == "Castle.litematic" and p["format"] == "litematic"
    assert p["size"] == len(body) and p["sha256"] == hashlib.sha256(body).hexdigest()
    expected = synthetic(two_regions=True)
    assert p["dimensions"] == list(expected.bounds()[1]) == [7, 2, 2]
    assert p["blockCount"] == expected.block_count()
    assert p["hasThumbnail"] is True
    assert p["score"] == 0 and p["ups"] == 0 and p["downs"] == 0
    assert p["sourcePath"] == "builds/Castle.litematic" and p["allowed"] == []
    assert p["myVote"] == 0

    snap_dir = data_dir / "posts" / p["id"]
    assert (snap_dir / "Castle.litematic").read_bytes() == body
    assert (snap_dir / "thumb.png").read_bytes() == PNG

    anon = TestClient(client.app)
    assert ids(anon.get("/api/board")) == [p["id"]]
    g = anon.get(f"/api/board/{p['id']}")
    assert g.status_code == 200
    assert g.json()["myVote"] is None and "sourcePath" not in g.json() and "allowed" not in g.json()
    f = anon.get(f"/api/board/{p['id']}/file")
    assert f.status_code == 200 and f.content == body
    assert 'filename="Castle.litematic"' in f.headers["content-disposition"]
    assert f.headers["x-sha256"] == p["sha256"]
    t = anon.get(f"/api/board/{p['id']}/thumbnail")
    assert t.status_code == 200 and t.content == PNG and t.headers["content-type"] == "image/png"
    e = anon.get(f"/api/board/{p['id']}/export?format=nbt")
    assert e.status_code == 200 and 'filename="Castle.nbt"' in e.headers["content-disposition"]
    assert convert.read(nbt.loads(e.content)[1], "nbt", "x").block_count() == expected.block_count()
    assert anon.get(f"/api/board/{p['id']}/export?format=litematic").status_code == 400
    assert anon.get(f"/api/board/{p['id']}/export?format=schem").status_code == 400
    assert list((data_dir / "tmp").iterdir()) == []


def test_post_unparseable_file_still_posts(client, ppl):
    upload(client, ppl.alice.h, "junk.nbt", gz(b"not nbt"))
    p = make_post(client, ppl.alice.h, "junk.nbt", "Junk")
    assert p["dimensions"] is None and p["blockCount"] is None and p["format"] == "nbt"
    assert p["hasThumbnail"] is False
    html = TestClient(client.app).get(f"/board/{p['id']}").text
    assert "unknown" in html
    assert TestClient(client.app).get(f"/api/board/{p['id']}/thumbnail").status_code == 404


def test_post_validation(client, ppl):
    h = ppl.alice.h
    upload(client, h, "a.litematic")
    upload(client, ppl.bob.h, "bobs.litematic")
    for bad in ({"title": ""}, {"title": "   "}, {"title": "x" * 101}, {"title": "two\nlines"},
                {"description": "d" * 5001}, {"description": "bell\x07"}, {"visibility": "secret"},
                {"path": "../a.litematic"}, {"path": "a.txt"}, {"allowed": "Bob"}, {"title": None}):
        body = {"path": "a.litematic", "title": "T", "visibility": "public", **bad}
        r = client.post("/api/board", json=body, headers=h)
        assert r.status_code == 400, (bad, r.text)
        assert "error" in r.json()
    r = client.post("/api/board", json={"path": "a.litematic", "title": "T"}, headers=h)  # visibility is required
    assert r.status_code == 400
    assert make_post(client, h, title="x" * 100, description="d" * 5000)["title"] == "x" * 100
    # missing file, or someone else's
    assert make_post(client, h, "nope.litematic", status=404) == {"error": "no such file"}
    make_post(client, h, "bobs.litematic", status=404)
    assert client.post("/api/board", json={"path": "a.litematic", "title": "T", "visibility": "public"}).status_code == 401


@pytest.mark.parametrize("fmt", ["slopmatic", "nbt"])
def test_post_other_formats(client, ppl, fmt):
    upload(client, ppl.alice.h, f"x.{fmt}", schem(fmt))
    p = make_post(client, ppl.alice.h, f"x.{fmt}")
    assert p["format"] == fmt and p["blockCount"] == synthetic().block_count()


# ------------------------------------------------------------------ visibility


def test_unlisted(client, ppl):
    upload(client, ppl.alice.h, "a.litematic", thumb=True)
    p = make_post(client, ppl.alice.h, title="Hidden tower", visibility="unlisted")
    anon = TestClient(client.app)
    for c, h in ((anon, {}), (client, ppl.bob.h), (client, ppl.alice.h)):
        assert ids(c.get("/api/board", headers=h)) == []
        assert ids(c.get("/api/board?q=tower", headers=h)) == []
    assert ids(client.get("/api/board?tab=mine", headers=ppl.alice.h)) == [p["id"]]
    assert ids(client.get("/api/board?tab=shared", headers=ppl.bob.h)) == []
    # but the link works for anyone
    assert anon.get(f"/api/board/{p['id']}").status_code == 200
    assert anon.get(f"/board/{p['id']}").status_code == 200
    assert anon.get(f"/api/board/{p['id']}/file").status_code == 200
    assert anon.get(f"/api/board/{p['id']}/thumbnail").status_code == 200
    assert anon.get(f"/api/board/{p['id']}/export?format=nbt").status_code == 200
    assert "Hidden tower" not in anon.get("/board").text
    assert "Hidden tower" not in anon.get("/board?q=Hidden").text


def private_endpoints(pid):
    """(method, url, json) for everything a viewer can do with a post."""
    return [
        ("GET", f"/api/board/{pid}", None),
        ("GET", f"/api/board/{pid}/file", None),
        ("GET", f"/api/board/{pid}/thumbnail", None),
        ("GET", f"/api/board/{pid}/export?format=nbt", None),
        ("POST", f"/api/board/{pid}/vote", {"value": 1}),
        ("POST", f"/api/board/{pid}/save", None),
        ("PATCH", f"/api/board/{pid}", {"title": "x"}),
        ("POST", f"/api/board/{pid}/refresh", None),
        ("DELETE", f"/api/board/{pid}", None),
        ("GET", f"/board/{pid}", None),
    ]


def test_private_access(client, ppl, mojang):
    upload(client, ppl.alice.h, "a.litematic", thumb=True)
    p = make_post(client, ppl.alice.h, title="Secret base", visibility="private", allowed=["bob"])
    pid = p["id"]
    # the name was resolved to Bob's uuid and canonical name
    assert p["allowed"] == [{"uuid": ppl.bob.uuid, "name": "Bob"}]

    anon = TestClient(client.app)
    carol_web = ppl.carol.cookie_client(client.app)
    for who, c, h in (("anon", anon, {}), ("carol", client, ppl.carol.h), ("carol-web", carol_web, CSRF)):
        for method, url, body in private_endpoints(pid):
            r = c.request(method, url, json=body, headers=h)
            assert r.status_code == 404, (who, method, url, r.status_code)
            if url.startswith("/api/"):
                assert r.json() == {"error": "no such post"}

    # never listed publicly or searchable, for anyone
    for c, h in ((anon, {}), (client, ppl.bob.h), (client, ppl.admin.h), (client, ppl.alice.h)):
        assert ids(c.get("/api/board", headers=h)) == []
        assert ids(c.get("/api/board?q=secret", headers=h)) == []
        assert ids(c.get("/api/board?q=alice&sort=new", headers=h)) == []
    assert "Secret base" not in anon.get("/board").text
    assert "Secret base" not in carol_web.get("/board?q=secret").text

    # Bob can do everything a viewer can
    b = ppl.bob.h
    assert client.get(f"/api/board/{pid}", headers=b).status_code == 200
    assert client.get(f"/api/board/{pid}/file", headers=b).status_code == 200
    assert client.get(f"/api/board/{pid}/thumbnail", headers=b).status_code == 200
    assert client.get(f"/api/board/{pid}/export?format=nbt", headers=b).status_code == 200
    assert client.post(f"/api/board/{pid}/vote", json={"value": 1}, headers=b).status_code == 200
    assert client.post(f"/api/board/{pid}/save", headers=b).status_code == 201
    assert "allowed" not in client.get(f"/api/board/{pid}", headers=b).json()  # the list is for the author
    assert client.patch(f"/api/board/{pid}", json={"title": "x"}, headers=b).status_code == 403
    assert client.delete(f"/api/board/{pid}", headers=b).status_code == 403
    assert ids(client.get("/api/board?tab=shared", headers=b)) == [pid]
    assert ids(client.get("/api/board?tab=shared", headers=ppl.carol.h)) == []
    assert ids(client.get("/api/board?tab=shared", headers=ppl.alice.h)) == []  # own posts aren't "shared"
    bob_web = ppl.bob.cookie_client(client.app)
    assert "Secret base" in bob_web.get(f"/board/{pid}").text
    assert "Secret base" in bob_web.get("/board?tab=shared").text

    # admins see everything; the author too
    assert client.get(f"/api/board/{pid}", headers=ppl.admin.h).json()["allowed"][0]["name"] == "Bob"
    assert "Moderation" in ppl.admin.cookie_client(client.app).get(f"/board/{pid}").text
    assert client.get(f"/api/board/{pid}/file", headers=ppl.alice.h).status_code == 200

    # access follows the uuid: Bob renames and keeps access, while someone new taking the name has none
    mojang.rename("Bob", "Robert")
    robert = Who(client, mojang, "Robert")
    assert robert.uuid == ppl.bob.uuid
    assert client.get(f"/api/board/{pid}", headers=robert.h).status_code == 200
    new_bob = Who(client, mojang, "Bob")
    assert new_bob.uuid != ppl.bob.uuid
    assert client.get(f"/api/board/{pid}", headers=new_bob.h).status_code == 404

    # logged-out vote/save on a visible post is 401, not 404
    upload(client, ppl.alice.h, "b.litematic")
    pub = make_post(client, ppl.alice.h, "b.litematic", visibility="public")
    assert anon.post(f"/api/board/{pub['id']}/vote", json={"value": 1}).status_code == 401
    assert anon.post(f"/api/board/{pub['id']}/save").status_code == 401
    # bad ids are plain 404s
    assert anon.get("/api/board/short").status_code == 404
    assert anon.get("/api/board/..%2F..%2Fxx/file").status_code == 404
    assert anon.get("/board/nope").status_code == 404


def test_changing_visibility(client, ppl):
    upload(client, ppl.alice.h, "a.litematic")
    p = make_post(client, ppl.alice.h, visibility="public")
    anon = TestClient(client.app)
    r = client.patch(f"/api/board/{p['id']}", json={"visibility": "private", "allowed": ["Carol"]}, headers=ppl.alice.h)
    assert r.status_code == 200 and r.json()["visibility"] == "private"
    assert anon.get(f"/api/board/{p['id']}").status_code == 404
    assert client.get(f"/api/board/{p['id']}", headers=ppl.carol.h).status_code == 200
    assert client.get(f"/api/board/{p['id']}", headers=ppl.bob.h).status_code == 404
    # back to unlisted keeps the stored names but opens it to everyone with the link
    client.patch(f"/api/board/{p['id']}", json={"visibility": "unlisted"}, headers=ppl.alice.h)
    assert anon.get(f"/api/board/{p['id']}").status_code == 200
    assert ids(anon.get("/api/board")) == []


# ------------------------------------------------------------------ Mojang name lookups


def test_name_resolution(client, ppl, mojang):
    upload(client, ppl.alice.h, "a.litematic")
    r = client.post("/api/board", headers=ppl.alice.h, json={
        "path": "a.litematic", "title": "T", "visibility": "private", "allowed": ["Bob", "Nobody_Here"]})
    assert r.status_code == 400
    assert r.json()["error"] == "unknown Minecraft username: Nobody_Here"
    r = client.post("/api/board", headers=ppl.alice.h, json={
        "path": "a.litematic", "title": "T", "visibility": "private", "allowed": ["Ghost1", "Ghost2"]})
    assert r.status_code == 400 and r.json()["error"] == "unknown Minecraft usernames: Ghost1, Ghost2"
    r = client.post("/api/board", headers=ppl.alice.h, json={
        "path": "a.litematic", "title": "T", "visibility": "private", "allowed": ["bad name!"]})
    assert r.status_code == 400 and "bad name!" in r.json()["error"]
    r = client.post("/api/board", headers=ppl.alice.h, json={
        "path": "a.litematic", "title": "T", "visibility": "private", "allowed": [f"p{i}" for i in range(51)]})
    assert r.status_code == 400 and "50" in r.json()["error"]
    assert ids(client.get("/api/board?tab=mine", headers=ppl.alice.h)) == []  # nothing was posted

    # duplicates and blanks collapse, names come back canonical
    p = make_post(client, ppl.alice.h, visibility="private", allowed=["bob", "BOB", " ", "carol"])
    assert sorted(a["name"] for a in p["allowed"]) == ["Bob", "Carol"]
    # names already on the post are not looked up again
    mojang.profile_lookups.clear()
    r = client.patch(f"/api/board/{p['id']}", json={"allowed": ["Bob", "Carol", "sputnikboi"]}, headers=ppl.alice.h)
    assert r.status_code == 200
    assert mojang.profile_lookups == ["sputnikboi"]
    r = client.patch(f"/api/board/{p['id']}", json={"allowed": []}, headers=ppl.alice.h)
    assert r.json()["allowed"] == []
    assert client.get(f"/api/board/{p['id']}", headers=ppl.bob.h).status_code == 404
    assert client.patch(f"/api/board/{p['id']}", json={"allowed": None}, headers=ppl.alice.h).status_code == 400

    # Mojang errors are reported clearly and nothing changes
    mojang.status_override = 500
    r = client.patch(f"/api/board/{p['id']}", json={"allowed": ["Bob"]}, headers=ppl.alice.h)
    assert r.status_code == 502 and "Mojang" in r.json()["error"]
    mojang.status_override = None
    assert client.get(f"/api/board/{p['id']}", headers=ppl.alice.h).json()["allowed"] == []


def test_mojang_unreachable(make_client, mojang):
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()  # nothing listens here now
    c = make_client(mojang_profile_url=f"http://127.0.0.1:{port}/users/profiles/minecraft")
    h = bearer(login(c, mojang, "Alice")["token"])
    upload(c, h, "a.litematic")
    r = c.post("/api/board", headers=h, json={"path": "a.litematic", "title": "T", "visibility": "private", "allowed": ["Bob"]})
    assert r.status_code == 502
    assert r.json()["error"] == "could not reach Mojang to look up Minecraft usernames, try again later"
    # a post without names never asks Mojang
    assert c.post("/api/board", headers=h, json={"path": "a.litematic", "title": "T", "visibility": "private"}).status_code == 201


def test_mojang_profile_settings():
    s = Settings()
    assert s.mojang_profile_url == "https://api.mojang.com/users/profiles/minecraft"
    assert s.mojang_timeout == 10
    assert Settings.from_env({"SLOPMATICA_MOJANG_PROFILE": "http://x/p"}).mojang_profile_url == "http://x/p"


# ------------------------------------------------------------------ snapshots


def test_snapshot_independent_of_the_file_and_refresh(client, ppl):
    h = ppl.alice.h
    v1 = schem("litematic")
    upload(client, h, "a.litematic", v1, thumb=True)
    p = make_post(client, h)
    pid = p["id"]
    anon = TestClient(client.app)

    v2 = schem("litematic", two_regions=True)
    upload(client, h, "a.litematic", v2)  # changes the file and drops its thumbnail
    assert anon.get(f"/api/board/{pid}/file").content == v1
    assert anon.get(f"/api/board/{pid}/thumbnail").content == PNG

    r = client.post(f"/api/board/{pid}/refresh", headers=h)
    assert r.status_code == 200, r.text
    q = r.json()
    assert q["sha256"] == hashlib.sha256(v2).hexdigest() and q["size"] == len(v2)
    assert q["dimensions"] == [7, 2, 2] and q["hasThumbnail"] is False
    assert q["created"] == p["created"] and q["updated"] >= p["updated"]
    assert anon.get(f"/api/board/{pid}/file").content == v2
    assert anon.get(f"/api/board/{pid}/thumbnail").status_code == 404

    # the file is renamed, then deleted: the post stays, refresh reports why it can't
    client.post("/api/files/move", json={"from": "a.litematic", "to": "b.litematic"}, headers=h)
    r = client.post(f"/api/board/{pid}/refresh", headers=h)
    assert r.status_code == 409 and "a.litematic" in r.json()["error"]
    client.delete("/api/files/b.litematic", headers=h)
    assert anon.get(f"/api/board/{pid}/file").content == v2
    assert client.post(f"/api/board/{pid}/refresh", headers=h).status_code == 409
    html = ppl.alice.cookie_client(client.app).get(f"/board/{pid}").text
    assert "no longer in your cloud files" in html
    # the file comes back with a thumbnail
    upload(client, h, "a.litematic", v1, thumb=True)
    assert client.post(f"/api/board/{pid}/refresh", headers=h).json()["hasThumbnail"] is True
    assert anon.get(f"/api/board/{pid}/file").content == v1
    # only the author refreshes
    assert client.post(f"/api/board/{pid}/refresh", headers=ppl.bob.h).status_code == 403
    assert client.post(f"/api/board/{pid}/refresh", headers=ppl.admin.h).status_code == 403


# ------------------------------------------------------------------ quota and limits


def test_quota(make_client, mojang):
    c = make_client(quota_bytes=100)
    alice = Who(c, mojang, "Alice")
    h = alice.h
    upload(c, h, "a.litematic", blob(40))
    p = make_post(c, h)  # 40 + 40
    assert c.get("/api/me", headers=h).json()["usedBytes"] == 80
    assert c.get("/api/board?tab=mine", headers=h).json()["postBytes"] == 40
    r = c.post("/api/board", json={"path": "a.litematic", "title": "T", "visibility": "public"}, headers=h)
    assert r.status_code == 413
    assert r.json() == {"error": "quota exceeded", "usedBytes": 80, "quotaBytes": 100}
    # file uploads see the snapshot too
    assert c.put("/api/files/b.litematic", content=blob(21), headers=h).status_code == 413
    assert c.put("/api/files/b.litematic", content=blob(20), headers=h).status_code == 201  # exactly full
    # refresh counts the difference
    upload(c, h, "a.litematic", blob(30))  # 30 + 40 + 20 = 90
    assert c.post(f"/api/board/{p['id']}/refresh", headers=h).status_code == 200  # 30 + 30 + 20
    upload(c, h, "a.litematic", blob(41))  # 41 + 30 + 20 = 91
    r = c.post(f"/api/board/{p['id']}/refresh", headers=h)  # would be 41 + 41 + 20
    assert r.status_code == 413
    assert len(TestClient(c.app).get(f"/api/board/{p['id']}/file").content) == 30
    # saving a post into your files counts as a file
    bob = Who(c, mojang, "Bob")
    upload(c, bob.h, "big.litematic", blob(80))
    assert c.post(f"/api/board/{p['id']}/save", headers=bob.h).status_code == 413
    assert c.get("/api/files", headers=bob.h).json()["usedBytes"] == 80
    # deleting the post frees its snapshot
    assert c.delete(f"/api/board/{p['id']}", headers=h).status_code == 204
    assert c.get("/api/me", headers=h).json()["usedBytes"] == 61
    # the files page says how much is board posts
    upload(c, h, "a.litematic", blob(20))
    make_post(c, h)
    html = alice.cookie_client(c.app).get("/").text
    assert "1 board post</a> use 20 B of that" in html


def test_admin_quota_unlimited(make_client, mojang):
    c = make_client(quota_bytes=100)
    h = Who(c, mojang, "sputnikboi").h
    upload(c, h, "a.litematic", blob(90))
    make_post(c, h)
    make_post(c, h)
    assert c.get("/api/me", headers=h).json() == {**c.get("/api/me", headers=h).json(), "usedBytes": 270, "quotaBytes": None}


def test_post_limit(make_client, mojang):
    c = make_client(max_posts_per_user=3)
    h = Who(c, mojang, "Alice").h
    upload(c, h, "a.litematic")
    posts = [make_post(c, h) for _ in range(3)]
    r = c.post("/api/board", json={"path": "a.litematic", "title": "T", "visibility": "public"}, headers=h)
    assert r.status_code == 409 and "3" in r.json()["error"]
    c.delete(f"/api/board/{posts[0]['id']}", headers=h)
    make_post(c, h)


def test_default_limits():
    s = Settings()
    assert s.max_posts_per_user == 200 and s.board_page_size == 24


# ------------------------------------------------------------------ votes and sorting


def test_votes(client, ppl):
    upload(client, ppl.alice.h, "a.litematic")
    pid = make_post(client, ppl.alice.h)["id"]
    url = f"/api/board/{pid}/vote"

    def vote(who, value, status=200):
        r = client.post(url, json={"value": value}, headers=who.h)
        assert r.status_code == status, r.text
        return r.json()

    assert vote(ppl.bob, 1) == {"score": 1, "ups": 1, "downs": 0, "myVote": 1}
    assert vote(ppl.carol, -1) == {"score": 0, "ups": 1, "downs": 1, "myVote": -1}
    assert vote(ppl.bob, -1) == {"score": -2, "ups": 0, "downs": 2, "myVote": -1}  # one vote per user
    assert vote(ppl.bob, 0) == {"score": -1, "ups": 0, "downs": 1, "myVote": 0}
    assert vote(ppl.bob, 0)["score"] == -1  # clearing twice is fine
    assert vote(ppl.alice, 1, 403)["error"] == "you cannot vote on your own post"
    for bad in (2, -2, "1", True, 1.0, None):
        vote(ppl.bob, bad, 400)
    assert client.post(url, headers=ppl.bob.h).status_code == 400  # no body
    vote(ppl.admin, 1)
    g = client.get(f"/api/board/{pid}", headers=ppl.carol.h).json()
    assert (g["score"], g["ups"], g["downs"], g["myVote"]) == (0, 1, 1, -1)
    assert client.get(f"/api/board/{pid}", headers=ppl.bob.h).json()["myVote"] == 0
    # the page shows the viewer's vote
    html = ppl.carol.cookie_client(client.app).get(f"/board/{pid}").text
    assert 'data-my-vote="-1"' in html and 'class="vote-btn down on"' in html
    html = TestClient(client.app).get(f"/board/{pid}").text
    assert "Log in from the game to vote" in html
    html = ppl.alice.cookie_client(client.app).get(f"/board/{pid}").text
    assert "can&rsquo;t vote on it" in html


def test_sorting(client, ppl):
    store = client.app.state.store
    upload(client, ppl.alice.h, "a.litematic")
    old = make_post(client, ppl.alice.h, title="Old classic")["id"]
    fresh = make_post(client, ppl.alice.h, title="Fresh hit")["id"]
    meh = make_post(client, ppl.alice.h, title="Meh")["id"]
    now = int(time.time() * 1000)
    hour = 3_600_000
    store.db.execute("UPDATE posts SET created=? WHERE id=?", (now - 100 * hour, old))
    store.db.execute("UPDATE posts SET created=? WHERE id=?", (now - 1 * hour, fresh))
    store.db.execute("UPDATE posts SET created=? WHERE id=?", (now - 2 * hour, meh))
    for i in range(10):
        uid = f"00000000-0000-0000-0000-{i:012d}"
        store.upsert_user(uid, f"voter{i}", False)
        store.set_vote(old, uid, 1)
        if i < 3:
            store.set_vote(fresh, uid, 1)
    # top: score; new: newest first; hot: score / (hours + 2) ** 1.5
    assert ids(client.get("/api/board?sort=top")) == [old, fresh, meh]
    assert ids(client.get("/api/board")) == [old, fresh, meh]  # top is the default
    assert ids(client.get("/api/board?sort=new")) == [fresh, meh, old]
    assert ids(client.get("/api/board?sort=hot")) == [fresh, old, meh]
    # ties on score go to the newer post
    for i in range(7):
        store.set_vote(fresh, f"00000000-0000-0000-0000-{i:012d}", 1) if i >= 3 else None
    store.set_vote(meh, "00000000-0000-0000-0000-000000000000", 1)
    store.set_vote(old, "00000000-0000-0000-0000-000000000009", -1)  # old: 9 - 1 = 8? no: 8 ups, 1 down
    scores = {p["id"]: p["score"] for p in client.get("/api/board").json()["posts"]}
    assert scores == {old: 8, fresh: 7, meh: 1}
    store.set_vote(fresh, "00000000-0000-0000-0000-000000000008", 1)
    assert ids(client.get("/api/board?sort=top")) == [fresh, old, meh]  # 8 and 8: fresh is newer
    for bad in ("sort=best", "tab=all", "page=0", "page=x"):
        r = client.get(f"/api/board?{bad}")
        assert r.status_code == 400 and "error" in r.json()


def test_search_and_pagination(make_client, mojang):
    c = make_client(board_page_size=3)
    alice, bob = Who(c, mojang, "Alice"), Who(c, mojang, "Bobby")
    upload(c, alice.h, "a.litematic")
    upload(c, bob.h, "b.litematic")
    made = [make_post(c, alice.h, title=f"House {i}")["id"] for i in range(5)]
    tower = make_post(c, bob.h, "b.litematic", title="Über Tower", description="A spiral STAIRCASE inside")["id"]
    castle = make_post(c, bob.h, "b.litematic", title="Castle", description="has a tower too")["id"]

    r = c.get("/api/board?sort=new").json()
    assert (r["total"], r["pages"], r["page"], r["pageSize"]) == (7, 3, 1, 3)
    pages = [ids(c.get(f"/api/board?sort=new&page={n}")) for n in (1, 2, 3)]
    assert pages == [[castle, tower, made[4]], made[3:0:-1], [made[0]]]
    assert ids(c.get("/api/board?sort=new&page=4")) == []

    assert set(ids(c.get("/api/board?q=tower"))) == {tower, castle}  # title or description
    assert ids(c.get("/api/board?q=staircase")) == [tower]
    assert ids(c.get("/api/board?q=%C3%BCBER")) == [tower]  # case-insensitive beyond ASCII
    assert len(ids(c.get("/api/board?q=bobby"))) == 2  # author name
    assert ids(c.get("/api/board?q=tower+spiral")) == [tower]  # every word must match
    assert ids(c.get("/api/board?q=house+tower")) == []
    assert ids(c.get("/api/board?q=%25")) == []  # LIKE wildcards mean nothing
    r = c.get("/api/board?q=house&sort=new&page=2").json()
    assert r["total"] == 5 and r["pages"] == 2 and len(r["posts"]) == 2

    html = c.get("/board?q=house&sort=new&page=2").text
    assert "Page 2 of 2" in html and "5 results for &ldquo;house&rdquo;" in html
    assert 'href="/board?sort=new&amp;q=house" rel="prev"' in html
    assert "House 0" in html and "House 4" not in html
    # login-only tabs prompt for a login on the page, 401 in the API
    anon = TestClient(c.app)
    assert anon.get("/api/board?tab=mine").status_code == 401
    assert anon.get("/api/board?tab=shared").status_code == 401
    assert "Log in to see your posts" in anon.get("/board?tab=mine").text
    assert anon.get("/board?tab=bogus&sort=bogus&page=-1").status_code == 200  # the page falls back to defaults


# ------------------------------------------------------------------ add to my cloud


def test_save_to_my_cloud(client, ppl):
    body = schem("nbt")
    upload(client, ppl.alice.h, "deep/x/a.nbt", body, thumb=True)
    pid = make_post(client, ppl.alice.h, "deep/x/a.nbt", title="Bob's: <tower>?")["id"]
    first = client.post(f"/api/board/{pid}/save", headers=ppl.bob.h)
    assert first.status_code == 201, first.text
    assert first.json()["path"] == "board/Bob's_ _tower__.nbt"
    assert first.json()["hasThumbnail"] is True and first.json()["size"] == len(body)
    second = client.post(f"/api/board/{pid}/save", headers=ppl.bob.h).json()
    third = client.post(f"/api/board/{pid}/save", headers=ppl.bob.h).json()
    assert second["path"] == "board/Bob's_ _tower__ (2).nbt" and third["path"] == "board/Bob's_ _tower__ (3).nbt"
    listing = {f["path"]: f for f in client.get("/api/files", headers=ppl.bob.h).json()["files"]}
    assert set(listing) == {first.json()["path"], second["path"], third["path"]}
    assert client.get("/api/files/board/Bob's_ _tower__.nbt", headers=ppl.bob.h).content == body
    assert client.get("/api/thumbnails/board/Bob's_ _tower__.nbt", headers=ppl.bob.h).content == PNG
    # the author can save their own post too, into their own files
    assert client.post(f"/api/board/{pid}/save", headers=ppl.alice.h).json()["path"] == "board/Bob's_ _tower__.nbt"


@pytest.mark.parametrize("title,expected", [
    ("...", "schematic"),
    (" .hidden. ", "hidden"),
    ("a/b\\c", "a_b_c"),
    ("x" * 100, "x" * (100 - len(".litematic") - len(" (9999)"))),
    ("Ünïcode ✓", "Ünïcode ✓"),
])
def test_save_names(client, ppl, title, expected):
    upload(client, ppl.alice.h, "a.litematic")
    pid = make_post(client, ppl.alice.h, title=title)["id"]
    r = client.post(f"/api/board/{pid}/save", headers=ppl.bob.h)
    assert r.status_code == 201, r.text
    assert r.json()["path"] == f"board/{expected}.litematic"


# ------------------------------------------------------------------ owner tools


def test_owner_edit_and_delete(client, ppl, data_dir):
    upload(client, ppl.alice.h, "a.litematic")
    pid = make_post(client, ppl.alice.h, title="Before", description="old")["id"]
    r = client.patch(f"/api/board/{pid}", json={"title": " After ", "description": "new\n\ntext"}, headers=ppl.alice.h)
    assert r.status_code == 200
    assert (r.json()["title"], r.json()["description"], r.json()["visibility"]) == ("After", "new\n\ntext", "public")
    r = client.patch(f"/api/board/{pid}", json={"visibility": "unlisted"}, headers=ppl.alice.h)
    assert r.json()["title"] == "After" and r.json()["visibility"] == "unlisted"
    for bad in ({"title": ""}, {"title": None}, {"visibility": "everyone"}, {"description": "x" * 5001}):
        assert client.patch(f"/api/board/{pid}", json=bad, headers=ppl.alice.h).status_code == 400, bad
    assert client.patch(f"/api/board/{pid}", json={"title": "Bob's"}, headers=ppl.bob.h).status_code == 403
    assert client.patch(f"/api/board/{pid}", json={"title": "x"}).status_code == 401
    assert client.patch(f"/api/board/{pid}", json={"title": "x"}, headers=ppl.admin.h).status_code == 403

    client.post(f"/api/board/{pid}/vote", json={"value": 1}, headers=ppl.bob.h)
    assert client.delete(f"/api/board/{pid}", headers=ppl.bob.h).status_code == 403
    assert client.delete(f"/api/board/{pid}").status_code == 401
    assert client.delete(f"/api/board/{pid}", headers=ppl.alice.h).status_code == 204
    assert client.get(f"/api/board/{pid}", headers=ppl.alice.h).status_code == 404
    assert client.delete(f"/api/board/{pid}", headers=ppl.alice.h).status_code == 404
    assert not (data_dir / "posts" / pid).exists()
    store = client.app.state.store
    assert store.db.execute("SELECT COUNT(*) FROM votes").fetchone()[0] == 0
    # the cloud file is untouched
    assert client.get("/api/files/a.litematic", headers=ppl.alice.h).status_code == 200


def test_admin_delete(client, ppl):
    upload(client, ppl.carol.h, "c.litematic")
    pid = make_post(client, ppl.carol.h, "c.litematic", visibility="private", allowed=["Bob"])["id"]
    assert client.delete(f"/api/board/{pid}", headers=ppl.alice.h).status_code == 404  # can't see it
    assert client.delete(f"/api/board/{pid}", headers=ppl.admin.h).status_code == 204
    assert client.get(f"/api/board/{pid}", headers=ppl.carol.h).status_code == 404


def test_cookie_mutations_need_csrf_header(client, ppl):
    upload(client, ppl.alice.h, "a.litematic")
    pid = make_post(client, ppl.alice.h)["id"]
    web = ppl.alice.cookie_client(client.app)
    bob = ppl.bob.cookie_client(client.app)
    body = {"path": "a.litematic", "title": "T", "visibility": "public"}
    assert web.post("/api/board", json=body).status_code == 403
    assert web.post("/api/board", json=body, headers=CSRF).status_code == 201
    assert web.patch(f"/api/board/{pid}", json={"title": "x"}).status_code == 403
    assert web.patch(f"/api/board/{pid}", json={"title": "x"}, headers=CSRF).status_code == 200
    assert web.post(f"/api/board/{pid}/refresh").status_code == 403
    assert web.post(f"/api/board/{pid}/refresh", headers=CSRF).status_code == 200
    assert bob.post(f"/api/board/{pid}/vote", json={"value": 1}).status_code == 403
    assert bob.post(f"/api/board/{pid}/vote", json={"value": 1}, headers=CSRF).status_code == 200
    assert bob.post(f"/api/board/{pid}/save").status_code == 403
    assert bob.post(f"/api/board/{pid}/save", headers=CSRF).status_code == 201
    # reads need no header
    assert bob.get(f"/api/board/{pid}").json()["myVote"] == 1
    assert bob.get("/api/board?tab=shared").status_code == 200
    assert bob.get(f"/api/board/{pid}/export?format=nbt").status_code == 200
    assert web.delete(f"/api/board/{pid}").status_code == 403
    assert web.delete(f"/api/board/{pid}", headers=CSRF).status_code == 204


# ------------------------------------------------------------------ pages


def test_pages(client, ppl):
    upload(client, ppl.alice.h, "builds/Barn.litematic", thumb=True)
    pid = make_post(client, ppl.alice.h, "builds/Barn.litematic", title="Barn <b>&</b>",
                    description="<script>alert(1)</script>\nsecond line https://example.com")["id"]
    anon = TestClient(client.app)
    board = anon.get("/board")
    assert board.status_code == 200 and "Content-Security-Policy" in board.headers
    assert "Barn &lt;b&gt;&amp;&lt;/b&gt;" in board.text
    assert f'href="/board/{pid}"' in board.text
    assert f"/api/board/{pid}/thumbnail?v=" in board.text
    assert f"https://mc-heads.net/avatar/{ppl.alice.uuid}/16" in board.text
    assert "My posts" not in board.text  # login-only tabs are hidden when logged out

    page = anon.get(f"/board/{pid}").text
    assert "&lt;script&gt;alert(1)&lt;/script&gt;\nsecond line https://example.com" in page
    assert "<script>alert" not in page and 'href="https://example.com' not in page
    assert f'href="/api/board/{pid}/file" download="Barn.litematic"' in page
    assert 'data-post-export' in page and '<option value="nbt">.nbt</option>' in page
    assert "Add to my cloud" not in page and "Update from my file" not in page and "Delete post" not in page

    owner = ppl.alice.cookie_client(client.app)
    page = owner.get(f"/board/{pid}").text
    assert "Update from my file" in page and "Delete post" in page and 'data-post-form="edit"' in page
    assert "builds/Barn.litematic" in page
    viewer = ppl.bob.cookie_client(client.app)
    page = viewer.get(f"/board/{pid}").text
    assert "Add to my cloud" in page and "Update from my file" not in page and "Moderation" not in page

    files = owner.get("/").text
    assert 'data-act="post"' in files and 'id="post-dialog"' in files and "/static/board.js" in files
    mine = owner.get("/board?tab=mine").text
    assert "badge vis-public" in mine
    missing = anon.get("/board/AAAAAAAAAA")
    assert missing.status_code == 404 and "text/html" in missing.headers["content-type"]


def test_header_has_board_button(client, ppl):
    anon = TestClient(client.app)
    for html in (anon.get("/").text, anon.get("/board").text):
        assert 'class="btn small nav-board' in html and 'href="/board"' in html
        assert "Schematics board" in html
        assert "My files" not in html
    web = ppl.alice.cookie_client(client.app)
    for url in ("/", "/board", "/board?tab=mine"):
        html = web.get(url).text
        assert 'class="btn small nav-board' in html
        assert 'class="btn small ghost nav-files' in html and ">My files</a>" in html


# ------------------------------------------------------------------ prefix-relative URLs


def test_no_root_relative_urls(make_client, mojang):
    prefix = "/slopmatica"
    c = make_client(root_path=prefix, public_url="https://box.example.ts.net")
    alice = Who(c, mojang, "Alice")
    bob = Who(c, mojang, "Bob")
    upload(c, alice.h, "a.litematic", thumb=True)
    pub = make_post(c, alice.h, title="Pub")["id"]
    priv = make_post(c, alice.h, title="Priv", visibility="private", allowed=["Bob"])["id"]
    make_post(c, alice.h, title="Pub2")
    web = alice.cookie_client(c.app)
    pages = [
        web.get(f"{prefix}/").text,
        web.get(f"{prefix}/board").text,
        web.get(f"{prefix}/board?tab=mine&q=p").text,
        web.get(f"{prefix}/board/{pub}").text,
        web.get(f"{prefix}/board/{priv}").text,
        bob.cookie_client(c.app).get(f"{prefix}/board/{priv}").text,
        bob.cookie_client(c.app).get(f"{prefix}/board?tab=shared").text,
        TestClient(c.app).get(f"{prefix}/board").text,
        TestClient(c.app).get(f"{prefix}/board/{priv}").text,  # the 404 page
        TestClient(c.app).get(f"{prefix}/").text,
    ]
    c2 = make_client(root_path=prefix, board_page_size=1)
    pages.append(TestClient(c2.app).get(f"{prefix}/board?page=2").text)  # pager links
    checked = 0
    for html in pages:
        assert f'data-base="{prefix}"' in html
        for attr in re.findall(r'(?:href|src|action)="([^"]*)"', html):
            if attr.startswith("https://"):
                continue
            assert attr.startswith(prefix + "/"), attr
            checked += 1
    assert checked > 50


def test_js_never_uses_root_relative_urls():
    from slopmatica_cloud.app import PKG_DIR

    scripts = sorted((PKG_DIR / "static").glob("*.js"))
    assert {p.name for p in scripts} >= {"app.js", "board.js"}
    for p in scripts:
        js = p.read_text()
        # a string starting with "/" is only ever appended to something (base, or a URL built from it)
        assert not re.search(r"""(?<!\+ )["'`]/(?=[A-Za-z?#])""", js), p.name
        # and site paths are appended to base itself
        assert not re.search(r"""(?<!base \+ )["'`]/(?:api|board|static|login|logout|account)\b""", js), p.name
        assert not re.search(r"""(?:href|src|action)\s*=\s*["'`]/""", js), p.name
    assert 'base + "/api/board' in (PKG_DIR / "static" / "board.js").read_text()


# ------------------------------------------------------------------ database migration


SCHEMA_0_3_0 = """
CREATE TABLE IF NOT EXISTS users (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, is_admin INTEGER NOT NULL DEFAULT 0,
    created INTEGER NOT NULL, last_login INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS tokens (hash TEXT PRIMARY KEY, kind TEXT NOT NULL, uuid TEXT NOT NULL REFERENCES users(uuid),
    created INTEGER NOT NULL, expires INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS tokens_uuid ON tokens(uuid);
CREATE TABLE IF NOT EXISTS challenges (server_id TEXT PRIMARY KEY, expires INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS files (uuid TEXT NOT NULL, path TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL,
    modified INTEGER NOT NULL, has_thumb INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (uuid, path));
"""


def test_migrates_a_0_3_0_database(make_client, mojang, data_dir):
    # a data directory exactly as 0.3.0 left it
    uuid = "12345678-1234-1234-1234-123456789abc"
    token = "old-api-token"
    body = schem("litematic")
    files = data_dir / "users" / uuid / "files" / "builds"
    files.mkdir(parents=True)
    (files / "Old.litematic").write_bytes(body)
    thumbs = data_dir / "users" / uuid / "thumbs" / "builds"
    thumbs.mkdir(parents=True)
    (thumbs / "Old.litematic.png").write_bytes(PNG)
    db = sqlite3.connect(data_dir / "cloud.db")
    db.executescript(SCHEMA_0_3_0)
    t = int(time.time())
    db.execute("INSERT INTO users VALUES(?,?,?,?,?)", (uuid, "Veteran", 0, t - 1000, t - 10))
    db.execute("INSERT INTO tokens VALUES(?,?,?,?,?)", (sha256_hex(token), "api", uuid, t, t + 86400))
    db.execute("INSERT INTO files VALUES(?,?,?,?,?,1)",
               (uuid, "builds/Old.litematic", len(body), hashlib.sha256(body).hexdigest(), 1_700_000_000_000))
    db.commit()
    assert db.execute("PRAGMA user_version").fetchone()[0] == 0
    db.close()

    c = make_client()
    h = bearer(token)
    me = c.get("/api/me", headers=h).json()
    assert me == {"uuid": uuid, "name": "Veteran", "usedBytes": len(body), "quotaBytes": 1073741824}
    listing = c.get("/api/files", headers=h).json()["files"]
    assert listing == [{"path": "builds/Old.litematic", "size": len(body), "sha256": hashlib.sha256(body).hexdigest(),
                        "modified": 1_700_000_000_000, "hasThumbnail": True}]
    store = c.app.state.store
    assert store.db.execute("PRAGMA user_version").fetchone()[0] == SCHEMA_VERSION == 2
    tables = {r[0] for r in store.db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert {"users", "tokens", "challenges", "files", "posts", "post_access", "votes"} <= tables

    # the new features work on the migrated database
    mojang.account("Bob")
    pid = make_post(c, h, "builds/Old.litematic", title="From before", visibility="private", allowed=["Bob"])["id"]
    bob = Who(c, mojang, "Bob")
    c.post(f"/api/board/{pid}/vote", json={"value": 1}, headers=bob.h)

    # a restart keeps everything and migrates nothing twice
    c2 = make_client()
    p = c2.get(f"/api/board/{pid}", headers=bob.h).json()
    assert p["title"] == "From before" and p["score"] == 1 and p["hasThumbnail"] is True
    assert c2.get("/api/me", headers=h).json()["usedBytes"] == 2 * len(body)
    assert c2.app.state.store.db.execute("PRAGMA user_version").fetchone()[0] == SCHEMA_VERSION


def test_reconcile_posts_on_startup(make_client, mojang, data_dir):
    c = make_client()
    h = Who(c, mojang, "Alice").h
    upload(c, h, "a.litematic", thumb=True)
    keep = make_post(c, h)["id"]
    lose = make_post(c, h)["id"]
    (data_dir / "posts" / lose / "a.litematic").unlink()
    (data_dir / "posts" / keep / "thumb.png").unlink()
    (data_dir / "posts" / "orphan1234").mkdir()
    (data_dir / "posts" / "orphan1234" / "x.nbt").write_bytes(b"x")

    c2 = make_client()
    assert ids(c2.get("/api/board")) == [keep]
    assert c2.get(f"/api/board/{keep}").json()["hasThumbnail"] is False
    assert not (data_dir / "posts" / "orphan1234").exists()
