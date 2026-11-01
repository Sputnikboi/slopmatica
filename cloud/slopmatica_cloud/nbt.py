"""A small NBT (Minecraft's Named Binary Tag) reader and writer.

Every tag is a typed subclass of a plain Python value (``Int(3)`` is an ``int``,
``Compound`` is a ``dict``, ``List`` is a ``list`` with an ``elem_type``), so data read
from a file writes back with exactly the same tag types. Values built by hand must use
these classes too; the writer refuses bare ints and floats because their tag type would
be a guess.

Files are a gzip stream holding one named root tag (a compound, whose name is ``""`` in
every schematic format).
"""

from __future__ import annotations

import gzip
import struct
import sys
import zlib
from array import array
from pathlib import Path
from typing import Any, BinaryIO, Callable, Iterable

TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12

MAX_DEPTH = 512  # same limit as Minecraft
DEFAULT_MAX_BYTES = 512 * 1024 * 1024  # decompressed
DEFAULT_MAX_TAGS = 8_000_000  # bounds the Python objects one file can make

_LITTLE = sys.byteorder == "little"


class NBTError(ValueError):
    """Malformed, truncated or oversized NBT data."""


# --------------------------------------------------------------------------- tag types


class Byte(int):
    __slots__ = ()
    tag_id = TAG_BYTE


class Short(int):
    __slots__ = ()
    tag_id = TAG_SHORT


class Int(int):
    __slots__ = ()
    tag_id = TAG_INT


class Long(int):
    __slots__ = ()
    tag_id = TAG_LONG


class Float(float):
    __slots__ = ()
    tag_id = TAG_FLOAT


class Double(float):
    __slots__ = ()
    tag_id = TAG_DOUBLE


class ByteArray(bytes):
    __slots__ = ()
    tag_id = TAG_BYTE_ARRAY


class String(str):
    __slots__ = ()
    tag_id = TAG_STRING


class List(list):
    """A list whose elements all have the tag type ``elem_type`` (TAG_END when empty)."""

    __slots__ = ("elem_type",)
    tag_id = TAG_LIST

    def __init__(self, items: Iterable[Any] = (), elem_type: int | None = None) -> None:
        super().__init__(items)
        if elem_type is None:
            elem_type = tag_id(self[0]) if self else TAG_END
        self.elem_type = elem_type

    def __repr__(self) -> str:
        return f"List({list.__repr__(self)}, elem_type={self.elem_type})"


class Compound(dict):
    __slots__ = ()
    tag_id = TAG_COMPOUND


class IntArray(array):
    tag_id = TAG_INT_ARRAY

    def __new__(cls, values: Iterable[int] = ()) -> "IntArray":
        return super().__new__(cls, "i", values)


class LongArray(array):
    tag_id = TAG_LONG_ARRAY

    def __new__(cls, values: Iterable[int] = ()) -> "LongArray":
        return super().__new__(cls, "q", values)


class RawList:
    """Write-only list of ``count`` elements of ``elem_type`` whose payloads are already
    encoded; ``chunks`` yields their bytes. Lets very long lists be streamed without
    building a Python object per element."""

    tag_id = TAG_LIST

    def __init__(self, elem_type: int, count: int, chunks: Iterable[bytes]) -> None:
        self.elem_type = elem_type
        self.count = count
        self.chunks = chunks


_TYPES: dict[int, type] = {
    TAG_BYTE: Byte, TAG_SHORT: Short, TAG_INT: Int, TAG_LONG: Long, TAG_FLOAT: Float,
    TAG_DOUBLE: Double, TAG_BYTE_ARRAY: ByteArray, TAG_STRING: String, TAG_LIST: List,
    TAG_COMPOUND: Compound, TAG_INT_ARRAY: IntArray, TAG_LONG_ARRAY: LongArray,
}


def tag_id(value: Any) -> int:
    try:
        return value.tag_id
    except AttributeError:
        raise TypeError(f"not an NBT tag: {type(value).__name__} (wrap it, e.g. Int(...))") from None


def copy_tag(value: Any) -> Any:
    """Deep copy, keeping tag types."""
    if isinstance(value, Compound):
        return Compound({k: copy_tag(v) for k, v in value.items()})
    if isinstance(value, List):
        return List((copy_tag(v) for v in value), value.elem_type)
    if isinstance(value, (IntArray, LongArray)):
        return type(value)(value)
    return value  # the scalar types are immutable


def canonical(value: Any) -> Any:
    """A hashable key equal for two tags exactly when they are the same NBT
    (types included, compound key order ignored)."""
    t = tag_id(value)
    if t == TAG_COMPOUND:
        return (t, tuple(sorted((k, canonical(v)) for k, v in value.items())))
    if t == TAG_LIST:
        return (t, value.elem_type, tuple(canonical(v) for v in value))
    if t in (TAG_INT_ARRAY, TAG_LONG_ARRAY):
        return (t, value.tobytes())
    if t in (TAG_FLOAT, TAG_DOUBLE):
        return (t, struct.pack(">d", value))  # NaN-safe
    return (t, str(value) if t == TAG_STRING else int(value) if t <= TAG_LONG else bytes(value))


# --------------------------------------------------------------------------- strings


def _decode_str(b: bytes) -> str:
    try:
        return b.decode("utf-8")
    except UnicodeDecodeError:
        pass
    # Java's modified UTF-8: NUL as C0 80, characters beyond the BMP as two 3-byte surrogates.
    try:
        s = b.replace(b"\xc0\x80", b"\x00").decode("utf-8", "surrogatepass")
        return s.encode("utf-16-le", "surrogatepass").decode("utf-16-le", "replace")
    except UnicodeError:
        return b.decode("utf-8", "replace")


def _encode_str(s: str) -> bytes:
    b = s.encode("utf-8", "surrogatepass")
    if b"\x00" in b or any(c >= 0xF0 for c in b):
        # modified UTF-8, which is what Minecraft's DataInput.readUTF accepts
        out = bytearray()
        for ch in s:
            o = ord(ch)
            if o == 0:
                out += b"\xc0\x80"
            elif o > 0xFFFF:
                o -= 0x10000
                for unit in (0xD800 | (o >> 10), 0xDC00 | (o & 0x3FF)):
                    out += chr(unit).encode("utf-8", "surrogatepass")
            else:
                out += ch.encode("utf-8", "surrogatepass")
        b = bytes(out)
    if len(b) > 0xFFFF:
        raise ValueError("NBT string longer than 65535 bytes")
    return b


# --------------------------------------------------------------------------- reading

_S_BYTE = struct.Struct(">b")
_S_SHORT = struct.Struct(">h")
_S_USHORT = struct.Struct(">H")
_S_INT = struct.Struct(">i")
_S_LONG = struct.Struct(">q")
_S_FLOAT = struct.Struct(">f")
_S_DOUBLE = struct.Struct(">d")
_SCALARS = {
    TAG_BYTE: (_S_BYTE, Byte, "b"), TAG_SHORT: (_S_SHORT, Short, "h"), TAG_INT: (_S_INT, Int, "i"),
    TAG_LONG: (_S_LONG, Long, "q"), TAG_FLOAT: (_S_FLOAT, Float, "f"), TAG_DOUBLE: (_S_DOUBLE, Double, "d"),
}


class _Reader:
    def __init__(self, data: bytes, max_tags: int) -> None:
        self.buf = memoryview(data)
        self.pos = 0
        self.tags_left = max_tags

    def need(self, n: int) -> int:
        p = self.pos
        if n < 0 or p + n > len(self.buf):
            raise NBTError("truncated NBT data")
        self.pos = p + n
        return p

    def count(self, n: int) -> None:
        self.tags_left -= n
        if self.tags_left < 0:
            raise NBTError("too many NBT tags")

    def length(self) -> int:
        n = _S_INT.unpack_from(self.buf, self.need(4))[0]
        if n < 0:
            raise NBTError("negative NBT length")
        return n

    def string(self) -> str:
        n = _S_USHORT.unpack_from(self.buf, self.need(2))[0]
        p = self.need(n)
        return _decode_str(bytes(self.buf[p : p + n]))

    def payload(self, t: int, depth: int) -> Any:
        self.count(1)
        if t in _SCALARS:
            s, cls, _ = _SCALARS[t]
            return cls(s.unpack_from(self.buf, self.need(s.size))[0])
        if t == TAG_STRING:
            return String(self.string())
        if t == TAG_COMPOUND:
            if depth > MAX_DEPTH:
                raise NBTError("NBT nested too deeply")
            out = Compound()
            while True:
                ct = self.buf[self.need(1)]
                if ct == TAG_END:
                    return out
                if ct not in _TYPES:
                    raise NBTError(f"unknown NBT tag type {ct}")
                name = self.string()
                out[name] = self.payload(ct, depth + 1)
        if t == TAG_LIST:
            if depth > MAX_DEPTH:
                raise NBTError("NBT nested too deeply")
            et = self.buf[self.need(1)]
            n = self.length()
            if et == TAG_END or n == 0:
                if n and et == TAG_END:
                    raise NBTError("non-empty list of TAG_End")
                return List((), et if et in _TYPES else TAG_END)
            if et not in _TYPES:
                raise NBTError(f"unknown NBT tag type {et}")
            if et in _SCALARS:
                s, cls, code = _SCALARS[et]
                p = self.need(n * s.size)
                self.count(n)
                return List(map(cls, struct.unpack_from(f">{n}{code}", self.buf, p)), et)
            if n > len(self.buf) - self.pos:
                raise NBTError("truncated NBT data")
            return List((self.payload(et, depth + 1) for _ in range(n)), et)
        if t == TAG_BYTE_ARRAY:
            n = self.length()
            p = self.need(n)
            return ByteArray(self.buf[p : p + n])
        if t in (TAG_INT_ARRAY, TAG_LONG_ARRAY):
            n = self.length()
            cls = IntArray if t == TAG_INT_ARRAY else LongArray
            out = cls()
            p = self.need(n * out.itemsize)
            out.frombytes(self.buf[p : p + n * out.itemsize])
            if _LITTLE:
                out.byteswap()
            return out
        raise NBTError(f"unknown NBT tag type {t}")


def parse(data: bytes, max_tags: int = DEFAULT_MAX_TAGS) -> tuple[str, Any]:
    """Parse uncompressed NBT: returns (root name, root tag)."""
    r = _Reader(data, max_tags)
    t = r.buf[r.need(1)]
    if t not in _TYPES:
        raise NBTError("data does not start with an NBT tag")
    name = r.string()
    return name, r.payload(t, 0)


def gunzip(source: bytes | BinaryIO, max_bytes: int = DEFAULT_MAX_BYTES) -> bytes:
    """Decompress gzip data (concatenated members allowed), refusing more than max_bytes of output."""
    read: Callable[[], bytes]
    if isinstance(source, (bytes, bytearray, memoryview)):
        chunks = iter([bytes(source)])
        read = lambda: next(chunks, b"")  # noqa: E731
    else:
        read = lambda: source.read(1 << 20)  # noqa: E731
    out = bytearray()
    d = zlib.decompressobj(31)
    pending = read()
    if not pending.startswith(b"\x1f\x8b"):
        raise NBTError("not gzip data")
    try:
        while True:
            if not pending:
                pending = read()
                if not pending:
                    break
            out += d.decompress(pending, max_bytes + 1 - len(out))
            if len(out) > max_bytes:
                raise NBTError(f"decompresses to more than {max_bytes} bytes")
            pending = d.unconsumed_tail
            if d.eof:
                rest = d.unused_data + (b"" if pending else read())
                if not rest.startswith(b"\x1f\x8b"):
                    return bytes(out)
                d, pending = zlib.decompressobj(31), rest
    except zlib.error as e:
        raise NBTError(f"corrupt gzip data: {e}") from None
    if not d.eof:
        raise NBTError("truncated gzip data")
    return bytes(out)


def load(path: str | Path, max_bytes: int = DEFAULT_MAX_BYTES, max_tags: int = DEFAULT_MAX_TAGS) -> tuple[str, Any]:
    with open(path, "rb") as f:
        return parse(gunzip(f, max_bytes), max_tags)


def loads(data: bytes, max_bytes: int = DEFAULT_MAX_BYTES, max_tags: int = DEFAULT_MAX_TAGS) -> tuple[str, Any]:
    return parse(gunzip(data, max_bytes), max_tags)


# --------------------------------------------------------------------------- writing


class _Writer:
    def __init__(self, sink: Callable[[bytes], Any] | None) -> None:
        self.buf = bytearray()
        self.sink = sink

    def flush(self) -> None:
        if self.sink is not None and self.buf:
            self.sink(bytes(self.buf))
            self.buf.clear()

    def maybe_flush(self) -> None:
        if self.sink is not None and len(self.buf) >= 1 << 20:
            self.flush()

    def string(self, s: str) -> None:
        b = _encode_str(s)
        self.buf += _S_USHORT.pack(len(b))
        self.buf += b

    def payload(self, value: Any, t: int) -> None:
        buf = self.buf
        try:
            if t in _SCALARS:
                buf += _SCALARS[t][0].pack(value)
            elif t == TAG_STRING:
                self.string(value)
            elif t == TAG_COMPOUND:
                for k, v in value.items():
                    vt = tag_id(v)
                    buf.append(vt)
                    self.string(k)
                    self.payload(v, vt)
                buf.append(TAG_END)
                self.maybe_flush()
            elif t == TAG_LIST:
                if isinstance(value, RawList):
                    buf.append(value.elem_type)
                    buf += _S_INT.pack(value.count)
                    for chunk in value.chunks:
                        self.buf += chunk
                        self.maybe_flush()
                    return
                et = value.elem_type if value else TAG_END
                buf.append(et)
                buf += _S_INT.pack(len(value))
                if et in _SCALARS:
                    for v in value:
                        if tag_id(v) != et:
                            raise TypeError(f"list of tag type {et} holds a {type(v).__name__}")
                    buf += struct.pack(f">{len(value)}{_SCALARS[et][2]}", *value)
                    return
                for v in value:
                    if tag_id(v) != et:
                        raise TypeError(f"list of tag type {et} holds a {type(v).__name__}")
                    self.payload(v, et)
            elif t == TAG_BYTE_ARRAY:
                buf += _S_INT.pack(len(value))
                buf += value
            elif t in (TAG_INT_ARRAY, TAG_LONG_ARRAY):
                buf += _S_INT.pack(len(value))
                if _LITTLE:
                    swapped = type(value)(value)
                    swapped.byteswap()
                    buf += swapped.tobytes()
                else:
                    buf += value.tobytes()
                self.maybe_flush()
            else:
                raise TypeError(f"unknown NBT tag type {t}")
        except struct.error as e:
            raise ValueError(f"value out of range for NBT tag type {t}: {e}") from None


def encode_payload(value: Any) -> bytes:
    """The payload bytes of one tag (no type byte, no name)."""
    w = _Writer(None)
    w.payload(value, tag_id(value))
    return bytes(w.buf)


def serialize(root: Any, name: str = "", sink: Callable[[bytes], Any] | None = None) -> bytes:
    """Uncompressed NBT for a named root tag. With sink, bytes are handed to it as they are
    produced and b"" is returned."""
    w = _Writer(sink)
    t = tag_id(root)
    w.buf.append(t)
    w.string(name)
    w.payload(root, t)
    if sink is None:
        return bytes(w.buf)
    w.flush()
    return b""


def dump(root: Any, fileobj: BinaryIO, name: str = "", compresslevel: int = 6) -> None:
    """Write root as a gzip-compressed NBT file to an open binary file. Like dumps, the gzip header
    carries no file name (GzipFile would otherwise record fileobj's, e.g. a temporary file's)."""
    with gzip.GzipFile(filename="", fileobj=fileobj, mode="wb", compresslevel=compresslevel, mtime=0) as gz:
        serialize(root, name, gz.write)


def dumps(root: Any, name: str = "", compresslevel: int = 6) -> bytes:
    return gzip.compress(serialize(root, name), compresslevel=compresslevel, mtime=0)
