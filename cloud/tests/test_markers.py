"""Vanilla structure markers resolve when a structure file is read, as the mod does it: jigsaws become their
final_state, data structure blocks air or the loot chest they mark, other structure blocks structure voids."""

from slopmatica_cloud import convert
from slopmatica_cloud.nbt import Compound, Int, List, String


def block(x, state, nbt=None):
    b = Compound({"pos": List([Int(x), Int(0), Int(0)]), "state": Int(state)})
    if nbt:
        b["nbt"] = Compound({k: String(v) for k, v in nbt.items()})
    return b


def structure(*blocks):
    palette = [Compound({"Name": String(n)}) for n in ("minecraft:stone", "minecraft:jigsaw", "minecraft:structure_block", "minecraft:chest")]
    return Compound({"size": List([Int(len(blocks)), Int(1), Int(1)]), "palette": List(palette), "blocks": List(list(blocks)),
                     "entities": List([]), "DataVersion": Int(4000)})


def at(s, x):
    r = s.regions[0]
    return convert.state_string(r.palette[r.blocks[x]]), r.block_entities.get((x, 0, 0))


def test_markers_resolve():
    s = convert.read(structure(
        block(0, 1, {"final_state": "minecraft:oak_planks", "pool": "x"}),
        block(1, 1, {"final_state": ""}),
        block(2, 2, {"mode": "DATA", "metadata": "ChestEast"}),
        block(3, 2, {"mode": "SAVE"}),
        block(4, 2, {"mode": "DATA", "metadata": "Sentry"}),
        block(5, 0),
    ), "nbt", "piece", path="/data/minecraft/structure/woodland_mansion/1x1_a1.nbt")
    assert at(s, 0) == ("minecraft:oak_planks", None)
    assert at(s, 1) == ("minecraft:air", None)
    state, data = at(s, 2)
    assert state == "minecraft:chest[facing=east]" and data["LootTable"] == "minecraft:chests/woodland_mansion"
    assert at(s, 3)[0] == convert.VOID_NAME
    assert at(s, 4) == ("minecraft:air", None)  # a mob marker: left out
    assert at(s, 5)[0] == "minecraft:stone"


def test_chest_marker_fills_the_chest_below():
    root = structure(block(0, 3))
    root["size"] = List([Int(1), Int(2), Int(1)])
    root["blocks"].append(Compound({"pos": List([Int(0), Int(1), Int(0)]), "state": Int(2),
                                    "nbt": Compound({"mode": String("DATA"), "metadata": String("chest")})}))
    s = convert.read(root, "nbt", "top", path="/igloo/top.nbt")
    r = s.regions[0]
    assert r.block_entities[(0, 0, 0)]["LootTable"] == "minecraft:chests/igloo_chest"
    assert convert.state_name(r.palette[r.blocks[1]]) == "minecraft:air"
