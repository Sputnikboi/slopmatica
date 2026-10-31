"""Website passwords, password login and its throttling, logging out everywhere, and weblogin's next path."""

import re
import sqlite3
import time

import pytest
from fastapi.testclient import TestClient

from slopmatica_cloud.app import SCHEMA_VERSION, hash_password, sha256_hex, verify_password

from conftest import bearer, gz, login
from test_board import SCHEMA_0_3_0, Who

CSRF = {"X-Requested-With": "slopmatica"}
PW = "correct horse battery"


def set_pw(web, new, current=None, status=200):
    body = {"new": new}
    if current is not None:
        body["current"] = current
    r = web.post("/api/account/password", json=body, headers=CSRF)
    assert r.status_code == status, r.text
    return r.json()


def pw_login(c, name, password, status=200, **kw):
    r = c.post("/api/login", json={"name": name, "password": password}, headers={**CSRF, **kw})
    assert r.status_code == status, r.text
    return r


def logged_in(c) -> bool:
    return c.get("/api/me").status_code == 200


@pytest.fixture
def alice(client, mojang):
    return Who(client, mojang, "Alice")


# ------------------------------------------------------------------ hashing


def test_hash_format_and_verify():
    h = hash_password(PW)
    assert re.fullmatch(r"scrypt\$16384\$8\$1\$[0-9a-f]{32}\$[0-9a-f]{128}", h)
    assert hash_password(PW) != h  # random salt
    assert verify_password(PW, h)
    assert not verify_password(PW + "x", h)
    assert not verify_password(PW, None)
    assert not verify_password(PW, "garbage")
    assert not verify_password(PW, "bcrypt$1$2$3$00$00")


# ------------------------------------------------------------------ set, change, remove


def test_set_change_remove(client, alice):
    web = alice.cookie_client(client.app)
    store = client.app.state.store
    assert web.post("/api/account/password", json={"new": PW}).status_code == 403  # CSRF header
    assert TestClient(client.app).post("/api/account/password", json={"new": PW}, headers=CSRF).status_code == 401
    for bad in ("short", "x" * 7, "x" * 201):
        set_pw(web, bad, status=400)
    assert set_pw(web, "x" * 8)["hasPassword"] is True  # the first one needs only the session
    stored, when = store.password_info(alice.uuid)
    assert re.fullmatch(r"scrypt\$16384\$8\$1\$[0-9a-f]{32}\$[0-9a-f]{128}", stored)
    assert abs(when - time.time()) < 5

    assert set_pw(web, PW, status=403)["error"] == "the current password is wrong"  # current is now required
    set_pw(web, PW, current="wrong guess", status=403)
    set_pw(web, "y" * 200, current="x" * 8)
    set_pw(web, PW, current="y" * 200)
    assert verify_password(PW, store.password_info(alice.uuid)[0])

    r = web.request("DELETE", "/api/account/password", json={"current": "nope nope"}, headers=CSRF)
    assert r.status_code == 403
    assert web.request("DELETE", "/api/account/password", json={"current": PW}).status_code == 403  # CSRF
    r = web.request("DELETE", "/api/account/password", json={"current": PW}, headers=CSRF)
    assert r.status_code == 200 and r.json()["hasPassword"] is False
    assert store.password_info(alice.uuid) == (None, None)
    r = web.request("DELETE", "/api/account/password", json={"current": PW}, headers=CSRF)
    assert r.status_code == 409
    set_pw(web, PW)  # with no password, setting one needs only the session again
    pw_login(TestClient(client.app), "Alice", PW)


def test_bearer_tokens_cannot_touch_passwords(client, alice):
    h = {**alice.h, **CSRF}
    assert client.post("/api/account/password", json={"new": PW}, headers=h).status_code == 401
    web = alice.cookie_client(client.app)
    set_pw(web, PW)
    r = client.request("DELETE", "/api/account/password", json={"current": PW}, headers=h)
    assert r.status_code == 401
    assert client.post("/api/account/password", json={"new": "x" * 9, "current": PW}, headers=h).status_code == 401
    assert verify_password(PW, client.app.state.store.password_info(alice.uuid)[0])


# ------------------------------------------------------------------ logging in


def test_password_login(client, alice, mojang):
    set_pw(alice.cookie_client(client.app), PW)
    c = TestClient(client.app)
    assert c.post("/api/login", json={"name": "Alice", "password": PW}).status_code == 403  # CSRF header
    r = pw_login(c, "aLiCe", PW)  # any case
    assert r.json() == {"uuid": alice.uuid, "name": "Alice"}
    cookie = r.headers["set-cookie"]
    assert cookie.startswith("slopmatica_session=") and "HttpOnly" in cookie and "Max-Age=2592000" in cookie
    assert "SameSite=lax" in cookie or "SameSite=Lax" in cookie
    assert c.get("/api/me").json()["name"] == "Alice"
    assert "Alice" in c.get("/").text and "Log in from the game" not in c.get("/").text
    # the session is a website session: it can't be used as a bearer token
    assert c.get("/api/me", headers=bearer(c.cookies.get("slopmatica_session"))).status_code == 401

    # every failure looks the same
    Who(client, mojang, "Bob")  # exists but has no password
    bodies = set()
    for name, pw in (("Alice", "wrong password"), ("Nobody", PW), ("Bob", PW), ("bad name!", PW), ("Alice", ""),
                     ("Alice", "x" * 300)):
        r = pw_login(TestClient(client.app), name, pw, status=401)
        bodies.add(r.text)
        assert "set-cookie" not in r.headers
    assert bodies == {'{"error":"wrong name or password"}'}
    assert client.post("/api/login", json={"name": "Alice"}, headers=CSRF).status_code == 400


def test_login_matches_the_current_name(client, alice, mojang):
    set_pw(alice.cookie_client(client.app), PW)
    mojang.rename("Alice", "Alicia")
    Who(client, mojang, "Alicia")  # logging in from the game updates the stored name
    pw_login(TestClient(client.app), "Alice", PW, status=401)
    assert pw_login(TestClient(client.app), "alicia", PW).json()["uuid"] == alice.uuid


def test_throttle_by_name(client, alice):
    set_pw(alice.cookie_client(client.app), PW)
    store = client.app.state.store
    # a success resets the name's counter
    for _ in range(9):
        pw_login(TestClient(client.app), "Alice", "wrong one", status=401)
    pw_login(TestClient(client.app), "Alice", PW)
    assert store.recent_failures("name:alice")[0] == 0
    for i in range(10):  # from different addresses: the name's limit still applies
        pw_login(TestClient(client.app, client=(f"10.0.0.{i}", 1)), "ALICE", "wrong one", status=401)
    r = pw_login(TestClient(client.app, client=("10.0.1.1", 1)), "Alice", PW, status=429)
    assert re.fullmatch(r"too many failed login attempts, try again in 1[45] minutes", r.json()["error"])
    # wrong "current password" guesses count too, and are refused while throttled
    web = alice.cookie_client(client.app)
    set_pw(web, "x" * 8, current=PW, status=429)
    # other names are unaffected
    pw_login(TestClient(client.app), "Bob", PW, status=401)
    # failures older than 15 minutes no longer count
    store.db.execute("UPDATE login_failures SET at = at - 901")
    pw_login(TestClient(client.app), "Alice", PW)


def test_throttle_by_ip(client, alice):
    set_pw(alice.cookie_client(client.app), PW)
    attacker = TestClient(client.app, client=("203.0.113.5", 1))
    for i in range(30):
        pw_login(attacker, f"guess{i}", "whatever1", status=401)
    pw_login(attacker, "Alice", PW, status=429)
    pw_login(TestClient(client.app, client=("203.0.113.6", 1)), "Alice", PW)
    # a client that isn't the local proxy can't pick its address with X-Forwarded-For
    pw_login(attacker, "Alice", PW, status=429, **{"X-Forwarded-For": "198.51.100.1"})


def test_throttle_behind_local_proxy(client, alice):
    set_pw(alice.cookie_client(client.app), PW)
    proxy = TestClient(client.app, client=("127.0.0.1", 1))
    for i in range(30):
        # the right-most entry is the one the proxy added; the client can't spoof it
        pw_login(proxy, f"guess{i}", "whatever1", status=401, **{"X-Forwarded-For": f"1.1.1.{i}, 198.51.100.7"})
    pw_login(proxy, "Alice", PW, status=429, **{"X-Forwarded-For": "198.51.100.7"})
    pw_login(proxy, "Alice", PW, **{"X-Forwarded-For": "198.51.100.8"})


# ------------------------------------------------------------------ sessions


def test_password_change_ends_other_sessions(client, alice):
    web1 = alice.cookie_client(client.app)
    web2 = alice.cookie_client(client.app)
    assert set_pw(web1, PW)["endedSessions"] == 1
    assert logged_in(web1) and not logged_in(web2)
    assert client.get("/api/me", headers=alice.h).status_code == 200  # the game's token is unaffected

    web3 = TestClient(client.app)
    pw_login(web3, "Alice", PW)
    set_pw(web1, "new password!", current=PW)
    assert logged_in(web1) and not logged_in(web3)
    pw_login(web3, "Alice", PW, status=401)
    pw_login(web3, "Alice", "new password!")
    r = web1.request("DELETE", "/api/account/password", json={"current": "new password!"}, headers=CSRF)
    assert r.status_code == 200
    assert logged_in(web1) and not logged_in(web3)


def test_logout_all(client, alice):
    web1 = alice.cookie_client(client.app)
    web2 = alice.cookie_client(client.app)
    assert web1.post("/api/account/logout-all").status_code == 403  # CSRF header
    r = web1.post("/api/account/logout-all", headers=CSRF)
    assert r.status_code == 200 and r.json()["endedSessions"] == 2
    assert "slopmatica_session=" in r.headers["set-cookie"] and "Max-Age=0" in r.headers["set-cookie"]
    assert not logged_in(web1) and not logged_in(web2)
    assert client.get("/api/me", headers=alice.h).status_code == 200
    # the game can do it too (its token survives)
    web3 = alice.cookie_client(client.app)
    assert client.post("/api/account/logout-all", headers=alice.h).json()["endedSessions"] == 1
    assert not logged_in(web3)
    assert client.get("/api/me", headers=alice.h).status_code == 200
    assert TestClient(client.app).post("/api/account/logout-all", headers=CSRF).status_code == 401


def test_logout_all_leaves_other_accounts(client, alice, mojang):
    bob = Who(client, mojang, "Bob").cookie_client(client.app)
    web = alice.cookie_client(client.app)
    web.post("/api/account/logout-all", headers=CSRF)
    assert logged_in(bob)


# ------------------------------------------------------------------ weblogin next


def weblogin_location(c, token, body=None, prefix=""):
    kw = {"json": body} if body is not None else {}
    r = c.post(f"{prefix}/api/weblogin", headers=bearer(token), **kw)
    assert r.status_code == 200, r.text
    path = "/" + r.json()["url"].split("://", 1)[1].split("/", 1)[1]
    r = TestClient(c.app).get(path, follow_redirects=False)
    assert r.status_code == 303
    return r.headers["location"]


@pytest.mark.parametrize("body,location", [
    (None, "/"),
    ({}, "/"),
    ({"next": None}, "/"),
    ({"next": "/account"}, "/account"),
    ({"next": "/board/abcdefghij?x=1#top"}, "/board/abcdefghij?x=1#top"),
    ({"next": "/"}, "/"),
])
def test_weblogin_next(client, alice, body, location):
    assert weblogin_location(client, alice.token, body) == location


@pytest.mark.parametrize("next_path", [
    "//evil.example", "https://evil.example/", "evil.example", "account", "/\\evil.example", "\\\\evil.example",
    "/ /x", "/x\r\nSet-Cookie: a=b", "/\t/evil.example", "", 5, ["/account"], "/" + "a" * 600, "javascript:alert(1)",
])
def test_weblogin_next_rejects_open_redirects(client, alice, next_path):
    r = client.post("/api/weblogin", headers=bearer(alice.token), json={"next": next_path})
    assert r.status_code == 400 and "next" in r.json()["error"]


def test_weblogin_body_must_be_an_object(client, alice):
    assert client.post("/api/weblogin", headers=bearer(alice.token), content=b"not json").status_code == 400
    assert client.post("/api/weblogin", headers=bearer(alice.token), json=["/account"]).status_code == 400


def test_weblogin_next_under_prefix(make_client, mojang):
    c = make_client(root_path="/slopmatica", public_url="https://box.example.ts.net")
    token = login(c, mojang, "Alice", prefix="/slopmatica")["token"]
    assert weblogin_location(c, token, {"next": "/account"}, prefix="/slopmatica") == "/slopmatica/account"
    assert weblogin_location(c, token, None, prefix="") == "/slopmatica/"


# ------------------------------------------------------------------ pages


def test_account_page(client, alice):
    anon = TestClient(client.app)
    r = anon.get("/account", follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/"
    web = alice.cookie_client(client.app)
    client.put("/api/files/a.litematic", content=gz(b"abc"), headers=alice.h)
    html = web.get("/account").text
    assert 'href="/account"' in html and 'aria-current="page"' in html
    assert "Set password" in html and "Log out of all browsers" in html and "/static/account.js" in html
    assert "of 1 GB" in html and "1 file</a>" in html and "0 board posts</a>" in html
    assert 'name="current"' not in html
    set_pw(web, PW)
    html = web.get("/account").text
    assert "Change password" in html and "Remove the password" in html and 'name="current"' in html
    # every logged-in page links to the account page from the header
    for url in ("/", "/board"):
        assert 'class="who-link' in web.get(url).text


def test_landing_page_login_form(client):
    html = TestClient(client.app).get("/").text
    assert 'id="login-form"' in html and 'action="/api/login"' in html
    assert 'autocomplete="current-password"' in html and "/static/account.js" in html
    assert "Log in from the game" in html
    assert "Set password" in html and "Open website" in html


def test_root_relative_urls_on_account_pages(make_client, mojang):
    prefix = "/slopmatica"
    c = make_client(root_path=prefix)
    who = Who(c, mojang, "Alice")
    web = who.cookie_client(c.app)
    r = web.post(f"{prefix}/api/account/password", json={"new": PW}, headers=CSRF)
    assert r.status_code == 200
    for html in (web.get(f"{prefix}/account").text, TestClient(c.app).get(f"{prefix}/").text):
        for attr in re.findall(r'(?:href|src|action)="([^"]*)"', html):
            if not attr.startswith("https://"):
                assert attr.startswith(prefix + "/"), attr
    anon = TestClient(c.app)
    r = anon.post(f"{prefix}/api/login", json={"name": "Alice", "password": PW}, headers=CSRF)
    assert r.status_code == 200 and "Path=/slopmatica" in r.headers["set-cookie"]


# ------------------------------------------------------------------ migration


def test_migration_adds_password_columns(make_client, mojang, data_dir):
    data_dir.mkdir(parents=True)
    db = sqlite3.connect(data_dir / "cloud.db")
    db.executescript(SCHEMA_0_3_0)
    t = int(time.time())
    uuid = "12345678-1234-1234-1234-123456789abc"
    db.execute("INSERT INTO users VALUES(?,?,?,?,?)", (uuid, "Veteran", 0, t, t))
    db.execute("INSERT INTO tokens VALUES(?,?,?,?,?)", (sha256_hex("pending-link"), "weblogin", uuid, t, t + 300))
    db.execute("INSERT INTO tokens VALUES(?,?,?,?,?)", (sha256_hex("old-session"), "session", uuid, t, t + 86400))
    db.commit()
    db.close()

    c = make_client()
    store = c.app.state.store
    assert store.db.execute("PRAGMA user_version").fetchone()[0] == SCHEMA_VERSION
    user_cols = {r[1] for r in store.db.execute("PRAGMA table_info(users)")}
    token_cols = {r[1] for r in store.db.execute("PRAGMA table_info(tokens)")}
    assert {"password_hash", "password_set"} <= user_cols and "next" in token_cols
    assert store.password_info(uuid) == (None, None)
    # a login link issued by 0.3.0 still works and lands on the root
    r = TestClient(c.app).get("/login/pending-link", follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/"
    # so does an existing session, which can now set a password
    old = TestClient(c.app)
    old.cookies.set("slopmatica_session", "old-session")
    assert old.post("/api/account/password", json={"new": PW}, headers=CSRF).status_code == 200
    pw_login(TestClient(c.app), "veteran", PW)
    make_client()  # restarting migrates nothing twice
