"""0.11.0: Quick Menu and action wheel layouts in usage reports."""
import json

from test_v080 import gated, ppl, report_body  # noqa: F401 (fixtures)

WHEEL = [["build_view", "easy_place", "", "", "", "", "", "toggle:ghostOpacity:50|100"],
         ["layer_up", "layer_down", "", "", "", "", "", ""]]


def test_quick_menu_reports_are_kept_and_summarised(gated, ppl):
    c = gated
    reports = [
        report_body("i1", quickStyle="WHEEL", wheelPages=WHEEL, wheelCloseAfterUse=False, wheelResetPage=True),
        report_body("i2", quickStyle="HOTBAR", quickMode="RELEASE", quickCloseAfterUse=True,
                    quickMenu=["build_view", "", "plan", "", "", "", "", "", ""]),
        report_body("i3", quickStyle="SIDEWAYS", quickMenu=["build_view"], wheelPages=[["x"] * 3]),  # all ignored
    ]
    for body in reports:
        assert c.post("/api/telemetry", json=body).status_code == 204
    rows = c.app.state.store.db.execute("SELECT install, quick FROM telemetry ORDER BY id").fetchall()
    quick = [json.loads(r["quick"]) for r in rows]
    assert quick[0] == {"quickStyle": "WHEEL", "wheelPages": WHEEL, "wheelCloseAfterUse": False, "wheelResetPage": True}
    assert quick[1]["quickMenu"][2] == "plan" and quick[1]["quickMode"] == "RELEASE"
    assert quick[2] == {}
    t = c.get("/api/admin/telemetry", headers=ppl.admin.h).json()
    assert {"name": "Wheel", "count": 1} in t["quickStyles"] and {"name": "Hotbar", "count": 1} in t["quickStyles"]
    assert {"name": "toggle:ghostOpacity:50|100", "count": 1} in t["wheelActions"]
    assert {"name": "plan", "count": 1} in t["hotbarActions"]
    assert t["wheelPages"] == [{"name": "2", "count": 1}]


def test_bad_action_ids_become_empty_slots(gated):
    body = report_body("i9", quickMenu=["build_view", "<script>", 5, "", "", "", "", "", ""])
    assert gated.post("/api/telemetry", json=body).status_code == 204
    quick = json.loads(gated.app.state.store.db.execute("SELECT quick FROM telemetry").fetchone()["quick"])
    assert quick["quickMenu"][:3] == ["build_view", "", ""]


def test_every_page_carries_the_mojang_disclaimer(gated):
    for url in ("/", "/board", "/releases", "/feedback"):
        html = gated.get(url).text
        assert "NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT." in html, url
