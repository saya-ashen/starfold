#!/usr/bin/env python3
"""Measured dev.71 survival hull B6 (185 blocks, Sable mass 312.5).

Usage: minimal.py CX DECK_Y CZ [A|B|C] [ballast_layers]
Default B6 is the first tested passing candidate; not a global minimum.
Forward west (-X). Deck is six blocks above the bottom ballast layer.
A and other B sizes are experimental; C was not needed/tested.
Prints build commands only; never connects to a game. See minimal.md.
"""
import sys


def blocks(candidate='B', layers=6):
    if candidate not in ('A', 'B', 'C') or not isinstance(layers, int) or layers < 0:
        raise ValueError('candidate must be A/B/C; ballast layers must be a nonnegative integer')
    hull = {}
    half = 3 if candidate == 'C' else 2
    def put(x, y, z, block):
        hull[x, y, z] = block
    for x in range(-half, half + 1):
        for z in range(-2, 3):
            put(x, 0, z, 'minecraft:oak_planks')
    if candidate == 'B':
        for y in range(-layers, 0):
            for x in range(-2, 3):
                for z in range(-2, 3):
                    put(x, y, z, 'minecraft:cobblestone')
    if candidate == 'C':
        for x in range(-half, half + 1):
            for z in range(-2, 3):
                for y in range(1, 4):
                    if abs(x) == half or abs(z) == 2 or y == 3:
                        put(x, y, z, 'minecraft:cobblestone')
    def thr(x, y, z, direction):
        put(x, y, z, f'starfold:thruster[facing={direction}]')
    # Ballast moves the mass centre: move horizontal engines to its nearest layer.
    # Exact centre and residual tilt must still be checked in the live Sable body.
    engine_y = -(layers // 2) if candidate == 'B' else (1 if candidate == 'C' else 0)
    for z in (-1, 1):
        thr(-half, engine_y, z, 'west')
        thr(half, engine_y, z, 'east')
        thr(0, 0, z, 'down')
    for x in (-1, 1):
        thr(x, engine_y, -2, 'north')
        thr(x, engine_y, 2, 'south')
        thr(x, 0, 0, 'up')
    for x in (-half, half):
        put(x, 1, 0, 'starfold:gyro')
        put(x, 2, 0, 'minecraft:lever[face=floor,facing=north,powered=true]')
    put(0, 1, 0, 'starfold:helm[facing=west]')
    put(0, 2, 0, 'starfold:scope_nav[facing=west]')
    for z, facing in ((-2, 'north'), (2, 'south')):
        put(0, 0, z, f'starfold:airstair[facing={facing}]')
        for y, part in ((1, 'lower'), (2, 'upper')):
            put(0, y, z, f'minecraft:oak_door[facing={facing},half={part},open=true]')
    return hull


def build(cx, deck_y, cz, candidate='B', layers=6):
    return [f'setblock {cx+x} {deck_y+y} {cz+z} {block}'
            for (x, y, z), block in sorted(blocks(candidate, layers).items(), key=lambda p: (p[0][1], p[0][2], p[0][0]))]


if __name__ == '__main__':
    cx, y, cz = map(int, sys.argv[1:4])
    print('\n'.join(build(cx, y, cz, *([sys.argv[4]] if len(sys.argv) > 4 else []),
                         **({'layers': int(sys.argv[5])} if len(sys.argv) > 5 else {}))))
