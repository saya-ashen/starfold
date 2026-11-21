# Development probes

Start with the [active and historical catalog](CATALOG.md). This directory contains development
client scripts, offline utilities, generators and historical session inputs. A tracked script
is not by itself a supported clean-checkout replay or evidence that gameplay passed.
Ignored copies under `build/probe/` are local working files, not canonical entry points.

The small documented runtime set is [trip](#full-trip-verdict), [flight performance](#flight-performance)
and [unpiloted hold diagnostics](#unpiloted-space-hold). The [moving-moon route](#moving-moon-probe)
has a known fixture dependency gap. All other scripts are historical/unclassified for replay
purposes unless the catalog explicitly says otherwise. Git history retains retired one-off inputs.

**Documentation review, 2026-11-12:** scripts, runner, verdict code and referenced fixtures were
inspected statically. No game, Gradle task or probe replay was run for this cleanup; the entries
below describe setup and expected evidence, not newly verified runtime success.
For long automated survival/industry sessions, the execution constraint in
[AGENTS.md](../../AGENTS.md) applies; historical plans do not authorize resuming them.

## Shared runtime dependencies and runner

Run commands from the repository root. [dev.sh](dev.sh) is written for the maintainer's Linux
setup, not a portable installer. Before attempting a documented runtime entry point, provide:

- A JDK 21 (`JAVA_HOME`, including `jstack`), Bash, Python 3, GNU `readlink`/`timeout`,
  `pgrep`/`pkill`, `steam-run`, and working GLX/OpenGL driver libraries. The runner uses
  `/run/opengl-driver/lib` and queries `/usr/lib64/libGLX.so.0` through `steam-run`.
- A configured `run/` game directory, accepted server EULA, `run/server.properties`, and client
  `run/options.txt`. These local settings, saves and `run/mods` are not supplied by this directory.
  Set the level name, seed, preset and enabled packs for the chosen entry explicitly. Use an
  unused level name for each fresh-world sample. The runner does not prepare or reset a world.
- The development Gradle runtime and its dependencies; see the [root requirements and build
  instructions](../../README.md#building) and [run configuration](../../build.gradle).
  `starfold_probes` must be loaded. The default development runs also load Worlds, Extras
  (outside `-PsurvivalPack`), Mining and other local modules. Supply the required Sable/Create/
  Create Aeronautics runtime for ship routes; Worlds supplies experience terrain and Extras
  supplies its starter ship. A published core jar alone does not supply this setup.
- The offline `Dev` account with operator permission for these dedicated-server scripts;
  their commands and the completion `/say CP_PROBE_SERVER_ACK` need permission. Flight
  performance additionally requires creative flight (`mayfly`).
- An AI desktop with `ai-desktop-run` on `PATH` for the documented runs. The runner uses it
  when available; if the command is absent it has no desktop isolation fallback of its own.
  Set a free `server-port` in this checkout's properties (the prior worktree setup used 25575);
  the client reads that port, defaulting to 25565.

```sh
export JAVA_HOME=/path/to/jdk-21
bash tools/probe/dev.sh tools/probe/flight-perf.txt 360
```

This is a game-launch example, to use only after the entry's setup is complete. The runner
stops matching development client/server JVMs for **this checkout**, builds `classes`, starts
`runServer`, launches `runClient -PdevProbe`, then stops them again. Run serially within a
checkout. It bounds the classes build and server process at 1700 seconds each and waits up to
900 seconds for server readiness; argument 2 controls the client timeout (default 300 seconds).
Shutdown requires `All dimensions are saved` in the server log; failure to exit within its
60-second grace forces termination and fails the run.

Before the next run, preserve `build/dev-client.log`, `build/dev-server.log`,
`build/dev-classes.log`, runner stdout/exit status and the relevant `run/screenshots/` files in
an evidence directory. Also preserve CSVs for performance runs and any `build/jstack-*.txt` or
shutdown-tail diagnostics. Shared log names and screenshot labels can be overwritten.

The **resolved canonical paths** `tools/probe/trip.txt` and `tools/probe/moon.txt` select
`trip-verdict.py` (moon adds `--moon`). Other paths, including independent copies under
`build/probe/`, select `verify.py`. Generic verification checks completion, received server ACK,
condition timeouts and selected rejected-command messages; it is not gameplay acceptance.
See [offline utilities](CATALOG.md#offline-utilities) for checking already captured logs.

## Flight performance

[flight-perf.txt](flight-perf.txt) expects a fresh dedicated-server `starfold:native_universe`
world, seed `20261009`, creative mode and the OP `Dev` account. Set those values and an unused
`level-name` in `run/server.properties` before using the launch example above. Do not reuse a
save between samples. The obsolete `cubeplanets_local:native_stars` preset is not this setup.

After 1200 client ticks of loading, `flightbench low 60` requests a 1920×1080 window, render
distance 10, vsync off and cap 260, lifts to ground + 20 and flies along client +X using creative
flight inputs while following the local heightmap. `flightbench high 30` targets Y≈300 and
continues forward. Lift/ascent are excluded; phase lengths use the wall clock. Server chart
handoffs remain enabled, so the storage-coordinate route can curve.

Expected evidence is a `PROBE FLIGHT` summary for **each** leg, nonempty
`run/flight-low.csv` and `run/flight-high.csv`, and
`run/screenshots/flight-perf-low.png` / `flight-perf-high.png`, as well as completion/ACK and
successful shutdown. Inspect these artifacts: the runner's generic verifier does not require
the CSVs, compare performance or impose a frame-time threshold.

[DevFlightPerfProbe](../../probes/src/main/java/dev/starfold/dev/DevFlightPerfProbe.java) reports
elapsed seconds, frame count, nearest-rank p50/p95/p99/max, GC collections/time (MXBean
milliseconds), and decimal MB/s allocated by all client JVM threads. This measures client JVM
allocation; GC time is not a JFR pause trace. Raw CSV contains measured frame intervals.
No GPU timing query or per-frame log is used by this benchmark. Compare repeated fresh-world
samples with the same environment: JIT, heap ergonomics and concurrent workloads affect tails.
The historical far-view performance report
records a separate benchmark setup. The terrain rendering notes
summarize current rendering behavior.

The [far correctness route](far-correctness-route.txt) uses a disposable ground world
with cheats, the probes module and `-Dstarfold.farTrace=true`. It exports `logs/fartrace.json`.
`fartrace check` requires observed motion plus native entry, exit and resident recovery
for the same area. FAIL and INCOMPLETE both stop the route after exporting the evidence.
The [devtools diagnostics guide](../../devtools/FAR_VIEW.md) covers interactive `/sffar` capture.

## Full-trip verdict

The canonical [trip.txt](trip.txt) route is **Hearth (slot 0) → Fleck (slot 8)**, the nearest
landable home moon in the experience layout. It is a one-way scripted creative ship route;
it does not certify a survival mining round trip. Dependencies beyond the shared runtime are:

- Fresh `starfold_worlds:experience` world, seed `20261009`, and an unused level name.
- Worlds plus Extras and the Create/Create Aeronautics ship runtime, with the reachable
  experience starter helm. The [Extras build](../../extras/build.gradle) adds
  `starfold_extras:starter_ship` to the Worlds experience preset.
- The OP account and ordinary movement/key bindings expected by the script.

Set these in `run/server.properties` before launching; the properties spelling for the preset
is `level-type=starfold_worlds\:experience`. The runner does not set it for you.

```sh
bash tools/probe/dev.sh tools/probe/trip.txt 890
python3 tools/probe/trip-verdict.py build/dev-client.log
```

[trip-verdict.py](trip-verdict.py) prints eight gameplay stages plus session integrity.
Exit 0 requires lift-off/space, aim and planet-8 lock, cruise/NOTICE, chart hand-over to planet 8
with pilot authority retained, measured descent below clearance 60, and completion plus a
received server ACK with no rejected commands, hard-condition timeouts or duplicate stage
snapshots. Missing or misordered required evidence fails closed. `PROBE LANDAID ... kind=UNKNOWN`
after hand-over or explicit pilot-loss telemetry fails the hand-over gate even later in landing.

**Landing site, stair and landed are informational.** A successful exit does not promise a
landing. `followlanding 2400 informational` releases its keys and records its own timeout
without throwing; strict landing-driver commands retain their failure policy. The site stage
requires a deployable site and no driver timeout; the stair stage requires its condition to
have been met and the stair deployed. Landed requires at least 40 consecutive resting client
ticks and clearance <= **0.15** (the contact threshold). The resting counter also requires finite
clearance, vertical speed magnitude < 0.3 and horizontal speed < 0.3. A nav label or `quit`
alone proves none of these.

The personal-flight `nav` fields are insufficient for a piloted hull. `PROBE TRIP` snapshots
supply pilot authority, chart identity, hull clearance/motion, stair/resting state and clock/
space data. High-altitude UNKNOWN survey classification is distinct from UNKNOWN in the
low-altitude LANDAID driver. Preserve both logs for interpreting hand-over and terrain readiness.

The route uses a 240-tick W + Ctrl burn, releases both keys, then re-aims before coasting.
Its lock is not automatic target tracking. `navplanets trip-lock` and `trip-cruise` record actual
centre/surface distances and closing speeds; inspect them if layout or timing changes.
The 890-second client limit does not include the runner's build/server startup time.

## Moving-moon probe

[moon.txt](moon.txt) is a documented **candidate blocked by a fixture dependency gap**, not a
supported clean-checkout replay at this review. The tracked
[moon preset](moon-pack/data/moon_probe/worldgen/world_preset/moon.json) requests
`starter_ship.structure=starfold:starter_ship`. The tracked structure now lives at
[starfold_extras:starter_ship](../../extras/src/main/resources/data/starfold_extras/structure/starter_ship.nbt);
there is no tracked `data/starfold/structure/starter_ship.nbt`.
The [starter loader](../../extras/src/main/java/dev/starfold/extras/StarterShip.java) looks up the
requested ID directly and throws if missing. Loading Extras alone does not supply the old ID.
A matching external resource/fixture repair is therefore required before claiming replay;
no repair or game launch was performed in this documentation change.

The existing [moon-pack](moon-pack/pack.mcmeta) is development-only. Its
[system](moon-pack/data/moon_probe/starfold/system/moon.json) defines one sun, Probe Home
(slot 0), Probe Outer (slot 1), and appended full-surface Probe Moon (slot 2). The moon's
8000-block / 48000-tick orbit inherits Home's 30000-block / 120000-tick motion. Home uses a
Worlds planet type and the starter option requires Extras and the ship runtime.

Intended setup, **after resolving the starter dependency**: choose a never-used world name,
create only `run/<world>/datapacks/`, copy `tools/probe/moon-pack` there as `moon-pack` before
any `level.dat` exists, and set these properties:

```properties
level-name=moon-UNUSED-NAME
level-seed=20261009
level-type=moon_probe\:moon
initial-enabled-packs=vanilla,file/moon-pack
initial-disabled-packs=
```

The corresponding candidate invocation is `bash tools/probe/dev.sh tools/probe/moon.txt 900`;
its offline verdict is `python3 tools/probe/trip-verdict.py <client-log> --moon`.
The runner uses the trip gates for target slot 2; default verdict mode expects slot 8.
Expected evidence includes `moon-lock` distances/speeds, `s-chart` height and hull-footprint
loaded/total chunks, the trip stages, both logs and `moon-landed.png`. A screenshot or generic
completion alone cannot certify moon travel, terrain readiness or surface sky appearance.

[moon-looks.txt](moon-looks.txt) and [moon-orbit-looks.txt](moon-orbit-looks.txt) are supplemental
admin-visit/sky-picture scripts, sharing the moon setup dependency. They need server JVM flags
`-Dstarfold.helmProbe=true -Dstarfold.packProbe=true` (the runner inherits `JAVA_TOOL_OPTIONS`).
They do not establish travel acceptance; orbital images do not establish a surface view.
See the [datapack reference](../../docs/DATAPACKS.md) for saved-world and registry semantics.

## Unpiloted space hold

[ship-hold.txt](ship-hold.txt) and [ship-space-sleep.txt](ship-space-sleep.txt) are documented
targeted diagnostics, **runtime unverified in this cleanup**. Each needs a fresh experience
world, seed `20261009`, Extras starter, shared ship runtime and OP account, with
`-Dstarfold.shipProbe=true` on the server JVM. There must be only one player/hull, starter
identity `0:0`, default helm key bindings, no external forces or latched thrust, and its gyro
enabled. Fresh worlds also provide unused scoreboard objectives (`s3_ok` / `s5_ok`).

The first script releases and retakes the real helm. Its early release can still be coasting;
client wait durations do not prove a captured hold. The second deliberately removes world
items, moves the player away, disables probe leases, saves, asserts dormancy, advances a
quarter home day, and wakes the hull. These fixture interventions are development diagnostics,
not evidence of ordinary unloading or crash recovery.

Expected `SHIP_HOLD_WINDOW` readings occur at **100, 400 and 1200 server ticks** after the
hold window begins. `assert hold-window` requires all three errors below **0.1 block and
0.1 degree**; see [DevShipHoldProbe](../../probes/src/main/java/dev/starfold/dev/DevShipHoldProbe.java)
and [DevShipPersistenceProbe](../../probes/src/main/java/dev/starfold/dev/DevShipPersistenceProbe.java).
Preserve positive `check` receipts, both logs, completion/ACK and final screenshots. The sleep
script additionally checks dormant/checkpoint/live/space-place state. `verify.py` remains a
generic completion check; review these domain receipts separately. These single home-hull
scripts do not establish multi-hull, moon, foreign-actuator or crash-recovery acceptance.

## Atlas command reference

The `atlas` commands are development-only helpers, not a separately supported replay route.
The historical [atlas-map.txt](atlas-map.txt) and other recovery scripts need their original
saved hull/player/cargo state; see the [catalog](CATALOG.md#historical-and-unclassified-inputs).
The implementation is [DevAtlasProbe](../../probes/src/main/java/dev/starfold/dev/DevAtlasProbe.java).

`atlas ships` logs the visible directory; `atlas ship <UUID>` and `atlas target` click visible
map buttons; `atlas require <UUID>` asserts the selected target. `atlas away` checks an untracked
player's distance, `atlas hull` additionally requires a matching live client hull, and
`atlas aboard` checks tracking or helm authority. A directory entry alone does not prove a
live hull. These commands do not themselves wake or teleport a ship.

Creative `atlas approach` and the `approachlocal` / `approachhelm` / `approachhelmcenter`
variants fly toward directory or hull-relative positions using ordinary inputs, without
obstacle avoidance. They require a flight permit, active space, and no helm/screen. Eye-position
and bounding-box-center offsets differ; use `atlas cabin` to inspect the actual collider,
orientation and doorway state. They do not validate survival EVA, oxygen or fuel.

`atlas useblock <namespace:block>` raycasts and interacts in one tick. `atlas cargo <UUID> <label>`
asks the server for loaded containers' `Items`; inspect server item IDs/counts rather than
assuming completion is an inventory assertion. Preserve both logs and restart the server
between persistence phases. `console <absolute-path>` consumes then deletes an external file
of commands and returns to waiting; that local input is another dependency and must be
preserved with evidence. Numeric `press` uses GLFW codes, while `key` uses symbolic bindings.
