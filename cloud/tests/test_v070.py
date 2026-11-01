"""0.7.0: the Fruitbowl whitelist gate, applications, storage requests, the admin dashboard, badges, reports,
the word filter, refusing modded schematics, and the schema 4 -> 5 migration."""

import hashlib
import json
import sqlite3
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from slopmatica_cloud import __version__, convert, nbt, textfilter
from slopmatica_cloud.app import (
    GIB, MIGRATIONS, REPORT_USER_LIMIT, REQUEST_LIMIT, SCHEMA_VERSION, Settings, create_app, sha256_hex,
)

from conftest import PNG, bearer, gz
from test_board import CSRF, Who, make_post, schem, upload
from test_convert import state, synthetic

ADMIN = "Sputnikboi"  # in Settings.admins by default
APPLY = "http://127.0.0.1:8095/apply"
REAL_WHITELIST = Path.home() / "minecraft-arg" / "server" / "whitelist.json"


@pytest.fixture
def gated(make_client):
    """A client with the whitelist enforced (the default outside the older tests' fixtures)."""
    return make_client(whitelist_enforced=True)


@pytest.fixture
def ppl(gated, mojang):
    class P:
        admin = Who(gated, mojang, ADMIN)
        member = Who(gated, mojang, "Member")
        outsider = Who(gated, mojang, "Outsider")
    gated.app.state.store.whitelist_add(P.member.uuid, "Member", "manual", None)
    return P


def not_whitelisted(r):
    assert r.status_code == 403, r.text
    body = r.json()
    assert body["code"] == "not_whitelisted" and body["apply"] == APPLY and "Fruitbowl" in body["error"]
    assert set(body) == {"error", "code", "apply"}
    return body


def modded_schem(block=True, entity=False) -> bytes:
    s = synthetic()
    if block:
        s.regions[0].palette[1] = state(s.data_version, "create:shaft")
    if entity:
        s.regions[0].entities[0].data["id"] = nbt.String("alexsmobs:crow")
    return nbt.dumps(convert.write(s, "litematic"))


def whitelist_file(tmp_path, entries, name="whitelist.json"):
    p = tmp_path / name
    p.write_text(json.dumps([{"uuid": u, "name": n} for u, n in entries]))
    return p


# ------------------------------------------------------------------ versions and settings


def test_version_bumped():
    assert SCHEMA_VERSION >= 5  # 0.7.0; test_v080 checks the current version


def test_settings_from_env(tmp_path):
    s = Settings.from_env({})
    assert s.whitelist_enforced is True and s.whitelist_seed is None
    for off in ("0", "false", "No", " off "):
        assert Settings.from_env({"SLOPMATICA_WHITELIST_ENFORCED": off}).whitelist_enforced is False
    assert Settings.from_env({"SLOPMATICA_WHITELIST_ENFORCED": "1"}).whitelist_enforced is True
    seed = Settings.from_env({"SLOPMATICA_WHITELIST_SEED": str(tmp_path / "w.json")}).whitelist_seed
    assert seed == tmp_path / "w.json"


# ------------------------------------------------------------------ the gate


def test_gate_on_every_protected_endpoint(gated, ppl, data_dir):
    c, out = gated, ppl.outsider
    # the account itself works: login answers say whether the player has access, and where to apply
    me = c.get("/api/me", headers=out.h).json()
    assert me["whitelisted"] is False and me["apply"] == APPLY and me["badges"] == []
    assert c.get("/api/me", headers=ppl.member.h).json()["whitelisted"] is True
    assert "apply" not in c.get("/api/me", headers=ppl.member.h).json()
    assert c.post("/api/weblogin", headers=out.h).status_code == 200  # so they can open the apply page

    # sync: every files, thumbnails and export call
    not_whitelisted(c.get("/api/files", headers=out.h))
    not_whitelisted(c.put("/api/files/a.litematic", content=schem(), headers=out.h))
    not_whitelisted(c.get("/api/files/a.litematic", headers=out.h))
    not_whitelisted(c.delete("/api/files/a.litematic", headers=out.h))
    not_whitelisted(c.post("/api/files/move", json={"from": "a.litematic", "to": "b.litematic"}, headers=out.h))
    not_whitelisted(c.put("/api/thumbnails/a.litematic", content=PNG, headers=out.h))
    not_whitelisted(c.get("/api/thumbnails/a.litematic", headers=out.h))
    not_whitelisted(c.get("/api/export/a.litematic?format=nbt", headers=out.h))
    # the board: posting, voting, saving, and the author's own changes
    upload(c, ppl.member.h, "m.litematic")
    post = make_post(c, ppl.member.h, "m.litematic")
    not_whitelisted(c.post("/api/board", json={"path": "a.litematic", "title": "x", "visibility": "public"}, headers=out.h))
    not_whitelisted(c.post(f"/api/board/{post['id']}/vote", json={"value": 1}, headers=out.h))
    not_whitelisted(c.post(f"/api/board/{post['id']}/save", headers=out.h))
    # reports and storage requests
    not_whitelisted(c.post("/api/reports", json={"kind": "post", "target": post["id"], "reason": "spam"}, headers=out.h))
    not_whitelisted(c.get("/api/account/storage-request", headers=out.h))
    not_whitelisted(c.post("/api/account/storage-request", json={"bytes": 2 * GIB, "reason": "x"}, headers=out.h))
    # by website cookie too (with the CSRF header the gate is what answers)
    web = out.cookie_client(c.app)
    not_whitelisted(web.get("/api/files"))
    not_whitelisted(web.put("/api/files/a.litematic", content=schem(), headers=CSRF))
    # bad tokens are still 401, not 403
    assert c.get("/api/files", headers=bearer("nope")).status_code == 401
    # members and admins (who need no whitelist entry) are fine
    assert c.get("/api/files", headers=ppl.member.h).status_code == 200
    assert c.get("/api/files", headers=ppl.admin.h).status_code == 200
    assert not (data_dir / "users" / out.uuid / "files").exists()  # nothing was stored


def test_gate_on_the_authors_own_post(gated, ppl):
    c, store = gated, gated.app.state.store
    # an author who loses access keeps the post, but can't change it
    store.whitelist_add(ppl.outsider.uuid, "Outsider", "manual", None)
    upload(c, ppl.outsider.h, "o.litematic")
    post = make_post(c, ppl.outsider.h, "o.litematic", title="Old build")
    store.whitelist_remove([ppl.outsider.uuid])
    not_whitelisted(c.patch(f"/api/board/{post['id']}", json={"title": "New"}, headers=ppl.outsider.h))
    not_whitelisted(c.post(f"/api/board/{post['id']}/refresh", headers=ppl.outsider.h))
    not_whitelisted(c.delete(f"/api/board/{post['id']}", headers=ppl.outsider.h))
    assert c.get(f"/api/board/{post['id']}").json()["title"] == "Old build"
    html = ppl.outsider.cookie_client(c.app).get(f"/board/{post['id']}").text
    assert "Editing, updating and deleting posts is for Fruitbowl members" in html and 'data-post-act="refresh"' not in html


def test_data_is_kept_while_access_is_off(gated, ppl, data_dir):
    c, store = gated, gated.app.state.store
    body = schem()
    upload(c, ppl.member.h, "keep/me.litematic", body)
    store.whitelist_remove([ppl.member.uuid])
    not_whitelisted(c.get("/api/files", headers=ppl.member.h))
    assert (data_dir / "users" / ppl.member.uuid / "files" / "keep" / "me.litematic").read_bytes() == body
    gated2 = TestClient(create_app(c.app.state.settings))  # a restart doesn't drop it either
    store.whitelist_add(ppl.member.uuid, "Member", "manual", None)
    files = gated2.get("/api/files", headers=ppl.member.h).json()["files"]
    assert [f["path"] for f in files] == ["keep/me.litematic"]
    assert gated2.get("/api/files/keep/me.litematic", headers=ppl.member.h).content == body


def test_public_board_stays_open(gated, ppl):
    c = gated
    upload(c, ppl.member.h, "pub.litematic", thumb=True)
    post = make_post(c, ppl.member.h, "pub.litematic", title="Open house")
    anon = TestClient(c.app)
    out_web = ppl.outsider.cookie_client(c.app)
    for viewer in (anon, out_web):
        assert [p["id"] for p in viewer.get("/api/board").json()["posts"]] == [post["id"]]
        assert viewer.get(f"/api/board/{post['id']}").status_code == 200
        assert viewer.get(f"/api/board/{post['id']}/file").status_code == 200
        assert viewer.get(f"/api/board/{post['id']}/thumbnail").status_code == 200
        assert viewer.get(f"/api/board/{post['id']}/export?format=nbt").status_code == 200
        assert viewer.get(f"/api/board/{post['id']}/model").status_code == 200
        assert "Open house" in viewer.get("/board").text
        assert viewer.get(f"/board/{post['id']}").status_code == 200
    # the outsider's tabs and the token API read too
    assert c.get("/api/board?tab=mine", headers=ppl.outsider.h).status_code == 200
    assert c.get("/api/board?tab=shared", headers=ppl.outsider.h).status_code == 200
    # the post page: no voting or saving for them, and a way to apply
    html = out_web.get(f"/board/{post['id']}").text
    assert 'data-post-act="vote" data-value="1" aria-label="Upvote" aria-pressed="false" disabled' in html
    assert 'data-post-act="save"' not in html and 'href="/apply">Apply for access' in html
    assert "data-report-form" not in html


def test_gate_can_be_turned_off(make_client, mojang):
    c = make_client(whitelist_enforced=False)
    out = Who(c, mojang, "Outsider")
    assert c.get("/api/me", headers=out.h).json()["whitelisted"] is True
    upload(c, out.h, "a.litematic")
    assert c.post("/api/application", headers=out.h, json={"reason": "x", "community": "y"}).status_code == 409


def test_website_for_outsiders(gated, ppl):
    web = ppl.outsider.cookie_client(gated.app)
    r = web.get("/", follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/apply"
    html = web.get("/apply").text
    assert "Slopmatica Cloud is for Fruitbowl members" in html and 'id="apply-form"' in html
    assert 'href="/apply"' in html and ">Get access<" in html and ">My files<" not in html
    assert 'src="/static/access.js' in html
    account = web.get("/account").text
    assert "Apply for access" in account and "data-storage-form" not in account
    # logged out: what it is and how to log in
    anon = TestClient(gated.app).get("/apply").text
    assert "Log in from the game first" in anon and "apply-form" not in anon
    # a member is told they're in
    assert "You&rsquo;re in, Member" in ppl.member.cookie_client(gated.app).get("/apply").text
    assert ppl.member.cookie_client(gated.app).get("/", follow_redirects=False).status_code == 200


# ------------------------------------------------------------------ applications


def apply_(c, who, status=201, **body):
    body = {"reason": "I build farms", "community": "Played on Fruitbowl with Member", "contact": "out#1", **body}
    r = c.post("/api/application", headers=who.h, json=body)
    assert r.status_code == status, r.text
    return r.json()


def test_application_approve(gated, ppl):
    c = gated
    a = apply_(c, ppl.outsider, contact="  disc#42 ", reason="  Farms\r\nand more ")
    assert a["status"] == "pending" and a["reason"] == "Farms\nand more" and a["contact"] == "disc#42"
    assert a["user"] == {"uuid": ppl.outsider.uuid, "name": "Outsider", "displayName": "Outsider", "tag": None}
    assert a["note"] is None
    assert apply_(c, ppl.outsider, status=409)["error"].startswith("you already have an open application")
    status = c.get("/api/application", headers=ppl.outsider.h).json()
    assert status["whitelisted"] is False and status["application"]["id"] == a["id"] and status["apply"] == APPLY
    html = ppl.outsider.cookie_client(c.app).get("/apply").text
    assert "Your application is waiting for an admin" in html and "apply-form" not in html
    # validation
    assert apply_(c, ppl.member, status=409)["error"] == "you already have access"
    # the admin sees it and approves
    listing = c.get("/api/admin/applications", headers=ppl.admin.h).json()
    assert [x["id"] for x in listing["applications"]] == [a["id"]] and listing["counts"] == {"pending": 1}
    assert listing["applications"][0]["whitelisted"] is False
    r = c.post(f"/api/admin/applications/{a['id']}/approve", headers=ppl.admin.h, json={})
    assert r.status_code == 200 and r.json()["status"] == "approved" and r.json()["decidedBy"] == ppl.admin.uuid
    assert c.post(f"/api/admin/applications/{a['id']}/reject", headers=ppl.admin.h, json={}).status_code == 409
    assert c.post("/api/admin/applications/9999/approve", headers=ppl.admin.h, json={}).status_code == 404
    entry = c.app.state.store.whitelist_entry(ppl.outsider.uuid)
    assert entry["source"] == "approved" and entry["name"] == "Outsider" and entry["added_by"] == ppl.admin.uuid
    assert c.get("/api/files", headers=ppl.outsider.h).status_code == 200
    assert c.get("/api/application", headers=ppl.outsider.h).json()["application"]["status"] == "approved"


def test_application_validation_and_reject(gated, ppl):
    c = gated
    assert apply_(c, ppl.outsider, status=400, reason="  ")["error"] == "reason is required"
    assert apply_(c, ppl.outsider, status=400, community=None)["error"] == "community is required"
    assert apply_(c, ppl.outsider, status=400, reason="x" * 2001)["error"] == "reason must be at most 2000 characters"
    assert apply_(c, ppl.outsider, status=400, contact="a\nb")["error"].startswith("contact may not contain")
    web = ppl.outsider.cookie_client(c.app)
    assert web.post("/api/application", json={"reason": "a", "community": "b"}).status_code == 403  # CSRF
    a = apply_(c, ppl.outsider)
    r = c.post(f"/api/admin/applications/{a['id']}/reject", headers=ppl.admin.h, json={"note": "Ask in Discord first"})
    assert r.json()["status"] == "rejected" and r.json()["note"] == "Ask in Discord first"
    assert c.app.state.store.on_whitelist(ppl.outsider.uuid) is False
    html = web.get("/apply").text
    assert "Your last application was not accepted" in html and "Ask in Discord first" in html and 'id="apply-form"' in html
    # they can apply again, up to the limit
    for _ in range(REQUEST_LIMIT - 1):
        b = apply_(c, ppl.outsider)
        c.post(f"/api/admin/applications/{b['id']}/reject", headers=ppl.admin.h, json={})
    assert apply_(c, ppl.outsider, status=429)["error"].startswith("too many applications")
    # the window passes
    c.app.state.store.db.execute("UPDATE applications SET created = created - 8 * 86400000")
    apply_(c, ppl.outsider)


# ------------------------------------------------------------------ storage requests


def ask(c, who, status=201, **body):
    r = c.post("/api/account/storage-request", headers=who.h, json={"bytes": 2 * GIB, "reason": "Map art", **body})
    assert r.status_code == status, r.text
    return r.json()


def test_storage_request_approve_and_reject(gated, ppl):
    c = gated
    st = c.get("/api/account/storage-request", headers=ppl.member.h).json()
    assert st["quotaBytes"] == GIB and st["choices"] == [2 * GIB, 5 * GIB, 10 * GIB] and st["request"] is None
    assert ask(c, ppl.member, status=400, bytes=GIB)["error"].startswith("bytes must be more than your current quota")
    assert ask(c, ppl.member, status=400, bytes="2GB")["error"].startswith("bytes must be")
    assert ask(c, ppl.member, status=400, reason="")["error"] == "reason is required"
    assert ask(c, ppl.admin, status=409)["error"] == "your storage is already unlimited"
    r = ask(c, ppl.member, bytes=5 * GIB)
    assert r["status"] == "pending" and r["requestedBytes"] == 5 * GIB and r["quotaBytes"] == GIB
    assert ask(c, ppl.member, status=409)["error"].startswith("you already have an open storage request")
    listing = c.get("/api/admin/storage-requests", headers=ppl.admin.h).json()
    assert listing["requests"][0]["current"] == {"usedBytes": 0, "quotaBytes": GIB}
    # approved with another amount than asked
    ok = c.post(f"/api/admin/storage-requests/{r['id']}/approve", headers=ppl.admin.h, json={"quotaBytes": 3 * GIB}).json()
    assert ok["status"] == "approved" and ok["grantedBytes"] == 3 * GIB
    assert c.get("/api/me", headers=ppl.member.h).json()["quotaBytes"] == 3 * GIB
    assert c.post(f"/api/admin/storage-requests/{r['id']}/approve", headers=ppl.admin.h, json={}).status_code == 409
    assert "your quota is now 3 GB" in ppl.member.cookie_client(c.app).get("/account").text
    # a second one, rejected
    r2 = ask(c, ppl.member, bytes=10 * GIB)
    assert c.post(f"/api/admin/storage-requests/{r2['id']}/approve", headers=ppl.admin.h,
                  json={"quotaBytes": -1}).status_code == 400
    no = c.post(f"/api/admin/storage-requests/{r2['id']}/reject", headers=ppl.admin.h, json={"note": "Too much"}).json()
    assert no["status"] == "rejected" and no["note"] == "Too much"
    assert c.get("/api/me", headers=ppl.member.h).json()["quotaBytes"] == 3 * GIB
    assert "Too much" in ppl.member.cookie_client(c.app).get("/account").text


def test_storage_request_form_on_the_account_page(make_client, mojang):
    c = make_client(whitelist_enforced=True, quota_bytes=1000)
    member = Who(c, mojang, "Member")
    c.app.state.store.whitelist_add(member.uuid, "Member", "manual", None)
    web = member.cookie_client(c.app)
    html = web.get("/account").text
    assert "data-storage-form" in html and "Need more space?" in html and '<details class="edit storage-more">' in html
    c.put("/api/files/big.litematic", content=b"\x1f\x8b" + b"x" * 850, headers=member.h)  # 85 %
    html = web.get("/account").text
    assert "Running out of space? Ask for more" in html and '<details class="edit storage-more" open>' in html
    # a quota an admin set replaces the default; null brings the default back
    admin = Who(c, mojang, ADMIN)
    r = c.patch(f"/api/admin/users/{member.uuid}", headers=admin.h, json={"quotaBytes": 5000})
    assert r.json()["quotaBytes"] == 5000 and r.json()["quotaOverride"] == 5000
    assert c.put("/api/files/b.litematic", content=b"\x1f\x8b" + b"x" * 2000, headers=member.h).status_code == 201
    r = c.patch("/api/admin/users/member", headers=admin.h, json={"quotaBytes": None})  # by name, any case
    assert r.json()["quotaBytes"] == 1000 and r.json()["quotaOverride"] is None
    assert c.patch(f"/api/admin/users/{member.uuid}", headers=admin.h, json={"quotaBytes": 0}).status_code == 400
    assert c.patch("/api/admin/users/nobody", headers=admin.h, json={"quotaBytes": 1}).status_code == 404


# ------------------------------------------------------------------ badges


def test_badges_in_payloads_and_pages(gated, ppl):
    c = gated
    upload(c, ppl.member.h, "b.litematic")
    post = make_post(c, ppl.member.h, "b.litematic", title="Badge house")
    r = c.put(f"/api/admin/users/{ppl.member.uuid}/badges/verified", headers=ppl.admin.h)
    assert r.status_code == 200 and r.json()["badges"] == ["verified"]
    r = c.put("/api/admin/users/MEMBER/badges/dev", headers=ppl.admin.h)  # by name
    assert r.json()["badges"] == ["verified", "dev"]
    c.put(f"/api/admin/users/{ppl.member.uuid}/badges/mod", headers=ppl.admin.h)
    expect = ["verified", "mod", "dev"]  # display order, not the order given
    assert c.get(f"/api/board/{post['id']}").json()["author"] == {"uuid": ppl.member.uuid, "name": "Member", "badges": expect,
                                                              "displayName": "Member"}
    assert c.get("/api/board").json()["posts"][0]["author"]["badges"] == expect
    assert c.get("/api/me", headers=ppl.member.h).json()["badges"] == expect
    board = TestClient(c.app).get("/board").text
    assert 'class="ubadge ub-verified" title="Verified"' in board and 'class="ubadge ub-dev" title="Dev">Dev<' in board
    assert 'class="ubadge ub-mod"' in TestClient(c.app).get(f"/board/{post['id']}").text
    assert 'class="ubadge ub-verified"' in ppl.member.cookie_client(c.app).get("/account").text
    r = c.delete(f"/api/admin/users/{ppl.member.uuid}/badges/mod", headers=ppl.admin.h)
    assert r.json()["badges"] == ["verified", "dev"]
    assert c.put(f"/api/admin/users/{ppl.member.uuid}/badges/king", headers=ppl.admin.h).status_code == 400
    assert c.put("/api/admin/users/Ghost/badges/dev", headers=ppl.admin.h).status_code == 404
    assert c.put(f"/api/admin/users/{ppl.member.uuid}/badges/dev", headers=ppl.member.h).status_code == 403
    html = ppl.admin.cookie_client(c.app).get("/admin?tab=badges").text
    assert "Member" in html and "Take Verified" in html and "Take Mod" not in html


# ------------------------------------------------------------------ reports


def report(c, who, status=201, **body):
    r = c.post("/api/reports", headers=who.h, json={"kind": "post", "reason": "spam", **body})
    assert r.status_code == status, r.text
    return r.json()


def test_reports(gated, ppl, mojang):
    c = gated
    reporter = Who(c, mojang, "Reporter")
    c.app.state.store.whitelist_add(reporter.uuid, "Reporter", "manual", None)
    upload(c, ppl.member.h, "r.litematic")
    post = make_post(c, ppl.member.h, "r.litematic", title="Copied castle")
    secret = make_post(c, ppl.member.h, "r.litematic", title="Secret", visibility="private")

    r = report(c, reporter, target=post["id"], reason="stolen", details="  It's Bob's build ")
    assert r["kind"] == "post" and r["target"] == {"id": post["id"], "label": "Copied castle", "uuid": ppl.member.uuid}
    assert r["details"] == "It's Bob's build" and r["status"] == "open" and "reporter" not in r
    assert report(c, reporter, status=409, target=post["id"])["error"].startswith("you already reported this")
    u = report(c, reporter, kind="user", target="member", reason="inappropriate")  # by name
    assert u["target"] == {"id": ppl.member.uuid, "label": "Member", "uuid": ppl.member.uuid}
    # validation
    assert report(c, reporter, status=400, target=post["id"], reason="ugly")["error"].startswith("reason must be one of")
    assert report(c, reporter, status=400, target=post["id"], reason="other")["error"] == "details is required"
    assert report(c, reporter, status=400, kind="comment", target=post["id"])["error"] == "kind must be post or user"
    assert report(c, ppl.member, status=400, target=post["id"])["error"] == "you can't report your own post"
    assert report(c, reporter, status=400, kind="user", target=reporter.uuid)["error"] == "you can't report yourself"
    assert report(c, reporter, status=404, target=secret["id"])["error"] == "no such post"  # can't see it
    assert report(c, reporter, status=404, kind="user", target="Ghost")["error"] == "no such user"
    assert TestClient(c.app).post("/api/reports", json={"kind": "post", "target": post["id"], "reason": "spam"},
                                  headers=CSRF).status_code == 401

    # the admin's view, with the reporter and the post's state
    listing = c.get("/api/admin/reports", headers=ppl.admin.h).json()
    assert listing["counts"] == {"open": 2}
    first = next(x for x in listing["reports"] if x["id"] == r["id"])
    assert first["reporter"] == {"uuid": reporter.uuid, "name": "Reporter", "displayName": "Reporter", "tag": None}
    assert first["post"] == {"visibility": "public", "hidden": False} and first["target"]["name"] == "Member"
    html = ppl.admin.cookie_client(c.app).get("/admin?tab=reports").text
    assert "Copied castle" in html and "Stolen / not their build" in html and f"/api/admin/posts/{post['id']}/hide" in html
    assert f'href="/admin?tab=users&amp;q={ppl.member.uuid}">Open user' in html

    # resolve one (after hiding the post), dismiss the other
    hid = c.post(f"/api/admin/posts/{post['id']}/hide", headers=ppl.admin.h, json={"hidden": True, "reason": "Not theirs"})
    assert hid.status_code == 200 and hid.json()["hidden"] is True and hid.json()["hiddenReason"] == "Not theirs"
    done = c.post(f"/api/admin/reports/{r['id']}/resolve", headers=ppl.admin.h, json={"note": "hidden"}).json()
    assert done["status"] == "resolved" and done["note"] == "hidden" and done["post"]["hidden"] is True
    assert c.post(f"/api/admin/reports/{r['id']}/dismiss", headers=ppl.admin.h, json={}).status_code == 409
    assert c.post(f"/api/admin/reports/{u['id']}/dismiss", headers=ppl.admin.h, json={}).json()["status"] == "dismissed"
    assert c.get("/api/admin/reports?status=all", headers=ppl.admin.h).json()["counts"] == {"resolved": 1, "dismissed": 1}
    assert c.get("/api/admin/reports?status=nope", headers=ppl.admin.h).status_code == 400
    # once closed, the same target can be reported again
    report(c, reporter, kind="user", target=ppl.member.uuid)


def test_hidden_posts(gated, ppl, mojang):
    c = gated
    upload(c, ppl.member.h, "h.litematic")
    post = make_post(c, ppl.member.h, "h.litematic", title="Hide me")
    c.post(f"/api/admin/posts/{post['id']}/hide", headers=ppl.admin.h, json={"hidden": True})
    anon = TestClient(c.app)
    assert anon.get("/api/board").json()["posts"] == []
    assert anon.get(f"/api/board/{post['id']}").status_code == 404
    assert anon.get(f"/api/board/{post['id']}/file").status_code == 404
    assert anon.get(f"/board/{post['id']}").status_code == 404
    assert c.get(f"/api/board/{post['id']}", headers=ppl.outsider.h).status_code == 404
    # the owner's visibility can't undo it
    c.patch(f"/api/board/{post['id']}", json={"visibility": "public"}, headers=ppl.member.h)
    assert anon.get(f"/api/board/{post['id']}").status_code == 404
    # the author and admins still see it, marked
    mine = c.get("/api/board?tab=mine", headers=ppl.member.h).json()["posts"]
    assert [p["id"] for p in mine] == [post["id"]] and mine[0]["hidden"] is True
    html = ppl.member.cookie_client(c.app).get(f"/board/{post['id']}").text
    assert "Hidden by an admin." in html and "Only you and admins" in html
    assert "Hidden</span>" in ppl.member.cookie_client(c.app).get("/board?tab=mine").text
    assert 'data-hide-post="%s" data-hidden="false"' % post["id"] in ppl.admin.cookie_client(c.app).get(f"/board/{post['id']}").text
    # unhide
    r = c.post(f"/api/admin/posts/{post['id']}/hide", headers=ppl.admin.h, json={"hidden": False})
    assert r.json()["hidden"] is False and r.json()["hiddenReason"] is None
    assert [p["id"] for p in anon.get("/api/board").json()["posts"]] == [post["id"]]
    assert c.post(f"/api/admin/posts/{post['id']}/hide", headers=ppl.admin.h, json={"hidden": "yes"}).status_code == 400
    assert c.post("/api/admin/posts/NoSuchPost/hide", headers=ppl.admin.h, json={"hidden": True}).status_code == 404


def test_report_rate_limit(gated, ppl, mojang):
    c = gated
    upload(c, ppl.member.h, "r.litematic")
    targets = []
    for i in range(REPORT_USER_LIMIT + 1):
        targets.append(make_post(c, ppl.member.h, "r.litematic", title=f"Post {i}")["id"])
    rep = Who(c, mojang, "Rep")
    c.app.state.store.whitelist_add(rep.uuid, "Rep", "manual", None)
    report(c, rep, status=400, target=targets[0], reason="nope")  # rejected ones don't count
    for t in targets[:REPORT_USER_LIMIT]:
        report(c, rep, target=t)
    assert report(c, rep, status=429, target=targets[-1])["error"] == "too many reports for now, try again in 15 minutes"
    c.app.state.store.db.execute("UPDATE login_failures SET at = at - 3600")
    report(c, rep, target=targets[-1])


# ------------------------------------------------------------------ the word filter


@pytest.mark.parametrize("text", [
    "fuck", "FUCKING farm", "sh1t build", "what a $hit", "f u c k this", "s.h.i.t", "fuuuuuck", "B1TCH",
    "you are a cunt", "kill yourself", "k y s", "Fück",
])
def test_filter_catches(text):
    assert textfilter.find_blocked(text) is not None


@pytest.mark.parametrize("text", [
    "Scunthorpe railway station", "a classic class assassin", "grape juice", "Penistone", "fire retardant",
    "niggardly", "Cockburnspath", "the shitake... no, shiitake farm", "Dickens' house", "Sussex cottage",
    "Hello world, my 1st 5x5 door", "spaced r e d s t o n e", "kill the wither yourself",
])
def test_filter_leaves_normal_words(text):
    assert textfilter.find_blocked(text) is None


def test_filter_on_user_text(gated, ppl, mojang):
    c = gated
    upload(c, ppl.member.h, "f.litematic")
    r = c.post("/api/board", headers=ppl.member.h, json={"path": "f.litematic", "title": "Sh1t house", "visibility": "public"})
    assert r.status_code == 400 and r.json() == {
        "error": "the title contains language that isn't allowed here; please rephrase it", "code": "filtered", "field": "title"}
    r = c.post("/api/board", headers=ppl.member.h,
               json={"path": "f.litematic", "title": "Nice", "description": "f u c k", "visibility": "public"})
    assert r.status_code == 400 and r.json()["field"] == "description"
    post = make_post(c, ppl.member.h, "f.litematic", title="Scunthorpe castle", description="A classic grape press")
    r = c.patch(f"/api/board/{post['id']}", headers=ppl.member.h, json={"title": "Bullshit"})
    assert r.status_code == 400 and r.json()["code"] == "filtered"
    assert apply_(c, ppl.outsider, status=400, community="you bitch")["field"] == "community"
    assert apply_(c, ppl.outsider, status=400, contact="fuckface#1")["field"] == "contact"
    assert ask(c, ppl.member, status=400, reason="for my shit")["field"] == "reason"
    assert report(c, ppl.outsider, status=403, target=post["id"])["code"] == "not_whitelisted"
    c.app.state.store.whitelist_add(ppl.outsider.uuid, "Outsider", "manual", None)
    assert report(c, ppl.outsider, status=400, target=post["id"], details="kys")["field"] == "details"
    # feedback stays unfiltered (bug reports may quote anything)
    assert c.post("/api/feedback", headers=ppl.member.h, json={"type": "bug", "message": "this shit crashes"}).status_code == 201


def test_filter_bypass_for_minecraft_names_and_admins(gated, ppl, mojang):
    c = gated
    # a Mojang-verified name is never held against a post: their own, or another player's
    shitlord = Who(c, mojang, "Shit_Happens")
    c.app.state.store.whitelist_add(shitlord.uuid, "Shit_Happens", "manual", None)
    upload(c, shitlord.h, "s.litematic")
    p = make_post(c, shitlord.h, "s.litematic", title="Shit_Happens's base", description="Built by shit_happens")
    assert p["title"] == "Shit_Happens's base"
    upload(c, ppl.member.h, "m.litematic")
    assert make_post(c, ppl.member.h, "m.litematic", title="Tribute to Shit_Happens")["title"] == "Tribute to Shit_Happens"
    # names on the whitelist count too, though they never logged in here
    c.app.state.store.whitelist_add("11111111-2222-3333-4444-555555555555", "FuckingCreeper", "seed", None)
    assert make_post(c, ppl.member.h, "m.litematic", title="Farm with FuckingCreeper")["title"] == "Farm with FuckingCreeper"
    # but the name doesn't cover other words
    make_post(c, shitlord.h, "s.litematic", title="Shit_Happens: shit house", status=400)
    # admins aren't filtered
    upload(c, ppl.admin.h, "a.litematic")
    assert make_post(c, ppl.admin.h, "a.litematic", title="Bullshit detector")["title"] == "Bullshit detector"


# ------------------------------------------------------------------ modded schematics


def test_uploads_refuse_modded_schematics(gated, ppl, data_dir):
    c = gated
    r = c.put("/api/files/mod.litematic", content=modded_schem(), headers=ppl.member.h)
    assert r.status_code == 400 and r.json()["code"] == "modded" and r.json()["namespaces"] == ["create"]
    assert "only vanilla Minecraft schematics" in r.json()["error"]
    r = c.put("/api/files/mob.litematic", content=modded_schem(block=False, entity=True), headers=ppl.member.h)
    assert r.status_code == 400 and r.json()["namespaces"] == ["alexsmobs"]
    assert c.get("/api/files", headers=ppl.member.h).json()["files"] == []
    assert not (data_dir / "users" / ppl.member.uuid / "files").exists() or \
        not any((data_dir / "users" / ppl.member.uuid / "files").iterdir())
    # vanilla is fine, in every format, and so is a file the server can't read (the game's business)
    for fmt in ("litematic", "nbt", "slopmatic"):
        upload(c, ppl.member.h, f"v.{fmt}", schem(fmt))
    upload(c, ppl.member.h, "opaque.litematic", gz(b"not nbt"))
    # a modded file that reached the cloud before 0.7.0 can't be posted to the board
    files = data_dir / "users" / ppl.member.uuid / "files"
    (files / "old.litematic").write_bytes(modded_schem())
    c.app.state.store.reconcile()
    r = c.post("/api/board", headers=ppl.member.h, json={"path": "old.litematic", "title": "Old", "visibility": "public"})
    assert r.status_code == 400 and r.json()["code"] == "modded"
    assert c.get("/api/board?tab=mine", headers=ppl.member.h).json()["posts"] == []


def test_namespaces():
    s = synthetic()
    assert convert.namespaces(s) == {"minecraft"}
    s.regions[0].palette.append(state(s.data_version, "stone"))  # no namespace: minecraft
    s.regions[0].palette.append(state(s.data_version, "Create:Cogwheel"))
    assert convert.namespaces(s) == {"minecraft", "create"}


# ------------------------------------------------------------------ whitelist admin


def test_whitelist_admin(gated, ppl, mojang, tmp_path):
    c, h = gated, ppl.admin.h
    store = c.app.state.store
    apply_(c, ppl.outsider)
    # add by name: a known account, which also approves their open application
    r = c.post("/api/admin/whitelist", headers=h, json={"player": "outsider"})
    assert r.status_code == 201 and r.json()["name"] == "Outsider" and r.json()["source"] == "manual" and r.json()["added"]
    assert c.get("/api/application", headers=ppl.outsider.h).json()["application"]["status"] == "approved"
    assert c.post("/api/admin/whitelist", headers=h, json={"player": ppl.outsider.uuid}).json()["added"] is False
    # an unknown name is looked up at Mojang
    uid = mojang.account("Newbie")
    r = c.post("/api/admin/whitelist", headers=h, json={"player": "Newbie"})
    assert r.status_code == 201 and r.json()["uuid"] == f"{uid[:8]}-{uid[8:12]}-{uid[12:16]}-{uid[16:20]}-{uid[20:]}"
    assert c.post("/api/admin/whitelist", headers=h, json={"player": "NoSuchPlayer"}).status_code == 400
    # an unknown uuid needs a name
    assert c.post("/api/admin/whitelist", headers=h, json={"player": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"}).status_code == 400
    r = c.post("/api/admin/whitelist", headers=h, json={"player": "AAAAAAAABBBBCCCCDDDDEEEEEEEEEEEE", "name": "Ghost"})
    assert r.status_code == 201 and r.json()["uuid"] == "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    assert c.post("/api/admin/whitelist", headers=h, json={"player": "bad name!"}).status_code == 400
    # remove one
    assert c.delete(f"/api/admin/whitelist/{ppl.outsider.uuid}", headers=h).status_code == 204
    assert c.delete(f"/api/admin/whitelist/{ppl.outsider.uuid}", headers=h).status_code == 404
    not_whitelisted(c.get("/api/files", headers=ppl.outsider.h))

    # upload a whitelist.json: adds and renames, never removes
    listed = {e["uuid"]: e for e in c.get("/api/admin/whitelist", headers=h).json()["entries"]}
    assert sorted(e["name"] for e in listed.values()) == ["Ghost", "Member", "Newbie"]
    newbie = next(u for u, e in listed.items() if e["name"] == "Newbie")
    fresh = "12345678-1234-1234-1234-123456789abc"
    upload_body = json.dumps([
        {"uuid": ppl.member.uuid.replace("-", ""), "name": "MemberRenamed", "created": "ignored"},
        {"uuid": fresh, "name": "Fresh"},
        {"uuid": fresh, "name": "Fresh again"},  # a repeat is ignored
    ])
    r = c.post("/api/admin/whitelist/import", headers=h, content=upload_body)
    assert r.status_code == 200, r.text
    res = r.json()
    assert res["added"] == [{"uuid": fresh, "name": "Fresh"}]
    assert res["renamed"] == [{"uuid": ppl.member.uuid, "from": "Member", "to": "MemberRenamed"}] and res["unchanged"] == 0
    assert sorted(m["name"] for m in res["missing"]) == ["Ghost", "Newbie"] and res["count"] == 4
    assert store.whitelist_entry(fresh)["source"] == "upload"
    assert store.whitelist_entry(newbie) is not None  # kept
    r = c.post("/api/admin/whitelist/remove", headers=h, json={"uuids": [m["uuid"] for m in res["missing"]]})
    assert r.json() == {"removed": 2, "count": 2}
    for bad in ("not json", "{}", '[{"uuid": "x", "name": "A"}]', '[{"uuid": "%s"}]' % fresh):
        r = c.post("/api/admin/whitelist/import", headers=h, content=bad)
        assert r.status_code == 400, bad
    assert c.post("/api/admin/whitelist/remove", headers=h, json={"uuids": ["nope"]}).status_code == 400
    # the page
    html = ppl.admin.cookie_client(c.app).get("/admin?tab=whitelist").text
    assert "2 players on the whitelist" in html and "MemberRenamed" in html and "data-wl-import" in html


# ------------------------------------------------------------------ admin pages and admin-only access


ADMIN_API = [
    ("get", "/api/admin/applications", None), ("post", "/api/admin/applications/1/approve", {}),
    ("post", "/api/admin/applications/1/reject", {}), ("get", "/api/admin/storage-requests", None),
    ("post", "/api/admin/storage-requests/1/approve", {}), ("post", "/api/admin/storage-requests/1/reject", {}),
    ("get", "/api/admin/reports", None), ("post", "/api/admin/reports/1/resolve", {}),
    ("post", "/api/admin/reports/1/dismiss", {}), ("post", "/api/admin/posts/AAAAAAAAAA/hide", {"hidden": True}),
    ("get", "/api/admin/whitelist", None), ("post", "/api/admin/whitelist", {"player": "Member"}),
    ("delete", "/api/admin/whitelist/12345678-1234-1234-1234-123456789abc", None),
    ("post", "/api/admin/whitelist/remove", {"uuids": []}), ("post", "/api/admin/whitelist/import", []),
    ("get", "/api/admin/users", None), ("patch", "/api/admin/users/Member", {"quotaBytes": 1}),
    ("put", "/api/admin/users/Member/badges/dev", None), ("delete", "/api/admin/users/Member/badges/dev", None),
]


def call(c, method, url, body, headers=None):
    kw = {"headers": headers or {}}
    if body is not None:
        kw["json"] = body
    return c.request(method.upper(), url, **kw)


def test_admin_api_is_admin_only(gated, ppl):
    c = gated
    anon = TestClient(c.app)
    web_admin = ppl.admin.cookie_client(c.app)
    for method, url, body in ADMIN_API:
        assert call(anon, method, url, body).status_code == 401, url
        assert call(c, method, url, body, ppl.member.h).status_code == 403, url
        assert call(c, method, url, body, ppl.outsider.h).status_code == 403, url
        if method != "get":  # an admin's cookie still needs the CSRF header
            assert call(web_admin, method, url, body).status_code == 403, url
    # and admins get real answers
    assert call(web_admin, "get", "/api/admin/users", None).status_code == 200
    assert call(c, "get", "/api/admin/whitelist", None, ppl.admin.h).status_code == 200


def test_admin_pages_are_404_to_everyone_else(gated, ppl):
    c = gated
    unknown = TestClient(c.app).get("/no/such/page")
    assert unknown.status_code == 404 and "This page isn’t here." in unknown.text and "text/html" in unknown.headers["content-type"]
    assert TestClient(c.app).get("/api/nope").json() == {"error": "Not Found"}  # the API keeps JSON
    pages = ["/admin", "/admin?tab=storage", "/admin?tab=reports", "/admin?tab=whitelist", "/admin?tab=badges",
             "/admin?tab=users", "/admin/feedback"]
    for viewer in (TestClient(c.app), ppl.member.cookie_client(c.app), ppl.outsider.cookie_client(c.app)):
        same = viewer.get("/no/such/page").text
        for url in pages:
            r = viewer.get(url)
            assert r.status_code == 404 and r.text == same, url
    admin = ppl.admin.cookie_client(c.app)
    for url in pages:
        r = admin.get(url)
        assert r.status_code == 200, url
        assert 'src="/static/admin.js' in r.text or url == "/admin/feedback"
        assert "<script>" not in r.text  # CSP: no inline scripts
        assert r.headers["content-security-policy"].startswith("default-src 'none'; script-src 'self'")
    assert 'href="/admin">Admin dashboard</a>' in admin.get("/account").text


def test_admin_dashboard_tabs(gated, ppl):
    c = gated
    apply_(c, ppl.outsider, reason="<b>bold</b> farms")
    ask(c, ppl.member, bytes=5 * GIB, reason="maps")
    upload(c, ppl.member.h, "p.litematic")
    post = make_post(c, ppl.member.h, "p.litematic")
    c.app.state.store.whitelist_add(ppl.outsider.uuid, "Outsider", "manual", None)
    report(c, ppl.outsider, target=post["id"])
    c.post("/api/feedback", headers=ppl.member.h, json={"type": "bug", "message": "hi"})
    web = ppl.admin.cookie_client(c.app)
    html = web.get("/admin").text
    assert "&lt;b&gt;bold&lt;/b&gt; farms" in html and "<b>bold" not in html
    for label, n in (("Applications", 1), ("Storage", 1), ("Reports", 1), ("Feedback", 1), ("Whitelist", 2)):
        assert f'{label} <span class="count">{n}</span>' in html, label
    assert "/api/admin/applications/1/approve" in html
    storage = web.get("/admin?tab=storage").text
    assert "Asks for <strong>5 GB</strong>" in storage and 'value="5"' in storage
    fb = web.get("/admin/feedback").text
    assert 'aria-current="page">Feedback' in fb and 'href="/admin?tab=users"' in fb


def test_admin_users_list(gated, ppl):
    c = gated
    size = upload(c, ppl.member.h, "u.litematic")["size"]
    c.put(f"/api/admin/users/{ppl.member.uuid}/badges/verified", headers=ppl.admin.h)
    users = c.get("/api/admin/users", headers=ppl.admin.h).json()
    assert users["total"] == 3 and users["pages"] == 1
    m = c.get("/api/admin/users?q=MEMB", headers=ppl.admin.h).json()["users"]
    assert len(m) == 1
    m = m[0]
    assert m["uuid"] == ppl.member.uuid and m["name"] == "Member" and m["admin"] is False
    assert m["usedBytes"] == size and m["quotaBytes"] == GIB and m["quotaOverride"] is None
    assert m["badges"] == ["verified"] and m["whitelisted"] is True and m["whitelistSource"] == "manual"
    assert m["posts"] == 0 and abs(m["lastSeen"] - time.time() * 1000) < 60_000
    out = c.get(f"/api/admin/users?q={ppl.outsider.uuid.replace('-', '')}", headers=ppl.admin.h).json()["users"]
    assert [u["name"] for u in out] == ["Outsider"] and out[0]["whitelisted"] is False
    adm = c.get("/api/admin/users?q=sputnik", headers=ppl.admin.h).json()["users"][0]
    assert adm["admin"] is True and adm["quotaBytes"] is None
    html = ppl.admin.cookie_client(c.app).get(f"/admin?tab=users&q={ppl.member.uuid}").text
    assert "1 account matching" in html and "Member" in html and "Give Mod" in html and "Take Verified" in html


def test_last_seen_is_tracked(gated, ppl):
    store = gated.app.state.store
    store.db.execute("UPDATE users SET last_seen = 1 WHERE uuid=?", (ppl.member.uuid,))
    store._seen.clear()
    gated.get("/api/me", headers=ppl.member.h)
    (seen,) = store.db.execute("SELECT last_seen FROM users WHERE uuid=?", (ppl.member.uuid,)).fetchone()
    assert abs(seen - time.time()) < 60


# ------------------------------------------------------------------ migration 4 -> 5

# The schema exactly as 0.6.0 (user_version 4) leaves it: 0.5.0's plus the releases and feedback tables.
SCHEMA_V4_EXTRA = """
CREATE TABLE releases (
    id INTEGER PRIMARY KEY,
    version TEXT NOT NULL,
    mc_version TEXT NOT NULL,
    loader TEXT NOT NULL,
    filename TEXT NOT NULL UNIQUE,
    sha256 TEXT NOT NULL,
    size INTEGER NOT NULL,
    uploaded INTEGER NOT NULL,
    changelog TEXT NOT NULL DEFAULT '',
    hidden INTEGER NOT NULL DEFAULT 0,
    downloads INTEGER NOT NULL DEFAULT 0,
    UNIQUE (version, mc_version, loader)
);
CREATE TABLE feedback (
    id INTEGER PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('bug', 'suggestion', 'other')),
    message TEXT NOT NULL,
    mod_version TEXT,
    mc_version TEXT,
    contact TEXT,
    uuid TEXT,
    name TEXT,
    created INTEGER NOT NULL,
    resolved INTEGER
);
CREATE INDEX feedback_open ON feedback(resolved, created);
PRAGMA user_version = 4;
"""

PLAYER = "abcdefab-1234-1234-1234-123456789abc"
ADMIN_UUID = "12345678-1234-1234-1234-123456789abc"


def v4_data_dir(data_dir):
    """A 0.6.0 data directory: an admin, and a player with a file and a public post."""
    from test_v060 import SCHEMA_V3
    t = int(time.time())
    ms = t * 1000
    body = schem()
    files = data_dir / "users" / PLAYER / "files"
    files.mkdir(parents=True)
    (files / "house.litematic").write_bytes(body)
    (data_dir / "posts" / "HousePost1").mkdir(parents=True)
    (data_dir / "posts" / "HousePost1" / "house.litematic").write_bytes(body)
    digest = hashlib.sha256(body).hexdigest()
    db = sqlite3.connect(data_dir / "cloud.db")
    db.executescript(SCHEMA_V3.replace("PRAGMA user_version = 3;", "") + SCHEMA_V4_EXTRA)
    db.execute("INSERT INTO users VALUES(?,?,?,?,?,NULL,NULL)", (ADMIN_UUID, ADMIN, 1, t - 1000, t - 10))
    db.execute("INSERT INTO users VALUES(?,?,?,?,?,NULL,NULL)", (PLAYER, "Player", 0, t - 1000, t - 500))
    db.execute("INSERT INTO tokens VALUES(?,?,?,?,?,NULL)", (sha256_hex("admintok"), "api", ADMIN_UUID, t, t + 86400))
    db.execute("INSERT INTO tokens VALUES(?,?,?,?,?,NULL)", (sha256_hex("playertok"), "api", PLAYER, t, t + 86400))
    db.execute("INSERT INTO files VALUES(?,?,?,?,?,0,NULL,NULL,NULL,NULL)", (PLAYER, "house.litematic", len(body), digest, ms))
    db.execute("INSERT INTO posts VALUES(?,?,?,?,?,?,?,?,?,?,0,3,2,2,7,?,?,?)",
               ("HousePost1", PLAYER, "House", "", "public", "house.litematic", "house.litematic", "litematic",
                len(body), digest, ms, ms, "small"))
    db.execute("INSERT INTO feedback(kind, message, created) VALUES('bug', 'old', ?)", (ms,))
    db.commit()
    db.close()
    return body


def columns(db, table):
    return [r[1] for r in db.execute(f"PRAGMA table_info({table})")]


def test_migrates_a_v4_database(make_client, data_dir):
    body = v4_data_dir(data_dir)
    c = make_client(whitelist_enforced=True)
    db = c.app.state.store.db
    assert db.execute("PRAGMA user_version").fetchone()[0] == SCHEMA_VERSION
    users, posts = columns(db, "users"), columns(db, "posts")
    assert users[users.index("quota_bytes"):][:2] == ["quota_bytes", "last_seen"]
    assert posts[posts.index("hidden"):][:2] == ["hidden", "hidden_reason"]
    assert columns(db, "whitelist") == ["uuid", "name", "source", "added", "added_by"]
    for table in ("applications", "storage_requests", "badges", "reports"):
        assert columns(db, table), table
    row = db.execute("SELECT last_seen, last_login FROM users WHERE uuid=?", (PLAYER,)).fetchone()
    assert row["last_seen"] == row["last_login"]
    assert db.execute("SELECT COUNT(*) FROM whitelist").fetchone()[0] == 0  # no seed configured
    # nothing was lost; the player (not on the empty whitelist) just can't use it, the admin can
    not_whitelisted(c.get("/api/files", headers=bearer("playertok")))
    assert (data_dir / "users" / PLAYER / "files" / "house.litematic").read_bytes() == body
    post = c.get("/api/board/HousePost1").json()
    assert post["author"] == {"uuid": PLAYER, "name": "Player", "badges": [], "displayName": "Player"} and post["hidden"] is False
    assert c.get("/api/me", headers=bearer("admintok")).json()["quotaBytes"] is None
    assert c.get("/api/admin/whitelist", headers=bearer("admintok")).json()["count"] == 0
    assert c.get("/api/feedback", headers=bearer("admintok")).json()["open"] == 1
    # the new tables take data, and a restart migrates nothing twice
    c.post("/api/admin/whitelist", headers=bearer("admintok"), json={"player": PLAYER})
    assert c.get("/api/files", headers=bearer("playertok")).status_code == 200
    c2 = make_client(whitelist_enforced=True)
    assert c2.app.state.store.db.execute("PRAGMA user_version").fetchone()[0] == SCHEMA_VERSION
    assert c2.get("/api/files", headers=bearer("playertok")).json()["files"][0]["path"] == "house.litematic"


def test_migration_seeds_the_whitelist_once(make_client, data_dir, tmp_path):
    v4_data_dir(data_dir)
    seed = whitelist_file(tmp_path, [(PLAYER.replace("-", ""), "Player"), ("11111111-1111-1111-1111-111111111111", "Friend")])
    c = make_client(whitelist_enforced=True, whitelist_seed=seed)
    rows = c.app.state.store.db.execute("SELECT uuid, name, source, added_by FROM whitelist ORDER BY name").fetchall()
    assert [tuple(r) for r in rows] == [
        ("11111111-1111-1111-1111-111111111111", "Friend", "seed", None), (PLAYER, "Player", "seed", None)]
    assert c.get("/api/files", headers=bearer("playertok")).status_code == 200
    # once: a later start doesn't read the file again, even if it changed
    whitelist_file(tmp_path, [("22222222-2222-2222-2222-222222222222", "Late")])
    c.app.state.store.whitelist_remove([PLAYER])
    c2 = make_client(whitelist_enforced=True, whitelist_seed=seed)
    assert [r[0] for r in c2.app.state.store.db.execute("SELECT name FROM whitelist")] == ["Friend"]


def test_bad_seed_stops_the_migration(make_client, data_dir, tmp_path):
    v4_data_dir(data_dir)
    with pytest.raises(RuntimeError, match="cannot read the whitelist seed"):
        make_client(whitelist_seed=tmp_path / "missing.json")
    bad = tmp_path / "bad.json"
    bad.write_text('[{"uuid": "nope", "name": "X"}]')
    with pytest.raises(RuntimeError, match="entry 1 has no valid uuid"):
        make_client(whitelist_seed=bad)
    db = sqlite3.connect(data_dir / "cloud.db")
    assert db.execute("PRAGMA user_version").fetchone()[0] == 4
    assert db.execute("SELECT COUNT(*) FROM sqlite_master WHERE name = 'whitelist'").fetchone()[0] == 0
    db.close()
    good = whitelist_file(tmp_path, [(PLAYER, "Player")])
    assert make_client(whitelist_seed=good).app.state.store.whitelist_count() == 1


def test_migration_5_is_one_transaction(make_client, data_dir, tmp_path, monkeypatch):
    v4_data_dir(data_dir)
    seed = whitelist_file(tmp_path, [(PLAYER, "Player")])
    monkeypatch.setitem(MIGRATIONS, 5, MIGRATIONS[5] + "\nCREATE TABLE badges (x);")  # fails at the end
    with pytest.raises(sqlite3.OperationalError):
        make_client(whitelist_seed=seed)
    db = sqlite3.connect(data_dir / "cloud.db")
    assert db.execute("PRAGMA user_version").fetchone()[0] == 4
    assert db.execute("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('whitelist', 'reports')").fetchone()[0] == 0
    assert "quota_bytes" not in columns(db, "users")
    db.close()
    monkeypatch.undo()
    c = make_client(whitelist_seed=seed)
    assert c.app.state.store.db.execute("PRAGMA user_version").fetchone()[0] == SCHEMA_VERSION
    assert c.app.state.store.whitelist_count() == 1


@pytest.mark.skipif(not REAL_WHITELIST.is_file(), reason="no local Fruitbowl test whitelist")
def test_seed_from_the_real_whitelist_format(make_client):
    c = make_client(whitelist_seed=REAL_WHITELIST)
    expected = {e["uuid"] for e in json.loads(REAL_WHITELIST.read_text())}
    assert c.app.state.store.whitelist_count() == len(expected) > 0


def test_under_a_root_path(make_client, mojang):
    """As deployed: behind the proxy at /slopmatica, whether or not it strips the prefix."""
    from conftest import login
    c = make_client(whitelist_enforced=True, root_path="/slopmatica",
                    public_url="https://homeserver.example.ts.net/slopmatica")
    h = bearer(login(c, mojang, "Outsider", prefix="/slopmatica")["token"])
    for prefix in ("/slopmatica", ""):
        body = c.get(f"{prefix}/api/files", headers=h).json()
        assert body["code"] == "not_whitelisted" and body["apply"] == "https://homeserver.example.ts.net/slopmatica/apply"
        r = c.get(f"{prefix}/no/such/page")
        assert r.status_code == 404 and "This page isn’t here." in r.text and 'href="/slopmatica/static/style.css' in r.text
        assert c.get(f"{prefix}/api/nope").json() == {"error": "Not Found"}
        assert c.get(f"{prefix}/admin").status_code == 404
        assert c.get(f"{prefix}/apply").status_code == 200
