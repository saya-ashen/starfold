#!/usr/bin/env bash
# usage: sp.sh <probe-script> <save-name> [seconds]; save must already exist under run/saves.
set -eu
cd "$(dirname "$(readlink -f "$0")")/../.." || exit 1
: "${JAVA_HOME:?set JAVA_HOME to a JDK 21}"
export LD_LIBRARY_PATH="$(timeout 60 steam-run sh -c 'dirname "$(readlink -f /usr/lib64/libGLX.so.0)"'):/run/opengl-driver/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
PATTERN='^([^ ]*/)?java .* net\.neoforged\.devlaunch\.Main @'"$PWD"'/build/moddev/clientRunProgramArgs\.txt( |$)'
[[ "$2" != */* && -f "run/saves/$2/level.dat" ]] || { echo 'Prepare a copy under run/saves first'; exit 1; }
if timeout 60 pgrep -f "$PATTERN" >/dev/null; then echo 'Dev client already running; refusing to interrupt it'; exit 1; fi
stop() {
    timeout 60 pkill -TERM -f "$PATTERN" || true
    local deadline=$((SECONDS+60))
    while timeout 60 pgrep -f "$PATTERN" >/dev/null && ((SECONDS<deadline)); do sleep .2; done
    if timeout 60 pgrep -f "$PATTERN" >/dev/null; then timeout 60 pkill -KILL -f "$PATTERN" || true; return 1; fi
}
cleanup() { local result=$?; trap - EXIT; stop || result=1; exit "$result"; }
trap cleanup EXIT
JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dstarfold.probe=$PWD/$1" timeout "${3:-2400}" steam-run ./gradlew runClient --no-daemon "-PdevProbeSave=$2" > build/space/sp-client.log 2>&1 < /dev/null
# A graceful integrated-server exit must save all dimensions as well as acknowledge the script.
timeout 60 python3 tools/probe/verify.py build/space/sp-client.log
timeout 60 grep 'All dimensions are saved' build/space/sp-client.log
