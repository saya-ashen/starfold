# Datapacks: planet types, universes and star systems

[简体中文](DATAPACKS_zh-CN.md)

A datapack can give Starfold new kinds of planet, change how the universe beyond the home system is laid out, and
write a whole star system by hand: where each sun stands, which planets circle it, how large they are and how they
turn. This page is the reference for those files, for Minecraft 1.21.1 on NeoForge.

A pack sets up how planets are **generated**. It cannot add a new terrain algorithm, and it does not put a new
planet into a world that already exists: what a world generates is saved with the world when it is created (see
[What a saved world keeps](#what-a-saved-world-keeps)). Back up a world before changing the packs it uses.

## Where the files go

| Content | Path in the pack | Referred to as |
|---|---|---|
| Planet type | `data/<namespace>/starfold/planet_type/<name>.json` | `demo:ash` |
| Universe layout | `data/<namespace>/starfold/universe/<name>.json` | `demo:ash_universe` |
| Star system | `data/<namespace>/starfold/system/<name>.json` | `handwritten:twin_lanterns` |
| World preset | `data/<namespace>/worldgen/world_preset/<name>.json` | `demo:my_world` |
| World types listed on the creation screen | `data/minecraft/tags/worldgen/world_preset/normal.json` | `{"replace": false, "values": ["demo:my_world"]}` |
| Structure sets allowed on planets | `data/starfold/tags/worldgen/structure_set/planets.json` | add your structure set ids with `"replace": false` |

The folder after your namespace is always **`starfold`** for types, layouts and systems; only the part right after
`data/` is your own namespace.

Defining a type does not put it in any world. Something has to name it: a layout's `types`, a star system's
`suns[].planets[].type`, or a planet entry of a world preset. In the same way a new layout is used only by a preset
or a system that names it.

## Planet types

All ranges include both ends. "Required" means there is no default. Biomes and blocks are given by id.

| Field | Type and range | If omitted | Meaning and limits |
|---|---|---|---|
| `biome` | biome id | required | The planet's base biome. Rocky planets use it everywhere; ecology planets also use the four roles in `biomes` |
| `size` | object | required | Range of the width of one face in blocks. This is not a diameter or a radius |
| `size.min` / `size.max` | integers, multiples of 16, min ≤ max | both required | **416–2048**. (Sizes from 256 are accepted as a shape, but below 416 a planet cannot hold the smallest depth of 191 blocks) |
| `depth_ratio` | number, 0–0.75 | `0.5` | Depth as a share of the radius; the result is rounded and clamped, see below |
| `algorithm` | `rocky`, `rocky_craters` or `ecology` | `rocky_craters` | Two rocky algorithms or the living one. The list is fixed; a pack cannot add an algorithm |
| `relief` | number, 0.25–2 | `1` | How rough rocky terrain is. Must be 1 for `ecology` |
| `ore_bias` | number, -0.1–0.1 | rocky `0.035`, ecology `0` | Larger means richer ore veins on rocky planets. Must be 0 for `ecology` |
| `cave_width` | number, 0.03–0.2 | rocky `0.085`, ecology `0.115` | Width of caves. Must be 0.115 for `ecology` |
| `shell` | `bedrock` or `core_shell` | `core_shell` | The block of the lowest layer: bedrock or the mod's core shell |
| `palette` | object, see below | stone, basalt, andesite, deepslate | Only `rocky_craters` may use a palette other than the default |
| `biomes` | object with four roles | none | **Required for `ecology`, and only allowed there** |
| `biomes.forest` | biome id | required with `biomes` | Must be `minecraft:forest` |
| `biomes.plains` | biome id | required with `biomes` | Must be `minecraft:plains` |
| `biomes.desert` | biome id | required with `biomes` | Must be `minecraft:desert` |
| `biomes.ocean` | biome id | required with `biomes` | Must be `minecraft:ocean` |
| `minerals` | object, see below | two empty lists | `rocky_craters` and `ecology` may set them; `rocky` must leave them empty |
| `climate` | object, see below | mild | Only `ecology` may use a climate other than the default |
| `ocean` | number, 0–0.95 | not set | Share of the surface that is sea. `ecology` only, and `climate.land` must then be 0. The share is sampled once for the planet's seed and turned into a land lift; it is an aim, not an exact count of blocks |
| `creatures` | boolean | ecology `true`, rocky `false` | Whether the biomes' animals and monsters appear. It does not define a list of creatures |
| `features` | boolean | ecology `true`, rocky `false` | Whether the biomes' vanilla features are placed. The mod's own terrain, caves and trees are not switched off by it |
| `structures` | boolean | ecology `true`, rocky `false` | Whether structures start on the planet, still subject to the structure set tag and to biomes |

**The four ecology roles cannot be other biomes.** Each must be exactly the vanilla id shown; a custom forest or a
desert in the forest's place is refused. The `biome` field has no such limit. Changing what those vanilla biomes
contain is a different thing from naming other biomes, and is allowed.

**Size and depth.** A planet's size is picked between `min` and `max` in steps of 16 from its seed. Its radius is
`size × 2 / π`. Its depth is `round(radius × depth_ratio)`, kept within `[191, floor(radius × 0.75)]`: a ratio of 0
still gives 191 blocks, and a 512-block rocky planet has a depth of 191, not half its radius. The lowest layer is at
Y `127 - depth`; nothing of the planet is generated below it.

The dimension has to be tall enough for the depth. The built-in dimension type `starfold:native_deep` runs from
Y -528 to 320, and all built-in types and the examples here fit in it. A deeper planet needs your own dimension type
covering its lowest layer, named in the preset. That combination has not been tried in game.

### Palette

If `palette` is given, its first four fields are all required. A block is given by id and used in its default
state. Air and blocks that hold a fluid are refused.

| Field | Type | Default palette | Meaning |
|---|---|---|---|
| `rock` | block id | `minecraft:stone` | The main rock |
| `dark` | block id | `minecraft:basalt` | Rock of the dark regions |
| `light` | block id | `minecraft:andesite` | Rock of the light regions |
| `deep` | block id | `minecraft:deepslate` | Rock of the depths |
| `cover` | block id, optional | none | A layer covering the surface |

### Minerals

`ores` and `strata` may each be left out. **An empty list means "use what the algorithm has by itself", not "no
ores" or "no strata".** Air and blocks that hold a fluid are refused.

| Field | Type and range | Default | Meaning |
|---|---|---|---|
| `ores` | list of 0–16 objects | `[]` | The planet's ores. Where two overlap, the earlier one wins |
| `ores[].block` | block id | required | The ore |
| `ores[].deep` | block id | same as `block` | Its form in the depths: below the start of the second stratum if there are at least two strata, otherwise below about two thirds of the depth |
| `ores[].from` | number, 0–1 | `0` | Where the ore begins, as a share of the depth |
| `ores[].to` | number, 0–1 | `1` | Where it ends |
| `ores[].rarity` | number, 0.5–0.999 | required | The closer to 1, the rarer |
| `strata` | list of 0–8 objects | `[]` | Layers of rock from the surface down |
| `strata[].block` | block id | required | The layer's rock |
| `strata[].from` | number, 0–1 | `0` | Where the layer begins; it runs to the next one. The first also covers everything above the nominal surface |

A share of 0 is the nominal surface and 1 is the lowest layer. Shares are rounded to whole layers of a 191-block
scale and then stretched to the planet's depth, so they are not exact. An ore's `from` must not be greater than its
`to` after rounding. The first stratum must begin at 0, and each later one strictly deeper; two strata that round to the
same layer are refused. Leave clear gaps between the shares.

### Climate

Every field may be left out.

| Field | Type and range | Default | Meaning |
|---|---|---|---|
| `humidity` | number, -0.5–0.5 | `0` | Above 0 leans towards forest, below towards dry land |
| `temperature` | number, -0.5–0.5 | `0` | Above 0 turns more of the dry land into desert |
| `latitude` | number, 0–1 | `0.15` | How much colder the poles are than the equator; 0 ignores latitude |
| `land` | number, -40–40 | `0` | Blocks by which the land is lifted against the sea; above 0 means less sea |

With `ocean` set, the land lift is worked out from it and saved; `ocean: 0` gives the largest lift of 40.

## Universe layouts

A layout describes the suns and planets **beyond** the system a preset or a star system writes out.

| Field | Type and range | Default | Meaning |
|---|---|---|---|
| `stars` | object | `{"min": 0, "max": 0}` | How many further suns a finite universe has |
| `stars.min` / `stars.max` | integers, 0 ≤ min ≤ max ≤ 64 | both required with `stars` | The home sun is not counted |
| `planets` | object | required | How many planets each further sun has |
| `planets.min` / `planets.max` | integers, 1 ≤ min ≤ max ≤ 8 | both required | A sun's planets appear together or not at all |
| `star_gap` | number, see the rule below, at most 1000000 | `49152` | Spacing of the grid the suns are placed on; not the exact distance between two suns |
| `ring` | number, at least 8192 | `12288` | Distance between the orbits of one sun's neighbouring planets |
| `types` | list of at least one object | required | The planet types to draw from |
| `types[].type` | planet type id, or a whole type written in place | required | |
| `types[].weight` | integer, 1–1000 | `1` | Relative chance of being drawn, not a count |
| `density` | number, 0–1 | `0` | Above 0 the universe is found as it is travelled, each grid cell holding a sun with this chance; `stars.max` must then be 0 |

**Distance rule.** `star_gap ≥ 4 × (8192 + (planets.max - 1) × ring) + 16384`. With `planets.max` 2 and `ring`
12288 the gap must be at least 98304: **the default gap does not fit every range of planets.**

**Two modes.** With `density` 0 the universe is finite: all further suns are decided when the world is made, the
nearest grid cells first. With `density` above 0 the eight cells round the home system are looked at when the world
is made and further cells as a traveller comes near them; the home cell never gets a further sun. The same world
seed gives the same universe either way.

## World presets

A world preset is vanilla's: a `dimensions` object in which each dimension has a `type` and a `generator`. The
planets live in the dimension `starfold:native_planet`, whose generator has the type `starfold:native_system`:

| Field | Type and range | Default | Meaning |
|---|---|---|---|
| `storage_layout` | string | required | Write `six_face_square_v2` |
| `system` | star system id, or a whole system written in place | none | The hand-written system to use. Cannot be combined with `planets`, `stars` or `universe`; see [Star systems](#star-systems) |
| `planets` | list of 2–810000 planet entries | none | The home system's planets in storage order. The first must be written out in full, not by type |
| `stars` | list of positive integers adding up to the number of `planets` | one sun with all of them | How the `planets` are shared among suns; each sun has at least one |
| `universe` | layout id, or a whole layout written in place | no further universe | What lies beyond the `planets`. Only with `planets` |

Three more fields exist and are not for pack authors: `a` and `b` are the older way of writing exactly two
planets, and `celestial` is what the game writes when it saves a world.

With `planets`, suns stand in a row 49152 blocks apart with orbits 12288 blocks apart, which limits how many
planets a sun can hold. To place suns and orbits yourself, write a star system instead.

**A planet entry by type** (any entry but the first):

| Field | Type and range | Default | Meaning |
|---|---|---|---|
| `planet_type` | type id, or a whole type written in place | required | |
| `terrain_seed` | 64-bit integer | required | The planet's own seed; it is not taken from the world seed |
| `size` | integer in the type's range, a multiple of 16 | picked from the seed | Width of a face of this planet |

**A planet entry in full** (required for the first planet):

| Field | Type and range | Default | Meaning |
|---|---|---|---|
| `settings` | vanilla flat world settings | required | Carries the base biome. The planet's terrain is not these flat layers |
| `terrain_seed` | 64-bit integer | required | The planet's seed |
| `terrain_version` | `4` or `5` | required | 4 is a living (ecology) planet, 5 a rocky one |
| `planet_biomes` | object | none | Required for version 4 and not allowed otherwise: `terrain_seed` (the same as above) and the four roles `forest`, `plains`, `desert`, `ocean` |
| `planet_generation` | object, see below | by version | Required for version 5 |

`settings` is vanilla's format: `biome` (plains if left out), `layers` (required; each with a `height` and a
`block`), `features` and `lakes` (false if left out) and `structure_overrides`. The example below writes them out.
They do not stand in for the `features` and `structures` switches of `planet_generation`.

`planet_generation` holds the same choices as a planet type as plain numbers, and **its defaults differ from a
type's**:

| Field | Type and range | Default |
|---|---|---|
| `type` | `rocky` or `temperate` | required |
| `algorithm` | for `rocky`: `rocky` or `rocky_craters`; for `temperate`: `ecology` | required |
| `algorithm_version` | `1` | required |
| `relief` / `ore_bias` / `cave_width` | as for a type; `temperate` takes 1 / 0 / 0.115 | all required |
| `size` | integer, as for a type | `2048` |
| `depth` | integer, 191 to `floor(radius × 0.75)` | `191`; not worked out from a ratio |
| `shell` | `bedrock` or `core_shell` | **`bedrock`** |
| `palette` / `minerals` / `climate` | as for a type | the defaults |
| `creatures` | boolean | **`true`**, whatever the algorithm |
| `features` / `structures` | boolean | **`false`**, whatever the algorithm |

Where it can, write later planets by type: the type's defaults are the ones meant for play.

## Built-in types and presets

All ids begin with `starfold:`.

| Type | Face width | `depth_ratio` | Algorithm | What sets it apart |
|---|---:|---:|---|---|
| `rocky` | 512–1536 | 0.5 | `rocky_craters` | Stony peaks; relief 1.2, ore bias 0.04, cave width 0.09 |
| `desert` | 512–2048 | 0.5 | `rocky_craters` | Desert; 0.8 / 0.03 / 0.08; a cover of sand over sandstone, terracotta, stone and deepslate; five ores of its own |
| `ice` | 448–1024 | **0.4** | `rocky_craters` | Snowy plains; 1.4 / 0.02 / 0.1; packed ice, blue ice and snow; four strata with lapis, iron, diamond and emerald |
| `temperate` | 1024–2048 | 0.5 | `ecology` | The four vanilla roles, mild climate, no set share of ocean |
| `lush` | 1024–2048 | 0.5 | `ecology` | humidity 0.22, temperature -0.1, latitude 0.1, ocean 0.55 |
| `arid` | 1024–2048 | 0.5 | `ecology` | humidity -0.35, temperature 0.05, latitude 0.5, ocean 0.2 |
| `archipelago` | 1024–2048 | 0.5 | `ecology` | humidity 0.15, temperature 0, latitude 0.15, ocean 0.88 |
| `steppe` | 1024–2048 | 0.5 | `ecology` | humidity -0.3, temperature 0.1, latitude 0.4, ocean 0 |

The five ecology types have plains as their base biome. All eight have a core shell. The three rocky ones have
creatures, features and structures off; the five ecology ones have them on.

The layout `starfold:default` draws those types in that order with the weights 3, 2, 2, 2, 1, 1, 1, 1, with
`density` 0.7, `star_gap` 98304, `ring` 12288 and one or two planets to a sun. `starfold:small` uses the same types
and weights in a finite universe of two to four further suns.

The world preset `starfold:native_universe` ("Starfold: Generated Universe") writes its two home planets out in full: A is a
living planet of size 2048 and depth 652, B a rocky one of size 1024 and depth 326. Beyond them it uses
`starfold:default`.

## Example 1: a new planet type in the generated universe

These four files are the whole pack. It adds the type `demo:ash` and the layout `demo:ash_universe`, and **replaces**
the preset `starfold:native_universe`: planet A stays as it was, B becomes an ash planet, and every further sun has
one ash planet. A preset cannot be added to; the file stands in for the mod's.

Turn the pack on in the datapack screen while **creating a new world**, and pick the world type "Starfold:
Generated Universe". The replaced preset is already listed on that screen, so the pack needs no tag. The pack's root holds
`pack.mcmeta` and `data`; in a zip, do not wrap them in another folder.

### `pack.mcmeta`

```json
{
  "pack": {
    "pack_format": 48,
    "description": "Starfold: an ash planet type (Minecraft 1.21.1)"
  }
}
```

### `data/demo/starfold/planet_type/ash.json`

```json
{
  "biome": "minecraft:stony_peaks",
  "size": {"min": 512, "max": 1024},
  "algorithm": "rocky_craters",
  "relief": 0.7,
  "palette": {
    "rock": "minecraft:tuff",
    "dark": "minecraft:basalt",
    "light": "minecraft:andesite",
    "deep": "minecraft:deepslate"
  }
}
```

### `data/demo/starfold/universe/ash_universe.json`

```json
{
  "density": 1.0,
  "planets": {"min": 1, "max": 1},
  "star_gap": 49152,
  "ring": 12288,
  "types": [{"type": "demo:ash", "weight": 1}]
}
```

### `data/starfold/worldgen/world_preset/native_universe.json`

```json
{
  "dimensions": {
    "minecraft:overworld": {
      "type": "minecraft:overworld",
      "generator": {
        "type": "minecraft:noise",
        "settings": "minecraft:overworld",
        "biome_source": {"type": "minecraft:multi_noise", "preset": "minecraft:overworld"}
      }
    },
    "minecraft:the_nether": {
      "type": "minecraft:the_nether",
      "generator": {
        "type": "minecraft:noise",
        "settings": "minecraft:nether",
        "biome_source": {"type": "minecraft:multi_noise", "preset": "minecraft:nether"}
      }
    },
    "minecraft:the_end": {
      "type": "minecraft:the_end",
      "generator": {
        "type": "minecraft:noise",
        "settings": "minecraft:end",
        "biome_source": {"type": "minecraft:the_end"}
      }
    },
    "starfold:native_planet": {
      "type": "starfold:native_deep",
      "generator": {
        "type": "starfold:native_system",
        "storage_layout": "six_face_square_v2",
        "planets": [
          {
            "settings": {
              "biome": "minecraft:plains",
              "features": false,
              "lakes": false,
              "layers": [{"height": 1, "block": "minecraft:bedrock"}],
              "structure_overrides": []
            },
            "terrain_seed": 20261001,
            "terrain_version": 4,
            "planet_generation": {
              "type": "temperate",
              "algorithm": "ecology",
              "algorithm_version": 1,
              "relief": 1,
              "ore_bias": 0,
              "cave_width": 0.115,
              "depth": 652,
              "shell": "core_shell",
              "features": true,
              "structures": true
            },
            "planet_biomes": {
              "terrain_seed": 20261001,
              "forest": "minecraft:forest",
              "plains": "minecraft:plains",
              "desert": "minecraft:desert",
              "ocean": "minecraft:ocean"
            }
          },
          {"planet_type": "demo:ash", "terrain_seed": 20261003, "size": 768}
        ],
        "universe": "demo:ash_universe"
      }
    }
  }
}
```

## Star systems

A star system file places suns and gives every planet its own circular orbit and spin. The generator of a world
preset names it with `"system": "<namespace>:<name>"`, together with `"storage_layout": "six_face_square_v2"`, and
then must not have `planets`, `stars` or `universe`. A universe beyond the system is named inside the system, as
`beyond`.

### Fields

Positions are **universe coordinates** in blocks. They are not the block coordinates shown on the debug screen,
and they are not measured from the first sun.

| Field | Type and range | If omitted |
|---|---|---|
| `suns` | list of at least one sun; 1–1600 planets in all | required |
| `suns[].position` | three numbers `[x, y, z]` in blocks; see rule 5 for how far | required |
| `suns[].radius` | number, 32–4096 blocks; at most 1024 for the home sun | required |
| `suns[].planets` | list of at least one planet | required |
| `suns[].planets[].type` | planet type id, or a whole type written in place | required; an unknown id stops the world from loading |
| `…size` | width of a face in blocks: in the type's range, a multiple of 16 | picked from the type and the planet's seed |
| `…seed` | 64-bit integer | derived from the world seed and the planet's place in the file |
| `…reach` | number, 512–2048 blocks above the surface; height where a ship leaves for space | `2048` |
| `…name` | 1–64 characters, not blank, no control characters | A, B, … Z, P26, P27 … by storage order |
| `…orbit` | object | required |
| `…orbit.radius` | number, 1–10000000 blocks, within the rules below | required |
| `…orbit.period` | number, 12000–1000000000 ticks (20 ticks to a second) | required |
| `…orbit.phase` | number, radians | `0` |
| `…spin` | object | **the planet does not turn** |
| `…spin.period` | number, 24000–1000000000 ticks | required with `spin` |
| `…spin.tilt` | number, -180–180 **degrees** | `0` |
| `…moons` | list of moons, with the same fields as a planet except `moons` | no moons |
| `home` | object, counting from 0 | `{"sun": 0, "planet": 0}` |
| `home.sun` / `home.planet` | a sun of the file and one of its planets | `0` each |
| `beyond` | layout id, or a whole layout written in place | **nothing beyond**: only the planets written here exist |

**Orbits** are circles in the horizontal plane of their sun: at tick `t` the angle is
`phase + 2π × t / period`, and the planet stands at `sun + [radius × sin(angle), 0, radius × cos(angle)]`.
Eccentric and tilted orbits are not supported. **Spin** is about the axis `[sin(tilt), cos(tilt), 0]`, starting
unturned at tick 0. A new world with a hand-written system starts its clock at tick 0.

**Seeds.** A planet without a `seed` gets one from the world seed and its index in the file, counting through the
suns in order and through each sun's planets. Another world seed therefore changes only the planets without a
`seed`, and changing `home` changes none. The seeds are saved with the world.

### Rules

1. **One planet is enough.** The home planet may be any planet of any sun. It obeys the same orbit and spacing
   rules as every other planet.
2. **Storage order is fixed.** The home planet is number 0; then the rest of the home sun's planets in the order
   written; then the other suns and their planets in the order written. Default names follow these numbers. Moons follow all the planets, in the order written through the suns, planets and their
   moon lists; adding moons changes no planet's slot.
3. **Planets keep their distance.** With `r = size × 2 / π` (taking the type's largest size where `size` is left
   out), the centres of two planets of one sun must never come closer than `r1 + r2 + reach1 + reach2 + 256`
   (`r1 + r2 + 4352` with default reach). For different periods
   that means the orbit radii must differ by at least that much. For equal periods the distance is fixed at
   `sqrt((R1 - R2)² + 4 × R1 × R2 × sin²(Δphase / 2))`. Two planets on one ring with different periods are always
   refused.
4. **Suns keep their distance.** An orbit's radius must exceed `sun radius + r + reach + 128`
   (`sun radius + r + 2176` with default reach). A sun's envelope is the largest
   `orbit radius + r + reach + 128` among its planets, and two suns must stand at least the sum of their envelopes
   apart, whatever the phases.
5. **Everything stays within reach.** The home sun stands at most 100000 blocks from the origin. For every sun,
   its distance from the origin plus its envelope is at most 200000 blocks, so that no planet lies where flight cannot
   take a player.
6. **At most 1600 planets**, including any that `beyond` adds. Suns from `beyond` stay clear of the envelope of
   every hand-written sun, and fewer are made where there is no room. With `beyond`, the outermost orbit it may
   create must not take longer than 1000000000 ticks:
   `home period × ((home radius + (beyond.planets.max - 1) × beyond.ring) / home radius)^1.5`. If it does, shorten
   the home period or reduce the ring or the number of planets. Its inner ring must also clear a generated sun
   and the largest default-reach surface: `home radius > 640 + 2048 × 2 / π + 2048 + 128`. Its `star_gap` must be at
   least `4 × (home radius + (beyond.planets.max - 1) × beyond.ring) + 16384`.
7. Sizes must pass the type's range and the depth rule, and the dimension type must hold the depth.

### Moons

A moon is another surface planet, with its own type, size, seed, name, orbit, spin and reach. Its orbit radius is
measured from its planet's centre, not the sun. The orbit plane stays in the region's axes; it does not turn with
its planet's spin. A moon cannot have moons, and `home` selects a planet, not a moon. Generated and discovered
systems make no moons.

A moon's orbit radius must be at least `planet r + planet reach + moon r + moon reach + 256`. Two moons of one
planet obey rule 3 with their own radii, periods and phases. For the sun-clearance, planet-spacing and envelope
rules, replace a planet's `r + reach` with the largest of its own `r + reach` and each moon's
`orbit radius + moon r + moon reach`. The family must fit throughout its orbit. The 1600 limit includes moons.
Discovery also reserves the whole family's envelope.

Seedless moons use the same seed derivation after all the planets, in written moon order. Adding a moon changes
no existing planet's seed. Moons are saved and generated like any other surface. For example:

```json
{
  "suns": [{
    "position": [0, 0, 0],
    "radius": 512,
    "planets": [{
      "type": "starfold:temperate", "size": 1024, "reach": 512,
      "orbit": {"radius": 30000, "period": 120000},
      "moons": [{
        "type": "starfold:rocky", "size": 512, "reach": 512, "name": "Moon",
        "orbit": {"radius": 5000, "period": 24000, "phase": 3.141592653589793}
      }]
    }]
  }]
}
```

A file that breaks a rule stops the world from being created, with a message naming the field or the sun and
planet. Nothing is generated first, and the game does not fall back to another system.

## Example 2: two suns and five planets

The pack is in the repository at `tools/datapacks/example_system/`. Its five files follow. It uses its own
namespace, replaces nothing of the mod's, and lists its world type on the creation screen through its own tag.

#### `pack.mcmeta`

```json
{
  "pack": {
    "pack_format": 48,
    "description": "Starfold: two hand-written suns and five planets"
  }
}
```

#### `data/handwritten/starfold/planet_type/ash.json`

```json
{
  "biome": "minecraft:stony_peaks",
  "size": {"min": 512, "max": 1024},
  "algorithm": "rocky_craters",
  "relief": 0.7,
  "palette": {
    "rock": "minecraft:tuff",
    "dark": "minecraft:basalt",
    "light": "minecraft:andesite",
    "deep": "minecraft:deepslate"
  }
}
```

#### `data/handwritten/starfold/system/twin_lanterns.json`

```json
{
  "suns": [
    {
      "position": [0, 0, 0],
      "radius": 512,
      "planets": [
        {"type": "starfold:temperate", "size": 2048, "seed": 20261001, "name": "Aster", "orbit": {"radius": 8192, "period": 24000, "phase": 0}, "spin": {"period": 48000, "tilt": 23.5}},
        {"type": "handwritten:ash", "size": 768, "name": "Cinder", "orbit": {"radius": 8192, "period": 24000, "phase": 3.141592653589793}},
        {"type": "starfold:ice", "name": "Rime", "orbit": {"radius": 17000, "period": 90000, "phase": 1.2}, "spin": {"period": 72000, "tilt": -12}}
      ]
    },
    {
      "position": [60000, 4000, -20000],
      "radius": 420,
      "planets": [
        {"type": "handwritten:ash", "size": 512, "orbit": {"radius": 9000, "period": 36000, "phase": 0.7}},
        {"type": "starfold:desert", "size": 1024, "name": "Dune", "orbit": {"radius": 18000, "period": 110000, "phase": 2.4}}
      ]
    }
  ],
  "home": {"sun": 0, "planet": 0}
}
```

#### `data/handwritten/worldgen/world_preset/twin_lanterns.json`

```json
{
  "dimensions": {
    "minecraft:overworld": {"type": "minecraft:overworld", "generator": {"type": "minecraft:noise", "settings": "minecraft:overworld", "biome_source": {"type": "minecraft:multi_noise", "preset": "minecraft:overworld"}}},
    "minecraft:the_nether": {"type": "minecraft:the_nether", "generator": {"type": "minecraft:noise", "settings": "minecraft:nether", "biome_source": {"type": "minecraft:multi_noise", "preset": "minecraft:nether"}}},
    "minecraft:the_end": {"type": "minecraft:the_end", "generator": {"type": "minecraft:noise", "settings": "minecraft:end", "biome_source": {"type": "minecraft:the_end"}}},
    "starfold:native_planet": {
      "type": "starfold:native_deep",
      "generator": {
        "type": "starfold:native_system",
        "storage_layout": "six_face_square_v2",
        "system": "handwritten:twin_lanterns"
      }
    }
  }
}
```

#### `data/minecraft/tags/worldgen/world_preset/normal.json`

```json
{"replace": false, "values": ["handwritten:twin_lanterns"]}
```

### Using it in a new world

- **Single player.** Create a new world, open the datapack screen, add the `example_system` folder (or a zip with
  `pack.mcmeta` and `data` at its root) and turn it on. Pick the world type the pack adds; without a language file
  of your own it is shown as `generator.handwritten.twin_lanterns`. You arrive on Aster.
- **Dedicated server.** With the server stopped, put the pack in `datapacks/example_system/` of a world folder
  that **has no `level.dat` yet**, and set `level-type=handwritten:twin_lanterns` and a `level-seed` in
  `server.properties`. The pack is turned on by itself. Do not change the `level-type` of an existing world to
  move it over.
- **A universe beyond.** Add `"beyond": "starfold:default"` at the top of the system file. The example leaves it
  out so that exactly two suns and five planets exist.

## What a saved world keeps

A world saves the planets it was created with. It does not save the whole pack.

1. **Planets are saved in full.** A planet written by type is turned into concrete numbers when the world is
   created, and those are saved. Changing the type's file later does not change that planet.
2. **So is the rule for what has not been found yet.** The layout and each of its types are saved with the world.
   Changing a layout file does not change what an existing world will find.
3. **So is a hand-written system**: suns, orbits, spins, names and the home planet. Changing or removing the
   system file moves nothing in an existing world.
4. **Found suns and planets stay found**, with their numbers and places; a world never renumbers them.
5. **Blocks, biomes and structures are still looked up by id.** If a pack that defines a biome, a block or a
   dimension type the world uses is removed, the world may fail to load. Changing a biome's features or the
   structure set tag can change what is generated from then on.
6. **Chunks already generated are never rewritten.**
7. **A preset only matters when a world is created.** It is not a way to update an existing world.

## Limits fixed in the code

- **Terrain.** The noise, caves, ecology and the mod's trees are code; a type chooses an algorithm and its
  parameters. Sea level is fixed, the sea is water, and rocky planets have no sea.
- **Vanilla features are filtered.** Lakes and vanilla ore features are skipped on every planet, and on ecology
  planets also features that place trees, since the mod grows its own. Structures start at least 8 chunks from a
  face's edge.
- **Generated universes have no per-body fields.** In a layout a sun's radius is drawn from 384–640 and its place
  jitters within its grid cell; planets take the home planet's orbit plane and a period that grows with the orbit
  radius. To set these, write a star system.
- **1600 planets can be found in one world**, the home system included. When a sun's planets no longer fit, that
  sun is not added, and the game shows no message about it.

## What has been tried in game

Everything on this page is checked by the mod's tests, which read the two example packs from this page and from
`tools/datapacks/`. In the game itself:

- **Tried, on a dedicated server:** a new world from Example 2. The player arrived on Aster; both suns and all five
  planets were where the file puts them; each planet was landed on, dug and built on. After the pack was removed
  the world loaded with every planet unchanged, and editing the system file changed new worlds only. An unknown
  type, orbits too close, a home planet without its partner and `system` combined with `planets` each stopped the
  server from starting.
- **Not tried:** creating either example's world from the single-player screen; a `home` other than the first
  planet; flying from one sun to the other without commands; finding a `beyond` universe in play; a planet deeper
  than the built-in dimension type; large structures from other mods; adding, changing or removing a second pack
  with `/reload`.
