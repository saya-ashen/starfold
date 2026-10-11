#!/usr/bin/env python3
"""Check script completion, not gameplay acceptance. Screenshots/readings need separate review."""
import re
import sys
from pathlib import Path


def failures(text):
    errors = []
    if not re.search(r"PROBE COMPLETE \| line \d+ of \d+", text):
        errors.append("script did not reach quit")
    if not re.search(r"\[CHAT\].*CP_PROBE_SERVER_ACK", text):
        errors.append("server did not acknowledge script completion")
    if re.search(r"PROBE until .* -> TIMEOUT", text):
        errors.append("a probe condition timed out")
    for line in text.splitlines():
        if "[CHAT]" in line and re.search(
            r"Unknown or incomplete command|Incorrect argument|You do not have permission|"
            r"You don't have permission|Unknown block type|That position is not loaded|Expected .* at position", line
        ):
            errors.append("command rejected: " + line.split("[CHAT]", 1)[1].strip())
    return errors


if __name__ == "__main__":
    errors = failures(Path(sys.argv[1]).read_text(errors="replace"))
    for error in errors:
        print("probe failed: " + error)
    if not errors:
        print("probe script completed (not a gameplay acceptance verdict)")
    sys.exit(bool(errors))
