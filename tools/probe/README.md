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

Datapack acceptance helpers (inert outside a probe):
- `system 1234` logs the received celestial model at the current simulation tick and at tick 1234, including every sun, planet centre, name, size, depth and spin; it does not change time.
- `sunlook 0` aims the camera at the zero-based authored sun, on a ready chart or in personal flight; it does not move the player.
- `breaknear 1 -1 0` sends the normal client block-breaking action at an offset from the player's client-chart block position. Use a server `check` afterward; an air target is not proof of mining.
