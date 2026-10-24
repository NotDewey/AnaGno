"""Strict non-interlaced 8-bit PNG coverage I/O using only the standard library.

Supported annotation format: grayscale8 PNG, gray value IS coverage, not
opaque alpha. Supported Android cutout format: RGBA8 PNG, actual alpha channel
is coverage; RGB channels are ignored. All five scanline filters are supported.
Palette/RGB/gray-alpha, tRNS, interlacing, other bit depths, bad CRC/order,
truncation and excess decompressed data fail explicitly with ValueError.
"""

from pathlib import Path
import struct
import zlib

from .projection import dimensions

SIGNATURE = b'\x89PNG\r\n\x1a\n'
MAX_FILE_BYTES = 256_000_000


def _chunk(kind, payload):
    return (struct.pack('>I', len(payload)) + kind + payload +
            struct.pack('>I', zlib.crc32(kind+payload) & 0xffffffff))


def _write(path, width, height, data, channels, color):
    dimensions(width, height)
    if not isinstance(data, bytes) or len(data) != width*height*channels:
        raise ValueError("PNG pixel buffer has wrong type or length")
    stride = width*channels
    raw = b''.join(b'\0'+data[row_index*stride:(row_index+1)*stride] for row_index in range(height))
    content = (SIGNATURE + _chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, color, 0, 0, 0)) +
               _chunk(b'IDAT', zlib.compress(raw, 9)) + _chunk(b'IEND', b''))
    Path(path).write_bytes(content)


def write_gray(path, width, height, data):
    """Write grayscale8, with gray values interpreted as coverage on read."""
    _write(path, width, height, data, 1, 0)


def write_rgba(path, width, height, data):
    """Write RGBA8, with actual alpha interpreted as coverage on read."""
    _write(path, width, height, data, 4, 6)


def _paeth(left, above, upper_left):
    estimate = left+above-upper_left
    pa, pb, pc = abs(estimate-left), abs(estimate-above), abs(estimate-upper_left)
    return left if pa <= pb and pa <= pc else above if pb <= pc else upper_left


def read_alpha(path):
    """Return (width, height, coverage_bytes); reject unsupported PNG formats."""
    path = Path(path)
    if path.stat().st_size > MAX_FILE_BYTES:
        raise ValueError("PNG file too large")
    content = path.read_bytes()
    if not content.startswith(SIGNATURE):
        raise ValueError("invalid PNG signature")
    offset, header, ended, idat_ended = 8, None, False, False
    blocks = []
    seen_idat = False
    while offset < len(content):
        if offset+12 > len(content):
            raise ValueError("truncated PNG chunk")
        size = struct.unpack_from('>I', content, offset)[0]
        kind = content[offset+4:offset+8]
        end = offset+12+size
        if end > len(content) or any(not (65 <= character <= 90 or 97 <= character <= 122) for character in kind) or kind[2] & 32:
            raise ValueError("invalid PNG chunk")
        payload = content[offset+8:offset+8+size]
        crc = struct.unpack_from('>I', content, offset+8+size)[0]
        if crc != zlib.crc32(kind+payload) & 0xffffffff:
            raise ValueError("PNG CRC mismatch")
        if header is None and kind != b'IHDR':
            raise ValueError("IHDR must be first")
        if kind == b'IHDR':
            if header is not None or size != 13:
                raise ValueError("invalid or duplicate IHDR")
            header = struct.unpack('>IIBBBBB', payload)
            width, height, depth, color, compression, filtering, interlace = header
            dimensions(width, height)
            if depth != 8 or color not in (0, 6) or (compression, filtering, interlace) != (0, 0, 0):
                raise ValueError("only non-interlaced grayscale8 and RGBA8 PNG supported")
        elif kind == b'IDAT':
            if idat_ended:
                raise ValueError("IDAT chunks must be consecutive")
            blocks.append(payload)
            seen_idat = True
        elif kind == b'IEND':
            if size or not seen_idat or end != len(content):
                raise ValueError("invalid IEND or trailing data")
            ended = True
            break
        else:
            if kind == b'tRNS' or not kind[0] & 32:
                raise ValueError("unsupported PNG critical/transparency chunk")
            if seen_idat:
                idat_ended = True
        offset = end
    if not ended:
        raise ValueError("PNG missing IEND")
    channels = 1 if color == 0 else 4
    stride = width*channels
    expected = height*(stride+1)
    decoder = zlib.decompressobj()
    try:
        raw = decoder.decompress(b''.join(blocks), expected+1)
    except zlib.error as exc:
        raise ValueError("invalid PNG compression") from exc
    if len(raw) != expected or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
        raise ValueError("PNG decompressed length/stream mismatch")
    previous = bytearray(stride)
    alpha = bytearray(width*height)
    for row_index in range(height):
        start = row_index*(stride+1)
        method = raw[start]
        if method > 4:
            raise ValueError("unsupported PNG scanline filter")
        row = bytearray(raw[start+1:start+1+stride])
        for byte_index in range(stride):
            left = row[byte_index-channels] if byte_index >= channels else 0
            above = previous[byte_index]
            upper_left = previous[byte_index-channels] if byte_index >= channels else 0
            predictor = (0, left, above, (left+above)//2, _paeth(left, above, upper_left))[method]
            row[byte_index] = (row[byte_index]+predictor) & 255
        alpha[row_index*width:(row_index+1)*width] = row if channels == 1 else row[3::4]
        previous = row
    return width, height, bytes(alpha)
