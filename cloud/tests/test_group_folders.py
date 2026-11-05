"""Folders inside a group: posts sit in a folder path, the group lists the folders at each level and the posts right
in it, a search looks through the folder and everything below it, and the author or the group's managers move posts."""

from fastapi.testclient import TestClient

from test_v120 import add, error, gated, new_group, post, ppl, set_role  # noqa: F401 (fixtures)


def listing(c, g, who=None, **q):
    r = c.get(f"/api/groups/{g['id']}/posts", headers=who.h if who else {}, params=q)
    assert r.status_code == 200, r.text
    return r.json()


def test_folders_list_by_level(gated, ppl):
    c, a = gated, ppl.alice
    g = new_group(c, a)
    top = post(c, a, "Loose", "top.litematic", group=g["id"])
    camp = post(c, a, "Camp whole", "camp.litematic", group=g["id"], folder=" Overworld / Abandoned Camp/ ", thumb=True)
    tent = post(c, a, "Tent", "tent.litematic", group=g["id"], folder="Overworld/Abandoned Camp/tent/forest")
    fort = post(c, a, "Fortress", "fort.litematic", group=g["id"], folder="Nether/Fortress")
    assert camp["group"]["folder"] == "Overworld/Abandoned Camp"

    res = listing(c, g)
    assert [p["id"] for p in res["posts"]] == [top["id"]] and res["folder"] == ""
    assert [(f["name"], f["count"]) for f in res["folders"]] == [("Nether", 1), ("Overworld", 2)]
    res = listing(c, g, folder="Overworld")
    assert res["posts"] == [] and res["folders"] == [
        {"name": "Abandoned Camp", "path": "Overworld/Abandoned Camp", "count": 2, "cover": camp["id"]}]
    res = listing(c, g, folder="/Overworld/Abandoned Camp/")
    assert [p["id"] for p in res["posts"]] == [camp["id"]] and [f["path"] for f in res["folders"]] == ["Overworld/Abandoned Camp/tent"]
    # a search looks below the folder too, and lists no folders
    res = listing(c, g, folder="Overworld", q="tent")
    assert [p["id"] for p in res["posts"]] == [tent["id"]] and res["folders"] == []
    assert listing(c, g, q="fortress")["posts"][0]["id"] == fort["id"]
    # LIKE wildcards in a name match only themselves
    assert listing(c, g, folder="Over%")["folders"] == [] and listing(c, g, folder="Over_orld")["folders"] == []

    # bad folders
    for bad in ("a/" * 9 + "b", "x" * 61, "a/../b", "tab\there"):
        error(c.post("/api/board", json={"path": "top.litematic", "title": "x", "visibility": "public", "group": g["id"],
                                         "folder": bad}, headers=a.h), 400)


def test_moving_posts_between_folders(gated, ppl):
    c, a, b, ca = gated, ppl.alice, ppl.bob, ppl.carol
    g = new_group(c, a)
    add(c, g, a, "Bob")
    add(c, g, a, "Carol")
    set_role(c, g, a, ca, "moderator")
    p = post(c, b, "Hut", group=g["id"])
    r = c.patch(f"/api/board/{p['id']}", json={"folder": "Overworld/Swamp Hut"}, headers=b.h)
    assert r.status_code == 200 and r.json()["group"]["folder"] == "Overworld/Swamp Hut"
    r = c.patch(f"/api/board/{p['id']}", json={"folder": "Overworld"}, headers=ca.h)  # a moderator files it
    assert r.status_code == 200 and r.json()["group"]["folder"] == "Overworld"
    error(c.patch(f"/api/board/{p['id']}", json={"folder": "Mine"}, headers=ppl.dave.h), 403)
    # leaving the group, or moving to another one, starts at the top
    r = c.patch(f"/api/board/{p['id']}", json={"group": None}, headers=b.h)
    assert r.json()["group"] is None
    error(c.patch(f"/api/board/{p['id']}", json={"folder": "Overworld"}, headers=b.h), 409)
    r = c.patch(f"/api/board/{p['id']}", json={"group": g["id"], "folder": "Back"}, headers=b.h)
    assert r.json()["group"]["folder"] == "Back"
    r = c.post(f"/api/groups/{g['id']}/posts", json={"post": post(c, b, "Other", "o.litematic")["id"]}, headers=b.h)
    assert r.json()["group"]["folder"] == ""


def test_folder_pages(gated, ppl):
    c, a = gated, ppl.alice
    g = new_group(c, a, "Vanilla")
    p = post(c, a, "Plains house", group=g["id"], folder="Overworld/Village/plains")
    anon = TestClient(c.app)
    page = anon.get("/g/vanilla").text
    assert "Plains house" not in page and 'href="/g/vanilla?folder=Overworld"' in page and "1 build<" in page
    page = anon.get("/g/vanilla?folder=Overworld/Village").text
    assert 'class="crumbs"' in page and 'href="/g/vanilla?folder=Overworld"' in page and "plains" in page
    assert "Plains house" in anon.get("/g/vanilla?folder=Overworld%2FVillage%2Fplains").text
    assert "Plains house" in anon.get("/g/vanilla?folder=Overworld&q=plains").text
    assert anon.get("/g/vanilla?folder=" + "x" * 99).status_code == 200  # nonsense: the top
    post_page = anon.get(f"/board/{p['id']}").text
    assert 'href="/g/vanilla?folder=Overworld/Village">Village</a>' in post_page
