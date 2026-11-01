"""The visible blocks of a schematic, for the website's 3D view.

A block is left out when all six of its neighbours are opaque full blocks inside the
schematic, so a big build sends only its shell. Air and structure voids are never sent.

The occupancy test runs on whole-volume Python integers with one byte per block
("lanes"): shifting by 8 bits moves to the next block along x, by 8 * sx along z and by
8 * sx * sz along y, and & combines the six neighbours of every block at once in C.
"""

from __future__ import annotations

import base64
from array import array
from itertools import compress
from typing import Any

from . import convert
from .nbt import Compound

MAX_VISIBLE = 300_000
MODEL_VERSION = 1

# Not opaque full cubes in the view, so they never hide what is behind them.
SHAPE_FULL, SHAPE_BOTTOM, SHAPE_TOP, SHAPE_THIN = "full", "bottom", "top", "thin"


class TooManyBlocks(Exception):
    def __init__(self, visible: int, limit: int) -> None:
        super().__init__(f"{visible} visible blocks (limit {limit})")
        self.visible = visible
        self.limit = limit


def _properties(state: Any) -> Compound:
    if isinstance(state, Compound):
        props = state.get("Properties", state.get("properties"))
        if isinstance(props, Compound):
            return props
    return Compound()


def block_shape(state: Any) -> str:
    """A rough shape: slabs are half blocks, carpets and pressure plates thin; the rest full cubes."""
    name = convert.state_name(state)
    if name.endswith("_slab"):
        kind = str(_properties(state).get("type", ""))
        if kind in (SHAPE_BOTTOM, SHAPE_TOP):
            return kind
    if name.endswith(("_carpet", "_pressure_plate")):
        return SHAPE_THIN
    return SHAPE_FULL


def _lanes(pattern: bytes, repeat: int) -> int:
    return int.from_bytes(pattern * repeat, "little")


def visible_blocks(
    schem: convert.Schematic,
    transparent: frozenset[str] | set[str] = frozenset(),
    max_visible: int = MAX_VISIBLE,
    max_volume: int = convert.DEFAULT_MAX_VOLUME,
) -> dict[str, Any]:
    """The model the 3D view draws:

    ``{"v", "size": [sx, sy, sz], "count", "palette": [{"id", "shape"}],
    "groups": [{"p": palette index, "n": blocks, "d": base64 little-endian uint32 deltas}]}``

    Each group lists block indices ``(y * sz + z) * sx + x`` in ascending order, as differences
    from the previous one (the first from 0). transparent block ids (glass and the like) and
    non-full shapes never hide a neighbour. Raises TooManyBlocks above max_visible.
    """
    box = convert.merge_regions(schem, max_volume)
    sx, sy, sz = box.size
    volume = sx * sy * sz
    entries: list[tuple[str, str]] = []
    entry_of: dict[tuple[str, str], int] = {}
    remap: dict[int, int] = {}
    # per palette index: 0 nothing to draw, 1 drawn but see-through, 3 drawn and opaque
    code: dict[int, int] = {convert.UNSET: 0}
    for i, state in enumerate(box.palette):
        if convert.block_kind(state) != convert.SOLID:
            code[i] = 0
            continue
        name, shape = convert.state_name(state), block_shape(state)
        key = (name, shape)
        if key not in entry_of:
            entry_of[key] = len(entries)
            entries.append(key)
        remap[i] = entry_of[key]
        code[i] = 3 if shape == SHAPE_FULL and name not in transparent else 1

    codes = bytes(map(code.__getitem__, box.blocks))
    ones = _lanes(b"\x01", volume)
    c = int.from_bytes(codes, "little")
    solid = c & ones
    opaque = (c >> 1) & ones
    del c
    hidden = solid
    if hidden:
        # x neighbours, except across the end of a row
        hidden &= (opaque >> 8) & _lanes(b"\x01" * (sx - 1) + b"\x00", sy * sz)
        hidden &= (opaque << 8) & _lanes(b"\x00" + b"\x01" * (sx - 1), sy * sz)
        # z neighbours, except across the end of a layer
        hidden &= (opaque >> (8 * sx)) & _lanes(b"\x01" * (sx * (sz - 1)) + b"\x00" * sx, sy)
        hidden &= (opaque << (8 * sx)) & _lanes(b"\x00" * sx + b"\x01" * (sx * (sz - 1)), sy)
        # y neighbours: shifting past either end brings in zeros
        hidden &= opaque >> (8 * sx * sz)
        hidden &= opaque << (8 * sx * sz)
    shown = (solid ^ hidden).to_bytes(volume, "little")
    del solid, opaque, hidden
    count = shown.count(1)
    if count > max_visible:
        raise TooManyBlocks(count, max_visible)

    buckets: dict[int, array] = {}
    blocks = box.blocks
    for i in compress(range(volume), shown):
        e = remap[blocks[i]]
        bucket = buckets.get(e)
        if bucket is None:
            bucket = buckets[e] = array("I")
        bucket.append(i)
    used = sorted(buckets)
    groups = []
    for new, e in enumerate(used):
        idx = buckets[e]
        deltas = array("I", [idx[0]])
        deltas.extend(b - a for a, b in zip(idx, idx[1:]))
        if deltas.itemsize != 4:  # pragma: no cover - 'I' is 4 bytes on every platform we run on
            raise RuntimeError("array('I') is not 32-bit")
        if not _little_endian():  # pragma: no cover
            deltas.byteswap()
        groups.append({"p": new, "n": len(idx), "d": base64.b64encode(deltas.tobytes()).decode("ascii")})
    return {
        "v": MODEL_VERSION,
        "size": [sx, sy, sz],
        "count": count,
        "palette": [{"id": entries[e][0], "shape": entries[e][1]} for e in used],
        "groups": groups,
    }


def _little_endian() -> bool:
    import sys

    return sys.byteorder == "little"


def decode_positions(model: dict[str, Any]) -> dict[tuple[int, int, int], tuple[str, str]]:
    """{(x, y, z): (id, shape)} from a model (for tests and debugging)."""
    sx, _, sz = model["size"]
    out = {}
    for g in model["groups"]:
        raw = array("I")
        raw.frombytes(base64.b64decode(g["d"]))
        if not _little_endian():  # pragma: no cover
            raw.byteswap()
        entry = model["palette"][g["p"]]
        i = 0
        for d in raw:
            i += d
            out[(i % sx, i // (sx * sz), (i // sx) % sz)] = (entry["id"], entry["shape"])
    return out
