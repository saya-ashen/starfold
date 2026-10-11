# B6: measured small survival ship (dev.71)

**185 placed blocks, Sable mass 312.5.** First passing design in the tested A/B
sequence, **not a proof of the global minimum**. It is a heavy 5×5 platform on
six ballast layers, not a sleek cabin. No boosters. Measurements and failures:
`build/space/blueprint1.md`. The 5×7 C fallback was not needed.

## Orientation and layers

Coordinates are block offsets from the centre of the walking deck. Forward is
**west (−X)**. Each drawing reads X=−2…+2 left to right and Z=−2…+2 top to bottom.
Y=0 is the deck; the bottom is Y=−6. Dots are **air: keep them clear**.

```
Y = -6, -5, -4, -2, -1       Y = -3 (engine/ballast layer)
CCCCC                        CnCnC
CCCCC                        wCCCe
CCCCC                        CCCCC
CCCCC                        wCCCe
CCCCC                        CsCsC

Y = 0                       Y = 1           Y = 2
PPAPP                       ..d..           ..d..
PPvPP                       .....           .....
P^P^P                       G.H.G           l.N.l
PPvPP                       .....           .....
PPAPP                       ..d..           ..d..
```

- `C`: full cobblestone block; `P`: oak plank.
- `n/s/w/e`: thruster nozzle facing north/south/west/east (push is opposite).
- `v`: nozzle down, for lift; `^`: nozzle up, for descent.
- `G`: gyro; `l`: floor-mounted lever on that gyro, **switched on**.
- `H`: helm facing west. `N`: **navigation scope**, facing west.
- `A`: airstair facing outward: north in the top row, south in the bottom row.
- `d`: lower/upper halves of an oak door, facing outward above its airstair.
  Open before walking. Keep both outside approaches empty, including below them.

## Parts

| Part | Count | Sable mass each | Subtotal |
|---|---:|---:|---:|
| Full cobblestone | 142 | 2 | 284 |
| Oak planks | 19 | 0.5 | 9.5 |
| Thrusters | 12 (two of each nozzle direction) | 1 | 12 |
| Gyros | 2 | 1 | 2 |
| Powered levers | 2 | 0 | 0 |
| Helm | 1 | 1 | 1 |
| Navigation scope | 1 | 1 | 1 |
| Airstairs | 2 | 1 | 2 |
| Oak doors | 2 items / 4 placed halves | 0.25 per half | 1 |
| **Total** | **185 placed blocks** | | **312.5** |

Also bring the guide's Super Glue, Physics Assembler and pickaxe. They are tools,
not permanent flight ballast. All permanent parts use vanilla materials or the
existing recipes exercised in `guide1.md` (including the nav-scope upgrade).
No iron-block ballast, precision scope, booster, fuel or inaccessible block.

## Hand build

1. On a clear level site, build the five plain cobblestone layers and the engine
   layer in the order shown from bottom up. Aim the eight horizontal nozzles
   outward. Leave support/scaffolding outside the final glue box.
2. Lay the deck, with the four vertical nozzles and two airstair sills replacing
   planks. Add the two gyros, helm, navigation scope and doors as drawn.
3. Temporarily place the Physics Assembler in a free deck cell, e.g. `(1,1,1)`.
   Glue the **whole** hull from an unobstructed bottom corner to the opposite
   highest corner: include door tops, scope and levers, but no terrain/scaffold.
   Use the assembler's normal lever interaction. Mine/recover the temporary
   assembler before flight so the final mass/layout matches the tested hull.
   This temporary-assembler workflow is a build instruction, not a separately
   timed survival assembly acceptance run in this experiment.
4. Switch on both gyro levers. Check all 12 nozzles were captured. Enter the helm
   by aiming at its **lower console**, not the scope immediately above it.
5. Test low before travelling. Space rises; Shift descends; release early. Let
   go of W to brake in a planet chart. R leaves the helm. Do not leave in space.
6. The deck is about seven blocks above the ground after landing. Wait for both
   ramps to deploy. Walk through the open door slightly away from its hinge;
   the automated centre-line walk caught the open door edge, but a 0.15-block
   lateral correction walked out and back without jumping or damage.

## Why this shape

The horizontal engines are down in the ballast, not on top of the deck. The
measured mass centre is 3.2624 blocks below the deck-block centre; the engine
plane is 3 blocks below it (residual vertical offset 0.2624). Mirrored engines
cancel sideways force while yawing. Two powered gyros damp rotation, but do not
multiply linear braking. Removing ballast or changing engine height invalidates
the handling measurements. A single extra plank floor is nowhere near enough.

On the planet-chart test: a 20-tick Space hold rose/coasted **14.09 blocks total**,
including **11.22 after release**, with sampled tilt **0.41° or less**. Ten-second
hover drift was **0.006 blocks**. Anticipatory yaw release at about70° settled at
**89.06°**, with no resolved lateral translation. Five-second W/release stopped
within **5.9 blocks**, heading change **2.72°**. Survival Shift descent from
**39.74 blocks** stayed upright, HP20→20. Walking down/up the deployed ramp also
preserved HP20. These are chart tests, **not an interplanetary-cruise qualification**.

## Reproduce

`python3 tools/ship/minimal.py 5292 147 862` prints the winning placement list.
`tools/probe/minimal-build.txt` is the disposable-world build/assembly fixture.
The generator retains A, B layer-count variants and the **untested** C fallback
for comparison; only default B6 is recommended from this experiment.
