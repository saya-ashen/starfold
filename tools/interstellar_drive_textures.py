#!/usr/bin/env python3
"""Deterministic 16x16 aerospace casing, cyan coils and blue-white core; stdlib only."""
import pathlib
import struct
import zlib

OUT = pathlib.Path(__file__).resolve().parents[1] / 'src/main/resources/assets/starfold/textures/block'


def pixel(x, y, core):
    c = (39 + (x * 3 + y * 7) % 5, 45 + (x + y) % 5, 54 + (x + y * 3) % 5)
    if x in (0, 15) or y in (0, 15):
        c = (19, 23, 30)
    if core:
        radius = (2 * x - 15) ** 2 + (2 * y - 15) ** 2
        if radius < 150:
            c = (15, 24, 35)
        if 72 <= radius <= 122:
            c = (58, 174, 225) if y > 7 else (137, 226, 249)
        if radius <= 26:
            c = (111, 210, 244) if radius > 10 else (225, 249, 255)
    else:
        if 3 <= x <= 12 and 3 <= y <= 12:
            c = (20, 28, 38)
        if x in (4, 7, 8, 11) and 3 <= y <= 12:
            c = (57, 158, 203) if y % 3 else (156, 226, 247)
        if y in (4, 11) and 2 <= x <= 13:
            c = (78, 90, 105)
    if (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        c = (119, 133, 146)
    return (*c, 255)


def write(name, core):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    rows = b''.join(b'\0' + bytes(c for x in range(16) for c in pixel(x, y, core)) for y in range(16))
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 16, 16, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(rows, 9)) + chunk(b'IEND', b'')
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / (name + '.png')).write_bytes(png)


if __name__ == '__main__':
    write('interstellar_drive_side', False)
    write('interstellar_drive_core', True)
