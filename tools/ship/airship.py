#!/usr/bin/env python3
"""Commands that build the starter airship around a deck centre. Forward is west (-x).
usage: airship.py CX DECK_Y CZ [key=value ...]  -> prints one command per line (no leading slash).

What flying it in the dev client showed (Create 6.0.10, Aeronautics 1.3.2; probes ship-*.txt):
- A propeller block takes its shaft from the side opposite its `facing`; a creative motor drives out of its `facing`.
- simulated:directional_gearshift: `facing` is one of its two redstone faces, the opposite face is the other, and the
  shaft runs across them (`axis_along_first=true` picks the first of the two remaining axes, x before y before z).
  Power on the `facing` side relays, power on the far side reverses. Levers stand on the deck against those two faces.
- Nothing moves a ship that sits on the ground: thrust is only seen with the burner lit.
- One propeller at 128 RPM gives about 1 block/s. At 256 RPM, 2.1 blocks/s, but pushed at deck level the ship pitches
  27 degrees and tumbles; five iron blocks under the deck hold it to about 12 degrees.
- A lever beside the burner lights it: the ship climbs at 6 to 8 blocks/s and levels off near y=300.
- Water washes levers off: build above sea level (128)."""
import sys

def build(cx, d, cz, burner=False, thrust=None, yaw=None, speed=128, prop='wooden_propeller', signs=True,
          thrust_facing='east', yaw_facing='south', gear='directional', motor_facing='east', thrust_speed=None, thrust_prop=None, keel=None):
    c = []
    def fill(x1, y1, z1, x2, y2, z2, block): c.append(f"fill {cx+x1} {d+y1} {cz+z1} {cx+x2} {d+y2} {cz+z2} {block}")
    def put(x, y, z, block): c.append(f"setblock {cx+x} {d+y} {cz+z} {block}")
    lever = lambda on, facing='west': f"minecraft:lever[face=floor,facing={facing},powered={'true' if on else 'false'}]"
    fill(-5, 0, -2, 5, 0, 2, "minecraft:spruce_planks")                    # deck, 11 long, 5 wide
    if keel: fill(-2, -1, 0, 2, -1, 0, f"minecraft:{keel}")                 # ballast: keeps her upright under thrust
    for x in (-1, 1):
        for z in (-1, 1): fill(x, 1, z, x, 2, z, "minecraft:spruce_fence")   # posts under the balloon
    fill(-4, 3, -4, 4, 9, 4, "aeronautics:white_envelope hollow")          # balloon
    fill(-3, 3, -3, 3, 3, 3, "minecraft:air")                              # open underneath
    fill(-4, 3, -1, 4, 3, -1, "minecraft:spruce_planks")                   # beams the posts hold
    fill(-4, 3, 1, 4, 3, 1, "minecraft:spruce_planks")
    put(0, 1, 0, "aeronautics:adjustable_burner")
    put(0, 1, 1, lever(burner, 'south'))                                   # burner: lift
    # stern: motor -> directional gearshift -> propeller, pushing along x
    put(3, 1, 0, f"create:creative_motor[facing={motor_facing}]{{ScrollValue:{thrust_speed or speed}}}")
    put(4, 1, 0, {'directional': "simulated:directional_gearshift[facing=north,axis_along_first=true]", 'shaft': "create:shaft[axis=x]"}.get(gear, gear))
    put(5, 1, 0, f"aeronautics:{thrust_prop or prop}[facing={thrust_facing}]")
    put(4, 1, -1, lever(thrust == 'a', 'north'))
    put(4, 1, 1, lever(thrust == 'b', 'south'))
    # bow: the same turned sideways, pushing along z far from the middle: turns the ship
    put(-4, 1, 0, f"create:creative_motor[facing=south]{{ScrollValue:{speed}}}")
    put(-4, 1, 1, "simulated:directional_gearshift[facing=west,axis_along_first=false]")
    put(-4, 1, 2, f"aeronautics:{prop}[facing={yaw_facing}]")
    put(-5, 1, 1, lever(yaw == 'a', 'west'))
    put(-3, 1, 1, lever(yaw == 'b', 'east'))
    if signs:
        def sign(x, y, z, rotation, *lines):
            text = ",".join("'{\"text\":\"%s\"}'" % l for l in (list(lines) + ["", "", "", ""])[:4])
            put(x, y, z, f"minecraft:oak_sign[rotation={rotation}]{{front_text:{{messages:[{text}]}}}}")
        sign(3, 1, -1, 4, "前进", "拉下此侧拉杆")          # rotation 4 faces west, towards the bow
        sign(3, 1, 1, 4, "后退", "拉下此侧拉杆")
        sign(0, 1, 2, 8, "燃烧器", "拉下=上升", "抬起=下降")  # 8 faces north, towards the middle of the deck
        sign(-5, 1, 2, 8, "右转")
        sign(-3, 1, 2, 8, "左转")
        for x in range(-5, 6):
            for z in (-2, 2):
                if (x, z) not in ((0, 2), (-5, 2), (-4, 2), (-3, 2)): put(x, 1, z, "minecraft:spruce_fence")
    return c

if __name__ == '__main__':
    cx, d, cz = map(int, sys.argv[1:4]); kw = {}
    for a in sys.argv[4:]:
        k, v = a.split('='); kw[k] = {'True': True, 'False': False, 'None': None}.get(v, int(v) if v.lstrip('-').isdigit() else v)
    print("\n".join(build(cx, d, cz, **kw)))
