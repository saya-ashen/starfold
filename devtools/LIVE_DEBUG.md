# Live game inspection

`starfold_devtools` exposes a local file mailbox so an assistant can inspect the user's existing client session without launching another game. Install matching core and devtools jars, restart once, then keep playing. The mailbox starts on the first client tick (including the title screen). No network port is opened.

The caller needs filesystem access to the **actual game instance directory**. A different machine requires an existing filesystem/remote execution connection; this feature does not establish that connection. Copying jars into a running instance does not hot-load Java changes.

```sh
python3 tools/devtools/live.py --game-dir /path/to/instance status
python3 tools/devtools/live.py --game-dir /path/to/instance snapshot
python3 tools/devtools/live.py --game-dir /path/to/instance screenshot
python3 tools/devtools/live.py --game-dir /path/to/instance far_watch --face 0 --x 512 --z 512 --radius 1
python3 tools/devtools/live.py --game-dir /path/to/instance far_snapshot
python3 tools/devtools/live.py --game-dir /path/to/instance far_stop
```

`far_watch` without coordinates watches the current **observer** tile, which may differ from the distant terrain under the crosshair. Explicit face/x/z use Starfold chart-face block coordinates, not storage/world coordinates. Radius is 0–2 tiles. Existing watch bounds (2400 client ticks / 120 seconds) apply. `far_snapshot` returns the retained report and freshly sampled, loaded-only raw center-tile data directly in JSON. `far_stop` retains evidence; call `far_snapshot` afterward to retrieve it. Existing `/sffar` commands share the same capture and cannot create a second simultaneous watch.

Operations:

| Operation | Result |
| --- | --- |
| `status` | World presence, dimension, client time, player position/velocity/orientation/health/vehicle type, camera pose, Starfold observer planet/face/chart eye, pause/screen/FPS and available operations |
| `snapshot` | Status plus crosshair target (loaded client block/fluid state or entity type/id), current bounded far-view CPU evidence |
| `screenshot` | PNG path plus request-time status; completion means the PNG was written |
| `far_watch` | Begin bounded region capture; errors if already watching or observer unavailable |
| `far_snapshot` | Structured existing far-view report, without requiring an in-game export command |
| `far_stop` | Stop capture and retain evidence |
| `profile_start`, `profile_stop` | Request existing CPU/GPU profiler start at next frame boundary; stop disables collection immediately and exports at next boundary; response is acknowledgement, not proof of recording/export completion |

The mailbox is at `starfold-devtools/live/` below the instance, with session discovery, requests and responses. The CLI supplies a fresh request UUID, matching session ID and expiry. Responses include success/error and correlation IDs. No arbitrary chat/server commands or world edits are exposed. `/sflive` reports the location, `/sflive stop` stops access, and `/sflive start` restarts it. JVM option `-Dstarfold.devtools.live.disabled=true` disables automatic startup. Only install this development module in instances where local inspection is intended; local processes with access to the instance can use the mailbox.

Requests are processed off the game thread; actual game reads and framebuffer capture run on the client/render thread. PNG encoding and mailbox I/O run in the background. A stalled client produces a bounded timeout, not an invented state. Reads do not load absent chunks. Inspection is on demand; screenshots and detailed raw reports have a one-time cost and are not suitable for per-frame polling. Screenshot files remain in `screenshots/` until manually removed.

Evidence limits:

- All world values are **client observations**, not an authoritative server snapshot. Multiplayer server internals, arbitrary machine inventories and complete ship simulation state are not exposed by this version.
- Far-view CPU geometry and lifecycle evidence do not prove visible GPU pixels. A screenshot supplies the actual last completed framebuffer, but its timestamp and the state/report timestamps can differ. No atomic whole-world/render snapshot is claimed.
- Missing/ unloaded/ unavailable data stays explicit. An empty far snapshot before starting a watch is not evidence of correct rendering.
- The CLI exits with failure on unavailable discovery, protocol errors or timeout. A leftover session file after a process crash cannot make a dead client respond.
- This enables observation while playing. Loading changed mod Java code still normally requires restarting Minecraft.

A practical session: the user reproduces the issue and leaves the scene visible; the assistant calls `snapshot` and `screenshot`, selects a chart region, starts `far_watch`, then fetches `far_snapshot` while the user reproduces the movement. No second game instance or automated gameplay route is needed.

### Planet chunk timing

In a planet world, run `/sfprofile start`, reproduce the slow loading, then run `/sfprofile stop`.
The same capture also works through `profile_start` and `profile_stop` in the live CLI. The
profiler writes matching `profile-*.csv` (frame timings), `chunks-*.csv` (server and client chunk
observations), and `generation-*.csv` (Minecraft's generation stage events) in `starfold-devtools/`.
Run `python3 tools/devtools/chunk_report.py /path/to/chunks-123.csv` from the repository with the
companion files beside the input. It reports matched stage waits, generation gaps, neighbor arrival,
mesh and frame timings, pairing coverage, and dropped events in one pass. Stage summaries can use
different cohorts. Generation stage elapsed time can include async work; adjacent-stage gaps include
dependency and scheduling waits. Join generation with the in-process chunk trace only for an
integrated server. For individual samples, filter by
`chunk_x` and `chunk_z` and compare `elapsed_ns`, which uses the same capture start as the frame
profile. `duration_ns` contains wall time for timed work, and the profile summary shows sample
counts and timing percentiles. Generation-layer start and resume, mesh scheduling, compilation, and section visibility keep up to
32,768 events each. Other stages keep up to 8,192 each. The summary reports excess events as `dropped`.

| Events | What they measure |
|---|---|
| `FULL_REQUEST`, `FULL_DEPENDENCIES_READY` | Start of accessible-chunk preparation and resolution of its range future. That future combines the center chunk and eight neighbors. |
| `FULL_DIRECT_SUBMITTED`, `FULL_CALLBACK`, `FULL_READY` | The planet's continuation is submitted directly to the main thread, starts there, and produces a successful result. `FULL_CALLBACK` duration covers all waiting after dependency resolution. |
| `FULL_SORTER_ENQUEUED`, `FULL_DISPATCHED` | Older captures mark when the priority sorter accepted and dispatched the continuation. |
| `FILL_QUEUED`, `FILL` | Time from submitting the fill until its worker holds the section locks, and time spent filling. |
| `COLUMN_EVALUATION`, `SECTION_WRITE` | The two sub-totals inside `FILL`. Column evaluation includes terrain and liquid lookup. Writes include the air scan, section writes, heightmaps and fluid postprocessing marks. |
| `BASE_HEIGHT` | Duration of one height-only generator query. These queries can overlap chunk preparation. |
| `GENERATION_SORTER_QUEUE`, `GENERATION_WORKER_QUEUE` | Time from posting a generation task to the priority sorter until it dispatches the task, then time until the worker starts it. A center may be dispatched repeatedly. `GENERATION_DISPATCH_LEVEL` records the holder's queue level at dispatch in `section_y`. |
| `TICKET_PLAYER`, `TICKET_NATIVE_VIEW`, `TICKET_NATIVE_CHART`, `TICKET_SPACE_VIEW`, `TICKET_OTHER` | Active ticket snapshot and explicit additions/removals in the planet dimension. `section_y` holds the ticket level, or `-level-1` for removals. Ticket sources can influence neighboring coordinates. |
| `EMPTY_READ`, `EMPTY_MAIN_QUEUE`, `EMPTY_MAIN_WORK` | Residual async read until main-thread submission, wait on the main-thread executor, and execution of saved-chunk deserialization or empty-chunk creation. The read interval can include async scheduling. |
| `GENERATION_LAYER_START`, `GENERATION_LAYER_LAST_DEPENDENCY`, `GENERATION_LAYER_RESUME` | Scheduling of a generation-task layer through `NOISE`, latest observed completion among futures that were still pending when registered, and the start of the next layer. These rows use `section_y` for the Minecraft status index and `source_x/source_z` for the last observed dependency. Pair only unique center/status sequences. Tasks can have completed dependencies before observation starts. |
| `FULL_CONVERSION_SUBMITTED`, `FULL_CONVERSION_DISPATCHED`, `FULL_CONVERSION` | Submission to the conversion sorter, dispatch to the main thread, and main-thread conversion including chunk post-load work. Compare the generation `full` event with the submission and execution markers. |
| `SABLE_COLLISION_CHUNK` | Wall time of the synchronous chunk reads that feed Sable physics tickets. |
| `SERVER_NEW_LOAD`, `SERVER_LOAD` | Server load notification for a new or existing chunk. The notification can precede `FULL_READY`. |
| `SEND_QUEUED`, `SENT` | Vanilla send-queue entry and transmission for the local integrated-server player. Native planet streaming takes over the vanilla queue. |
| `IMAGE_SENT` | Planet image packet enqueued through the native stream. Duration covers column lookup and packet construction up to enqueue. |
| `IMAGE_FIRST_ATTEMPT` | First observed selection by the image stream before owner resolution. Time until send includes failed owner lookups and later retries. An attempt may remain unsent when the capture ends or the view moves. |
| `CLIENT_LIGHT_APPLIED`, `CLIENT_SECTION_VISIBLE` | The chunk packet's light update finished, and a section was first observed in the native compile loop's visible list after load. Visibility includes `section_y`. |
| `CLIENT_LOAD`, `CLIENT_MESH_SCHEDULED`, `CLIENT_MESH_COMPILED`, `FIRST_DRAW` | Client packet applied, task creation and CPU compilation for rebuilds that produce nonempty native geometry, and first native section draw after load. Mesh and draw events include `section_y`. Subtract compile duration from its timestamp to recover compile start. Task creation precedes dispatch and can include time waiting for neighbor checks. Compile-to-draw includes upload, publication and visibility. |

Server stages require an integrated server. Client stages also work in multiplayer. Use the client
chart coordinates in `IMAGE_SENT` to match `CLIENT_LOAD` and `FIRST_DRAW`. For identity images,
`source_x` and `source_z` match the corresponding server generation events. Composite or rotated
images leave the source fields empty. An absent draw can indicate that the chunk stayed offscreen,
was empty, unloaded, or was still waiting at the end of the capture.

### Integrated server stalls

Launch with `-Dstarfold.traceLongTicks=true` to log the vanilla profiler tree when tick work or the
between-tick phase reaches 100 ms. The between-tick phase can include scheduled tasks and idle time.
The same flag logs `ServerChunkCache.getChunk` calls taking at least 20 ms, with status, load flag,
monotonic start time and caller stack. Nested reads can overlap. Use this flag for diagnosis only,
and compare runs with identical instrumentation.

### Render timing

Fine performance diagnostics are enabled only inside actual `/sfprofile start` / `profile_start`
recording frames. Pending start is inactive; actual frame end closes fine collection immediately.
Stop closes an open frame promptly and drops further events; export/interval bookkeeping waits
for the next boundary to preserve existing pacing intervals. No separate command is needed.
The main mod exposes `dev.starfold.api.RenderWorkDiagnostics`; devtools owns collection.
Production call sites use `active()`, batch-local counts with `add(Metric, count)`, and
`long timer = start(); try { ... } finally { finish(Metric.SCOPE_NS, timer); }`.
A timer is an opaque single-use handle, not a timestamp. Inactive start returns zero without
reading a clock; inactive adds/finishes do nothing. A fixed pool admits 256 simultaneous
timers; exhaustion omits further timers until a slot is released. Stale handles cannot cross
frame/capture boundaries, even when clock readings tie or are zero/negative. Elapsed time
uses unmodified clock readings. Do not put clocks or diagnostic objects inside per-cell loops.
`captureGeneration()` supplies a stable capture ID while active for pending diagnostic reasons;
reasons must not survive stop/restart or inactive invalidation/view activity.

CSV retains the existing 24-column prefix and appends every `RenderWorkDiagnostics.Metric`
in enum order. New enum members automatically become export columns; consumers should resolve
columns by header name rather than assume a fixed metric count or total column count.
CPU elapsed fields have exact integer `_ns` and derived `_ms` columns; operation counters
are exact integer totals for each frame. Zero means no observed work at the documented
instrumented site within the frame, not absence of work outside those scopes. These are
render-thread wall times, including waits, not thread CPU consumption. Fine metrics issue no
GPU queries. Each export also saves `profile-*.csv.summary.txt` with totals, means,
nearest-rank p95, maxima and scope notes. Storage is bounded at 120000 frames. For `M =
RenderWorkDiagnostics.Metric.values().length`, fine storage is `120000 * M * 8` bytes;
CPU/GPU/interval arrays add 22,080,000 bytes, for `120000 * (M + 23) * 8` bytes combined.
Each additional metric therefore adds 960,000 bytes (about 0.916 MiB) at the frame cap.
These figures exclude JVM array headers, query/state arrays and export scratch storage;
work-summary scratch is one reusable `long[recordedFrames]` (at most 960,000 payload bytes).
No fine array allocation occurs per frame. `ProfileMetricExportTest` checks all enum columns,
exact raw CSV integer round trips, per-metric summaries and a 256 MiB recording-array budget
at the production frame/query limits. Revisit the frame cap or storage design if enum growth
exceeds that budget; a bounded frame count alone does not bound bytes independently of schema.

Native metrics time first-demand listed construction (shared across both passes), required
readiness, whole water gate with separate palette/scan batches, and coordinate mapping.
`NATIVE_WATER_PALETTE_NS` times only the initial palette gate. Lazy per-section
`maybeHas` calls reached during the height traversal are included in
`NATIVE_WATER_SCAN_NS`; `NATIVE_WATER_PALETTE_SECTIONS` counts both initial and lazy
palette examinations. Scan time therefore includes palette work as well as height/fluid
access. `NATIVE_WATER_SURFACE_CELLS` counts actual height attempts and
`NATIVE_WATER_FLUID_LOOKUPS` counts actual fluid attempts, including throwing attempts.
A full fallback still reads 256 heights even when dry-section proofs eliminate every
fluid lookup; that remains a fallback, not a fast accept.
The local classification array has one byte per section on valid-metadata fallbacks.
The saved Overworld uses the standard -64..319 bounds (24 sections), hence 24 payload
bytes per allocating gate, plus JVM array header/alignment. At 524 allocating calls
per frame that is 12,576 payload bytes (about 20,960 bytes with typical 40-byte arrays).
This is an allocation cost estimate, not measured heap traffic or an FPS guarantee;
extra palette checks can also cost work without reducing fluid reads.
They form an **incomplete partition** of `visible()`: chunk lookup, dirty iteration, early
no-level/empty-dirty exits, collection maintenance, covered/result insertion and copying
remain outside these timers. Counters include actual predicate calls, pass splits, required
readiness rejections and explicit fallback entries, including exceptional exits. Submitted
sections are repeated section/layer records, not unique sections. Dirty minus covered is
not a valid missing-readiness count.

FAR prepare contains view construction/comparison and visible range collection. Arena command
assembly runs in `drawLods` outside prepare, nested in draw timing; compaction may occur outside
FAR. Do not add inclusive parent/child times or subtract scopes spanning different calls.
`far_full_rebuilds` and `far_cache_reuses` describe stable visible range/order selection.
Arena reuse/rebuild counters describe `Commands.begin`; arena commands count final merged
subdraws for every assembled/reused batch. Fades remain dynamic: unsupported packed-data
and fade-update fields have been removed. Raw submitted indices remain index counts;
submitted vertices are logical quad vertices (`indices / 6 * 4`) summed across submissions,
not unique vertices or hardware shader invocations. Repeated draws/passes count repeatedly.

Invalidation event counters record actual events only in their active frame. Prepare reason
flags attribute applicable causes within the same capture; pending causes cannot leak across
stop/restart. Flags overlap and do not sum to rebuilds. Cold counts geometry plus a separate
cold miss. Fade means published topology fade completion, not time passage; culling identifies
forced visibility reevaluation. See `build/fine-performance-diagnostics-20261113/api-contract.md`
for exact field semantics. Its READY-contract marker establishes schema readiness; native/far
source alignment and final integrated verification are separate from runtime evidence.
