"""0.8.0: settings profiles, telemetry, display names and tags, avatars, Discord linking, and on the board
co-authors, links, versions, share links with OpenGraph tags, download counts and complexity; the 5 -> 6 migration."""

import hashlib
import json
import os
import re
import sqlite3
import struct
import time
import urllib.parse
import zlib
from array import array

import pytest
from fastapi.testclient import TestClient

from slopmatica_cloud import app as app_module, convert, images, nbt
from slopmatica_cloud.app import (
    CSP, MIGRATIONS, PROFILE_LIMIT, SCHEMA_VERSION, TELEMETRY_INSTALL_LIMIT, TELEMETRY_IP_LIMIT, MojangUnavailable,
    Settings, complexity_class, complexity_sql, fetch_skin,
)

from conftest import PNG, bearer
from test_board import CSRF, Who, make_post, schem, upload
from test_convert import OLD_DV, state, synthetic
from test_v070 import ADMIN, ADMIN_UUID, PLAYER, columns, not_whitelisted, report, v4_data_dir


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


def variety(n: int) -> bytes:
    """A .litematic of n blocks in a row, each a different block type."""
    air = state(OLD_DV, "minecraft:air")
    palette = [air] + [state(OLD_DV, f"minecraft:test_block_{i}") for i in range(n)]
    region = convert.Region("Row", (0, 0, 0), (n, 1, 1), palette, array("I", range(1, n + 1)))
    return nbt.dumps(convert.write(convert.Schematic("Row", "Alice", OLD_DV, [region]), "litematic"))


def post_json(c, h, path="a.litematic", status=201, **extra):
    body = {"path": path, "title": "My build", "visibility": "public", **extra}
    r = c.post("/api/board", json=body, headers=h)
    assert r.status_code == status, r.text
    return r.json()


# ------------------------------------------------------------------ versions and settings


def test_schema_version():
    assert SCHEMA_VERSION == 6  # 0.9.0 added no migration


def test_settings_from_env():
    s = Settings.from_env({})
    assert s.discord_enabled is False and s.skin_hosts == frozenset({"textures.minecraft.net"})
    env = {"SLOPMATICA_DISCORD_CLIENT_ID": "123", "SLOPMATICA_DISCORD_CLIENT_SECRET": "sec"}
    assert Settings.from_env(env).discord_enabled is False  # all three are needed
    env["SLOPMATICA_DISCORD_REDIRECT"] = " https://x.example/slopmatica/account/discord/callback "
    s = Settings.from_env(env)
    assert s.discord_enabled and s.discord_redirect == "https://x.example/slopmatica/account/discord/callback"
    assert Settings.from_env({"SLOPMATICA_MOJANG_SESSION_PROFILE": "http://h/p"}).mojang_session_profile_url == "http://h/p"


def test_csp_needs_no_image_hosts():
    assert "img-src 'self' data:;" in CSP and "mc-heads" not in CSP


# ------------------------------------------------------------------ migration 5 -> 6

STALE = "fedcba98-1234-1234-1234-123456789abc"  # an older account whose stored name is the same as PLAYER's


def v5_data_dir(data_dir):
    """A 0.7.0 data directory: 0.6.0's plus migration 5 (as 0.7.0 ran it), a second account with the same
    Minecraft name (a stale row after renames), a badge and whitelist entries."""
    body = v4_data_dir(data_dir)
    db = sqlite3.connect(data_dir / "cloud.db")
    db.executescript(MIGRATIONS[5] + "\nPRAGMA user_version = 5;")
    t = int(time.time())
    db.execute("INSERT INTO users(uuid, name, is_admin, created, last_login, last_seen) VALUES(?,?,0,?,?,?)",
               (STALE, "player", t - 5000, t - 4000, t - 4000))
    db.execute("INSERT INTO whitelist VALUES(?,?, 'manual', ?, NULL)", (PLAYER, "Player", t * 1000))
    db.execute("INSERT INTO badges VALUES(?, 'verified', ?, NULL)", (PLAYER, t * 1000))
    db.commit()
    db.close()
    return body


def test_migrates_a_v5_database(make_client, data_dir):
    body = v5_data_dir(data_dir)
    c = make_client(whitelist_enforced=True)
    db = c.app.state.store.db
    assert db.execute("PRAGMA user_version").fetchone()[0] == 6
    users, posts = columns(db, "users"), columns(db, "posts")
    assert users[-8:] == ["display_name", "display_key", "display_tag", "display_custom", "avatar", "avatar_version",
                          "discord_id", "discord_name"]
    assert posts[-6:] == ["version", "version_label", "mc_version", "distinct_count", "links", "downloads"]
    for table in ("post_versions", "post_authors", "post_downloads", "profiles", "telemetry", "meta"):
        assert columns(db, table), table
    # display names start as the Minecraft names; of two accounts with one name, the older stays untagged
    rows = {r["uuid"]: r for r in db.execute("SELECT uuid, display_name, display_tag, display_custom FROM users")}
    assert rows[STALE]["display_name"] == "player" and rows[STALE]["display_tag"] is None
    assert rows[PLAYER]["display_name"] == "Player" and re.fullmatch(r"[A-Z2-9]{4}", rows[PLAYER]["display_tag"])
    assert rows[ADMIN_UUID]["display_name"] == ADMIN and rows[ADMIN_UUID]["display_tag"] is None
    assert all(r["display_custom"] == 0 for r in rows.values())
    with pytest.raises(sqlite3.IntegrityError):  # (name, tag) is unique
        db.execute("UPDATE users SET display_key='player', display_tag=NULL WHERE uuid=?", (PLAYER,))
    # the old post: version 1, nothing new set, and its block types counted from the snapshot at startup
    post = c.get("/api/board/HousePost1").json()
    assert post["version"] == 1 and post["versionCount"] == 1 and post["versionLabel"] == "" and post["mc"] is None
    assert post["coauthors"] == [] and post["links"] == [] and post["downloads"] == 0
    assert post["distinctTypes"] == synthetic().distinct_types() == 4 and post["complexity"] == "simple"
    assert post["author"] == {"uuid": PLAYER, "name": "Player", "badges": ["verified"], "displayName": "Player"}
    # the player's data and quota usage are as they were
    me = c.get("/api/me", headers=bearer("playertok")).json()
    assert me["usedBytes"] == 2 * len(body) and me["displayName"] == "Player" and me["tag"] == rows[PLAYER]["display_tag"]
    assert c.get("/api/files", headers=bearer("playertok")).json()["files"][0]["path"] == "house.litematic"
    # the new tables take data, and a restart migrates nothing twice
    assert c.post("/api/profiles", headers=bearer("playertok"), json={"name": "Mine", "settings": {}}).status_code == 201
    salt = db.execute("SELECT value FROM meta WHERE key='ip_salt'").fetchone()[0]
    c2 = make_client(whitelist_enforced=True)
    db2 = c2.app.state.store.db
    assert db2.execute("PRAGMA user_version").fetchone()[0] == 6
    assert db2.execute("SELECT value FROM meta WHERE key='ip_salt'").fetchone()[0] == salt
    assert db2.execute("SELECT display_tag FROM users WHERE uuid=?", (PLAYER,)).fetchone()[0] == rows[PLAYER]["display_tag"]
    assert len(c2.get("/api/profiles", headers=bearer("playertok")).json()["profiles"]) == 1


def test_migration_6_is_one_transaction(make_client, data_dir, monkeypatch):
    v5_data_dir(data_dir)
    monkeypatch.setitem(MIGRATIONS, 6, MIGRATIONS[6] + "\nCREATE TABLE profiles (x);")  # fails at the end
    with pytest.raises(sqlite3.OperationalError):
        make_client()
    db = sqlite3.connect(data_dir / "cloud.db")
    assert db.execute("PRAGMA user_version").fetchone()[0] == 5
    assert "display_name" not in columns(db, "users") and "version" not in columns(db, "posts")
    assert db.execute("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('profiles', 'telemetry')").fetchone()[0] == 0
    db.close()
    monkeypatch.undo()
    assert make_client().app.state.store.db.execute("PRAGMA user_version").fetchone()[0] == 6


# ------------------------------------------------------------------ settings profiles


def test_profiles_contract(gated, ppl):
    c, h = gated, ppl.alice.h
    assert c.get("/api/profiles", headers=h).json() == {"profiles": [], "limit": PROFILE_LIMIT} and PROFILE_LIMIT == 6
    before = int(time.time())
    r = c.post("/api/profiles", headers=h, json={"name": "  Survival  ", "settings": {"renderLayers": 3, "x": [1]}})
    assert r.status_code == 201
    created = r.json()
    assert set(created) == {"id", "name", "updated"} and created["name"] == "Survival"
    assert re.fullmatch(r"[A-Za-z0-9_-]{10}", created["id"]) and before <= created["updated"] <= time.time() + 1
    pid = created["id"]
    assert c.get("/api/profiles", headers=h).json() == {"profiles": [created], "limit": 6}
    got = c.get(f"/api/profiles/{pid}", headers=h).json()
    assert got == {**created, "settings": {"renderLayers": 3, "x": [1]}}
    # PUT replaces the settings (the name stays unless given) and moves updated on
    store = c.app.state.store
    store.db.execute("UPDATE profiles SET updated = updated - 100 WHERE id=?", (pid,))
    r = c.put(f"/api/profiles/{pid}", headers={**h, "Content-Type": "application/json"}, content=json.dumps({"settings": {"a": True}}))
    assert r.status_code == 200 and r.json()["name"] == "Survival" and r.json()["updated"] >= created["updated"]
    assert set(r.json()) == {"id", "name", "updated"}
    assert c.get(f"/api/profiles/{pid}", headers=h).json()["settings"] == {"a": True}
    assert c.put(f"/api/profiles/{pid}", headers=h, json={"settings": {}, "name": "Creative"}).json()["name"] == "Creative"
    assert c.put(f"/api/profiles/{pid}", headers=h, json={"name": "Only name"}).json()["name"] == "Only name"
    assert c.put(f"/api/profiles/{pid}", headers=h, json={}).status_code == 400
    # profiles are per account: someone else's id is 404 everywhere
    for method in ("get", "put", "delete"):
        r = getattr(c, method)(f"/api/profiles/{pid}", headers=ppl.bob.h, **({"json": {"settings": {}}} if method == "put" else {}))
        assert r.status_code == 404 and r.json() == {"error": "no such profile"}, method
    assert c.get("/api/profiles", headers=ppl.bob.h).json()["profiles"] == []
    assert c.get("/api/profiles/nope", headers=h).status_code == 404
    assert c.delete(f"/api/profiles/{pid}", headers=h).status_code == 204
    assert c.get(f"/api/profiles/{pid}", headers=h).status_code == 404
    assert c.delete(f"/api/profiles/{pid}", headers=h).status_code == 404


def test_profile_validation_and_access(gated, ppl):
    c, h = gated, ppl.alice.h

    def make(status=201, **body):
        r = c.post("/api/profiles", headers=h, json=body)
        assert r.status_code == status, r.text
        return r.json()

    make(400, name="x", settings=[1, 2])
    make(400, name="x", settings="{}")
    make(400, name="x")
    make(400, name="", settings={})
    make(400, name="x" * 41, settings={})
    make(400, name="a\nb", settings={})
    assert make(name="x" * 40, settings={})["name"] == "x" * 40
    make(413, name="big", settings={"blob": "x" * (64 * 1024)})
    assert make(name="fits", settings={"blob": "x" * (60 * 1024)})["name"] == "fits"
    assert c.post("/api/profiles", headers={**h, "Content-Type": "application/json"}, content=b"{nope").status_code == 400
    assert c.post("/api/profiles", headers=h, content=b"x" * (80 * 1024)).status_code == 413
    # login, the whitelist gate, and the CSRF header for cookies
    assert c.get("/api/profiles").status_code == 401
    not_whitelisted(c.get("/api/profiles", headers=ppl.outsider.h))
    not_whitelisted(c.post("/api/profiles", headers=ppl.outsider.h, json={"name": "x", "settings": {}}))
    web = ppl.alice.cookie_client(c.app)
    assert web.get("/api/profiles").status_code == 200
    assert web.post("/api/profiles", json={"name": "x", "settings": {}}).status_code == 403
    assert web.post("/api/profiles", json={"name": "x", "settings": {}}, headers=CSRF).status_code == 201


def test_profile_limit_and_admins(gated, ppl):
    c = gated
    for i in range(6):
        assert c.post("/api/profiles", headers=ppl.alice.h, json={"name": f"P{i}", "settings": {}}).status_code == 201
    r = c.post("/api/profiles", headers=ppl.alice.h, json={"name": "P6", "settings": {}})
    assert r.status_code == 409 and r.json()["code"] == "limit" and set(r.json()) == {"error", "code"}
    pid = c.get("/api/profiles", headers=ppl.alice.h).json()["profiles"][0]["id"]
    c.delete(f"/api/profiles/{pid}", headers=ppl.alice.h)
    assert c.post("/api/profiles", headers=ppl.alice.h, json={"name": "P6", "settings": {}}).status_code == 201
    # admins have no limit
    assert c.get("/api/profiles", headers=ppl.admin.h).json()["limit"] is None
    for i in range(8):
        assert c.post("/api/profiles", headers=ppl.admin.h, json={"name": f"A{i}", "settings": {}}).status_code == 201
    names = [p["name"] for p in c.get("/api/profiles", headers=ppl.admin.h).json()["profiles"]]
    assert names == [f"A{i}" for i in range(8)]  # oldest first


# ------------------------------------------------------------------ telemetry


def report_body(install="install-1", **extra):
    body = {
        "install": install, "mod": "0.8.0", "mc": "26.3", "loader": "fabric", "start": 1790000000, "end": 1790001800,
        "specs": {"os": "Windows 11 10.0", "cpuCores": 8, "maxMemoryMb": 4096, "gpu": "NVIDIA GeForce RTX 3060",
                  "gpuVendor": "NVIDIA Corporation", "backend": "OpenGL", "renderDistance": 12, "width": 1920, "height": 1080},
        "render": {"frames": 1000, "avgMs": 8.4, "p50Ms": 7.5, "p99Ms": 21.5, "over33Ms": 3, "maxSections": 40},
        "schematics": ["local", "cloud"], "settingsChanged": ["renderLayers", "ghostOpacity"], "keys": {"menu": 4, "flip": 1},
    }
    for key, value in extra.items():
        if isinstance(value, dict) and isinstance(body.get(key), dict):
            body[key] = {**body[key], **value}
        else:
            body[key] = value
    return body


def test_telemetry_accepts_and_stores_nothing_personal(gated, ppl):
    c = gated
    anon = TestClient(c.app, client=("203.0.113.9", 4444))
    r = anon.post("/api/telemetry", json=report_body())
    assert r.status_code == 204 and r.content == b""
    store = c.app.state.store
    row = dict(store.db.execute("SELECT * FROM telemetry").fetchone())
    assert row["install"] == hashlib.sha256(b"install-1").hexdigest()
    assert row["gpu_vendor"] == "NVIDIA Corporation" and row["p50_ms"] == 7.5 and row["max_memory_mb"] == 4096
    assert json.loads(row["schematics"]) == ["local", "cloud"]
    assert json.loads(row["settings_changed"]) == ["ghostOpacity", "renderLayers"]
    assert json.loads(row["keys"]) == {"menu": 4, "flip": 1}
    # the address is nowhere in the database
    dump = "\n".join(store.db.iterdump())
    assert "203.0.113.9" not in dump and "install-1" not in dump
    # no login needed, and a bearer token or cookie changes nothing about what is stored
    assert c.post("/api/telemetry", json=report_body("install-2"), headers=ppl.alice.h).status_code == 204
    assert ppl.alice.uuid not in "\n".join(str(tuple(r)) for r in store.db.execute("SELECT * FROM telemetry"))


@pytest.mark.parametrize("body", [
    [1, 2], "x", {}, {"install": ""}, {"install": 5}, {"install": "x" * 101},
    {"install": "a", "specs": "nope"}, {"install": "a", "render": []}, {"install": "a", "specs": {"cpuCores": "8"}},
    {"install": "a", "specs": {"cpuCores": True}}, {"install": "a", "render": {"p50Ms": -1}},
    {"install": "a", "start": 1e15}, {"install": "a", "schematics": "local"}, {"install": "a", "keys": [1]},
    {"install": "a", "mod": 7}, {"install": "a", "settingsChanged": {"a": 1}},
])
def test_telemetry_garbage_is_400(gated, body):
    r = gated.post("/api/telemetry", json=body)
    assert r.status_code == 400, body  # the mod deletes it
    assert gated.app.state.store.db.execute("SELECT COUNT(*) FROM telemetry").fetchone()[0] == 0


def test_telemetry_is_loose_about_details(gated):
    c = gated
    body = report_body(schematics=["local", "bogus", 7, "board:", "local"], settingsChanged=["ok", "bad name!", 3],
                       keys={"menu": 2, "neg": -1, "float": 1.5, "bad key!": 1})
    assert c.post("/api/telemetry", json=body).status_code == 204
    r = c.post("/api/telemetry", json={"install": "minimal"})  # everything else is optional
    assert r.status_code == 204
    rows = c.app.state.store.db.execute("SELECT schematics, settings_changed, keys FROM telemetry ORDER BY id").fetchall()
    assert json.loads(rows[0][0]) == ["local"] and json.loads(rows[0][1]) == ["ok"]
    assert json.loads(rows[0][2]) == {"menu": 2, "flip": 1}  # (flip from report_body's defaults)
    assert tuple(rows[1]) == ("[]", "[]", "{}")
    # too big: 400 (not 413), so the mod drops it instead of retrying it forever
    r = c.post("/api/telemetry", json=report_body(gpu="x" * 40000))
    assert r.status_code == 400 and "32 KiB" in r.json()["error"]


def test_telemetry_keeps_only_public_post_ids(gated, ppl):
    c, h = gated, ppl.alice.h
    upload(c, h, "a.litematic")
    pub = make_post(c, h, title="Pub")["id"]
    unl = make_post(c, h, title="Unl", visibility="unlisted")["id"]
    priv = make_post(c, h, title="Priv", visibility="private")["id"]
    hid = make_post(c, h, title="Hid")["id"]
    c.post(f"/api/admin/posts/{hid}/hide", headers=ppl.admin.h, json={"hidden": True})
    sources = [f"board:{pid}" for pid in (pub, unl, priv, hid)] + ["board:Nonexisten", "board:x", "board:private", "shared"]
    assert c.post("/api/telemetry", json=report_body(schematics=sources)).status_code == 204
    (stored,) = c.app.state.store.db.execute("SELECT schematics FROM telemetry").fetchone()
    assert json.loads(stored) == [f"board:{pub}", "board:unlisted", "board:private", "board:gone", "shared"]
    for pid in (unl, priv, hid):
        assert pid not in stored


def test_telemetry_rate_limits(gated):
    c = gated
    for i in range(TELEMETRY_INSTALL_LIMIT):
        assert c.post("/api/telemetry", json=report_body("same")).status_code == 204
    r = c.post("/api/telemetry", json=report_body("same"))
    assert r.status_code == 429  # the mod keeps the report and tries later
    # the address limit: other installs from the same address
    for i in range(TELEMETRY_IP_LIMIT - TELEMETRY_INSTALL_LIMIT):
        assert c.post("/api/telemetry", json=report_body(f"other-{i}")).status_code == 204
    assert c.post("/api/telemetry", json=report_body("fresh")).status_code == 429
    # another address is counted on its own (but the full install stays full)
    other = TestClient(c.app, client=("198.51.100.7", 1))
    assert other.post("/api/telemetry", json=report_body("fresh")).status_code == 204
    assert other.post("/api/telemetry", json=report_body("same")).status_code == 429
    n = c.app.state.store.db.execute("SELECT COUNT(*) FROM telemetry").fetchone()[0]
    assert n == TELEMETRY_IP_LIMIT + 1


def test_telemetry_summary_is_admin_only(gated, ppl):
    c, h = gated, ppl.alice.h
    upload(c, h, "a.litematic")
    pub = make_post(c, h, title="Popular farm")["id"]
    reports = [
        report_body("i1", render={"p50Ms": 5.5, "p99Ms": 20.5}, schematics=[f"board:{pub}", "local"]),
        report_body("i1", render={"p50Ms": 7.5, "p99Ms": 30.5}, settingsChanged=["renderLayers"], keys={"menu": 10}),
        report_body("i2", render={"p50Ms": 9.5, "p99Ms": 40.5}, schematics=[f"board:{pub}"],
                    specs={"gpuVendor": "AMD", "os": "Linux 6.8.0-45-generic", "maxMemoryMb": 16384}, mod="0.7.0"),
        report_body("i3", render={"frames": 0}, schematics=["board:gone"], specs={"os": "Mac OS X 14.5", "maxMemoryMb": 1024}),
    ]
    for body in reports:
        assert c.post("/api/telemetry", json=body).status_code == 204
    store = c.app.state.store
    store.db.execute("UPDATE telemetry SET received = received - 10 * 86400 WHERE id = 4")  # older than a week
    assert c.get("/api/admin/telemetry").status_code == 401
    assert c.get("/api/admin/telemetry", headers=h).status_code == 403
    t = c.get("/api/admin/telemetry", headers=ppl.admin.h).json()
    assert (t["reports7"], t["installs7"], t["reports30"], t["installs30"]) == (3, 2, 4, 3)
    assert t["byGpuVendor"] == [
        {"key": "NVIDIA", "reports": 2, "installs": 1, "p50": 6.5, "p99": 25.5},
        {"key": "AMD", "reports": 1, "installs": 1, "p50": 9.5, "p99": 40.5},
    ]
    assert [(x["key"], x["reports"]) for x in t["byModVersion"]] == [("0.8.0", 2), ("0.7.0", 1)]
    # settings: per install, from its latest report
    assert {"name": "renderLayers", "count": 3} in t["settings"]
    assert {"name": "ghostOpacity", "count": 2} in t["settings"]
    assert t["keys"][0] == {"name": "menu", "uses": 4 + 10 + 4 + 4, "installs": 3}
    assert {"name": "board (public)", "count": 2} in t["sources"] and {"name": "board:gone", "count": 1} in t["sources"]
    assert t["topPosts"] == [{"id": pub, "title": "Popular farm", "installs": 2}]
    assert {x["name"] for x in t["os"]} == {"Windows 11", "Linux", "macOS"}
    assert t["memory"] == [{"name": "under 2 GB", "count": 1}, {"name": "4 to 8 GB", "count": 1},
                           {"name": "16 GB or more", "count": 1}]
    # a post that is no longer public drops out of the top list
    c.patch(f"/api/board/{pub}", headers=h, json={"visibility": "private"})
    assert c.get("/api/admin/telemetry", headers=ppl.admin.h).json()["topPosts"] == []
    # the admin page's tab, 404 to everyone else
    html = ppl.admin.cookie_client(c.app).get("/admin?tab=telemetry").text
    assert 'aria-current="page">Telemetry' in html and "By GPU vendor" in html and "<td>NVIDIA</td>" in html
    assert "<script>" not in html
    for viewer in (TestClient(c.app), ppl.alice.cookie_client(c.app)):
        assert viewer.get("/admin?tab=telemetry").status_code == 404


def test_old_telemetry_is_deleted_at_startup(make_client):
    c = make_client()
    assert c.post("/api/telemetry", json=report_body()).status_code == 204
    c.app.state.store.db.execute("UPDATE telemetry SET received = received - 181 * 86400")
    c2 = make_client()
    assert c2.app.state.store.db.execute("SELECT COUNT(*) FROM telemetry").fetchone()[0] == 0


# ------------------------------------------------------------------ display names and tags


def set_name(c, who, status=200, **body):
    r = c.put("/api/account/display-name", headers=who.h, json=body)
    assert r.status_code == status, r.text
    return r.json()


def test_display_name_tags(gated, ppl):
    c = gated
    assert c.get("/api/me", headers=ppl.alice.h).json()["displayName"] == "Alice"  # the Minecraft name by default
    first = set_name(c, ppl.alice, name="  The   Builder ")
    assert first == {"displayName": "The Builder", "tag": None}  # the first to take it: no tag
    second = set_name(c, ppl.bob, name="the builder")  # any case counts as the same name
    assert re.fullmatch(r"[A-Z2-9]{4}", second["tag"]) and "carries the tag #" + second["tag"] in second["warning"]
    me = c.get("/api/me", headers=ppl.bob.h).json()
    assert me["displayName"] == "the builder" and me["tag"] == second["tag"] and me["name"] == "Bob"
    # changing only the case keeps the tag (and the slot)
    assert set_name(c, ppl.bob, name="The Builder")["tag"] == second["tag"]
    # a chosen tag must be unique with the name, in any case
    assert set_name(c, ppl.carol, name="THE BUILDER", tag="abc") == {"displayName": "THE BUILDER", "tag": "abc"}
    r = set_name(c, ppl.bob, 409, tag="ABC")
    assert r["code"] == "taken"
    assert set_name(c, ppl.bob, tag="Xy9z1")["tag"] == "Xy9z1"
    assert set_name(c, ppl.bob, 409, tag=None)["code"] == "taken"  # Alice has it without a tag
    for bad in ("ab", "abcdef", "a-b", "#ABC", 12):
        set_name(c, ppl.bob, 400, tag=bad)
    # once Alice moves on, the plain name is free
    set_name(c, ppl.alice, name="Alice Again")
    assert set_name(c, ppl.bob, tag="") == {"displayName": "The Builder", "tag": None}
    # names: 3 to 24 characters, a letter or digit, no #
    for bad in ("ab", "x" * 25, "a#b", "...", "a\tb\nc" * 9, 7):
        set_name(c, ppl.carol, 400, name=bad)
    set_name(c, ppl.carol, 400)  # nothing to change
    assert set_name(c, ppl.carol, name="Ünïcode Ok")["displayName"] == "Ünïcode Ok"
    # back to the Minecraft name (which then follows renames again)
    assert set_name(c, ppl.carol, name=None) == {"displayName": "Carol", "tag": None}
    # members only, like the rest of the account's cloud
    not_whitelisted(c.put("/api/account/display-name", headers=ppl.outsider.h, json={"name": "Outside"}))


def test_display_name_follows_minecraft_renames_until_chosen(gated, ppl, mojang):
    c = gated
    set_name(c, ppl.alice, name="Bobby")
    mojang.rename("Bob", "Bobby")  # Bob's new Minecraft name is taken as Alice's display name
    from conftest import login
    login(c, mojang, "Bobby")
    me = c.get("/api/me", headers=ppl.bob.h).json()
    assert me["name"] == "Bobby" and me["displayName"] == "Bobby" and me["tag"]  # tagged: Alice was first
    # a chosen display name stays through renames
    set_name(c, ppl.carol, name="Carol The Great")
    mojang.rename("Carol", "Caroline")
    login(c, mojang, "Caroline")
    assert c.get("/api/me", headers=ppl.carol.h).json()["displayName"] == "Carol The Great"


def test_tags_are_for_the_owner_and_admins(gated, ppl):
    c = gated
    set_name(c, ppl.alice, name="Twin")
    tag = set_name(c, ppl.bob, name="Twin")["tag"]
    upload(c, ppl.bob.h, "a.litematic")
    post = make_post(c, ppl.bob.h, title="Bob's tower")
    # public pages and the post API: the name alone
    anon = TestClient(c.app)
    for html in (anon.get("/board").text, anon.get(f"/board/{post['id']}").text, anon.get(f"/u/{ppl.bob.uuid}").text):
        assert ">Twin<" in html and tag not in html
    assert post["author"]["displayName"] == "Twin" and tag not in json.dumps(c.get(f"/api/board/{post['id']}").json())
    assert c.get(f"/api/users/{ppl.bob.uuid}").json()["displayName"] == "Twin"
    # the owner sees it on the account page
    assert f"#{tag}" in ppl.bob.cookie_client(c.app).get("/account").text
    assert "#" + tag not in ppl.alice.cookie_client(c.app).get(f"/u/{ppl.bob.uuid}").text
    # admins: in the user list and in reports
    users = c.get("/api/admin/users?q=twin", headers=ppl.admin.h).json()["users"]
    assert sorted((u["name"], u["displayName"], u["tag"]) for u in users) == [("Alice", "Twin", None), ("Bob", "Twin", tag)]
    admin_web = ppl.admin.cookie_client(c.app)
    assert f'Twin<span class="tag">#{tag}</span>' in admin_web.get("/admin?tab=users").text
    report(c, ppl.alice, kind="user", target=ppl.bob.uuid)
    rep = c.get("/api/admin/reports", headers=ppl.admin.h).json()["reports"][0]
    assert rep["target"]["displayName"] == "Twin" and rep["target"]["tag"] == tag
    assert rep["reporter"] == {"uuid": ppl.alice.uuid, "name": "Alice", "displayName": "Twin", "tag": None}
    assert f'Twin<span class="tag">#{tag}</span>' in admin_web.get("/admin?tab=reports").text


# ------------------------------------------------------------------ avatars


def skin_png(width=64, height=64, hat=None, head=(200, 150, 100)):
    """A skin: the face (8..15, 8..15) in head, the hat layer (40..47, 8..15) from hat(x, y) or transparent."""
    px = []
    for y in range(height):
        for x in range(width):
            if 8 <= x < 16 and 8 <= y < 16:
                px.append((*head, 255))
            elif 40 <= x < 48 and 8 <= y < 16 and hat:
                px.append(hat(x - 40, y - 8))
            else:
                px.append((0, 0, 0, 0))
    return images.encode_png(width, height, px)


def face_pixel(png: bytes, x: int, y: int):
    w, h, px = images.decode_png(png, max_pixels=128 * 128)
    assert (w, h) == (128, 128)
    return px[(y * 16 + 8) * 128 + x * 16 + 8]


def test_avatar_from_the_minecraft_skin(gated, ppl, mojang):
    c, store = gated, gated.app.state.store
    hexid = ppl.alice.uuid.replace("-", "")
    # the hat covers the top-left pixel: fully, and half see-through next to it
    mojang.skins[hexid] = skin_png(hat=lambda x, y: (10, 20, 30, 255) if (x, y) == (0, 0) else
                                   (0, 0, 255, 128) if (x, y) == (1, 0) else (0, 0, 0, 0))
    r = TestClient(c.app).get(f"/avatar/{ppl.alice.uuid}")
    assert r.status_code == 200 and r.headers["content-type"] == "image/png"
    assert face_pixel(r.content, 0, 0) == (10, 20, 30, 255)
    assert face_pixel(r.content, 1, 0) == (99, 74, 177, 255)  # blended over the face
    assert face_pixel(r.content, 5, 5) == (200, 150, 100, 255)
    assert mojang.skin_lookups == [hexid]
    assert (store.skins_dir / f"{ppl.alice.uuid}.png").read_bytes() == mojang.skins[hexid]  # the skin is cached
    # cached for a day: no new lookup, also without dashes in the address
    assert c.get(f"/avatar/{hexid}").content == r.content and mojang.skin_lookups == [hexid]
    face = store.skins_dir / f"{ppl.alice.uuid}.face.png"
    old = time.time() - 86400 - 60
    os.utime(face, (old, old))
    mojang.skins[hexid] = skin_png(head=(1, 2, 3))
    assert face_pixel(c.get(f"/avatar/{ppl.alice.uuid}").content, 5, 5) == (1, 2, 3, 255)
    assert len(mojang.skin_lookups) == 2
    # Mojang down: the stale face is shown, and Mojang is left alone for a while
    os.utime(face, (old, old))
    mojang.status_override = 500
    calls = len(mojang.calls)
    assert face_pixel(c.get(f"/avatar/{ppl.alice.uuid}").content, 5, 5) == (1, 2, 3, 255)
    assert c.get(f"/avatar/{ppl.alice.uuid}").status_code == 200
    assert len(mojang.calls) == calls + 1
    # pages use our own address for every head
    page = ppl.alice.cookie_client(c.app).get("/board").text
    assert f'src="/avatar/{ppl.alice.uuid}"' in page and "mc-heads" not in page


def test_avatar_edge_cases(gated, ppl, mojang):
    c = gated
    placeholder = images.placeholder_face()
    # no skin (the default ones): the placeholder
    mojang.skins[ppl.bob.uuid.replace("-", "")] = None
    assert c.get(f"/avatar/{ppl.bob.uuid}").content == placeholder
    # a stranger's uuid is never looked up
    assert c.get("/avatar/11111111-2222-3333-4444-555555555555").content == placeholder
    assert mojang.skin_lookups == [ppl.bob.uuid.replace("-", "")]
    # a whitelisted player without an account is
    stranger = "99999999-2222-3333-4444-555555555555"
    c.app.state.store.whitelist_add(stranger, "Wl", "manual", None)
    c.get(f"/avatar/{stranger}")
    assert mojang.skin_lookups[-1] == stranger.replace("-", "")
    assert c.get("/avatar/not-a-uuid").status_code == 404
    # a skin that isn't a readable 64x64 / 64x32 PNG: the placeholder
    mojang.skins[ppl.carol.uuid.replace("-", "")] = images.encode_png(32, 32, [(1, 1, 1, 255)] * 1024)
    assert c.get(f"/avatar/{ppl.carol.uuid}").content == placeholder


def test_legacy_skin_hat_rule():
    opaque = skin_png(64, 32, hat=lambda x, y: (9, 9, 9, 255), head=(50, 60, 70))
    assert face_pixel(images.face_png(opaque), 3, 3) == (50, 60, 70, 255)  # an opaque old hat layer isn't drawn
    holey = skin_png(64, 32, hat=lambda x, y: (9, 9, 9, 255 if x else 0), head=(50, 60, 70))
    assert face_pixel(images.face_png(holey), 3, 3) == (9, 9, 9, 255)
    assert face_pixel(images.face_png(holey), 0, 3) == (50, 60, 70, 255)
    with pytest.raises(ValueError):
        images.face_png(b"not a png")


def filtered_png(width, height, rows, ctype=6, plte=b"", trns=b"", depth=8):
    """A PNG with each row stored with a different filter (0..4), to check the decoder's unfiltering."""
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    bpp = max(1, channels * depth // 8)

    def paeth(a, b, c):
        p = a + b - c
        pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
        return a if pa <= pb and pa <= pc else b if pb <= pc else c

    raw, prev = bytearray(), bytes(len(rows[0]))
    for y, row in enumerate(rows):
        f = y % 5
        out = bytearray()
        for i, v in enumerate(row):
            a = row[i - bpp] if i >= bpp else 0
            b, c = prev[i], prev[i - bpp] if i >= bpp else 0
            pred = (0, a, b, (a + b) >> 1, paeth(a, b, c))[f]
            out.append((v - pred) & 0xFF)
        raw += bytes([f]) + out
        prev = row

    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

    return (images.PNG_MAGIC + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, depth, ctype, 0, 0, 0))
            + (chunk(b"PLTE", plte) if plte else b"") + (chunk(b"tRNS", trns) if trns else b"")
            + chunk(b"IDAT", zlib.compress(bytes(raw))) + chunk(b"IEND", b""))


def test_png_decoder_formats():
    rows = [bytes((x * 7 + y * 13 + k * 31) % 256 for x in range(6) for k in range(4)) for y in range(7)]
    w, h, px = images.decode_png(filtered_png(6, 7, rows))
    assert (w, h) == (6, 7) and px[3 * 6 + 2] == tuple(rows[3][8:12])
    rgb = [bytes((x * 5 + y) % 256 for x in range(6) for _ in range(3)) for y in range(5)]
    _, _, px = images.decode_png(filtered_png(6, 5, rgb, ctype=2))
    assert px[6 + 1] == (6, 6, 6, 255)
    # a 4-bit palette with transparency
    plte = bytes([255, 0, 0, 0, 255, 0, 0, 0, 255])
    pal_rows = [bytes([0x01, 0x20]) for _ in range(3)]  # pixels 0, 1, 2, 0
    _, _, px = images.decode_png(filtered_png(4, 3, pal_rows, ctype=3, plte=plte, trns=b"\x00", depth=4))
    assert px[:4] == [(255, 0, 0, 0), (0, 255, 0, 255), (0, 0, 255, 255), (255, 0, 0, 0)]
    # 16 bits per channel: the high byte
    deep = [bytes([1, 2, 3, 4, 5, 6, 7, 8]) for _ in range(2)]
    _, _, px = images.decode_png(filtered_png(1, 2, deep, ctype=6, depth=16))
    assert px[0] == (1, 3, 5, 7)
    with pytest.raises(ValueError):
        images.decode_png(images.encode_png(600, 600, [(0, 0, 0, 0)] * 360000))  # over the pixel cap


def test_fetch_skin_through_the_session_server(mojang, data_dir):
    s = Settings(data_dir=data_dir, mojang_session_profile_url=mojang.session_profile_url, skin_hosts=frozenset({"127.0.0.1"}))
    hexid = "0123456789abcdef0123456789abcdef"
    mojang.skins[hexid] = skin_png()
    assert fetch_skin(s, "01234567-89ab-cdef-0123-456789abcdef") == mojang.skins[hexid]
    mojang.skins[hexid] = None
    assert fetch_skin(s, hexid) is None  # no skin set
    assert fetch_skin(s, "f" * 32) is None  # unknown player (204)
    # a skin URL on any other host is refused, so a strange answer can't make us fetch elsewhere
    mojang.skins[hexid] = skin_png()
    with pytest.raises(MojangUnavailable, match="unexpected skin host"):
        fetch_skin(Settings(data_dir=data_dir, mojang_session_profile_url=mojang.session_profile_url), hexid)
    mojang.status_override = 503
    with pytest.raises(MojangUnavailable):
        fetch_skin(s, hexid)


def jpeg(width, height):
    return (b"\xff\xd8" + b"\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
            + b"\xff\xdb\x00\x04\x00\x00"  # a (bogus, short) table segment before the frame header
            + b"\xff\xc0\x00\x11\x08" + struct.pack(">HH", height, width) + b"\x03" + b"\x01\x22\x00" * 3 + b"\xff\xd9")


def test_custom_icon(gated, ppl, mojang):
    c, h = gated, ppl.alice.h
    icon = images.encode_png(100, 80, [(255, 0, 0, 255)] * 8000)
    r = c.put("/api/account/avatar", headers=h, content=icon)
    assert r.status_code == 200 and r.json() == {"avatar": f"http://127.0.0.1:8095/avatar/{ppl.alice.uuid}?v=1", "custom": True}
    got = c.get(f"/avatar/{ppl.alice.uuid}")
    assert got.content == icon and got.headers["content-type"] == "image/png"
    assert c.get("/api/me", headers=h).json()["avatar"].endswith("?v=1")
    # JPEG replaces it
    pic = jpeg(512, 300)
    assert c.put("/api/account/avatar", headers=h, content=pic).json()["avatar"].endswith("?v=2")
    got = c.get(f"/avatar/{ppl.alice.uuid}")
    assert got.content == pic and got.headers["content-type"] == "image/jpeg"
    assert not (c.app.state.store.root / "users" / ppl.alice.uuid / "avatar.png").exists()
    web = ppl.alice.cookie_client(c.app)
    assert f'src="/avatar/{ppl.alice.uuid}?v=2"' in web.get("/account").text  # header and account page
    # refused: not an image, a faked header, too big a picture, too many bytes
    for bad, why in ((b"GIF89a" + b"\x00" * 20, "PNG or JPEG"), (images.PNG_MAGIC + b"\x00" * 30, "PNG or JPEG"),
                     (b"\xff\xd8\xff\xd9", "PNG or JPEG"), (jpeg(513, 10), "513 x 10"),
                     (images.encode_png(10, 600, [(0, 0, 0, 0)] * 6000), "10 x 600")):
        r = c.put("/api/account/avatar", headers=h, content=bad)
        assert r.status_code == 400 and why in r.json()["error"], why
    assert c.put("/api/account/avatar", headers=h, content=icon + b"\x00" * (256 * 1024)).status_code == 413
    assert c.get(f"/avatar/{ppl.alice.uuid}").content == pic  # unchanged
    # reset to the Minecraft face
    mojang.skins[ppl.alice.uuid.replace("-", "")] = skin_png()
    r = c.delete("/api/account/avatar", headers=h)
    assert r.json() == {"avatar": f"http://127.0.0.1:8095/avatar/{ppl.alice.uuid}?v=3", "custom": False}
    assert face_pixel(c.get(f"/avatar/{ppl.alice.uuid}").content, 2, 2) == (200, 150, 100, 255)
    assert not any((c.app.state.store.root / "users" / ppl.alice.uuid).glob("avatar.*"))
    # cookies need the CSRF header; outsiders can't
    assert web.put("/api/account/avatar", content=icon).status_code == 403
    not_whitelisted(c.put("/api/account/avatar", headers=ppl.outsider.h, content=icon))


# ------------------------------------------------------------------ Discord


DISCORD = {"discord_client_id": "4242", "discord_client_secret": "shh",
           "discord_redirect": "https://cloud.example/account/discord/callback"}


def test_discord_disabled_without_settings(gated, ppl):
    web = ppl.alice.cookie_client(gated.app)
    assert "Discord linking isn&rsquo;t set up yet" in web.get("/account").text
    for url in ("/account/discord/link", "/account/discord/callback?state=x&code=y"):
        r = web.get(url, follow_redirects=False)
        assert r.status_code == 303 and r.headers["location"] == "/account?discord=off#discord"
    assert "Discord linking isn&rsquo;t set up yet." in web.get("/account?discord=off").text


def test_discord_linking(make_client, mojang, monkeypatch):
    c = make_client(**DISCORD)
    alice, bob = Who(c, mojang, "Alice"), Who(c, mojang, "Bob")
    calls = []
    monkeypatch.setattr(app_module, "discord_token", lambda s, code: calls.append(("token", code)) or f"tok-{code}")
    monkeypatch.setattr(app_module, "discord_user", lambda s, tok: calls.append(("me", tok)) or ("80351110224678912", "alicecord"))
    web = alice.cookie_client(c.app)
    page = web.get("/account").text
    assert 'href="/account/discord/link">Link Discord' in page

    def start(client):
        r = client.get("/account/discord/link", follow_redirects=False)
        assert r.status_code == 303
        url = urllib.parse.urlsplit(r.headers["location"])
        assert f"{url.scheme}://{url.netloc}{url.path}" == "https://discord.com/oauth2/authorize"
        q = dict(urllib.parse.parse_qsl(url.query))
        assert q["client_id"] == "4242" and q["scope"] == "identify" and q["response_type"] == "code"
        assert q["redirect_uri"] == DISCORD["discord_redirect"]
        return q["state"]

    def back(client, **params):
        r = client.get("/account/discord/callback?" + urllib.parse.urlencode(params), follow_redirects=False)
        assert r.status_code == 303
        return r.headers["location"].removeprefix("/account?discord=").removesuffix("#discord")

    # a forged or missing state: refused, and Discord is never called
    assert back(web, state="forged", code="c1") == "state"
    assert back(web, code="c1") == "state"
    # a state from another browser of the same account: refused too (it is tied to the session)
    other = alice.cookie_client(c.app)
    state = start(other)
    assert back(web, state=state, code="c1") == "state"
    assert back(other, state=state, code="c1") == "state"  # used up by the failed try
    assert calls == []
    # cancelled on Discord
    assert back(web, state=start(web), error="access_denied") == "cancelled"
    # the real thing
    assert back(web, state=start(web), code="good") == "linked"
    assert calls == [("token", "good"), ("me", "tok-good")]
    user = c.app.state.store.get_user(alice.uuid)
    assert (user.discord_id, user.discord_name) == ("80351110224678912", "alicecord")
    page = web.get("/account?discord=linked").text
    assert "Linked to <strong>alicecord</strong>" in page and "Your Discord account is linked." in page
    users = c.get(f"/api/admin/users?q={alice.uuid}", headers=Who(c, mojang, ADMIN).h).json()["users"]
    assert users[0]["discord"] == {"id": "80351110224678912", "name": "alicecord"}
    # the same Discord account can't be linked to a second Slopmatica account
    bob_web = bob.cookie_client(c.app)
    assert back(bob_web, state=start(bob_web), code="again") == "taken"
    assert c.app.state.store.get_user(bob.uuid).discord_id is None
    # Discord failing
    monkeypatch.setattr(app_module, "discord_token", lambda s, code: (_ for _ in ()).throw(app_module.DiscordError("x")))
    assert back(bob_web, state=start(bob_web), code="x") == "failed"
    # unlinking
    assert web.delete("/api/account/discord").status_code == 403  # CSRF header
    assert web.delete("/api/account/discord", headers=CSRF).json() == {"discord": None}
    assert c.app.state.store.get_user(alice.uuid).discord_id is None
    # logged out: back to the start page
    assert TestClient(c.app).get("/account/discord/link", follow_redirects=False).headers["location"] == "/"


def test_discord_http_calls(data_dir, monkeypatch):
    """The token exchange and the user lookup, with urlopen mocked: what is sent and what is kept."""
    s = Settings(data_dir=data_dir, **DISCORD)
    sent = []

    class Resp:
        def __init__(self, body):
            self.body = body

        def read(self, n=-1):
            return self.body

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

    def fake_urlopen(req, timeout=None):
        sent.append(req)
        if req.full_url == app_module.DISCORD_TOKEN:
            return Resp(json.dumps({"access_token": "abc", "token_type": "Bearer"}).encode())
        return Resp(json.dumps({"id": "123456789", "username": "handle", "global_name": "Shown Name"}).encode())

    monkeypatch.setattr(app_module.urllib.request, "urlopen", fake_urlopen)
    assert app_module.discord_token(s, "the-code") == "abc"
    form = dict(urllib.parse.parse_qsl(sent[0].data.decode()))
    assert form == {"client_id": "4242", "client_secret": "shh", "grant_type": "authorization_code", "code": "the-code",
                    "redirect_uri": DISCORD["discord_redirect"]}
    assert sent[0].get_method() == "POST" and sent[0].headers["Content-type"] == "application/x-www-form-urlencoded"
    assert app_module.discord_user(s, "abc") == ("123456789", "handle")
    assert sent[1].full_url == app_module.DISCORD_ME and sent[1].headers["Authorization"] == "Bearer abc"
    monkeypatch.setattr(app_module.urllib.request, "urlopen", lambda req, timeout=None: Resp(b'{"error": "invalid_grant"}'))
    with pytest.raises(app_module.DiscordError):
        app_module.discord_token(s, "bad")
    with pytest.raises(app_module.DiscordError):
        app_module.discord_user(s, "bad")


# ------------------------------------------------------------------ co-authors and links


def test_coauthors(gated, ppl, mojang):
    c, h = gated, ppl.alice.h
    upload(c, h, "a.litematic")
    p = post_json(c, h, coauthors=[{"player": "bob"}, {"name": "  Free   Guy "}, {"player": "Alice"}, {"name": "free guy"},
                                    {"player": "BOB"}])
    assert p["coauthors"] == [{"uuid": ppl.bob.uuid, "name": "Bob", "displayName": "Bob"},
                              {"uuid": None, "name": "Free Guy", "displayName": "Free Guy"}]
    assert p["author"]["uuid"] == ppl.alice.uuid  # the uploader is always the owner
    # names are current: the display name, and the Minecraft name after a rename (so the edit form still saves)
    set_name(c, ppl.bob, name="Bob the Builder")
    mojang.rename("Bob", "Robert")
    from conftest import login
    login(c, mojang, "Robert")
    assert c.get(f"/api/board/{p['id']}").json()["coauthors"][0] == {"uuid": ppl.bob.uuid, "name": "Robert",
                                                                      "displayName": "Bob the Builder"}
    assert 'name="players" value="Robert"' in ppl.alice.cookie_client(c.app).get(f"/board/{p['id']}").text
    assert c.patch(f"/api/board/{p['id']}", headers=h, json={"coauthors": [{"player": "Robert"}, {"name": "Free Guy"}]}).status_code == 200
    page = TestClient(c.app).get(f"/board/{p['id']}").text
    assert f'<a href="/u/{ppl.bob.uuid}" data-user="{ppl.bob.uuid}">Bob the Builder</a>' in page and "<span>Free Guy</span>" in page
    # the co-author's profile and the author filter list it; search finds them too
    assert "My build" in TestClient(c.app).get(f"/u/{ppl.bob.uuid}").text
    assert [x["id"] for x in c.get(f"/api/board?author={ppl.bob.uuid}").json()["posts"]] == [p["id"]]
    assert [x["id"] for x in c.get("/api/board?q=free+guy").json()["posts"]] == [p["id"]]
    assert [x["id"] for x in c.get("/api/board?q=builder").json()["posts"]] == [p["id"]]
    assert c.get("/api/board?author=nope").status_code == 400
    # validation
    r = post_json(c, h, status=400, coauthors=[{"player": "Nobody"}])
    assert r["code"] == "unknown_player" and r["player"] == "Nobody"
    post_json(c, h, status=400, coauthors=[{"name": f"N{i}"} for i in range(11)])
    post_json(c, h, status=400, coauthors=[{"foo": 1}])
    post_json(c, h, status=400, coauthors=["Bob"])
    post_json(c, h, status=400, coauthors=[{"name": "x" * 41}])
    post_json(c, h, status=400, coauthors={"player": "Bob"})
    # editing replaces the list; leaving it out keeps it
    r = c.patch(f"/api/board/{p['id']}", headers=h, json={"title": "Renamed"})
    assert len(r.json()["coauthors"]) == 2
    r = c.patch(f"/api/board/{p['id']}", headers=h, json={"coauthors": [{"name": "Solo"}]})
    assert r.json()["coauthors"] == [{"uuid": None, "name": "Solo", "displayName": "Solo"}]
    assert c.get(f"/api/board?author={ppl.bob.uuid}").json()["posts"] == []


def test_links(gated, ppl):
    c, h = gated, ppl.alice.h
    upload(c, h, "a.litematic")
    links = ["https://www.youtube.com/watch?v=dQw4w9WgXcQ", "http://Planet.Example.org/thread/1", "https://x.example/a"]
    p = post_json(c, h, links=links + [links[0], "  "])
    assert p["links"] == links  # repeats and blanks dropped
    page = TestClient(c.app).get(f"/board/{p['id']}").text
    assert '>youtube.com</a>' in page and '>planet.example.org</a>' in page
    assert 'href="https://www.youtube.com/watch?v=dQw4w9WgXcQ" rel="noopener noreferrer nofollow ugc"' in page
    for bad in (["ftp://example.com/x"], ["javascript:alert(1)"], ["https://user:pw@example.com/"], ["https:///nohost"],
                ["https://exa mple.com"], ["https://e.com/" + "x" * 300], [f"https://e{i}.com" for i in range(5)], "https://e.com", [3]):
        post_json(c, h, status=400, links=bad)
    r = c.patch(f"/api/board/{p['id']}", headers=h, json={"links": []})
    assert r.json()["links"] == []


# ------------------------------------------------------------------ versions


def snapshot_bytes(data_dir, post_id, filename, version=None):
    folder = data_dir / "posts" / post_id / (f"v{version}" if version else "")
    return (folder / filename).read_bytes()


def test_versions(make_client, mojang, data_dir):
    c = make_client()
    alice, bob = Who(c, mojang, "Alice"), Who(c, mojang, "Bob")
    h = alice.h
    upload(c, h, "a.litematic", thumb=True)
    upload(c, h, "b.litematic", variety(40))
    p = post_json(c, h, versionLabel="first", mc="26.2")
    pid = p["id"]
    assert (p["version"], p["versionLabel"], p["mc"], p["versionCount"]) == (1, "first", "26.2", 1)
    v1 = snapshot_bytes(data_dir, pid, "a.slopmatic")
    # a new version from another cloud file
    r = c.post(f"/api/board/{pid}/versions", headers=h, json={"path": "b.litematic", "label": "second", "mc": "26.3"})
    assert r.status_code == 201, r.text
    p2 = r.json()
    assert (p2["version"], p2["versionLabel"], p2["mc"], p2["versionCount"]) == (2, "second", "26.3", 2)
    assert p2["filename"] == "b.slopmatic" and p2["blockCount"] == 40 and p2["hasThumbnail"] is False
    assert p2["sourcePath"] == "b.litematic" and p2["complexity"] == "complex"
    assert snapshot_bytes(data_dir, pid, "a.slopmatic", 1) == v1  # the old one moved to v1/
    assert sorted(f.name for f in (data_dir / "posts" / pid / "v1").iterdir()) == ["a.slopmatic", "thumb.png"]
    versions = c.get(f"/api/board/{pid}/versions").json()["versions"]
    assert [(v["version"], v["label"], v["mc"], v["current"]) for v in versions] == [(2, "second", "26.3", True),
                                                                                      (1, "first", "26.2", False)]
    assert versions[1]["hasThumbnail"] is True and versions[1]["complexity"] == "simple"
    # every older version can be downloaded, exported, previewed and shown in 3D
    assert c.get(f"/api/board/{pid}/file?version=1").content == v1
    assert c.get(f"/api/board/{pid}/file").content == snapshot_bytes(data_dir, pid, "b.slopmatic")
    assert c.get(f"/api/board/{pid}/file?version=2").content == snapshot_bytes(data_dir, pid, "b.slopmatic")
    assert c.get(f"/api/board/{pid}/thumbnail?version=1").content == PNG
    assert c.get(f"/api/board/{pid}/thumbnail").status_code == 404
    assert c.get(f"/api/board/{pid}/export?version=1&format=nbt").status_code == 200
    assert c.get(f"/api/board/{pid}/model?version=1").json()["count"] > 0
    assert any(f.name.startswith("model-") for f in (data_dir / "posts" / pid / "v1").iterdir())
    for bad in ("9", "x", "-1"):
        assert c.get(f"/api/board/{pid}/file?version={bad}").status_code == 404
    # a version uploaded directly (any format, converted the same way)
    r = c.post(f"/api/board/{pid}/versions?filename=c.nbt&label=third", headers={**h, "Content-Type": "application/octet-stream"},
               content=schem("nbt"))
    assert r.status_code == 201, r.text
    p3 = r.json()
    assert (p3["version"], p3["filename"], p3["format"], p3["mc"], p3["sourcePath"]) == (3, "c.slopmatic", "slopmatic", None, "")
    assert c.post(f"/api/board/{pid}/refresh", headers=h).status_code == 409  # nothing to re-read
    # the board filter by Minecraft version looks at every version
    for mc, found in (("26.2", True), ("26.3", True), ("1.20", False)):
        assert [x["id"] for x in c.get(f"/api/board?mc={mc}").json()["posts"]] == ([pid] if found else []), mc
    assert c.get("/api/board?mc=bad version").status_code == 400
    assert "Minecraft 26.3" in TestClient(c.app).get("/board").text  # offered in the filter
    # the page: a version list, the newest by default, older ones on request
    page = TestClient(c.app).get(f"/board/{pid}").text
    assert "data-version-select" in page and "v3 &middot; third (newest)" in page and "v1 &middot; first &middot; MC 26.2" in page
    old = TestClient(c.app).get(f"/board/{pid}?version=1").text
    assert "You are looking at an older version" in old and f'href="/api/board/{pid}/file?version=1" download="a.slopmatic"' in old
    assert TestClient(c.app).get(f"/board/{pid}?version=7").status_code == 404
    # bad uploads
    def new_version(status, who=alice, headers=None, **kw):
        r = c.post(f"/api/board/{pid}/versions", headers={**who.h, **(headers or {})}, **kw)
        assert r.status_code == status, r.text
        return r.json()
    new_version(403, who=bob, json={"path": "b.litematic"})
    new_version(404, json={"path": "missing.litematic"})
    new_version(400, json={"path": 5})
    new_version(400, json={"path": "b.litematic", "mc": "not a version"})
    raw = {"Content-Type": "application/octet-stream"}
    new_version(400, content=schem(), headers=raw)  # no filename
    new_version(400, params={"filename": "x/y.litematic"}, content=schem(), headers=raw)
    new_version(400, params={"filename": "y.txt"}, content=schem(), headers=raw)
    new_version(400, params={"filename": "y.litematic"}, content=b"plain", headers=raw)
    assert c.get(f"/api/board/{pid}").json()["version"] == 3


def test_versions_count_toward_the_quota(make_client, mojang, data_dir):
    c = make_client()
    alice = Who(c, mojang, "Alice")
    h = alice.h
    sizes = [upload(c, h, n, body)["size"] for n, body in (("a.litematic", schem()), ("b.litematic", variety(20)))]
    pid = post_json(c, h)["id"]
    c.post(f"/api/board/{pid}/versions", headers=h, json={"path": "b.litematic"})
    snaps = [len(snapshot_bytes(data_dir, pid, "a.slopmatic", 1)), len(snapshot_bytes(data_dir, pid, "b.slopmatic"))]
    used = c.get("/api/me", headers=h).json()["usedBytes"]
    assert used == sum(sizes) + sum(snaps)
    mine = c.get("/api/board?tab=mine", headers=h).json()
    assert mine["postBytes"] == sum(snaps) and mine["postCount"] == 1
    # full: a new version is refused, and nothing moves
    c.app.state.store.set_quota(alice.uuid, used + 10)
    r = c.post(f"/api/board/{pid}/versions", headers=h, json={"path": "a.litematic"})
    assert r.status_code == 413 and r.json()["usedBytes"] == used
    assert c.get(f"/api/board/{pid}").json()["version"] == 2 and not (data_dir / "posts" / pid / "v2").exists()
    # deleting an older version frees its bytes; the current one only goes with the post
    assert c.delete(f"/api/board/{pid}/versions/2", headers=h).status_code == 409
    assert c.delete(f"/api/board/{pid}/versions/1", headers=Who(c, mojang, "Bob").h).status_code == 403
    assert c.delete(f"/api/board/{pid}/versions/1", headers=h).status_code == 204
    assert c.delete(f"/api/board/{pid}/versions/1", headers=h).status_code == 404
    assert c.get("/api/me", headers=h).json()["usedBytes"] == sum(sizes) + snaps[1]
    assert not (data_dir / "posts" / pid / "v1").exists()
    assert c.post(f"/api/board/{pid}/versions", headers=h, json={"path": "a.litematic"}).status_code == 201
    # deleting the post frees every version
    c.delete(f"/api/board/{pid}", headers=h)
    assert c.get("/api/me", headers=h).json()["usedBytes"] == sum(sizes)


def test_versions_survive_a_restart(make_client, mojang, data_dir):
    c = make_client()
    h = Who(c, mojang, "Alice").h
    upload(c, h, "a.litematic", thumb=True)
    upload(c, h, "b.litematic", variety(3))
    pid = post_json(c, h)["id"]
    c.post(f"/api/board/{pid}/versions", headers=h, json={"path": "b.litematic"})
    pdir = data_dir / "posts" / pid
    (pdir / "v99").mkdir()  # a folder without a version
    (pdir / "v1" / "leftover.part").write_bytes(b"x")
    (pdir / "stray").mkdir()
    c2 = make_client()
    assert sorted(f.name for f in pdir.iterdir()) == ["b.slopmatic", "v1"]
    assert sorted(f.name for f in (pdir / "v1").iterdir()) == ["a.slopmatic", "thumb.png"]
    assert c2.get(f"/api/board/{pid}/file?version=1").status_code == 200
    # a version whose snapshot is gone is dropped
    (pdir / "v1" / "a.slopmatic").unlink()
    c3 = make_client()
    assert c3.get(f"/api/board/{pid}").json()["versionCount"] == 1 and not (pdir / "v1").exists()


# ------------------------------------------------------------------ share links and OpenGraph


def test_share_link_and_og_tags(make_client, mojang):
    c = make_client(public_url="https://box.example.ts.net/slopmatica", root_path="/slopmatica")
    from conftest import login
    h = bearer(login(c, mojang, "Alice", prefix="/slopmatica")["token"])
    r = c.put("/slopmatica/api/files/a.litematic", content=schem(), headers=h)
    assert r.status_code == 201
    c.put("/slopmatica/api/thumbnails/a.litematic", content=PNG, headers=h)
    desc = "A very long description. " * 20
    p = c.post("/slopmatica/api/board", headers=h, json={"path": "a.litematic", "title": 'Big "farm" <3', "visibility": "public",
                                                           "description": desc}).json()
    assert p["shareUrl"] == f"https://box.example.ts.net/slopmatica/b/{p['id']}"
    for prefix in ("/slopmatica", ""):
        r = c.get(f"{prefix}/b/{p['id']}", follow_redirects=False)
        assert r.status_code == 302 and r.headers["location"] == f"/slopmatica/board/{p['id']}"
    assert c.get("/slopmatica/b/bad").status_code == 404
    page = TestClient(c.app).get(f"/slopmatica/board/{p['id']}").text
    assert '<meta property="og:title" content="Big &#34;farm&#34; &lt;3">' in page
    image = f"https://box.example.ts.net/slopmatica/api/board/{p['id']}/thumbnail?v={p['updated']}"
    assert f'<meta property="og:image" content="{image}">' in page and '<meta name="twitter:card" content="summary_large_image">' in page
    assert f'<meta property="og:url" content="https://box.example.ts.net/slopmatica/board/{p["id"]}">' in page
    og_desc = re.search(r'<meta property="og:description" content="([^"]*)">', page).group(1)
    assert len(og_desc) == 200 and og_desc.endswith("...")
    assert f'value="https://box.example.ts.net/slopmatica/b/{p["id"]}"' in page  # the share box
    # the image really is there for crawlers
    assert TestClient(c.app).get(urllib.parse.urlsplit(image).path + "?" + urllib.parse.urlsplit(image).query).content == PNG
    # no thumbnail, no description: a summary card describing the build
    c.put("/slopmatica/api/files/b.litematic", content=schem(), headers=h)
    q = c.post("/slopmatica/api/board", headers=h, json={"path": "b.litematic", "title": "Plain", "visibility": "unlisted"}).json()
    page = TestClient(c.app).get(f"/slopmatica/board/{q['id']}").text
    assert '<meta name="twitter:card" content="summary">' in page and "og:image" not in page
    assert '<meta property="og:description" content="A Minecraft schematic by Alice, 3 x 2 x 2, 8 blocks.">' in page
    # private posts don't advertise themselves
    r = c.post("/slopmatica/api/board", headers=h, json={"path": "b.litematic", "title": "Secret", "visibility": "private"}).json()
    assert TestClient(c.app).get(f"/slopmatica/b/{r['id']}").status_code == 404
    assert "data-share-url" not in c.get(f"/slopmatica/board/{r['id']}", headers=h).text


# ------------------------------------------------------------------ download counts


def test_download_counts(gated, ppl):
    c = gated
    upload(c, ppl.alice.h, "a.litematic")
    pid = make_post(c, ppl.alice.h)["id"]
    downloads = lambda: c.get(f"/api/board/{pid}").json()["downloads"]  # noqa: E731
    one = TestClient(c.app, client=("203.0.113.1", 1))
    for _ in range(3):  # refreshing doesn't inflate it
        assert one.get(f"/api/board/{pid}/file").status_code == 200
    one.get(f"/api/board/{pid}/export?format=nbt")
    assert downloads() == 1
    TestClient(c.app, client=("203.0.113.2", 1)).get(f"/api/board/{pid}/file")
    assert downloads() == 2
    # accounts count once, whether they download or save it
    c.get(f"/api/board/{pid}/file", headers=ppl.bob.h)
    c.post(f"/api/board/{pid}/save", headers=ppl.bob.h)
    c.get(f"/api/board/{pid}/file", headers=ppl.bob.h)
    assert downloads() == 3
    assert c.post(f"/api/board/{pid}/save", headers=ppl.carol.h).status_code == 201
    assert downloads() == 4
    # the author's own downloads don't count; nor do failed ones or the 3D view
    c.get(f"/api/board/{pid}/file", headers=ppl.alice.h)
    c.get(f"/api/board/{pid}/model")
    c.get(f"/api/board/{pid}/export?format=bogus", headers=ppl.outsider.h)
    assert downloads() == 4
    # shown on cards and the post page
    assert "&#8681; 4" in TestClient(c.app).get("/board").text
    assert "<dt>Downloads</dt><dd>4</dd>" in TestClient(c.app).get(f"/board/{pid}").text
    # addresses are only kept as keyed hashes, and only for a couple of days
    store = c.app.state.store
    dump = "\n".join(store.db.iterdump())
    assert "203.0.113" not in dump
    whos = [r[0] for r in store.db.execute("SELECT who FROM post_downloads")]
    assert sum(w.startswith("ip:") for w in whos) == 2 and f"u:{ppl.bob.uuid}" in whos
    store.db.execute("UPDATE post_downloads SET at = at - 3 * 86400 WHERE who LIKE 'ip:%'")
    store.prune()
    assert store.db.execute("SELECT COUNT(*) FROM post_downloads WHERE who LIKE 'ip:%'").fetchone()[0] == 0
    assert downloads() == 4  # the count stays


# ------------------------------------------------------------------ complexity


@pytest.mark.parametrize("n,expected", [(None, None), (0, "simple"), (9, "simple"), (10, "moderate"), (29, "moderate"),
                                         (30, "complex"), (79, "complex"), (80, "very-complex"), (5000, "very-complex")])
def test_complexity_thresholds(n, expected):
    assert complexity_class(n) == expected


def test_complexity_sql_matches():
    db = sqlite3.connect(":memory:")
    db.execute("CREATE TABLE t (n INTEGER)")
    values = [None, *range(0, 120)]
    db.executemany("INSERT INTO t VALUES(?)", [(n,) for n in values])
    got = [r[0] for r in db.execute(f"SELECT {complexity_sql('n')} FROM t ORDER BY rowid")]
    assert got == [complexity_class(n) for n in values]


def test_complexity_on_the_board(make_client, mojang):
    c = make_client()
    h = Who(c, mojang, "Alice").h
    for name, body in (("s.litematic", schem()), ("m.litematic", variety(12)), ("v.litematic", variety(85))):
        upload(c, h, name, body)
    ids = {}
    for name in ("s", "m", "v"):
        p = post_json(c, h, path=f"{name}.litematic")
        ids[name] = p["id"]
        assert p["distinctTypes"] == {"s": 4, "m": 12, "v": 85}[name]
    for cx, name in (("simple", "s"), ("moderate", "m"), ("very-complex", "v")):
        assert [x["id"] for x in c.get(f"/api/board?complexity={cx}").json()["posts"]] == [ids[name]]
    assert c.get("/api/board?complexity=complex").json()["posts"] == []
    assert c.get("/api/board?complexity=bogus").status_code == 400
    html = TestClient(c.app).get("/board").text
    assert 'href="/board?complexity=very-complex" title="85 different block and entity types">Very complex</a>' in html
    assert 'class="chip cx-chip cx-simple"' in html
    filtered = TestClient(c.app).get("/board?complexity=moderate").text
    assert "<strong>Moderate</strong>" in filtered and filtered.count('class="card post-card"') == 1
    assert TestClient(c.app).get("/board?complexity=bogus").status_code == 200  # the page just ignores it
    page = TestClient(c.app).get(f"/board/{ids['v']}").text
    assert "<dt>Block types</dt><dd>85</dd>" in page


# ------------------------------------------------------------------ the word filter on the new fields


def test_text_filter_on_new_fields(gated, ppl):
    c, h = gated, ppl.alice.h
    upload(c, h, "a.litematic")

    def refused(r, field):
        assert r.status_code == 400, r.text
        assert r.json()["code"] == "filtered" and r.json()["field"] == field

    refused(c.post("/api/profiles", headers=h, json={"name": "shit list", "settings": {}}), "profile name")
    refused(c.put("/api/account/display-name", headers=h, json={"name": "fuuuck you"}), "display name")
    refused(c.put("/api/account/display-name", headers=h, json={"tag": "sh1t"}), "tag")
    refused(c.post("/api/board", headers=h, json={"path": "a.litematic", "title": "x", "visibility": "public",
                                                    "coauthors": [{"name": "b i t c h"}]}), "co-author name")
    refused(c.post("/api/board", headers=h, json={"path": "a.litematic", "title": "x", "visibility": "public",
                                                    "versionLabel": "retarded edition"}), "version label")
    refused(c.post("/api/board", headers=h, json={"path": "a.litematic", "title": "x", "visibility": "public",
                                                    "links": ["https://example.com/fuck-this"]}), "link")
    pid = make_post(c, h)["id"]
    refused(c.patch(f"/api/board/{pid}", headers=h, json={"versionLabel": "shit"}), "version label")
    refused(c.post(f"/api/board/{pid}/versions", headers=h, json={"path": "a.litematic", "label": "cunt"}), "version label")
    refused(c.post(f"/api/board/{pid}/versions?filename=a.litematic&label=whore", content=schem(),
                   headers={**h, "Content-Type": "application/octet-stream"}), "version label")
    # Minecraft names of real accounts aren't held against the text, and admins aren't filtered
    assert c.put("/api/account/display-name", headers=ppl.admin.h, json={"name": "shit happens"}).status_code == 200
    assert c.get(f"/api/board/{pid}").json()["version"] == 1


# ------------------------------------------------------------------ pages


def test_new_pages_under_a_root_path(make_client, mojang):
    prefix = "/slopmatica"
    c = make_client(root_path=prefix, public_url="https://box.example.ts.net", **DISCORD)
    who = Who(c, mojang, "Alice")
    alice = who.h
    bob = Who(c, mojang, "Bob")
    admin = Who(c, mojang, ADMIN)
    c.put(f"{prefix}/api/files/a.litematic", content=schem(), headers=alice)
    c.put(f"{prefix}/api/files/b.litematic", content=variety(15), headers=alice)
    pid = c.post(f"{prefix}/api/board", headers=alice, json={
        "path": "a.litematic", "title": "T", "visibility": "public", "coauthors": [{"player": "Bob"}, {"name": "Z"}],
        "links": ["https://example.com"], "mc": "26.3"}).json()["id"]
    c.post(f"{prefix}/api/board/{pid}/versions", headers=alice, json={"path": "b.litematic"})
    c.post(f"{prefix}/api/telemetry", json=report_body(schematics=[f"board:{pid}"]))
    def browser(person):
        """Logged in over https: with an https PUBLIC_URL the session cookie is Secure."""
        b = TestClient(c.app, base_url="https://testserver")
        _, path = web_login(b, person.token)
        assert b.get(path, follow_redirects=False).status_code == 303
        return b

    from test_web import web_login
    web, admin_web = browser(who), browser(admin)
    pages = [
        web.get(f"{prefix}/account").text,
        web.get(f"{prefix}/board/{pid}").text,
        web.get(f"{prefix}/board/{pid}?version=1").text,
        web.get(f"{prefix}/board?mc=26.3&complexity=simple&author={bob.uuid}").text,
        TestClient(c.app).get(f"{prefix}/u/{bob.uuid}").text,
        web.get(f"{prefix}/").text,
        admin_web.get(f"{prefix}/admin?tab=telemetry").text,
        admin_web.get(f"{prefix}/admin?tab=users").text,
    ]
    checked = 0
    for html in pages:
        assert f'data-base="{prefix}"' in html and "<script>" not in html and "mc-heads" not in html
        for attr in re.findall(r'(?:href|src|action)="([^"]*)"', html):
            if attr.startswith("https://"):
                continue
            assert attr.startswith(prefix + "/"), attr
            checked += 1
    assert checked > 80
    assert 'name="players"' in pages[5] and 'name="links"' in pages[5]  # the post dialog on the files page
    assert 'data-version-form' in pages[1] and 'data-display-form' in pages[0] and f'href="{prefix}/account/discord/link"' in pages[0]
    assert TestClient(c.app).get(f"{prefix}/u/{'0' * 32}").status_code == 404
    assert TestClient(c.app).get(f"{prefix}/u/nope").status_code == 404
