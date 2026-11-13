# Changelog

[中文](CHANGELOG_zh-CN.md)


## Unreleased

- Project planet terrain through Sodium 0.8: its chunk shader receives the same warp, projection uniforms
  and native-chunk coverage as the vanilla core shaders. Iris is supported while no shader pack is enabled;
  an active Iris pack, Embeddium or Canvas still disables planet terrain projection and far views.

## 0.3.0-alpha.1 “Starward” — 2026-11-09

**Create a new world. Saves from 0.2.0 are not supported, and no migration is provided.**

- Split experience worlds, starter content, maps, materials and Biotech into optional modules; keep development probes and profiling separate from player jars.
- Add Atlas planet surveys, ground/globe views, 3D system and interstellar maps, navigation targets and persistent ship tracking.
- Extend reachable star systems and interstellar engine progression. Fuel and component mass now affect flight and remaining delta-v.
- Add gas-giant pressure, skimming, aluminum hull plating and osmium pressure hulls.
- Add optional Flesh hive terrain, blood and the Host encounter through Biotech (requires Worlds and GeckoLib 4.8.2).
- Rework deterministic terrain planning, caves, roads, structures and ecology; improve far-view caching, streaming and rendering.
- Improve return flights and map-based recovery of saved ship dependency groups.

This prerelease contains changes since 0.2.0. Single-player remains the validation target. Full survival progression, fuel balance, multiplayer and shader compatibility are not accepted. The optional seasonal display module is omitted while its third-party provenance review remains pending; core seasonal APIs remain available.

## 0.2.0 “Moonrise”

**Worlds from 0.1.x cannot be opened with 0.2.0.** Create a new world. An experience-pack or generated-universe
world from 0.1.x stops with a crash report when it is opened; Starfold does not overwrite its data, but open such
a world with 0.1.1 only. A plain overworld that merely had Starfold installed still opens, without its old Starfold
dimension entries. Back up before trying.

### Added

- Moons: a planet in a star system datapack can list `moons`, each a full planet with its own surface, orbit and
  spin. See [docs/DATAPACKS.md](docs/DATAPACKS.md).
- A star system may hold a single planet, and the home planet no longer needs a companion.
- Other planets, moons and suns appear in the sky seen from a planet's surface.
- A ship left without a pilot in space coasts to a stop and then holds its place relative to the nearest body, also
  across saving and reloading.

### Changed

- Landing: the height above ground is measured from the hull's real underside against the terrain right below it.
  Ships touch down on slopes instead of cutting lift a block or two above them, and the airstair deploys once the
  ship rests on sloped ground.
- All bodies of a world now share one level.
- The far view reuses its patches while nothing in them changes.
- The code is sorted into packages; a roadmap is in [docs/ROADMAP.md](docs/ROADMAP.md).

### Fixed

- Light placed or removed next to a face edge now updates on the other side of the edge too.
- Worlds with more than 256 planets no longer mix up planets over the network.

### Known issues

- On stepped or sharply sloped ground a landed hull can rest a fraction of a block into a terrain edge.
- After saving and reloading in space, the locked destination is cleared; lock it again.
- On long flights the server can pause for 2–4 seconds around arrival.
- Tested with NeoForge 21.1.247, Create 6.0.10, Create Aeronautics 1.3.2 and Sable 2.0.5. With Sable 2.0.6 a
  landing, a walk on the ground and a take-off were checked, not a whole trip.
