# Dev probe scripts

Scripted in-game checks for the development client (`-Dstarfold.probe=<script>`, see `DevProbe`).
Working copies live in `build/probe/` (ignored); these are the versioned originals.

- `dev.sh <script> [timeout]` restarts the dev server, runs one scripted client session, dumps `jstack` of anything still running, then stops the server.
- `gen_sim.py <out> [face-size origin-x origin-z]` builds the seam interaction fixture (redstone, pistons, water, light, mob chase) on an unrotated and a rotated face seam.
- `gen_loop.py 1|2|3 [a-chest x,y,z] [b-chest x,y,z]` builds the three sessions of the A→B→A loop; phase 3 needs the chest positions printed by phase 1.

## Flight performance

`flight-perf.txt` uses a dedicated-server **fresh** `starfold:native_universe` world,
seed `20261009`, creative mode, and an offline `Dev` test account with OP permission
(the ordinary probe completion ACK uses `/say`). Do not reuse a save between samples.
For this worktree use port `25575`; copy only `server.properties` and `options.txt`
from a configured checkout when setting up `run/`. The obsolete
`cubeplanets_local:native_stars` preset silently creates an ordinary world instead.

After 1200 client ticks of loading, `flightbench low 60` resizes to 1920×1080,
sets render distance 10 / vsync off / cap 260, lifts to ground + 20 and flies along
client +X with ordinary creative-flight inputs, following the local heightmap.
`flightbench high 30` first ascends to Y≈300, then continues forward. Lift/ascent
are excluded; phase lengths use the wall clock, not a frame or tick count.
The command requires `mayfly`; do not use survival mode. Server native chart
handoffs remain enabled, so a high-altitude leg can curve in storage coordinates.

```sh
export JAVA_HOME=/path/to/jdk-21
# Set fresh level-name, level-seed=20261009, level-type=starfold\:native_universe,
# gamemode=creative, server-port=25575 in this checkout's run/server.properties.
bash tools/probe/dev.sh tools/probe/flight-perf.txt 360
```

Keep each run's `build/dev-client.log`, `build/dev-server.log`,
`run/flight-{low,high}.csv` and `run/screenshots/flight-perf-{low,high}.png`
before the next run. `PROBE FLIGHT` logs one comparable summary per leg: elapsed
seconds, frame count, nearest-rank p50/p95/p99/max, GC collections/time (MXBean
milliseconds), and decimal MB/s allocated by **all client JVM threads**. This is
not whole-machine or server allocation; collection time is not a JFR stop-the-world
pause trace. Raw CSV contains every measured frame interval. No GPU timing query
or per-frame logging is enabled. The first allocation snapshot is outside the
sampled frame intervals; summaries and CSV writing happen after measurement.
Compare run-to-run variation: heap ergonomics, JIT and concurrent workloads can
change GC counts and tails even with identical routes.

## Full-trip verdict

The canonical script is `tools/probe/trip.txt` (not an ignored working copy): **Hearth (slot 0)
→ Fleck (slot 8)**, its nearest landable moon in the single-star experience layout. Use
`level-type=starfold_worlds\:experience`, `level-seed=20261009`, and a **new** level name for each
run. Run through the AI desktop (`ai-desktop-run` must be on `PATH`). Example:

```sh
export JAVA_HOME=/path/to/jdk-21
timeout 20 mkdir -p build/pi-logs
timeout 20 sed -i -e 's/^level-name=.*/level-name=trip-UNUSED-NAME/' -e 's/^level-seed=.*/level-seed=20261009/' run/server.properties
timeout 1500 bash tools/probe/dev.sh tools/probe/trip.txt 890 > build/pi-logs/trip-UNUSED-NAME.out 2>&1
timeout 20 python3 tools/probe/trip-verdict.py build/dev-client.log
```

Run serially and copy both `build/dev-client.log` and `build/dev-server.log` before the next
run overwrites them. The runner bounds both the classes build and server launch at 1700 seconds;
the optional second argument controls the client timeout (300 seconds by default).
The runner selects the trip verdict **only for the exact canonical script path**. Other scripts
(including `build/probe/trip.txt`) retain `verify.py`'s completion/timeout policy.

`trip-verdict.py <client-log>` prints eight gameplay stages plus a session/integrity stage.
Hard gates: lift-off/space, successful aim and planet-8 lock, cruise/NOTICE, chart hand-over to
planet 8 with pilot authority retained, measured descent below clearance 60, and completion
plus server ACK with no rejected commands/hard-condition timeouts. Missing or misordered
evidence fails closed. `PROBE LANDAID ... kind=UNKNOWN` after hand-over or explicit pilot-loss
telemetry fails the hand-over gate even if it occurs later in the landing leg.

**Landing site, stair, and landed are informational, not regression gates.** The script still
attempts the original landing leg through real player inputs; `followlanding 2400 informational`
releases its own held keys and records a timeout rather than throwing/crashing. Existing
`followlanding 2400` and excursion commands retain their strict failure policy. No hull is
teleported, no ground is prepared, and no production behavior is changed. A landing-site PASS
requires no driver timeout and a deployable site at `s-site`; a stair PASS requires the condition
actually met and a fully deployed stair. Landed requires at least 40 consecutive client ticks
with finite hull clearance <= 1, vertical speed < 0.3 in magnitude and horizontal speed < 0.3.
A nav label or `quit` alone proves none of these.

The legacy `nav` reading describes personal flight, not the piloted hull: its altitude and
`controlled false` fields are misleading here. Development-only `PROBE TRIP` snapshots read
pilot authority, chart identity, hull clearance/motion, stair state, simulation time/partial,
space phase/drift and orientation. UNKNOWN **survey** at high chart altitude is normal:
production suppresses surface classification at clearance >= 200. It is not pilot loss;
UNKNOWN in the low-altitude LANDAID driver is still a failure.

The route still uses the original **240-tick W + Ctrl burn**, releases both keys, then re-aims
before coasting. Do not replace this with an unattended sustained burn: lock/forward assist
is not automatic target tracking. `navplanets trip-lock` and `trip-cruise` record actual
centre/surface distances and closing speeds so layout changes can be diagnosed from logs.
In a fresh seed-20261009 run near simulation tick 108k, Fleck is about 41k blocks away;
Pelagia is about 297k, Anvil 367k, Viridia 433k and Saffron 520k. After the burn, Fleck is
28k away with closing speed 1036 blocks/s: NOTICE follows in 277 ticks and chart hand-over
in another 383. The unchanged NOTICE limit of 1600 ticks is ample for Fleck, not Saffron.
NOTICE requires the nearest planet to match the lock, an inbound trajectory, and surface
height minus `hullIn` below 12 seconds of closing distance (or height below `hullIn + 300`).
The final route completes in about four minutes from world entry; the 890-second client
timeout bounds the session below 15 minutes, independently of build/server startup.

CPU verdict/runner tests: `timeout 40 python3 -m unittest discover -s tools/probe -p 'test_*.py' -v`.
`./gradlew --offline offlineToolsCheck` runs these plus the ship generators and modpack source checks;
root `check` includes it. These tests use temporary fixtures and mock processes; they never launch Minecraft.

## Unpiloted space hold

`ship-hold.txt` and `ship-space-sleep.txt` require the experience preset, a fresh world per run,
seed `20261009`, and server JVM flag `-Dstarfold.shipProbe=true`. The first releases and retakes
the real helm; the second sleeps the hull, advances a quarter home day, and wakes it again.
`SHIP_HOLD_WINDOW` reads the recorded-place error at **100, 400 and 1200 server ticks** from
its first post-tick observation, not from client wait duration. `assert hold-window` requires
all three readings below **0.1 block and 0.1 degree**. `SHIP_HOLD_BEGIN` exposes recapture;
`SHIP_HOLD_PILOT ... held=false` checks that a valid pilot has no hold. Copy both logs per run.

A fast release first coasts with body-relative velocity/spin drag, without positional holding.
Capture occurs once both residual speeds are at most .02 blocks/tick and .00001 radians/tick;
the ordinary live hold then keeps that body-relative material point and attitude using bounded
damped velocity corrections, not live pose teleports. Wake uses the saved place immediately.
Helm reacquisition ends the hold; a displacement over 32 blocks or 45 degrees yields, capturing
a new place if slow or coasting first if fast. Powered gyros defer only while a place is held.

The as-is `ship-hold.txt` releases soon after ascent: it is a **fast** release, not a slow fixture.
`SHIP_COAST` reports nearest-body speed/spin, held status, and displacement from the first
post-release audit at exact elapsed 23/428/833/1238/1643/2048/2453 server ticks. That audit origin
is one tick later than the historical S4 release-callback origin; compare raw numbers with this
phase noted, not client wait durations. The `motion` command uses this audit origin during coast
and the held place after capture. Hold windows start at **capture**, not helm release. Saved
coasting records use the current pose; they do not preserve the audit's release origin.
Production never puts a hull to sleep (loaded hulls keep ALWAYS tickets), but a space hull left unloaded by a restart wakes at its current place. The scripts'
sleep/wake exclusions remain development-only. These home-hull probes do not establish real
multi-hull/moon/foreign-actuator or crash-recovery acceptance.

## Moving-moon probe

`moon-pack/` is a **development-only** datapack, not a player example. It defines one sun,
Probe Home (slot 0, with the experience starter ship), Probe Outer (slot 1), and an appended
full-surface Probe Moon (slot 2). The moon's 8000-block orbit takes 48000 ticks and inherits its
parent's 30000-block / 120000-tick motion. Both use the ordinary 2048-block reach.

Before **each** run, choose a never-used world name. Create only its `datapacks` directory;
there must be no `level.dat`. From the repository root:

```sh
export JAVA_HOME=/path/to/jdk-21
timeout 20 bash -c 'set -eu
world=moon-UNUSED-NAME
test ! -e "run/$world"
mkdir -p "run/$world/datapacks"
cp -a tools/probe/moon-pack "run/$world/datapacks/moon-pack"
sed -i -e "s/^level-name=.*/level-name=$world/" \
    -e "s/^level-seed=.*/level-seed=20261009/" \
    -e "s/^level-type=.*/level-type=moon_probe\\\\:moon/" \
    -e "s/^initial-enabled-packs=.*/initial-enabled-packs=vanilla,file\\/moon-pack/" \
    run/server.properties'
timeout 1500 bash tools/probe/dev.sh tools/probe/moon.txt 900 > build/pi-logs/moon-UNUSED-NAME.out 2>&1
timeout 20 python3 tools/probe/trip-verdict.py build/dev-client.log --moon
```

The directory is discovered as `file/moon-pack` while the fresh world's registries load.
`level-type=moon_probe\:moon` selects its world preset, which names `moon_probe:moon` as its
system; `starter_ship` explicitly opts into the existing structure. No resource injection,
`/reload`, normal-preset tag or production pack edit is needed. `mod_data` and Create's
built-in dynamic pack are enabled automatically. Keep `initial-disabled-packs` empty.
Copy both logs before the next run. Screenshots go to `run/screenshots/`; copy them to the
run's evidence folder too.

The runner selects the same trip gates for **exactly** `tools/probe/moon.txt`, using slot 2.
The default trip verdict requires experience moon Fleck, slot 8. The shorter burn waits directly for NOTICE,
without trying to re-aim after the arrival controller has taken over. `navplanets moon-lock`
logs centre/surface distance and closing speed (blocks/s); `s-chart` logs height above chart
Y=128 and loaded/total chunks intersecting the hull's physical footprint. High-altitude
UNKNOWN landing classification is deliberately suppressed, not missing terrain.

`moon-looks.txt` takes surface pictures using explicit admin visits and sky skips, **not travel
acceptance**. `moon-orbit-looks.txt` takes supplemental pictures at altitude 1658, not on the
surface. Both require the following environment for their `dev.sh` run:

```sh
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dstarfold.helmProbe=true -Dstarfold.packProbe=true"
```

Allow remote meshes to build before shooting. Near-ground `SpaceView.chartFar` currently clips
these distant bodies even when their meshes are ready; orbital pictures must not be reported
as proof of a surface view. `skylook <slot>` aims a ready chart or controlled personal-flight
camera at the evaluated body centre without moving the observer.

Datapack acceptance helpers (inert outside a probe):
- `system 1234` logs the received celestial model at the current simulation tick and at tick 1234, including every sun, planet centre, name, size, depth and spin; it does not change time.
- `sunlook 0` aims the camera at the zero-based authored sun, on a ready chart or in personal flight; it does not move the player.
- `breaknear 1 -1 0` sends the normal client block-breaking action at an offset from the player's client-chart block position. Use a server `check` afterward; an air target is not proof of mining.

## Atlas recovery checks

The `atlas` commands run only in the development probe. `atlas ships` logs the
player-visible ship directory and current target. `atlas ship <UUID>` selects the
matching ship through its visible map button (including page navigation), and
`atlas target` clicks the visible target button. `atlas require <UUID>` asserts
the selected target. Names must be unique for automated button selection.

`atlas away <UUID> <min-distance>` requires an untracked player at least that far
from the directory position. `atlas hull <UUID> <min> <max>` additionally requires
one matching live client hull; a persistent directory entry alone cannot pass it.
`atlas aboard <UUID>` checks the actual tracked hull or current helm authority.
These commands do not wake, move, or teleport a ship.

For a creative-mode recovery route, use `fly on`, then
`atlas approach <UUID> <distance> <timeout-ticks>` to fly toward the catalog position.
Once the hull is loaded, `atlas approachlocal <UUID> <x> <y> <z> <distance> <timeout>`
aims at an entrance relative to the hull bounding box's center X/Z and minimum Y.
The Y coordinate is the destination **eye** height. For a hull with one helm,
`atlas approachhelm <UUID> <x> <y> <z> <distance> <timeout>` instead anchors
the offsets to that block's minimum corner, so changes in plot bounds cannot
shift the entrance height. These are eye-position offsets: choose a height that
fits the player's current collider orientation inside the doorway. A height that
works on an upright hull can hit the lintel after the hull tilts.
`atlas approachhelmcenter <UUID> <x> <y> <z> <distance> <timeout>` uses the
same helm anchor but targets the player's bounding-box center instead of the eye.
It pulses forward input near the waypoint, accounts for vertical momentum, and
spaces jump rising edges to avoid the vanilla double-tap flight toggle.
For a two-block doorway, target its center and confirm both doors are open;
route around the exterior before approaching the interior waypoint. Flight
waypoints have no obstacle avoidance: starting in the cabin requires a clear route
through its furniture before using an exterior waypoint. Ship universe
attitude alone does not tell you the hull's tilt in the player's Level frame.
`atlas cabin <UUID>` logs the current local eye, feet, bounding box, up vector,
tracking identity, and door states for diagnosis.
The driver holds normal flight keys and restores the creative flight toggle after deck contact; it requires
space, a flight permit, and no active helm or screen. It does not validate survival
EVA equipment, oxygen, or fuel. Keep flight enabled through the entrance before
landing and asserting `atlas aboard`.

`atlas useblock <namespace:block>` raycasts and right-clicks a reachable matching
block in one tick, avoiding stale hit results between scripted aim and click.
`atlas cargo <UUID> <label>` asks the server for each loaded container's `Items`
data. Inspect the resulting server chat for item IDs and counts; client block
entities do not necessarily contain synchronized inventories, and command
completion alone is not an inventory assertion. Preserve both client/server logs
and restart the dedicated server between persistence phases.

For a fixture whose UUID is only known after spawning, `console <absolute-path>`
waits for a text file and inserts its commands into the running script, then deletes
that file. It returns to the console after those commands; include `quit` in the
inserted commands to end the session. Numeric key events use `press <GLFW-code> 1`
and `press <GLFW-code> 0`; `key` accepts symbolic movement bindings instead.
