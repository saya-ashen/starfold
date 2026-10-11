#!/usr/bin/env python3
"""Commands that build the starter rocket around a deck centre. Forward is west (-x). Needs Sable.
usage: rocket.py CX DECK_Y CZ [signs=False] [rel=True]  -> prints one command per line (no leading slash).
With rel=True the three numbers are offsets from whoever runs the commands (~).

It is flown from our ship controller: right-click it; R leaves. Vanilla movement keys command the hull's
thrusters directly; sprint enables the hull's boosters. Two gyros, always on, keep the deck level, carry the ship's weight and
still its drift, so a key held is a speed and no key is standing still. A glass hull shuts the crew in. There is no dial:
hold space to climb, with Ctrl for haste. Turning is by a pair: one thruster at the bow
and one at the stern push opposite ways, so the ship turns where it is."""
import sys

def build(cx, d, cz, signs=True, rel=False, cabin=True):
    c = []
    at = (lambda a, b: f"~{a+b}" if a+b else "~") if rel else (lambda a, b: str(a+b))
    def fill(x1, y1, z1, x2, y2, z2, block): c.append(f"fill {at(cx,x1)} {at(d,y1)} {at(cz,z1)} {at(cx,x2)} {at(d,y2)} {at(cz,z2)} {block}")
    def put(x, y, z, block): c.append(f"setblock {at(cx,x)} {at(d,y)} {at(cz,z)} {block}")
    def thruster(x, y, z, nozzle): put(x, y, z, f"starfold:thruster[facing={nozzle}]")
    if cabin:
        # A hull about it all, three high inside: between two planets down turns over, and a crew that is shut in is
        # a crew that is still there when the ship has righted itself. Wide doors and half-height boarding
        # steps on both sides keep a yawed, uneven-ground landing boardable without unbalancing the hull.
        fill(-5, 0, -3, 5, 4, 3, "minecraft:glass"); fill(-4, 1, -2, 4, 3, 2, "minecraft:air"); fill(-5, 0, -3, 5, 0, 3, "minecraft:spruce_planks")
        for z, facing, hinges in ((3, "north", ("left", "right")), (-3, "south", ("right", "left"))):
            for x, hinge in enumerate(hinges):
                for y, half in ((1, "lower"), (2, "upper")): put(x, y, z, f"minecraft:oak_door[half={half},facing={facing},hinge={hinge}]")
            # Each door leaf has a sill; adjacent sills form one two-wide airstair.
            for x in range(2): put(x, 0, z, f"starfold:airstair[facing={'south' if z > 0 else 'north'}]")
    fill(-4, 0, -2, 4, 0, 2, "minecraft:spruce_planks")
    for x, z in ((-2, -1), (-2, 1), (0, -1), (0, 1), (2, -1), (2, 1)): put(x, 0, z, "minecraft:iron_block")
    # The controller selects pushes and turning moments; no receiver or frequency tuning.
    thruster(0, 0, 0, "down")
    # What stands off the centre line stands on both sides of it: a ship heavier on one side veers under thrust.
    thruster(1, 1, -1, "up"); thruster(1, 1, 1, "up")
    thruster(4, 1, 0, "east")
    # Mirrored boosters keep the ship balanced and hasten every thruster, not just the stern.
    for z in (-1, 1): put(2, 1, z, "starfold:booster")
    # Bow machinery sits in the deck: a clear glass windscreen ahead.
    thruster(-4, 0, 0, "west")
    # Left is south (+z) for a ship that heads west: the bow is pushed south and the stern north.
    thruster(-4, 0, -2, "north"); thruster(4, 1, 2, "south")
    thruster(-4, 0, 2, "south"); thruster(4, 1, -2, "north")
    # A lever powers the gyro it stands on, and a powered block powers what is beside it: no thruster is beside a gyro.
    for x, z in ((-1, 2), (-1, -2)): put(x, 1, z, "starfold:gyro"); put(x, 2, z, "minecraft:lever[face=floor,facing=north,powered=true]")
    put(-2, 1, 0, "starfold:helm[facing=west]")
    # Centreline cameras replace hull only, never machinery: bow at pilot eye height, deck looking down.
    put(-5, 2, 0, "starfold:scope_precise[facing=west]")
    put(-3, 0, 0, "starfold:scope_precise[facing=down]")
    if signs:
        def sign(x, y, z, rotation, *lines):
            text = ",".join("\'{\"text\":\"%s\"}\'" % l for l in (list(lines) + ["", "", "", ""])[:4])
            put(x, y, z, f"minecraft:oak_sign[rotation={rotation}]{{front_text:{{messages:[{text}]}}}}")
        sign(0, 1, 1, 12, "右键控制器", "W前 S后", "空格升 Shift降", "Ctrl各方向加速")
        sign(0, 1, -1, 12, "R或右键退出", "A左 D右", "星球转向", "太空滚转")
    return c

if __name__ == '__main__':
    cx, d, cz = map(int, sys.argv[1:4]); kw = {}
    for a in sys.argv[4:]:
        k, v = a.split('='); kw[k] = {'True': True, 'False': False}.get(v, int(v) if v.lstrip('-').isdigit() else v)
    print("\n".join(build(cx, d, cz, **kw)))
