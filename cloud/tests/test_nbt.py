import gzip
import struct

import pytest

from slopmatica_cloud import nbt
from slopmatica_cloud.nbt import (
    Byte,
    ByteArray,
    Compound,
    Double,
    Float,
    Int,
    IntArray,
    List,
    Long,
    LongArray,
    NBTError,
    Short,
    String,
)


def sample() -> Compound:
    return Compound({
        "byte": Byte(-128),
        "byteMax": Byte(127),
        "short": Short(-32768),
        "int": Int(2**31 - 1),
        "long": Long(-(2**63)),
        "float": Float(1.5),
        "double": Double(-0.1),
        "bytes": ByteArray(b"\x00\x7f\x80\xff"),
        "string": String("plain"),
        "unicode": String("smørrebrød 💎 \x00 end"),
        "doubles": List([Double(1.0), Double(2.5), Double(-3.0)]),
        "floats": List([Float(0.25), Float(90.0)]),
        "ints": List([Int(1), Int(-2)]),
        "shorts": List([Short(7)]),
        "longs": List([Long(2**40)]),
        "bytesList": List([Byte(1), Byte(0)]),
        "strings": List([String("a"), String("")]),
        "empty": List(),
        "nested": List([List([Int(1), Int(2)]), List([Int(3)])]),
        "compounds": List([Compound({"a": Int(1)}), Compound()]),
        "arrays": List([IntArray([1, -1]), IntArray()]),
        "compound": Compound({"inner": Compound({"deep": Short(3)})}),
        "intArray": IntArray([0, -1, 2**31 - 1, -(2**31)]),
        "longArray": LongArray([0, -1, 2**63 - 1, -(2**63)]),
        "": String("empty key"),
    })


def assert_same(a, b):
    """Equal values and identical tag types all the way down."""
    assert type(a) is type(b), (a, b)
    assert nbt.canonical(a) == nbt.canonical(b)
    if isinstance(a, Compound):
        assert list(a) == list(b)
        for k in a:
            assert_same(a[k], b[k])
    elif isinstance(a, List):
        assert a.elem_type == b.elem_type
        for x, y in zip(a, b):
            assert_same(x, y)
    else:
        assert a == b


def test_round_trip_every_tag_type():
    root = sample()
    name, back = nbt.loads(nbt.dumps(root))
    assert name == ""
    assert_same(root, back)
    # and again from the parsed copy
    assert nbt.dumps(back) == nbt.dumps(root)


def test_named_root_and_uncompressed():
    raw = nbt.serialize(Compound({"x": Int(1)}), name="Level")
    assert raw[:1] == b"\x0a" and raw[1:3] == b"\x00\x05" and raw[3:8] == b"Level"
    name, back = nbt.parse(raw)
    assert name == "Level" and back == {"x": 1}


def test_types_are_not_confused():
    a = Compound({"v": Int(1)})
    b = Compound({"v": Short(1)})
    assert a == b  # plain dict equality ...
    assert nbt.canonical(a) != nbt.canonical(b)  # ... but not the same NBT
    assert nbt.canonical(List([Double(1.0)])) != nbt.canonical(List([Float(1.0)]))
    assert nbt.canonical(Compound({"a": Int(1), "b": Int(2)})) == nbt.canonical(Compound({"b": Int(2), "a": Int(1)}))


def test_known_bytes():
    raw = nbt.serialize(Compound({"d": List([Double(1.0)]), "i": Int(-2)}))
    assert raw == (
        b"\x0a\x00\x00"
        + b"\x09\x00\x01d\x06\x00\x00\x00\x01" + struct.pack(">d", 1.0)
        + b"\x03\x00\x01i\xff\xff\xff\xfe"
        + b"\x00"
    )


def test_modified_utf8():
    # Java writes NUL as C0 80 and characters outside the BMP as surrogate pairs
    s = "a\x00💎"
    raw = nbt.serialize(Compound({"s": String(s)}))
    assert b"\xc0\x80" in raw and b"\xed\xa0\xbd\xed\xb2\x8e" in raw
    assert b"\xf0" not in raw
    assert nbt.parse(raw)[1]["s"] == s
    # plain UTF-8 (4-byte sequences) is read too
    plain = s.encode()
    data = b"\x0a\x00\x00\x08\x00\x01s" + struct.pack(">H", len(plain)) + plain + b"\x00"
    assert nbt.parse(data)[1]["s"] == s
    # a lone surrogate does not crash the reader
    lone = b"\xed\xa0\xbd"
    data = b"\x0a\x00\x00\x08\x00\x01s" + struct.pack(">H", len(lone)) + lone + b"\x00"
    assert nbt.parse(data)[1]["s"] == "�"


def test_writer_refuses_untyped_values():
    with pytest.raises(TypeError):
        nbt.serialize(Compound({"x": 1}))
    with pytest.raises(TypeError):
        nbt.serialize(Compound({"x": List([Int(1), Short(2)], nbt.TAG_INT)}))
    with pytest.raises(ValueError):
        nbt.serialize(Compound({"x": Byte(200)}))


def test_copy_tag_is_deep():
    root = sample()
    c = nbt.copy_tag(root)
    assert_same(root, c)
    c["compound"]["inner"]["deep"] = Short(4)
    c["doubles"].append(Double(9.0))
    assert root["compound"]["inner"]["deep"] == 3 and len(root["doubles"]) == 3


@pytest.mark.parametrize("cut", [1, 3, 10, 40, -2])
def test_truncated(cut):
    raw = nbt.serialize(sample())
    with pytest.raises(NBTError):
        nbt.parse(raw[:cut])


def test_garbage_and_limits():
    with pytest.raises(NBTError):
        nbt.loads(b"not gzip")
    with pytest.raises(NBTError):
        nbt.loads(gzip.compress(b"\x0a\x00\x00\x63"))  # unknown tag type 99
    with pytest.raises(NBTError):
        nbt.loads(gzip.compress(nbt.serialize(sample()))[:-12])  # truncated gzip
    # a list claiming more elements than there are bytes
    with pytest.raises(NBTError):
        nbt.parse(b"\x0a\x00\x00\x09\x00\x01l\x0a\x7f\xff\xff\xff\x00")
    # decompressed size cap
    big = nbt.dumps(Compound({"b": ByteArray(bytes(100_000))}))
    with pytest.raises(NBTError, match="decompresses"):
        nbt.loads(big, max_bytes=50_000)
    # tag budget
    many = nbt.dumps(Compound({"l": List([Compound() for _ in range(1000)])}))
    with pytest.raises(NBTError, match="too many"):
        nbt.loads(many, max_tags=500)
    assert len(nbt.loads(many)[1]["l"]) == 1000
    # nesting depth
    deep = b"\x0a\x00\x00" + b"\x0a\x00\x01a" * 600 + b"\x00" * 601
    with pytest.raises(NBTError, match="deeply"):
        nbt.parse(deep)


def test_concatenated_gzip_members():
    raw = nbt.serialize(Compound({"x": Int(5)}))
    data = gzip.compress(raw[:4]) + gzip.compress(raw[4:])
    assert nbt.loads(data)[1] == {"x": 5}


def test_raw_list_streaming(tmp_path):
    payloads = [nbt.encode_payload(Compound({"i": Int(i)})) for i in range(3)]
    root = Compound({"l": nbt.RawList(nbt.TAG_COMPOUND, 3, iter(payloads))})
    p = tmp_path / "x.nbt"
    with open(p, "wb") as f:
        nbt.dump(root, f)
    back = nbt.load(p)[1]
    assert back["l"].elem_type == nbt.TAG_COMPOUND
    assert [c["i"] for c in back["l"]] == [0, 1, 2]
