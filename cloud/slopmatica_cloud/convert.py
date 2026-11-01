"""Conversion between the four schematic formats Slopmatica reads.

``.litematic`` (Litematica), ``.nbt`` (vanilla structure files), ``.schem`` (WorldEdit's, the
Sponge Schematic format, versions 1 to 3) and ``.slopmatic`` (Slopmatica's own) are all gzip
NBT. Each is read into one small in-memory model
(:class:`Schematic`: regions with a palette, an index per block, block entities and
entities) and written back out from it.

Block state compounds are never rewritten and the source's data version is kept, so
Minecraft's data fixers see every output exactly as they would have seen the input.
Entity and block entity NBT is passed through untouched apart from its coordinates.
"""

from __future__ import annotations

import operator
import re
import struct
import time
from array import array
from collections import Counter
from dataclasses import dataclass, field
from itertools import chain, compress, islice, repeat
from pathlib import Path
from typing import Any, BinaryIO

from . import nbt
from .nbt import (
    TAG_COMPOUND,
    TAG_DOUBLE,
    ByteArray,
    Compound,
    Double,
    Int,
    IntArray,
    List,
    Long,
    LongArray,
    RawList,
    String,
)

FORMATS = ("slopmatic", "nbt", "litematic", "schem")
DEFAULT_MAX_VOLUME = 64_000_000

AIR_NAMES = frozenset({"minecraft:air", "minecraft:cave_air", "minecraft:void_air"})
VOID_NAME = "minecraft:structure_void"
# Data version from which vanilla writes block states as {id, properties} instead of {Name, Properties}.
NEW_STATE_KEYS = 5006

SLOPMATIC_MAGIC = "slopmatica"
SLOPMATIC_VERSION = 1

# Sponge v1 predates DataVersion; WorldEdit reads such files as Minecraft 1.13.2.
SPONGE_V1_DATA_VERSION = 1631
SPONGE_VERSION = 3  # the version .schem exports are written as
SPONGE_MAX_SIDE = 0xFFFF  # Width, Height and Length are unsigned shorts

SOLID, AIR, VOID = 0, 1, 2
UNSET = 0xFFFFFFFF  # merged .nbt box: no region covers this position


class ConversionError(ValueError):
    """The file cannot be read as its format, or cannot be converted."""


class TooLarge(ConversionError):
    pass


# --------------------------------------------------------------------------- model

Vec3 = tuple[int, int, int]


@dataclass
class Entity:
    pos: tuple[float, float, float]  # relative to the region's min corner
    data: Compound  # full entity NBT; its Pos is rewritten on output


@dataclass
class Region:
    name: str
    pos: Vec3  # min corner
    size: Vec3  # all positive
    palette: list[Compound]
    blocks: array  # 'I', one palette index per block, x fastest, then z, then y
    block_entities: dict[Vec3, Compound] = field(default_factory=dict)  # region-relative, no coordinates
    entities: list[Entity] = field(default_factory=list)

    @property
    def volume(self) -> int:
        return self.size[0] * self.size[1] * self.size[2]

    def kinds(self) -> bytes:
        return palette_kinds(self.palette)

    def block_count(self) -> int:
        kinds = self.kinds()
        return sum(n for i, n in Counter(self.blocks).items() if kinds[i] == SOLID)


@dataclass
class Schematic:
    name: str
    author: str
    data_version: int
    regions: list[Region]
    description: str = ""
    created: int | None = None  # unix ms
    modified: int | None = None

    def bounds(self) -> tuple[Vec3, Vec3]:
        """(min corner, enclosing size)."""
        lo = tuple(min(r.pos[a] for r in self.regions) for a in range(3))
        hi = tuple(max(r.pos[a] + r.size[a] for r in self.regions) for a in range(3))
        return lo, tuple(h - l for h, l in zip(hi, lo))  # type: ignore[return-value]

    def block_count(self) -> int:
        return sum(r.block_count() for r in self.regions)

    def distinct_types(self) -> int:
        """How many different block ids (properties ignored, so every facing of a stair is one; no air or
        structure void) and entity ids the schematic uses: the board's complexity measure."""
        names: set[str] = set()
        for r in self.regions:
            kinds = r.kinds()
            for i in set(r.blocks):
                if i < len(r.palette) and kinds[i] == SOLID:
                    names.add(state_name(r.palette[i]))
            for e in r.entities:
                eid = e.data.get("id") if isinstance(e.data, Compound) else None
                if isinstance(eid, str) and eid:
                    names.add("entity:" + str(eid))
        return len(names)


def state_name(state: Any) -> str:
    if isinstance(state, Compound):
        name = state.get("Name", state.get("id"))
        if isinstance(name, str):
            return str(name)
    return ""


def namespaces(s: Schematic) -> set[str]:
    """The namespaces of every block, block entity and entity id in s: {"minecraft"} for a vanilla build.
    An id without a namespace counts as minecraft, as it does in the game."""
    found: set[str] = set()

    def add(name: Any) -> None:
        if isinstance(name, str) and name:
            found.add(name.split(":", 1)[0].lower() if ":" in name else "minecraft")

    for r in s.regions:
        for state in r.palette:
            add(state_name(state))
        for be in r.block_entities.values():
            add(be.get("id") if isinstance(be, Compound) else None)
        for e in r.entities:
            add(e.data.get("id") if isinstance(e.data, Compound) else None)
    return found


def block_kind(state: Any) -> int:
    name = state_name(state)
    return AIR if name in AIR_NAMES else VOID if name == VOID_NAME else SOLID


def palette_kinds(palette: list[Compound]) -> bytes:
    return bytes(block_kind(s) for s in palette)


def make_state(name: str, data_version: int) -> Compound:
    return Compound({("id" if data_version >= NEW_STATE_KEYS else "Name"): String(name)})


_STATE_STRING = re.compile(r"([^\[\]=,]+)(?:\[([^\[\]]*)\])?")


def parse_state(text: str, data_version: int) -> Compound:
    """A block state string as Sponge palettes key them, ``minecraft:oak_stairs[facing=east,half=bottom]``,
    as the compound the other formats store (keys in the style of data_version, properties in their order)."""
    m = _STATE_STRING.fullmatch(text.strip())
    if m is None:
        raise ConversionError(f"malformed block state {text[:100]!r}")
    state = make_state(m.group(1).strip(), data_version)
    if m.group(2) and m.group(2).strip():
        props = Compound()
        for part in m.group(2).split(","):
            key, eq, value = (t.strip() for t in part.partition("="))
            if not eq or not key or not value:
                raise ConversionError(f"malformed block state {text[:100]!r}")
            props[key] = String(value)
        state["properties" if data_version >= NEW_STATE_KEYS else "Properties"] = props
    return state


def state_string(state: Any) -> str:
    """The inverse of parse_state: ``name[key=value,...]``, or the name alone without properties."""
    name = state_name(state)
    if not name:
        raise ConversionError("a block state in the palette has no name")
    props = state.get("Properties", state.get("properties"))
    if isinstance(props, Compound) and props:
        return name + "[" + ",".join(f"{k}={v}" for k, v in props.items()) + "]"
    return name


# --------------------------------------------------------------------------- packing


def unpack_bits(longs: array, bits: int, count: int) -> array:
    """Litematica's BlockStates: `bits`-wide entries end to end over little-endian 64-bit
    words, LSB first; an entry may straddle two words."""
    raw = memoryview(_le_bytes(longs))
    mask = (1 << bits) - 1
    chunk = 8 * bits  # 64 entries
    shifts = range(0, 64 * bits, bits)
    masks = [mask] * 64
    rsh, and_ = operator.rshift, operator.and_
    out = array("I")
    for off in range(0, -(-count // 64) * chunk, chunk):
        v = int.from_bytes(raw[off : off + chunk], "little")
        out.extend(map(and_, map(rsh, repeat(v, 64), shifts), masks))
    del out[count:]
    return out


def pack_bits(values: array, bits: int) -> LongArray:
    n = len(values)
    shifts = range(0, 64 * bits, bits)
    lsh = operator.lshift
    out = bytearray()
    for off in range(0, n, 64):
        # the entries do not overlap, so their sum is their bitwise or
        out += sum(map(lsh, values[off : off + 64], shifts)).to_bytes(8 * bits, "little")
    del out[(n * bits + 63) // 64 * 8 :]
    longs = LongArray()
    longs.frombytes(bytes(out))
    if not nbt._LITTLE:
        longs.byteswap()
    return longs


def _le_bytes(longs: array) -> bytes:
    if nbt._LITTLE:
        return longs.tobytes()
    swapped = array(longs.typecode, longs)
    swapped.byteswap()
    return swapped.tobytes()


def bits_for(palette_size: int) -> int:
    return max(2, (palette_size - 1).bit_length())


def _put_varint(out: bytearray, v: int) -> None:
    while v > 0x7F:
        out.append((v & 0x7F) | 0x80)
        v >>= 7
    out.append(v)


def rle_encode(blocks: array) -> bytes:
    """.slopmatic Blocks: (palette index, run length) pairs as unsigned LEB128 varints."""
    n = len(blocks)
    if not n:
        return b""
    # Run starts are where a block differs from the one before. Every loop here runs in C
    # and the intermediate results are arrays, not lists of Python ints.
    starts = array("I", (0,))
    starts.extend(compress(range(1, n), map(operator.ne, islice(blocks, 1, None), blocks)))
    ends = starts[1:]
    ends.append(n)
    runs = array("I", map(operator.sub, ends, starts))
    values = array("I", map(blocks.__getitem__, starts))
    if max(values) < 0x80 and max(runs) < 0x80:  # every varint is one byte
        out = bytearray(2 * len(starts))
        out[0::2] = array("B", values)
        out[1::2] = array("B", runs)
        return bytes(out)
    out = bytearray()
    for v, r in zip(values, runs):
        _put_varint(out, v)
        _put_varint(out, r)
    return bytes(out)


_VARINT = re.compile(rb"[\x80-\xff]{0,9}[\x00-\x7f]")


def _varints(data: bytes, what: str = ".slopmatic") -> list[int] | bytes:
    if data.isascii():
        return data  # all single-byte varints; indexing bytes gives ints
    tokens = _VARINT.findall(data)
    if sum(map(len, tokens)) != len(data):
        raise ConversionError(f"corrupt {what}: truncated or overlong varint in block data")
    out = []
    for t in tokens:
        if len(t) == 1:
            out.append(t[0])
        else:
            out.append(sum((b & 0x7F) << (7 * k) for k, b in enumerate(t)))
    return out


# Byte tables for varint_array: the 7 value bits; the value bits of a continuation byte (else 0);
# 7 for a continuation byte (else 0); 1 for the last byte of a varint.
_LOW7 = bytes(b & 0x7F for b in range(256))
_CONT_LOW = bytes(b & 0x7F if b >= 0x80 else 0 for b in range(256))
_CONT_SHIFT = bytes(7 if b >= 0x80 else 0 for b in range(256))
_LAST = bytes(int(b < 0x80) for b in range(256))


def varint_array(data: bytes, what: str) -> array:
    """Unsigned LEB128 varints, one per block (Sponge's block data). One- and two-byte varints (palettes
    of up to 16384 states) are decoded without a Python loop: each final byte adds its value bits,
    shifted by 7 if a continuation byte precedes it, to that byte's value bits."""
    if data.isascii():
        return array("I", array("B", data))
    if data[-1] >= 0x80:
        raise ConversionError(f"corrupt {what}: truncated varint at the end of the block data")
    shift = b"\0" + data[:-1].translate(_CONT_SHIFT)
    if b"\x07\x07" in shift:  # some varint is three bytes or longer
        return array("I", _varints(data, what))
    values = map(operator.add, b"\0" + data[:-1].translate(_CONT_LOW), map(operator.lshift, data.translate(_LOW7), shift))
    return array("I", compress(values, data.translate(_LAST)))


def rle_decode(data: bytes, palette_size: int, volume: int) -> array:
    nums = _varints(data)
    if len(nums) % 2:
        raise ConversionError("corrupt .slopmatic: truncated block data (run length missing)")
    values, runs = nums[0::2], nums[1::2]
    if values and max(values) >= palette_size:
        raise ConversionError("corrupt .slopmatic: block index outside the palette")
    if runs and min(runs) <= 0:
        raise ConversionError("corrupt .slopmatic: empty run in block data")
    filled = sum(runs)
    if filled != volume:
        raise ConversionError(f"corrupt .slopmatic: block data covers {filled} of {volume} blocks")
    return array("I", chain.from_iterable(map(repeat, values, runs)))


# --------------------------------------------------------------------------- tag helpers


def _get(c: Any, key: str, cls: type | tuple[type, ...]) -> Any:
    v = c.get(key) if isinstance(c, Compound) else None
    return v if isinstance(v, cls) else None


def _str(c: Any, key: str, default: str = "") -> str:
    v = _get(c, key, String)
    return str(v) if v is not None else default


def _int(c: Any, key: str, default: int | None = 0) -> int | None:
    v = _get(c, key, (nbt.Byte, nbt.Short, Int, Long))
    return int(v) if v is not None else default


def _vec3i(tag: Any, what: str) -> Vec3:
    """Three ints from an IntArray, a List of ints, or a {x,y,z} compound."""
    if isinstance(tag, Compound):
        vals = [_int(tag, k, None) for k in "xyz"]
    elif isinstance(tag, (IntArray, List)) and len(tag) == 3:
        vals = [int(v) if isinstance(v, int) else None for v in tag]
    else:
        vals = [None]
    if len(vals) != 3 or any(v is None for v in vals):
        raise ConversionError(f"missing or malformed {what}")
    return tuple(vals)  # type: ignore[return-value]


def _vec3d(tag: Any) -> tuple[float, float, float] | None:
    if isinstance(tag, List) and len(tag) == 3 and all(isinstance(v, (int, float)) for v in tag):
        return (float(tag[0]), float(tag[1]), float(tag[2]))
    return None


def _compounds(c: Any, key: str) -> list[Compound]:
    v = _get(c, key, List)
    return [x for x in v if isinstance(x, Compound)] if v is not None else []


def _palette(tag: Any, what: str) -> list[Compound]:
    if not isinstance(tag, List) or not tag:
        raise ConversionError(f"{what} has no block palette")
    if tag.elem_type != TAG_COMPOUND:
        raise ConversionError(f"{what} has a malformed block palette")
    return list(tag)


def _without(c: Compound, keys: tuple[str, ...]) -> Compound:
    return Compound({k: v for k, v in c.items() if k not in keys})


def _with_pos(data: Compound, pos: tuple[float, float, float]) -> Compound:
    out = Compound(data)
    out["Pos"] = List([Double(v) for v in pos], TAG_DOUBLE)
    return out


def _ivec(v: Vec3) -> IntArray:
    return IntArray(v)


def _xyz(v: Vec3) -> Compound:
    return Compound({"x": Int(v[0]), "y": Int(v[1]), "z": Int(v[2])})


def _check_volume(size: Vec3, total: int, max_volume: int) -> int:
    if any(s <= 0 for s in size):
        raise ConversionError(f"invalid size {size[0]}x{size[1]}x{size[2]}")
    total += size[0] * size[1] * size[2]
    if total > max_volume:
        raise TooLarge(f"too large to convert: {total} blocks of volume (limit {max_volume})")
    return total


def _check_indices(blocks: array, palette_size: int, what: str) -> None:
    if blocks and max(blocks) >= palette_size:
        raise ConversionError(f"{what} has a block index outside its palette")


def _inside(p: Vec3, size: Vec3) -> bool:
    return 0 <= p[0] < size[0] and 0 <= p[1] < size[1] and 0 <= p[2] < size[2]


def _unique_names(regions: list[Region]) -> list[str]:
    seen: set[str] = set()
    names = []
    for r in regions:
        name, n = r.name or "Region", 2
        while name in seen:
            name, n = f"{r.name or 'Region'} ({n})", n + 1
        seen.add(name)
        names.append(name)
    return names


# --------------------------------------------------------------------------- readers


def read_litematic(root: Compound, fallback_name: str, max_volume: int = DEFAULT_MAX_VOLUME) -> Schematic:
    meta = _get(root, "Metadata", Compound) or Compound()
    name = _str(meta, "Name").strip() and _str(meta, "Name") or fallback_name
    regions_tag = _get(root, "Regions", Compound)
    if not regions_tag:
        raise ConversionError("not a .litematic file (no Regions)")
    regions = []
    total = 0
    for rname, tag in regions_tag.items():
        if not isinstance(tag, Compound):
            raise ConversionError(f"region {rname!r} is malformed")
        what = f"region {rname!r}"
        position = _vec3i(tag.get("Position"), f"{what} Position")
        signed = _vec3i(tag.get("Size"), f"{what} Size")
        if 0 in signed:
            raise ConversionError(f"{what} has a zero size")
        # A negative size extends back from Position; blocks start at the min corner.
        lo: Vec3 = tuple(p + s + 1 if s < 0 else p for p, s in zip(position, signed))  # type: ignore[assignment]
        size: Vec3 = tuple(abs(s) for s in signed)  # type: ignore[assignment]
        total = _check_volume(size, total, max_volume)
        palette = _palette(tag.get("BlockStatePalette"), what)
        volume = size[0] * size[1] * size[2]
        bits = bits_for(len(palette))
        states = tag.get("BlockStates")
        if not isinstance(states, LongArray) or len(states) * 64 < volume * bits:
            raise ConversionError(f"{what} has missing or short BlockStates")
        blocks = unpack_bits(states, bits, volume)
        _check_indices(blocks, len(palette), what)
        region = Region(str(rname), lo, size, palette, blocks)
        for te in _compounds(tag, "TileEntities"):
            p = tuple(_int(te, k, None) for k in "xyz")
            if None not in p and _inside(p, size):  # type: ignore[arg-type]
                region.block_entities[p] = _without(te, ("x", "y", "z"))  # type: ignore[index]
        # Litematica stores entity positions relative to the region's Position (not its min
        # corner, unlike blocks and tile entities); they differ on axes with a negative size.
        shift = tuple(p - m for p, m in zip(position, lo))
        for e in _compounds(tag, "Entities"):
            pos = _vec3d(e.get("Pos"))
            if pos is not None:
                region.entities.append(Entity(tuple(a + s for a, s in zip(pos, shift)), e))  # type: ignore[arg-type]
        regions.append(region)
    return Schematic(
        name=name,
        author=_str(meta, "Author"),
        data_version=_int(root, "MinecraftDataVersion") or 0,
        regions=regions,
        description=_str(meta, "Description"),
        created=_int(meta, "TimeCreated", None),
        modified=_int(meta, "TimeModified", None),
    )


def read_slopmatic(root: Compound, fallback_name: str, max_volume: int = DEFAULT_MAX_VOLUME) -> Schematic:
    if _str(root, "Format") != SLOPMATIC_MAGIC:
        raise ConversionError("not a .slopmatic file")
    version = _int(root, "Version")
    if version > SLOPMATIC_VERSION:  # type: ignore[operator]
        raise ConversionError(f"made by a newer Slopmatica (format version {version}); this server reads version {SLOPMATIC_VERSION}")
    if version < 1:  # type: ignore[operator]
        raise ConversionError("not a .slopmatic file (bad Version)")
    name = _str(root, "Name").strip() and _str(root, "Name") or fallback_name
    regions = []
    total = 0
    for i, tag in enumerate(_compounds(root, "Regions")):
        rname = _str(tag, "Name", name)
        what = f"region {rname!r}"
        pos = _vec3i(tag.get("Pos"), f"{what} Pos")
        size = _vec3i(tag.get("Size"), f"{what} Size")
        total = _check_volume(size, total, max_volume)
        palette = _palette(tag.get("Palette"), what)
        data = _get(tag, "Blocks", ByteArray)
        if data is None:
            raise ConversionError(f"{what} has no Blocks")
        blocks = rle_decode(data, len(palette), size[0] * size[1] * size[2])
        region = Region(rname, pos, size, palette, blocks)
        for te in _compounds(tag, "BlockEntities"):
            p = te.get("Pos")
            if isinstance(p, IntArray) and len(p) == 3 and _inside(tuple(p), size):  # type: ignore[arg-type]
                region.block_entities[tuple(p)] = _without(te, ("Pos",))  # type: ignore[index]
        for e in _compounds(tag, "Entities"):
            epos = _vec3d(e.get("Pos"))
            if epos is not None:
                region.entities.append(Entity(epos, e))
        regions.append(region)
    if not regions:
        raise ConversionError(".slopmatic file has no regions")
    return Schematic(
        name=name,
        author=_str(root, "Author"),
        data_version=_int(root, "DataVersion") or 0,
        regions=regions,
        created=_int(root, "Created", None),
        modified=_int(root, "Modified", None),
    )


def read_structure(root: Compound, fallback_name: str, max_volume: int = DEFAULT_MAX_VOLUME) -> Schematic:
    size = _vec3i(root.get("size"), "structure size")
    _check_volume(size, 0, max_volume)
    data_version = _int(root, "DataVersion") or 0
    palette_tag = root.get("palette")
    if palette_tag is None:
        # Several palettes are random variants of one structure (shipwrecks); use the first.
        palettes = _get(root, "palettes", List)
        palette_tag = palettes[0] if palettes else None
    palette = _palette(palette_tag, "structure")
    void = len(palette)
    sx, sy, sz = size
    blocks = array("I", (void,)) * (sx * sy * sz)
    region = Region(fallback_name, (0, 0, 0), size, palette, blocks)
    for b in _compounds(root, "blocks"):
        p = b.get("pos")
        state = _int(b, "state", None)
        if not isinstance(p, List) or len(p) != 3 or not all(isinstance(v, int) for v in p) or state is None:
            raise ConversionError("structure has a malformed block entry")
        if not 0 <= state < void:
            raise ConversionError("structure has a block state index outside its palette")
        x, y, z = (int(v) for v in p)
        if not _inside((x, y, z), size):
            continue
        blocks[(y * sz + z) * sx + x] = state
        be = _get(b, "nbt", Compound)
        if be is not None:
            region.block_entities[(x, y, z)] = _without(be, ("x", "y", "z", "Pos"))
        else:
            region.block_entities.pop((x, y, z), None)
    if void in blocks:
        # positions not listed are structure voids: "leave the world alone"
        palette.append(make_state(VOID_NAME, data_version))
    for e in _compounds(root, "entities"):
        data = _get(e, "nbt", Compound)
        pos = _vec3d(e.get("pos")) or (_vec3d(data.get("Pos")) if data is not None else None)
        if data is not None and pos is not None:
            region.entities.append(Entity(pos, data))
    return Schematic(name=fallback_name, author=_str(root, "author"), data_version=data_version, regions=[region])


def _sponge_palette(tag: Any, data_version: int) -> list[Compound]:
    """Sponge's palette ({state string: index}) as a list. Indices must be distinct; a gap (no writer leaves
    one) is filled with air."""
    if not isinstance(tag, Compound) or not tag:
        raise ConversionError("schematic has no block palette")
    by_index: dict[int, Compound] = {}
    for text, index in tag.items():
        if not isinstance(index, int) or not 0 <= index < len(tag) + 1024 or index in by_index:
            raise ConversionError("schematic has a malformed block palette")
        by_index[int(index)] = parse_state(text, data_version)
    air = make_state("minecraft:air", data_version)
    return [by_index.get(i, air) for i in range(max(by_index) + 1)]


def _sponge_data(te: Compound, nested: bool) -> Compound | None:
    """A Sponge block entity or entity as the NBT the other formats keep: its own tags (v3 nests them in
    Data, earlier versions put them next to Pos and Id) with Id as their id."""
    eid = _str(te, "Id")
    if not eid:
        return None
    data = _get(te, "Data", Compound) if nested else None
    out = _without(data if data is not None else te, ("Pos", "Id", "id", "x", "y", "z", "ContentVersion"))
    out["id"] = String(eid)
    return out


def read_sponge(root: Compound, fallback_name: str, max_volume: int = DEFAULT_MAX_VOLUME) -> Schematic:
    """A Sponge Schematic (WorldEdit's .schem), version 1, 2 or 3. Biomes are not kept."""
    inner = _get(root, "Schematic", Compound)  # v3 wraps everything in a "Schematic" compound
    if inner is not None:
        root = inner
    version = _int(root, "Version", None)
    if version not in (1, 2, 3):
        raise ConversionError("not a Sponge schematic" if version is None else f"unsupported Sponge schematic version {version}")
    dims = [_int(root, k, None) for k in ("Width", "Height", "Length")]
    if None in dims:
        raise ConversionError("schematic has no Width, Height or Length")
    size: Vec3 = tuple(d & 0xFFFF for d in dims)  # type: ignore[assignment,operator]  # unsigned shorts
    _check_volume(size, 0, max_volume)
    data_version = _int(root, "DataVersion", None) or (SPONGE_V1_DATA_VERSION if version == 1 else 0)
    meta = _get(root, "Metadata", Compound) or Compound()
    offset = root.get("Offset")
    offset_v: Vec3 = _vec3i(offset, "Offset") if offset is not None else (0, 0, 0)
    if version == 3:
        holder = _get(root, "Blocks", Compound)
        if holder is None:
            raise ConversionError("schematic has no Blocks")
        palette_tag, data = holder.get("Palette"), _get(holder, "Data", ByteArray)
        tiles = _compounds(holder, "BlockEntities")
        # Offset is the min corner relative to the paste origin; entities are relative to the min corner.
        pos, entity_origin = offset_v, (0, 0, 0)
    else:
        palette_tag, data = root.get("Palette"), _get(root, "BlockData", ByteArray)
        tiles = _compounds(root, "BlockEntities") or _compounds(root, "TileEntities")  # v1 calls them TileEntities
        # Offset is the min corner in the world it was copied from, and entity positions are world positions
        # too; WorldEdit keeps the min corner relative to the paste origin in WEOffsetX/Y/Z.
        we = [_int(meta, "WEOffset" + a, None) for a in "XYZ"]
        pos, entity_origin = (tuple(we) if None not in we else (0, 0, 0)), offset_v  # type: ignore[assignment]
    palette = _sponge_palette(palette_tag, data_version)
    if data is None:
        raise ConversionError("schematic has no block data")
    blocks = varint_array(data, "schematic")
    if len(blocks) != size[0] * size[1] * size[2]:
        raise ConversionError(f"corrupt schematic: block data covers {len(blocks)} of {size[0] * size[1] * size[2]} blocks")
    _check_indices(blocks, len(palette), "schematic")
    name = _str(meta, "Name").strip() and _str(meta, "Name") or fallback_name
    region = Region(name, pos, size, palette, blocks)
    for te in tiles:
        p = te.get("Pos")
        be = _sponge_data(te, version == 3)
        if isinstance(p, IntArray) and len(p) == 3 and _inside(tuple(p), size) and be is not None:  # type: ignore[arg-type]
            region.block_entities[tuple(p)] = be  # type: ignore[index]
    for e in _compounds(root, "Entities"):
        epos, data_e = _vec3d(e.get("Pos")), _sponge_data(e, version == 3)
        if epos is None or data_e is None:
            continue
        rel = tuple(a - o for a, o in zip(epos, entity_origin))
        # Other v2 writers store positions relative to the min corner already: keep those that only fit as such.
        if version != 3 and not _near(rel, size) and _near(epos, size):
            rel = epos
        region.entities.append(Entity(rel, data_e))  # type: ignore[arg-type]
    date = _int(meta, "Date", None)
    return Schematic(name=name, author=_str(meta, "Author"), data_version=data_version, regions=[region],
                     created=date, modified=date)


def _near(p: tuple[float, ...], size: Vec3) -> bool:
    """Whether an entity at p (relative to the min corner) is in or right next to the box."""
    return all(-1 <= p[a] <= size[a] + 1 for a in range(3))


READERS = {"litematic": read_litematic, "slopmatic": read_slopmatic, "nbt": read_structure, "schem": read_sponge}


# --------------------------------------------------------------------------- writers


def _now_ms() -> int:
    return int(time.time() * 1000)


def _air_first(region: Region, data_version: int) -> tuple[list[Compound], array]:
    """Litematica expects palette index 0 to be air."""
    palette, blocks = region.palette, region.blocks
    if palette and state_name(palette[0]) == "minecraft:air":
        return palette, blocks
    at = next((i for i, s in enumerate(palette) if state_name(s) == "minecraft:air"), None)
    if at is None:
        table = list(range(1, len(palette) + 1))
        palette = [make_state("minecraft:air", data_version)] + palette
    else:
        table = list(range(len(palette)))
        table[0], table[at] = at, 0
        palette = list(palette)
        palette[0], palette[at] = palette[at], palette[0]
    return palette, array("I", map(table.__getitem__, blocks))


def write_litematic(s: Schematic) -> Compound:
    regions = Compound()
    for rname, r in zip(_unique_names(s.regions), s.regions):
        palette, blocks = _air_first(r, s.data_version)
        tiles = List((), TAG_COMPOUND)
        for p, data in r.block_entities.items():
            te = Compound(data)
            te.update(_xyz(p))
            tiles.append(te)
        regions[rname] = Compound({
            "Position": _xyz(r.pos),
            "Size": _xyz(r.size),
            "BlockStatePalette": List(palette, TAG_COMPOUND),
            "BlockStates": pack_bits(blocks, bits_for(len(palette))),
            "TileEntities": tiles,
            "Entities": List((_with_pos(e.data, e.pos) for e in r.entities), TAG_COMPOUND),
            "PendingBlockTicks": List(),
            "PendingFluidTicks": List(),
        })
    _, size = s.bounds()
    now = _now_ms()
    meta = Compound({
        "Name": String(s.name),
        "Author": String(s.author),
        "Description": String(s.description),
        "RegionCount": Int(len(s.regions)),
        "TotalBlocks": Int(min(s.block_count(), 2**31 - 1)),
        "TotalVolume": Int(min(sum(r.volume for r in s.regions), 2**31 - 1)),
        "EnclosingSize": _xyz(size),
        "TimeCreated": Long(s.created if s.created is not None else now),
        "TimeModified": Long(s.modified if s.modified is not None else now),
    })
    return Compound({
        "Version": Int(7),
        "SubVersion": Int(1),
        "MinecraftDataVersion": Int(s.data_version),
        "Metadata": meta,
        "Regions": regions,
    })


def write_slopmatic(s: Schematic) -> Compound:
    regions = List((), TAG_COMPOUND)
    for rname, r in zip(_unique_names(s.regions), s.regions):
        tiles = List((), TAG_COMPOUND)
        for p, data in r.block_entities.items():
            te = _without(data, ("x", "y", "z"))
            te["Pos"] = _ivec(p)
            tiles.append(te)
        regions.append(Compound({
            "Name": String(rname),
            "Pos": _ivec(r.pos),
            "Size": _ivec(r.size),
            "Palette": List(r.palette, TAG_COMPOUND),
            "Blocks": ByteArray(rle_encode(r.blocks)),
            "BlockEntities": tiles,
            "Entities": List((_with_pos(e.data, e.pos) for e in r.entities), TAG_COMPOUND),
        }))
    _, size = s.bounds()
    now = _now_ms()
    return Compound({
        "Format": String(SLOPMATIC_MAGIC),
        "Version": Int(SLOPMATIC_VERSION),
        "DataVersion": Int(s.data_version),
        "Name": String(s.name),
        "Author": String(s.author),
        "Created": Long(s.created if s.created is not None else now),
        "Modified": Long(s.modified if s.modified is not None else now),
        "Size": _ivec(size),
        "BlockCount": Long(s.block_count()),
        "Regions": regions,
    })


def _wins(new: int, old: int) -> bool:
    """Overlapping regions: a real block beats air and void, air beats void; void never
    overwrites anything (it means "no block here")."""
    return new == SOLID or (new == AIR and old != SOLID)


def merge_regions(s: Schematic, max_volume: int = DEFAULT_MAX_VOLUME) -> Region:
    """All regions as one box over their enclosing bounds. Positions no region covers hold UNSET."""
    if len(s.regions) == 1:
        return s.regions[0]
    lo, size = s.bounds()
    _check_volume(size, 0, max_volume)
    SX, SY, SZ = size
    palette: list[Compound] = []
    index: dict[Any, int] = {}
    blocks = array("I", (UNSET,)) * (SX * SY * SZ)
    block_entities: dict[Vec3, Compound] = {}
    entities: list[Entity] = []
    kinds = bytearray()
    done: list[tuple[Vec3, Vec3]] = []  # boxes already written, to spot overlaps

    def kind(i: int) -> int:
        return VOID if i == UNSET else kinds[i]

    for r in s.regions:
        table = []
        for state in r.palette:
            key = nbt.canonical(state)
            if key not in index:
                index[key] = len(palette)
                palette.append(state)
                kinds.append(block_kind(state))
            table.append(index[key])
        src = array("I", map(table.__getitem__, r.blocks))
        ox, oy, oz = (r.pos[a] - lo[a] for a in range(3))
        sx, sy, sz = r.size

        def at(p: Vec3) -> tuple[int, int]:  # (merged index, region index) of a region-relative position
            return ((p[1] + oy) * SZ + p[2] + oz) * SX + p[0] + ox, (p[1] * sz + p[2]) * sx + p[0]

        # block entities follow whichever region's block wins the position
        bounds_hi = (ox + sx, oy + sy, oz + sz)
        touched = {(x - ox, y - oy, z - oz) for (x, y, z) in block_entities
                   if ox <= x < bounds_hi[0] and oy <= y < bounds_hi[1] and oz <= z < bounds_hi[2]}
        touched.update(r.block_entities)
        won = {}
        for p in touched:
            d, i = at(p)
            won[p] = _wins(kinds[src[i]], kind(blocks[d]))

        overlap = any(all(a_lo < b_hi and b_lo < a_hi for a_lo, a_hi, b_lo, b_hi in
                          zip((ox, oy, oz), bounds_hi, olo, ohi)) for olo, ohi in done)
        for y in range(sy):
            for z in range(sz):
                i = (y * sz + z) * sx
                d = ((y + oy) * SZ + z + oz) * SX + ox
                if not overlap:
                    blocks[d : d + sx] = src[i : i + sx]
                    continue
                for k in range(sx):
                    new = src[i + k]
                    if _wins(kinds[new], kind(blocks[d + k])):
                        blocks[d + k] = new
        done.append(((ox, oy, oz), bounds_hi))

        for p in touched:
            if won[p]:
                q = (p[0] + ox, p[1] + oy, p[2] + oz)
                block_entities.pop(q, None)
                if p in r.block_entities:
                    block_entities[q] = r.block_entities[p]
        for e in r.entities:
            entities.append(Entity((e.pos[0] + ox, e.pos[1] + oy, e.pos[2] + oz), e.data))
    return Region(s.name, lo, size, palette, blocks, block_entities, entities)


_POS_HEAD = b"\x09\x00\x03pos\x03\x00\x00\x00\x03"  # TAG_List "pos" of 3 TAG_Int
_NBT_HEAD = b"\x0a\x00\x03nbt"  # TAG_Compound "nbt"
_PACK3 = struct.Struct(">iii").pack


def write_structure(s: Schematic, max_volume: int = DEFAULT_MAX_VOLUME) -> Compound:
    box = merge_regions(s, max_volume)
    kinds = palette_kinds(box.palette)
    skip = {i for i, k in enumerate(kinds) if k == VOID} | {UNSET}
    count = sum(n for i, n in Counter(box.blocks).items() if i not in skip)
    states = [b"\x03\x00\x05state" + struct.pack(">i", i) for i in range(len(box.palette))]
    bes = {p: nbt.encode_payload(_without(d, ("x", "y", "z", "Pos"))) for p, d in box.block_entities.items()}
    sx, sy, sz = box.size

    def chunks():
        # Each element of "blocks" is encoded straight to bytes: a Python object per block
        # would cost far more memory than the block itself.
        out = bytearray()
        blocks = box.blocks
        i = 0
        for y in range(sy):
            for z in range(sz):
                for x in range(sx):
                    idx = blocks[i]
                    i += 1
                    if idx in skip:
                        continue
                    out += _POS_HEAD
                    out += _PACK3(x, y, z)
                    out += states[idx]
                    if bes:
                        be = bes.get((x, y, z))
                        if be is not None:
                            out += _NBT_HEAD
                            out += be
                    out.append(0)
                if len(out) >= 1 << 20:
                    yield bytes(out)
                    out.clear()
        yield bytes(out)

    entities = List((), TAG_COMPOUND)
    for e in box.entities:
        entities.append(Compound({
            "pos": List([Double(v) for v in e.pos], TAG_DOUBLE),
            "blockPos": List([Int(int(v // 1)) for v in e.pos]),
            "nbt": _with_pos(e.data, e.pos),
        }))
    root = Compound({
        "DataVersion": Int(s.data_version),
        "size": List([Int(v) for v in box.size]),
        "palette": List(box.palette, TAG_COMPOUND),
        "blocks": RawList(TAG_COMPOUND, count, chunks()),
        "entities": entities,
    })
    if s.author:
        root["author"] = String(s.author)
    return root


def _varint(v: int) -> bytes:
    out = bytearray()
    _put_varint(out, v)
    return bytes(out)


def write_sponge(s: Schematic, max_volume: int = DEFAULT_MAX_VOLUME) -> Compound:
    """Sponge Schematic version 3, as WorldEdit 7.3 writes it. Regions are merged into one box as for
    .nbt; positions no region covers become structure voids. The box's min corner, relative to the
    schematic's origin, is the Offset."""
    box = merge_regions(s, max_volume)
    if max(box.size) > SPONGE_MAX_SIDE:
        raise ConversionError(f"too large for .schem: {box.size[0]}x{box.size[1]}x{box.size[2]} (at most {SPONGE_MAX_SIDE} a side)")
    palette: dict[str, int] = {}  # state string -> index; two compounds may print the same
    codes: dict[int, bytes] = {}  # box palette index -> varint of the new index
    for i, st in enumerate(box.palette):
        codes[i] = _varint(palette.setdefault(state_string(st), len(palette)))
    if UNSET in box.blocks:
        codes[UNSET] = _varint(palette.setdefault(VOID_NAME, len(palette)))
    tiles = List((), TAG_COMPOUND)
    for p, data in box.block_entities.items():
        idx = box.blocks[(p[1] * box.size[2] + p[2]) * box.size[0] + p[0]]
        eid = _str(data, "id") or (state_name(box.palette[idx]) if idx != UNSET else "")
        if eid:
            tiles.append(Compound({"Pos": _ivec(p), "Id": String(eid), "Data": _without(data, ("id", "x", "y", "z", "Pos"))}))
    entities = List((), TAG_COMPOUND)
    for e in box.entities:
        if _str(e.data, "id"):
            entities.append(Compound({
                "Pos": List([Double(v) for v in e.pos], TAG_DOUBLE),
                "Id": String(_str(e.data, "id")),
                "Data": _without(e.data, ("id", "Pos")),
            }))
    meta = Compound({"Name": String(s.name), "Date": Long(s.created if s.created is not None else _now_ms())})
    if s.author:
        meta["Author"] = String(s.author)
    blocks = Compound({
        "Palette": Compound({k: Int(v) for k, v in palette.items()}),
        "Data": ByteArray(b"".join(map(codes.__getitem__, box.blocks))),
        "BlockEntities": tiles,
    })
    schematic = Compound({
        "Version": Int(SPONGE_VERSION),
        "DataVersion": Int(s.data_version),
        "Metadata": meta,
        # unsigned shorts, so a side over 32767 is stored as a negative Short
        **{k: nbt.Short(v - 0x10000 if v > 0x7FFF else v) for k, v in zip(("Width", "Height", "Length"), box.size)},
        "Offset": _ivec(box.pos),
        "Blocks": blocks,
    })
    if entities:
        schematic["Entities"] = entities
    return Compound({"Schematic": schematic})


# --------------------------------------------------------------------------- entry points


def format_of(filename: str) -> str | None:
    lower = filename.lower()
    return next((f for f in FORMATS if lower.endswith("." + f)), None)


def read(root: Any, fmt: str, fallback_name: str, max_volume: int = DEFAULT_MAX_VOLUME) -> Schematic:
    if not isinstance(root, Compound):
        raise ConversionError("the file's root tag is not a compound")
    return READERS[fmt](root, fallback_name, max_volume)


def write(s: Schematic, fmt: str, max_volume: int = DEFAULT_MAX_VOLUME) -> Compound:
    if fmt == "litematic":
        return write_litematic(s)
    if fmt == "slopmatic":
        return write_slopmatic(s)
    if fmt == "nbt":
        return write_structure(s, max_volume)
    if fmt == "schem":
        return write_sponge(s, max_volume)
    raise ValueError(f"unknown format {fmt!r}")


def load(path: Path, max_volume: int = DEFAULT_MAX_VOLUME) -> Schematic:
    fmt = format_of(path.name)
    if fmt is None:
        raise ConversionError("not a schematic file name")
    _, root = nbt.load(path)
    return read(root, fmt, path.name[: -len(fmt) - 1], max_volume)


def convert_file(src: Path, fmt: str, out: BinaryIO, max_volume: int = DEFAULT_MAX_VOLUME) -> None:
    """Read src (format from its extension) and write it gzip-compressed as fmt to out."""
    nbt.dump(write(load(src, max_volume), fmt, max_volume), out)
