import io
import itertools
from array import array
from pathlib import Path

import pytest

from slopmatica_cloud import convert, nbt
from slopmatica_cloud.convert import ConversionError, Entity, Region, Schematic
from slopmatica_cloud.nbt import (
    ByteArray,
    Compound,
    Double,
    Float,
    Int,
    IntArray,
    List,
    Long,
    String,
)

SAMPLES = Path(__file__).resolve().parents[2] / "run" / "schematics"
OLD_DV, NEW_DV = 3953, 5023  # {Name, Properties} vs {id, properties}


def state(dv, name, **props):
    n, p = ("id", "properties") if dv >= convert.NEW_STATE_KEYS else ("Name", "Properties")
    c = Compound({n: String(name)})
    if props:
        c[p] = Compound({k: String(v) for k, v in props.items()})
    return c


def chest_data():
    return Compound({
        "id": String("minecraft:chest"),
        "CustomName": String('{"text":"Loot"}'),
        "Items": List([Compound({"Slot": nbt.Byte(0), "id": String("minecraft:diamond"), "count": Int(3)})]),
    })


def entity_data(pos):
    return Compound({
        "id": String("minecraft:armor_stand"),
        "Pos": List([Double(v) for v in pos]),
        "Rotation": List([Float(90.0), Float(0.0)]),
        "Tags": List([String("a"), String("b")]),
        "Nested": List([List([Int(1), Int(2)]), List([Int(3)])]),
        "UUID": IntArray([1, 2, 3, 4]),
    })


def synthetic(dv=OLD_DV, two_regions=False) -> Schematic:
    """3x2x2 main region: air, stone, stairs (with properties), a named chest, plus an entity.
    With two_regions, a second region at x 5..6 (to be written with a negative size)."""
    air, stone = state(dv, "minecraft:air"), state(dv, "minecraft:stone")
    stairs = state(dv, "minecraft:oak_stairs", facing="north", half="bottom")
    chest = state(dv, "minecraft:chest", facing="east")
    # index = (y * sz + z) * sx + x
    blocks = array("I", [1, 1, 1,  1, 3, 2,   0, 2, 0,  0, 0, 1])
    main = Region("Main", (0, 0, 0), (3, 2, 2), [air, stone, stairs, chest], blocks,
                  {(1, 0, 1): chest_data()}, [Entity((1.5, 1.0, 0.5), entity_data((1.5, 1.0, 0.5)))])
    regions = [main]
    if two_regions:
        glass = state(dv, "minecraft:glass")
        regions.append(Region("Side", (5, 0, 0), (2, 2, 1), [air, glass], array("I", [1, 0, 0, 1]),
                              {}, [Entity((0.25, 0.0, 0.75), entity_data((0.25, 0.0, 0.75)))]))
    return Schematic("Synthetic", "Alice", dv, regions)


def through(s: Schematic, fmt: str, fallback="fallback") -> Schematic:
    """Write s as fmt (through real gzip bytes) and read it back."""
    data = nbt.dumps(convert.write(s, fmt))
    return convert.read(nbt.loads(data)[1], fmt, fallback)


def world_blocks(s: Schematic) -> dict:
    out = {}
    for r in s.regions:
        sx, sy, sz = r.size
        for i, idx in enumerate(r.blocks):
            st = r.palette[idx]
            if convert.block_kind(st) == convert.VOID:
                continue
            x, z, y = i % sx, (i // sx) % sz, i // (sx * sz)
            out[(r.pos[0] + x, r.pos[1] + y, r.pos[2] + z)] = nbt.canonical(st)
    return out


def world_block_entities(s: Schematic) -> dict:
    return {
        tuple(r.pos[a] + p[a] for a in range(3)): nbt.canonical(d)
        for r in s.regions for p, d in r.block_entities.items()
    }


def world_entities(s: Schematic) -> list:
    out = []
    for r in s.regions:
        for e in r.entities:
            pos = tuple(round(r.pos[a] + e.pos[a], 9) for a in range(3))
            data = Compound({k: v for k, v in e.data.items() if k != "Pos"})
            out.append((pos, nbt.canonical(data)))
    return sorted(out)


def assert_same_content(a: Schematic, b: Schematic):
    assert world_blocks(a) == world_blocks(b)
    assert world_block_entities(a) == world_block_entities(b)
    assert world_entities(a) == world_entities(b)
    assert a.data_version == b.data_version
    assert a.block_count() == b.block_count()


def source_in(fmt: str, dv: int) -> Schematic:
    """The synthetic schematic as read from a file of format fmt."""
    return through(synthetic(dv, two_regions=(fmt == "litematic")), fmt)


# ------------------------------------------------------------------ every ordered pair


@pytest.mark.parametrize("dv", [OLD_DV, NEW_DV])
@pytest.mark.parametrize("src,dst", list(itertools.permutations(convert.FORMATS, 2)))
def test_pair_round_trip(src, dst, dv):
    a = source_in(src, dv)
    b = through(a, dst)
    assert_same_content(a, b)
    assert b.author == "Alice"
    # .nbt has no name field: the file stem is used
    assert b.name == ("fallback" if "nbt" in (src, dst) else "Synthetic")
    # palette compounds are carried over verbatim (key style of the data version)
    key = "id" if dv >= convert.NEW_STATE_KEYS else "Name"
    for r in b.regions:
        assert all(key in st for st in r.palette)
    # and back again
    c = through(b, src)
    assert_same_content(a, c)
    if dst not in ("nbt", "schem"):  # .nbt and .schem merge regions into one box
        assert [(r.pos, r.size) for r in c.regions] == [(r.pos, r.size) for r in a.regions]


def test_entity_and_block_entity_types_survive():
    s = synthetic()
    for fmt in ("litematic", "slopmatic", "nbt"):
        back = through(s, fmt)
        e = back.regions[0].entities[0].data
        assert type(e["Pos"][0]) is Double and type(e["Rotation"][0]) is Float
        assert e["Nested"].elem_type == nbt.TAG_LIST and type(e["Nested"][0][0]) is Int
        assert type(e["UUID"]) is IntArray
        be = back.regions[0].block_entities[(1, 0, 1)]
        assert be["CustomName"] == '{"text":"Loot"}'
        assert type(be["Items"][0]["Slot"]) is nbt.Byte


def test_litematic_layout():
    root = convert.write(synthetic(two_regions=True), "litematic")
    assert root["Version"] == 7 and root["SubVersion"] == 1 and root["MinecraftDataVersion"] == OLD_DV
    meta = root["Metadata"]
    assert meta["RegionCount"] == 2
    assert meta["TotalBlocks"] == 8 + 2
    assert meta["TotalVolume"] == 12 + 4
    assert dict(meta["EnclosingSize"]) == {"x": 7, "y": 2, "z": 2}
    assert type(meta["TimeCreated"]) is Long
    main = root["Regions"]["Main"]
    assert convert.state_name(main["BlockStatePalette"][0]) == "minecraft:air"
    te = main["TileEntities"][0]
    assert (te["x"], te["y"], te["z"]) == (1, 0, 1) and te["id"] == "minecraft:chest"
    assert list(main["Entities"][0]["Pos"]) == [1.5, 1.0, 0.5]
    assert main["PendingBlockTicks"] == [] and main["PendingFluidTicks"] == []


def test_slopmatic_layout():
    root = convert.write(synthetic(two_regions=True), "slopmatic")
    assert root["Format"] == "slopmatica" and root["Version"] == 1 and root["DataVersion"] == OLD_DV
    assert list(root["Size"]) == [7, 2, 2] and type(root["Size"]) is IntArray
    assert root["BlockCount"] == 10 and type(root["BlockCount"]) is Long
    main = root["Regions"][0]
    assert list(main["Pos"]) == [0, 0, 0] and list(main["Size"]) == [3, 2, 2]
    assert list(main["BlockEntities"][0]["Pos"]) == [1, 0, 1]
    assert type(main["Blocks"]) is ByteArray


def test_structure_layout():
    s = synthetic(two_regions=True)
    s.regions[1].entities.clear()
    root = convert.write(s, "nbt")
    assert root["DataVersion"] == OLD_DV and [int(v) for v in root["size"]] == [7, 2, 2]
    blocks = nbt.loads(nbt.dumps(root))[1]["blocks"]
    # air is listed, the gap between the regions (x 3..4) is void and is not
    assert len(blocks) == 12 + 4
    listed = {tuple(b["pos"]) for b in blocks}
    assert (3, 0, 0) not in listed and (1, 1, 0) in listed
    chest = next(b for b in blocks if tuple(b["pos"]) == (1, 0, 1))
    assert chest["nbt"]["CustomName"] == '{"text":"Loot"}' and "x" not in chest["nbt"]
    ent = root["entities"][0]
    assert list(ent["pos"]) == [1.5, 1.0, 0.5] and list(ent["blockPos"]) == [1, 1, 0]
    assert list(ent["nbt"]["Pos"]) == [1.5, 1.0, 0.5]
    assert root["author"] == "Alice"


# ------------------------------------------------------------------ litematic specifics


def test_bit_packing_straddles_longs():
    # 17 palette entries -> 5 bits; entry 12 occupies bits 60..64 and so spans two longs
    assert convert.bits_for(17) == 5 and convert.bits_for(2) == 2 and convert.bits_for(1) == 2
    values = array("I", [0] * 30)
    values[12] = 0b11011
    longs = convert.pack_bits(values, 5)
    assert len(longs) == 3  # ceil(30 * 5 / 64)
    assert longs[0] == (0b1011 << 60) - 2**64  # signed in NBT
    assert longs[1] == 0b1
    assert convert.unpack_bits(longs, 5, 30) == values
    many = array("I", [(i * 7) % 17 for i in range(1000)])
    assert convert.unpack_bits(convert.pack_bits(many, 5), 5, 1000) == many
    for bits in (2, 3, 7, 8, 13, 16, 31):
        vals = array("I", [(i * 2654435761) % (1 << bits) for i in range(333)])
        assert convert.unpack_bits(convert.pack_bits(vals, bits), bits, 333) == vals


def test_litematic_negative_size_region():
    dv = OLD_DV
    air, stone, dirt = state(dv, "minecraft:air"), state(dv, "minecraft:stone"), state(dv, "minecraft:dirt")
    # Position (10, 0, 5), Size (-3, 1, -2): x 8..10, z 4..5, stored from the min corner
    blocks = array("I", [1, 0, 2,  0, 0, 1])
    region = Compound({
        "Position": Compound({"x": Int(10), "y": Int(0), "z": Int(5)}),
        "Size": Compound({"x": Int(-3), "y": Int(1), "z": Int(-2)}),
        "BlockStatePalette": List([air, stone, dirt]),
        "BlockStates": convert.pack_bits(blocks, 2),
        "TileEntities": List([Compound({"id": String("minecraft:sign"), "x": Int(2), "y": Int(0), "z": Int(0)})]),
        # Litematica measures entity positions from Position, not the min corner
        "Entities": List([entity_data((-0.5, 0.0, -0.5))]),
    })
    root = Compound({"MinecraftDataVersion": Int(dv), "Metadata": Compound({"Name": String("Neg")}),
                     "Regions": Compound({"R": region})})
    s = convert.read(root, "litematic", "x")
    r = s.regions[0]
    assert r.pos == (8, 0, 4) and r.size == (3, 1, 2)
    assert r.block_entities.keys() == {(2, 0, 0)}
    assert r.entities[0].pos == (1.5, 0.0, 0.5)
    w = world_blocks(s)
    assert w[(8, 0, 4)] == nbt.canonical(stone) and w[(10, 0, 4)] == nbt.canonical(dirt)
    assert w[(10, 0, 5)] == nbt.canonical(stone)
    # written back with a positive size from the min corner
    out = convert.write(s, "litematic")["Regions"]["R"]
    assert dict(out["Position"]) == {"x": 8, "y": 0, "z": 4} and dict(out["Size"]) == {"x": 3, "y": 1, "z": 2}
    assert_same_content(s, through(s, "litematic"))


def test_litematic_rejects_bad_data():
    s = synthetic()
    root = convert.write(s, "litematic")
    root["Regions"]["Main"]["BlockStates"] = nbt.LongArray()
    with pytest.raises(ConversionError, match="BlockStates"):
        convert.read(root, "litematic", "x")
    with pytest.raises(ConversionError):
        convert.read(Compound({"Regions": Compound()}), "litematic", "x")
    # index 7 with a 4-entry palette (2 bits can hold 0..3; use 3 bits worth of palette)
    root = convert.write(s, "litematic")
    reg = root["Regions"]["Main"]
    pal = list(reg["BlockStatePalette"]) + [state(OLD_DV, f"minecraft:b{i}") for i in range(2)]  # 6 -> 3 bits
    reg["BlockStatePalette"] = List(pal)
    reg["BlockStates"] = convert.pack_bits(array("I", [7] * 12), 3)
    with pytest.raises(ConversionError, match="palette"):
        convert.read(root, "litematic", "x")


# ------------------------------------------------------------------ .nbt specifics


def structure(dv, listed, size=(2, 1, 2)):
    stone, air = state(dv, "minecraft:stone"), state(dv, "minecraft:air")
    return Compound({
        "DataVersion": Int(dv),
        "size": List([Int(v) for v in size]),
        "palette": List([stone, air]),
        "blocks": List([Compound({"pos": List([Int(v) for v in p]), "state": Int(st)}) for p, st in listed]),
        "entities": List(),
    })


@pytest.mark.parametrize("dv", [OLD_DV, NEW_DV])
def test_structure_void_handling(dv):
    root = structure(dv, [((0, 0, 0), 0), ((1, 0, 0), 1)])
    s = convert.read(root, "nbt", "Tower")
    r = s.regions[0]
    assert s.name == "Tower" and r.name == "Tower"
    assert len(r.palette) == 3
    assert r.palette[2] == ({"id": "minecraft:structure_void"} if dv >= 5006 else {"Name": "minecraft:structure_void"})
    assert list(r.blocks) == [0, 1, 2, 2]
    assert s.block_count() == 1
    # voids are not listed when written back; air is
    back = nbt.loads(nbt.dumps(convert.write(s, "nbt")))[1]
    assert sorted((tuple(b["pos"]), b["state"]) for b in back["blocks"]) == [((0, 0, 0), 0), ((1, 0, 0), 1)]
    # through the other formats the void survives as structure_void
    for fmt in ("litematic", "slopmatic"):
        other = through(s, fmt)
        assert world_blocks(other) == world_blocks(s)
        again = nbt.loads(nbt.dumps(convert.write(other, "nbt")))[1]
        assert len(again["blocks"]) == 2


def test_structure_fully_listed_has_no_void_entry():
    s = convert.read(structure(OLD_DV, [((x, 0, z), 0) for x in range(2) for z in range(2)]), "nbt", "x")
    assert len(s.regions[0].palette) == 2


def test_structure_palettes_variant_and_bad_index():
    root = structure(OLD_DV, [((0, 0, 0), 1)])
    root["palettes"] = List([root.pop("palette"), List([state(OLD_DV, "minecraft:dirt"), state(OLD_DV, "minecraft:air")])])
    s = convert.read(root, "nbt", "x")
    assert convert.state_name(s.regions[0].palette[0]) == "minecraft:stone"
    with pytest.raises(ConversionError):
        convert.read(structure(OLD_DV, [((0, 0, 0), 5)]), "nbt", "x")


def test_overlapping_regions_merge_for_nbt():
    dv = OLD_DV
    air, stone, dirt = state(dv, "minecraft:air"), state(dv, "minecraft:stone"), state(dv, "minecraft:dirt")
    void = state(dv, "minecraft:structure_void")
    a = Region("A", (0, 0, 0), (3, 1, 1), [air, stone, void], array("I", [1, 1, 0]), {(0, 0, 0): chest_data()})
    b = Region("B", (1, 0, 0), (3, 1, 1), [dirt, air, void], array("I", [1, 0, 2]))
    s = Schematic("m", "", dv, [a, b])
    box = convert.merge_regions(s)
    names = [convert.state_name(box.palette[i]) if i != convert.UNSET else None for i in box.blocks]
    # x0: stone (A only); x1: A stone vs B air -> stone; x2: A air vs B dirt -> dirt; x3: B void -> nothing
    assert names[:3] == ["minecraft:stone", "minecraft:stone", "minecraft:dirt"]
    assert names[3] in (None, "minecraft:structure_void")
    assert box.block_entities.keys() == {(0, 0, 0)}
    # palette union by compound equality: one air, one void
    assert sum(convert.state_name(p) == "minecraft:air" for p in box.palette) == 1
    out = nbt.loads(nbt.dumps(convert.write(s, "nbt")))[1]
    assert len(out["blocks"]) == 3


# ------------------------------------------------------------------ .slopmatic specifics


def test_rle_encoding():
    blocks = array("I", [0] * 300 + [1, 1, 2] + [200] * 5)
    data = convert.rle_encode(blocks)
    assert data == bytes([0, 0xAC, 0x02, 1, 2, 2, 1, 0xC8, 0x01, 5])
    assert convert.rle_decode(data, 201, len(blocks)) == blocks
    small = array("I", [3, 3, 0, 1])
    assert convert.rle_encode(small) == bytes([3, 2, 0, 1, 1, 1])
    assert convert.rle_encode(array("I")) == b""


@pytest.mark.parametrize(
    "data,why",
    [
        (bytes([0, 5]), "covers"),  # 5 of 4
        (bytes([0, 3]), "covers"),  # 3 of 4
        (bytes([9, 4]), "palette"),  # index outside the palette
        (bytes([0, 2, 1]), "missing"),  # pair without a run length
        (bytes([0, 0x82]), "truncated"),  # varint cut off
        (bytes([0, 0, 1, 4]), "empty run"),
        (bytes([0, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01]), "overlong"),
    ],
)
def test_rle_rejects_corrupt_data(data, why):
    with pytest.raises(ConversionError, match=why):
        convert.rle_decode(data, 2, 4)


def test_slopmatic_rejects_corrupt_files():
    good = convert.write(synthetic(), "slopmatic")
    for mutate, why in [
        (lambda r: r.__setitem__("Version", Int(2)), "newer Slopmatica"),
        (lambda r: r.__setitem__("Format", String("other")), "not a .slopmatic"),
        (lambda r: r["Regions"][0].__setitem__("Blocks", ByteArray(b"\x00\x02")), "covers 2 of 12"),
        (lambda r: r["Regions"][0].__setitem__("Blocks", ByteArray(b"\x09\x0c")), "palette"),
        (lambda r: r["Regions"][0].pop("Blocks"), "no Blocks"),
        (lambda r: r["Regions"][0].__setitem__("Size", IntArray([3, 0, 2])), "size"),
        (lambda r: r["Regions"].clear(), "no regions"),
    ]:
        root = nbt.copy_tag(good)
        mutate(root)
        with pytest.raises(ConversionError, match=why):
            convert.read(root, "slopmatic", "x")


def test_volume_cap():
    s = synthetic(two_regions=True)
    for fmt in convert.FORMATS:
        root = nbt.loads(nbt.dumps(convert.write(s, fmt)))[1]
        with pytest.raises(convert.TooLarge):
            convert.read(root, fmt, "x", max_volume=10)
        convert.read(root, fmt, "x", max_volume=28 if fmt in ("nbt", "schem") else 16)  # one box: 7x2x2
    # merging into one box counts the enclosing volume (7x2x2 = 28 > 16 of regions)
    with pytest.raises(convert.TooLarge):
        convert.write(s, "nbt", max_volume=20)


def test_convert_file(tmp_path):
    src = tmp_path / "House.litematic"
    src.write_bytes(nbt.dumps(convert.write(synthetic(two_regions=True), "litematic")))
    out = io.BytesIO()
    convert.convert_file(src, "slopmatic", out)
    s = convert.read(nbt.loads(out.getvalue())[1], "slopmatic", "x")
    assert s.name == "Synthetic" and s.block_count() == 10
    nbt_src = tmp_path / "Tower.nbt"
    nbt_src.write_bytes(nbt.dumps(structure(OLD_DV, [((0, 0, 0), 0)])))
    out = io.BytesIO()
    convert.convert_file(nbt_src, "litematic", out)
    root = nbt.loads(out.getvalue())[1]
    assert root["Metadata"]["Name"] == "Tower" and list(root["Regions"]) == ["Tower"]


# ------------------------------------------------------------------ real files


@pytest.mark.skipif(not SAMPLES.is_dir(), reason="no sample schematics in run/schematics")
@pytest.mark.parametrize("path", sorted(SAMPLES.glob("*.litematic")) if SAMPLES.is_dir() else [], ids=lambda p: p.name)
def test_real_litematics(path):
    _, root = nbt.load(path)
    s = convert.read(root, "litematic", path.stem)
    expected = root.get("Metadata", Compound()).get("TotalBlocks")
    if expected is not None:
        assert s.block_count() == expected
    for fmt in ("nbt", "slopmatic"):
        other = through(s, fmt)
        assert world_blocks(other) == world_blocks(s)
        back = through(other, "litematic")
        assert_same_content(s, back)
        if expected is not None:
            assert back.block_count() == expected
            assert convert.write(back, "litematic")["Metadata"]["TotalBlocks"] == expected


# ------------------------------------------------------------------ the mod's settings (SlopmaticaMeta)


def raw_meta(root: Compound, fmt: str) -> Compound:
    """The SlopmaticaMeta compound where the mod looks for it."""
    holder = root["Schematic"]["Metadata"] if fmt == "schem" else root
    return holder[convert.META_KEY]


def mod_origin(s: Schematic, fmt: str) -> tuple:
    """The origin point measured from the build's min corner, the way the mod reads the file:
    .schem and .nbt from 0,0,0, .litematic and .slopmatic from where their regions are."""
    root = nbt.loads(nbt.dumps(convert.write(s, fmt)))[1]
    origin = tuple(raw_meta(root, fmt)["Origin"])
    lo = (0, 0, 0) if fmt in convert.ZERO_BASED else convert.read(root, fmt, "x").bounds()[0]
    return tuple(o - l for o, l in zip(origin, lo))


def with_meta(s: Schematic) -> Schematic:
    # Regions start at (-4, 2, 3): the origin sits one block in from the min corner along x.
    for r in s.regions:
        r.pos = (r.pos[0] - 4, r.pos[1] + 2, r.pos[2] + 3)
    s.meta = Compound({
        "Biome": String("minecraft:plains"),
        "Position": IntArray([100, 64, -20]),
        "Rotation": String("CLOCKWISE_90"),
        "MinVersion": String("1.21"),
        "Origin": IntArray([-3, 2, 3]),
    })
    return s


@pytest.mark.parametrize("src,dst", list(itertools.permutations(convert.FORMATS, 2)))
def test_meta_survives_conversion(src, dst):
    a = through(with_meta(synthetic(two_regions=(src == "litematic"))), src)
    b = through(a, dst)
    for s, fmt in ((a, src), (b, dst)):
        assert mod_origin(s, fmt) == (1, 0, 0)
        meta = raw_meta(convert.write(s, fmt), fmt)
        assert meta["Biome"] == "minecraft:plains"
        assert tuple(meta["Position"]) == (100, 64, -20)
        assert meta["Rotation"] == "CLOCKWISE_90"
        assert meta["MinVersion"] == "1.21"
    assert mod_origin(through(b, src), src) == (1, 0, 0)


def test_meta_in_a_mod_written_schem():
    # The mod writes .schem with a zero Offset and its Origin relative to that.
    s = synthetic()
    s.meta = Compound({"Origin": IntArray([2, 0, 1])})
    root = convert.write(s, "schem")
    assert tuple(raw_meta(root, "schem")["Origin"]) == (2, 0, 1)
    back = convert.read(root, "schem", "x")
    assert tuple(raw_meta(convert.write(back, "litematic"), "litematic")["Origin"]) == (2, 0, 1)


def test_no_meta_writes_none():
    for fmt in convert.FORMATS:
        root = convert.write(synthetic(), fmt)
        holder = root["Schematic"]["Metadata"] if fmt == "schem" else root
        assert convert.META_KEY not in holder
