#!/usr/bin/env python3
"""Check or apply the repository's pinned Java format, without downloading tools."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
FORMATTER_SHA256 = "32342e7c1b4600f80df3471da46aee8012d3e1445d5ea1be1fb71289b07cc735"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--formatter", type=Path, required=True,
                        help="google-java-format 1.28.0 all-deps jar")
    parser.add_argument("--apply", action="store_true", help="rewrite instead of checking")
    args = parser.parse_args()
    jar = args.formatter.resolve()
    if hashlib.sha256(jar.read_bytes()).hexdigest() != FORMATTER_SHA256:
        parser.error("formatter must be the pinned google-java-format 1.28.0 all-deps jar")
    tracked = subprocess.check_output(["git", "ls-files", "-z", "--", "*.java"], cwd=ROOT)
    files = {os.fsdecode(name) for name in tracked.split(b"\0") if name}
    added = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "-z", "--", "*.java"], cwd=ROOT)
    files.update(os.fsdecode(name) for name in added.split(b"\0") if name)
    files = sorted(name for name in files if (ROOT / name).is_file())
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else "java"
    command = [java, "-jar", str(jar), "--aosp", "--skip-reflowing-long-strings", "--skip-javadoc-formatting"]
    command += ["--replace"] if args.apply else ["--dry-run", "--set-exit-if-changed"]
    # Bound command size on platforms with smaller argument limits.
    status = 0
    for offset in range(0, len(files), 100):
        result = subprocess.run(command + files[offset:offset + 100], cwd=ROOT)
        status = max(status, result.returncode)
    return status


if __name__ == "__main__":
    sys.exit(main())
