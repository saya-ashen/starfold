"""Offline standard-library tests using invented declarations; no gameplay."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from voyage_log import check_log


SCRIPT = Path(__file__).with_name("voyage_log.py").resolve()
EXAMPLE = SCRIPT.parents[2] / "docs/examples/first-round-trip-log.json"
PLAYER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
HULL = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
OTHER = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"


def declared_trip():
    # Independent fixture: different destination, amount, fuel and clock from example.
    names = ("home_departure", "offworld_landing", "ore_loaded",
             "offworld_departure", "home_landing", "unloaded")
    planets = (0, 7, 7, 7, 0, 0)
    cargo = (0, 0, 12, 12, 12, 0)
    events = [dict(event=name, player_uuid=PLAYER, hull_uuid=HULL,
                   planet=planets[i], game_tick=i * 20, fuel_mb=50 - i * 10,
                   cargo={"raw_osmium": cargo[i]}) for i, name in enumerate(names)]
    events[2]["source"] = "mined_on_destination"
    events[5]["delivered_raw_osmium"] = 12
    return dict(schema_version=1, trip_id=OTHER, player_uuid=PLAYER,
                hull_uuid=HULL, destination=7, events=events)


class VoyageLogTest(unittest.TestCase):
    def setUp(self):
        self.log = declared_trip()

    def assert_manual(self, result):
        self.assertEqual(result["scope"], "consistency_only")
        self.assertEqual(result["mode"], "manual")
        self.assertEqual(result["provenance"], "provenance_not_verified")
        self.assertIn("does not verify", result["disclaimer"])
        self.assertIn("Stage4 completion", result["disclaimer"])

    def assert_inconsistent(self, fragment):
        result = check_log(self.log)
        self.assertFalse(result["consistent"])
        self.assert_manual(result)
        self.assertTrue(any(fragment in error for error in result["errors"]), result)

    def test_valid_default_minimum_and_no_input_mutation(self):
        before = copy.deepcopy(self.log)
        result = check_log(self.log)
        self.assertTrue(result["consistent"])
        self.assertEqual(result["errors"], [])
        self.assertEqual(result["min_raw_osmium"], 8)
        self.assert_manual(result)
        self.assertEqual(self.log, before)

    def test_configurable_positive_minimum(self):
        for minimum, consistent in ((1, True), (8, True), (12, True), (13, False)):
            with self.subTest(minimum=minimum):
                self.log["min_raw_osmium"] = minimum
                self.assertEqual(check_log(self.log)["consistent"], consistent)
        for index in (2, 3, 4):
            self.log["events"][index]["cargo"]["raw_osmium"] = 1
        self.log["events"][5]["delivered_raw_osmium"] = 1
        self.log["min_raw_osmium"] = 1
        self.assertTrue(check_log(self.log)["consistent"])
        del self.log["min_raw_osmium"]
        self.assert_inconsistent("at least 8")

    def test_hull_or_player_change_at_any_stage(self):
        for key in ("hull_uuid", "player_uuid"):
            for index in range(6):
                with self.subTest(key=key, index=index):
                    self.log = declared_trip()
                    self.log["events"][index][key] = OTHER
                    self.assert_inconsistent(key)

    def test_uuid_case_does_not_change_identity(self):
        self.log["events"][3]["hull_uuid"] = HULL.upper()
        self.assertTrue(check_log(self.log)["consistent"])

    def test_preexisting_or_outbound_cargo_is_rejected(self):
        for index in (0, 1):
            with self.subTest(index=index):
                self.log = declared_trip()
                self.log["events"][index]["cargo"]["raw_osmium"] = 12
                self.assert_inconsistent("before loading")

    def test_every_event_must_use_correct_planet(self):
        for index in range(6):
            with self.subTest(index=index):
                self.log = declared_trip()
                self.log["events"][index]["planet"] = 9
                self.assert_inconsistent("must be on planet")
        self.log = declared_trip()
        self.log["destination"] = 9
        self.assert_inconsistent("planet 9")

    def test_events_cannot_be_missing_extra_reordered_or_renamed(self):
        original = declared_trip()["events"]
        variants = [[], original[:-1], original[1:],
                    original + [original[-1]],
                    [original[1], original[0], *original[2:]]]
        renamed = copy.deepcopy(original)
        renamed[3]["event"] = "refuel"
        variants.append(renamed)
        for events in variants:
            with self.subTest(events=events):
                self.log["events"] = events
                self.assert_inconsistent("exactly ordered")

    def test_cargo_losses_and_additions_are_rejected(self):
        for index in (3, 4):
            for amount in (0, 7, 11, 13):
                with self.subTest(index=index, amount=amount):
                    self.log = declared_trip()
                    self.log["events"][index]["cargo"]["raw_osmium"] = amount
                    self.assert_inconsistent("equal loaded amount 12")

    def test_unload_requires_empty_hold_and_exact_delivery(self):
        self.log["events"][5]["cargo"]["raw_osmium"] = 1
        self.assert_inconsistent("must be 0")
        for amount in (0, 11, 13):
            self.log = declared_trip()
            self.log["events"][5]["delivered_raw_osmium"] = amount
            self.assert_inconsistent("delivered_raw_osmium must equal loaded amount 12")

    def test_source_is_required_manual_claim(self):
        self.log["events"][2]["source"] = "inventory"
        self.assert_inconsistent("manual claim only")
        del self.log["events"][2]["source"]
        with self.assertRaisesRegex(ValueError, "source is required"):
            check_log(self.log)

    def test_fuel_gain_at_every_transition_is_rejected(self):
        for index in range(1, 6):
            with self.subTest(index=index):
                self.log = declared_trip()
                self.log["events"][index]["fuel_mb"] = self.log["events"][index - 1]["fuel_mb"] + 1
                self.assert_inconsistent("split log/refuel not supported")
        self.log = declared_trip()
        for event in self.log["events"]:
            event["fuel_mb"] = 0
        self.assertTrue(check_log(self.log)["consistent"])

    def test_clock_equal_or_reversed_at_every_transition(self):
        for index in range(1, 6):
            for offset in (-1, 0):
                with self.subTest(index=index, offset=offset):
                    self.log = declared_trip()
                    self.log["events"][index]["game_tick"] = self.log["events"][index - 1]["game_tick"] + offset
                    # For the first transition keep a nonnegative reversed tick.
                    if index == 1 and offset == -1:
                        self.log["events"][0]["game_tick"] = 2
                        self.log["events"][1]["game_tick"] = 1
                    self.assert_inconsistent("strictly increase")

    def test_bad_numeric_types_and_bounds(self):
        paths = [("schema_version",), ("destination",), ("min_raw_osmium",)]
        paths += [("events", 2, key) for key in ("planet", "game_tick", "fuel_mb")]
        paths += [("events", 2, "cargo", "raw_osmium"),
                  ("events", 5, "delivered_raw_osmium")]
        for path in paths:
            invalids = [-1, True, False, 1.0, 1.5, "8", None, [], {}, float("nan"), float("inf")]
            if len(path) == 1:
                invalids.append(0)
            for invalid in invalids:
                with self.subTest(path=path, invalid=invalid):
                    log = declared_trip()
                    parent = log
                    for key in path[:-1]:
                        parent = parent[key]
                    parent[path[-1]] = invalid
                    with self.assertRaises(ValueError):
                        check_log(log)
        self.log["schema_version"] = 2
        with self.assertRaisesRegex(ValueError, "schema_version must be 1"):
            check_log(self.log)

    def test_bad_uuids_at_top_level_and_events(self):
        for key in ("trip_id", "player_uuid", "hull_uuid"):
            for invalid in (None, 1, True, {}, "", "not-a-uuid", HULL.replace("-", "")):
                with self.subTest(key=key, invalid=invalid):
                    log = declared_trip()
                    log[key] = invalid
                    with self.assertRaisesRegex(ValueError, "UUID"):
                        check_log(log)
                    if key != "trip_id":
                        log = declared_trip()
                        log["events"][4][key] = invalid
                        with self.assertRaisesRegex(ValueError, "UUID"):
                            check_log(log)

    def test_missing_required_schema_fields(self):
        paths = [(key,) for key in declared_trip()]
        for i, event in enumerate(declared_trip()["events"]):
            paths += [("events", i, key) for key in event]
            paths.append(("events", i, "cargo", "raw_osmium"))
        for path in paths:
            with self.subTest(path=path):
                log = declared_trip()
                parent = log
                for key in path[:-1]:
                    parent = parent[key]
                del parent[path[-1]]
                with self.assertRaises(ValueError):
                    check_log(log)

    def test_wrong_container_types_unknown_fields_and_claim_placement(self):
        for invalid in (None, [], "log", True):
            with self.subTest(root=invalid), self.assertRaises(ValueError):
                check_log(invalid)
        for path, value in ((('events',), {}), (('events', 0), []),
                            (('events', 0, 'cargo'), []), (('notes',), False),
                            (('events', 0, 'event'), True),
                            (('events', 0, 'source'), 'mined_on_destination'),
                            (('events', 2, 'source'), True),
                            (('events', 0, 'delivered_raw_osmium'), 0),
                            (('min_raw_osmuim',), 1),
                            (('events', 0, 'cargo', 'raw_osmuim'), 0)):
            with self.subTest(path=path):
                log = declared_trip()
                parent = log
                for key in path[:-1]:
                    parent = parent[key]
                parent[path[-1]] = value
                with self.assertRaises(ValueError):
                    check_log(log)

    def test_example_is_consistent_and_explicitly_synthetic(self):
        log = json.loads(EXAMPLE.read_text(encoding="utf-8"))
        self.assertIn("ALL SYNTHETIC", log["notes"])
        self.assertIn("not real voyage evidence", log["notes"])
        result = check_log(log)
        self.assertTrue(result["consistent"])
        self.assert_manual(result)


class VoyageLogCliTest(unittest.TestCase):
    def invoke(self, text=None, *, missing_file=False, no_argument=False):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manual.json"
            if not missing_file:
                path.write_text(text if text is not None else json.dumps(declared_trip()),
                                encoding="utf-8")
            args = [] if no_argument else ["--log", str(path)]
            return subprocess.run([sys.executable, "-B", str(SCRIPT), *args],
                                  cwd=directory, capture_output=True, text=True,
                                  timeout=10, check=False)

    def test_valid_json_output_from_another_working_directory(self):
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stderr, "")
        output = json.loads(result.stdout)
        self.assertTrue(output["consistent"])
        self.assertEqual((output["scope"], output["mode"], output["provenance"]),
                         ("consistency_only", "manual", "provenance_not_verified"))

    def test_inconsistent_json_and_exit_one(self):
        log = declared_trip()
        log["events"].pop()
        result = self.invoke(json.dumps(log))
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(result.stderr, "")
        output = json.loads(result.stdout)
        self.assertFalse(output["consistent"])
        self.assertTrue(output["errors"])
        self.assertEqual(output["provenance"], "provenance_not_verified")

    def test_malformed_input_is_argparse_error_without_traceback(self):
        for text in ("{broken", "{}", "[]", "[" * 1100 + "0" + "]" * 1100,
                     '{"schema_version": 1, "schema_version": 1}',
                     '{"value": NaN}', '{"value": Infinity}', '\ufeff{}'):
            with self.subTest(text=text):
                result = self.invoke(text)
                self.assertEqual(result.returncode, 2)
                self.assertEqual(result.stdout, "")
                self.assertIn("error:", result.stderr)
                self.assertIn("consistency_only/manual/provenance_not_verified", result.stderr)
                self.assertNotIn("Traceback", result.stderr)

    def test_missing_file_and_missing_argument(self):
        for options in ({"missing_file": True}, {"no_argument": True}):
            with self.subTest(options=options):
                result = self.invoke(**options)
                self.assertEqual(result.returncode, 2)
                self.assertEqual(result.stdout, "")
                self.assertIn("error:", result.stderr)
                self.assertNotIn("Traceback", result.stderr)


if __name__ == "__main__":
    unittest.main()
