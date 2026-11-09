# Datapacks: planet types, universes and star systems

[简体中文](DATAPACKS_zh-CN.md)

A datapack can give Starfold new kinds of planet, change how the universe beyond the home system is laid out, and
write a whole star system by hand: where each sun stands, which planets circle it, how large they are and how they
turn. This page is the reference for those files, for Minecraft 1.21.1 on NeoForge.

A pack sets up how planets are **generated**. Registered Java terrain stages and rules can be selected by the pack. It cannot install new Java implementations, and it does not put a new
planet into a world that already exists: what a world generates is saved with the world when it is created (see
[What a saved world keeps](#what-a-saved-world-keeps)). Back up a world before changing the packs it uses.

## Experimental gas-giant branch (D1)

Gas giants are catalog bodies without terrain generators or landing slots. The development branch supports optional
`suns[].gas_giants`; existing saved catalogs do not gain bodies merely by reloading a pack. The experience preset
still does **not** enable one: maximum-depth enforcement and bounded real-game acceptance remain pending.

Pressure is a conservative **whole-hull estimate**, including loaded mounted contraption extents, independent of
navigation lock. The HUD uses server samples, not camera pressure; unknown/expired samples are not safe zero.
Its 40-tick receipt budget includes server-reported sample age, but cannot measure network transit delay.
At pressure >=50%, loaded ships can lose eligible structural blocks through normal server mutation. V1 deliberately
excludes block entities/inventories, machinery/custom subclasses, fluids, unbreakable/non-full blocks and mounted
contraption contents. Only exact simple block/pillar/glass classes are eligible. Missing loaded same-plot neighbors
prevent removal rather than causing hidden chunk allocation. Recent successful-write intervals survive hull sleep
and save/reload; there is no HP/exposure counter or offline damage simulation. Direct removals are capped at one
per hull per interval and two per server tick; native splitting/floating aftermath is not a total block-loss bound.
Use disposable world/ship copies for this unfinished feature; this paragraph is not runtime or release acceptance.

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

A type contains `shape`, `terrain`, `life`, and `environment`. All 13 types use the same fixed-plan and point-evaluation pipeline. Old `algorithm`, scalar `relief`, `ocean`, legacy climate fields, `life.biomes`, `life.regions`, `palette`, and `minerals` formats have been removed. Start a new world.

`shape.size.min/max` are face widths, multiples of 16 in 416–2048. Depth is `round(size × 2 / π × depth_ratio)`, clamped to `[191, floor(radius × .75)]`; `depth_ratio` is 0–.75 and `shell` is bedrock/core_shell. `life.biome` is the fallback outside valid charts. `creatures/features/structures` control mobs, BiomeScatter vegetation, and fixed structures; all default to true. Dry types should explicitly disable unwanted population switches.

`environment` supplies atmosphere, gravity and temperature offset. Its default atmosphere is Earth-like; gravity resolves from size. Recipes are fixed when a planet is materialized. The saved plan includes the complete recipe, fields and elements; reload affects future planets.

### Terrain recipe (version=2)

All fields below are required. Unknown fields, unregistered types, out-of-range values and unsupported versions fail decoding. Units are blocks, Celsius, or fractions.

| Field | Meaning and range |
|---|---|
| `planner.stages` | Ordered registered stage IDs; built-ins are starfold:plates, landforms, erosion, climate, biomes, volumes |
| `land_fraction` | 0–1 continental share |
| `relief` | mountains 0–175, hills 0–64, roughness 0–8; home starts at 160/24/1 |
| `landforms[]` | type + parameters, at most 64 groups; crater, volcano, canyon, glacial_rift, hive, dunes, plateau in the starfold namespace |
| `volumes[]` | At most 64 groups of registered overhang, arch, undercut, cave_mouth, sinkhole, floating_rock types and parameters |
| `erosion` | rivers boolean, strength 0–2, river_threshold 2–4096 contributing nodes |
| `climate` | temperature -200–200, latitude_gradient 0–100, humidity -1–1, lapse_rate 0–.1 |
| `biomes[]` | At most 64 registered rules; first match wins; every node must match an explicit rule |
| `materials[]` | At most 64 registered rules; first match wins, otherwise use strata |
| `strata[]` / `ores[]` | Up to 8 strata and 16 ores; from/to are 0–1 shares from nominal surface to floor; rarity .5–.999 |
| `caves` | width 0–.2, scale 4–128, global 3D noise |
| `water` | block is fluid-bearing or air, sea_level -1024–300, freeze boolean |
| `preferences` | desolation, damage, burial, overlap, road_continuity, density: 0–1; candidate probability, fixed effects, arrangements, road decisions and landform/volume density |

Hive also declares spacing (32–256) and wall_slope (.1–4); geometric neighbours are saved in the plan. Custom biome rules declare their output ID through `BiomeRule.biome(parameters)` without adopting built-in parameter names. Landform parameters: density 0–8, radius_min/max 16–256, amplitude 0–128, operation raise/lower. `starfold:climate` biome rule parameters: biome, temperature_min/max, humidity_min/max, height_min/max, river, coast. `starfold:terrain` biome rule parameters: biome, height_min/max (-1024–320), slope_min/max (0–200, rise per physical block), landform_min/max (-2048–2048, signed height contribution of all landforms), and optional region_min/max (0–1). Region is the global rank of a 192-block low-frequency spherical noise field combined with elevation, slope and landform relief; thresholds therefore control area and keep broad contiguous patches. For example, desert scrub uses region_min=0, region_max=.35, with an unrestricted barren final rule. Shipped regional types use 25–35% secondary regions. Biome elevation uses the generated surface ceiling of 318; slope and landform relief retain the planning geometry. Dry types can select crater floors, rims, rifts and volcanic slopes without relying on humidity. Tables require an explicit unrestricted final rule when earlier rules do not cover the entire surface; uncovered nodes fail planning. `starfold:surface` material parameters: block, depth_min/max, temperature_min/max, slope_min, shore, biome (empty string matches all).

Volume parameters: density (0–8, multiplied by preferences.density), radius/height (2–64), offset (-64–96), operation (fill/carve), slope_min/max (0–200), cave_connection. Overhang and undercut placement requires an uphill anchor inside the shape and exposed lower ground outward; saved wall_x/wall_z are a unit direction in the spherical tangent frame. Their placement callback derives this direction; floating rock is a separate type used only by recipes that declare it. Cave mouths and sinkholes search the actual underground double-noise caves before saving a connecting shape. Volumes apply in saved fill/carve order inside their bounds, then existing local water_level refills final air gaps while preserving filled solids. This is a column water-level rule without global connectivity simulation. Ice and snow follow the final exposed top before roads and structures override it. Chunks, surface queries and LOD read the same final columns and preserve air gaps.

The plan preserves structure composition order and permits overlap; later sites overwrite earlier sites. Bury moves the structure down and backfills underground template AIR with terrain material, replacing earlier site blocks at those positions. Damage saves a deterministic removal amount; cut saves explicit typed references. Each site's cut constrains its own adaptation before later sites run. Original templates and recipes remain intact; replay uses final columns and final ownership before restoring blocks and block entities. Relations refer to kind plus list index, or stable site.id for structures; each road has a connects relation to each endpoint settlement.

Roads use eight-neighbour minimum-cost shared-grid routes with physical slope cost and save control nodes, heights and GROUND/BRIDGE/CUT decisions. A shared spherical cubic gives continuous tangents through turns; planning crossings, actual road blocks and evaluation overlays use that curve. Crossing checks sample at no more than one physical block or half the narrowest river width, inspecting rivers, lakes/oceans, canyon profiles and carved volumes. Bridges use wooden decks; cut segments do not lay road material. Road continuity is a planning decision. Placement reads final surfaces including volumes and biome eligibility; the `starfold:river_settlements` structure tag prefers flat riversides, `starfold:highland_ruins` prefers high ground, and detached floating caps incur a support penalty. Desolation, burial, damage, overlap and density control candidate probability, burial probability/depth, removal amount, candidate spacing and landform/volume density.

Extension API: `TerrainRegistries.LANDFORMS/VOLUMES/MATERIALS/BIOMES.register(id, codec, implementation)` and `registerStage(id, stage)`. Built-ins use the same APIs. Shapes supply profile and placement; rules supply evaluation and planning callbacks. Evaluation composes fixed operations. Volume types provide a shape inside the normalized box [-1,1], a parameter Codec and placement callback. Evaluation uses a deterministic spherical tangent basis and the entire box bound, including noncircular shapes at seams.

Coarse surface sampling (levels 16, 32 and 64) reads four bounded field/feature summaries. The first SurfaceFunction constructor for each PlanetTerrain builds and shares these summaries, invalidated when its terrain/plan is rebound, using fixed four-point quadrature per 16-block cell, then mip reduction; it also rasterizes volume and structure bounds so off-centre roofs/caps remain covered. This one-time setup and its memory are separate from per-sample O(1) reads and from terrain-plan serialization. Coarse coverage is approximate; fine LOD and chunks retain exact multi-run columns. Core seasonal APIs expose the synchronized TerrainPlan through near and exterior render contexts. The optional seasonal appearance module is omitted from this public source snapshot pending its third-party provenance review.

Nodes are 16 blocks apart and share geometric edge/corner identities. Height combines bicubic fields, shapes, detail, river channels and existing structure adaptation. Priority-flood supplies lakes and drainage; BiomeScatter is the only vegetation layer and density is multiplied by humidity. Complete initial home recipe:

```json
{
  "version": 2,
  "planner": {
    "stages": [
      "starfold:plates",
      "starfold:landforms",
      "starfold:erosion",
      "starfold:climate",
      "starfold:biomes",
      "starfold:volumes"
    ]
  },
  "land_fraction": 0.4,
  "relief": {
    "mountains": 160,
    "hills": 24,
    "roughness": 1
  },
  "landforms": [],
  "erosion": {
    "rivers": true,
    "strength": 1,
    "river_threshold": 48
  },
  "climate": {
    "temperature": 27,
    "latitude_gradient": 47,
    "humidity": 0,
    "lapse_rate": 0.0065
  },
  "biomes": [
    {
      "type": "starfold:climate",
      "parameters": {
        "biome": "minecraft:ocean",
        "temperature_min": -300,
        "temperature_max": 300,
        "humidity_min": 0,
        "humidity_max": 1,
        "height_min": -1024,
        "height_max": 127,
        "river": false,
        "coast": false
      }
    },
    {
      "type": "starfold:climate",
      "parameters": {
        "biome": "minecraft:desert",
        "temperature_min": 20,
        "temperature_max": 300,
        "humidity_min": 0,
        "humidity_max": 0.4,
        "height_min": -1024,
        "height_max": 320,
        "river": false,
        "coast": false
      }
    },
    {
      "type": "starfold:climate",
      "parameters": {
        "biome": "minecraft:forest",
        "temperature_min": -15,
        "temperature_max": 30,
        "humidity_min": 0.5,
        "humidity_max": 1,
        "height_min": -1024,
        "height_max": 320,
        "river": false,
        "coast": false
      }
    },
    {
      "type": "starfold:climate",
      "parameters": {
        "biome": "minecraft:plains",
        "temperature_min": -300,
        "temperature_max": 300,
        "humidity_min": 0,
        "humidity_max": 1,
        "height_min": -1024,
        "height_max": 320,
        "river": false,
        "coast": false
      }
    }
  ],
  "materials": [
    {
      "type": "starfold:surface",
      "parameters": {
        "block": "minecraft:gravel",
        "depth_min": 0,
        "depth_max": 3,
        "temperature_min": -300,
        "temperature_max": 300,
        "slope_min": 0,
        "shore": true,
        "biome": ""
      }
    },
    {
      "type": "starfold:surface",
      "parameters": {
        "block": "minecraft:stone",
        "depth_min": 0,
        "depth_max": 3,
        "temperature_min": -300,
        "temperature_max": 300,
        "slope_min": 3,
        "shore": false,
        "biome": ""
      }
    },
    {
      "type": "starfold:surface",
      "parameters": {
        "block": "minecraft:sand",
        "depth_min": 0,
        "depth_max": 3,
        "temperature_min": -300,
        "temperature_max": 300,
        "slope_min": 0,
        "shore": false,
        "biome": "minecraft:desert"
      }
    },
    {
      "type": "starfold:surface",
      "parameters": {
        "block": "minecraft:grass_block",
        "depth_min": 0,
        "depth_max": 0,
        "temperature_min": -300,
        "temperature_max": 300,
        "slope_min": 0,
        "shore": false,
        "biome": ""
      }
    },
    {
      "type": "starfold:surface",
      "parameters": {
        "block": "minecraft:dirt",
        "depth_min": 1,
        "depth_max": 3,
        "temperature_min": -300,
        "temperature_max": 300,
        "slope_min": 0,
        "shore": false,
        "biome": ""
      }
    }
  ],
  "strata": [
    {
      "block": "minecraft:stone"
    }
  ],
  "ores": [
    {
      "block": "minecraft:coal_ore",
      "from": 0,
      "to": 0.6,
      "rarity": 0.82
    },
    {
      "block": "minecraft:iron_ore",
      "from": 0.15,
      "to": 1,
      "rarity": 0.84
    },
    {
      "block": "minecraft:diamond_ore",
      "from": 0.8,
      "to": 1,
      "rarity": 0.94
    },
    {
      "block": "minecraft:copper_ore",
      "from": 0,
      "to": 0.65,
      "rarity": 0.82
    },
    {
      "block": "minecraft:redstone_ore",
      "from": 0.55,
      "to": 1,
      "rarity": 0.84
    }
  ],
  "caves": {
    "width": 0.085,
    "scale": 36
  },
  "water": {
    "block": "minecraft:water",
    "sea_level": 128,
    "freeze": false
  },
  "preferences": {
    "desolation": 0.3,
    "damage": 0.2,
    "burial": 0.1,
    "overlap": 0.2,
    "road_continuity": 0.8,
    "density": 1
  },
  "volumes": []
}
```

## Universe layouts

A layout describes the suns and planets **beyond** the system a preset or a star system writes out.

| Field | Type and range | Default | Meaning |
|---|---|---|---|
| `stars` | object | `{"min": 0, "max": 0}` | How many further suns a finite universe has |
| `stars.min` / `stars.max` | integers, 0 ≤ min ≤ max ≤ 64 | both required with `stars` | The home sun is not counted |
| `planets` | object | required | How many planets each further sun has |
| `planets.min` / `planets.max` | integers, 1 ≤ min ≤ max ≤ 8 | both required | A sun's planets appear together or not at all |
| `star_gap` | number, see the rule below, at most 1000000 | `137900` | Grid spacing; axial neighbouring cells are exactly this far apart, diagonal cells farther |
| `ring` | number, at least 8192 | `12288` | Orbit spacing for systems with three or more generated planets; pairs share a ring |
| `types` | list of at least one object | required | The planet types to draw from |
| `types[].type` | planet type id, or a whole type written in place | required | |
| `types[].weight` | integer, 1–1000 | `1` | Relative chance of being drawn, not a count |
| `density` | number, 0–1 | `0` | Above 0 the universe is found as it is travelled, each grid cell holding a sun with this chance; `stars.max` must then be 0 |

**Distance rule.** `star_gap ≥ 2 × (17800 + (planets.max - 1) × ring) + 8192` (68368 for two planets and
`ring` 12288). Authored systems use their home orbit radius instead of 17800. This conservative envelope also
allows the legacy separate-ring fallback. Suns now occupy grid centres without random position offsets.
Generated two-planet systems share the inner ring, period and a fixed 120° phase separation; larger counts retain
separate rings. Counts, types, terrain seeds and starting orbital phase are still seeded.

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

With `planets`, suns stand in a row 137900 blocks apart with orbits 12288 blocks apart (the home pair shares
a 17800-block ring at 120° separation), which limits how many
planets a sun can hold. To place suns and orbits yourself, write a star system instead.

**A planet entry by type** (any entry but the first):

| Field | Type and range | Default | Meaning |
|---|---|---|---|
| `planet_type` | type id, or a whole type written in place | required | |
| `terrain_seed` | 64-bit integer | required | The planet's own seed; it is not taken from the world seed |
| `size` | integer in the type's range, a multiple of 16 | picked from the seed | Width of a face of this planet |

Full generators use `planet_format: 3`, `terrain_version: 14`. `planet_generation` requires type, surface_version=10, recipe (the complete terrain object above), size, depth, shell, creatures, features, structures and environment. `planet_biomes` contains terrain_seed and palette (the recipe biome IDs). Older formats are rejected. Network protocol is 18 and Profile is `starfold:native_planet_v17_profile`. PlanetPlan FORMAT=4 includes terrain and structures and reuses bounded transfers and digest caching. Terrain plans are persisted and synchronized even with structures disabled. TerrainPlan FORMAT=3 stores fixed-point fields: height, temperature, water level and strata shift at 1/8 units, roughness at 1/1024, humidity at 1/16384. The same quantization is applied in memory so save/reload preserves evaluation. Old formats are rejected; create a new world.

## Built-in types and presets

All ids begin with `starfold:`.

| Type | Face width | Land share | Mountains/hills | Landforms | Liquid |
|---|---:|---:|---:|---|---|
| `rocky` | 512–1536 | 1 | 50/12 | crater | air |
| `desert` | 512–2048 | 1 | 50/12 | canyon, dunes | air |
| `ice` | 448–1024 | 1 | 50/12 | glacial_rift | water |
| `temperate` | 1024–2048 | 0.4 | 160/24 | — | water |
| `lush` | 1024–2048 | 0.6 | 24/12 | — | water |
| `arid` | 1024–2048 | 0.4 | 24/12 | — | water |
| `archipelago` | 1024–2048 | 0.12 | 24/12 | volcano | water |
| `steppe` | 1024–2048 | 0.4 | 24/12 | — | water |

The worlds pack supplies home, archipelago, lush, desert, ice, rocky, asteroid, basalt, crystal, end, mushroom and warden; biotech adds the thirteenth type, `starfold_biotech:flesh`, with blood and spherical Voronoi hive walls. Types declare their atmospheres explicitly. Recipes and resolved templates are fixed with the planet.

The layout `starfold:default` draws those types in that order with the weights 3, 2, 2, 2, 1, 1, 1, 1, with
`density` 1, `star_gap` 137900, `ring` 12288 and one or two planets to a sun. `starfold:small` uses the same types
and weights in a finite universe of two to four further suns (its explicit gap remains 98304).

### Experience-pack flight scale

Both experience presets now select the existing authored-system format `starfold_worlds:experience`; no codec
extension is needed. Four suns form a roughly 137900-block tetrahedron, each with three planets on a 17800-block
ring, equal 24000-tick periods and 120° spacing. Storage order, types, sizes and terrain seeds are retained:
Hearth/Viridia/Hush; Anvil/Rime/Saffron; Pelagia/Haven/Fleck; Cinder/Prism/Ivory. The production preset still
starts with the starter ship. Hearth now names the `starfold_worlds:home` type rather than an anonymous full generator.
At epoch zero (and every subsequent phase), no same-system planet chord intersects its sun.

For the unarmed Hauler, the P1 acceleration-plus-cruise estimate is about 60.1 seconds within a system and
roughly 188–292 seconds between experience systems; boost halves these estimates. Default axial-neighbour
planet routes are bounded by 102300–173500 blocks, about 180.1–299.7 seconds. These are centre-distance design
estimates, not measured departure/arrival/landing times; diagonal or multi-cell trips are longer. Custom sparse
layouts can have missing cells and therefore more distant nearest occupied suns. No ship speed was changed.
The nearest 24 suns remain selected; the region sky far plane is 1000000 blocks, comfortably beyond these routes.
Existing non-authored saves that rebuild paths, and future generated cells, change layout: start a new world for
this scale. Authored saved celestial records retain their written geometry.

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
  "shape": {
    "size": {
      "min": 512,
      "max": 1024
    }
  },
  "terrain": {
    "version": 2,
    "planner": {
      "stages": [
        "starfold:plates",
        "starfold:landforms",
        "starfold:erosion",
        "starfold:climate",
        "starfold:biomes",
        "starfold:volumes"
      ]
    },
    "land_fraction": 1,
    "relief": {
      "mountains": 50,
      "hills": 12,
      "roughness": 1
    },
    "landforms": [
      {
        "type": "starfold:crater",
        "parameters": {
          "density": 0.25,
          "radius_min": 24,
          "radius_max": 128,
          "amplitude": 24,
          "operation": "raise"
        }
      }
    ],
    "erosion": {
      "rivers": false,
      "strength": 1,
      "river_threshold": 48
    },
    "climate": {
      "temperature": 27,
      "latitude_gradient": 47,
      "humidity": -0.8,
      "lapse_rate": 0.0065
    },
    "biomes": [
      {
        "type": "starfold:climate",
        "parameters": {
          "biome": "minecraft:stony_peaks",
          "temperature_min": -300,
          "temperature_max": 300,
          "humidity_min": 0,
          "humidity_max": 1,
          "height_min": -1024,
          "height_max": 320,
          "river": false,
          "coast": false
        }
      }
    ],
    "materials": [
      {
        "type": "starfold:surface",
        "parameters": {
          "block": "minecraft:tuff",
          "depth_min": 0,
          "depth_max": 3,
          "temperature_min": -300,
          "temperature_max": 300,
          "slope_min": 0,
          "shore": false,
          "biome": ""
        }
      }
    ],
    "strata": [
      {
        "block": "minecraft:deepslate"
      }
    ],
    "ores": [],
    "caves": {
      "width": 0.085,
      "scale": 36
    },
    "water": {
      "block": "minecraft:air",
      "sea_level": 128,
      "freeze": false
    },
    "preferences": {
      "desolation": 0.3,
      "damage": 0.2,
      "burial": 0.1,
      "overlap": 0.2,
      "road_continuity": 0.8,
      "density": 1
    },
    "volumes": []
  },
  "life": {
    "biome": "minecraft:stony_peaks"
  },
  "environment": {}
}
```

### `data/demo/starfold/universe/ash_universe.json`

```json
{
  "density": 1.0,
  "planets": {
    "min": 1,
    "max": 1
  },
  "star_gap": 49152,
  "ring": 12288,
  "types": [
    {
      "type": "demo:ash",
      "weight": 1
    }
  ]
}
```

### `data/starfold/worldgen/world_preset/native_universe.json`

[Complete current-format preset](../src/main/resources/data/starfold/worldgen/world_preset/native_universe.json)

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
| `suns[].radius` | number, 32–16384 blocks | required |
| `suns[].planets` | list of at least one planet | required |
| `suns[].planets[].type` | planet type id, or a whole type written in place | required; an unknown id stops the world from loading |
| `…size` | width of a face in blocks: in the type's range, a multiple of 16 | picked from the type and the planet's seed |
| `…seed` | 64-bit integer | derived from the world seed and the planet's place in the file |
| `…reach` | number, 512–2048 blocks above the surface; height where a ship leaves for space | `2048` |
| `…name` | 1–64 characters, not blank, no control characters | A, B, … Z, P26, P27 … by storage order |
| `…orbit` | object | required |
| `…orbit.radius` | number, 1–10000000 blocks, within the rules below | required |
| `…orbit.phase` | number, radians | `0` |
| `…spin` | object | **the planet does not turn** |
| `…spin.period` | number, 24000–1000000000 ticks | required with `spin` |
| `…spin.tilt` | number, -180–180 **degrees** | `0` |
| `…moons` | list of moons, with the same fields as a planet except `moons` | no moons |
| `home` | object, counting from 0 | `{"sun": 0, "planet": 0}` |
| `home.sun` / `home.planet` | a sun of the file and one of its planets | `0` each |
| `beyond` | layout id, or a whole layout written in place | **nothing beyond**: only the planets written here exist |

**Orbits** are circles in the horizontal plane of their parent: period is derived in ticks as
`20 × 2π × sqrt(radius³ / parent GM)`. At tick `t` the angle is `phase + 2π × t / period`, and the body stands at
`parent + [radius × sin(angle), 0, radius × cos(angle)]`.
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
   (`r1 + r2 + 4352` with default reach). Periods are derived from each orbit's radius and the parent GM; same-ring
   planets therefore share a period. Their separation is `sqrt((R1 - R2)² + 4 × R1 × R2 × sin²(Δphase / 2))`.
4. **Suns keep their distance.** An orbit's radius must exceed `sun radius + r + reach + 128`
   (`sun radius + r + 2176` with default reach). A sun's envelope is the largest
   `orbit radius + r + reach + 128` among its planets, and two suns must stand at least the sum of their envelopes
   apart, whatever the phases.
5. **Coordinates stay bounded.** A sun's position and its orbit envelope must remain within 1000000000 blocks of the origin.
6. **At most 1600 planets**, including any that `beyond` adds. Suns from `beyond` stay clear of the envelope of
   every hand-written sun, and fewer are made where there is no room. With `beyond`, the outermost orbit it may
   create must not take longer than 1000000000 ticks, using the Kepler formula and the parent GM. Its inner ring must also clear a generated sun
   and the largest default-reach surface: `home radius > 960 + 2048 × 2 / π + 2048 + 128`
   (960 is the maximum generated sun radius). Its `star_gap` must be at least
   `2 × (home radius + (beyond.planets.max - 1) × beyond.ring) + 8192`.
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
  "suns": [
    {
      "position": [
        0,
        0,
        0
      ],
      "radius": 512,
      "planets": [
        {
          "type": "starfold:temperate",
          "size": 1024,
          "reach": 512,
          "orbit": {
            "radius": 30000
          },
          "moons": [
            {
              "type": "starfold:rocky",
              "size": 512,
              "reach": 512,
              "name": "Moon",
              "orbit": {
                "radius": 5000,
                "phase": 3.141592653589793
              }
            }
          ]
        }
      ]
    }
  ]
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
  "shape": {
    "size": {
      "min": 512,
      "max": 1024
    }
  },
  "terrain": {
    "version": 2,
    "planner": {
      "stages": [
        "starfold:plates",
        "starfold:landforms",
        "starfold:erosion",
        "starfold:climate",
        "starfold:biomes",
        "starfold:volumes"
      ]
    },
    "land_fraction": 1,
    "relief": {
      "mountains": 50,
      "hills": 12,
      "roughness": 1
    },
    "landforms": [
      {
        "type": "starfold:crater",
        "parameters": {
          "density": 0.25,
          "radius_min": 24,
          "radius_max": 128,
          "amplitude": 24,
          "operation": "raise"
        }
      }
    ],
    "erosion": {
      "rivers": false,
      "strength": 1,
      "river_threshold": 48
    },
    "climate": {
      "temperature": 27,
      "latitude_gradient": 47,
      "humidity": -0.8,
      "lapse_rate": 0.0065
    },
    "biomes": [
      {
        "type": "starfold:climate",
        "parameters": {
          "biome": "minecraft:stony_peaks",
          "temperature_min": -300,
          "temperature_max": 300,
          "humidity_min": 0,
          "humidity_max": 1,
          "height_min": -1024,
          "height_max": 320,
          "river": false,
          "coast": false
        }
      }
    ],
    "materials": [
      {
        "type": "starfold:surface",
        "parameters": {
          "block": "minecraft:tuff",
          "depth_min": 0,
          "depth_max": 3,
          "temperature_min": -300,
          "temperature_max": 300,
          "slope_min": 0,
          "shore": false,
          "biome": ""
        }
      }
    ],
    "strata": [
      {
        "block": "minecraft:tuff"
      },
      {
        "block": "minecraft:deepslate",
        "from": 0.65
      }
    ],
    "ores": [],
    "caves": {
      "width": 0.085,
      "scale": 36
    },
    "water": {
      "block": "minecraft:air",
      "sea_level": 128,
      "freeze": false
    },
    "preferences": {
      "desolation": 0.3,
      "damage": 0.2,
      "burial": 0.1,
      "overlap": 0.2,
      "road_continuity": 0.8,
      "density": 1
    },
    "volumes": []
  },
  "life": {
    "biome": "minecraft:stony_peaks"
  },
  "environment": {}
}
```

#### `data/handwritten/starfold/system/twin_lanterns.json`

```json
{
  "suns": [
    {
      "position": [
        0,
        0,
        0
      ],
      "radius": 512,
      "planets": [
        {
          "type": "starfold:temperate",
          "size": 2048,
          "seed": 20261001,
          "name": "Aster",
          "orbit": {
            "radius": 8192,
            "phase": 0
          },
          "spin": {
            "period": 48000,
            "tilt": 23.5
          }
        },
        {
          "type": "handwritten:ash",
          "size": 768,
          "name": "Cinder",
          "orbit": {
            "radius": 8192,
            "phase": 3.141592653589793
          }
        },
        {
          "type": "starfold:ice",
          "name": "Rime",
          "orbit": {
            "radius": 17000,
            "phase": 1.2
          },
          "spin": {
            "period": 72000,
            "tilt": -12
          }
        }
      ]
    },
    {
      "position": [
        60000,
        4000,
        -20000
      ],
      "radius": 420,
      "planets": [
        {
          "type": "handwritten:ash",
          "size": 512,
          "orbit": {
            "radius": 9000,
            "phase": 0.7
          }
        },
        {
          "type": "starfold:desert",
          "size": 1024,
          "name": "Dune",
          "orbit": {
            "radius": 18000,
            "phase": 2.4
          }
        }
      ]
    }
  ],
  "home": {
    "sun": 0,
    "planet": 0
  }
}
```

#### `data/handwritten/worldgen/world_preset/twin_lanterns.json`

```json
{
  "dimensions": {
    "minecraft:overworld": {
      "type": "minecraft:overworld",
      "generator": {
        "type": "minecraft:noise",
        "settings": "minecraft:overworld",
        "biome_source": {
          "type": "minecraft:multi_noise",
          "preset": "minecraft:overworld"
        }
      }
    },
    "minecraft:the_nether": {
      "type": "minecraft:the_nether",
      "generator": {
        "type": "minecraft:noise",
        "settings": "minecraft:nether",
        "biome_source": {
          "type": "minecraft:multi_noise",
          "preset": "minecraft:nether"
        }
      }
    },
    "minecraft:the_end": {
      "type": "minecraft:the_end",
      "generator": {
        "type": "minecraft:noise",
        "settings": "minecraft:end",
        "biome_source": {
          "type": "minecraft:the_end"
        }
      }
    },
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
{
  "replace": false,
  "values": [
    "handwritten:twin_lanterns"
  ]
}
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
   dimension type the world uses is removed, the world may fail to load. Biome features supply the surface scatter
   policy; the structure-set tag and biome eligibility affect planets whose structure plan has not yet been created.
   Each planet keeps its first saved plan.
6. **Chunks already generated are never rewritten.**
7. **A preset only matters when a world is created.** It is not a way to update an existing world.

## Limits fixed in the code

- **Terrain.** The noise, caves and ecology are code; trees follow supported biome templates, and a type supplies a fixed terrain recipe and its
  parameters. All 13 types share the evaluator; sea level and fluid (water, lava, blood or air) come from the recipe.
- **Structures use fixed plans.** `life.structures`, the `starfold:planets` structure-set tag and each
  structure's biomes select candidates. All 11 shipped sets participate: villages/outposts, procedural temples
  and huts, complete igloos/ocean ruins, portals (including underground variants), shipwrecks, buried treasure
  and mineshafts. Vanilla jigsaw assembly reads the final terrain surface including volumes. Every piece and its NBT recipe is retained; piece
  bboxes and `terrain_adaptation` replace the old circular site pad. Rigid pieces fill/cut a foundation with a
  12-block smooth edge; `terrain_matching` roads follow L1. `none` leaves terrain height unchanged. Structures
  cross face seams, avoid corner anomalies and exclude footprint vegetation. AIR excavates interiors, including
  caves/ores; a candidate touching the core shell is rejected in full. Unsupported placements, biome exclusions,
  recording-budget refusals are reported in the server log.
  Plans are generated once on a background worker and saved under `data/planet-plans/`. Visible states sync
  with Profile; large plans use separate 256 KiB parts with total-length/SHA-256 validation. The 1,000,000-byte
  packet budget remains fixed, and clients publish only complete plans. Templates and loot/block-entity recipes
  stay on the server; every saved piece is checked against its fixed visible result before publication. Replacing
  a referenced template refuses the saved plan. Structure entity recipes (including witch/cat, drowned and chest
  minecarts) are recorded deterministically and replayed in their owning chunks; ordinary chest/spawner/archaeology
  block entities are preserved. Terrain composition
  is version 9; use a new world. Non-pool pieces with a datapack-supplied non-default adaptation are currently
  rejected with an explicit dynamic-height relocation reason; shipped non-pool structures use `none`.
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
