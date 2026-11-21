#!/usr/bin/env python3
"""Check Stage4 MANUAL voyage declarations offline; never certify gameplay.

Schema v1: schema_version=1, trip_id/player_uuid/hull_uuid UUID strings,
destination (positive integer; home is planet 0), optional min_raw_osmium
(positive integer, default 8), optional notes (string), and events (array).
Each event has event, player_uuid, hull_uuid, planet, game_tick, fuel_mb,
and cargo={"raw_osmium": nonnegative integer}. Ticks, fuel and planets are
nonnegative integers; booleans and floats are not integers in this schema.
Events must be exactly EVENT_ORDER. ore_loaded additionally requires
source="mined_on_destination" (a manual claim only); unloaded requires
delivered_raw_osmium. Unknown fields are rejected to expose misspellings.

Cargo must be zero through offworld landing, unchanged from loading through
home landing, then zero after unloading the exact loaded amount. Fuel may
stay equal or decrease; refueling is unsupported. UUIDs identify declarations,
not authenticated players or hulls. No game, save, telemetry or network reads.

CLI: --log PATH; exit 0 = consistent declarations, 1 = inconsistent
observations (both JSON), 2 = malformed schema/JSON, unreadable file or CLI
usage error (argparse stderr). Consistency never verifies provenance.
"""
import argparse
import json
from pathlib import Path
from uuid import UUID


EVENT_ORDER = (
    "home_departure", "offworld_landing", "ore_loaded",
    "offworld_departure", "home_landing", "unloaded",
)
DISCLAIMER = (
    "Manual declared observations only. Consistency does not verify ore "
    "provenance, actual gameplay, telemetry, or Stage4 completion."
)


def _object(value, path, required, optional=()):
    if not isinstance(value, dict):
        raise ValueError(f"{path} must be an object")
    missing = set(required) - value.keys()
    if missing:
        raise ValueError(f"{path} missing required fields: {', '.join(sorted(missing))}")
    unknown = value.keys() - set(required) - set(optional)
    if unknown:
        raise ValueError(f"{path} unknown fields: {', '.join(sorted(unknown))}")


def _integer(value, path, minimum=0):
    if type(value) is not int or value < minimum:
        raise ValueError(f"{path} must be an integer >= {minimum} (booleans forbidden)")


def _uuid(value, path):
    # Require the familiar dashed UUID representation, without imposing a UUID version.
    if not isinstance(value, str):
        raise ValueError(f"{path} must be a dashed UUID string")
    try:
        parsed = UUID(value)
    except ValueError as exc:
        raise ValueError(f"{path} must be a dashed UUID string") from exc
    if str(parsed) != value.lower():
        raise ValueError(f"{path} must be a dashed UUID string")
    return parsed


def check_log(log):
    """Return a consistency-only report; raise ValueError for malformed schema."""
    _object(log, "log", ("schema_version", "trip_id", "player_uuid", "hull_uuid",
                         "destination", "events"), ("min_raw_osmium", "notes"))
    _integer(log["schema_version"], "schema_version", 1)
    if log["schema_version"] != 1:
        raise ValueError("schema_version must be 1")
    _uuid(log["trip_id"], "trip_id")
    player = _uuid(log["player_uuid"], "player_uuid")
    hull = _uuid(log["hull_uuid"], "hull_uuid")
    destination = log["destination"]
    _integer(destination, "destination", 1)
    minimum = log.get("min_raw_osmium", 8)
    _integer(minimum, "min_raw_osmium", 1)
    if "notes" in log and not isinstance(log["notes"], str):
        raise ValueError("notes must be a string")
    events = log["events"]
    if not isinstance(events, list):
        raise ValueError("events must be an array")

    errors = []
    previous = None
    for index, event in enumerate(events):
        path = f"events[{index}]"
        _object(event, path, ("event", "player_uuid", "hull_uuid", "planet",
                              "game_tick", "fuel_mb", "cargo"),
                ("source", "delivered_raw_osmium"))
        name = event["event"]
        if not isinstance(name, str):
            raise ValueError(f"{path}.event must be a string")
        for key in ("planet", "game_tick", "fuel_mb"):
            _integer(event[key], f"{path}.{key}")
        _object(event["cargo"], f"{path}.cargo", ("raw_osmium",))
        _integer(event["cargo"]["raw_osmium"], f"{path}.cargo.raw_osmium")
        if "source" in event and (name != "ore_loaded" or
                                    not isinstance(event["source"], str)):
            raise ValueError(f"{path}.source must be a string on ore_loaded only")
        if name == "ore_loaded" and "source" not in event:
            raise ValueError(f"{path}.source is required (manual claim only)")
        if "delivered_raw_osmium" in event:
            if name != "unloaded":
                raise ValueError(f"{path}.delivered_raw_osmium is allowed on unloaded only")
            _integer(event["delivered_raw_osmium"], f"{path}.delivered_raw_osmium")
        elif name == "unloaded":
            raise ValueError(f"{path}.delivered_raw_osmium is required")
        if _uuid(event["player_uuid"], f"{path}.player_uuid") != player:
            errors.append(f"{path}: player_uuid differs from trip player")
        if _uuid(event["hull_uuid"], f"{path}.hull_uuid") != hull:
            errors.append(f"{path}: hull_uuid differs from trip hull; same hull required")
        if name in EVENT_ORDER:
            expected_planet = 0 if name in (
                "home_departure", "home_landing", "unloaded") else destination
            if event["planet"] != expected_planet:
                errors.append(f"{path}: {name} must be on planet {expected_planet}")
        if previous is not None:
            if event["game_tick"] <= previous["game_tick"]:
                errors.append(f"{path}: game_tick must strictly increase")
            if event["fuel_mb"] > previous["fuel_mb"]:
                errors.append(f"{path}: fuel_mb increased; split log/refuel not supported")
        previous = event

    if [event["event"] for event in events] != list(EVENT_ORDER):
        errors.append("events must be exactly ordered: " + ", ".join(EVENT_ORDER))
    else:
        departure, landing, loaded, off_departure, home, unloaded = events
        for event in (departure, landing):
            if event["cargo"]["raw_osmium"] != 0:
                errors.append(f"{event['event']}: cargo.raw_osmium must be 0 before loading")
        amount = loaded["cargo"]["raw_osmium"]
        if amount < minimum:
            errors.append(f"ore_loaded: cargo.raw_osmium must be at least {minimum}")
        if loaded["source"] != "mined_on_destination":
            errors.append("ore_loaded: source must be mined_on_destination (manual claim only)")
        for event in (off_departure, home):
            if event["cargo"]["raw_osmium"] != amount:
                errors.append(f"{event['event']}: cargo.raw_osmium must equal loaded amount {amount}")
        if unloaded["cargo"]["raw_osmium"] != 0:
            errors.append("unloaded: cargo.raw_osmium must be 0")
        if unloaded["delivered_raw_osmium"] != amount:
            errors.append(f"unloaded: delivered_raw_osmium must equal loaded amount {amount}")

    return {
        "consistent": not errors,
        "scope": "consistency_only",
        "mode": "manual",
        "provenance": "provenance_not_verified",
        "disclaimer": DISCLAIMER,
        "trip_id": log["trip_id"],
        "min_raw_osmium": minimum,
        "errors": errors,
    }


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON field: {key}")
        result[key] = value
    return result


def _reject_constant(value):
    raise ValueError(f"invalid JSON numeric constant: {value}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", required=True, type=Path,
                        help="local JSON file of MANUAL declarations; no provenance verification")
    args = parser.parse_args(argv)
    try:
        log = json.loads(args.log.read_text(encoding="utf-8"),
                         object_pairs_hook=_unique_object, parse_constant=_reject_constant)
        result = check_log(log)
    except (OSError, ValueError, RecursionError) as exc:
        parser.error(f"consistency_only/manual/provenance_not_verified: {exc}")
    print(json.dumps(result, indent=2, allow_nan=False))
    return 0 if result["consistent"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
