#!/usr/bin/env python3
"""CPU regression checks for the log verdict; no Minecraft needed."""
import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("trip_verdict", Path(__file__).with_name("trip-verdict.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def fixture():
    rows = []

    def snap(label, space=False, clearance=0, **overrides):
        fields = dict(space=str(space).lower(), phase="CRUISE", locked=4, helm="true",
                      chart=str(not space).lower(), planet=4, clearance=clearance,
                      stair="true", resting=50)
        fields.update(overrides)
        rows.append("PROBE TRIP " + label + " " + " ".join(f"{k} {v}" for k, v in fields.items())
                    + " site Site[kind=LAND, deployable=true]")

    rows.append("PROBE until region 0.0 -> met")
    snap("s-space", True)
    rows.append("PROBE SHIPAIM planet 4 error 0.2 0.1 remaining 630")
    snap("s-aimed", True)
    rows.append("PROBE LOCK s-lock locked 4 main air")
    snap("s-cruise", True)
    rows.append("PROBE until notice 0.0 -> met")
    snap("s-notice", True, phase="NOTICE")
    rows.append("PROBE until chart 0.0 -> met")
    snap("s-chart", clearance=1500)
    rows.append("PROBE until clearance< 60.0 -> met")
    snap("s-low", clearance=59)
    snap("s-site", clearance=8)
    snap("s-hover", clearance=8)
    rows.append("PROBE until stair 0.0 -> met")
    snap("s-stair")
    snap("s-landed")
    rows += ["PROBE COMPLETE | line 57 of 57", "[CHAT] CP_PROBE_SERVER_ACK"]
    return "\n".join(rows)


class VerdictTests(unittest.TestCase):
    def gate(self, text, name):
        return next(row[1] for row in module.verdict(text) if row[0] == name)

    def test_complete_positive(self):
        self.assertEqual(len(module.verdict(fixture())), 9)
        self.assertTrue(all(row[1] for row in module.verdict(fixture())))

    def test_quit_is_not_acceptance(self):
        self.assertTrue(any(not row[1] and not row[2] for row in module.verdict(
            "PROBE COMPLETE | line 1 of 1\n[CHAT] CP_PROBE_SERVER_ACK")))

    def test_each_hard_stage_fails_closed(self):
        for label, stage in (("s-space", "lift-off and space"), ("s-aimed", "aim and lock"),
                             ("s-notice", "cruise and notice"), ("s-chart", "hand-over and helm"),
                             ("s-low", "descent"), ("s-landed", "session")):
            with self.subTest(label=label):
                text = "\n".join(line for line in fixture().splitlines()
                                 if not line.startswith("PROBE TRIP " + label + " "))
                self.assertFalse(self.gate(text, stage))

    def test_unknown_even_after_good_handover_is_hard_failure(self):
        for line in ("PROBE LANDAID remaining 20 site Site[kind=UNKNOWN, deployable=false]",
                     "PROBE TRIP HELM LOST tick 123 site Site[kind=UNKNOWN]"):
            self.assertFalse(self.gate(fixture() + "\n" + line, "hand-over and helm"))

    def test_high_chart_unknown_survey_is_not_loss_of_authority(self):
        rows = fixture().splitlines()
        rows = [line.replace("kind=LAND, deployable=true", "kind=UNKNOWN, deployable=false")
                if line.startswith("PROBE TRIP s-chart ") else line for line in rows]
        self.assertTrue(self.gate("\n".join(rows), "hand-over and helm"))

    def test_wrong_lock_planet_or_lost_helm(self):
        for old, new, stage in (("LOCK s-lock locked 4", "LOCK s-lock locked 3", "aim and lock"),
                                ("planet 4", "planet 3", "hand-over and helm"),
                                ("helm true", "helm false", "hand-over and helm"),
                                ("clearance 59", "clearance NaN", "descent"),
                                ("clearance 59", "clearance 61", "descent"),
                                ("-> met", "-> TIMEOUT", "lift-off and space"),
                                ("remaining 630", "remaining 0", "aim and lock")):
            with self.subTest(old=old):
                self.assertFalse(self.gate(fixture().replace(old, new), stage))

    def test_landing_failures_are_visible_but_informational(self):
        text = fixture().replace("deployable=true", "deployable=false").replace(
            "until stair 0.0 -> met", "until stair 0.0 -> TIMEOUT").replace("resting 50", "resting 0")
        results = module.verdict(text)
        for name in ("landing site", "stair", "landed"):
            row = next(row for row in results if row[0] == name)
            self.assertFalse(row[1])
            self.assertTrue(row[2])
        self.assertTrue(all(ok for _, ok, info, _ in results if not info))

    def test_timeout_cannot_pass_site(self):
        text = fixture().replace("PROBE TRIP s-site", "PROBE LANDING TIMEOUT informational\nPROBE TRIP s-site")
        self.assertFalse(self.gate(text, "landing site"))

    def test_completion_ack_and_rejections(self):
        for text in (fixture().replace("[CHAT] CP_PROBE_SERVER_ACK", ""),
                     fixture().replace("PROBE COMPLETE", "PROBE INCOMPLETE"),
                     fixture() + "\n[CHAT] Incorrect argument for command",
                     fixture() + "\nPROBE FAIL unexpected"):
            self.assertFalse(self.gate(text, "session"))

    def test_hard_timeout_anywhere_is_not_masked_by_success_marker(self):
        for condition in ("region 0.0", "notice 0.0", "chart 0.0", "clearance< 60.0"):
            with self.subTest(condition=condition):
                self.assertFalse(self.gate(fixture() + "\nPROBE until " + condition + " -> TIMEOUT", "session"))

    def test_order_and_duplicates(self):
        self.assertFalse(self.gate(fixture().replace("s-chart", "swap").replace(
            "s-low", "s-chart").replace("swap", "s-low"), "descent"))
        duplicated = module.verdict(fixture() + "\nPROBE TRIP s-chart helm true")
        self.assertEqual(len(duplicated), 9)
        self.assertFalse(next(row[1] for row in duplicated if row[0] == "session"))


if __name__ == "__main__":
    unittest.main()
