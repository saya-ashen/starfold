# Development and verification

Use JDK 21 and the Gradle wrapper from the repository root:

```sh
export JAVA_HOME=/path/to/jdk-21
./gradlew check build
./gradlew packagedContractTest
```

`-PcandidateVersion=<version>` overrides the development version. Module jars are written to each module's `build/libs`; the core jar is in `build/libs`. These commands do not publish a release. Gradle needs access to its configured dependency repositories on a fresh machine. Use `--offline` only after dependencies have been cached.

## Architecture

The core owns planet identity, cube geometry, terrain plans, environmental rules, ship dynamics, storage, networking and rendering. A fixed planet recipe creates a deterministic terrain plan shared by generation and far views. Vanilla structure recording is kept serial where vanilla generators share mutable state.

Worlds supplies experience definitions and presets. Extras supplies the optional starter ship and guide. Atlas consumes core survey/navigation APIs. Materials supplies lightweight and pressure-resistant components. Biotech adds Flesh content and the Host, with an exact GeckoLib 4.8.2 dependency. Probes and Devtools are development tools; do not install their jars as ordinary content.

Core seasonal climate hooks are available, but the optional seasonal display implementation is not part of this source snapshot while third-party provenance review remains pending.

## Checks and their limits

The root verification tasks aggregate Java tests, packaged mixin contracts, module/core packaging boundaries, Sable runtime contracts and offline Python tool checks. Datapack tests also read the examples in `DATAPACKS.md` and `DATAPACKS_zh-CN.md`; keep them synchronized with source definitions. Tests and content generators under `tools/` are retained as reproducible development inputs.

Scripted game checks live under `tools/probe`; see its README for the Linux launch environment and commands. They are separate from unit tests and require an actual game installation. Generated logs, screenshots, saves and measurement output belong under ignored `build/` or `run/`, not in source control.

Existing game verification has covered return flight, landing, map selection after restart, re-entry into the original ship and persistence of cargo, including recovery of saved dependency groups. Those checks used creative flight; they do not certify survival oxygen/equipment/fuel progression, arbitrary inverted boarding, natural ship unloading or multiplayer. Native ship restoration does not yet provide complete transaction rollback after a partial failure.

Full survival progression and balance remain unaccepted. The source under `modpack/` still requires locally built Starfold jars and is not a complete exported installation. Planet planning with structures has exceeded its five-second performance target in recorded measurements; use the evaluation tools and a fresh game run for current measurements rather than treating old timing reports as a guarantee.

## Assets

Runtime assets and the source images needed by the Biotech texture generator are retained. See `biotech/art/PROVENANCE.md` for generated-art provenance. Unused concept sheets and historical internal verification reports are omitted from this public snapshot.

## Release artifacts

After a successful versioned build, package the six player modules and the Experience pack with:

```sh
python3 tools/release/experience.py 0.3.0-alpha.1 --verify-downloads
```

The dependency manifest pins exact download URLs, sizes and hashes. The command checks downloaded dependency archives and built module metadata, then writes player jars, an importable `.mrpack`, installation notes and SHA-256 checksums under ignored `build/release/`. It does not publish. The Experience pack is independent of the unfinished survival configuration under `modpack/`.
