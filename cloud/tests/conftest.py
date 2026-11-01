"""Shared fixtures: a fake Mojang session server on localhost and app factories."""

from __future__ import annotations

import base64
import gzip
import http.server
import json
import threading
import urllib.parse
import uuid as uuidlib

import pytest
from fastapi.testclient import TestClient

from slopmatica_cloud.app import Settings, create_app


class FakeMojang:
    """Minimal stand-in for sessionserver.mojang.com/session/minecraft/hasJoined,
    api.mojang.com/users/profiles/minecraft/<name>, and (0.8.0, for avatars) the session server's
    profile/<uuid> with its textures property and the skin files it points at (/texture/<uuid>)."""

    def __init__(self) -> None:
        self.accounts: dict[str, tuple[str, str]] = {}  # lower name -> (hex uuid, canonical name)
        self.joined: set[tuple[str, str]] = set()
        self.status_override: int | None = None
        self.calls: list[dict[str, str]] = []
        self.url = ""
        self.profile_url = ""
        self.profile_lookups: list[str] = []
        self.skins: dict[str, bytes | None] = {}  # hex uuid -> skin PNG (None: no skin set)
        self.skin_lookups: list[str] = []
        self.session_profile_url = ""
        self.port = 0

    def reset(self) -> None:
        self.accounts.clear()
        self.joined.clear()
        self.status_override = None
        self.calls.clear()
        self.profile_lookups.clear()
        self.skins.clear()
        self.skin_lookups.clear()

    def account(self, name: str) -> str:
        key = name.lower()
        if key not in self.accounts:
            self.accounts[key] = (uuidlib.uuid4().hex, name)
        return self.accounts[key][0]

    def rename(self, old: str, new: str) -> None:
        uid, _ = self.accounts.pop(old.lower())
        self.accounts[new.lower()] = (uid, new)

    def join(self, name: str, server_id: str) -> None:
        self.account(name)
        self.joined.add((name.lower(), server_id))


@pytest.fixture(scope="session")
def _mojang_server():
    fake = FakeMojang()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):  # quiet
            pass

        def do_GET(self):
            parsed = urllib.parse.urlparse(self.path)
            q = dict(urllib.parse.parse_qsl(parsed.query))
            fake.calls.append(q)
            if fake.status_override is not None:
                self.send_response(fake.status_override)
                self.end_headers()
                return
            if parsed.path.startswith(("/session/minecraft/profile/", "/texture/")):
                hexid = parsed.path.rsplit("/", 1)[1]
                if parsed.path.startswith("/texture/"):
                    body, ctype = fake.skins.get(hexid) or b"", "image/png"
                else:
                    fake.skin_lookups.append(hexid)
                    if hexid not in fake.skins:
                        self.send_response(204)
                        self.end_headers()
                        return
                    url = f"http://127.0.0.1:{fake.port}/texture/{hexid}"
                    textures = {"textures": {"SKIN": {"url": url}} if fake.skins[hexid] else {}}
                    value = base64.b64encode(json.dumps(textures).encode()).decode()
                    body = json.dumps({"id": hexid, "name": "x", "properties": [{"name": "textures", "value": value}]}).encode()
                    ctype = "application/json"
                self.send_response(200 if body else 404)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if parsed.path.startswith("/users/profiles/minecraft/"):
                name = urllib.parse.unquote(parsed.path.rsplit("/", 1)[1])
                fake.profile_lookups.append(name)
                hit = fake.accounts.get(name.lower())
                if hit is None:
                    self.send_response(404)
                    self.end_headers()
                    return
                body = json.dumps({"id": hit[0], "name": hit[1]}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            key = (q.get("username", "").lower(), q.get("serverId", ""))
            if parsed.path.endswith("/hasJoined") and key in fake.joined and key[0] in fake.accounts:
                uid, name = fake.accounts[key[0]]
                body = json.dumps({"id": uid, "name": name, "properties": []}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            else:
                self.send_response(204)
                self.end_headers()

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    fake.url = f"http://127.0.0.1:{server.server_port}/session/minecraft/hasJoined"
    fake.profile_url = f"http://127.0.0.1:{server.server_port}/users/profiles/minecraft"
    fake.session_profile_url = f"http://127.0.0.1:{server.server_port}/session/minecraft/profile"
    fake.port = server.server_port
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield fake
    server.shutdown()


@pytest.fixture
def mojang(_mojang_server):
    _mojang_server.reset()
    return _mojang_server


@pytest.fixture
def data_dir(tmp_path):
    return tmp_path / "data"


@pytest.fixture
def make_client(data_dir, mojang):
    def make(settings: Settings | None = None, **kw) -> TestClient:
        if settings is None:
            kw.setdefault("data_dir", data_dir)
            kw.setdefault("mojang_has_joined_url", mojang.url)
            kw.setdefault("mojang_profile_url", mojang.profile_url)
            # avatars: skins come from the fake too, so no test ever reaches the real Mojang
            kw.setdefault("mojang_session_profile_url", mojang.session_profile_url)
            kw.setdefault("skin_hosts", frozenset({"127.0.0.1"}))
            # The tests from before 0.7.0 cover what members can do; test_v070 turns the whitelist gate on.
            kw.setdefault("whitelist_enforced", False)
            settings = Settings(**kw)
        return TestClient(create_app(settings))

    return make


@pytest.fixture
def client(make_client):
    return make_client()


def login(client: TestClient, mojang: FakeMojang, name: str = "Alice", prefix: str = "") -> dict:
    sid = client.post(f"{prefix}/api/auth/challenge").json()["serverId"]
    mojang.join(name, sid)
    r = client.post(f"{prefix}/api/auth/verify", json={"username": name, "serverId": sid})
    assert r.status_code == 200, r.text
    return r.json()


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def gz(payload: bytes = b"litematic nbt") -> bytes:
    return gzip.compress(payload, mtime=0)  # deterministic bytes


PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 32
