# Dev probe scripts

Scripted in-game checks for the development client (`-Dstarfold.probe=<script>`, see `DevProbe`).
Working copies live in `build/probe/` (ignored); these are the versioned originals.

- `dev.sh <script> [timeout]` restarts the dev server, runs one scripted client session, dumps `jstack` of anything still running, then stops the server.
- `gen_sim.py <out> [face-size origin-x origin-z]` builds the seam interaction fixture (redstone, pistons, water, light, mob chase) on an unrotated and a rotated face seam.
- `gen_loop.py 1|2|3 [a-chest x,y,z] [b-chest x,y,z]` builds the three sessions of the A→B→A loop; phase 3 needs the chest positions printed by phase 1.

## Full-trip verdict

The canonical script is `tools/probe/trip.txt` (not an ignored working copy). Use the experience
preset, a fixed `level-seed`, and a **new** level name for each run. Example, from this worktree:

```sh
export JAVA_HOME=/nix/store/2cv1b5y6h029cvg07c944pq89fszwbya-openjdk-21.0.12.1+1/lib/openjdk
timeout 20 mkdir -p build/pi-logs
timeout 20 sed -i -e 's/^level-name=.*/level-name=trip-UNUSED-NAME/' -e 's/^level-seed=.*/level-seed=20261009/' run/server.properties
timeout 1500 bash tools/probe/dev.sh tools/probe/trip.txt 900 > build/pi-logs/trip-UNUSED-NAME.out 2>&1
timeout 20 python3 tools/probe/trip-verdict.py build/dev-client.log
```

Run serially and copy both `build/dev-client.log` and `build/dev-server.log` before the next
run overwrites them. `PROBE_GRADLE_TIMEOUT=1500` permits the first classes build 1500 seconds;
subsequent classes builds default to 900, and the server launch is bounded at 900 seconds.
The runner selects the trip verdict **only for the exact canonical script path**. Other scripts
(including `build/probe/trip.txt`) retain `verify.py`'s completion/timeout policy.

`trip-verdict.py <client-log>` prints eight gameplay stages plus a session/integrity stage.
Hard gates: lift-off/space, successful aim and planet-4 lock, cruise/NOTICE, chart hand-over to
planet 4 with pilot authority retained, measured descent below clearance 60, and completion
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

CPU verdict/runner tests: `timeout 40 python3 -m unittest discover -s tools/probe -p 'test_*.py' -v`.
See [trip-results.md](trip-results.md) for the investigation and all eight runs (five final).

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
Production dormancy remains disabled: loaded hulls still receive ALWAYS tickets. The scripts'
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
export JAVA_HOME=/nix/store/2cv1b5y6h029cvg07c944pq89fszwbya-openjdk-21.0.12.1+1/lib/openjdk
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
The default trip verdict still requires slot 4. The shorter burn waits directly for NOTICE,
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
