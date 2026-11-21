# Probe catalog

This catalog defines a small documented set and a default classification for everything else
in `tools/probe/`. Historical inputs with a known evidence or fixture relationship remain at their existing paths.
Retired one-off inputs are recoverable from Git history.

Status is about documentation and known dependencies, **not a claim that a script passed**.
The 2026-11-12 cleanup used source inspection, static path checks and the 27 finite probe
Python tests: no game execution, Gradle task, replay, archive restoration or performance measurement.
[README.md](README.md) gives shared runtime dependencies and evidence handling.

## Documented runtime entry points

| Entry | Classification | Required fixture / environment | Expected evidence and limit |
| --- | --- | --- | --- |
| [trip.txt](trip.txt) | Active documented route; runtime unverified here | Fresh seed-20261009 `starfold_worlds:experience`; Worlds, Extras starter, Sable/Create/Create Aeronautics; OP `Dev`; configured development server/client and AI desktop | Canonical runner uses `trip-verdict.py`, target 8. Ordered lift/lock/NOTICE/hand-over/descent and session gates; landing site/stair/landed are informational. One-way creative route, not survival or round-trip acceptance. See [setup and gates](README.md#full-trip-verdict). |
| [flight-perf.txt](flight-perf.txt) | Active documented measurement; runtime unverified here | Fresh seed-20261009 `starfold:native_universe`; creative `mayfly`, OP `Dev`, shared desktop/runtime dependencies; identical environment per sample | Low 60s and high 30s summaries, raw CSVs, screenshots, logs and completion/ACK. Generic verifier supplies no performance threshold or artifact completeness check. See [measurement details](README.md#flight-performance). |
| [moon.txt](moon.txt), [moon-pack](moon-pack/pack.mcmeta) | Documented candidate; **blocked on starter fixture** | Fresh seed-20261009 `moon_probe:moon`; pack installed before creation; Worlds and ship runtime; matching starter structure resource | Preset requests `starfold:starter_ship`, absent from tracked resources; tracked template is `starfold_extras:starter_ship`. Direct loader lookup needs a fixture repair or external resource. No clean-checkout replay claim. Intended trip verdict uses `--moon`, target 2. See [dependency gap and intended setup](README.md#moving-moon-probe). |
| [ship-hold.txt](ship-hold.txt), [ship-space-sleep.txt](ship-space-sleep.txt) | Documented targeted diagnostics; runtime unverified here | Fresh experience save per run, seed 20261009, starter `0:0`, only one player/hull, no other forces, unused scoreboards, server `-Dstarfold.shipProbe=true` and shared ship runtime | Positive domain receipts and 100/400/1200-server-tick hold windows, errors <0.1 block and <0.1 degree. Sleep adds deliberate fixture interventions and dormancy/wake checks. Generic completion is insufficient. See [dependencies and limits](README.md#unpiloted-space-hold). |
| [moon-looks.txt](moon-looks.txt), [moon-orbit-looks.txt](moon-orbit-looks.txt) | Supplemental visual diagnostics; share moon fixture blocker | Same authored moon system plus server `-Dstarfold.helmProbe=true -Dstarfold.packProbe=true`; OP/admin visits and mesh-loading waits | Screenshots of administratively selected views; neither travel nor surface-view acceptance follows from orbital pictures. |

[dev.sh](dev.sh) is the shared runtime runner, not a fixture generator. Its resolved-path
selection matters: only canonical trip/moon paths get trip verdicts; independent working
copies get generic completion checks. Its builds, process shutdown and shared output names
are described in [runner behavior](README.md#shared-runtime-dependencies-and-runner).

## Offline utilities

These Python 3 standard-library utilities operate on supplied files and do not launch the
game. Their inputs may be local evidence absent from a clean checkout. Commands below
illustrate interfaces for supplied evidence; no historical evidence was processed in this review.

| Utility / invocation from repository root | Required inputs | Result and boundary |
| --- | --- | --- |
| `python3 tools/probe/verify.py <client-log>` ([source](verify.py)) | A captured client log | Exit 0 requires completion, received chat ACK, no `until` timeout and no selected command rejection. This is script completion, not gameplay acceptance, and does not authenticate log provenance. |
| `python3 tools/probe/trip-verdict.py <client-log> [--moon]` ([source](trip-verdict.py)) | Single-session client log containing ordered `PROBE TRIP` snapshots and command evidence; default target 8, `--moon` target 2 | Prints nine stages; nonzero for failed hard gates/session integrity. The three landing stages are informational. It does not establish a return journey, survival progress or evidence provenance. |
| `python3 tools/probe/light-send-summary.py <run-dir>...` ([source](light-send-summary.py)) | Each directory must contain `dev-server.log` from the matching light-send fixture: four torch-change windows at the fixed ordinary/seam coordinates, expected `Dev` command text, `LIGHT_SEND` and `PROBE RELIGHT_AUDIT` instrumentation | JSON packet/byte/sample-time summaries; fails if four changes are not found. Does not run [light-send.txt](light-send.txt), validate visual correctness or set a performance acceptance threshold. That runtime fixture remains historical/unclassified. |
| `python3 tools/probe/restore-stage2-recording.py <source> <new-output>` ([source](restore-stage2-recording.py)) | Local `artifacts/stage2-recording/manifest.json` and matching `objects/<sha256>`; source must match a manifest entry, output must not exist | Verifies SHA-256 and creates one output file without overwriting. Local archive is not tracked and is not a game-save backup; no clean-checkout restoration promise. See the [recording index](../../docs/stage2-recording/README.md). |

Existing CPU checks are [test_verify.py](test_verify.py), [test_trip_verdict.py](test_trip_verdict.py),
[test_dev_stop.py](test_dev_stop.py) and [test_ship_scripts.py](test_ship_scripts.py).
Their entry is `python3 -m unittest discover -s tools/probe -p 'test_*.py' -v`.
All 27 passed during this cleanup. They use synthetic evidence, source checks or mocked shutdown
processes; this does not demonstrate real-game replay. The shutdown tests need a writable
existing `build/` parent for temporary directories.

## Escape Velocity acceptance runner

[ACCEPTANCE.md](ACCEPTANCE.md) documents `acceptance.py` and its three named slices. The runner stages a fresh pinned development instance, records each client/server session and checks scenario-specific receipts. The two Stage 2 fixture slices are prepared for a fresh replay; see the acceptance guide for their evidence boundaries and authorization requirement.

## Historical and unclassified inputs

**Default rule:** every runtime script, alternate runner and generator not explicitly in the
documented runtime table is historical/unclassified and **not supported for clean-checkout
replay** by this catalog. This includes scripts with names such as `fresh`, `acceptance`,
`verify`, `rejoin` or `final`; a name is not an acceptance receipt. They may depend on prepared
fixtures, modpack/KubeJS commands, local flags, saved hull UUIDs/storage addresses, player
position, inventory, earlier phases, generated scripts, local console batches or ignored
`build/` evidence. Dependencies have not been exhaustively reconstructed for each file.

The following inspected examples ground that boundary; this table is a family index, not an
exhaustive per-script audit or a claim that every family member shares every dependency.

| Historical input / family | Observed dependency or reason replay is not supported here | Evidence use |
| --- | --- | --- |
| Other `pack-*.txt` | Modpack/fixture experiments; dependencies must be recovered per script rather than inferred from the scoop example. | Historical resource, boss, fuel, oil and smoke observations. |
| `stage2-survival-*.txt` | Continuations of evolving saved player/base/inventory state. For example [natural-distillation-rejoin](stage2-survival-natural-distillation-rejoin.txt) requires survival state, exact oil-bucket/bucket counts and an absolute `build/stage2/client-console.txt` input; the script alone is not the full session. | Use the [recording index](../../docs/stage2-recording/README.md) and [runtime report's final conclusion](../../docs/stage2-runtime-2026-11-10.md) for historical status. Prior pending lists are not restart instructions. |
| Other `stage2-*.txt` | Domain fixtures and stateful phases. [stage2-industry-rejoin.txt](stage2-industry-rejoin.txt) explicitly requires the same disposable fixture after `liquid-checkpoint`, a new server JVM, `industrytest` and fixed tank locations. | Controlled industry/oil/ocean/transport evidence must remain distinct from ordinary survival inventory and acceptance. See the [Stage2 report](../../docs/stage2-runtime-2026-11-10.md). |
| `rocket-*`, `space-trip*`, `space-loop*`, other `ship-*` and recovery scripts | Saved hull/player state, ordered phases, generated inputs and/or coordinates. [rocket-attitude-rejoin.txt](rocket-attitude-rejoin.txt) names the existing `rocket-test-11-b` save; [space-trip4-rejoin.txt](space-trip4-rejoin.txt) expects a loaded typewriter helm to reacquire. The two documented hold scripts are the explicit exceptions above. | Persistence/flight/boarding diagnostics tied to the original setup, not interchangeable fresh-world routes. |
| [gen_sim.py](gen_sim.py), [gen_loop.py](gen_loop.py), other `*-generate.py` | Fixture/script generators are not game validators. `gen_sim.py <out> [face-size origin-x origin-z]` emits destructive seam setup at assumed face/storage positions (defaults 2048,0,0). `gen_loop.py 1\|2\|3 ...` writes `loop-p1.txt`/`loop-p2.txt`/`loop-p3.txt` in the working directory; phase 3 consumes A/B chest coordinates from the earlier saved route. | Preserve generated input and phase state with logs; generation alone establishes no gameplay result. |
| [sp.sh](sp.sh) | Alternate runner for an existing single-player fixture. Requires a prepared save and the local development runtime. | Targeted diagnostics on a prepared copy of a save. |
| All remaining `.txt`, Python helpers and fixtures | Unclassified for replay; includes seam, rendering, performance, recovery and ad hoc diagnostics not promoted above. Original fixture/saved-world/evidence requirements must be established individually. | Retained source/history and diagnosis aids; generic completion or file presence does not prove current acceptance. |

To promote another route, document the actual preset/mods/flags, generated and external
fixtures, saved-world and phase prerequisites, output locations and domain verdict. Supply
separate evidence of any runtime replay claimed. This catalog deliberately records the
current unknowns rather than treating all versioned scripts as reproducible regressions.
