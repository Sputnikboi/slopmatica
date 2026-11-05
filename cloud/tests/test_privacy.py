"""0.14.0: usage reports and bug reports keep less: coarse telemetry, unlinked after a month, anonymous feedback,
scrubbed logs, expiring reports, and no stored addresses."""

from __future__ import annotations

import gzip
import hashlib
import time

from fastapi.testclient import TestClient

from slopmatica_cloud.app import DAY, coarse_gpu, coarse_os, floor_to, round_to, scrub_log
from test_board import CSRF
from test_v080 import report_body
from test_v090 import gated, ppl  # noqa: F401 - fixtures
from test_v100 import send_report as send_multipart

NO_FILE = {"schematic": ("", b"", "x")}  # so a report without files is still multipart, as the mod sends it


def send_report(c, headers, status=201, data=None, files=None):
    return send_multipart(c, headers, status, data, files or NO_FILE)


def test_coarsening_helpers():
    assert coarse_os("Windows 11 10.0") == "Windows 11" and coarse_os("Linux 7.0.0-38-generic") == "Linux"
    assert coarse_os("Mac OS X 14.5") == "macOS" and coarse_os(None) is None
    assert coarse_gpu("NVIDIA GeForce RTX 3080/PCIe/SSE2") == "NVIDIA GeForce RTX 3080" and coarse_gpu("") is None
    assert round_to(1920, 160) == 1920 and round_to(1366, 160) == 1440 and round_to(3, 90) == 90 and round_to(None, 9) is None
    assert floor_to(4096, 512) == 4096 and floor_to(4095, 512) == 3584 and floor_to(1900, 512) == 1536


def test_telemetry_keeps_only_coarse_values(gated):
    c = gated
    body = report_body(specs={"os": "Linux 7.0.0-38-generic", "gpu": "NVIDIA GeForce RTX 3080/PCIe/SSE2",
                              "width": 1366, "height": 768, "maxMemoryMb": 4000})
    assert c.post("/api/telemetry", json=body).status_code == 204
    # what 0.32.0 mods send: the session's length, no times
    new = report_body("install-2", minutes=12)
    del new["start"], new["end"]
    assert c.post("/api/telemetry", json=new).status_code == 204
    rows = [dict(r) for r in c.app.state.store.db.execute("SELECT * FROM telemetry ORDER BY id")]
    old = rows[0]
    assert (old["started"], old["ended"], old["minutes"]) == (None, None, 30)
    assert (old["os"], old["gpu"], old["width"], old["height"], old["max_memory_mb"]) == (
        "Linux", "NVIDIA GeForce RTX 3080", 1440, 810, 3584)
    assert old["received"] % DAY == 0 and rows[1]["minutes"] == 12


def test_scrub_telemetry_unlinks_old_reports(gated):
    store = gated.app.state.store
    t = int(time.time())
    install = hashlib.sha256(b"x").hexdigest()
    for received in (t - 40 * DAY, t - 5 * DAY):
        store.db.execute(
            """INSERT INTO telemetry(received, install, started, ended, os, gpu, width, height, max_memory_mb)
               VALUES(?,?,?,?,?,?,?,?,?)""",
            (received, install, received - 600, received, "Windows 10 10.0.19045", "Intel(R) UHD/PCIe", 1600, 900, 8191))
    store.scrub_telemetry()
    old, recent = [dict(r) for r in store.db.execute("SELECT * FROM telemetry ORDER BY received")]
    assert len(old["install"]) == 32 and old["install"] != install and recent["install"] == install
    for r in (old, recent):
        assert (r["started"], r["ended"], r["minutes"], r["received"] % DAY) == (None, None, 10, 0)
        assert (r["os"], r["gpu"], r["width"], r["height"], r["max_memory_mb"]) == ("Windows 10", "Intel(R) UHD", 1600, 900, 7680)
    store.scrub_telemetry()  # again: nothing more changes
    assert [dict(r) for r in store.db.execute("SELECT * FROM telemetry ORDER BY received")] == [old, recent]


def test_no_addresses_stored(gated, ppl):  # noqa: F811
    c = gated
    who = TestClient(c.app, client=("198.51.100.7", 5555))
    who.post("/api/login", headers=CSRF, json={"name": "Alice", "password": "wrong password"})
    send_report(who, CSRF)
    dump = "\n".join(c.app.state.store.db.iterdump())
    assert "198.51.100.7" not in dump and "feedback-ip:" in dump


def test_feedback_without_name(gated, ppl):  # noqa: F811
    c, a = gated, ppl.alice
    f = send_report(c, a.h, data={"anonymous": "true"})
    assert f["user"] is None
    assert c.post("/api/feedback", headers=a.h, json={"type": "bug", "message": "hi", "anonymous": True}).json()["user"] is None
    assert send_report(c, a.h, data={"anonymous": ""})["user"] == {"uuid": a.uuid, "name": "Alice"}
    stored = c.app.state.store.db.execute("SELECT uuid, name FROM feedback WHERE id <= ?", (f["id"] + 1,)).fetchall()
    assert [tuple(r) for r in stored] == [(None, None), (None, None)]


LOG = (b"[12:00:00] [Render thread/INFO]: Connecting to 203.0.113.50, 25565\n"
       b"[12:00:01] [Render thread/INFO]: [System] [CHAT] <Bob> my secret\n"
       b"[12:00:02] [Render thread/INFO]: Loading Slopmatica 0.31.0 on LWJGL 3.3.3\n")


def test_logs_are_scrubbed(gated, ppl):  # noqa: F811
    c, store = gated, gated.app.state.store
    want = (b"[12:00:00] [Render thread/INFO]: Connecting to <address>, 25565\n"
            b"[12:00:02] [Render thread/INFO]: Loading Slopmatica 0.31.0 on LWJGL 3.3.3\n")
    assert scrub_log(LOG) == want
    plain = send_report(c, ppl.alice.h, files={"log": ("latest.log", LOG, "text/plain")})
    packed = send_report(c, ppl.alice.h, files={"log": ("latest.log", gzip.compress(LOG), "application/gzip")})
    assert (store.feedback_root / str(plain["id"]) / "log").read_bytes() == want
    assert gzip.decompress((store.feedback_root / str(packed["id"]) / "log").read_bytes()) == want


def test_old_feedback_expires(gated, ppl):  # noqa: F811
    c, store = gated, gated.app.state.store
    files = {"log": ("latest.log", b"log line\n", "text/plain")}
    old_open, old_resolved, fresh = (send_report(c, ppl.alice.h, files=files)["id"] for _ in range(3))
    ms = int(time.time() * 1000)
    store.db.execute("UPDATE feedback SET created=? WHERE id=?", (ms - 100 * DAY * 1000, old_open))
    store.db.execute("UPDATE feedback SET created=?, resolved=? WHERE id=?", (ms - 400 * DAY * 1000, ms - 370 * DAY * 1000, old_resolved))
    store.housekeeping()
    ids = [r[0] for r in store.db.execute("SELECT id FROM feedback ORDER BY id")]
    assert old_resolved not in ids and old_open in ids and fresh in ids
    assert set(store.feedback_attachments([old_open, fresh])) == {fresh}
    assert not (store.feedback_root / str(old_open)).exists() and not (store.feedback_root / str(old_resolved)).exists()
