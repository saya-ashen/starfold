#!/usr/bin/env python3
"""Offline B6-F first-ship proposal. Prints a parts list/layout, never game commands.

Retains B6's deck and nozzles; replaces six ballast blocks with four tanks and
opposed cargo barrels at the engine plane. An isolated supplied 4000 mB diesel fixture passed a short local flight;
this is not full survival, payload or endurance qualification.
"""
import collections
import json

from minimal import blocks


def layout():
    hull = blocks()
    for x in (-2, 2):
        for z in (-2, 2):
            hull[x, -3, z] = 'create:fluid_tank'
        hull[x, -3, 0] = f'minecraft:barrel[facing={"west" if x < 0 else "east"}]'
    return hull


def report():
    hull = layout()
    items = collections.Counter(s.split('[')[0] for s in hull.values())
    # A door item places both halves. Levers must be switched on by the builder.
    items['minecraft:oak_door'] //= 2
    return {
        'design': 'B6-F',
        'qualification': 'isolated supplied 4000 mB diesel local-flight fixture passed; survival, payload and endurance unqualified',
        'coordinates': 'deck Y=0, west (-X) forward, relative block coordinates',
        'placed_blocks': len(hull),
        'items': dict(sorted(items.items())),
        'blocks': [{'position': list(pos), 'state': state}
                   for pos, state in sorted(hull.items(), key=lambda p: (p[0][1], p[0][2], p[0][0]))],
        'tools_and_supplies': ['Physics Assembler', 'Super Glue', 'pickaxe',
                               'compatible fuel', 'food', 'repair spares'],
        'mass': 'measure the assembled hull; add fuel and any modelled cargo mass separately',
    }


if __name__ == '__main__':
    print(json.dumps(report(), indent=2, ensure_ascii=False))
