#!/usr/bin/env python3
"""Evidence-based full-trip gates. Landing remains explicitly informational.

Only the versioned trip produces the required read-only TRIP snapshots. Missing,
truncated or out-of-order evidence fails closed; reaching quit alone never passes.
"""
import math
import re
import sys
from pathlib import Path

LABELS = ("s-space", "s-aimed", "s-cruise", "s-notice", "s-chart",
          "s-low", "s-site", "s-hover", "s-stair", "s-landed")


def field(line, key):
    match = re.search(r"(?:^| )" + re.escape(key) + r" ([^ ]+)", line)
    return match[1] if match else ""


def number(line, key):
    try:
        value = float(field(line, key))
        return value if math.isfinite(value) else math.nan
    except ValueError:
        return math.nan


def verdict(text):
    lines = text.splitlines()
    snapshots, positions = {}, {}
    duplicate = ""
    for i, line in enumerate(lines):
        match = re.search(r"PROBE TRIP (s-\S+) ", line)
        if match and match[1] in LABELS:
            # Duplicate stage labels are not valid single-trip evidence.
            if match[1] in snapshots:
                duplicate = "duplicate stage: " + line
            snapshots[match[1]] = line
            positions[match[1]] = i
    results = []

    def evidence(pattern, start=0, end=None):
        return next((line for line in lines[start:end] if re.search(pattern, line)), "")

    def stage(name, labels, valid, extra="", informational=False):
        found = [snapshots.get(label, "") for label in labels]
        ordered = all(positions.get(a, -1) < positions.get(b, -1)
                      for a, b in zip(labels, labels[1:]))
        ok = all(found) and ordered and valid
        detail = extra or (found[-1] if found else "") or "missing snapshot " + labels[-1]
        if not all(found):
            detail = "missing snapshot " + next(l for l in labels if l not in snapshots)
        elif not ordered:
            detail = "out-of-order evidence: " + detail
        results.append((name, bool(ok), informational, detail))

    space = snapshots.get("s-space", "")
    region = evidence(r"PROBE until region .* -> met", end=positions.get("s-space", 0))
    stage("lift-off and space", ["s-space"], bool(region) and
          field(space, "space") == "true" and field(space, "helm") == "true")

    aimed = snapshots.get("s-aimed", "")
    lock = evidence(r"PROBE LOCK s-lock locked 4(?: |$)",
                    positions.get("s-aimed", len(lines)), positions.get("s-cruise", 0))
    aim = evidence(r"PROBE SHIPAIM planet 4 error \S+ \S+ remaining [1-9]\d*",
                   positions.get("s-space", len(lines)), positions.get("s-aimed", 0))
    aim_match = re.search(r"error (\S+) (\S+) remaining", aim)
    aim_ok = False
    if aim_match:
        try:
            aim_ok = math.hypot(float(aim_match[1]), float(aim_match[2])) < 1
        except ValueError:
            pass
    stage("aim and lock", ["s-space", "s-aimed", "s-cruise"],
          aim_ok and bool(lock) and field(aimed, "helm") == "true", lock or aim)

    cruise, notice = snapshots.get("s-cruise", ""), snapshots.get("s-notice", "")
    notice_met = evidence(r"PROBE until notice .* -> met",
                          positions.get("s-cruise", len(lines)), positions.get("s-notice", 0))
    stage("cruise and notice", ["s-cruise", "s-notice"], bool(notice_met) and
          field(cruise, "space") == "true" and field(cruise, "locked") == "4" and
          field(notice, "space") == "true" and field(notice, "phase") == "NOTICE" and
          field(notice, "locked") == "4" and field(notice, "helm") == "true")

    chart = snapshots.get("s-chart", "")
    chart_met = evidence(r"PROBE until chart .* -> met",
                         positions.get("s-notice", len(lines)), positions.get("s-chart", 0))
    lost = evidence(r"PROBE (?:LANDAID .*kind=UNKNOWN|TRIP HELM LOST)",
                    positions.get("s-chart", len(lines)))
    stage("hand-over and helm", ["s-notice", "s-chart"], bool(chart_met) and not lost and
          field(chart, "space") == "false" and field(chart, "chart") == "true" and
          field(chart, "planet") == "4" and field(chart, "helm") == "true", lost or chart)

    low = snapshots.get("s-low", "")
    descent = evidence(r"PROBE until clearance< 60(?:\.0)? -> met",
                       positions.get("s-chart", len(lines)), positions.get("s-low", 0))
    stage("descent", ["s-chart", "s-low"], bool(descent) and
          field(low, "helm") == "true" and field(low, "chart") == "true" and
          field(low, "planet") == "4" and number(low, "clearance") < 60 and
          number(chart, "clearance") > number(low, "clearance"))

    site = snapshots.get("s-site", "")
    timeout = evidence(r"PROBE LANDING TIMEOUT", positions.get("s-low", len(lines)),
                       positions.get("s-site", 0))
    stage("landing site", ["s-low", "s-site"], not timeout and
          "deployable=true" in site and "kind=UNKNOWN" not in site, timeout or site, True)

    stair = snapshots.get("s-stair", "")
    stair_met = evidence(r"PROBE until stair .* -> met",
                         positions.get("s-hover", len(lines)), positions.get("s-stair", 0))
    stage("stair", ["s-site", "s-hover", "s-stair"], bool(stair_met) and
          field(stair, "stair") == "true", stair_met or stair, True)

    landed = snapshots.get("s-landed", "")
    stage("landed", ["s-stair", "s-landed"], field(landed, "helm") == "true" and
          field(landed, "chart") == "true" and field(landed, "planet") == "4" and
          number(landed, "resting") >= 40 and number(landed, "clearance") <= 1,
          informational=True)

    complete = evidence(r"PROBE COMPLETE \| line \d+ of \d+")
    ack = evidence(r"\[CHAT\].*CP_PROBE_SERVER_ACK")
    rejected = evidence(r"PROBE FAIL|PROBE exception|\[CHAT\].*(?:Unknown or incomplete command|Incorrect argument|You do not have permission|You don't have permission|Unknown block type|That position is not loaded|Expected .* at position)")
    hard_timeout = evidence(r"PROBE until (?:region|notice|chart) .* -> TIMEOUT|PROBE until clearance< 60(?:\.0)? -> TIMEOUT")
    stage("session", ["s-space", "s-landed"], bool(complete and ack) and
          not rejected and not hard_timeout and not duplicate,
          duplicate or rejected or hard_timeout or (complete if ack else "missing server ACK: " + complete)
          or "missing completion/ACK")
    return results


def main():
    if len(sys.argv) != 2:
        print("usage: trip-verdict.py <client-log>", file=sys.stderr)
        return 2
    try:
        text = Path(sys.argv[1]).read_text(errors="replace")
    except OSError as error:
        print(error, file=sys.stderr)
        return 2
    results = verdict(text)
    for name, ok, informational, line in results:
        suffix = " [INFORMATIONAL: terrain-dependent, not a regression gate]" if informational else ""
        print(f"{'PASS' if ok else 'FAIL'} {name}{suffix} | {line}")
    return int(any(not ok and not informational for _, ok, informational, _ in results))


if __name__ == "__main__":
    sys.exit(main())
