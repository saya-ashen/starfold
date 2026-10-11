# Starfold

[中文说明](README_zh-CN.md)

A Minecraft 1.21.1 NeoForge mod in which planets are closed, six-faced worlds built from real vanilla chunks. You walk
over a face edge without a teleport or a reload, several planets and suns share one dimension, and you fly between
them without a loading screen, in a ship built with Create and Create Aeronautics or on a thruster pack.

**Status: alpha.** Development targets single-player; multiplayer and compatibility with other mods are not a goal
yet. Saves are not kept compatible between versions, so back up before updating.

## What is in it

- Planets whose terrain, block entities, inventories, collision and simulation are ordinary vanilla chunks. Near
  terrain is loaded the vanilla way, including across face edges; a far view covers what lies beyond view distance.
- Planets of different sizes, with depth scaled to size, caves, ores, biomes and structures.
- Star systems with suns, orbits, spin and day and night, defined in datapacks: see
  [docs/DATAPACKS.md](docs/DATAPACKS.md) (Chinese).
- Space flight for ships built with Create and Create Aeronautics: Starfold adds the ship controller, thrusters,
  booster, gyro, navigation scopes, airstair and aircraft dock. Without those mods, the thruster pack still takes
  you to space on your own.
- Leaving the ground is up to you: build a ship, or craft a thruster pack. The experience world preset also places
  a ready-made starter ship at spawn as a shortcut.
- The eight corners of every planet are fold points that keep players out. The setting behind them is in
  [docs/LORE.md](docs/LORE.md).

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.247 or newer |
| Sable | 2.0.5 or 2.0.6 (required) |
| Create, Create Aeronautics | needed to build ships and for the experience preset |
| Immersive Aircraft | optional; adds an aircraft to the starter ship |

Planet rendering needs the vanilla renderer: shader packs are not supported. The mod declares itself incompatible
with `gravity_changer`.

## Playing

Create a new world and pick a world type:

- `starfold_pack:experience`: the experience pack, with the starter ship (needs Create and Create Aeronautics).
- `starfold:native_universe` ("Starfold: Generated Universe"): a generated universe without the pack.
- The remaining `native_*` presets are small setups used in development. They are not listed in the world
  creation screen; a server can select one by id with `level-type`.

`/starfold` holds the planet commands.

## Building

A JDK 21 is required; Gradle is run through the wrapper.

```
./gradlew build                  # jar in build/libs, runs every test
./gradlew nativeTest             # the faster planet regression suite
./gradlew packagedContractTest   # checks every mixin of the built jar against Minecraft
./gradlew runClient              # development client; its game directory is run/
```

Optional mods for the development client go in `run/mods`, which is not versioned. Tests that check item ids of
those mods skip that check when the directory is absent.

## Repository layout

- `src/main`, `src/test`: the mod and its tests.
- `docs/DATAPACKS.md`: the datapack interface; tests read its examples.
- `tools/ship`: generators for the starter ship structures and block resources.
- `tools/probe`: scripted in-game checks for the development client, see [tools/probe/README.md](tools/probe/README.md).
  The launch scripts are written for the maintainer's Linux setup.
- `tools/rename_project.py`: renames the project (ids, packages, paths) in one run from `project-names.json`.

Earlier prototypes and their release notes are kept on the `archive/*` branches.

## License

MIT, see [LICENSE](LICENSE).
