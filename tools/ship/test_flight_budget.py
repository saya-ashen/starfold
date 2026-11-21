"""Bounded standard-library tests; never launch a game, Java or Gradle."""
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from flight_budget import (FUEL_DIR, FUELS, REPO_ROOT, THRUST_SOURCE, calculate_budget,
                           load_parameters)
from round_trip_budget import calculate_round_trip


class FlightBudgetTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / FUEL_DIR).mkdir(parents=True)
        (self.root / THRUST_SOURCE).parent.mkdir(parents=True)
        self.configure()

    def configure(self, *, impulse=100, mass=1000, thrust="10000"):
        for fuel in FUELS:
            data = {"fluid": f"test:{fuel}", "impulse": impulse}
            if mass is not None:
                data["mass"] = mass
            (self.root / FUEL_DIR / f"{fuel}.json").write_text(json.dumps(data), encoding="utf-8")
        (self.root / THRUST_SOURCE).write_text(
            "// static final double THRUST = 123;\n"
            "/* static final double THRUST = 456; */\n"
            f"public static final double THRUST = {thrust}, BOOST = 4;\n",
            encoding="utf-8")

    def budget(self, **changes):
        args = dict(dry_mass=100, cargo_mass=20, fuel_mb=100, fuel="diesel",
                    lift_nozzles=2, gravity=10, reserve_fraction=0.5, repo_root=self.root)
        args.update(changes)
        return calculate_budget(**args)

    def test_mass_lift_weight_and_initial_consumption(self):
        result = self.budget()
        self.assertEqual(result["stored_fuel_mass"], 100)
        self.assertEqual(result["total_mass"], 220)
        self.assertEqual(result["total_lift"], 20000)
        self.assertEqual(result["weight"], 2200)
        self.assertAlmostEqual(result["thrust_to_weight_ratio"], 20000 / 2200)
        self.assertEqual(result["initial_hover_consumption_mb_s"], 22)

    def test_more_stored_fuel_lowers_thrust_to_weight(self):
        light = self.budget(fuel_mb=100)
        heavy = self.budget(fuel_mb=1000)
        self.assertGreater(heavy["total_mass"], light["total_mass"])
        self.assertLess(heavy["thrust_to_weight_ratio"], light["thrust_to_weight_ratio"])

    def test_reserve_is_retained_mass_and_excluded_from_both_budgets(self):
        full = self.budget(reserve_fraction=0)
        half = self.budget()
        most = self.budget(reserve_fraction=0.9)
        self.assertEqual(half["reserved_fuel_mb"], 50)
        self.assertEqual(half["burnable_fuel_mb"], 50)
        self.assertEqual(half["mass_after_budget_burn"], 170)
        self.assertEqual(full["total_mass"], half["total_mass"])
        self.assertGreater(full["ideal_hover_time_s"], half["ideal_hover_time_s"])
        self.assertGreater(half["ideal_hover_time_s"], most["ideal_hover_time_s"])
        self.assertGreater(full["theoretical_space_delta_v"], half["theoretical_space_delta_v"])
        self.assertGreater(half["theoretical_space_delta_v"], most["theoretical_space_delta_v"])
        self.assertAlmostEqual(half["theoretical_space_delta_v"], 100 * math.log(220 / 170))

    def test_hover_uses_analytic_changing_mass_integral(self):
        result = self.budget()
        expected = 100 / 10 * math.log(220 / 170)
        self.assertAlmostEqual(result["ideal_hover_time_s"], expected, places=12)
        # Independent midpoint integration over remaining fuel, not over time.
        steps = 2000
        step = 50 / steps
        numerical = sum(100 / (10 * (170 + (i + 0.5) * step)) * step
                        for i in range(steps))
        self.assertAlmostEqual(result["ideal_hover_time_s"], numerical, places=8)
        initial_rate_shortcut = 50 / result["initial_hover_consumption_mb_s"]
        self.assertGreater(result["ideal_hover_time_s"], initial_rate_shortcut)

    def test_zero_mass_fuel_and_omitted_mass_use_constant_mass_limit(self):
        for mass in (0, None):
            with self.subTest(mass=mass):
                self.configure(mass=mass)
                result = self.budget()
                self.assertEqual(result["total_mass"], 120)
                self.assertEqual(result["stored_fuel_mass"], 0)
                self.assertAlmostEqual(result["ideal_hover_time_s"], 50 * 100 / (120 * 10))
                self.assertAlmostEqual(result["theoretical_space_delta_v"], 50 * 100 / 120)

    def test_tiny_fuel_mass_does_not_lose_logarithmic_precision(self):
        self.configure(mass=1e-20)
        result = self.budget()
        self.assertAlmostEqual(result["theoretical_space_delta_v"], 50 * 100 / 120)
        self.assertGreater(result["ideal_hover_time_s"], 0)

    def test_zero_fuel_has_zero_duration_and_delta_v(self):
        result = self.budget(fuel_mb=0)
        self.assertEqual(result["ideal_hover_time_s"], 0)
        self.assertEqual(result["theoretical_space_delta_v"], 0)
        self.assertEqual(result["reserved_fuel_mb"], 0)

    def test_overweight_and_no_nozzles_are_reports_not_passes(self):
        for nozzles in (0, 1):
            with self.subTest(nozzles=nozzles):
                result = self.budget(dry_mass=2000, lift_nozzles=nozzles)
                self.assertTrue(result["overweight"])
                self.assertIsNone(result["ideal_hover_time_s"])
                self.assertEqual(result["lift_shortfall"], result["weight"] - result["total_lift"])
                self.assertIn("cannot hover", result["lift_report"])
                self.assertNotIn("pass", result)
                self.assertGreater(result["theoretical_space_delta_v"], 0)

    def test_exact_static_lift_boundary(self):
        self.configure(thrust="2200")
        result = self.budget(lift_nozzles=1)
        self.assertEqual(result["thrust_to_weight_ratio"], 1)
        self.assertFalse(result["overweight"])
        self.assertIsNotNone(result["ideal_hover_time_s"])
        self.assertIn("No flight certification", result["estimate_scope"])

    def test_configuration_changes_are_loaded_on_each_call(self):
        before = self.budget()
        self.configure(impulse=200, mass=2000, thrust="2_500.0e1D")
        after = self.budget()
        self.assertEqual(after["stored_fuel_mass"], 2 * before["stored_fuel_mass"])
        self.assertEqual(after["fuel_impulse_per_mb"], 200)
        self.assertEqual(after["full_power_force_per_nozzle"], 25000)
        self.assertEqual(after["total_lift"], 50000)
        self.assertAlmostEqual(after["theoretical_space_delta_v"], 100 * math.log(320 / 220))
        self.assertEqual(self.budget(fuel="gasoline"), {**after, "fuel": "gasoline",
                         "sources": {"fuel": str(FUEL_DIR / "gasoline.json"),
                                     "thrust": str(THRUST_SOURCE)}})

    def test_hydrogen_uses_live_parameters_and_is_shared_with_round_trip(self):
        self.assertIn("hydrogen", FUELS)
        self.assertNotIn("deuterium", FUELS)
        (self.root / FUEL_DIR / "hydrogen.json").write_text(
            json.dumps({"impulse": 200, "mass": 500}), encoding="utf-8")
        result = self.budget(fuel="hydrogen")
        self.assertEqual(result["stored_fuel_mass"], 50)
        self.assertEqual(result["fuel_impulse_per_mb"], 200)
        self.assertAlmostEqual(result["theoretical_space_delta_v"], 400 * math.log(170 / 145))
        plan = {"fuel": "hydrogen", "dry_mass": 120, "initial_fuel_mb": 100,
                "reserve_mb": 50,
                "legs": [{"name": "ideal burn", "delta_v": result["theoretical_space_delta_v"]}]}
        trip = calculate_round_trip(plan, self.root)
        self.assertTrue(trip["feasible"])
        self.assertAlmostEqual(trip["remaining_fuel_mb"], 50)
        self.assertEqual(trip["fuel_impulse_per_mb"], result["fuel_impulse_per_mb"])
        with self.assertRaises(ValueError):
            self.budget(fuel="deuterium")

    def test_current_repo_data_loads_without_cwd_dependency(self):
        for fuel in FUELS:
            with self.subTest(fuel=fuel):
                data = json.loads((REPO_ROOT / FUEL_DIR / f"{fuel}.json").read_text())
                impulse, mass, thrust = load_parameters(fuel)
                self.assertEqual(impulse, data["impulse"])
                self.assertEqual(mass, data.get("mass", 0))
                self.assertGreater(thrust, 0)

    def test_invalid_numeric_inputs(self):
        for key in ("dry_mass", "cargo_mass", "fuel_mb", "gravity", "reserve_fraction"):
            for invalid in (-1, float("nan"), float("inf"), -float("inf"), True, "1"):
                with self.subTest(key=key, invalid=invalid), self.assertRaises(ValueError):
                    self.budget(**{key: invalid})
        for changes in ({"dry_mass": 0}, {"gravity": 0}, {"reserve_fraction": 1},
                        {"reserve_fraction": 1.5}, {"fuel": "other"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.budget(**changes)
        for invalid in (-1, 1.5, 1.0, True, float("nan"), float("inf")):
            with self.subTest(nozzles=invalid), self.assertRaises(ValueError):
                self.budget(lift_nozzles=invalid)

    def test_invalid_fuel_configuration(self):
        for key, invalids in (("impulse", (0, -1, float("nan"), float("inf"), True, "100")),
                              ("mass", (-1, float("nan"), float("inf"), True, "1000"))):
            for invalid in invalids:
                with self.subTest(key=key, invalid=invalid):
                    self.configure(**{key: invalid})
                    with self.assertRaises(ValueError):
                        self.budget()
        for text in ("[]", "{}", "{broken"):
            with self.subTest(text=text):
                (self.root / FUEL_DIR / "diesel.json").write_text(text)
                with self.assertRaises(ValueError):
                    self.budget()

    def test_missing_sources_and_nonliteral_thrust_fail_without_fallback(self):
        for thrust in ("0", "-3", "1e999", "OTHER * 2", "3350 + 1"):
            with self.subTest(thrust=thrust):
                self.configure(thrust=thrust)
                with self.assertRaises(ValueError):
                    self.budget()
        for source in ("", "static final double THRUST = 1; static final double THRUST = 2;"):
            (self.root / THRUST_SOURCE).write_text(source)
            with self.assertRaises(ValueError):
                self.budget()
        self.configure()
        (self.root / THRUST_SOURCE).unlink()
        with self.assertRaises(OSError):
            self.budget()
        self.configure()
        (self.root / FUEL_DIR / "diesel.json").unlink()
        with self.assertRaises(OSError):
            self.budget()

    def test_nonfinite_derived_values_are_rejected(self):
        for changes in ({"dry_mass": 1e308, "gravity": 1e308},
                        {"fuel_mb": 1e308}, {"gravity": 1e-320}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.budget(**changes)


class FlightBudgetCliTest(unittest.TestCase):
    def invoke(self, *extra):
        script = Path(__file__).with_name("flight_budget.py").resolve()
        with tempfile.TemporaryDirectory() as cwd:
            return subprocess.run(
                [sys.executable, "-B", str(script), "--dry-mass", "100", "--fuel-mb", "1000",
                 "--fuel", "diesel", "--lift-nozzles", "2", *extra],
                cwd=cwd, env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
                capture_output=True, text=True, timeout=10, check=False)

    def test_cli_json_defaults_and_explicit_gravity(self):
        completed = self.invoke("--gravity", "9.81")
        self.assertEqual(completed.returncode, 0, completed.stderr)
        result = json.loads(completed.stdout)
        self.assertEqual(result["cargo_mass"], 0)
        self.assertEqual(result["reserve_fraction"], 0.5)
        self.assertEqual(result["effective_gravity"], 9.81)
        self.assertEqual(result["reserved_fuel_mb"], 500)
        self.assertEqual(completed.stderr, "")

    def test_cli_accepts_hydrogen_but_rejects_deuterium(self):
        completed = self.invoke("--gravity", "9.81", "--fuel", "hydrogen")
        self.assertEqual(completed.returncode, 0, completed.stderr)
        result = json.loads(completed.stdout)
        impulse, mass, _ = load_parameters("hydrogen")
        self.assertEqual(result["fuel"], "hydrogen")
        self.assertEqual(result["fuel_impulse_per_mb"], impulse)
        self.assertEqual(result["fuel_mass_per_1000mb"], mass)
        completed = self.invoke("--gravity", "9.81", "--fuel", "deuterium")
        self.assertEqual(completed.returncode, 2)
        self.assertEqual(completed.stdout, "")
        self.assertNotIn("Traceback", completed.stderr)

    def test_cli_rejects_missing_gravity_and_invalid_inputs(self):
        for extra in ((), ("--gravity", "0"), ("--gravity", "nan"),
                      ("--gravity", "inf"), ("--gravity", "-1"),
                      ("--gravity", "10", "--reserve-fraction", "1"),
                      ("--gravity", "10", "--dry-mass", "0"),
                      ("--gravity", "10", "--cargo-mass", "-1"),
                      ("--gravity", "10", "--fuel-mb", "-1"),
                      ("--gravity", "10", "--fuel", "other"),
                      ("--gravity", "10", "--lift-nozzles", "1.5")):
            with self.subTest(extra=extra):
                completed = self.invoke(*extra)
                self.assertEqual(completed.returncode, 2)
                self.assertEqual(completed.stdout, "")
                self.assertIn("error:", completed.stderr)
                self.assertNotIn("Traceback", completed.stderr)


if __name__ == "__main__":
    unittest.main()
