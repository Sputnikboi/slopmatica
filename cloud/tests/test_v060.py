"""0.6.0: mod releases (publishing, the mod's update check, downloads, the releases page, the CLI),
the feedback page and its admin list, and the schema 3 -> 4 migration."""

import hashlib
import io
import json
import os
import re
import sqlite3
import time
import zipfile

import pytest
from fastapi.testclient import TestClient

import publish_release
from slopmatica_cloud import __version__
from slopmatica_cloud.app import (
    FEEDBACK_IP_LIMIT, MIGRATIONS, SCHEMA_VERSION, Settings, parse_multipart, render_changelog, semver_key,
    sha256_hex,
)

from conftest import bearer, gz
from test_board import CSRF, Who

ADMIN = "Sputnikboi"  # in Settings.admins by default


def jar_bytes(version="0.6.0", extra=b"") -> bytes:
    """A small jar: fabric.mod.json plus a binary entry (CRLFs and dashes, to stress the multipart parser)."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("fabric.mod.json", json.dumps({"schemaVersion": 1, "id": "slopmatica", "version": version}))
        z.writestr("blob.bin", bytes(range(256)) * 4 + b"\r\n--boundary\r\n\r\n" + extra)
    return buf.getvalue()


def publish(client, h, version="0.6.0", mc="26.3", loader="fabric", changelog="", filename=None, body=None,
            status=201, **fields):
    name = filename or f"slopmatica-{version}+{mc}-{loader}.jar"
    data = {"version": version, "mc": mc, "loader": loader, "changelog": changelog, **fields}
    r = client.post("/api/releases", headers=h, data=data,
                    files={"jar": (name, body if body is not None else jar_bytes(version), "application/java-archive")})
    assert r.status_code == status, r.text
    return r.json()


@pytest.fixture
def admin(client, mojang):
    return Who(client, mojang, ADMIN)


@pytest.fixture
def alice(client, mojang):
    return Who(client, mojang, "Alice")


# ------------------------------------------------------------------ versions


def test_version_bumped():
    assert SCHEMA_VERSION >= 4  # 0.6.0; test_v070 checks the current version


def test_semver_order():
    # the semver.org precedence example, plus numeric (not text) comparison of each part
    ordered = ["0.9.2", "0.10.0", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
               "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.2.0", "1.10.0", "10.0.0"]
    shuffled = ordered[::2] + ordered[1::2]
    assert sorted(shuffled, key=semver_key) == ordered


# ------------------------------------------------------------------ publishing


def test_publish_is_admin_only(client, admin, alice, data_dir):
    jar = jar_bytes()
    files = {"jar": ("slopmatica-0.6.0.jar", jar, "application/java-archive")}
    data = {"version": "0.6.0", "mc": "26.3", "loader": "fabric"}
    assert TestClient(client.app).post("/api/releases", data=data, files=files).status_code == 401
    r = client.post("/api/releases", headers=alice.h, data=data, files=files)
    assert r.status_code == 403 and r.json() == {"error": "only admins can do that"}
    # an admin's website session works too, but needs the CSRF header like every cookie change
    web = admin.cookie_client(client.app)
    assert web.post("/api/releases", data=data, files=files).status_code == 403
    assert list((data_dir / "releases").iterdir()) == []
    r = web.post("/api/releases", data=data, files=files, headers=CSRF)
    assert r.status_code == 201, r.text
    rel = r.json()
    assert rel["filename"] == "slopmatica-0.6.0.jar" and rel["version"] == "0.6.0" and rel["mc"] == "26.3"
    assert rel["loader"] == "fabric" and rel["size"] == len(jar) and rel["sha256"] == hashlib.sha256(jar).hexdigest()
    assert rel["hidden"] is False and rel["downloads"] == 0 and rel["changelog"] == ""
    assert (data_dir / "releases" / "slopmatica-0.6.0.jar").read_bytes() == jar
    assert sorted(p.name for p in (data_dir / "releases").iterdir()) == ["slopmatica-0.6.0.jar"]  # no leftovers


def test_publish_validation(make_client, mojang, data_dir):
    client = make_client(max_release_bytes=50_000)
    h = Who(client, mojang, ADMIN).h
    for version in ("0.6", "v0.6.0", "0.6.0.1", "0.6.0-", "0.6.0+build", "0.6.0-be ta", "", "x" * 70):
        assert publish(client, h, version=version, filename="a.jar", status=400)["error"].startswith("version")
    for mc in ("", "26 3", "../26.3", "-26.3", "x" * 33):
        assert publish(client, h, mc=mc, filename="a.jar", status=400)["error"].startswith("mc")
    for loader in ("Fabric", "fab ric", "../x"):
        assert publish(client, h, loader=loader, filename="a.jar", status=400)["error"].startswith("loader")
    assert publish(client, h, body=b"not a zip", status=400)["error"] == "not a jar (zip) file"
    assert publish(client, h, changelog="bad \x07 bell", status=400)["error"].startswith("changelog")
    assert publish(client, h, changelog="x" * 20001, status=400)["error"].startswith("changelog")
    big = jar_bytes(extra=os.urandom(60_000))
    assert publish(client, h, body=big, status=413)["error"] == "file too large"
    # no jar, or no multipart at all
    r = client.post("/api/releases", headers=h, data={"version": "0.6.0", "mc": "26.3"})
    assert r.status_code == 400
    r = client.post("/api/releases", headers=h, json={"version": "0.6.0"})
    assert r.status_code == 400 and r.json() == {"error": "expected multipart/form-data"}
    assert list((data_dir / "releases").iterdir()) == []
    # duplicates: the same version for the same mc and loader, or the same file name
    publish(client, h, filename="slopmatica-0.6.0.jar")
    assert "already published" in publish(client, h, filename="other.jar", status=409)["error"]
    assert "already exists" in publish(client, h, version="0.6.1", filename="slopmatica-0.6.0.jar", status=409)["error"]
    publish(client, h, mc="26.2", filename="slopmatica-0.6.0-26.2.jar")  # another Minecraft version is fine
    assert sorted(p.name for p in (data_dir / "releases").iterdir()) == ["slopmatica-0.6.0-26.2.jar", "slopmatica-0.6.0.jar"]


@pytest.mark.parametrize("name", [
    "../evil.jar", "..%2Fevil.jar", "a/b.jar", "a\\b.jar", ".hidden.jar", "-x.jar", "evil.exe", "evil.jar.exe",
    "evil..jar", "evil.jar\x00", "x" * 101 + ".jar", "é.jar", "a b.jar",
])
def test_publish_refuses_unsafe_file_names(client, admin, data_dir, name):
    # given as the filename field (an upload's own name loses any folders first, see below)
    r = client.post("/api/releases", headers=admin.h,
                    data={"version": "0.6.0", "mc": "26.3", "filename": name},
                    files={"jar": ("ok.jar", jar_bytes(), "application/java-archive")})
    assert r.status_code == 400 and r.json()["error"].startswith("filename"), r.text
    assert list((data_dir / "releases").iterdir()) == []
    assert not (data_dir / "evil.jar").exists() and not (data_dir.parent / "evil.jar").exists()


def test_upload_name_folders_are_dropped(client, admin, data_dir):
    rel = publish(client, admin.h, filename="..\\..\\build/libs/slopmatica-0.6.0.jar")
    assert rel["filename"] == "slopmatica-0.6.0.jar"
    assert sorted(p.name for p in (data_dir / "releases").iterdir()) == ["slopmatica-0.6.0.jar"]


def test_multipart_round_trip():
    payload = os.urandom(5000) + b"\r\n--b\r\n\r\n--b--\r\n"
    body = (b"--b0undary\r\nContent-Disposition: form-data; name=\"version\"\r\n\r\n0.6.0\r\n"
            b"--b0undary\r\nContent-Disposition: form-data; name=\"jar\"; filename=\"x.jar\"\r\n"
            b"Content-Type: application/java-archive\r\n\r\n" + payload + b"\r\n--b0undary--\r\n")
    fields, files = parse_multipart(body, 'multipart/form-data; boundary="b0undary"')
    assert fields == {"version": "0.6.0"} and files == {"jar": ("x.jar", payload)}
    for bad in (b"", b"--b0undary\r\nno headers", body[:-4]):
        with pytest.raises(Exception):
            parse_multipart(bad, "multipart/form-data; boundary=b0undary")


# ------------------------------------------------------------------ the mod's update check


def test_latest_404_when_nothing_is_published(client):
    r = client.get("/api/releases/latest?mc=26.3&loader=fabric")
    assert r.status_code == 404 and r.json() == {"error": "no release for Minecraft 26.3 (fabric)"}
    assert client.get("/api/releases/latest").status_code == 400  # no mc
    assert client.get("/api/releases/latest?mc=26.3&loader=Fa%20bric").status_code == 400
    assert client.get("/api/releases").json() == {"releases": []}


def test_latest_by_semver_mc_and_loader(client, admin):
    h = admin.h
    # uploaded out of order: newest means highest version, not latest upload
    for v in ("0.10.0", "0.9.2", "0.10.0-beta.2", "0.10.0-beta.10", "0.2.0"):
        publish(client, h, version=v)
    publish(client, h, version="0.11.0", mc="26.2")
    publish(client, h, version="0.12.0", loader="quilt")
    publish(client, h, version="0.13.0", hidden="true")  # hidden: never offered
    anon = TestClient(client.app)

    r = anon.get("/api/releases/latest?mc=26.3&loader=fabric")
    assert r.status_code == 200
    latest = r.json()
    jar = jar_bytes("0.10.0")
    assert latest == {
        "version": "0.10.0",
        "download": "http://127.0.0.1:8095/releases/slopmatica-0.10.0%2B26.3-fabric.jar",
        "page": "http://127.0.0.1:8095/releases?highlight=0.10.0&mc=26.3#v0.10.0",
        "sha256": hashlib.sha256(jar).hexdigest(),
        "mc": "26.3",
        "loader": "fabric",
    }
    assert anon.get("/api/releases/latest?mc=26.3").json()["version"] == "0.10.0"  # fabric by default
    assert anon.get("/api/releases/latest?mc=26.2&loader=fabric").json()["version"] == "0.11.0"
    assert anon.get("/api/releases/latest?mc=26.3&loader=quilt").json()["version"] == "0.12.0"
    assert anon.get("/api/releases/latest?mc=26.1&loader=fabric").status_code == 404
    # the download link works, through the path that the absolute URL names
    d = anon.get(latest["download"].removeprefix("http://127.0.0.1:8095"))
    assert d.status_code == 200 and d.content == jar
    # the public list leaves the hidden one out; an admin sees it
    listed = [(r["version"], r["mc"], r["loader"]) for r in anon.get("/api/releases").json()["releases"]]
    assert listed[0] == ("0.12.0", "26.3", "quilt") and ("0.13.0", "26.3", "fabric") not in listed
    assert listed.index(("0.10.0", "26.3", "fabric")) < listed.index(("0.10.0-beta.10", "26.3", "fabric")) \
        < listed.index(("0.10.0-beta.2", "26.3", "fabric")) < listed.index(("0.9.2", "26.3", "fabric"))
    assert ("0.13.0", "26.3", "fabric") in [(r["version"], r["mc"], r["loader"])
                                            for r in client.get("/api/releases", headers=h).json()["releases"]]
    # unhiding makes it the latest
    r = client.patch("/api/releases/slopmatica-0.13.0+26.3-fabric.jar", headers=h, json={"hidden": False})
    assert r.status_code == 200 and r.json()["hidden"] is False
    assert anon.get("/api/releases/latest?mc=26.3").json()["version"] == "0.13.0"


@pytest.mark.parametrize("public", ["https://example.net", "https://example.net/slopmatica"])
def test_release_urls_under_a_root_path(make_client, mojang, public):
    # PUBLIC_URL with or without the prefix (the live one has it): never doubled
    c = make_client(root_path="/slopmatica", public_url=public)
    h = Who(c, mojang, ADMIN).h
    r = c.post("/slopmatica/api/releases", headers=h, data={"version": "0.6.0", "mc": "26.3"},
               files={"jar": ("slopmatica-0.6.0.jar", jar_bytes(), "application/java-archive")})
    assert r.status_code == 201, r.text
    latest = c.get("/slopmatica/api/releases/latest?mc=26.3").json()
    assert latest["download"] == "https://example.net/slopmatica/releases/slopmatica-0.6.0.jar"
    assert latest["page"] == "https://example.net/slopmatica/releases?highlight=0.6.0&mc=26.3#v0.6.0"
    assert r.json()["download"] == latest["download"]
    assert c.get("/slopmatica/releases/slopmatica-0.6.0.jar").status_code == 200
    assert c.get("/releases/slopmatica-0.6.0.jar").status_code == 200  # the proxy stripped the prefix
    html = c.get("/slopmatica/releases").text
    assert 'href="/slopmatica/releases/slopmatica-0.6.0.jar"' in html and 'src="/slopmatica/static/releases.js' in html


def test_patch_release(client, admin, alice):
    publish(client, admin.h, filename="slopmatica-0.6.0.jar")
    url = "/api/releases/slopmatica-0.6.0.jar"
    assert TestClient(client.app).patch(url, json={"hidden": True}).status_code == 401
    assert client.patch(url, headers=alice.h, json={"hidden": True}).status_code == 403
    assert client.patch("/api/releases/nope.jar", headers=admin.h, json={"hidden": True}).status_code == 404
    assert client.patch(url, headers=admin.h, json={"hidden": "yes"}).status_code == 400
    r = client.patch(url, headers=admin.h, json={"changelog": "  - fixed it\r\n"})
    assert r.status_code == 200 and r.json()["changelog"] == "- fixed it" and r.json()["hidden"] is False
    assert client.patch(url, headers=admin.h, json={"hidden": True}).json()["hidden"] is True
    assert client.get("/api/releases/latest?mc=26.3").status_code == 404


# ------------------------------------------------------------------ downloads


def test_download_counts_and_headers(client, admin):
    jar = jar_bytes()
    publish(client, admin.h, filename="slopmatica-0.6.0.jar", body=jar)
    anon = TestClient(client.app)
    for n in (1, 2):
        d = anon.get("/releases/slopmatica-0.6.0.jar")
        assert d.status_code == 200 and d.content == jar
        assert d.headers["content-type"] == "application/java-archive"
        assert d.headers["content-disposition"] == 'attachment; filename="slopmatica-0.6.0.jar"'
        assert d.headers["x-sha256"] == hashlib.sha256(jar).hexdigest()
        assert anon.get("/api/releases").json()["releases"][0]["downloads"] == n
    assert "2 downloads" in anon.get("/releases").text


def test_download_path_safety(client, admin, data_dir):
    publish(client, admin.h, filename="slopmatica-0.6.0.jar")
    publish(client, admin.h, version="0.5.0", filename="slopmatica-0.5.0.jar", hidden="1")
    anon = TestClient(client.app)
    (data_dir / "releases" / "stray.jar").write_bytes(jar_bytes())  # on disk, but not a release
    (data_dir / "secret.jar").write_bytes(b"PK\x03\x04secret")
    for path in ("/releases/stray.jar", "/releases/..%2Fcloud.db", "/releases/..%2Fsecret.jar", "/releases/%2e%2e",
                 "/releases/.%2E%2Fsecret.jar", "/releases/cloud.db", "/releases/%00.jar",
                 "/releases/slopmatica-0.6.0.jar%00", "/releases/..%5Csecret.jar", "/releases/nope.jar"):
        r = anon.get(path)
        assert r.status_code == 404 and b"secret" not in r.content and b"SQLite" not in r.content, path
    # hidden: gone for everyone but admins
    assert anon.get("/releases/slopmatica-0.5.0.jar").status_code == 404
    assert client.get("/releases/slopmatica-0.5.0.jar", headers=admin.h).status_code == 200
    # a symlink posing as a release jar is not followed out of the folder
    os.symlink(data_dir / "secret.jar", data_dir / "releases" / "linked.jar")
    db = client.app.state.store.db
    db.execute("INSERT INTO releases(version, mc_version, loader, filename, sha256, size, uploaded) "
               "VALUES('0.4.0','26.3','fabric','linked.jar','x',1,1)")
    assert anon.get("/releases/linked.jar").status_code == 404
    # nothing was counted for any of it
    counts = {r["filename"]: r["downloads"] for r in client.get("/api/releases", headers=admin.h).json()["releases"]}
    assert counts["slopmatica-0.6.0.jar"] == 0 and counts["slopmatica-0.5.0.jar"] == 1


def test_missing_jar_is_404_and_logged(make_client, mojang, data_dir, caplog):
    c = make_client()
    publish(c, Who(c, mojang, ADMIN).h, filename="slopmatica-0.6.0.jar")
    (data_dir / "releases" / "slopmatica-0.6.0.jar").unlink()
    stale = data_dir / "releases" / ".slopmatica-0.7.0.jar.abcd1234.part"
    stale.write_bytes(b"half")
    os.utime(stale, (time.time() - 7200,) * 2)
    fresh = data_dir / "releases" / ".slopmatica-0.7.1.jar.abcd1234.part"  # a publish running right now
    fresh.write_bytes(b"half")
    c2 = make_client()
    assert any("slopmatica-0.6.0.jar" in r.message and "missing" in r.message for r in caplog.records)
    assert c2.get("/releases/slopmatica-0.6.0.jar").status_code == 404
    assert not stale.exists() and fresh.exists()


# ------------------------------------------------------------------ releases page


NOTES = """# Big one
- **Bold** thing with `code`
- a [link](https://example.com/x?a=1&b=2) and [bad](javascript:alert(1))
<script>alert("x")</script>

1. first
2. second
line one
line two"""


def test_changelog_renderer():
    html = str(render_changelog(NOTES))
    assert "<h3>Big one</h3>" in html
    assert "<ul><li><strong>Bold</strong> thing with <code>code</code></li>" in html
    assert '<a href="https://example.com/x?a=1&amp;b=2" rel="noopener noreferrer">link</a>' in html
    assert "<a href=\"javascript" not in html and "[bad](javascript:alert(1))" in html
    assert "<script>" not in html and "&lt;script&gt;" in html
    assert "<ol><li>first</li><li>second</li></ol><p>line one<br>line two</p>" in html


def test_releases_page(client, admin):
    h = admin.h
    publish(client, h, version="0.5.0", filename="slopmatica-0.5.0.jar", changelog="- old stuff")
    publish(client, h, version="0.6.0", filename="slopmatica-0.6.0.jar", changelog=NOTES)
    publish(client, h, version="0.6.0", mc="26.2", filename="slopmatica-0.6.0-26.2.jar", changelog=NOTES)
    publish(client, h, version="0.4.0", mc="26.2", filename="slopmatica-0.4.0-26.2.jar")
    publish(client, h, version="0.9.0", filename="slopmatica-0.9.0.jar", changelog="- secret", hidden="on")
    anon = TestClient(client.app)
    r = anon.get("/releases")
    assert r.status_code == 200 and "Content-Security-Policy" in r.headers
    html = r.text
    # every script is a file (the CSP forbids inline ones)
    assert all(m.group(1) for m in re.finditer(r"<script( src=\"[^\"]+\")?>", html))
    assert 'href="/static/releases.js' not in html and 'src="/static/releases.js' in html
    # the nav tab
    assert 'class="btn small ghost nav-releases on" href="/releases" aria-current="page">Releases</a>' in html
    assert ">Releases</a>" in anon.get("/board").text
    # the newest release up top, with a download per Minecraft version
    hero = html.split('<h2 class="rel-history-title">')[0]
    assert "Slopmatica 0.6.0" in hero and "0.9.0" not in html and "secret" not in html
    assert 'href="/releases/slopmatica-0.6.0.jar">Download for Minecraft 26.3</a>' in hero
    assert 'href="/releases/slopmatica-0.6.0-26.2.jar">Download for Minecraft 26.2</a>' in hero
    assert "<h3>Big one</h3>" in hero and "&lt;script&gt;" in hero and "<script>alert" not in html
    assert 'href="https://github.com/Sputnikboi/slopmatica"' in html
    # then each Minecraft version, newest first, its versions newest first
    groups = re.findall(r'<h3 class="rel-mc" id="mc-\d">Minecraft ([\d.]+)</h3>', html)
    assert groups == ["26.3", "26.2"]
    ids = re.findall(r'<article class="release[^"]*" id="([^"]+)"', html)
    assert ids == ["v0.6.0", "v0.5.0", "v0.6.0-mc26.2-fabric", "v0.4.0"]  # unique, #v<version> on one
    assert "<li>old stuff</li>" in html and "No notes." in html
    assert 'class="release hl"' not in html


def test_releases_page_highlight(client, admin):
    h = admin.h
    publish(client, h, version="0.5.0", filename="a.jar")
    publish(client, h, version="0.6.0", filename="b.jar")
    publish(client, h, version="0.6.0", mc="26.2", filename="c.jar")
    anon = TestClient(client.app)

    def marked(url):
        html = anon.get(url).text
        return re.findall(r'<article class="release hl" id="([^"]+)"', html)

    assert marked("/releases?highlight=0.5.0") == ["v0.5.0"]
    # the mod's page URL names its Minecraft version: that build gets #v<version>
    assert marked("/releases?highlight=0.6.0&mc=26.2") == ["v0.6.0"]
    html = anon.get("/releases?highlight=0.6.0&mc=26.2").text
    assert html.index('id="v0.6.0-mc26.3-fabric"') < html.index('id="v0.6.0"')
    assert marked("/releases?highlight=0.6.0&mc=26.3") == ["v0.6.0"]
    assert marked("/releases?highlight=0.6.0") == ["v0.6.0", "v0.6.0-mc26.2-fabric"]
    assert marked("/releases?highlight=9.9.9") == [] and marked("/releases?highlight=%3Cscript%3E") == []
    # the page URL from the update check lands on the highlighted build
    page = anon.get("/api/releases/latest?mc=26.2").json()["page"]
    assert marked(page.removeprefix("http://127.0.0.1:8095").split("#")[0]) == ["v0.6.0"]


def test_releases_page_empty(client):
    r = TestClient(client.app).get("/releases")
    assert r.status_code == 200 and "No release has been published yet" in r.text


def test_releases_page_mobile_viewport(client):
    html = client.get("/releases").text
    assert '<meta name="viewport" content="width=device-width, initial-scale=1">' in html


# ------------------------------------------------------------------ feedback


def send(c, status=201, headers=CSRF, **body):
    body.setdefault("type", "bug")
    body.setdefault("message", "It broke")
    r = c.post("/api/feedback", json=body, headers=headers)
    assert r.status_code == status, r.text
    return r.json()


def test_feedback_page(client, alice):
    anon = TestClient(client.app)
    html = anon.get("/feedback?mod=0.6.0&mc=26.3&type=suggestion").text
    assert "Made by Sputniknull + Claude" in html and "Slopmatica is a schematic mod" in html
    assert 'name="modVersion" value="0.6.0"' in html and 'name="mcVersion" value="26.3"' in html
    assert 'value="suggestion" checked' in html and "sent anonymously" in html
    assert 'src="/static/feedback.js' in html
    html = alice.cookie_client(client.app).get("/feedback").text
    assert "sent as <strong>Alice</strong>" in html and 'name="anonymous"' in html
    # the footer credit links here on every page
    for url in ("/", "/board", "/releases", "/feedback"):
        assert '<a href="/feedback">Made by Sputniknull + Claude</a>' in anon.get(url).text


def test_feedback_submit(client, alice, mojang):
    anon = TestClient(client.app)
    f = send(anon, type="suggestion", message="  More colours\r\nplease  ", modVersion=" 0.6.0 ", mcVersion="26.3",
             contact="alice#1234")
    assert f["type"] == "suggestion" and f["message"] == "More colours\nplease" and f["modVersion"] == "0.6.0"
    assert f["mcVersion"] == "26.3" and f["contact"] == "alice#1234" and f["user"] is None and f["resolved"] is None
    assert send(anon, modVersion="", contact="  ")["modVersion"] is None
    # logged in: recorded, by website session or by game token
    web = alice.cookie_client(client.app)
    assert send(web)["user"] == {"uuid": alice.uuid, "name": "Alice"}
    assert send(client, headers=alice.h)["user"] == {"uuid": alice.uuid, "name": "Alice"}
    # the header is required without a token, logged out too
    assert send(anon, status=403, headers={})["error"].startswith("missing X-Requested-With")
    assert send(web, status=403, headers={})["error"].startswith("missing X-Requested-With")
    assert send(client, status=401, headers=bearer("nope"))["error"] == "invalid or expired token"
    # validation
    assert send(anon, status=400, type="rant")["error"] == "type must be one of bug, suggestion, other"
    assert send(anon, status=400, message="   ")["error"] == "message is required"
    assert send(anon, status=400, message=5)["error"] == "message is required"
    assert send(anon, status=400, message="x" * 5001)["error"] == "message must be at most 5000 characters"
    assert send(anon, status=400, message="a\x00b")["error"] == "message may not contain control characters"
    assert send(anon, status=400, modVersion="x" * 41)["error"] == "modVersion must be at most 40 characters"
    assert send(anon, status=400, contact="a\nb")["error"].startswith("contact may not contain")
    assert send(anon, status=400, mcVersion=26.3)["error"] == "mcVersion must be text"
    rows = client.app.state.store.db.execute("SELECT COUNT(*) FROM feedback").fetchone()[0]
    assert rows == 4


def test_feedback_rate_limit(client, alice):
    anon = TestClient(client.app, client=("203.0.113.5", 1000))
    # rejected submissions don't count
    send(anon, status=400, type="nope")
    for _ in range(FEEDBACK_IP_LIMIT):
        send(anon)
    r = send(anon, status=429)
    assert r["error"] == "too many messages for now, try again in 15 minutes"
    # per address: another one still can; behind the local proxy, the forwarded address counts
    send(TestClient(client.app, client=("203.0.113.6", 1000)))
    proxied = TestClient(client.app, client=("127.0.0.1", 1000))
    send(proxied, status=429, headers={**CSRF, "X-Forwarded-For": "203.0.113.5"})
    send(proxied, headers={**CSRF, "X-Forwarded-For": "203.0.113.7"})
    # logged in from the same address: counted per account, with a higher limit
    web = alice.cookie_client(client.app)
    web_same_ip = TestClient(client.app, client=("203.0.113.5", 1000), cookies=web.cookies)
    for _ in range(FEEDBACK_IP_LIMIT + 1):
        send(web_same_ip)
    # the window passes
    client.app.state.store.db.execute("UPDATE login_failures SET at = at - 3600")
    send(anon)


def test_admin_feedback_list(client, admin, alice):
    anon = TestClient(client.app)
    first = send(anon, message="<b>first</b>", contact="me@example.com")
    second = send(alice.cookie_client(client.app), type="suggestion", message="second", modVersion="0.6.0")
    # admins only, and to anyone else the page is simply not there
    for c in (anon, alice.cookie_client(client.app)):
        r = c.get("/admin/feedback")
        assert r.status_code == 404 and "first" not in r.text
    assert anon.get("/api/feedback").status_code == 401
    assert client.get("/api/feedback", headers=alice.h).status_code == 403
    assert client.post(f"/api/feedback/{first['id']}/resolve", headers=alice.h, json={"resolved": True}).status_code == 403

    web = admin.cookie_client(client.app)
    html = web.get("/admin/feedback").text
    assert "&lt;b&gt;first&lt;/b&gt;" in html and "<b>first" not in html and "me@example.com" in html
    assert html.index("second") < html.index("first")  # newest first
    assert "Open (2)" in html and "Resolved (0)" in html and "Alice" in html and "anonymous" in html
    assert 'data-resolve="%d" data-resolved="false"' % first["id"] in html
    assert 'href="/admin/feedback">Feedback</a>' in web.get("/account").text
    assert "/admin/feedback" not in alice.cookie_client(client.app).get("/account").text

    # resolve, by cookie (with the header) or token
    url = f"/api/feedback/{first['id']}/resolve"
    assert web.post(url, json={"resolved": True}).status_code == 403
    r = web.post(url, json={"resolved": True}, headers=CSRF)
    assert r.status_code == 200 and r.json()["resolved"] is not None
    resolved_at = r.json()["resolved"]
    assert client.post(url, headers=admin.h, json={"resolved": True}).json()["resolved"] == resolved_at
    assert client.post(url, headers=admin.h, json={"resolved": "yes"}).status_code == 400
    assert client.post("/api/feedback/99999/resolve", headers=admin.h, json={"resolved": True}).status_code == 404

    listing = client.get("/api/feedback", headers=admin.h).json()
    assert [f["id"] for f in listing["feedback"]] == [second["id"]] and listing["open"] == 1 and listing["resolved"] == 1
    every = client.get("/api/feedback?status=all", headers=admin.h).json()["feedback"]
    assert [f["id"] for f in every] == [second["id"], first["id"]]  # open ones first
    assert client.get("/api/feedback?status=resolved", headers=admin.h).json()["feedback"][0]["contact"] == "me@example.com"
    assert client.get("/api/feedback?status=nope", headers=admin.h).status_code == 400
    html = web.get("/admin/feedback").text
    assert "first" not in html and "Open (1)" in html and "Resolved (1)" in html
    assert "first" in web.get("/admin/feedback?status=resolved").text
    # and reopen
    assert client.post(url, headers=admin.h, json={"resolved": False}).json()["resolved"] is None
    assert client.get("/api/feedback", headers=admin.h).json()["open"] == 2


# ------------------------------------------------------------------ CLI


def write_jar(tmp_path, name="slopmatica-0.6.0.jar", version="0.6.0"):
    p = tmp_path / name
    p.write_bytes(jar_bytes(version))
    return p


def test_cli_publish(client, tmp_path, data_dir, capsys):
    jar = write_jar(tmp_path)
    notes = tmp_path / "notes.md"
    notes.write_text("- **new** thing\n")
    argv = [str(jar), "--mc", "26.3", "--loader", "fabric", "--changelog-file", str(notes), "--data", str(data_dir)]
    assert publish_release.main(argv, env={}) == 0
    out = capsys.readouterr().out
    assert out.startswith("published 0.6.0 for Minecraft 26.3 (fabric): ") and hashlib.sha256(jar.read_bytes()).hexdigest() in out
    assert (data_dir / "releases" / "slopmatica-0.6.0.jar").read_bytes() == jar.read_bytes()
    # the running server sees it straight away
    latest = client.get("/api/releases/latest?mc=26.3&loader=fabric").json()
    assert latest["version"] == "0.6.0" and latest["sha256"] == hashlib.sha256(jar.read_bytes()).hexdigest()
    assert client.get("/api/releases").json()["releases"][0]["changelog"] == "- **new** thing"
    assert "<strong>new</strong>" in client.get("/releases").text
    assert client.get("/releases/slopmatica-0.6.0.jar").content == jar.read_bytes()

    # taken: the same version again, or the same file name
    assert publish_release.main(argv, env={}) == 1
    assert "already published" in capsys.readouterr().err
    assert publish_release.main([*argv, "--version", "0.6.1"], env={}) == 1
    assert "already exists" in capsys.readouterr().err
    # --version and --filename override the jar's, --hidden keeps it out of the update check
    assert publish_release.main([str(jar), "--mc", "26.3", "--version", "0.7.0", "--filename", "s-0.7.0.jar",
                                 "--hidden", "--data", str(data_dir)], env={}) == 0
    assert "(hidden)" in capsys.readouterr().out
    assert client.get("/api/releases/latest?mc=26.3").json()["version"] == "0.6.0"
    # bad input
    assert publish_release.main([str(jar), "--mc", "26 3", "--version", "0.8.0", "--data", str(data_dir)], env={}) == 1
    assert "mc must be" in capsys.readouterr().err
    assert publish_release.main([str(jar), "--mc", "26.3", "--version", "0.8.0", "--filename", "../x.jar",
                                 "--data", str(data_dir)], env={}) == 1
    assert "filename must be" in capsys.readouterr().err
    assert not (data_dir / "x.jar").exists()
    not_a_jar = tmp_path / "plain.jar"
    not_a_jar.write_bytes(b"hello")
    assert publish_release.main([str(not_a_jar), "--mc", "26.3", "--version", "0.8.0", "--data", str(data_dir)], env={}) == 1
    assert "not a jar (zip) file" in capsys.readouterr().err
    assert publish_release.main([str(tmp_path / "missing.jar"), "--mc", "26.3", "--data", str(data_dir)], env={}) == 1
    assert sorted(p.name for p in (data_dir / "releases").iterdir()) == ["s-0.7.0.jar", "slopmatica-0.6.0.jar"]


def test_cli_version_from_the_jar(tmp_path, data_dir, client, capsys):
    unexpanded = write_jar(tmp_path, "dev.jar", "${version}")
    assert publish_release.main([str(unexpanded), "--mc", "26.3", "--data", str(data_dir)], env={}) == 1
    assert "pass --version" in capsys.readouterr().err
    assert publish_release.jar_version(write_jar(tmp_path, "b.jar", "1.2.3-beta.1")) == "1.2.3-beta.1"
    (tmp_path / "plain.jar").write_bytes(b"PK\x03\x04 not really")
    assert publish_release.jar_version(tmp_path / "plain.jar") is None


def test_cli_needs_a_migrated_database(tmp_path, capsys):
    data = tmp_path / "old"
    data.mkdir()
    db = sqlite3.connect(data / "cloud.db")
    db.executescript(SCHEMA_V3)
    db.close()
    jar = write_jar(tmp_path)
    assert publish_release.main([str(jar), "--mc", "26.3", "--data", str(data)], env={}) == 1
    assert "is at schema 3; start the 0.6.0 server once" in capsys.readouterr().err
    assert not (data / "releases").exists()
    assert publish_release.main([str(jar), "--mc", "26.3", "--data", str(tmp_path / "none")], env={}) == 1
    assert "no database" in capsys.readouterr().err


def test_cli_finds_the_data_dir(tmp_path):
    env_file = tmp_path / "env"
    env_file.write_text('# comment\nSLOPMATICA_ROOT_PATH=/slopmatica\nSLOPMATICA_DATA="/srv/slop data"\n')
    find = publish_release.find_data_dir
    assert find("/a", {"SLOPMATICA_DATA": "/b"}, env_file) == publish_release.Path("/a")
    assert find(None, {"SLOPMATICA_DATA": "/b"}, env_file) == publish_release.Path("/b")
    assert find(None, {}, env_file) == publish_release.Path("/srv/slop data")
    assert find(None, {}, tmp_path / "missing") == Settings().data_dir


# ------------------------------------------------------------------ migration 3 -> 4

# The schema exactly as 0.5.0 (user_version 3) leaves it: the live 0.4.0 schema plus migration 3.
SCHEMA_V3 = """
CREATE TABLE users (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, is_admin INTEGER NOT NULL DEFAULT 0,
    created INTEGER NOT NULL, last_login INTEGER NOT NULL, password_hash TEXT, password_set INTEGER);
CREATE TABLE tokens (hash TEXT PRIMARY KEY, kind TEXT NOT NULL, uuid TEXT NOT NULL REFERENCES users(uuid),
    created INTEGER NOT NULL, expires INTEGER NOT NULL, next TEXT);
CREATE INDEX tokens_uuid ON tokens(uuid);
CREATE TABLE challenges (server_id TEXT PRIMARY KEY, expires INTEGER NOT NULL);
CREATE TABLE files (uuid TEXT NOT NULL, path TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL,
    modified INTEGER NOT NULL, has_thumb INTEGER NOT NULL DEFAULT 0, source_post TEXT, source_title TEXT, source_author_uuid TEXT, source_author_name TEXT, PRIMARY KEY (uuid, path));
CREATE TABLE posts (id TEXT PRIMARY KEY, uuid TEXT NOT NULL REFERENCES users(uuid), title TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    visibility TEXT NOT NULL CHECK (visibility IN ('public', 'unlisted', 'private')),
    source_path TEXT NOT NULL, filename TEXT NOT NULL, format TEXT NOT NULL, size INTEGER NOT NULL,
    sha256 TEXT NOT NULL, has_thumb INTEGER NOT NULL DEFAULT 0, size_x INTEGER, size_y INTEGER, size_z INTEGER,
    block_count INTEGER, created INTEGER NOT NULL, updated INTEGER NOT NULL, size_class TEXT);
CREATE INDEX posts_uuid ON posts(uuid);
CREATE INDEX posts_visibility ON posts(visibility, created);
CREATE TABLE post_access (post_id TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE, uuid TEXT NOT NULL,
    name TEXT NOT NULL, PRIMARY KEY (post_id, uuid));
CREATE INDEX post_access_uuid ON post_access(uuid);
CREATE TABLE votes (post_id TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE, uuid TEXT NOT NULL REFERENCES users(uuid),
    value INTEGER NOT NULL CHECK (value IN (-1, 1)), created INTEGER NOT NULL, PRIMARY KEY (post_id, uuid));
CREATE TABLE login_failures (key TEXT NOT NULL, at INTEGER NOT NULL);
CREATE INDEX login_failures_key ON login_failures(key, at);
CREATE INDEX posts_size_class ON posts(size_class);
CREATE TABLE post_tags (
    post_id TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    tag TEXT NOT NULL,
    PRIMARY KEY (post_id, tag)
);
CREATE INDEX post_tags_tag ON post_tags(tag, post_id);
PRAGMA user_version = 3;
"""

UUID = "12345678-1234-1234-1234-123456789abc"


def v3_data_dir(data_dir):
    """A 0.5.0 data directory: an admin with a cloud file and a tagged public post, and a login failure."""
    t = int(time.time())
    ms = t * 1000
    body = gz(b"schematic")
    files = data_dir / "users" / UUID / "files"
    files.mkdir(parents=True)
    (files / "house.slopmatic").write_bytes(body)
    (data_dir / "posts" / "HousePost1").mkdir(parents=True)
    (data_dir / "posts" / "HousePost1" / "house.slopmatic").write_bytes(body)
    digest = hashlib.sha256(body).hexdigest()
    db = sqlite3.connect(data_dir / "cloud.db")
    db.executescript(SCHEMA_V3)
    db.execute("INSERT INTO users VALUES(?,?,?,?,?,NULL,NULL)", (UUID, ADMIN, 1, t - 1000, t - 10))
    db.execute("INSERT INTO tokens VALUES(?,?,?,?,?,NULL)", (sha256_hex("tok"), "api", UUID, t, t + 86400))
    db.execute("INSERT INTO files VALUES(?,?,?,?,?,0,NULL,NULL,NULL,NULL)", (UUID, "house.slopmatic", len(body), digest, ms))
    db.execute("INSERT INTO posts VALUES(?,?,?,?,?,?,?,?,?,?,0,1,1,1,1,?,?,?)",
               ("HousePost1", UUID, "House", "", "public", "house.slopmatic", "house.slopmatic", "slopmatic",
                len(body), digest, ms, ms, "small"))
    db.execute("INSERT INTO post_tags VALUES(?,?)", ("HousePost1", "building"))
    db.execute("INSERT INTO login_failures VALUES(?,?)", ("name:someone", t))
    db.commit()
    db.close()
    return body


def columns(db, table):
    return [r[1] for r in db.execute(f"PRAGMA table_info({table})")]


def test_migrates_a_v3_database(make_client, mojang, data_dir):
    body = v3_data_dir(data_dir)
    c = make_client()
    db = c.app.state.store.db
    assert db.execute("PRAGMA user_version").fetchone()[0] == SCHEMA_VERSION
    assert columns(db, "releases") == ["id", "version", "mc_version", "loader", "filename", "sha256", "size",
                                       "uploaded", "changelog", "hidden", "downloads"]
    assert columns(db, "feedback") == ["id", "kind", "message", "mod_version", "mc_version", "contact", "uuid",
                                       "name", "created", "resolved"]
    assert (data_dir / "releases").is_dir()
    # everything from before is still there and works
    h = bearer("tok")
    me = c.get("/api/me", headers=h).json()
    assert me["name"] == ADMIN and me["quotaBytes"] is None
    assert [f["path"] for f in c.get("/api/files", headers=h).json()["files"]] == ["house.slopmatic"]
    post = c.get("/api/board/HousePost1").json()
    assert post["tags"] == ["building"] and post["sizeClass"] == "small"
    assert c.get("/api/board/HousePost1/file").content == body
    assert db.execute("SELECT COUNT(*) FROM login_failures WHERE key='name:someone'").fetchone()[0] == 1
    # and the new tables take data: the migrated admin can publish, anyone can send feedback
    publish(c, h, filename="slopmatica-0.6.0.jar")
    send(TestClient(c.app))
    # a restart migrates nothing twice and keeps it all
    c2 = make_client()
    assert c2.app.state.store.db.execute("PRAGMA user_version").fetchone()[0] == SCHEMA_VERSION
    assert c2.get("/api/releases/latest?mc=26.3").json()["version"] == "0.6.0"
    assert c2.get("/api/feedback", headers=h).json()["open"] == 1


def test_migration_4_is_one_transaction(make_client, mojang, data_dir, monkeypatch):
    """A failure part way leaves the database at version 3 with no half-made tables, and the next start succeeds."""
    v3_data_dir(data_dir)
    monkeypatch.setitem(MIGRATIONS, 4, MIGRATIONS[4] + "\nCREATE TABLE releases (x);")  # fails at the end
    with pytest.raises(sqlite3.OperationalError):
        make_client()
    db = sqlite3.connect(data_dir / "cloud.db")
    assert db.execute("PRAGMA user_version").fetchone()[0] == 3
    assert db.execute("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('releases', 'feedback')").fetchone()[0] == 0
    db.close()
    monkeypatch.undo()
    assert make_client().app.state.store.db.execute("PRAGMA user_version").fetchone()[0] == SCHEMA_VERSION
