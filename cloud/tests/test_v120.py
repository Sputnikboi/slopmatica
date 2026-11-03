"""0.12.0: groups (visibility, join and post policies, roles and permissions, members-only builds that never show
anywhere else), credited authors on posts, and the schema 8 -> 9 migration."""

import re
import sqlite3

import pytest
from fastapi.testclient import TestClient

from slopmatica_cloud import __version__
from slopmatica_cloud.app import GROUP_ADD_LIMIT, MIGRATIONS, SCHEMA_VERSION, semver_key

from conftest import PNG, bearer
from test_board import CSRF, Who, upload
from test_v070 import ADMIN, columns
from test_v100 import INLINE_SCRIPT, gated, v6_data_dir  # noqa: F401 (fixture)


@pytest.fixture
def ppl(gated, mojang):
    class P:
        admin = Who(gated, mojang, ADMIN)  # a site admin
        alice = Who(gated, mojang, "Alice")
        bob = Who(gated, mojang, "Bob")
        carol = Who(gated, mojang, "Carol")
        dave = Who(gated, mojang, "Dave")
        eve = Who(gated, mojang, "Eve")
        outsider = Who(gated, mojang, "Outsider")  # logged in, not on the whitelist
    for who in (P.alice, P.bob, P.carol, P.dave, P.eve):
        gated.app.state.store.whitelist_add(who.uuid, who.name, "manual", None)
    return P


def new_group(c, who, name="Builders", status=201, **settings):
    r = c.post("/api/groups", json={"name": name, **settings}, headers=who.h)
    assert r.status_code == status, r.text
    return r.json()


def post(c, who, title="Build", path="a.litematic", status=201, thumb=False, **extra):
    upload(c, who.h, path, thumb=thumb)
    r = c.post("/api/board", json={"path": path, "title": title, "visibility": "public", **extra}, headers=who.h)
    assert r.status_code == status, r.text
    return r.json()


def add(c, group, by, player, status=201):
    r = c.post(f"/api/groups/{group['id']}/members", json={"player": player}, headers=by.h)
    assert r.status_code == status, r.text
    return r.json()


def set_role(c, group, by, who, role, status=200):
    r = c.patch(f"/api/groups/{group['id']}/members/{who.uuid}", json={"role": role}, headers=by.h)
    assert r.status_code == status, r.text
    return r.json()


def roles(c, group, who=None) -> dict[str, str]:
    r = c.get(f"/api/groups/{group['id']}/members", headers=who.h if who else {})
    assert r.status_code == 200, r.text
    return {m["name"]: m["role"] for m in r.json()["members"]}


def board_ids(c, who=None, **q) -> list[str]:
    r = c.get("/api/board", headers=who.h if who else {}, params=q)
    assert r.status_code == 200, r.text
    return [p["id"] for p in r.json()["posts"]]


def group_ids(c, group, who=None, **q) -> list[str]:
    r = c.get(f"/api/groups/{group['id']}/posts", headers=who.h if who else {}, params=q)
    assert r.status_code == 200, r.text
    return [p["id"] for p in r.json()["posts"]]


def error(r, status: int) -> dict:
    assert r.status_code == status, r.text
    return r.json()


def test_version_bumped():
    assert semver_key(__version__) >= semver_key("0.12.0") and SCHEMA_VERSION >= 9


# ------------------------------------------------------------------ making groups, visibility, the directory


def test_create_groups_and_the_directory(gated, ppl):
    c, a = gated, ppl.alice
    g = new_group(c, a, "  Redstone   Guild ", description="Clocks\r\nand farms")
    assert g["name"] == "Redstone Guild" and g["slug"] == "redstone-guild" and g["description"] == "Clocks\nand farms"
    assert (g["visibility"], g["joinPolicy"], g["postPolicy"], g["postVisibility"]) == ("public", "open", "members", "any")
    assert g["memberPerms"] == {"post": True, "invite": False, "download": True}
    assert g["owner"]["uuid"] == a.uuid and g["role"] == "admin" and g["canPost"] is True and g["members"] == 1
    assert re.fullmatch(r"[A-Za-z0-9_-]{10}", g["id"]) and g["url"].endswith("/g/redstone-guild")
    assert new_group(c, ppl.admin, "Redstone guild!")["slug"] == "redstone-guild-2"
    assert new_group(c, ppl.admin, "Ærø Café")["slug"] == "r-cafe"  # accents fold away, letters without an ASCII form go
    assert new_group(c, ppl.admin, "日本")["slug"] == "group"  # never empty
    unlisted = new_group(c, a, "Quiet", visibility="unlisted")
    private = new_group(c, a, "Secret", visibility="private", postVisibility="group")
    # bad input
    for bad in ({"name": ""}, {"name": "x", "visibility": "hidden"}, {"name": "x", "joinPolicy": "maybe"},
                {"name": "x", "postPolicy": "all"}, {"name": "x", "postVisibility": "public"},
                {"name": "x", "memberPerms": {"fly": True}}, {"name": "x", "memberPerms": {"post": "yes"}}, {"name": "x" * 61}):
        assert "error" in error(c.post("/api/groups", json=bad, headers=a.h), 400), bad
    assert c.post("/api/groups", json={}, headers=a.h).status_code == 400
    # only Fruitbowl members make groups; the website needs the CSRF header
    assert error(c.post("/api/groups", json={"name": "Mine"}, headers=ppl.outsider.h), 403)["code"] == "not_whitelisted"
    web = a.cookie_client(c.app)
    assert web.post("/api/groups", json={"name": "Web"}).status_code == 403
    assert web.post("/api/groups", json={"name": "Web"}, headers=CSRF).status_code == 201
    # new groups are rate limited (site admins aren't)
    new_group(c, a, "Fifth")
    assert error(c.post("/api/groups", json={"name": "Sixth"}, headers=a.h), 429)
    # the directory: public groups only; the caller's own (any visibility) with their role
    anon = TestClient(c.app)
    listed = {x["id"]: x for x in anon.get("/api/groups").json()["groups"]}
    assert g["id"] in listed and unlisted["id"] not in listed and private["id"] not in listed
    assert all(x["role"] is None for x in listed.values())
    mine = {x["id"]: x["role"] for x in c.get("/api/groups", headers=a.h).json()["groups"]}
    assert mine[g["id"]] == mine[unlisted["id"]] == mine[private["id"]] == "admin"
    assert unlisted["id"] not in {x["id"] for x in c.get("/api/groups", headers=ppl.bob.h).json()["groups"]}
    html = anon.get("/groups").text
    assert "Redstone Guild" in html and "Quiet" not in html and "Secret" not in html and "New group" not in html
    page = web.get("/groups").text
    assert "Your groups" in page and "Secret" in page and "New group" in page and 'data-group-form="create"' in page


def test_owned_groups_limit(gated, ppl, monkeypatch):
    from slopmatica_cloud import app as app_module
    monkeypatch.setattr(app_module, "GROUPS_PER_USER", 2)
    new_group(gated, ppl.alice, "One")
    new_group(gated, ppl.alice, "Two")
    assert "limit" in error(gated.post("/api/groups", json={"name": "Three"}, headers=ppl.alice.h), 409)["error"]
    for n in range(3):  # site admins have no limit
        new_group(gated, ppl.admin, f"Admin's {n}")


def test_group_visibility(gated, ppl):
    c, a, b = gated, ppl.alice, ppl.bob
    unlisted = new_group(c, a, "Quiet", visibility="unlisted")
    private = new_group(c, a, "Secret", visibility="private")
    anon = TestClient(c.app)
    # unlisted: anyone with the link
    assert anon.get(f"/api/groups/{unlisted['id']}").status_code == 200
    assert anon.get("/g/quiet").status_code == 200 and anon.get("/g/quiet/members").status_code == 200
    # private: members (and site admins) only; everyone else gets a 404, as if there were none
    for who in (None, b, ppl.outsider):
        h = who.h if who else {}
        assert c.get(f"/api/groups/{private['id']}", headers=h).status_code == 404
        assert c.get(f"/api/groups/{private['id']}/posts", headers=h).status_code == 404
        assert c.get(f"/api/groups/{private['id']}/members", headers=h).status_code == 404
    assert c.post(f"/api/groups/{private['id']}/join", headers=b.h).status_code == 404
    assert anon.get("/g/secret").status_code == 404 and b.cookie_client(c.app).get("/g/secret").status_code == 404
    assert c.get(f"/api/groups/{private['id']}", headers=ppl.admin.h).json()["role"] is None  # site admins see it all
    assert ppl.admin.cookie_client(c.app).get("/g/secret/settings").status_code == 200
    assert c.get("/api/groups/nonexistent").status_code == 404 and anon.get("/g/nope").status_code == 404
    add(c, private, a, "Bob")
    assert c.get(f"/api/groups/{private['id']}", headers=b.h).json()["role"] == "member"
    assert b.cookie_client(c.app).get("/g/secret").status_code == 200


# ------------------------------------------------------------------ joining


def test_open_groups(gated, ppl):
    c, a, b = gated, ppl.alice, ppl.bob
    g = new_group(c, a)
    joined = c.post(f"/api/groups/{g['id']}/join", headers=b.h)
    assert joined.status_code == 200 and joined.json()["role"] == "member" and joined.json()["members"] == 2
    assert error(c.post(f"/api/groups/{g['id']}/join", headers=b.h), 409)
    assert error(c.post(f"/api/groups/{g['id']}/requests", json={}, headers=ppl.carol.h), 409)["code"] == "open"
    assert c.post(f"/api/groups/{g['id']}/join", headers=ppl.outsider.h).status_code == 403  # not whitelisted
    assert TestClient(c.app).post(f"/api/groups/{g['id']}/join").status_code == 401
    assert c.post(f"/api/groups/{g['id']}/leave", headers=b.h).status_code == 204
    assert error(c.post(f"/api/groups/{g['id']}/leave", headers=b.h), 409)
    assert roles(c, g) == {"Alice": "admin"}


def test_groups_by_request(gated, ppl):
    c, a, b, d = gated, ppl.alice, ppl.bob, ppl.dave
    g = new_group(c, a, joinPolicy="request")
    assert error(c.post(f"/api/groups/{g['id']}/join", headers=b.h), 409)["code"] == "request_required"
    r = c.post(f"/api/groups/{g['id']}/requests", json={"message": "I build clocks"}, headers=b.h)
    assert r.status_code == 201 and r.json()["requested"] is True and r.json()["role"] is None
    assert error(c.post(f"/api/groups/{g['id']}/requests", json={}, headers=b.h), 409)
    assert c.post(f"/api/groups/{g['id']}/requests", json={}, headers=d.h).status_code == 201
    assert c.post(f"/api/groups/{g['id']}/requests", json={}, headers=ppl.eve.h).status_code == 201
    assert c.delete(f"/api/groups/{g['id']}/requests", headers=ppl.eve.h).status_code == 204  # withdrawn
    # the requests are for the group's admins and moderators
    pending = c.get(f"/api/groups/{g['id']}/requests", headers=a.h).json()["requests"]
    assert [(p["name"], p["message"]) for p in pending] == [("Bob", "I build clocks"), ("Dave", "")]
    assert "requests" not in c.get(f"/api/groups/{g['id']}/members", headers=b.h).json()
    assert c.get(f"/api/groups/{g['id']}/requests", headers=b.h).status_code == 403
    assert c.post(f"/api/groups/{g['id']}/requests/{d.uuid}/approve", headers=b.h).status_code == 403
    # approve one, deny the other
    assert c.post(f"/api/groups/{g['id']}/requests/{b.uuid}/approve", headers=a.h).status_code == 200
    assert c.post(f"/api/groups/{g['id']}/requests/{d.uuid}/deny", headers=a.h).status_code == 200
    assert c.post(f"/api/groups/{g['id']}/requests/{d.uuid}/deny", headers=a.h).status_code == 404
    assert roles(c, g) == {"Alice": "admin", "Bob": "member"}
    assert c.get(f"/api/groups/{g['id']}/requests", headers=a.h).json()["requests"] == []
    # a moderator approves too
    set_role(c, g, a, b, "moderator")
    c.post(f"/api/groups/{g['id']}/requests", json={}, headers=d.h)
    assert c.post(f"/api/groups/{g['id']}/requests/{d.uuid}/approve", headers=b.h).status_code == 200
    assert roles(c, g)["Dave"] == "member"
    # the group page offers the right button
    assert 'data-group-act="request"' in ppl.carol.cookie_client(c.app).get(f"/g/{g['slug']}").text
    assert 'data-group-act="leave"' in d.cookie_client(c.app).get(f"/g/{g['slug']}").text


def test_invite_only_groups_and_adding_people(gated, ppl, mojang):
    c, a, b, d, e = gated, ppl.alice, ppl.bob, ppl.dave, ppl.eve
    g = new_group(c, a, joinPolicy="invite")
    assert error(c.post(f"/api/groups/{g['id']}/join", headers=b.h), 403)["code"] == "invite_only"
    assert error(c.post(f"/api/groups/{g['id']}/requests", json={}, headers=b.h), 403)["code"] == "invite_only"
    # admins add people by Minecraft name, looked up through Mojang (even players without an account here yet)
    mojang.account("Ghost")
    added = add(c, g, a, "bob")
    assert {m["name"] for m in added["members"]} == {"Alice", "Bob"}
    ghost = next(m for m in add(c, g, a, "Ghost")["members"] if m["name"] == "Ghost")
    assert ghost["hasAccount"] is False and ghost["role"] == "member"
    assert error(c.post(f"/api/groups/{g['id']}/members", json={"player": "Bob"}, headers=a.h), 409)
    assert "unknown" in error(c.post(f"/api/groups/{g['id']}/members", json={"player": "Nobody99"}, headers=a.h), 400)["error"]
    assert error(c.post(f"/api/groups/{g['id']}/members", json={"player": "bad name!"}, headers=a.h), 400)
    # plain members can't add anyone, unless the group or their own permissions let them invite
    assert c.post(f"/api/groups/{g['id']}/members", json={"player": "Carol"}, headers=b.h).status_code == 403
    r = c.patch(f"/api/groups/{g['id']}/members/{b.uuid}", json={"perms": {"invite": True}}, headers=a.h)
    assert next(m for m in r.json()["members"] if m["name"] == "Bob")["effective"]["invite"] is True
    add(c, g, b, "Carol")
    assert c.post(f"/api/groups/{g['id']}/members", json={"player": "Dave"}, headers=ppl.carol.h).status_code == 403
    assert c.patch(f"/api/groups/{g['id']}", json={"memberPerms": {"invite": True}}, headers=a.h).status_code == 200
    add(c, g, ppl.carol, "Dave")
    assert roles(c, g) == {"Alice": "admin", "Bob": "member", "Ghost": "member", "Carol": "member", "Dave": "member"}
    # adding by name is rate limited (Mojang lookups); site admins aren't
    for _ in range(GROUP_ADD_LIMIT):
        c.post(f"/api/groups/{g['id']}/members", json={"player": "Bob"}, headers=d.h)
    assert error(c.post(f"/api/groups/{g['id']}/members", json={"player": "Eve"}, headers=d.h), 429)
    assert c.post(f"/api/groups/{g['id']}/members", json={"player": "Eve"}, headers=ppl.admin.h).status_code == 201
    assert e.uuid in {m["uuid"] for m in c.get(f"/api/groups/{g['id']}/members").json()["members"]}


def test_bans(gated, ppl):
    c, a, b, d = gated, ppl.alice, ppl.bob, ppl.dave
    g = new_group(c, a)
    for who in (b, d):
        c.post(f"/api/groups/{g['id']}/join", headers=who.h)
    set_role(c, g, a, b, "moderator")
    r = c.post(f"/api/groups/{g['id']}/bans", json={"player": "Dave"}, headers=b.h)
    assert r.status_code == 201 and [x["name"] for x in r.json()["bans"]] == ["Dave"]
    assert "Dave" not in roles(c, g)
    assert error(c.post(f"/api/groups/{g['id']}/join", headers=d.h), 403)["code"] == "banned"
    assert error(c.post(f"/api/groups/{g['id']}/members", json={"player": "Dave"}, headers=a.h), 409)["code"] == "banned"
    assert "You can&rsquo;t join this group." in d.cookie_client(c.app).get(f"/g/{g['slug']}").text
    # bans are for admins and moderators to see and lift
    assert "bans" not in c.get(f"/api/groups/{g['id']}/members", headers=d.h).json()
    assert c.delete(f"/api/groups/{g['id']}/bans/{d.uuid}", headers=d.h).status_code == 403
    assert c.delete(f"/api/groups/{g['id']}/bans/{d.uuid}", headers=b.h).json()["bans"] == []
    assert c.delete(f"/api/groups/{g['id']}/bans/{d.uuid}", headers=b.h).status_code == 404
    assert c.post(f"/api/groups/{g['id']}/join", headers=d.h).status_code == 200
    # banning someone who isn't a member keeps them out, and takes their request
    req = new_group(c, a, "Asked", joinPolicy="request")
    c.post(f"/api/groups/{req['id']}/requests", json={}, headers=ppl.carol.h)
    c.post(f"/api/groups/{req['id']}/bans", json={"player": ppl.carol.uuid}, headers=a.h)
    assert c.get(f"/api/groups/{req['id']}/requests", headers=a.h).json()["requests"] == []
    assert error(c.post(f"/api/groups/{req['id']}/requests", json={}, headers=ppl.carol.h), 403)["code"] == "banned"
    assert error(c.post(f"/api/groups/{g['id']}/bans", json={"player": "Bob"}, headers=b.h), 400)  # not yourself


# ------------------------------------------------------------------ roles and permissions


def test_roles_and_permissions(gated, ppl):
    """Owner Alice; Bob admin; Carol and Eve moderators; Dave member."""
    c, a, b, ca, d, e = gated, ppl.alice, ppl.bob, ppl.carol, ppl.dave, ppl.eve
    g = new_group(c, a)
    gid = g["id"]
    for who in (b, ca, d, e):
        c.post(f"/api/groups/{gid}/join", headers=who.h)
    set_role(c, g, a, b, "admin")
    set_role(c, g, b, ca, "moderator")  # an admin who isn't the owner promotes too
    set_role(c, g, a, e, "moderator")
    assert roles(c, g) == {"Alice": "admin", "Bob": "admin", "Carol": "moderator", "Eve": "moderator", "Dave": "member"}
    assert set_role(c, g, a, d, "boss", status=400)

    # moderators: no settings, no roles, nothing on admins or other moderators
    assert c.patch(f"/api/groups/{gid}", json={"name": "Mine now"}, headers=ca.h).status_code == 403
    assert c.delete(f"/api/groups/{gid}", headers=ca.h).status_code == 403
    set_role(c, g, ca, d, "moderator", status=403)
    for target in (b, e, a):
        assert c.delete(f"/api/groups/{gid}/members/{target.uuid}", headers=ca.h).status_code == 403, target.name
        assert c.post(f"/api/groups/{gid}/bans", json={"player": target.name}, headers=ca.h).status_code == 403, target.name
    assert c.patch(f"/api/groups/{gid}/members/{e.uuid}", json={"perms": {"post": False}}, headers=ca.h).status_code == 409
    assert c.post(f"/api/groups/{gid}/transfer", json={"player": "Carol"}, headers=ca.h).status_code == 403
    assert ca.cookie_client(c.app).get(f"/g/{g['slug']}/settings").status_code == 404
    # ... but plain members are theirs: permissions, removing
    r = c.patch(f"/api/groups/{gid}/members/{d.uuid}", json={"perms": {"post": False, "download": False}}, headers=ca.h)
    dave = next(m for m in r.json()["members"] if m["name"] == "Dave")
    assert dave["perms"] == {"post": False, "download": False}
    assert dave["effective"] == {"post": False, "invite": False, "download": False}
    cleared = c.patch(f"/api/groups/{gid}/members/{d.uuid}", json={"perms": None}, headers=ca.h).json()
    assert next(m for m in cleared["members"] if m["name"] == "Dave")["perms"] is None
    assert c.patch(f"/api/groups/{gid}/members/{d.uuid}", json={"perms": {"fly": True}}, headers=ca.h).status_code == 400
    assert c.patch(f"/api/groups/{gid}/members/{d.uuid}", json={}, headers=ca.h).status_code == 400
    assert c.delete(f"/api/groups/{gid}/members/{d.uuid}", headers=ca.h).status_code == 200
    assert "Dave" not in roles(c, g)

    # plain members: none of it
    c.post(f"/api/groups/{gid}/join", headers=d.h)
    for method, url, body in (("PATCH", f"/api/groups/{gid}", {"name": "x"}), ("DELETE", f"/api/groups/{gid}", None),
                              ("DELETE", f"/api/groups/{gid}/members/{e.uuid}", None),
                              ("PATCH", f"/api/groups/{gid}/members/{e.uuid}", {"role": "member"}),
                              ("POST", f"/api/groups/{gid}/bans", {"player": "Eve"})):
        assert c.request(method, url, json=body, headers=d.h).status_code == 403, url
    assert c.delete(f"/api/groups/{gid}/members/{d.uuid}", headers=d.h).status_code == 400  # leaving is /leave

    # admins: settings, roles, anyone but the owner
    s = c.patch(f"/api/groups/{gid}", json={"name": "Builders 2", "slug": "b2", "joinPolicy": "request",
                                            "memberPerms": {"download": False}}, headers=b.h).json()
    assert (s["name"], s["slug"], s["joinPolicy"], s["memberPerms"]) == (
        "Builders 2", "b2", "request", {"post": True, "invite": False, "download": False})
    assert c.patch(f"/api/groups/{gid}", json={"slug": "Bad Slug"}, headers=b.h).status_code == 400
    other = new_group(c, d, "Taken")
    assert c.patch(f"/api/groups/{gid}", json={"slug": other["slug"]}, headers=b.h).status_code == 409
    assert c.delete(f"/api/groups/{gid}/members/{e.uuid}", headers=b.h).status_code == 200
    set_role(c, g, b, ca, "member")

    # the owner is always an admin: nobody removes, demotes or bans them, and they can't leave
    assert error(c.delete(f"/api/groups/{gid}/members/{a.uuid}", headers=b.h), 403)["code"] == "owner"
    assert set_role(c, g, b, a, "member", status=403)["code"] == "owner"
    assert set_role(c, g, a, a, "moderator", status=403)["code"] == "owner"
    assert error(c.post(f"/api/groups/{gid}/bans", json={"player": "Alice"}, headers=b.h), 403)["code"] == "owner"
    assert error(c.delete(f"/api/groups/{gid}/members/{a.uuid}", headers=ppl.admin.h), 403)["code"] == "owner"
    assert error(c.post(f"/api/groups/{gid}/leave", headers=a.h), 409)["code"] == "owner"
    assert error(c.post(f"/api/groups/{gid}/transfer", json={"player": "Carol"}, headers=b.h), 403)  # owner only

    # ... unless they hand it over: to a member, who becomes an admin and the owner
    assert c.post(f"/api/groups/{gid}/transfer", json={"player": "Nobody"}, headers=a.h).status_code == 404
    t = c.post(f"/api/groups/{gid}/transfer", json={"player": "carol"}, headers=a.h)
    assert t.status_code == 200 and t.json()["owner"]["uuid"] == ca.uuid
    assert roles(c, g)["Carol"] == "admin" and roles(c, g)["Alice"] == "admin"
    assert c.post(f"/api/groups/{gid}/leave", headers=ca.h).status_code == 409
    assert c.delete(f"/api/groups/{gid}/members/{a.uuid}", headers=ca.h).status_code == 200  # no longer the owner
    # members can always leave
    assert c.post(f"/api/groups/{gid}/leave", headers=d.h).status_code == 204


def test_site_admins_manage_every_group(gated, ppl):
    c, a, root = gated, ppl.alice, ppl.admin
    g = new_group(c, a, "Hidden away", visibility="private", postPolicy="closed")
    assert c.patch(f"/api/groups/{g['id']}", json={"description": "tidied"}, headers=root.h).status_code == 200
    add(c, g, root, "Bob")
    set_role(c, g, root, ppl.bob, "moderator")
    upload(c, root.h, "a.litematic")
    assert c.post("/api/board", json={"path": "a.litematic", "title": "Admin's", "visibility": "public", "group": g["id"]},
                  headers=root.h).status_code == 201  # closed: admins only, and site admins are a group's admins
    assert c.post(f"/api/groups/{g['id']}/transfer", json={"player": "Bob"}, headers=root.h).status_code == 200
    assert c.delete(f"/api/groups/{g['id']}", headers=root.h).status_code == 204


# ------------------------------------------------------------------ posting in groups


def test_post_policies(gated, ppl):
    c, a, b, ca, d = gated, ppl.alice, ppl.bob, ppl.carol, ppl.dave
    g = new_group(c, a)
    for who in (b, ca):
        c.post(f"/api/groups/{g['id']}/join", headers=who.h)
    set_role(c, g, a, ca, "moderator")
    # members: everyone in the group with the post permission
    p = post(c, b, "Bob's", group=g["id"])
    assert p["group"]["id"] == g["id"] and p["group"]["name"] == "Builders" and p["visibility"] == "public"
    assert post(c, d, "Not a member", group=g["id"], status=403)["code"] == "group_post"
    post(c, d, "No such group", group="missing123", status=404)
    c.patch(f"/api/groups/{g['id']}/members/{b.uuid}", json={"perms": {"post": False}}, headers=ca.h)
    post(c, b, "Not allowed", group=g["id"], status=403)
    c.patch(f"/api/groups/{g['id']}/members/{b.uuid}", json={"perms": None}, headers=ca.h)
    c.patch(f"/api/groups/{g['id']}", json={"memberPerms": {"post": False}}, headers=a.h)
    post(c, b, "Not allowed", group=g["id"], status=403)
    c.patch(f"/api/groups/{g['id']}/members/{b.uuid}", json={"perms": {"post": True}}, headers=ca.h)
    post(c, b, "Allowed again", group=g["id"])
    # moderators: no plain members, whatever their permissions
    c.patch(f"/api/groups/{g['id']}", json={"postPolicy": "moderators"}, headers=a.h)
    post(c, b, "Nope", group=g["id"], status=403)
    post(c, ca, "Mod's", group=g["id"])
    # closed: admins only
    c.patch(f"/api/groups/{g['id']}", json={"postPolicy": "closed"}, headers=a.h)
    post(c, ca, "Nope", group=g["id"], status=403)
    post(c, a, "Owner's", group=g["id"])
    # the form's Group choice lists only groups the player may post in
    assert c.get(f"/api/groups/{g['id']}", headers=ca.h).json()["canPost"] is False
    other = new_group(c, ca, "Carol's own")
    files = ca.cookie_client(c.app).get("/").text
    assert f'<option value="{other["id"]}"' in files and f'<option value="{g["id"]}"' not in files
    # putting an existing post in a group, or taking it out
    mine = post(c, d, "Dave's")
    c.post(f"/api/groups/{other['id']}/join", headers=d.h)
    moved = c.post(f"/api/groups/{other['id']}/posts", json={"post": mine["id"]}, headers=d.h)
    assert moved.status_code == 200 and moved.json()["group"]["id"] == other["id"]
    assert c.post(f"/api/groups/{other['id']}/posts", json={"post": p["id"]}, headers=b.h).status_code == 403  # not a member
    assert c.post(f"/api/groups/{other['id']}/posts", json={"post": mine["id"]}, headers=ca.h).status_code == 403  # not theirs
    assert c.patch(f"/api/board/{mine['id']}", json={"group": None}, headers=d.h).json()["group"] is None
    assert c.patch(f"/api/board/{mine['id']}", json={"group": g["id"]}, headers=d.h).status_code == 403  # not in it


def test_members_only_downloads(gated, ppl):
    c, a, b, ca = gated, ppl.alice, ppl.bob, ppl.carol
    g = new_group(c, a, postVisibility="group", memberPerms={"download": False})
    for who in (b, ca):
        add(c, g, a, who.name)
    set_role(c, g, a, ca, "moderator")
    p = post(c, a, "Look, don't take", group=g["id"], thumb=True)
    # a plain member sees it (picture, 3D view) but can't download, export or save it
    assert c.get(f"/api/board/{p['id']}", headers=b.h).status_code == 200
    assert c.get(f"/api/board/{p['id']}/thumbnail", headers=b.h).content == PNG
    assert c.get(f"/api/board/{p['id']}/model", headers=b.h).status_code == 200
    for r in (c.get(f"/api/board/{p['id']}/file", headers=b.h), c.get(f"/api/board/{p['id']}/export?format=nbt", headers=b.h),
              c.post(f"/api/board/{p['id']}/save", headers=b.h)):
        assert error(r, 403)["code"] == "no_download"
    html = b.cookie_client(c.app).get(f"/board/{p['id']}").text
    assert "can look at its builds here but not download them" in html and f"/api/board/{p['id']}/file" not in html
    # moderators, the uploader and site admins always may; a member's own permission may too
    for who in (ca, a, ppl.admin):
        assert c.get(f"/api/board/{p['id']}/file", headers=who.h).status_code == 200, who.name
    c.patch(f"/api/groups/{g['id']}/members/{b.uuid}", json={"perms": {"download": True}}, headers=ca.h)
    assert c.get(f"/api/board/{p['id']}/file", headers=b.h).status_code == 200
    assert c.post(f"/api/board/{p['id']}/save", headers=b.h).status_code == 201


# ------------------------------------------------------------------ members-only builds never leak


def test_members_only_posts_stay_in_the_group(gated, ppl):
    c, a, b, ca = gated, ppl.alice, ppl.bob, ppl.carol
    g = new_group(c, a, "Vault", postVisibility="group")
    add(c, g, a, "Bob")
    # posted "public", with Carol on its access list: the group decides, so it is stored private and only members see it
    p = post(c, a, "Secret base", thumb=True, group=g["id"], allowed=["Carol"], description="hush", mc="26.9")
    c.patch(f"/api/board/{p['id']}", json={"credit": "Ghostwriter"}, headers=a.h)
    assert p["visibility"] == "private" and p["group"]["postVisibility"] == "group"
    assert c.patch(f"/api/board/{p['id']}", json={"visibility": "public"}, headers=a.h).json()["visibility"] == "private"
    pid = p["id"]
    anon = TestClient(c.app)
    outsiders = [(anon, {}), (c, ca.h), (c, ppl.outsider.h), (c, ppl.dave.h)]
    for client, h in outsiders:
        # the board API: every tab, sort and search
        for q in ({}, {"sort": "new"}, {"sort": "hot"}, {"sort": "downloads"}, {"q": "secret"}, {"q": "ghostwriter"},
                  {"mc": "26.9"}, {"author": a.uuid}):
            assert pid not in [x["id"] for x in client.get("/api/board", params=q, headers=h).json()["posts"]], q
        if h:
            assert pid not in [x["id"] for x in client.get("/api/board", params={"tab": "shared"}, headers=h).json()["posts"]]
        # the post itself and everything of it
        for url in (f"/api/board/{pid}", f"/api/board/{pid}/file", f"/api/board/{pid}/thumbnail", f"/api/board/{pid}/export?format=nbt",
                    f"/api/board/{pid}/model", f"/api/board/{pid}/versions"):
            assert client.get(url, headers=h).status_code == 404, url
        for url in (f"/api/board/{pid}/save", f"/api/board/{pid}/favorite", f"/api/board/{pid}/vote"):
            assert client.post(url, json={"value": 1}, headers=h).status_code == 404, url
        # the group's own listing (the group itself is public)
        listed = client.get(f"/api/groups/{g['id']}/posts", headers=h).json()["posts"]
        assert pid not in [x["id"] for x in listed]
    assert c.post("/api/reports", json={"kind": "post", "target": pid, "reason": "spam"}, headers=ca.h).status_code == 404
    # the website: board, search, Minecraft version filter, profile, hover card, post page, share link
    for web in (anon, ca.cookie_client(c.app)):
        for url in ("/board", "/board?q=secret", "/board?sort=new", f"/u/{a.uuid}", f"/g/{g['slug']}"):
            html = web.get(url).text
            assert "Secret base" not in html and pid not in html, url
        assert "26.9" not in web.get("/board").text
        assert web.get(f"/board/{pid}").status_code == 404
        assert web.get(f"/b/{pid}").status_code == 404  # follows the redirect
        assert web.get(f"/api/users/{a.uuid}/card").json()["contributions"] == 0
    assert c.get("/api/favorites", headers=ca.h).json()["posts"] == []
    # members see it everywhere it belongs: the post, its files, the group's builds
    assert c.get(f"/api/board/{pid}", headers=b.h).json()["title"] == "Secret base"
    assert c.get(f"/api/board/{pid}/thumbnail", headers=b.h).content == PNG
    assert c.get(f"/api/board/{pid}/file", headers=b.h).status_code == 200
    assert group_ids(c, g, b) == [pid]
    bweb = b.cookie_client(c.app)
    assert "Secret base" in bweb.get(f"/g/{g['slug']}").text and bweb.get(f"/board/{pid}").status_code == 200
    assert "Group members only" in bweb.get(f"/board/{pid}").text
    assert pid not in board_ids(c, b) and pid not in board_ids(c, b, q="secret")  # still not on the board
    # site admins and the uploader see it too
    assert c.get(f"/api/board/{pid}", headers=ppl.admin.h).status_code == 200
    assert pid in board_ids(c, a, tab="mine")
    # leaving the group (or being removed) takes it away
    c.post(f"/api/groups/{g['id']}/leave", headers=b.h)
    assert c.get(f"/api/board/{pid}", headers=b.h).status_code == 404


def test_private_groups_and_their_posts(gated, ppl):
    """A private group whose builds follow their own visibility: its members also see its private posts, its public
    posts are on the board, and outsiders never learn the group's name."""
    c, a, b, ca = gated, ppl.alice, ppl.bob, ppl.carol
    g = new_group(c, a, "Hush", visibility="private")
    add(c, g, a, "Bob")
    pub = post(c, a, "Open build", group=g["id"])
    priv = post(c, a, "Closed build", group=g["id"], visibility="private")  # overrides the helper's public
    unl = post(c, a, "Quiet build", group=g["id"], visibility="unlisted")
    assert priv["visibility"] == "private" and unl["visibility"] == "unlisted"
    # members: the private one through the group, the unlisted one in the group's list
    assert c.get(f"/api/board/{priv['id']}", headers=b.h).status_code == 200
    assert set(group_ids(c, g, b)) == {pub["id"], priv["id"], unl["id"]}
    # outsiders: the public one is on the board, without the group; the private one isn't anywhere
    assert pub["id"] in board_ids(c) and priv["id"] not in board_ids(c, ca) and unl["id"] not in board_ids(c)
    assert c.get(f"/api/board/{priv['id']}", headers=ca.h).status_code == 404
    seen = c.get(f"/api/board/{pub['id']}", headers=ca.h).json()
    assert seen["group"] is None
    assert "Hush" not in ca.cookie_client(c.app).get(f"/board/{pub['id']}").text
    assert c.get(f"/api/board/{pub['id']}", headers=b.h).json()["group"]["name"] == "Hush"
    assert c.get(f"/api/board/{unl['id']}", headers=ca.h).status_code == 200  # unlisted: anyone with the link
    # in a public group with builds "as posted", a private post stays the poster's business
    open_group = new_group(c, a, "Open")
    c.post(f"/api/groups/{open_group['id']}/join", headers=b.h)
    mine = post(c, a, "Mine alone", group=open_group["id"], visibility="private")
    assert c.get(f"/api/board/{mine['id']}", headers=b.h).status_code == 404
    assert mine["id"] not in group_ids(c, open_group, b)
    # unlisted posts are listed to members only; public ones to anyone who sees the group
    shown = post(c, a, "Shown", group=open_group["id"])
    quiet = post(c, a, "Quiet", group=open_group["id"], visibility="unlisted")
    assert group_ids(c, open_group) == [shown["id"]] and set(group_ids(c, open_group, b)) == {shown["id"], quiet["id"]}


def test_switching_to_members_only_makes_posts_private(gated, ppl):
    c, a, b = gated, ppl.alice, ppl.bob
    g = new_group(c, a)
    p = post(c, a, "Was public", group=g["id"])
    assert p["id"] in board_ids(c)
    c.patch(f"/api/groups/{g['id']}", json={"postVisibility": "group"}, headers=a.h)
    assert p["id"] not in board_ids(c) and c.get(f"/api/board/{p['id']}", headers=b.h).status_code == 404
    c.patch(f"/api/groups/{g['id']}", json={"postVisibility": "any"}, headers=a.h)
    after = c.get(f"/api/board/{p['id']}", headers=a.h).json()
    assert after["visibility"] == "private" and p["id"] not in board_ids(c)  # nothing opens up by itself


# ------------------------------------------------------------------ group moderation of posts


def test_group_moderators_on_posts(gated, ppl):
    c, a, b, ca, d = gated, ppl.alice, ppl.bob, ppl.carol, ppl.dave
    g = new_group(c, a, postVisibility="group")
    for who in (b, ca, d):
        add(c, g, a, who.name)
    set_role(c, g, a, ca, "moderator")
    p = post(c, b, "Bob's tower", group=g["id"], tags=["redstone"])
    pid = p["id"]
    # moderators edit the listing: title, description, tags, credit
    r = c.patch(f"/api/board/{pid}", json={"title": "Tower", "description": "tidied", "tags": ["farm"], "credit": "Mojang"},
                headers=ca.h)
    assert r.status_code == 200 and (r.json()["title"], r.json()["tags"], r.json()["credit"]) == ("Tower", ["farm"], "Mojang")
    # ... nothing else, and never the uploader's file
    for body in ({"visibility": "public"}, {"links": []}, {"allowed": ["Dave"]}, {"group": new_group(c, ca, "Elsewhere")["id"]}):
        assert c.patch(f"/api/board/{pid}", json=body, headers=ca.h).status_code == 403, body
    assert c.delete(f"/api/board/{pid}", headers=ca.h).status_code == 403
    assert c.post(f"/api/board/{pid}/refresh", headers=ca.h).status_code == 403
    assert c.patch(f"/api/board/{pid}", json={"title": "Mine"}, headers=d.h).status_code == 403  # plain members can't
    # the post page gives them the tools
    html = ca.cookie_client(c.app).get(f"/board/{pid}").text
    assert "Group moderation" in html and "data-group-edit" in html and "groups.js" in html
    assert "Group moderation" not in d.cookie_client(c.app).get(f"/board/{pid}").text
    # hiding it in the group: off the group's list and away from plain members; the uploader and moderators keep it
    assert c.post(f"/api/groups/{g['id']}/posts/{pid}/hide", json={"hidden": True}, headers=d.h).status_code == 403
    assert c.post(f"/api/groups/{g['id']}/posts/{pid}/hide", json={"hidden": "yes"}, headers=ca.h).status_code == 400
    assert c.post(f"/api/groups/{g['id']}/posts/{pid}/hide", json={"hidden": True}, headers=ca.h).json()["hidden"] is True
    assert group_ids(c, g, d) == [] and c.get(f"/api/board/{pid}", headers=d.h).status_code == 404
    assert group_ids(c, g, ca) == [pid] and c.get(f"/api/board/{pid}", headers=b.h).json()["group"]["hidden"] is True
    assert c.get(f"/api/groups/{g['id']}", headers=d.h).json()["builds"] == 0
    c.post(f"/api/groups/{g['id']}/posts/{pid}/hide", json={"hidden": False}, headers=ca.h)
    assert group_ids(c, g, d) == [pid]
    # taking it out of the group: the post stays (private, so it opens up to nobody new)
    assert c.delete(f"/api/groups/{g['id']}/posts/{pid}", headers=d.h).status_code == 403
    assert c.delete(f"/api/groups/{g['id']}/posts/{pid}", headers=ca.h).status_code == 204
    assert c.delete(f"/api/groups/{g['id']}/posts/{pid}", headers=ca.h).status_code == 404
    out = c.get(f"/api/board/{pid}", headers=b.h).json()
    assert out["group"] is None and out["visibility"] == "private" and out["title"] == "Tower"
    assert c.get(f"/api/board/{pid}", headers=d.h).status_code == 404
    # an uploader takes their own post out too
    q = post(c, d, "Dave's", group=g["id"])
    assert c.delete(f"/api/groups/{g['id']}/posts/{q['id']}", headers=d.h).status_code == 204


# ------------------------------------------------------------------ credit


def test_credit(gated, ppl):
    c, a = gated, ppl.alice
    p = post(c, a, "Desert temple", credit="  Mojang  ")
    assert p["credit"] == "Mojang" and p["author"]["name"] == "Alice"
    assert c.get(f"/api/board/{p['id']}").json()["credit"] == "Mojang"
    anon = TestClient(c.app)
    page = anon.get(f"/board/{p['id']}").text
    assert re.search(r'by <strong>Mojang</strong></span> <span class="muted">&middot; uploaded by</span>', page)
    assert f'data-user="{a.uuid}"' in page
    assert re.search(r'>by Mojang</span> <span class="muted">&middot; uploaded by</span>', anon.get("/board").text)
    assert [x["id"] for x in anon.get("/api/board", params={"q": "mojang"}).json()["posts"]] == [p["id"]]
    # editing: the field is on the form, it can be cleared, and it is checked like other text
    own = a.cookie_client(c.app).get(f"/board/{p['id']}").text
    assert 'name="credit" value="Mojang"' in own
    assert c.patch(f"/api/board/{p['id']}", json={"credit": "x" * 61}, headers=a.h).status_code == 400
    assert c.patch(f"/api/board/{p['id']}", json={"credit": "a\nb"}, headers=a.h).json()["credit"] == "a b"
    assert c.patch(f"/api/board/{p['id']}", json={"credit": None}, headers=a.h).json()["credit"] is None
    assert "uploaded by" not in anon.get(f"/board/{p['id']}").text
    assert post(c, a, "Plain")["credit"] is None


# ------------------------------------------------------------------ deleting a group


def test_deleting_a_group_keeps_its_posts(gated, ppl):
    c, a, b, ca = gated, ppl.alice, ppl.bob, ppl.carol
    g = new_group(c, a, postVisibility="group")
    open_group = new_group(c, a, "Open")
    add(c, g, a, "Bob")
    add(c, g, a, "Carol")
    set_role(c, g, a, ca, "moderator")
    hidden = post(c, b, "Members only", group=g["id"])
    shown = post(c, a, "On the board", group=open_group["id"])
    assert c.delete(f"/api/groups/{g['id']}", headers=ca.h).status_code == 403  # moderators can't
    assert c.delete(f"/api/groups/{g['id']}", headers=b.h).status_code == 403
    assert c.delete(f"/api/groups/{g['id']}", headers=a.h).status_code == 204
    assert c.delete(f"/api/groups/{open_group['id']}", headers=a.h).status_code == 204
    assert c.get(f"/api/groups/{g['id']}", headers=a.h).status_code == 404
    db = c.app.state.store.db
    assert db.execute("SELECT COUNT(*) FROM group_members WHERE group_id=?", (g["id"],)).fetchone()[0] == 0
    # the posts stay, in no group; the members-only one stays private to its uploader
    kept = c.get(f"/api/board/{hidden['id']}", headers=b.h).json()
    assert kept["group"] is None and kept["visibility"] == "private"
    assert c.get(f"/api/board/{hidden['id']}", headers=ca.h).status_code == 404
    assert c.get(f"/api/board/{shown['id']}").json()["group"] is None and shown["id"] in board_ids(c)
    assert db.execute("SELECT COUNT(*) FROM posts WHERE group_id IS NOT NULL OR group_hidden IS NOT NULL").fetchone()[0] == 0


# ------------------------------------------------------------------ API shape for the mod


def test_group_posts_api_matches_the_board(gated, ppl):
    c, a, b = gated, ppl.alice, ppl.bob
    g = new_group(c, a)
    made = [post(c, a, f"Build {n}", group=g["id"]) for n in range(3)]
    c.post(f"/api/board/{made[0]['id']}/vote", json={"value": 1}, headers=b.h)
    res = c.get(f"/api/groups/{g['id']}/posts", params={"limit": 2, "sort": "new", "page": 1}, headers=b.h).json()
    board = c.get("/api/board", params={"limit": 2, "sort": "new", "page": 1}, headers=b.h).json()
    assert set(board) - set(res) == {"tab"} and res["group"] == g["id"]
    assert set(res["posts"][0]) == set(board["posts"][0])
    assert [x["id"] for x in res["posts"]] == [made[2]["id"], made[1]["id"]] and (res["total"], res["pages"]) == (3, 2)
    assert group_ids(c, g, sort="top")[0] == made[0]["id"]
    assert group_ids(c, g, q="build 1") == [made[1]["id"]]
    for bad in ({"limit": "0"}, {"sort": "random"}, {"page": "0"}):
        assert c.get(f"/api/groups/{g['id']}/posts", params=bad).status_code == 400, bad
    # a group as the mod sees it: the caller's role and what they may do
    mine = c.get(f"/api/groups/{g['id']}", headers=a.h).json()
    assert (mine["role"], mine["canPost"], mine["perms"], mine["builds"]) == (
        "admin", True, {"post": True, "invite": True, "download": True}, 3)
    theirs = c.get(f"/api/groups/{g['id']}", headers=b.h).json()
    assert (theirs["role"], theirs["canPost"], theirs["perms"], theirs["requested"]) == (None, False, None, False)
    assert "canPost" not in c.get(f"/api/groups/{g['id']}").json()


# ------------------------------------------------------------------ pages


def test_group_pages(gated, ppl):
    c, a, b, ca = gated, ppl.alice, ppl.bob, ppl.carol
    g = new_group(c, a, "Castle Crew", description="We build castles.", joinPolicy="request")
    add(c, g, a, "Carol")
    set_role(c, g, a, ca, "moderator")
    c.post(f"/api/groups/{g['id']}/requests", json={"message": "please"}, headers=b.h)
    p = post(c, a, "Keep", group=g["id"])
    anon = TestClient(c.app)
    page = anon.get("/g/castle-crew").text
    assert "Castle Crew" in page and "We build castles." in page and "By request" in page and "Keep" in page
    assert 'class="btn small ghost nav-groups' in page and 'href="/groups"' in page
    assert "Log in from the game to join." in page and "Settings</a>" not in page
    assert "Settings</a>" in a.cookie_client(c.app).get("/g/castle-crew").text
    assert "You asked to join." in b.cookie_client(c.app).get("/g/castle-crew").text
    assert anon.get("/g/castle-crew?sort=new&page=7").status_code == 200
    # members page: requests and member tools for moderators only
    mod = ca.cookie_client(c.app).get("/g/castle-crew/members").text
    assert "Asking to join" in mod and "please" in mod and 'data-member-add' in mod and "data-member-ban" in mod
    assert 'data-member-role' not in mod  # roles are for admins
    owner = a.cookie_client(c.app).get("/g/castle-crew/members").text
    assert 'data-member-role' in owner and 'data-member-act="transfer"' in owner
    plain = anon.get("/g/castle-crew/members").text
    assert "Asking to join" not in plain and "data-member-add" not in plain and f'data-user="{ca.uuid}"' in plain
    # settings: admins only (404 for everyone else, like the admin pages)
    assert anon.get("/g/castle-crew/settings").status_code == 404
    assert ca.cookie_client(c.app).get("/g/castle-crew/settings").status_code == 404
    settings = a.cookie_client(c.app).get("/g/castle-crew/settings").text
    assert 'data-group-form="settings"' in settings and 'value="castle-crew"' in settings
    # the post page links the group
    assert '<a href="/g/castle-crew">Castle Crew</a>' in anon.get(f"/board/{p['id']}").text
    # no inline scripts anywhere (the CSP forbids them)
    for url in ("/groups", "/g/castle-crew", "/g/castle-crew/members"):
        assert not INLINE_SCRIPT.search(anon.get(url).text), url
    assert not INLINE_SCRIPT.search(settings)


def test_group_pages_under_a_root_path(make_client, mojang):
    prefix = "/slopmatica"
    c = make_client(root_path=prefix, public_url="http://box.example.ts.net")
    alice = Who(c, mojang, "Alice")
    g = c.post(f"{prefix}/api/groups", json={"name": "Rooted"}, headers=alice.h).json()
    assert g["url"] == "http://box.example.ts.net/slopmatica/g/rooted"
    web = alice.cookie_client(c.app, prefix)
    for url in ("/groups", "/g/rooted", "/g/rooted/members", "/g/rooted/settings"):
        html = web.get(prefix + url).text
        for attr in re.findall(r'(?:href|src|action)="([^"]*)"', html):
            assert attr.startswith(prefix + "/") or attr.startswith(("https://", "http://box.")), (url, attr)


# ------------------------------------------------------------------ migration 8 -> 9


def v8_data_dir(data_dir):
    """A 0.11.x data directory: 0.9.0's (test_v100.v6_data_dir, with the post HousePost1) plus migrations 7 and 8."""
    v6_data_dir(data_dir)
    db = sqlite3.connect(data_dir / "cloud.db")
    db.executescript(MIGRATIONS[7] + MIGRATIONS[8] + "\nPRAGMA user_version = 8;")
    db.close()


def test_migrates_a_v8_database(make_client, data_dir):
    v8_data_dir(data_dir)
    c = make_client()
    db = c.app.state.store.db
    assert db.execute("PRAGMA user_version").fetchone()[0] == SCHEMA_VERSION >= 9
    posts = columns(db, "posts")
    assert posts[posts.index("group_id"):][:3] == ["group_id", "group_hidden", "credit"]
    assert columns(db, "groups")[:4] == ["id", "slug", "name", "description"]
    assert columns(db, "group_members") == ["group_id", "uuid", "name", "role", "joined", "perms"]
    assert columns(db, "group_requests") == ["group_id", "uuid", "message", "created"]
    assert columns(db, "group_bans") == ["group_id", "uuid", "name", "banned_by", "created"]
    # the old post is as it was, in no group and without a credit, and still on the board
    old = c.get("/api/board/HousePost1").json()
    assert old["group"] is None and old["credit"] is None and old["visibility"] == "public"
    assert [x["id"] for x in c.get("/api/board").json()["posts"]] == ["HousePost1"]
    # the new tables take data, and a restart migrates nothing twice
    player = bearer("playertok")
    g = c.post("/api/groups", json={"name": "After"}, headers=player).json()
    assert c.post(f"/api/groups/{g['id']}/posts", json={"post": "HousePost1"}, headers=player).status_code == 200
    c2 = make_client()
    assert c2.app.state.store.db.execute("PRAGMA user_version").fetchone()[0] == SCHEMA_VERSION
    assert c2.get("/api/board/HousePost1").json()["group"]["id"] == g["id"]


def test_migration_9_is_one_transaction(make_client, data_dir, monkeypatch):
    v8_data_dir(data_dir)
    monkeypatch.setitem(MIGRATIONS, 9, MIGRATIONS[9] + "\nCREATE TABLE groups (x);")  # fails at the end
    with pytest.raises(sqlite3.OperationalError):
        make_client()
    db = sqlite3.connect(data_dir / "cloud.db")
    assert db.execute("PRAGMA user_version").fetchone()[0] == 8
    assert "group_id" not in columns(db, "posts") and "credit" not in columns(db, "posts")
    assert db.execute("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 'group%'").fetchone()[0] == 0
    db.close()
    monkeypatch.undo()
    assert make_client().app.state.store.db.execute("PRAGMA user_version").fetchone()[0] == SCHEMA_VERSION


def test_site_admins_have_no_post_cap(make_client, mojang):
    from test_board import Who, make_post, upload
    from test_v070 import ADMIN
    c = make_client(max_posts_per_user=2)
    h = Who(c, mojang, ADMIN).h
    upload(c, h, "a.litematic")
    for _ in range(3):
        make_post(c, h)
