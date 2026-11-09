# First-guess balance / integration notes

Ore/recipe values remain first guesses. Fuel history and round-2 repeat measurements
are recorded below; the small fixture is not a fully qualified transport or round trip.
Copied planet parameters outside `terrain.minerals.ores` are unchanged from the source.

- Every ore entry's `from`, `to` and `rarity` is a first guess. Depths are normalized
  shares of mineable depth (0 surface → 1 floor); larger rarity means less ore.
  Habitat iron/copper/coal/zinc start at 0; gold/redstone start deeper. Ore tables
  remain under the codec's 16-entry cap. Volcanic debris uses 0.985 rarity from 0.55.
- Nether-free bootstrap: a Blaze Burner costs three iron sheets, one coal block and
  one copper ingot. Rose Quartz costs glass plus eight redstone dust (same redstone
  cost as upstream); this enables electronics without granting off-world quartz ore.
  Each new shaped recipe yields one item; all pattern quantities are first guesses.
  Helm and gyro use Create electronics rather than osmium so the first launch stays
  before Mekanism. The basic scope uses glass/copper/iron sheet, avoiding the upstream
  spyglass's amethyst requirement before the crystal-planet trip. Navigation optics
  now cost that basic scope, one precision mechanism, one electron tube and two
  brass sheets: Create-only, before the first trip (chapter 4). Precision optics
  retain two advanced circuits and two fluorite gems; Mekanism still follows osmium.
- First live diesel fixture: Minimal-A derivative, mass 24.5, 12 thrusters, two
  gyros, basic scope, one Create tank. At old `impulse = 1000`, HUD read 8000 mB
  filled, 7996 before ascent, 7986 after 200 input ticks (hull clearance about 1496,
  server/client snapshot timing differs). Thus <=14 mB total to >1100 altitude,
  including pre-flight hover; only about 10 mB during that climb, not eight buckets.
- Historical round-1 correction (superseded by round 2): divide all active impulses by 500: diesel/gasoline **2**,
  fluid hydrogen **5.334**. Same paid impulse would cost about 7 buckets including
  that fixture's pre-flight hover, about 5 buckets for the climb itself; hydrogen
  keeps the 2.667 efficiency ratio. This is deliberately a conservative correction,
  not a claimed exact 8/3-bucket calibration. The reference transport hull is not
  yet specified. Both follow-up clients failed in NeoForge window handoff, so the
  new values, entry-to-space cost, full fuel exhaustion and return reserve remain
  **UNVERIFIED**. See the historical observations in `../README.md`; a new fuel probe is required for acceptance.
- Round 2 grounded fixture: mass 24.5, same 12 nozzles and one 8000 mB tank,
  navigation scope, platform contact, piloted/settled for 100 ticks. At impulse=2:
  8000 mB before launch; about 3847 mB at nav altitude ~1100 (3815 in the following
  NBT read); 2134 mB on first space screenshot, 1954 in the following NBT read.
  Entry therefore costs roughly 5.87–6.05 buckets, not eight. Lift-off/space
  advancements both fire. Braking exhausted the tank; further thrust demand for
  600 ticks left speed at ~534 blocks/s, with empty NBT and no acceleration.
- Round 2 correction: diesel/gasoline **1.55**, fluid hydrogen **4.13385**;
  preserve the 2.667 ratio. For the same ascent this predicts 7.57–7.80 buckets
  diesel (2.84–2.92 hydrogen). A 49-mass transport with proportional thrust
  provisionally needs ~15–16 diesel buckets, not eight; mass scaling is an
  estimate, not a second hull measurement. Repeat run at 1.55 used 8000 mB at
  rest, 2581 mB on the ~1100 screenshot (2560 in following NBT), and 447 mB
  at the space-transition screenshot (415 in following NBT): **7.55–7.59 buckets**.
  Fuel then exhausted during braking; with forward demanded, speed stayed ~201
  blocks/s across two samples 100 ticks apart. The repeat's final key-release hit
  the probe's strict penultimate-shot guard AFTER all measurements; the runner
  exited 1, not a gameplay failure. Added a final shot; no third fuel run claimed.
- Round 3 enables fluid `mekanismgenerators:deuterium` at **4.65 impulse**,
  a first guess of **3 × diesel per mB**, with `interstellar: true`. This predicts
  ~2.5 buckets for the mass-24.5 ascent, NOT a measured one-bucket tier-3 target.
  Diesel/gasoline/hydrogen remain non-interstellar. The experience layout has one sun, so
  every planet is inside home; only outside `homeModel().extent()` is ordinary fuel ineligible.
- One global `gas_skim/deuterium.json` uses **rate=10 mB/tick at pressure 0.5**
  (first guess). At 20 TPS, pressure .25 yields 100 mB/s: ~80 s for an 8-bucket
  tank, excluding approach, drift and damage. Pressure .5 starts damage for ordinary blocks;
  osmium pressure hulls now tolerate .85 (provisional), without protecting other blocks.
  Do not describe that pressure as safe for an entire mixed-material ship. All gas giants share this definition.
- Remove `mekanismgenerators:separator/heavy_water` by recipe ID, since its chemical
  output is not caught by an item-output filter. Pinned-JAR output scans assert
  that no other deuterium producer exists except reversible rotary conversion,
  which needs existing deuterium and cannot create a terrestrial source.
- The scoop costs a precision mechanism, two advanced circuits, a Create tank and
  a rotary condensentrator (all first guesses). This follows hydrogen machinery.
  Chapter 8 preserves existing IDs; scoop/bucket are item checks, while full tank
  and system departure are explicitly manual checkmarks, NOT location telemetry.
- Fuel schema `{fluid, impulse, interstellar?, mass?}` matches the merged loader.
  Unit is Sable force-seconds per mB.

## Fuel impulse, mass and Δv — 暂定初值（未实机平衡）

This section **supersedes all round-1/2/3 fuel values and ascent predictions above**.
Those measurements used different physics and a tiny fixture; they are history,
not acceptance under the rocket equation. Formal balance waits until the features
are complete. Every fuel value and travel budget below is provisional.

### Offline reference hull (actual structure, not the old generator mass total)

Read `extras/src/main/resources/data/starfold_extras/structure/starter_ship.nbt`
with the existing `tools/ship/pack/shipgen.py:read_nbt` parser. Ignore 8969 explicit
air entries. The 2347 remaining blocks, using pinned Sable 2.0.5 mass tiers plus
`src/main/resources/data/sable/tags/block/heavy.json`, sum as follows:

| Sable mass per block | Count | Subtotal |
| ---: | ---: | ---: |
| 0 (5 levers, 4 wall signs; non-solid) | 9 | 0 |
| 0.25 (doors, trapdoors, ladders, bars, redstone links) | 25 | 6.25 |
| 0.5 (slabs/stairs, chests, shaft) | 119 | 59.5 |
| 1 (other hull/machinery blocks) | 2135 | 2135 |
| 2 (58 Starfold components + 1 stone) | 59 | 118 |
| **Total** | **2347** | **2318.75** |

The 58 heavy components are 46 thrusters, 2 gyros, 2 boosters, 2 scopes, 4 airstairs,
1 helm and 1 aircraft dock. The generator's old **2260.75** ignores these 58 extra
mass units; do not reuse it as the current dry mass. Slab volume is not another
mass multiplier: Sable's mass and volume properties are separate. There are no
double slabs, heavy storage blocks or material-module hull panels in this palette.
The optional weapon overlay changes dry mass to **2335.5**.

**There is no fluid tank in the shipped starter structure.** The survival pack
also deliberately omits Extras/the supplied starter ship. Thus “fill its existing
tank” is not literally possible. For the requested starter-sized reference only,
assume one **additional** ordinary Create tank (default capacity **8000 mB**,
block mass **1**), giving dry mass **2319.75** (armed: **2336.5**). This task does
not modify the structure, grant fuel or enable Extras. Cargo and attached aircraft
are excluded; larger payloads lower Δv.

### Provisional fuel table and calculation

`mass` is Sable mass per **1000 mB**, not per mB; impulse is force-seconds per mB.
For one fuel: `m_f = V × mass / 1000`, `v_e = 1000 × impulse / mass`,
`Δv = v_e × ln(1 + m_f / m_dry)`.

| Fluid | impulse (暂定) | mass / 1000 mB (暂定) | v_e, blocks/s (暂定) | 8000 mB Δv, blocks/s (暂定) |
| --- | ---: | ---: | ---: | ---: |
| Diesel | 940 | 47 | 20000 | 3004.34 |
| Gasoline | 940 | 47 | 20000 | 3004.34 |
| Hydrogen | 1880 | 47 | 40000 | 6008.68 |
| Deuterium | 23500 | 47 | 500000 | 75108.52 |

Choose a provisional **10×** scale of the old exhaust speeds, preserving exactly
`2000:4000:50000 = 1:2:25`, not their absolute magnitudes. Holding v_e=2000 would
require `2319.75 × (exp(3000/2000) - 1) ≈ 8076.65` fuel mass; ten ordinary lift
nozzles could not lift that wet ship. Instead choose equal provisional density
**47** for every tier, making both per-volume impulse and equal-volume Δv exactly
**1:2:25**, with identical launch weight. These are gameplay units, not real densities.

At v_e=20000 the exact 3000 target requires `m_f ≈ 375.41` (mass/bucket ≈46.93).
Round to **47**, hence `impulse = 20000 × 47 / 1000 = 940` and `m_f=376`:
`Δv = 20000 × ln(2695.75 / 2319.75) = 3004.34 blocks/s`.
Fuel adds **16.21%** to dry mass (**13.95%** of wet mass), so its mass, COM and
inertia remain meaningful without preventing ordinary takeoff. Armed reference
with the same tank gets **2984.33 blocks/s**. A nominal four-burn trip at **500
blocks/s per burn** consumes 2000 Δv, reserving roughly 1000 before planetary
launch/landing costs. This is a budget example, not a proven round trip to Fleck;
relative orbital motion, guidance, payload and piloting still matter. Δv is total
maneuver reserve, not speed: tier-3 reserve can exceed the separate 20000 blocks/s
technical speed limit. Boost changes thrust/burn rate, not total Δv.

### Provisional planetary ascent estimate (not game evidence)

`ThrusterFuel.draw` still pays **raw impulse** in planet charts. The reference has
**10** upward nozzles at **3350** each: full ordinary lift **33500**, boosted
**134000**. With wet mass **2695.75**, home surface hull gravity
`11 × 0.75 = 8.25`, weight is **22239.94**, giving ordinary lift/weight **1.51**
and initial net acceleration **4.18 blocks/s²**. It can lift without boost.
Initial full-throttle diesel draw is `33500/940 = 35.64 mB/s`; surface hover is
`22239.94/940 = 23.66 mB/s`. Five seconds of airborne waiting costs about **118 mB**,
not zero. Grounded resting nozzles do not hover-burn.

Reproducible 1-D estimate is in `tools/modpack/check-modpack.py`: start at nominal
surface, rest, level hull, no horizontal thrust; integrate dt=**0.0125 s**, update
fuel mass continuously, `g(h)=8.25 × (1-smoothstep(1024,3072,h))`,
`F=clamp(m × (g+4 × (v_target-v)),0,33500 × boost)`,
`v_next=(v+(F/m-g)dt)/(1+0.09dt)`, `h_next=h+v_next dt`, `fuel-=Fdt/940`.
These inputs follow `LiftControl`, `HullWeight`, default Sable gravity/linear drag
and default home reach **2048** (`hullOut`); targets are **160 / 640 blocks/s**.
The estimate omits native PRE/POST displacement splitting, 20-tick mass snapshots,
terrain/surface offsets, rotation, contact, server timing and pilot delay.

| Mode (暂定估算) | Time to chart h=2048 | Diesel spent | Tank spent | Remaining ideal Δv |
| --- | ---: | ---: | ---: | ---: |
| Ordinary climb | 51.40 s | 1832 mB | 22.90% | 2355 blocks/s |
| Boosted climb | 11.58 s | 1650 mB | 20.63% | 2421 blocks/s |

Thus the offline estimate spends about one fifth to one quarter, not the whole
tank and not a negligible amount, leaving over **6000 mB** for maneuvering.
A five-second surface hover allowance plus ordinary ascent leaves about **2313
blocks/s** ideal reserve, before destination landing/relaunch. Moon return and
landing costs are **not qualified** by this estimate. Do not scale the old
mass-24.5 measurement linearly and call it current flight evidence.

Fuel mass sampling remains every 20 ticks by tank-position deltas. Current HUD
Δv follows usable fuels in actual burn order, including prepaid sub-mB credit;
input-only/ineligible fuel remains payload, not burnable reserve. The ordinary/
interstellar eligibility and installed-drive gate are unchanged.

**主管实机待验：** add a real tank to the reference hull; compare dry/wet mass and
HUD Δv, ordinary/boosted takeoff and chart transition fuel, paid acceleration and
braking, empty-tank behavior, landing/relaunch, and a complete nearest-moon return.
No game, server or GUI was run for this provisional calculation.

## Interstellar drive — 暂定初值（未实机平衡）

- Plain core block/item `starfold:interstellar_drive`, no block entity, speed bonus
  or new mechanism. Sable's highest existing tier is `super_heavy` (mass **4** per
  full block), separate from the ten `heavy` components above. Default pressure
  limit remains **0.5**; this is not a pressure shield.
- Standalone recipe: four diamond blocks, two iron ingots, two redstone blocks and
  an ender eye yield one drive. Pack override: four osmium pressure hull blocks,
  four elite control circuits, one deuterium bucket and one Host Ganglion yield
  one drive via Create mechanical crafting (3×4). All costs and mass are provisional;
  the ganglion makes the Host encounter a required fabrication gate.
- Chapter 8 (the ninth chapter) adds fabrication after the manual tank-fill entry
  and before departure; quest ID `1000000000080A00` also depends on the Host kill.
  Chapter 7 retires IDs 0500–0700 and appends Flesh records at 0800–0B00;
  unrelated quest/task/reward IDs remain unchanged.
  Item possession does not prove installation; departure still needs manual checking.
- Blue-white ring/core and cyan side coils are static 16×16 pixel textures, not
  powered states or dynamic/emissive rendering. Supervisor must check appearance,
  recipe bucket remainder, installed/removed-drive HUD and thrust beyond home.

## Oil drilling compatibility

CDG's `oil_deposit` block tag now adds `starfold:core_shell` without replacing
its vanilla bedrock entry. Saved home chunks have a natural core-shell floor.
This fixes a static integration gap, **not** a live extraction acceptance: the
round-2 rig also had zero-speed motors and an offhand selection failure. Its
tanks remained empty; no command-injected crude or diesel is counted as output.

## Known progression gaps (do not claim enforced)

1. `minerals.ores` accepts only solid blocks. Diesel Generators' oil fields require
   testing/integration with the custom generator, not fake crude-oil ore placement.
2. Rocky/asteroid tables retain osmium, iron and rare deep diamonds:
   diamond_ore/deepslate_diamond_ore, from=.65, to=1, rarity=.99.
   Crystal gets a smaller diamond supply at rarity=.995 with the same depth range.
   All are first guesses, not measured yields; home/habitable tables have no diamonds.
3. `starfold:interstellar_drive` now supplies the hardware gate through
   `starfold:interstellar_drives`: outside home, thrust requires a drive on the
   same hull and interstellar deuterium. Host Ganglion gates the pack recipe;
   no separate runtime boss-permission flag is used.
   Neither this hardware/fuel rule nor the quest chain prohibits inertial coasting.
4. The experience layout has a single sun. First osmium travel is intra-system;
   it must remain possible before any later `beyond` gate.
5. The preset retains vanilla Overworld but removes Nether/End. Audit alternative
   processing recipes, mob drops and loot for resource-gate bypasses in play.
6. Biotech supplies Flesh and the Host encounter in its hive crater. The pack
   uses the Host kill and ganglion as the interstellar fabrication route; terrain,
   encounter completion, loot pickup and mechanical crafting need in-game acceptance.
7. Normal world-preset tag is replaced with only `starfold_worlds:experience`, making
   it the normal creation choice; confirm the create-world screen offline limitation
   in a later authorized game run. Dedicated servers need
   `level-type=starfold_worlds:experience` in their instance's `server.properties`.


## Hull materials / aluminum — 暂定初值（未实机平衡）

- `starfold_materials` 独立 jar 提供铝合金板和锇耐压壳及半砖；完整质量分别
  **0.5 / 4**，半砖 **0.25 / 2**，双半砖 **0.5 / 4**；耐压 **0.5 / 0.85**。
  质量、硬度、配方产率及耐压全部暂定，详见 `materials/BALANCE.md`。
- 配方初值：8铝锭→4铝板；5锇锭+4铁锭→4耐压壳；3完整板→6半砖。
  不提供整舰压力护盾；其他暴露船件仍按各自定义损坏。
- `starfold_materials` 仅注册铝的矿石、粗矿、锭、粒及存储块；不加世界生成。
  锇由 Mekanism 提供，船体配方继续使用 `c:` 通用锭标签。

| 星球类型 | 新增矿物 | from → to | rarity（越高越少） |
| --- | --- | --- | --- |
| desert（干旱/沙漠） | 铝土矿 | 0 → 1 | 0.90 |

使用 `starfold_materials:bauxite_ore` 与 `starfold_materials:deepslate_bauxite_ore`。
只有沙漠类星球矿表放置铝土矿，母星/宜居类不新增铝。矿表数值保持原暂定值；
粗铝/矿石熔炼及高炉冶炼产铝锭，存储采用原版式 9 合 1 / 1 拆 9。
详细采炼、挖掘和标签契约见 `materials/BALANCE.md`。
