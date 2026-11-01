import re

from slopmatica_cloud.app import Settings, create_app
from fastapi.testclient import TestClient

from conftest import bearer, login


def test_challenge_format(client):
    a = client.post("/api/auth/challenge").json()["serverId"]
    b = client.post("/api/auth/challenge").json()["serverId"]
    assert re.fullmatch(r"[0-9a-f]{40}", a)
    assert a != b


def test_verify_happy_path(client, mojang):
    data = login(client, mojang, "Alice")
    hexid = mojang.accounts["alice"][0]
    assert data["uuid"] == f"{hexid[:8]}-{hexid[8:12]}-{hexid[12:16]}-{hexid[16:20]}-{hexid[20:]}"
    assert data["name"] == "Alice"
    assert data["usedBytes"] == 0
    assert data["quotaBytes"] == 1073741824
    assert data["token"]
    # Mojang was asked with exactly the username and serverId
    assert mojang.calls[-1]["username"] == "Alice"
    assert re.fullmatch(r"[0-9a-f]{40}", mojang.calls[-1]["serverId"])

    me = client.get("/api/me", headers=bearer(data["token"]))
    assert me.status_code == 200
    assert me.json() == {"uuid": data["uuid"], "name": "Alice", "usedBytes": 0, "quotaBytes": 1073741824,
                         "whitelisted": True, "badges": [],  # 0.7.0 added these two
                         "displayName": "Alice", "tag": None,  # 0.8.0 added these three
                         "avatar": f"http://127.0.0.1:8095/avatar/{data['uuid']}"}


def test_verify_uses_mojang_canonical_name(client, mojang):
    mojang.account("Alice")
    data = login(client, mojang, "alice")
    assert data["name"] == "Alice"


def test_verify_without_join_fails_and_consumes_challenge(client, mojang):
    mojang.account("Alice")
    sid = client.post("/api/auth/challenge").json()["serverId"]
    r = client.post("/api/auth/verify", json={"username": "Alice", "serverId": sid})
    assert r.status_code == 401
    assert "error" in r.json()
    mojang.join("Alice", sid)  # too late: challenge is single use
    r = client.post("/api/auth/verify", json={"username": "Alice", "serverId": sid})
    assert r.status_code == 401


def test_verify_unknown_server_id(client, mojang):
    mojang.join("Alice", "f" * 40)
    r = client.post("/api/auth/verify", json={"username": "Alice", "serverId": "f" * 40})
    assert r.status_code == 401


def test_challenge_single_use(client, mojang):
    sid = client.post("/api/auth/challenge").json()["serverId"]
    mojang.join("Alice", sid)
    assert client.post("/api/auth/verify", json={"username": "Alice", "serverId": sid}).status_code == 200
    assert client.post("/api/auth/verify", json={"username": "Alice", "serverId": sid}).status_code == 401


def test_challenge_expires(client, mojang):
    sid = client.post("/api/auth/challenge").json()["serverId"]
    client.app.state.store.db.execute("UPDATE challenges SET expires = 0")
    mojang.join("Alice", sid)
    assert client.post("/api/auth/verify", json={"username": "Alice", "serverId": sid}).status_code == 401


def test_wrong_username_for_join(client, mojang):
    sid = client.post("/api/auth/challenge").json()["serverId"]
    mojang.join("Alice", sid)
    mojang.account("Mallory")
    r = client.post("/api/auth/verify", json={"username": "Mallory", "serverId": sid})
    assert r.status_code == 401


def test_mojang_unreachable(make_client, mojang):
    c = make_client(mojang_has_joined_url="http://127.0.0.1:9/hasJoined", mojang_timeout=2)
    sid = c.post("/api/auth/challenge").json()["serverId"]
    r = c.post("/api/auth/verify", json={"username": "Alice", "serverId": sid})
    assert r.status_code == 401
    assert "Mojang" in r.json()["error"]


def test_mojang_server_error(client, mojang):
    mojang.status_override = 503
    sid = client.post("/api/auth/challenge").json()["serverId"]
    r = client.post("/api/auth/verify", json={"username": "Alice", "serverId": sid})
    assert r.status_code == 401


def test_verify_malformed(client):
    assert client.post("/api/auth/verify", json={"username": "Alice"}).status_code == 400
    assert client.post("/api/auth/verify", content=b"not json").status_code == 400
    r = client.post("/api/auth/verify", json={"username": "bad name!", "serverId": "a" * 40})
    assert r.status_code == 400
    assert "error" in r.json()


def test_token_auth_failures(client, mojang):
    assert client.get("/api/me").status_code == 401
    assert client.get("/api/me", headers=bearer("nope")).status_code == 401
    assert client.get("/api/me", headers={"Authorization": "Basic abc"}).status_code == 401
    assert client.get("/api/files", headers={"Authorization": "Bearer "}).status_code == 401
    r = client.get("/api/files")
    assert r.json() == {"error": "authentication required"}


def test_token_expiry(client, mojang):
    tok = login(client, mojang)["token"]
    assert client.get("/api/me", headers=bearer(tok)).status_code == 200
    client.app.state.store.db.execute("UPDATE tokens SET expires = 1")
    assert client.get("/api/me", headers=bearer(tok)).status_code == 401


def test_token_lifetime_180_days(client, mojang):
    login(client, mojang)
    created, expires = client.app.state.store.db.execute("SELECT created, expires FROM tokens").fetchone()
    assert expires - created == 180 * 86400


def test_token_stored_hashed(client, mojang):
    tok = login(client, mojang)["token"]
    hashes = [r[0] for r in client.app.state.store.db.execute("SELECT hash FROM tokens")]
    assert tok not in hashes
    assert len(hashes) == 1 and re.fullmatch(r"[0-9a-f]{64}", hashes[0])


def test_name_updates_on_relogin(client, mojang):
    first = login(client, mojang, "Alice")
    mojang.rename("Alice", "Alicia")
    second = login(client, mojang, "Alicia")
    assert second["uuid"] == first["uuid"]
    assert second["name"] == "Alicia"
    assert client.get("/api/me", headers=bearer(first["token"])).json()["name"] == "Alicia"


def test_admin_unlimited_case_insensitive(client, mojang):
    data = login(client, mojang, "SputnikBoi")
    assert data["quotaBytes"] is None
    assert client.get("/api/me", headers=bearer(data["token"])).json()["quotaBytes"] is None


def test_admin_flag_sticks_to_uuid(client, mojang):
    login(client, mojang, "sputnikboi")
    mojang.rename("sputnikboi", "SomeoneElse")
    assert login(client, mojang, "SomeoneElse")["quotaBytes"] is None


def test_settings_from_env(monkeypatch, tmp_path, mojang):
    monkeypatch.setenv("SLOPMATICA_DATA", str(tmp_path / "d"))
    monkeypatch.setenv("SLOPMATICA_ROOT_PATH", "/slopmatica/")
    monkeypatch.setenv("SLOPMATICA_ADMINS", "Foo, Bar")
    monkeypatch.setenv("SLOPMATICA_QUOTA_BYTES", "1234")
    monkeypatch.setenv("SLOPMATICA_PUBLIC_URL", "https://example.test/")
    monkeypatch.setenv("SLOPMATICA_MOJANG_HASJOINED", mojang.url)
    s = Settings.from_env()
    assert s.data_dir == (tmp_path / "d").resolve()
    assert s.root_path == "/slopmatica"
    assert s.admins == {"foo", "bar"}
    assert s.quota_bytes == 1234
    assert s.public_url == "https://example.test"
    assert s.mojang_has_joined_url == mojang.url
    c = TestClient(create_app(s))
    assert login(c, mojang, "bar", prefix="/slopmatica")["quotaBytes"] is None
    assert login(c, mojang, "Alice", prefix="/slopmatica")["quotaBytes"] == 1234


def test_defaults():
    s = Settings.from_env({})
    assert s.root_path == ""
    assert s.admins == {"sputnikboi"}
    assert s.quota_bytes == 1073741824
    assert s.public_url == "http://127.0.0.1:8095"
    assert s.mojang_has_joined_url == "https://sessionserver.mojang.com/session/minecraft/hasJoined"
    assert s.data_dir.name == "data" and s.data_dir.parent.name == "cloud"
