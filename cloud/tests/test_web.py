import re

import pytest
from fastapi.testclient import TestClient

from slopmatica_cloud.app import Settings, create_app

from conftest import bearer, gz, login

CSRF = {"X-Requested-With": "slopmatica"}


def web_login(client, token, prefix=""):
    r = client.post(f"{prefix}/api/weblogin", headers=bearer(token))
    assert r.status_code == 200, r.text
    url = r.json()["url"]
    path = url.split("://", 1)[1].split("/", 1)[1]
    return url, "/" + path


def test_landing_page_logged_out(client):
    r = client.get("/")
    assert r.status_code == 200
    assert "text/html" in r.headers["content-type"]
    assert "Log in from the game" in r.text
    assert "Slopmatica menu (<kbd>M</kbd>) &rarr; <strong>Cloud</strong> &rarr; <strong>Open website</strong>" in r.text
    assert "Content-Security-Policy" in r.headers


def test_weblogin_requires_bearer(client, mojang):
    assert client.post("/api/weblogin").status_code == 401
    assert client.post("/api/weblogin", headers=bearer("bogus")).status_code == 401


def test_weblogin_flow(client, mojang):
    data = login(client, mojang, "Alice")
    url, path = web_login(client, data["token"])
    assert re.fullmatch(r"http://127\.0\.0\.1:8095/login/[A-Za-z0-9_-]{20,}", url)

    r = client.get(path, follow_redirects=False)
    assert r.status_code == 303
    assert r.headers["location"] == "/"
    cookie = r.headers["set-cookie"]
    assert cookie.startswith("slopmatica_session=")
    assert "HttpOnly" in cookie
    assert "SameSite=lax" in cookie or "SameSite=Lax" in cookie
    assert "Max-Age=2592000" in cookie
    assert "Path=/" in cookie
    assert "Secure" not in cookie

    # the link is single use
    other = TestClient(client.app)
    r2 = other.get(path, follow_redirects=False)
    assert r2.status_code == 400
    assert "set-cookie" not in r2.headers

    # cookie session works for reads
    me = client.get("/api/me")
    assert me.status_code == 200 and me.json()["name"] == "Alice"
    home = client.get("/")
    assert "Alice" in home.text
    assert data["uuid"] in home.text  # head avatar
    assert "of 1 GB" in home.text
    assert "schematics/cloud/" in home.text


def test_weblogin_expires(client, mojang):
    data = login(client, mojang)
    _, path = web_login(client, data["token"])
    client.app.state.store.db.execute("UPDATE tokens SET expires = 1 WHERE kind = 'weblogin'")
    assert client.get(path, follow_redirects=False).status_code == 400
    assert client.get("/api/me").status_code == 401


def test_weblogin_lifetime(client, mojang):
    data = login(client, mojang)
    web_login(client, data["token"])
    created, expires = client.app.state.store.db.execute(
        "SELECT created, expires FROM tokens WHERE kind='weblogin'"
    ).fetchone()
    assert expires - created == 300


def test_session_token_cannot_be_used_as_bearer_and_vice_versa(client, mojang):
    data = login(client, mojang)
    _, path = web_login(client, data["token"])
    r = client.get(path, follow_redirects=False)
    session = r.cookies.get("slopmatica_session") or client.cookies.get("slopmatica_session")
    assert session
    assert client.get("/api/me", headers=bearer(session)).status_code == 401
    fresh = TestClient(client.app)
    fresh.cookies.set("slopmatica_session", data["token"])
    assert fresh.get("/api/me").status_code == 401


def test_cookie_requires_csrf_header_for_mutations(client, mojang):
    data = login(client, mojang)
    _, path = web_login(client, data["token"])
    client.get(path)

    r = client.put("/api/files/a.litematic", content=gz())
    assert r.status_code == 403
    assert "X-Requested-With" in r.json()["error"]
    assert client.put("/api/files/a.litematic", content=gz(), headers=CSRF).status_code == 201

    assert client.put("/api/thumbnails/a.litematic", content=b"\x89PNG\r\n\x1a\n").status_code == 403
    assert client.post("/api/files/move", json={"from": "a.litematic", "to": "b.litematic"}).status_code == 403
    assert client.post("/api/files/move", json={"from": "a.litematic", "to": "b.litematic"}, headers=CSRF).status_code == 200
    assert client.delete("/api/files/b.litematic").status_code == 403
    assert client.get("/api/files/b.litematic").status_code == 200  # reads need no header
    assert client.delete("/api/files/b.litematic", headers=CSRF).status_code == 204
    # bearer clients (the mod) do not need the header
    assert client.put("/api/files/c.litematic", content=gz(), headers=bearer(data["token"])).status_code == 201
    # weblogin cannot be minted from a cookie session
    assert client.post("/api/weblogin", headers=CSRF).status_code == 401


def test_logged_in_page_lists_files(client, mojang):
    data = login(client, mojang)
    h = bearer(data["token"])
    client.put("/api/files/builds/Castle.litematic", content=gz(), headers=h)
    client.put("/api/thumbnails/builds/Castle.litematic", content=b"\x89PNG\r\n\x1a\n", headers=h)
    assert client.put("/api/files/Barn & 'Silo'.litematic", content=gz(), headers=h).status_code == 201
    _, path = web_login(client, data["token"])
    client.get(path)
    html = client.get("/").text
    assert "Castle" in html
    assert 'data-folder="builds"' in html
    assert "/api/thumbnails/builds/Castle.litematic?v=" in html
    assert "Barn &amp; &#39;Silo&#39;" in html
    assert "/api/files/Barn%20%26%20%27Silo%27.litematic" in html
    assert "2 schematics" in html


def test_admin_page_says_unlimited(client, mojang):
    data = login(client, mojang, "sputnikboi")
    _, path = web_login(client, data["token"])
    client.get(path)
    html = client.get("/").text
    assert "Unlimited" in html
    assert "of 1 GB" not in html


def test_logout(client, mojang):
    data = login(client, mojang)
    _, path = web_login(client, data["token"])
    client.get(path)
    assert client.get("/api/me").status_code == 200
    session = client.cookies.get("slopmatica_session")
    r = client.post("/logout", follow_redirects=False)
    assert r.status_code == 303
    assert client.get("/api/me").status_code == 401
    # the old cookie value is revoked server-side too
    again = TestClient(client.app)
    again.cookies.set("slopmatica_session", session)
    assert again.get("/api/me").status_code == 401
    # the mod's API token is unaffected
    assert client.get("/api/me", headers=bearer(data["token"])).status_code == 200


def test_secure_cookie_behind_https_proxy(client, mojang):
    data = login(client, mojang)
    _, path = web_login(client, data["token"])
    r = client.get(path, headers={"X-Forwarded-Proto": "https"}, follow_redirects=False)
    assert "Secure" in r.headers["set-cookie"]


def test_secure_cookie_when_public_url_is_https(make_client, mojang):
    c = make_client(public_url="https://box.example.ts.net")
    data = login(c, mojang)
    url, path = web_login(c, data["token"])
    assert url.startswith("https://box.example.ts.net/login/")
    r = c.get(path, follow_redirects=False)
    assert "Secure" in r.headers["set-cookie"]


# ------------------------------------------------------------------ mounted under a prefix


@pytest.fixture
def prefixed(make_client):
    return make_client(root_path="/slopmatica", public_url="https://box.example.ts.net")


@pytest.mark.parametrize("prefix", ["/slopmatica", ""])  # proxy keeps or strips the prefix
def test_root_path(prefixed, mojang, prefix):
    c = prefixed
    data = login(c, mojang, prefix=prefix)
    url, _ = web_login(c, data["token"], prefix=prefix)
    assert re.fullmatch(r"https://box\.example\.ts\.net/slopmatica/login/[A-Za-z0-9_-]+", url)
    token = url.rsplit("/", 1)[1]
    r = c.get(f"{prefix}/login/{token}", follow_redirects=False)
    assert r.status_code == 303
    assert r.headers["location"] == "/slopmatica/"
    assert "Path=/slopmatica" in r.headers["set-cookie"]
    c.put(f"{prefix}/api/files/x.litematic", content=gz(), headers=bearer(data["token"]))
    c.put(f"{prefix}/api/thumbnails/x.litematic", content=b"\x89PNG\r\n\x1a\n", headers=bearer(data["token"]))
    # the TestClient's cookie jar honours Path=/slopmatica, so read the page with the prefix
    html = c.get("/slopmatica/").text
    assert 'data-base="/slopmatica"' in html
    for attr in re.findall(r'(?:href|src|action)="([^"]*)"', html):
        if attr.startswith("http"):
            continue
        assert attr.startswith("/slopmatica/"), attr
    assert c.get(f"{prefix}/static/app.js").status_code == 200
    assert c.get(f"{prefix}/static/style.css").status_code == 200


def test_root_path_without_trailing_slash(prefixed):
    r = prefixed.get("/slopmatica")
    assert r.status_code == 200
    assert "Log in from the game" in r.text
    assert 'href="/slopmatica/static/style.css' in r.text


def test_js_never_uses_root_relative_api_urls():
    from slopmatica_cloud.app import PKG_DIR

    js = (PKG_DIR / "static" / "app.js").read_text()
    assert not re.search(r"""(?<!base \+ )["'`]/api/""", js)
    assert 'base + "/api/' in js
