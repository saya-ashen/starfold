"""Owned, bounded standard-library tests; no game, network, Java or Gradle."""
import copy
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
from round_trip_budget import ROUND_TRIP_FUELS, calculate_round_trip


class RoundTripBudgetTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / FUEL_DIR).mkdir(parents=True)
        (self.root / THRUST_SOURCE).parent.mkdir(parents=True)
        self.configure()

    def configure(self, *, impulse=100, mass=1000, thrust=10000):
        for fuel in ROUND_TRIP_FUELS:
            data = {"impulse": impulse}
            if fuel == "deuterium":
                data["interstellar"] = True
            if mass is not None:
                data["mass"] = mass
            (self.root / FUEL_DIR / f"{fuel}.json").write_text(json.dumps(data), encoding="utf-8")
        (self.root / THRUST_SOURCE).write_text(
            f"public static final double THRUST = {thrust}, BOOST = 4;\n", encoding="utf-8")

    def plan(self, **changes):
        plan = {"fuel": "diesel", "dry_mass": 100, "initial_fuel_mb": 100,
                "reserve_mb": 50, "legs": [
                    {"name": "outbound", "delta_v": 100 * math.log(200 / 175)},
                    {"name": "return", "delta_v": 100 * math.log(175 / 150)},
                ]}
        plan.update(changes)
        return plan

    def budget(self, **changes):
        return calculate_round_trip(self.plan(**changes), repo_root=self.root)

    def test_analytic_sequential_burns_masses_and_reserve(self):
        result = self.budget()
        self.assertTrue(result["feasible"], result["reasons"])
        self.assertEqual(result["completed_legs"], 2)
        self.assertEqual(result["remaining_leg_names"], [])
        self.assertEqual(result["reasons"], [])
        for leg, departure, arrival, remaining in zip(result["legs"], (200, 175),
                                                      (175, 150), (75, 50)):
            self.assertTrue(leg["completed"])
            self.assertAlmostEqual(leg["departure_total_mass"], departure)
            self.assertAlmostEqual(leg["arrival_total_mass"], arrival)
            self.assertAlmostEqual(leg["required_burn_mb"], 25)
            self.assertAlmostEqual(leg["fuel_burn_mb"], 25)
            self.assertAlmostEqual(leg["remaining_fuel_mb"], remaining)
            self.assertEqual(leg["departure_nonfuel_mass"], 100)
            self.assertIsNone(leg["departure_thrust_to_weight_ratio"])
            self.assertIsNone(leg["overweight"])
        self.assertAlmostEqual(result["total_burn_mb"], 50)
        self.assertGreaterEqual(result["remaining_fuel_mb"], 50)
        self.assertAlmostEqual(result["remaining_total_mass"], 150)
        self.assertEqual(result["remaining_nonfuel_mass"], 100)

    def test_combined_delta_v_matches_sequential_without_added_mass(self):
        split = self.budget()
        combined = self.budget(legs=[{"name": "combined", "delta_v": sum(
            leg["delta_v"] for leg in self.plan()["legs"])}])
        self.assertTrue(combined["feasible"], combined["reasons"])
        self.assertAlmostEqual(split["total_burn_mb"], combined["total_burn_mb"])
        self.assertAlmostEqual(split["remaining_total_mass"], combined["remaining_total_mass"])

    def test_inverse_consistency_with_existing_calculator(self):
        for fuel in ("diesel", "gasoline", "hydrogen"):
            for mass in (1000, 0, None, 1e-20):
                with self.subTest(fuel=fuel, mass=mass):
                    self.configure(mass=mass)
                    forward = calculate_budget(
                        dry_mass=100, cargo_mass=20, fuel_mb=100, fuel=fuel,
                        lift_nozzles=2, gravity=10, reserve_fraction=0.5, repo_root=self.root)
                    inverse = self.budget(fuel=fuel, legs=[{
                        "name": "inverse", "delta_v": forward["theoretical_space_delta_v"],
                        "added_mass": 20}])
                    self.assertTrue(inverse["feasible"], inverse["reasons"])
                    leg = inverse["legs"][0]
                    self.assertAlmostEqual(leg["required_burn_mb"], 50, places=12)
                    self.assertAlmostEqual(leg["departure_total_mass"], forward["total_mass"])
                    self.assertAlmostEqual(leg["arrival_total_mass"],
                                           forward["mass_after_budget_burn"])
                    self.assertGreaterEqual(inverse["remaining_fuel_mb"], 50)

    def test_current_repo_parameters_are_reused_for_all_supported_fuels(self):
        for fuel in ("diesel", "gasoline", "hydrogen"):
            with self.subTest(fuel=fuel):
                result = calculate_round_trip(self.plan(fuel=fuel))
                impulse, mass, thrust = load_parameters(fuel)
                self.assertEqual(result["fuel_impulse_per_mb"], impulse)
                self.assertEqual(result["fuel_mass_per_1000mb"], mass)
                self.assertEqual(result["full_power_force_per_nozzle"], thrust)
                self.assertTrue(result["feasible"], result["reasons"])

    def test_cargo_added_late_only_affects_that_and_later_departures(self):
        plan = self.plan()
        plan["reserve_mb"] = 0
        plan["legs"][1].update(added_mass=50, delta_v=100 * math.log(225 / 200))
        plan["legs"].append({"name": "landing", "delta_v": 0})
        original = copy.deepcopy(plan)
        late = calculate_round_trip(plan, self.root)
        self.assertEqual(plan, original)
        self.assertTrue(late["feasible"])
        self.assertAlmostEqual(late["legs"][0]["departure_total_mass"], 200)
        self.assertAlmostEqual(late["legs"][1]["departure_total_mass"], 225)
        self.assertAlmostEqual(late["legs"][1]["fuel_burn_mb"], 25)
        self.assertEqual(late["legs"][2]["departure_nonfuel_mass"], 150)
        self.assertAlmostEqual(late["remaining_total_mass"], 200)
        early_legs = copy.deepcopy(plan["legs"])
        early_legs[0]["added_mass"] = early_legs[1].pop("added_mass")
        early = self.budget(legs=early_legs, reserve_mb=0)
        self.assertGreater(early["legs"][0]["fuel_burn_mb"], late["legs"][0]["fuel_burn_mb"])
        self.assertGreater(early["total_burn_mb"], late["total_burn_mb"])

    def test_cumulative_failure_retains_fuel_and_loaded_mass_and_stops(self):
        legs = copy.deepcopy(self.plan()["legs"])
        legs[1].update(delta_v=100, added_mass=20)
        legs.append({"name": "later cargo", "delta_v": 0, "added_mass": 999})
        result = self.budget(legs=legs)
        self.assertFalse(result["feasible"])
        self.assertEqual(result["completed_legs"], 1)
        self.assertEqual(result["remaining_leg_names"], ["later cargo"])
        self.assertEqual(len(result["legs"]), 2)
        failed = result["legs"][1]
        self.assertFalse(failed["completed"])
        self.assertEqual(failed["fuel_burn_mb"], 0)
        self.assertAlmostEqual(failed["burnable_fuel_mb"], 25)
        self.assertGreater(failed["required_burn_mb"], failed["burnable_fuel_mb"])
        self.assertAlmostEqual(result["remaining_fuel_mb"], 75)
        self.assertEqual(failed["departure_total_mass"], failed["arrival_total_mass"])
        self.assertEqual(result["remaining_nonfuel_mass"], 120)
        self.assertAlmostEqual(result["remaining_total_mass"], 195)
        self.assertIn("return: insufficient non-reserved fuel", result["reasons"][0])
        self.assertEqual(result["reasons"], failed["reasons"])

    def test_first_leg_insufficient_does_not_spend_any_fuel(self):
        result = self.budget(legs=[{"name": "too far", "delta_v": 1000}])
        self.assertFalse(result["feasible"])
        self.assertEqual(result["total_burn_mb"], 0)
        self.assertEqual(result["remaining_fuel_mb"], 100)
        self.assertEqual(result["remaining_total_mass"], 200)

    def test_zero_demand_zero_fuel_and_all_reserved_fuel(self):
        for initial, reserve in ((0, 0), (50, 50), (100, 0)):
            with self.subTest(initial=initial, reserve=reserve):
                result = self.budget(initial_fuel_mb=initial, reserve_mb=reserve,
                                     legs=[{"name": "stationary", "delta_v": 0}])
                self.assertTrue(result["feasible"])
                self.assertEqual(result["total_burn_mb"], 0)
                self.assertEqual(result["remaining_fuel_mb"], initial)
                self.assertEqual(result["remaining_total_mass"], 100 + initial)
        for initial, reserve in ((0, 0), (50, 50)):
            with self.subTest(initial=initial, reserve=reserve):
                result = self.budget(initial_fuel_mb=initial, reserve_mb=reserve,
                                     legs=[{"name": "needs fuel", "delta_v": 1e-20}])
                self.assertFalse(result["feasible"])
                self.assertEqual(result["remaining_fuel_mb"], initial)

    def test_zero_mass_fuel_uses_linear_limit_and_persistent_cargo(self):
        for mass in (0, None):
            with self.subTest(mass=mass):
                self.configure(mass=mass)
                result = self.budget(reserve_mb=0, legs=[
                    {"name": "out", "delta_v": 10},
                    {"name": "back", "delta_v": 10, "added_mass": 50}])
                self.assertTrue(result["feasible"])
                self.assertEqual([leg["fuel_burn_mb"] for leg in result["legs"]], [10, 15])
                self.assertEqual(result["remaining_fuel_mb"], 75)
                self.assertEqual(result["remaining_total_mass"], 150)

    def test_inverse_saturation_cannot_claim_excess_delta_v_near_exhaustion(self):
        result = self.budget(dry_mass=1e-12, reserve_mb=0,
                             legs=[{"name": "beyond budget", "delta_v": 10000}])
        leg = result["legs"][0]
        # The inverse burn differs from 100 mB by only ~1e-12, but the
        # requested dv is over three times the actual available propulsion.
        self.assertAlmostEqual(leg["required_burn_mb"], 100, places=10)
        self.assertAlmostEqual(leg["available_delta_v"],
                               100 * math.log1p(100 / 1e-12))
        self.assertLess(leg["available_delta_v"], 10000)
        self.assertFalse(result["feasible"])
        self.assertEqual(result["total_burn_mb"], 0)
        self.assertEqual(result["remaining_fuel_mb"], 100)

    def test_small_burns_accumulate_independently_of_large_stored_reserve(self):
        result = self.budget(initial_fuel_mb=1e20, reserve_mb=99999999999999983616,
                             legs=[{"name": f"leg {i}", "delta_v": 1e-15} for i in range(20)])
        self.assertFalse(result["feasible"])
        self.assertEqual(result["completed_legs"], 16)
        self.assertEqual(result["remaining_leg_names"], ["leg 17", "leg 18", "leg 19"])
        self.assertAlmostEqual(result["total_burn_mb"], 16000)
        self.assertAlmostEqual(result["remaining_burnable_fuel_mb"], 384)
        self.assertAlmostEqual(result["total_burn_mb"],
                               math.fsum(leg["fuel_burn_mb"] for leg in result["legs"]))
        self.assertEqual(result["legs"][-1]["fuel_burn_mb"], 0)
        self.assertGreaterEqual(result["remaining_fuel_mb"], result["reserve_mb"])
        self.assertGreater(result["legs"][0]["burnable_fuel_mb"],
                           result["legs"][-1]["burnable_fuel_mb"])

    def test_large_exponent_and_underflow_limit_remain_finite(self):
        result = self.budget(legs=[{"name": "extreme", "delta_v": 100000}])
        self.assertFalse(result["feasible"])
        self.assertEqual(result["legs"][0]["required_burn_mb"], 200)
        self.configure(mass=1e-300)
        tiny = self.budget(legs=[{"name": "tiny", "delta_v": 1e-20}])
        self.assertTrue(tiny["feasible"])
        self.assertGreater(tiny["legs"][0]["required_burn_mb"], 0)
        self.assertAlmostEqual(tiny["legs"][0]["required_burn_mb"] / 1e-20, 1)
        json.dumps(tiny, allow_nan=False)

    def test_static_twr_uses_departure_wet_mass_including_cargo_and_reserve(self):
        self.configure(thrust=2000)
        result = self.budget(legs=[
            {"name": "out", "delta_v": 0, "effective_gravity": 10, "lift_nozzles": 1},
            {"name": "back", "delta_v": 0, "added_mass": 1,
             "effective_gravity": 10, "lift_nozzles": 1}])
        self.assertFalse(result["feasible"])
        first, second = result["legs"]
        self.assertEqual(first["departure_thrust_to_weight_ratio"], 1)
        self.assertFalse(first["overweight"])
        self.assertTrue(first["completed"])
        self.assertEqual(second["departure_weight"], 2010)
        self.assertEqual(second["total_lift"], 2000)
        self.assertTrue(second["overweight"])
        self.assertIn("departure wet TWR below 1", result["reasons"][0])
        self.assertEqual(result["remaining_fuel_mb"], 100)

    def test_twr_is_checked_before_burn_and_zero_nozzles_fail(self):
        self.configure(thrust=1800)
        for nozzles in (0, 1):
            with self.subTest(nozzles=nozzles):
                result = self.budget(legs=[{
                    "name": "departure", "delta_v": 100 * math.log(200 / 150),
                    "effective_gravity": 10, "lift_nozzles": nozzles}])
                self.assertFalse(result["feasible"])
                self.assertTrue(result["legs"][0]["overweight"])
                self.assertEqual(result["legs"][0]["fuel_burn_mb"], 0)
                self.assertEqual(result["remaining_fuel_mb"], 100)

    def test_reports_both_fuel_and_lift_failure(self):
        result = self.budget(legs=[{"name": "blocked", "delta_v": 1000,
                                   "effective_gravity": 10, "lift_nozzles": 0}])
        self.assertFalse(result["feasible"])
        self.assertEqual(len(result["reasons"]), 2)
        self.assertEqual(result["remaining_fuel_mb"], 100)

    def test_invalid_top_level_fields_and_numbers(self):
        for key in ("dry_mass", "initial_fuel_mb", "reserve_mb"):
            for invalid in (-1, True, False, "1", None, [], {}, math.nan, math.inf, -math.inf,
                            10 ** 1000):
                with self.subTest(key=key, invalid=invalid), self.assertRaises(ValueError):
                    self.budget(**{key: invalid})
        for changes in ({"dry_mass": 0}, {"reserve_mb": 101}, {"fuel": "other"},
                        {"fuel": True}, {"fuel": []}, {"legs": []}, {"legs": {}},
                        {"legs": None}, {"extra": 1}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.budget(**changes)
        for invalid in ([], None, True, "plan"):
            with self.subTest(plan=invalid), self.assertRaises(ValueError):
                calculate_round_trip(invalid, self.root)
        for key in self.plan():
            plan = self.plan()
            del plan[key]
            with self.subTest(missing=key), self.assertRaises(ValueError):
                calculate_round_trip(plan, self.root)

    def test_invalid_leg_fields_and_numbers(self):
        invalids = (-1, True, False, "1", None, [], {}, math.nan, math.inf, -math.inf,
                    10 ** 1000)
        for key in ("delta_v", "added_mass", "effective_gravity", "lift_nozzles"):
            for invalid in invalids:
                leg = {"name": "leg", "delta_v": 0,
                       "effective_gravity": 10, "lift_nozzles": 1, key: invalid}
                with self.subTest(key=key, invalid=invalid), self.assertRaises(ValueError):
                    self.budget(legs=[leg])
        invalid_legs = (None, [], 0, {}, {"name": "x"}, {"delta_v": 0},
                        {"name": "x", "delta_v": 0, "unknown": 1},
                        {"name": "x", "delta_v": 0, "effective_gravity": 10},
                        {"name": "x", "delta_v": 0, "lift_nozzles": 1},
                        {"name": "x", "delta_v": 0, "effective_gravity": 0, "lift_nozzles": 1})
        for leg in invalid_legs:
            with self.subTest(leg=leg), self.assertRaises(ValueError):
                self.budget(legs=[leg])
        for nozzles in (1.0, 1.5):
            with self.subTest(nozzles=nozzles), self.assertRaises(ValueError):
                self.budget(legs=[{"name": "x", "delta_v": 0,
                                   "effective_gravity": 10, "lift_nozzles": nozzles}])

    def test_names_are_explicit_nonblank_and_unique(self):
        for name in ("", " \n\t", 1, True, None, []):
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.budget(legs=[{"name": name, "delta_v": 0}])
        for duplicate in ("outbound", " outbound "):
            with self.subTest(duplicate=duplicate), self.assertRaises(ValueError):
                self.budget(legs=[{"name": "outbound", "delta_v": 0},
                                  {"name": duplicate, "delta_v": 0}])

    def test_all_legs_validated_even_after_an_infeasible_leg(self):
        with self.assertRaises(ValueError):
            self.budget(legs=[{"name": "too far", "delta_v": 1000},
                              {"name": "invalid later", "delta_v": True}])

    def test_live_parameter_changes_and_invalid_sources(self):
        before = self.budget()
        self.configure(impulse=200, mass=2000, thrust=25000)
        after = self.budget()
        self.assertNotEqual(before["legs"][0]["required_burn_mb"],
                            after["legs"][0]["required_burn_mb"])
        self.assertEqual(after["fuel_impulse_per_mb"], 200)
        self.assertEqual(after["fuel_mass_per_1000mb"], 2000)
        self.assertEqual(after["full_power_force_per_nozzle"], 25000)
        for key, invalid in (("impulse", 0), ("mass", -1), ("thrust", "OTHER * 2")):
            self.configure(**{key: invalid})
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.budget()
        self.configure()
        (self.root / THRUST_SOURCE).unlink()
        with self.assertRaises(OSError):
            self.budget()

    def test_nonfinite_derived_values_fail_instead_of_json_infinity(self):
        for changes in (
            {"initial_fuel_mb": 1e308, "dry_mass": 1e308},
            {"legs": [{"name": "weight overflow", "delta_v": 0,
                       "effective_gravity": 1e308, "lift_nozzles": 1}]},
            {"legs": [{"name": "lift overflow", "delta_v": 0,
                       "effective_gravity": 10, "lift_nozzles": 10 ** 308}]},
            {"legs": [{"name": "cargo overflow", "delta_v": 0, "added_mass": 1e308},
                      {"name": "more", "delta_v": 0, "added_mass": 1e308}]},
        ):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.budget(**changes)
        self.configure(impulse=1e-300)
        with self.assertRaises(ValueError):
            self.budget(legs=[{"name": "burn overflow", "delta_v": 1e308}])

    def test_interstellar_gate_matrix_and_home_defaults(self):
        for fuel in ROUND_TRIP_FUELS:
            for drive in (False, True):
                for outside in (False, True):
                    with self.subTest(fuel=fuel, drive=drive, outside=outside):
                        result = self.budget(fuel=fuel, drive_installed=drive, legs=[{
                            "name": "departure", "delta_v": 1,
                            "outside_home_system": outside}])
                        eligible = not outside or (drive and fuel == "deuterium")
                        self.assertEqual(result["feasible"], eligible)
                        self.assertIs(result["drive_installed"], drive)
                        self.assertIs(result["fuel_interstellar"], fuel == "deuterium")
                        leg = result["legs"][0]
                        self.assertIs(leg["outside_home_system"], outside)
                        self.assertTrue(leg["propulsion_required"])
                        self.assertIs(leg["eligibility_checked"], outside)
                        self.assertIs(leg["propulsion_eligible"], eligible)
                        self.assertEqual(leg["completed"], eligible)
                        if eligible:
                            self.assertGreater(result["total_burn_mb"], 0)
                        else:
                            self.assertEqual(result["total_burn_mb"], 0)
                            self.assertEqual(result["remaining_fuel_mb"], 100)
                            self.assertEqual(result["remaining_total_mass"], 200)
                            reason = " ".join(result["reasons"])
                            self.assertEqual("drive_installed=true" in reason, not drive)
                            self.assertEqual("interstellar=true" in reason,
                                             fuel != "deuterium")
        default = self.budget()
        self.assertFalse(default["drive_installed"])
        self.assertFalse(default["fuel_interstellar"])
        for leg in default["legs"]:
            self.assertFalse(leg["outside_home_system"])
            self.assertFalse(leg["eligibility_checked"])
            self.assertTrue(leg["propulsion_eligible"])

    def test_gate_failure_after_home_burn_keeps_fuel_mass_and_stops(self):
        for fuel, drive in (("diesel", True), ("deuterium", False)):
            with self.subTest(fuel=fuel):
                result = self.budget(fuel=fuel, drive_installed=drive, legs=[
                    {"name": "home", "delta_v": 100 * math.log(200 / 175)},
                    {"name": "outside", "delta_v": 1, "added_mass": 20,
                     "outside_home_system": True},
                    {"name": "unevaluated", "delta_v": 0, "added_mass": 999}])
                self.assertFalse(result["feasible"])
                self.assertEqual(result["completed_legs"], 1)
                self.assertEqual(result["remaining_leg_names"], ["unevaluated"])
                self.assertAlmostEqual(result["total_burn_mb"], 25)
                self.assertAlmostEqual(result["remaining_fuel_mb"], 75)
                self.assertAlmostEqual(result["remaining_burnable_fuel_mb"], 25)
                self.assertEqual(result["remaining_nonfuel_mass"], 120)
                self.assertAlmostEqual(result["remaining_total_mass"], 195)
                failed = result["legs"][1]
                self.assertEqual(failed["fuel_burn_mb"], 0)
                self.assertEqual(failed["departure_total_mass"], failed["arrival_total_mass"])
                self.assertEqual(result["reasons"], failed["reasons"])

    def test_zero_delta_v_outside_coasts_skip_gate_but_positive_tiny_demand_does_not(self):
        for fuel in ROUND_TRIP_FUELS:
            for drive in (False, True):
                for initial, reserve in ((0, 0), (100, 100)):
                    with self.subTest(fuel=fuel, drive=drive, initial=initial):
                        result = self.budget(fuel=fuel, drive_installed=drive,
                                             initial_fuel_mb=initial, reserve_mb=reserve,
                                             legs=[{"name": "coast", "delta_v": 0,
                                                    "outside_home_system": True}])
                        self.assertTrue(result["feasible"], result["reasons"])
                        self.assertFalse(result["legs"][0]["propulsion_required"])
                        self.assertFalse(result["legs"][0]["eligibility_checked"])
                        self.assertEqual(result["legs"][0]["propulsion_eligible"],
                                         drive and fuel == "deuterium")
                        self.assertEqual(result["total_burn_mb"], 0)
                        self.assertEqual(result["remaining_fuel_mb"], initial)
                        self.assertEqual(result["remaining_total_mass"], 100 + initial)
        tiny = self.budget(legs=[{"name": "tiny", "delta_v": 1e-300,
                                 "outside_home_system": True}])
        self.assertFalse(tiny["feasible"])
        self.assertTrue(tiny["legs"][0]["eligibility_checked"])
        self.assertEqual(tiny["total_burn_mb"], 0)
        # Coasting does not waive an explicitly requested static lift check.
        coast = self.budget(legs=[{"name": "coast", "delta_v": 0,
                                  "outside_home_system": True,
                                  "effective_gravity": 10, "lift_nozzles": 0}])
        self.assertFalse(coast["feasible"])
        self.assertEqual(len(coast["reasons"]), 1)
        self.assertIn("TWR below 1", coast["reasons"][0])

    def test_eligible_interstellar_burn_still_checks_reserve_lift_and_added_mass(self):
        good = self.budget(fuel="deuterium", drive_installed=True, legs=[{
            "name": "outside", "delta_v": 100 * math.log(220 / 170),
            "outside_home_system": True, "added_mass": 20,
            "effective_gravity": 10, "lift_nozzles": 1}])
        self.assertTrue(good["feasible"], good["reasons"])
        self.assertAlmostEqual(good["total_burn_mb"], 50)
        self.assertAlmostEqual(good["remaining_fuel_mb"], 50)
        self.assertAlmostEqual(good["remaining_total_mass"], 170)
        self.assertEqual(good["legs"][0]["departure_weight"], 2200)
        bad = self.budget(fuel="deuterium", drive_installed=True, legs=[{
            "name": "outside", "delta_v": 1000, "outside_home_system": True,
            "effective_gravity": 10, "lift_nozzles": 0}])
        self.assertTrue(bad["legs"][0]["propulsion_eligible"])
        self.assertFalse(bad["feasible"])
        self.assertEqual(len(bad["reasons"]), 2)
        self.assertEqual(bad["remaining_fuel_mb"], 100)

    def test_declared_booleans_require_actual_boolean_types_on_all_legs(self):
        for invalid in (0, 1, -1, 0.0, 1.0, "true", "false", None, [], {}, math.nan):
            with self.subTest(root=invalid), self.assertRaisesRegex(ValueError, "boolean"):
                self.budget(drive_installed=invalid)
            with self.subTest(leg=invalid), self.assertRaisesRegex(ValueError, "boolean"):
                self.budget(legs=[{"name": "blocked", "delta_v": 1000},
                                  {"name": "later", "delta_v": 0,
                                   "outside_home_system": invalid}])

    def test_eligibility_comes_from_actual_fuel_definition_on_each_call(self):
        plan = self.plan(fuel="deuterium", drive_installed=True, legs=[{
            "name": "outside", "delta_v": 1, "outside_home_system": True}])
        path = self.root / FUEL_DIR / "deuterium.json"
        for flag in (True, False, None):
            data = {"impulse": 100, "mass": 1000}
            if flag is not None:
                data["interstellar"] = flag
            path.write_text(json.dumps(data), encoding="utf-8")
            with self.subTest(flag=flag):
                result = calculate_round_trip(plan, self.root)
                self.assertIs(result["fuel_interstellar"], flag is True)
                self.assertIs(result["feasible"], flag is True)
        # The flag controls eligibility, rather than a hardcoded fuel name.
        (self.root / FUEL_DIR / "diesel.json").write_text(json.dumps({
            "impulse": 100, "mass": 1000, "interstellar": True}), encoding="utf-8")
        plan["fuel"] = "diesel"
        self.assertTrue(calculate_round_trip(plan, self.root)["feasible"])

    def test_interstellar_source_flag_is_strict_and_has_no_fallback(self):
        path = self.root / FUEL_DIR / "deuterium.json"
        for invalid in (0, 1, "true", "false", None, [], {}):
            path.write_text(json.dumps({"impulse": 100, "interstellar": invalid}),
                            encoding="utf-8")
            with self.subTest(flag=invalid), self.assertRaisesRegex(ValueError, "boolean"):
                self.budget(fuel="deuterium")
        for text in ("[]", "{}", "{broken", '{"impulse": 0}',
                     '{"impulse": 100, "mass": -1}',
                     '{"impulse": 100, "interstellar": true, "interstellar": false}',
                     '{"impulse": 100, "interstellar": NaN}'):
            path.write_text(text, encoding="utf-8")
            with self.subTest(text=text), self.assertRaises(ValueError):
                self.budget(fuel="deuterium")
        path.unlink()
        with self.assertRaises(OSError):
            self.budget(fuel="deuterium")
        self.configure()
        (self.root / THRUST_SOURCE).write_text(
            "static final double THRUST = OTHER * 2;", encoding="utf-8")
        with self.assertRaises(ValueError):
            self.budget(fuel="deuterium")

    def test_deuterium_mass_and_impulse_are_live_including_zero_mass_limit(self):
        path = self.root / FUEL_DIR / "deuterium.json"
        for mass in (2000, 0, None):
            data = {"impulse": 200, "interstellar": True}
            if mass is not None:
                data["mass"] = mass
            path.write_text(json.dumps(data), encoding="utf-8")
            density = (mass or 0) / 1000
            delta_v = (200 / density * math.log((100 + 100 * density) /
                                               (100 + 75 * density)) if density else 50)
            with self.subTest(mass=mass):
                result = self.budget(fuel="deuterium", drive_installed=True, legs=[{
                    "name": "outside", "delta_v": delta_v, "outside_home_system": True}])
                self.assertTrue(result["feasible"], result["reasons"])
                self.assertEqual(result["fuel_impulse_per_mb"], 200)
                self.assertEqual(result["fuel_mass_per_1000mb"], mass or 0)
                self.assertAlmostEqual(result["total_burn_mb"], 25)
                self.assertAlmostEqual(result["remaining_total_mass"], 100 + 75 * density)

    def test_local_deuterium_does_not_expand_shared_fuel_policy(self):
        self.assertEqual(FUELS, ("diesel", "gasoline", "hydrogen"))
        with self.assertRaises(ValueError):
            load_parameters("deuterium", self.root)
        with self.assertRaises(ValueError):
            calculate_budget(dry_mass=100, fuel_mb=100, fuel="deuterium",
                             lift_nozzles=1, gravity=10, repo_root=self.root)
        self.assertEqual(load_parameters("deuterium", self.root,
                                         allowed_fuels=ROUND_TRIP_FUELS), (100, 1000, 10000))
        with self.assertRaises(ValueError):
            load_parameters("diesel", self.root, allowed_fuels=("deuterium",))
        for fuel in ("../deuterium", "deuterium.json", "other"):
            with self.subTest(fuel=fuel), self.assertRaises(ValueError):
                self.budget(fuel=fuel)

    def test_repo_deuterium_uses_local_data_and_reports_sources(self):
        data = json.loads((REPO_ROOT / FUEL_DIR / "deuterium.json").read_text())
        result = calculate_round_trip(self.plan(fuel="deuterium", drive_installed=True,
                                               legs=[{"name": "outside", "delta_v": 1,
                                                      "outside_home_system": True}]))
        self.assertTrue(result["feasible"], result["reasons"])
        self.assertIs(result["fuel_interstellar"], data["interstellar"])
        self.assertEqual(result["fuel_impulse_per_mb"], data["impulse"])
        self.assertEqual(result["fuel_mass_per_1000mb"], data["mass"])
        self.assertEqual(result["sources"], {"fuel": str(FUEL_DIR / "deuterium.json"),
                                            "thrust": str(THRUST_SOURCE)})
        self.assertAlmostEqual(result["legs"][0]["departure_total_mass"],
                               100 + 100 * data["mass"] / 1000)

    def test_scope_states_ideal_boundaries_and_no_certification(self):
        result = self.budget()
        scope = result["estimate_scope"]
        for phrase in ("no flight or round-trip certification", "gravity losses", "inventory count",
                       "not checked", "Fuel eligibility", "Sable units",
                       "outside_home_system", "drive_installed", "interstellar=true",
                       "Zero delta_v coasts", "all stored fuel counts as mass"):
            self.assertIn(phrase, scope)
        self.assertNotIn("pass", result)
        self.assertNotIn("certified", result)
        json.dumps(result, allow_nan=False)


class RoundTripBudgetCliTest(unittest.TestCase):
    def invoke(self, text=None, *, extra=(), missing=False):
        script = Path(__file__).with_name("round_trip_budget.py").resolve()
        with tempfile.TemporaryDirectory() as cwd:
            args = [sys.executable, "-B", str(script)]
            if text is not None or missing:
                path = Path(cwd) / "plan with spaces.json"
                if not missing:
                    path.write_text(text, encoding="utf-8")
                args.extend(("--plan", str(path)))
            return subprocess.run(
                [*args, *extra], cwd=cwd, capture_output=True, text=True, timeout=10,
                env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"}, check=False)

    def plan(self, **changes):
        plan = {"fuel": "diesel", "dry_mass": 100, "initial_fuel_mb": 100,
                "reserve_mb": 10, "legs": [{"name": "out", "delta_v": 1},
                                           {"name": "back", "delta_v": 1, "added_mass": 10}]}
        plan.update(changes)
        return json.dumps(plan)

    def test_cli_outputs_json_from_unrelated_cwd_for_all_fuels(self):
        for fuel in ROUND_TRIP_FUELS:
            with self.subTest(fuel=fuel):
                completed = self.invoke(self.plan(fuel=fuel))
                self.assertEqual(completed.returncode, 0, completed.stderr)
                self.assertEqual(completed.stderr, "")
                result = json.loads(completed.stdout)
                self.assertTrue(result["feasible"])
                self.assertEqual(result["fuel"], fuel)
                self.assertEqual(result["completed_legs"], 2)
                self.assertGreaterEqual(result["remaining_fuel_mb"], 10)
                self.assertEqual(result["remaining_nonfuel_mass"], 110)

    def test_cli_interstellar_gate_exit_codes_and_coast(self):
        for fuel, drive, delta_v, expected in (
            ("deuterium", True, 1, 0), ("deuterium", False, 1, 1),
            ("hydrogen", True, 1, 1), ("diesel", False, 0, 0)):
            with self.subTest(fuel=fuel, drive=drive, delta_v=delta_v):
                completed = self.invoke(self.plan(fuel=fuel, drive_installed=drive, legs=[{
                    "name": "outside", "delta_v": delta_v, "outside_home_system": True}]))
                self.assertEqual(completed.returncode, expected, completed.stderr)
                self.assertEqual(completed.stderr, "")
                result = json.loads(completed.stdout)
                self.assertEqual(result["feasible"], expected == 0)
                self.assertIs(result["drive_installed"], drive)
                self.assertTrue(result["legs"][0]["outside_home_system"])
                if expected == 1:
                    self.assertEqual(result["remaining_fuel_mb"], 100)
                    self.assertEqual(result["total_burn_mb"], 0)

    def test_cli_infeasible_plan_is_json_and_exit_one(self):
        completed = self.invoke(self.plan(legs=[{"name": "too far", "delta_v": 100000}]))
        self.assertEqual(completed.returncode, 1, completed.stderr)
        self.assertEqual(completed.stderr, "")
        result = json.loads(completed.stdout)
        self.assertFalse(result["feasible"])
        self.assertTrue(result["reasons"])
        self.assertEqual(result["remaining_fuel_mb"], 100)
        self.assertEqual(result["total_burn_mb"], 0)

    def test_cli_malformed_json_schema_and_numbers_are_clean_errors(self):
        texts = ("{broken", "[]", "{}", "null", "[" * 1100 + "0" + "]" * 1100,
                 self.plan(dry_mass=True), self.plan(drive_installed="true"),
                 self.plan(legs=[{"name": "x", "delta_v": 0, "outside_home_system": 1}]),
                 self.plan(dry_mass=0), self.plan(reserve_mb=101), self.plan(fuel="other"),
                 self.plan(initial_fuel_mb=math.inf), self.plan(reserve_mb=math.nan),
                 self.plan(legs=[{"name": "x", "delta_v": -1}]),
                 self.plan(legs=[{"name": "x", "delta_v": 1e309}]),
                 self.plan(legs=[{"name": "x", "delta_v": 0, "lift_nozzles": 1.5,
                                  "effective_gravity": 10}]),
                 self.plan().replace('"dry_mass": 100', '"dry_mass": 1e999'),
                 self.plan().replace('"fuel": "diesel"', '"fuel": "gasoline", "fuel": "diesel"'),
                 self.plan().replace('"delta_v": 1', '"delta_v": 2, "delta_v": 1', 1))
        for text in texts:
            with self.subTest(text=text):
                completed = self.invoke(text)
                self.assertEqual(completed.returncode, 2)
                self.assertEqual(completed.stdout, "")
                self.assertIn("error:", completed.stderr)
                self.assertNotIn("Traceback", completed.stderr)

    def test_cli_missing_plan_missing_file_and_unknown_argument(self):
        for kwargs in ({}, {"missing": True}, {"text": self.plan(), "extra": ("--other",)}):
            with self.subTest(kwargs=kwargs):
                completed = self.invoke(**kwargs)
                self.assertEqual(completed.returncode, 2)
                self.assertEqual(completed.stdout, "")
                self.assertIn("error:", completed.stderr)
                self.assertNotIn("Traceback", completed.stderr)

    def test_cli_help_documents_plan_and_exit_codes(self):
        completed = self.invoke(extra=("--help",))
        self.assertEqual(completed.returncode, 0)
        self.assertIn("--plan", completed.stdout)
        self.assertIn("gravity losses", completed.stdout)
        self.assertIn("Exit codes", completed.stdout)
        for phrase in ("deuterium", "drive_installed", "outside_home_system",
                       "interstellar=true", "Zero delta_v", "default false"):
            self.assertIn(phrase, completed.stdout)
        self.assertEqual(completed.stderr, "")


if __name__ == "__main__":
    unittest.main()
