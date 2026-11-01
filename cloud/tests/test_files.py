import hashlib
import os

import pytest
from fastapi.testclient import TestClient

from slopmatica_cloud.app import ApiError, Settings, create_app, validate_path

from conftest import PNG, bearer, gz, login


@pytest.fixture
def alice(client, mojang):
    return bearer(login(client, mojang, "Alice")["token"])


# ------------------------------------------------------------------ path validation

GOOD = [
    "a.litematic",
    "House.LITEMATIC",
    "builds/castle/keep.litematic",
    "with space/and-dash_ünïcode.litematic",
    "/".join(["d"] * 7) + "/x.litematic",  # 8 segments
    "a" * 90 + ".litematic",  # 100-char segment
    "copy.slopmatic",
    "structures/tower.nbt",
    "Mixed.NBT",
]
BAD = [
    "",
    "a.schem",
    "a.litematic.png",
    "../a.litematic",
    "x/../a.litematic",
    "./a.litematic",
    "/abs.litematic",
    "a//b.litematic",
    "a/b.litematic/",
    "back\\slash.litematic",
    ".hidden.litematic",
    "dir/.hidden/a.litematic",
    ".litematic",
    "/".join(["d"] * 8) + "/x.litematic",  # 9 segments
    "a" * 91 + ".litematic",  # 101-char segment
    "/".join(["d" * 99] * 3) + "/x.litematic",  # > 300 chars
    "nul\x00.litematic",
    "colon:.litematic",
    "q?.litematic",
    "folder.litematic/inner.litematic",
    " lead.litematic",
    "trail /a.litematic",
    "dot./a.litematic",
    "a.schematic",
    "a.nbt.txt",
    "folder.nbt/inner.slopmatic",
    ".slopmatic",
]


@pytest.mark.parametrize("path", GOOD)
def test_validate_good(path):
    assert validate_path(path) == path


@pytest.mark.parametrize("path", BAD)
def test_validate_bad(path):
    with pytest.raises(ApiError) as e:
        validate_path(path)
    assert e.value.status == 400


@pytest.mark.parametrize(
    "url",
    [
        "/api/files/..%2F..%2Fescape.litematic",
        "/api/files/%2E%2E/escape.litematic",
        "/api/files/a/%2E%2E/%2E%2E/%2E%2E/escape.litematic",
        "/api/files/%2Fetc%2Fescape.litematic",
        "/api/files/a%5Cb.litematic",
        "/api/files/a.txt",
        "/api/files/",
    ],
)
def test_traversal_rejected_over_http(client, alice, url, data_dir):
    r = client.put(url, content=gz(), headers=alice)
    assert r.status_code == 400, r.text
    assert "error" in r.json()
    assert client.get(url, headers=alice).status_code == 400
    assert not list(data_dir.rglob("escape.litematic"))


# ------------------------------------------------------------------ CRUD


def test_upload_list_download_delete(client, alice, data_dir):
    body = gz(b"castle")
    r = client.put("/api/files/builds/Castle.litematic", content=body, headers=alice)
    assert r.status_code == 201, r.text
    entry = r.json()
    assert entry["path"] == "builds/Castle.litematic"
    assert entry["size"] == len(body)
    assert entry["sha256"] == hashlib.sha256(body).hexdigest()
    assert entry["hasThumbnail"] is False
    assert isinstance(entry["modified"], int) and entry["modified"] > 1_600_000_000_000

    listing = client.get("/api/files", headers=alice).json()
    assert listing["files"] == [entry]
    assert listing["usedBytes"] == len(body)
    assert listing["quotaBytes"] == 1073741824

    me = client.get("/api/me", headers=alice).json()
    assert me["usedBytes"] == len(body)

    d = client.get("/api/files/builds/Castle.litematic", headers=alice)
    assert d.status_code == 200
    assert d.content == body
    assert d.headers["content-type"] == "application/octet-stream"
    assert d.headers["x-sha256"] == entry["sha256"]
    assert d.headers["content-disposition"].startswith("attachment")
    assert "Castle.litematic" in d.headers["content-disposition"]

    uuid = me["uuid"]
    assert (data_dir / "users" / uuid / "files" / "builds" / "Castle.litematic").read_bytes() == body

    assert client.delete("/api/files/builds/Castle.litematic", headers=alice).status_code == 204
    assert client.get("/api/files/builds/Castle.litematic", headers=alice).status_code == 404
    assert client.delete("/api/files/builds/Castle.litematic", headers=alice).status_code == 404
    assert client.get("/api/files", headers=alice).json()["files"] == []
    # empty folders are tidied up
    assert not (data_dir / "users" / uuid / "files" / "builds").exists()


def test_replace_returns_200(client, alice):
    assert client.put("/api/files/a.litematic", content=gz(b"1"), headers=alice).status_code == 201
    r = client.put("/api/files/a.litematic", content=gz(b"22"), headers=alice)
    assert r.status_code == 200
    assert client.get("/api/files/a.litematic", headers=alice).content == gz(b"22")
    assert len(client.get("/api/files", headers=alice).json()["files"]) == 1


def test_unicode_filename_download(client, alice):
    client.put("/api/files/%C3%BCber.litematic", content=gz(), headers=alice)
    r = client.get("/api/files/%C3%BCber.litematic", headers=alice)
    assert r.status_code == 200
    assert "filename*=utf-8''%C3%BCber.litematic" in r.headers["content-disposition"]
    assert client.get("/api/files", headers=alice).json()["files"][0]["path"] == "über.litematic"


def test_sha_header(client, alice):
    body = gz(b"x")
    good = hashlib.sha256(body).hexdigest()
    r = client.put("/api/files/a.litematic", content=body, headers={**alice, "X-Sha256": "0" * 64})
    assert r.status_code == 400
    assert "mismatch" in r.json()["error"]
    assert client.get("/api/files", headers=alice).json()["files"] == []
    assert client.put("/api/files/a.litematic", content=body, headers={**alice, "X-Sha256": "zz"}).status_code == 400
    r = client.put("/api/files/a.litematic", content=body, headers={**alice, "X-Sha256": good.upper()})
    assert r.status_code == 201


def test_rejects_non_gzip(client, alice):
    assert client.put("/api/files/a.litematic", content=b"plain text", headers=alice).status_code == 400
    assert client.put("/api/files/a.litematic", content=b"", headers=alice).status_code == 400
    assert client.put("/api/files/a.litematic", content=b"\x1f", headers=alice).status_code == 400


def test_upload_size_cap(make_client, mojang, data_dir):
    c = make_client(max_upload_bytes=100)
    h = bearer(login(c, mojang)["token"])
    big = b"\x1f\x8b" + os.urandom(200)
    r = c.put("/api/files/a.litematic", content=big, headers=h)
    assert r.status_code == 413
    # also enforced while streaming when there is no Content-Length
    r = c.put("/api/files/a.litematic", content=iter([big[:50], big[50:]]), headers=h)
    assert r.status_code == 413
    assert c.get("/api/files", headers=h).json()["usedBytes"] == 0
    assert not any((data_dir / "tmp").iterdir())


def test_users_are_isolated(client, mojang, alice):
    client.put("/api/files/secret.litematic", content=gz(), headers=alice)
    bob = bearer(login(client, mojang, "Bob")["token"])
    assert client.get("/api/files", headers=bob).json()["files"] == []
    assert client.get("/api/files/secret.litematic", headers=bob).status_code == 404
    assert client.delete("/api/files/secret.litematic", headers=bob).status_code == 404


# ------------------------------------------------------------------ quota


@pytest.fixture
def tiny_quota_client(monkeypatch, data_dir, mojang):
    monkeypatch.setenv("SLOPMATICA_DATA", str(data_dir))
    monkeypatch.setenv("SLOPMATICA_QUOTA_BYTES", "100")
    monkeypatch.setenv("SLOPMATICA_MOJANG_HASJOINED", mojang.url)
    monkeypatch.setenv("SLOPMATICA_WHITELIST_ENFORCED", "0")  # 0.7.0: the gate is covered in test_v070
    return TestClient(create_app(Settings.from_env()))


def blob(n: int) -> bytes:
    assert n >= 2
    return b"\x1f\x8b" + b"x" * (n - 2)


def test_quota(tiny_quota_client, mojang):
    c = tiny_quota_client
    data = login(c, mojang)
    assert data["quotaBytes"] == 100
    h = bearer(data["token"])
    assert c.put("/api/files/a.litematic", content=blob(60), headers=h).status_code == 201
    r = c.put("/api/files/b.litematic", content=blob(41), headers=h)
    assert r.status_code == 413
    assert r.json() == {"error": "quota exceeded", "usedBytes": 60, "quotaBytes": 100}
    assert c.put("/api/files/b.litematic", content=blob(40), headers=h).status_code == 201  # exactly full
    assert c.get("/api/me", headers=h).json()["usedBytes"] == 100


def test_quota_replacement_accounting(tiny_quota_client, mojang):
    c = tiny_quota_client
    h = bearer(login(c, mojang)["token"])
    assert c.put("/api/files/a.litematic", content=blob(60), headers=h).status_code == 201
    assert c.put("/api/files/b.litematic", content=blob(30), headers=h).status_code == 201
    # replacing a (60) with 70 bytes -> 30 + 70 = 100: allowed
    assert c.put("/api/files/a.litematic", content=blob(70), headers=h).status_code == 200
    # replacing a (70) with 71 -> 101: rejected, old file kept
    r = c.put("/api/files/a.litematic", content=blob(71), headers=h)
    assert r.status_code == 413
    assert len(c.get("/api/files/a.litematic", headers=h).content) == 70
    # shrinking always works
    assert c.put("/api/files/a.litematic", content=blob(10), headers=h).status_code == 200
    assert c.get("/api/files", headers=h).json()["usedBytes"] == 40
    # deleting frees space
    c.delete("/api/files/b.litematic", headers=h)
    assert c.put("/api/files/c.litematic", content=blob(90), headers=h).status_code == 201


def test_admin_unlimited_ignores_quota(tiny_quota_client, mojang):
    c = tiny_quota_client
    data = login(c, mojang, "sputnikboi")
    assert data["quotaBytes"] is None
    h = bearer(data["token"])
    assert c.put("/api/files/huge.litematic", content=blob(5000), headers=h).status_code == 201
    listing = c.get("/api/files", headers=h).json()
    assert listing["usedBytes"] == 5000 and listing["quotaBytes"] is None


# ------------------------------------------------------------------ move


def test_move(client, alice, data_dir):
    client.put("/api/files/a.litematic", content=gz(b"a"), headers=alice)
    client.put("/api/thumbnails/a.litematic", content=PNG, headers=alice)
    client.put("/api/files/b.litematic", content=gz(b"b"), headers=alice)

    r = client.post("/api/files/move", json={"from": "a.litematic", "to": "sub/dir/renamed.litematic"}, headers=alice)
    assert r.status_code == 200, r.text
    assert r.json()["path"] == "sub/dir/renamed.litematic"
    assert r.json()["hasThumbnail"] is True
    paths = [f["path"] for f in client.get("/api/files", headers=alice).json()["files"]]
    assert paths == ["b.litematic", "sub/dir/renamed.litematic"]
    assert client.get("/api/files/sub/dir/renamed.litematic", headers=alice).content == gz(b"a")
    assert client.get("/api/thumbnails/sub/dir/renamed.litematic", headers=alice).content == PNG
    assert client.get("/api/files/a.litematic", headers=alice).status_code == 404

    # target exists
    r = client.post("/api/files/move", json={"from": "b.litematic", "to": "sub/dir/renamed.litematic"}, headers=alice)
    assert r.status_code == 409
    assert client.post("/api/files/move", json={"from": "b.litematic", "to": "b.litematic"}, headers=alice).status_code == 409
    # source missing
    assert client.post("/api/files/move", json={"from": "nope.litematic", "to": "x.litematic"}, headers=alice).status_code == 404
    # validation on both sides
    assert client.post("/api/files/move", json={"from": "b.litematic", "to": "../x.litematic"}, headers=alice).status_code == 400
    assert client.post("/api/files/move", json={"from": "../b.litematic", "to": "x.litematic"}, headers=alice).status_code == 400
    assert client.post("/api/files/move", json={"from": "b.litematic", "to": "x.txt"}, headers=alice).status_code == 400
    assert client.post("/api/files/move", json={"from": "b.litematic"}, headers=alice).status_code == 400
    # moving back out of the nested folder removes the empty folders
    client.post("/api/files/move", json={"from": "sub/dir/renamed.litematic", "to": "c.litematic"}, headers=alice)
    uuid = client.get("/api/me", headers=alice).json()["uuid"]
    assert not (data_dir / "users" / uuid / "files" / "sub").exists()
    assert not (data_dir / "users" / uuid / "thumbs" / "sub").exists()


# ------------------------------------------------------------------ thumbnails


def test_thumbnails(client, alice, data_dir):
    assert client.put("/api/thumbnails/a.litematic", content=PNG, headers=alice).status_code == 404
    client.put("/api/files/a.litematic", content=gz(b"a"), headers=alice)
    assert client.get("/api/thumbnails/a.litematic", headers=alice).status_code == 404
    assert client.put("/api/thumbnails/a.litematic", content=b"GIF89a....", headers=alice).status_code == 400
    r = client.put("/api/thumbnails/a.litematic", content=PNG, headers=alice)
    assert r.status_code == 200
    assert r.json()["hasThumbnail"] is True

    g = client.get("/api/thumbnails/a.litematic", headers=alice)
    assert g.status_code == 200
    assert g.headers["content-type"] == "image/png"
    assert g.content == PNG
    listing = client.get("/api/files", headers=alice).json()
    assert listing["files"][0]["hasThumbnail"] is True
    assert listing["usedBytes"] == len(gz(b"a"))  # thumbnails are free

    uuid = client.get("/api/me", headers=alice).json()["uuid"]
    thumb = data_dir / "users" / uuid / "thumbs" / "a.litematic.png"
    assert thumb.read_bytes() == PNG

    # re-uploading identical bytes keeps the thumbnail, changed bytes drop it
    client.put("/api/files/a.litematic", content=gz(b"a"), headers=alice)
    assert client.get("/api/files", headers=alice).json()["files"][0]["hasThumbnail"] is True
    client.put("/api/files/a.litematic", content=gz(b"changed"), headers=alice)
    assert client.get("/api/files", headers=alice).json()["files"][0]["hasThumbnail"] is False
    assert not thumb.exists()

    # deleting the file deletes the thumbnail
    client.put("/api/thumbnails/a.litematic", content=PNG, headers=alice)
    client.delete("/api/files/a.litematic", headers=alice)
    assert not thumb.exists()
    assert client.get("/api/thumbnails/a.litematic", headers=alice).status_code == 404


def test_thumbnail_size_cap(client, alice):
    client.put("/api/files/a.litematic", content=gz(), headers=alice)
    big = PNG + b"\x00" * (2 * 1024 * 1024)
    assert client.put("/api/thumbnails/a.litematic", content=big, headers=alice).status_code == 413


# ------------------------------------------------------------------ disk is the source of truth


def test_reconcile_on_startup(make_client, mojang, data_dir):
    c = make_client()
    h = bearer(login(c, mojang)["token"])
    c.put("/api/files/keep.litematic", content=gz(b"k"), headers=h)
    c.put("/api/files/gone.litematic", content=gz(b"g"), headers=h)
    uuid = c.get("/api/me", headers=h).json()["uuid"]
    files = data_dir / "users" / uuid / "files"
    (files / "gone.litematic").unlink()
    (files / "new").mkdir()
    (files / "new" / "dropped.litematic").write_bytes(gz(b"dropped"))
    (files / "ignored.txt").write_bytes(b"x")

    c2 = make_client()  # restart
    listing = c2.get("/api/files", headers=h).json()
    by_path = {f["path"]: f for f in listing["files"]}
    assert set(by_path) == {"keep.litematic", "new/dropped.litematic"}
    assert by_path["new/dropped.litematic"]["sha256"] == hashlib.sha256(gz(b"dropped")).hexdigest()
    assert listing["usedBytes"] == len(gz(b"k")) + len(gz(b"dropped"))


@pytest.mark.parametrize("name", ["copy.slopmatic", "structures/tower.nbt"])
def test_other_formats_round_trip(client, alice, name):
    body = gz(b"blocks")
    assert client.put("/api/files/" + name, content=body, headers=alice).status_code == 201
    assert client.get("/api/files/" + name, headers=alice).content == body
    assert name in [f["path"] for f in client.get("/api/files", headers=alice).json()["files"]]
