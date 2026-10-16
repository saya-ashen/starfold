#!/usr/bin/env bash
# usage: dev.sh <probe-script> [client-timeout-seconds]; restarts the dev server, runs one scripted client session
cd "$(dirname "$(readlink -f "$0")")/../.." || exit 1
: "${JAVA_HOME:?set JAVA_HOME to a JDK 21}"
# steam-run no longer puts its libraries on the library path (system generation of 2026-10-04): without the GL
# dispatch library and the driver directory there, GLFW fails with "GLX: Failed to load GLX".
export LD_LIBRARY_PATH="$(steam-run sh -c 'dirname "$(readlink -f /usr/lib64/libGLX.so.0)"'):/run/opengl-driver/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
# Anchor to the Java executable and this project's exact ModDevGradle launch arguments, never a shell command.
PATTERN='^([^ ]*/)?java .* net\.neoforged\.devlaunch\.Main @'"$PWD"'/build/moddev/(client|server)RunProgramArgs\.txt( |$)'
SERVER_PATTERN=${PATTERN/\(client|server\)/server}
# The game window opens inside the AI desktop where there is one, never on the desktop someone is working at.
# Where ai-desktop-run exists but no AI desktop is running, the client fails to start instead of falling back.
# The client joins the port this checkout's server listens on, so that probes of several checkouts can run at once,
# each with its own server-port in run/server.properties.
PORT=$(sed -n 's/^server-port=//p' run/server.properties 2>/dev/null); PORT=${PORT:-25565}
DESKTOP=()
command -v ai-desktop-run > /dev/null && DESKTOP=(ai-desktop-run --)
shutdown_failed=0
server_started=0
stop() {
    local p watcher forced=0 deadline=$((SECONDS+60))
    local -a watchers=()
    for p in $(pgrep -f "$SERVER_PATTERN"); do
        (
            n=0; next=$SECONDS
            while kill -0 "$p" 2>/dev/null; do
                timeout 4 "$JAVA_HOME/bin/jstack" "$p" > "build/jstack-stop-$p-$n.txt" 2>&1
                n=$((n+1)); next=$((next+5))
                while ((SECONDS<next)) && kill -0 "$p" 2>/dev/null; do sleep 0.1; done
            done
        ) & watchers+=("$!")
    done
    pkill -TERM -f "$PATTERN" || true
    while pgrep -f "$PATTERN" >/dev/null && ((SECONDS<deadline-1)); do sleep 0.1; done
    if pgrep -f "$PATTERN" >/dev/null; then
        # SIGQUIT asks HotSpot itself to dump threads to the server log, independently of attach/jstack.
        for p in $(pgrep -f "$SERVER_PATTERN"); do kill -QUIT "$p"; done
        while ((SECONDS<deadline)); do sleep 0.1; done
        if pgrep -f "$PATTERN" >/dev/null; then
            echo "FAIL: server/client did not exit within the 60 s shutdown grace; forcing KILL" >&2
            shutdown_failed=1; forced=1
            tail -200 build/dev-server.log > build/dev-server-stop-tail.log
            pkill -KILL -f "$PATTERN" || true
        fi
    fi
    for watcher in "${watchers[@]}"; do wait "$watcher"; done
    if ((server_started)) && ! grep -q "All dimensions are saved" build/dev-server.log; then
        echo "FAIL: server shutdown did not complete: missing 'All dimensions are saved'" >&2
        ((forced)) || tail -200 build/dev-server.log > build/dev-server-stop-tail.log
        shutdown_failed=1
    fi
    return "$shutdown_failed"
}
cleanup() { local result=$?; trap - EXIT; stop || result=1; exit "$result"; }
trap cleanup EXIT
stop || exit 1
timeout 1700 ./gradlew classes --no-daemon -q > build/dev-classes.log 2>&1 < /dev/null || { grep -E "error" -A3 build/dev-classes.log; exit 1; }
timeout 1700 ./gradlew runServer --no-daemon > build/dev-server.log 2>&1 < /dev/null & server=$!
server_started=1
for i in $(seq 1 900); do grep -q "Done (" build/dev-server.log && break; grep -q 'BUILD FAILED' build/dev-server.log && break; sleep 1; done
grep -q "Done (" build/dev-server.log || { echo "server failed"; tail -20 build/dev-server.log; exit 1; }
# Create's Registrate now and then loses a race while the client loads its mods ("Found unused register callbacks");
# the client then sits in a broken state until the timeout. Seen about one start in eight: start it once more.
for attempt in 1 2; do
    JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dstarfold.probe=$PWD/$1" timeout ${2:-300} "${DESKTOP[@]}" steam-run ./gradlew runClient --no-daemon -PdevProbe -PdevProbePort="$PORT" > build/dev-client.log 2>&1 < /dev/null & client=$!
    while kill -0 $client 2>/dev/null; do grep -q "Found unused register callbacks" build/dev-client.log && { pkill -TERM -f "$(echo "$PATTERN" | sed 's/(client|server)/client/')"; break; }; sleep 2; done
    wait $client; client_exit=$?
    grep -q "Found unused register callbacks" build/dev-client.log || break
    echo "client lost Registrate's loading race (attempt $attempt)"; sleep 3
done
echo "client exit $client_exit"
for p in $(pgrep -f "$PATTERN"); do timeout 15 "$JAVA_HOME/bin/jstack" $p > build/jstack-$p.txt 2>&1; done
sleep 1; stop || shutdown_failed=1
wait "$server" 2>/dev/null
trap - EXIT
((shutdown_failed==0)) || exit 1
grep -n "PROBE" build/dev-client.log | sed 's/.*PROBE/PROBE/' | cut -c1-260
grep -n "ERROR\|Exception\|Caused by" build/dev-client.log | grep -v "flywheel\|data fixer\|physics properties\|missingTag\|Veil\|GlslNode" | head -20 | cut -c1-260
echo "--- server"; grep -n "ERROR\|Exception\|Caused by\|lost connection\|moved wrongly\|moved too" build/dev-server.log | grep -v "flywheel\|data fixer\|physics properties" | head -20 | cut -c1-260
# Trip probes have gameplay verdicts; generic probes keep their completion/timeout policy.
if [[ "$(readlink -f "$1")" == "$PWD/tools/probe/trip.txt" ]]; then
    python3 tools/probe/trip-verdict.py build/dev-client.log || exit 1
elif [[ "$(readlink -f "$1")" == "$PWD/tools/probe/moon.txt" ]]; then
    python3 tools/probe/trip-verdict.py build/dev-client.log --moon || exit 1
else
    python3 tools/probe/verify.py build/dev-client.log || exit 1
fi
exit "$client_exit"
