#!/usr/bin/env python3
"""Writes the 16x16 textures of the anomaly blocks, the core shell and the thruster pack. Standard library only; rerun after changing the look."""
import struct, zlib, pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent / 'src/main/resources/assets/starfold/textures/block'

def noise(x, y, salt):
    n = (x * 374761393 + y * 668265263 + salt * 2147483647) & 0xffffffff
    n = ((n ^ (n >> 13)) * 1274126177) & 0xffffffff
    return ((n ^ (n >> 16)) & 255) / 255

def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))

def write(name, pixel, out=None):
    # A pixel is RGB, or None for a transparent one.
    def rgba(p): return (*p, 255) if p else (0, 0, 0, 0)
    rows = b''.join(b'\0' + bytes(c for x in range(16) for c in rgba(pixel(x, y))) for y in range(16))
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 16, 16, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(rows, 9)) + chunk(b'IEND', b'')
    out = out or OUT
    out.mkdir(parents=True, exist_ok=True)
    (out / f'{name}.png').write_bytes(png)

PLATE, DARK, EDGE, CYAN, WHITE = (38, 42, 50), (20, 22, 28), (64, 72, 84), (88, 232, 255), (226, 252, 255)

def shell(x, y):
    # One plate per block: bevelled rim, a recessed seam through the middle, a rivet in each corner.
    c = mix(PLATE, DARK, noise(x, y, 1) * .35)
    if x in (0, 15) or y in (0, 15): c = DARK
    elif x == 1 or y == 1: c = mix(c, EDGE, .55)
    elif x == 14 or y == 14: c = mix(c, DARK, .5)
    if y in (7, 8) and 2 < x < 13: c = DARK if y == 7 else mix(PLATE, EDGE, .4)
    if (x, y) in ((3, 3), (12, 3), (3, 12), (12, 12)): c = EDGE
    return c

def glow(x, y):
    # Light with faint scan lines; meant to be tinted by Anomaly.GLOW_TINT.
    t = .78 + .22 * noise(x, y, 2)
    if y % 4 == 3: t *= .82
    return mix((150, 190, 200), WHITE, t)

def ground(x, y):
    # Dark plating with a hairline circuit that continues from block to block.
    c = mix(PLATE, DARK, .25 + noise(x, y, 3) * .45)
    if x == 0 or y == 0: c = DARK
    if (y == 5 and x >= 5) or (x == 5 and y <= 5) or (y == 11 and x <= 10) or (x == 10 and y >= 11): c = mix(c, CYAN, .38)
    if (x, y) in ((5, 5), (10, 11)): c = mix(c, CYAN, .8)
    return c

def ring(x, y):
    # A lit square channel in a dark frame: the same from every side, since rings run in every direction.
    d = max(abs(x - 7.5), abs(y - 7.5))
    if d > 7: return DARK
    if d > 6: return mix(PLATE, EDGE, .5)
    if d > 4: return mix(CYAN, WHITE, .35 + .3 * noise(x, y, 4))
    if d > 3: return mix(PLATE, CYAN, .45)
    return mix(PLATE, DARK, noise(x, y, 5) * .5) if d > 1 else mix(PLATE, CYAN, .7)

AMBER, HOT = (255, 150, 60), (255, 226, 170)

def core(x, y):
    # Heavy dark plate, tiling in every direction, with a hot vein that runs on into the next block: what lies
    # under it is not the anomaly's cold light.
    c = mix(DARK, PLATE, .2 + noise(x, y, 6) * .5)
    if x % 8 == 0 or y % 8 == 0: c = mix(c, (8, 9, 12), .7)
    elif x % 8 == 1 or y % 8 == 1: c = mix(c, EDGE, .3)
    def vein(x, y):
        return (y == 4 and (x <= 5 or x >= 10)) or (x in (5, 10) and 4 <= y <= 11) or (y == 11 and 5 <= x <= 10)
    if vein(x, y): c = mix(AMBER, HOT, noise(x, y, 7) * .6)
    elif any(vein(x + i, y + j) for i, j in ((1, 0), (-1, 0), (0, 1), (0, -1))): c = mix(c, AMBER, .22)
    return c

def pack(x, y):
    # Seen from behind: two tanks on a frame, straps above, a nozzle with its flame under each tank.
    left, right = 3 <= x <= 6, 9 <= x <= 12
    if 2 <= y <= 10 and (left or right):
        c = mix(EDGE, PLATE, .25 + noise(x, y, 8) * .3)
        if x in (3, 9): c = mix(c, WHITE, .25)
        if x in (6, 12) or y == 10: c = mix(c, DARK, .5)
        if y == 2: c = mix(c, WHITE, .15)
        if y == 5: c = AMBER
        return c
    if 4 <= y <= 8 and 7 <= x <= 8: return mix(PLATE, DARK, .4)
    if y in (0, 1) and x in (4, 5, 10, 11): return mix(PLATE, EDGE, .5) if y else DARK
    if y == 11 and (4 <= x <= 5 or 10 <= x <= 11): return DARK
    if y == 12 and (4 <= x <= 5 or 10 <= x <= 11): return HOT
    if y == 13 and (4 <= x <= 5 or 10 <= x <= 11): return AMBER
    if y == 14 and x in (4, 11): return mix(AMBER, DARK, .45)
    return None

if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    for name, pixel in (('anomaly_shell', shell), ('anomaly_glow', glow), ('anomaly_ground', ground), ('anomaly_ring', ring), ('core_shell', core)):
        write(name, pixel)
    write('thruster_pack', pack, OUT.parent / 'item')
