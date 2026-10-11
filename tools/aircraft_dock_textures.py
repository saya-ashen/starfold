#!/usr/bin/env python3
"""Deterministic 16x16 dark aerospace plating and a yellow landing H; stdlib only."""
import pathlib
import struct
import zlib

OUT = pathlib.Path(__file__).resolve().parents[1] / 'src/main/resources/assets/starfold/textures/block'

def pixel(x, y, top):
    c = (39 + (x * 3 + y * 7) % 5, 45 + (x + y) % 5, 54 + (x + y * 3) % 5)
    if x in (0, 15) or y in (0, 15): c = (19, 23, 30)
    if (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)): c = (119, 133, 146)
    if top and ((x in (5, 10) and 4 <= y <= 11) or (y in (7, 8) and 5 <= x <= 10)
                or (y in (1, 14) and 5 <= x <= 10) or (x in (1, 14) and 5 <= y <= 10)): c = (238, 187, 43)
    if not top and 6 <= y <= 9 and 3 <= x <= 12: c = (21, 26, 34) if y % 2 == 0 else (67, 76, 88)
    return (*c, 255)

def write(name, top):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    rows = b''.join(b'\0' + bytes(c for x in range(16) for c in pixel(x, y, top)) for y in range(16))
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 16, 16, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(rows, 9)) + chunk(b'IEND', b'')
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / (name + '.png')).write_bytes(png)

if __name__ == '__main__':
    write('aircraft_dock_top', True)
    write('aircraft_dock_side', False)
