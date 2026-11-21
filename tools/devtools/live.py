#!/usr/bin/env python3
"""Send a session-scoped request to a running Starfold devtools client.

Protocol 1 lives under GAME_DIR/starfold-devtools/live. session.json advertises
protocol, session UUID, pid and startedAt (epoch milliseconds). Requests are UTF-8
JSON objects named UUID.json, published by atomic rename into requests/. Each
contains id, session, operation, expiresAt (epoch milliseconds), and optional
operation arguments (face, x, z, radius) at the top level. Responses in responses/
contain id, session, ok, and either data or error. The server processes requests
serially, rejects stale/expired requests, and waits at most 10 seconds for dispatch.
"""

import argparse
import json
import math
import os
from pathlib import Path
import stat
import sys
import tempfile
import time
import uuid


OPERATIONS = (
    "status", "snapshot", "far_snapshot", "far_watch", "far_stop",
    "screenshot", "profile_start", "profile_stop",
)
MAX_REQUEST_BYTES = 16 * 1024
MAX_RESPONSE_BYTES = 16 * 1024 * 1024


def canonical_uuid(value):
    return isinstance(value, str) and str(uuid.UUID(value)) == value


def reject_constant(value):
    raise ValueError(f"Invalid JSON number: {value}")


def read_json(path, limit):
    """Bound reads and refuse symlinks, including a last-component swap on POSIX."""
    if not stat.S_ISREG(path.lstat().st_mode):
        raise ValueError(f"Expected a regular JSON file: {path}")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
    descriptor = os.open(path, flags)
    with os.fdopen(descriptor, "rb") as source:
        if not stat.S_ISREG(os.fstat(source.fileno()).st_mode):
            raise ValueError(f"Expected a regular JSON file: {path}")
        content = source.read(limit + 1)
    if len(content) > limit:
        raise ValueError(f"JSON file exceeds {limit} bytes: {path}")
    result = json.loads(content.decode("utf-8"), parse_constant=reject_constant)
    if not isinstance(result, dict):
        raise ValueError(f"Expected a JSON object: {path}")
    return result


def check_directories(root):
    for directory in (root.parent, root, root / "requests", root / "responses"):
        if not stat.S_ISDIR(directory.lstat().st_mode):
            raise ValueError(f"Mailbox directory must not be a symlink: {directory}")


def publish(path, request):
    content = json.dumps(request, allow_nan=False, separators=(",", ":")).encode("utf-8")
    if len(content) > MAX_REQUEST_BYTES:
        raise ValueError("Request exceeds 16 KiB")
    descriptor, temporary = tempfile.mkstemp(prefix=".live-", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(content)
        if path.is_symlink():
            raise ValueError(f"Refusing symlink: {path}")
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def exchange(args):
    root = args.game_dir.expanduser().absolute() / "starfold-devtools" / "live"
    check_directories(root)
    discovery = read_json(root / "session.json", MAX_REQUEST_BYTES)
    session = discovery.get("session")
    if type(discovery.get("protocol")) is not int or discovery["protocol"] != 1:
        raise ValueError("Unsupported live bridge protocol")
    if not canonical_uuid(session):
        raise ValueError("Invalid session UUID")
    request_id = str(uuid.uuid4())
    request = {
        "id": request_id,
        "session": session,
        "operation": args.operation,
        "expiresAt": math.ceil((time.time() + args.timeout) * 1000),
    }
    for key in ("face", "x", "z", "radius"):
        value = getattr(args, key)
        if value is not None:
            request[key] = value
    request_path = root / "requests" / f"{request_id}.json"
    response_path = root / "responses" / f"{request_id}.json"
    deadline = time.monotonic() + args.timeout
    try:
        publish(request_path, request)
        while True:
            check_directories(root)
            try:
                response = read_json(response_path, MAX_RESPONSE_BYTES)
            except FileNotFoundError:
                response = None
            if response is not None:
                if response.get("id") != request_id or response.get("session") != session:
                    raise ValueError("Response belongs to a different request or session")
                if type(response.get("ok")) is not bool:
                    raise ValueError("Response must contain a boolean ok")
                if (response["ok"] and "data" not in response
                        or not response["ok"] and "error" not in response):
                    raise ValueError("Response is missing data or error")
                response_path.unlink(missing_ok=True)
                return response
            current = read_json(root / "session.json", MAX_REQUEST_BYTES)
            if current.get("session") != session:
                raise ValueError("Live bridge session changed while waiting")
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f"No response within {args.timeout:g} seconds")
            time.sleep(min(0.1, remaining))
    finally:
        # Remove an unconsumed request on timeout, interruption, or session change.
        check_directories(root)
        request_path.unlink(missing_ok=True)


def positive_timeout(value):
    result = float(value)
    if not math.isfinite(result) or result <= 0:
        raise argparse.ArgumentTypeError("timeout must be a finite positive number")
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--game-dir", type=Path, required=True)
    parser.add_argument("--timeout", type=positive_timeout, default=15.0, help="seconds (default: 15)")
    parser.add_argument("operation", choices=OPERATIONS)
    parser.add_argument("--face", type=int)
    parser.add_argument("--x", type=int)
    parser.add_argument("--z", type=int)
    parser.add_argument("--radius", type=int)
    args = parser.parse_args(argv)
    try:
        response = exchange(args)
    except (OSError, ValueError, OverflowError) as error:
        response = {"ok": False, "error": str(error)}
    except KeyboardInterrupt:
        response = {"ok": False, "error": "Interrupted"}
    print(json.dumps(response, ensure_ascii=False, allow_nan=False))
    return 0 if response["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
