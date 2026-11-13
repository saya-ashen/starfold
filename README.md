# Starfold

[中文说明](README_zh-CN.md)

A Minecraft 1.21.1 NeoForge mod in which planets are closed, six-faced worlds built from real vanilla chunks. You walk
over a face edge without a teleport or a reload, several planets and suns share one dimension, and you fly between
them without a loading screen, in a ship built with Create and Create Aeronautics or on a thruster pack.

**Status: alpha.** Development targets single-player; multiplayer and compatibility with other mods are not a goal
yet. Current prerelease: **0.3.0-alpha.1 — Starward**. **Saves from 0.2.0 are not supported; create a new world.** Keep backups of older worlds.

## What is in it

- Planets whose terrain, block entities, inventories, collision and simulation are ordinary vanilla chunks. Near
  terrain is loaded the vanilla way, including across face edges; a far view covers what lies beyond view distance.
- Planets of different sizes, with depth scaled to size, caves, ores, biomes and structures.
- Star systems with suns, orbits, spin and day and night, defined in datapacks: see
  [docs/DATAPACKS.md](docs/DATAPACKS.md).
- Space flight for ships built with Create and Create Aeronautics: Starfold adds the ship controller, thrusters,
  booster, gyro, navigation scopes, airstair and aircraft dock. Without those mods, the thruster pack still takes
  you to space on your own.
- Leaving the ground is up to you: build a ship, or craft a thruster pack. Installing Worlds and Extras also provides
  an experience preset with a ready-made starter ship at spawn as a shortcut.
- The eight corners of every planet are fold points that keep players out. The setting behind them is in
  [docs/LORE.md](docs/LORE.md).

- Atlas adds persistent planet surveys, ground maps, 3D system/interstellar maps and ship tracking.
- Ship fuel contributes mass and remaining delta-v; gas-giant skimming and pressure hulls extend the flight progression.
- Optional Biotech adds Flesh hive terrain, blood and the Host encounter.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.247 or newer |
| Sable | 2.0.5 or 2.0.6 (required) |
| Create, Create Aeronautics | needed to build ships and use Extras |
| Immersive Aircraft | optional; adds an aircraft to the starter ship |

Install `starfold_worlds` for experience planets and presets; optionally add `starfold_extras` for the starter ship, landing pad and guide (requires Worlds, Create and Create Aeronautics).
`starfold_materials` adds lightweight aluminum hull plating and heavy osmium pressure hulls (full blocks and slabs); metal ingredients come from other mods via common tags. See [provisional material values](materials/BALANCE.md).

`starfold_atlas` supplies maps and ship tracking. `starfold_biotech` requires Worlds and exactly GeckoLib 4.8.2; its balance values are provisional. The survival pack under `modpack/` is source configuration, not an accepted complete survival release.

Planet rendering supports the vanilla renderer and Sodium 0.8. Iris is supported while no shader pack is
enabled; an active Iris shader pack, Embeddium or Canvas disables planet terrain projection and far views in
this build. The mod declares itself incompatible with `gravity_changer`.

## Playing

Create a new world and pick a world type:

- `starfold_worlds:experience`: the experience worlds; installing `starfold_extras` adds the starter ship.
- `starfold:native_universe` ("Starfold: Generated Universe"): a generated universe without the pack.
- The remaining `native_*` presets are small setups used in development. They are not listed in the world
  creation screen; a server can select one by id with `level-type`.

`/starfold` holds the planet commands.

## Building

A JDK 21 is required; Gradle is run through the wrapper.

```
./gradlew build                  # jar in build/libs, runs every test
./gradlew packagedContractTest   # checks every mixin of the built jar against Minecraft
./gradlew runClient              # development client; its game directory is run/
```

Java sources are formatted with google-java-format 1.28.0 in its AOSP style
(`--aosp --skip-reflowing-long-strings --skip-javadoc-formatting`); it is run by hand, not by the build.

Optional mods for the development client go in `run/mods`, which is not versioned. Tests that check item ids of
those mods skip that check when the directory is absent.

## Repository layout

- `src/main`, `src/test`: the core `starfold` mod and its tests. The code is in `dev.starfold`, one package per subsystem
  (a test sits in the package of what it tests):
  - `geometry`: the cube surface, its faces and seams, as plain maths with no Minecraft types.
  - `network`: shared payload registration and dist-safe receivers.
  - `chart`: planet shapes, celestial orbit and sun computations, and how an observer sees a planet from ground to orbit.
  - `planet`: planet storage and identity, the core and fold-point geometry, and the pure flight dynamics kernel.
  - `worldgen`: the terrain algorithms: height, rock, caves, minerals, biomes and decoration.
  - `universe`: what a datapack defines (planet types, star systems, universe layouts), the generated universe
    and the chunk generators that save a system with its world.
  - `sky`: saved celestial motion, the clock, daylight and weather.
  - `ground`: the server side of living on a face: seams, chunk views, observer and flight payloads, light and lifecycle hooks.
  - `render`: the client's far view of the planet, terrain display transitions and the shader patches.
  - `flight`: personal flight, its HUD, the permit other mods can grant and fold-point player effects.
  - `space`: the region between planets.
  - `ship`: Sable hulls: helm, thrusters, fuel, landing, airstair, dock, pressure damage and saved ship records.
  - `waypoint`: marks and navigation to them.
  - `api`: survey and system-map access for client modules.
  - `storage`: shared chunk-address keys.
  - `mixin`: core mixins.
- The subprojects in `settings.gradle` each keep code/resources in `<module>/src/main` and tests in
  `<module>/src/test` where present:

  | Module | Responsibility and source path |
  |---|---|
  | `atlas` | Planet surveys and galaxy/system/ground maps: `atlas/src/main/java/dev/starfold/atlas` |
  | `devtools` | Opt-in client CPU/GPU profiling: `devtools/src/main/java/dev/starfold/devtools` |
  | `probes` | Development probes, passive measurements and probe mixins: `probes/src/main/java/dev/starfold/{dev,probe,probes}`; development only |
  | `worlds` | Experience planets, biomes and world presets: `worlds/src/main/java/dev/starfold/worlds` and `worlds/src/main/resources` |
  | `extras` | Optional starter ship, landing pad and guide: `extras/src/main/java/dev/starfold/extras`; ship structures in `extras/src/main/resources/data/starfold_extras/structure` |
  | `materials` | Aluminum stock, hull plating and pressure hull components: `materials/src/main/java/dev/starfold/materials` and `materials/src/main/resources` |
  | `biotech` | Flesh hive terrain, organic materials and the Host encounter: `biotech/src/main/java/dev/starfold/biotech` and `biotech/src/main/resources` |

- `docs/DATAPACKS.md`: the reference for datapack authors, also in Chinese; tests read its examples.
- `docs/DEVELOPMENT.md`: architecture, verification commands and current validation limits.
- `docs/ROADMAP.md`: what is planned and how far it has been thought through, also in Chinese.
- `tools/ship`: generators for the starter ship structures and block resources.
- `tools/probe`: scripted in-game checks for the development client, see [tools/probe/README.md](tools/probe/README.md).
  The launch scripts are written for the maintainer's Linux setup.
- `tools/rename_project.py`: renames the project (ids, packages, paths) in one run from `project-names.json`.


## License

MIT, see [LICENSE](LICENSE).
