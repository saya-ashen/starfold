#!/usr/bin/env python3
"""Stdlib summary of identical light-send fixture runs (server log, not client wall time)."""
import json
import re
import sys
from pathlib import Path


def summarize(path):
    events = []
    window = None
    for line in path.read_text().splitlines():
        if "[Dev: Changed the block at " in line and any(
            p in line for p in ("1024, 170, 1024", "2047, 170, 1024")
        ):
            window = {"site": "seam" if "2047," in line else "ordinary", "packets": 0,
                      "bytes": 0, "sampleNs": 0, "maxSampleNs": 0, "identityPackets": 0}
            events.append(window)
        if "PROBE RELIGHT_AUDIT" in line:
            window = None
        m = re.search(r"LIGHT_SEND game (\d+) image (-?\d+) bytes (\d+) sampleNs (\d+) identity (true|false)", line)
        if m and window is not None:
            size, ns = int(m[3]), int(m[4])
            window["packets"] += 1
            window["bytes"] += size
            window["sampleNs"] += ns
            window["maxSampleNs"] = max(window["maxSampleNs"], ns)
            window["identityPackets"] += m[5] == "true"
    if len(events) != 4:
        raise ValueError(f"Expected four torch changes, found {len(events)} in {path}")
    for i, event in enumerate(events):
        event["action"] = "place" if i % 2 == 0 else "remove"
    return events


if __name__ == "__main__":
    print(json.dumps({p: summarize(Path(p) / "dev-server.log") for p in sys.argv[1:]}, indent=2))
