#!/usr/bin/env python3
"""Deterministic 16px powered faces, stdlib only; geometry remains the existing cube."""
from pathlib import Path
import struct
import zlib

root = Path(__file__).resolve().parents[2] / 'src/main/resources/assets/starfold/textures/block'

def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))

for name, high in [('thruster_nozzle_low', False), ('thruster_nozzle_high', True), ('booster_on', True)]:
    data = bytearray()
    for y in range(16):
        data.append(0)
        for x in range(16):
            r = max(abs(x - 7.5), abs(y - 7.5))
            color = (31, 40, 52) if r > 5.5 else (48, 73, 93) if r > 4.5 else (20, 82, 127) if r > 3.5 else (55, 169, 231) if r > 1.5 else (164, 227, 255)
            if high and r <= 3.5:
                color = (107, 211, 255) if r > 2.5 else (229, 250, 255)
            if name == 'booster_on' and 3 <= x <= 12 and 3 <= y <= 12:
                color = (222, 249, 255) if 6 <= x <= 9 and 6 <= y <= 9 else (65, 187, 249) if 6 <= x <= 9 or 6 <= y <= 9 else (20, 45, 66)
            if (x, y) in [(1, 1), (14, 1), (1, 14), (14, 14)]:
                color = (110, 124, 140)
            data.extend((*color, 255))
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>2I5B', 16, 16, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(data)) + chunk(b'IEND', b'')
    (root / (name + '.png')).write_bytes(png)
