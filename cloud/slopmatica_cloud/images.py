"""Just enough image handling for avatars, without Pillow: reading the size of an uploaded PNG or JPEG,
decoding a Minecraft skin (a small PNG) and cutting the face out of it as a new PNG.

The decoder handles what skins are saved as: non-interlaced PNGs, 8 or 16 bits per channel (grey, grey +
alpha, RGB, RGBA) or a palette of up to 8 bits. Anything else raises ValueError, and the caller shows the
placeholder face instead.
"""

from __future__ import annotations

import struct
import zlib

PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
JPEG_MAGIC = b"\xff\xd8\xff"

FACE = 8  # the face is 8 x 8 pixels in the skin
FACE_SCALE = 16  # served as 128 x 128, so browsers scale it down crisply (whole multiples for 16 to 128 px)
_HEAD = (8, 8)  # where the face (front of the head) sits in the skin
_HAT = (40, 8)  # the hat layer over it


def png_size(data: bytes) -> tuple[int, int] | None:
    """(width, height) from a PNG's header, or None if data does not start like a PNG."""
    if not data.startswith(PNG_MAGIC) or len(data) < 24 or data[12:16] != b"IHDR":
        return None
    return struct.unpack(">II", data[16:24])


def jpeg_size(data: bytes) -> tuple[int, int] | None:
    """(width, height) from a JPEG's frame header, or None if it is not a JPEG or has no frame header."""
    if not data.startswith(JPEG_MAGIC):
        return None
    i = 2
    while i + 4 <= len(data):
        if data[i] != 0xFF:
            return None
        marker = data[i + 1]
        if marker == 0xFF:  # fill byte
            i += 1
            continue
        if marker in (0x01, 0xD8) or 0xD0 <= marker <= 0xD7:  # no length
            i += 2
            continue
        if marker in (0xD9, 0xDA):  # end of image, or the scan began before any frame header
            return None
        (length,) = struct.unpack(">H", data[i + 2:i + 4])
        if length < 2:
            return None
        # SOF0..SOF15 carry the size, except DHT (C4), JPG (C8) and DAC (CC)
        if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
            if i + 9 > len(data):
                return None
            height, width = struct.unpack(">HH", data[i + 5:i + 9])
            return width, height
        i += 2 + length
    return None


def _chunks(data: bytes):
    pos = len(PNG_MAGIC)
    while pos + 8 <= len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        if len(body) != length:
            raise ValueError("truncated PNG")
        yield kind, body
        pos += 12 + length
        if kind == b"IEND":
            return


def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    return b if pb <= pc else c


def decode_png(data: bytes, max_pixels: int = 512 * 512) -> tuple[int, int, list[tuple[int, int, int, int]]]:
    """(width, height, RGBA pixels row by row). ValueError for anything it can't read."""
    if not data.startswith(PNG_MAGIC):
        raise ValueError("not a PNG")
    header = None
    palette: list[tuple[int, int, int]] = []
    trns = b""
    idat = bytearray()
    for kind, body in _chunks(data):
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body[:13])
        elif kind == b"PLTE":
            palette = [tuple(body[i:i + 3]) for i in range(0, len(body) - 2, 3)]  # type: ignore[misc]
        elif kind == b"tRNS":
            trns = body
        elif kind == b"IDAT":
            idat += body
    if header is None:
        raise ValueError("no PNG header")
    width, height, depth, ctype, _, _, interlace = header
    if not 0 < width * height <= max_pixels or interlace != 0:
        raise ValueError("unsupported PNG size or interlacing")
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(ctype)
    if channels is None or (ctype == 3 and depth not in (1, 2, 4, 8)) or (ctype != 3 and depth not in (8, 16)):
        raise ValueError("unsupported PNG format")
    bits = channels * depth
    stride = (width * bits + 7) // 8
    bpp = max(1, bits // 8)
    expected = height * (stride + 1)
    raw = zlib.decompressobj().decompress(bytes(idat), expected + 1)
    if len(raw) < expected:
        raise ValueError("truncated PNG data")

    rows: list[bytearray] = []
    prev = bytearray(stride)
    for y in range(height):
        start = y * (stride + 1)
        ftype, line = raw[start], bytearray(raw[start + 1:start + 1 + stride])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if ftype == 1:
                line[i] = (line[i] + a) & 0xFF
            elif ftype == 2:
                line[i] = (line[i] + b) & 0xFF
            elif ftype == 3:
                line[i] = (line[i] + ((a + b) >> 1)) & 0xFF
            elif ftype == 4:
                line[i] = (line[i] + _paeth(a, b, c)) & 0xFF
            elif ftype != 0:
                raise ValueError("bad PNG filter")
        rows.append(line)
        prev = line

    pixels: list[tuple[int, int, int, int]] = []
    step = depth // 8 if depth >= 8 else 0
    for line in rows:
        if ctype == 3:
            per = 8 // depth
            for x in range(width):
                byte = line[x // per]
                index = (byte >> (8 - depth * (x % per + 1))) & ((1 << depth) - 1)
                if index >= len(palette):
                    raise ValueError("palette index out of range")
                r, g, b = palette[index]
                pixels.append((r, g, b, trns[index] if index < len(trns) else 255))
            continue
        for x in range(width):
            s = [line[(x * channels + k) * step] for k in range(channels)]  # the high byte of 16-bit samples
            if ctype == 0:
                pixels.append((s[0], s[0], s[0], 255))
            elif ctype == 4:
                pixels.append((s[0], s[0], s[0], s[1]))
            elif ctype == 2:
                pixels.append((s[0], s[1], s[2], 255))
            else:
                pixels.append((s[0], s[1], s[2], s[3]))
    return width, height, pixels


def encode_png(width: int, height: int, pixels: list[tuple[int, int, int, int]]) -> bytes:
    """An RGBA PNG (no filtering: these images are tiny and repetitive, zlib does the work)."""
    def chunk(kind: bytes, body: bytes) -> bytes:
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for r, g, b, a in pixels[y * width:(y + 1) * width]:
            raw += bytes((r, g, b, a))
    return (PNG_MAGIC + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def _scaled(face: list[tuple[int, int, int, int]]) -> bytes:
    size = FACE * FACE_SCALE
    out = [face[(y // FACE_SCALE) * FACE + x // FACE_SCALE] for y in range(size) for x in range(size)]
    return encode_png(size, size, out)


def _over(top: tuple[int, int, int, int], bottom: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
    """top drawn over an opaque bottom pixel."""
    a = top[3]
    return tuple((top[k] * a + bottom[k] * (255 - a)) // 255 for k in range(3)) + (255,)  # type: ignore[return-value]


def face_png(skin: bytes) -> bytes:
    """The face of a Minecraft skin (head front plus the hat layer), as a 128 x 128 PNG. ValueError if the
    skin can't be read: it must be a 64 x 64 skin or a legacy 64 x 32 one."""
    width, height, px = decode_png(skin, max_pixels=64 * 64)
    if width != 64 or height not in (32, 64):
        raise ValueError("not a 64 x 64 or 64 x 32 skin")

    def region(x0: int, y0: int) -> list[tuple[int, int, int, int]]:
        return [px[(y0 + y) * width + x0 + x] for y in range(FACE) for x in range(FACE)]

    head = [(r, g, b, 255) for r, g, b, _ in region(*_HEAD)]  # the game draws the head opaque
    hat = region(*_HAT)
    if height == 32 and all(p[3] >= 128 for p in hat):
        # the game's rule for old skins: a hat layer without any transparency is not drawn
        hat = [(0, 0, 0, 0)] * len(hat)
    return _scaled([_over(h, f) for h, f in zip(hat, head)])


def placeholder_face() -> bytes:
    """A plain grey face, for players whose skin can't be fetched or read."""
    light, dark = (139, 139, 139, 255), (98, 98, 98, 255)
    face = [dark if y in (4, 5) and x in (1, 2, 5, 6) else light for y in range(FACE) for x in range(FACE)]
    return _scaled(face)
