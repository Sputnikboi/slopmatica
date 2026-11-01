import pytest
from fastapi.testclient import TestClient

from slopmatica_cloud import convert, nbt

from conftest import bearer, gz, login
from test_convert import synthetic
from test_web import web_login


@pytest.fixture
def alice(client, mojang):
    return bearer(login(client, mojang, "Alice")["token"])


def schematic_bytes(fmt: str, **kw) -> bytes:
    return nbt.dumps(convert.write(synthetic(**kw), fmt))


def upload(client, headers, path, body):
    r = client.put(f"/api/files/{path}", content=body, headers=headers)
    assert r.status_code in (200, 201), r.text


@pytest.mark.parametrize("src,dst", [("litematic", "nbt"), ("litematic", "slopmatic"), ("nbt", "litematic"),
                                     ("nbt", "slopmatic"), ("slopmatic", "litematic"), ("slopmatic", "nbt")])
def test_export_formats(client, alice, src, dst):
    original = schematic_bytes(src, two_regions=True)
    upload(client, alice, f"builds/deep/My House.{src}", original)
    r = client.get(f"/api/export/builds/deep/My%20House.{src}", params={"format": dst}, headers=alice)
    assert r.status_code == 200, r.text
    assert r.headers["content-type"] == "application/octet-stream"
    cd = r.headers["content-disposition"]
    # the space makes Starlette use the RFC 5987 form, as for the plain download
    assert cd == f"attachment; filename*=utf-8''My%20House.{dst}"
    assert r.content[:2] == b"\x1f\x8b"
    s = convert.read(nbt.loads(r.content)[1], dst, "x")
    assert s.block_count() == 10 and s.data_version == synthetic().data_version
    # the stored file is untouched
    assert client.get(f"/api/files/builds/deep/My%20House.{src}", headers=alice).content == original


def test_export_format_param(client, alice):
    upload(client, alice, "a.litematic", schematic_bytes("litematic"))
    assert client.get("/api/export/a.litematic?format=.NBT", headers=alice).status_code == 200
    for bad in ("", "schematic", "litematic"):  # missing, unknown, same as the source
        r = client.get(f"/api/export/a.litematic?format={bad}", headers=alice)
        assert r.status_code == 400, bad
        assert "error" in r.json()
    assert client.get("/api/export/a.litematic", headers=alice).status_code == 400
    assert "already" in client.get("/api/export/a.litematic?format=litematic", headers=alice).json()["error"]


def test_export_errors(client, alice, mojang):
    r = client.get("/api/export/missing.litematic?format=nbt", headers=alice)
    assert r.status_code == 404 and r.json() == {"error": "no such file"}
    assert client.get("/api/export/..%2Fx.litematic?format=nbt", headers=alice).status_code == 400
    # gzip, but not a schematic
    upload(client, alice, "junk.litematic", gz(b"not nbt at all"))
    r = client.get("/api/export/junk.litematic?format=nbt", headers=alice)
    assert r.status_code == 400 and "cannot convert" in r.json()["error"]
    # valid NBT of the wrong format
    upload(client, alice, "wrong.slopmatic", schematic_bytes("litematic"))
    r = client.get("/api/export/wrong.slopmatic?format=nbt", headers=alice)
    assert r.status_code == 400 and "not a .slopmatic" in r.json()["error"]
    # another account cannot see it
    bob = bearer(login(client, mojang, "Bob")["token"])
    upload(client, alice, "mine.nbt", schematic_bytes("nbt"))
    assert client.get("/api/export/mine.nbt?format=litematic", headers=bob).status_code == 404


def test_export_auth(client, mojang):
    data = login(client, mojang, "Alice")
    upload(client, bearer(data["token"]), "a.nbt", schematic_bytes("nbt"))
    anon = TestClient(client.app)
    assert anon.get("/api/export/a.nbt?format=litematic").status_code == 401
    assert anon.get("/api/export/a.nbt?format=litematic", headers=bearer("bogus")).status_code == 401
    # the website's session cookie works, and a read needs no CSRF header
    _, path = web_login(client, data["token"])
    client.get(path, follow_redirects=False)
    r = client.get("/api/export/a.nbt?format=litematic")
    assert r.status_code == 200 and 'filename="a.litematic"' in r.headers["content-disposition"]


def test_export_volume_cap(make_client, mojang, data_dir):
    client = make_client(max_export_volume=12)
    alice = bearer(login(client, mojang, "Alice")["token"])
    upload(client, alice, "big.litematic", schematic_bytes("litematic", two_regions=True))  # 12 + 4 blocks
    r = client.get("/api/export/big.litematic?format=nbt", headers=alice)
    assert r.status_code == 400 and "too large" in r.json()["error"]
    upload(client, alice, "small.litematic", schematic_bytes("litematic"))  # 12 blocks
    assert client.get("/api/export/small.litematic?format=nbt", headers=alice).status_code == 200
    # temporary export files are cleaned up
    assert list((data_dir / "tmp").iterdir()) == []


def test_files_page_has_export_menu(client, mojang):
    data = login(client, mojang, "Alice")
    upload(client, bearer(data["token"]), "x/Castle.slopmatic", schematic_bytes("slopmatic"))
    _, path = web_login(client, data["token"])
    client.get(path, follow_redirects=False)
    html = client.get("/").text
    assert "data-export" in html and "Export as" in html
    assert '<option value="nbt">.nbt</option>' in html and '<option value="litematic">.litematic</option>' in html
    assert '<option value="slopmatic">' not in html
    assert 'download="Castle.slopmatic"' in html
