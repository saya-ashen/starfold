"""Bounded offline formula/configuration/CLI tests; standard library only."""
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from first_ship import layout
from flight_budget import FUEL_DIR, REPO_ROOT, THRUST_SOURCE, load_parameters
from upgrade_comparison import (CORE_HEAVY_TAG, PLATING_PROPERTIES, SABLE_DATA,
                                calculate_comparison, load_mass_model)


class UpgradeComparisonTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        paths = [CORE_HEAVY_TAG, PLATING_PROPERTIES, THRUST_SOURCE]
        paths += [FUEL_DIR / f"{fuel}.json" for fuel in ("diesel", "hydrogen")]
        paths += [SABLE_DATA / "physics_block_properties" / f"{name}.json"
                  for name in ("heavy", "light", "super_light", "normal")]
        paths += [SABLE_DATA / "tags/block" / f"{name}.json"
                  for name in ("heavy", "light", "super_light")]
        for path in paths:
            (self.root / path).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPO_ROOT / path, self.root / path)

    def compare(self, **changes):
        args = dict(gravity=9.81, fuel_mb=4000, cargo_mass=0,
                    reserve_fraction=0.5, lift_nozzles=2, repo_root=self.root)
        args.update(changes)
        return calculate_comparison(**args)

    def change_json(self, path, change):
        data = json.loads((self.root / path).read_text())
        change(data)
        (self.root / path).write_text(json.dumps(data), encoding="utf-8")

    def test_current_root_mass_is_derived_from_placed_blocks_and_live_json(self):
        model = load_mass_model()
        self.assertEqual(model["placed_blocks"], len(layout()))
        self.assertEqual(model["placed_blocks"], 185)
        self.assertEqual(model["replacement_count"], 136)
        rows = {row["block"]: row for row in model["block_masses"]}
        self.assertEqual(rows["minecraft:oak_door"]["placed_count"], 4)  # two items
        self.assertEqual(rows["minecraft:barrel"]["mass_per_block"], 0.5)
        self.assertEqual(rows["create:fluid_tank"]["mass_per_block"], 1)
        self.assertEqual(rows["minecraft:lever"]["subtotal"], 0)
        # Known B6-F mass accounting: 136 ballast + 18 heavy ship components,
        # 19 planks + 2 barrels, 4 placed door halves, 4 empty tanks.
        self.assertEqual(model["reference_baseline_dry_mass"],
                         (136 + 18) * 2 + (19 + 2) * 0.5 + 4 * 0.25 + 4 * 1)
        self.assertEqual(model["reference_baseline_dry_mass"], 323.5)
        self.assertEqual(model["cobblestone_mass_per_block"], 2)
        self.assertEqual(model["plating_mass_per_block"], 0.5)
        self.assertEqual(model["dry_mass_reduction"], 204)
        result = calculate_comparison(gravity=10, fuel_mb=4000, cargo_mass=0,
                                      reserve_fraction=0.5, lift_nozzles=2)
        self.assertEqual(result["light_dry_mass"], 119.5)
        self.assertIn("reference estimate", result["baseline_dry_mass_source"])
        self.assertIn("not measured", model["assumptions"])
        self.assertIn("non-solid", model["assumptions"])
        self.assertIn("No runtime or cargo-mass qualification", result["estimate_scope"])
        self.assertIn("no flight certification", result["estimate_scope"])

    def test_mass_values_are_reloaded_instead_of_hardcoded_totals(self):
        self.compare()
        for category, mass in (("heavy", 3), ("light", 0.75),
                               ("super_light", 0.1), ("normal", 2)):
            self.change_json(SABLE_DATA / "physics_block_properties" / f"{category}.json",
                             lambda data, mass=mass: data["properties"].update({"sable:mass": mass}))
        self.change_json(PLATING_PROPERTIES,
                         lambda data: data["properties"].update({"sable:mass": 0.2}))
        result = self.compare()
        expected = (136 + 18) * 3 + (19 + 2) * 0.75 + 4 * 0.1 + 4 * 2
        self.assertAlmostEqual(result["baseline_dry_mass"], expected)
        self.assertAlmostEqual(result["mass_model"]["dry_mass_reduction"], 136 * (3 - 0.2))
        self.assertAlmostEqual(result["light_dry_mass"], expected - 136 * (3 - 0.2))

    def test_replacement_count_comes_from_layout_and_unknown_blocks_fail(self):
        hull = layout()
        position = next(pos for pos, state in hull.items() if state == "minecraft:cobblestone")
        del hull[position]
        with patch("upgrade_comparison.layout", return_value=hull):
            result = self.compare()
        self.assertEqual(result["mass_model"]["replacement_count"], 135)
        self.assertEqual(result["baseline_dry_mass"], 321.5)
        self.assertEqual(result["light_dry_mass"], 119)
        hull[position] = "unknown:unmodelled_hull"
        with patch("upgrade_comparison.layout", return_value=hull), self.assertRaises(ValueError):
            self.compare()
        hull = {pos: "minecraft:oak_planks" if state == "minecraft:cobblestone" else state
                for pos, state in layout().items()}
        with patch("upgrade_comparison.layout", return_value=hull), self.assertRaises(ValueError):
            self.compare()

    def test_equal_volume_formulas_with_distinct_live_fuel_densities(self):
        for fuel, impulse, mass in (("diesel", 100, 500), ("hydrogen", 300, 200)):
            self.change_json(FUEL_DIR / f"{fuel}.json",
                             lambda data, impulse=impulse, mass=mass:
                             data.update(impulse=impulse, mass=mass))
        (self.root / THRUST_SOURCE).write_text("static final double THRUST = 10000;")
        result = self.compare(baseline_dry_mass=1000, fuel_mb=100, cargo_mass=20,
                              gravity=10, reserve_fraction=0.25)
        self.assertEqual(result["light_dry_mass"], 796)
        self.assertIn("caller-supplied measured", result["baseline_dry_mass_source"])
        for key, dry, impulse, density in (("diesel_baseline", 1000, 100, 0.5),
                                          ("diesel_light", 796, 100, 0.5),
                                          ("hydrogen_light", 796, 300, 0.2)):
            with self.subTest(variant=key):
                row = result["variants"][key]
                wet = dry + 20 + 100 * density
                final = dry + 20 + 25 * density
                dv = impulse / density * math.log(wet / final)
                self.assertEqual(row["fuel_mb"], 100)
                self.assertEqual(row["reserved_fuel_mb"], 25)
                self.assertEqual(row["cargo_mass"], 20)
                self.assertEqual(row["total_mass"], wet)
                self.assertEqual(row["mass_after_budget_burn"], final)
                self.assertAlmostEqual(row["thrust_to_weight_ratio"], 20000 / (wet * 10))
                self.assertAlmostEqual(row["initial_hover_consumption_mb_s"], wet * 10 / impulse)
                self.assertAlmostEqual(row["theoretical_space_delta_v"], dv)
                self.assertAlmostEqual(row["ideal_hover_time_s"], dv / 10)
        self.assertEqual(result["shared_inputs"], {"effective_gravity": 10, "fuel_mb": 100,
                         "cargo_mass": 20, "reserve_fraction": 0.25, "lift_nozzles": 2})

    def test_current_fuel_data_gives_equal_light_wet_mass_and_double_hydrogen_budget(self):
        result = self.compare()["variants"]
        base, light, hydrogen = (result[key] for key in
                                  ("diesel_baseline", "diesel_light", "hydrogen_light"))
        self.assertEqual(base["total_mass"] - light["total_mass"], 204)
        self.assertGreater(light["thrust_to_weight_ratio"], base["thrust_to_weight_ratio"])
        self.assertEqual(light["total_mass"], hydrogen["total_mass"])
        self.assertEqual(light["thrust_to_weight_ratio"], hydrogen["thrust_to_weight_ratio"])
        self.assertAlmostEqual(hydrogen["ideal_hover_time_s"], 2 * light["ideal_hover_time_s"])
        self.assertAlmostEqual(hydrogen["theoretical_space_delta_v"],
                               2 * light["theoretical_space_delta_v"])
        for row in result.values():
            impulse, mass, thrust = load_parameters(row["fuel"], self.root)
            self.assertEqual(row["fuel_impulse_per_mb"], impulse)
            self.assertEqual(row["stored_fuel_mass"], 4 * mass)
            self.assertEqual(row["total_lift"], 2 * thrust)

    def test_zero_fuel_zero_mass_fuel_and_overweight_reports(self):
        for row in self.compare(fuel_mb=0)["variants"].values():
            self.assertEqual(row["theoretical_space_delta_v"], 0)
            self.assertEqual(row["ideal_hover_time_s"], 0)
        for fuel in ("diesel", "hydrogen"):
            self.change_json(FUEL_DIR / f"{fuel}.json", lambda data: data.update(mass=0))
        for row in self.compare(fuel_mb=100)["variants"].values():
            expected = row["fuel_impulse_per_mb"] * 50 / row["dry_mass"]
            self.assertAlmostEqual(row["theoretical_space_delta_v"], expected)
        for row in self.compare(lift_nozzles=0)["variants"].values():
            self.assertTrue(row["overweight"])
            self.assertIsNone(row["ideal_hover_time_s"])
            self.assertGreater(row["theoretical_space_delta_v"], 0)
            self.assertIn("cannot hover", row["lift_report"])

    def test_invalid_inputs_and_impossible_dry_mass(self):
        for key in ("gravity", "fuel_mb", "cargo_mass", "reserve_fraction", "baseline_dry_mass"):
            for value in (-1, float("nan"), float("inf"), -float("inf"), True, "1"):
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    self.compare(**{key: value})
        for key in ("gravity", "fuel_mb", "cargo_mass", "reserve_fraction", "lift_nozzles"):
            with self.subTest(missing=key), self.assertRaises(TypeError):
                args = dict(gravity=10, fuel_mb=1000, cargo_mass=0, reserve_fraction=0.5,
                            lift_nozzles=2)
                del args[key]
                calculate_comparison(**args)
        for value in (-1, 1.5, 1.0, True, float("nan"), float("inf")):
            with self.subTest(nozzles=value), self.assertRaises(ValueError):
                self.compare(lift_nozzles=value)
        for changes in ({"gravity": 0}, {"reserve_fraction": 1}, {"reserve_fraction": 2},
                        {"baseline_dry_mass": 0}, {"baseline_dry_mass": 204},
                        {"baseline_dry_mass": 203.9}, {"cargo_mass": 1e308, "gravity": 1e308}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.compare(**changes)

    def test_invalid_mass_sources_fail_without_fallback(self):
        for path in (PLATING_PROPERTIES, SABLE_DATA / "physics_block_properties/heavy.json"):
            original = (self.root / path).read_text()
            for mass in (-1, float("nan"), float("inf"), True, "0.5", None):
                with self.subTest(path=path, mass=mass):
                    self.change_json(path, lambda data: data["properties"].update({"sable:mass": mass}))
                    with self.assertRaises(ValueError):
                        self.compare()
            for text in ("[]", "{}", "{broken", '{"selector":"wrong","properties":{"sable:mass":1}}'):
                (self.root / path).write_text(text)
                with self.subTest(path=path, text=text), self.assertRaises(ValueError):
                    self.compare()
            (self.root / path).unlink()
            with self.assertRaises(OSError):
                self.compare()
            (self.root / path).write_text(original)
        self.change_json(PLATING_PROPERTIES,
                         lambda data: data["properties"].update({"sable:mass": 3}))
        with self.assertRaises(ValueError):
            self.compare()  # replacement cannot honestly be called light

    def test_changed_or_missing_tag_membership_fails(self):
        for path in (CORE_HEAVY_TAG, SABLE_DATA / "tags/block/light.json",
                     SABLE_DATA / "tags/block/heavy.json", SABLE_DATA / "tags/block/super_light.json"):
            original = (self.root / path).read_text()
            for text in ('{"values":[]}', '{"values":null}'):
                (self.root / path).write_text(text)
                with self.subTest(path=path, text=text), self.assertRaises(ValueError):
                    self.compare()
            (self.root / path).write_text(original)


class UpgradeComparisonCliTest(unittest.TestCase):
    OPTIONS = {"--gravity": "9.81", "--fuel-mb": "4000", "--cargo-mass": "0",
               "--reserve-fraction": "0.5", "--lift-nozzles": "2"}

    def invoke(self, *, omit=None, **changes):
        options = {**self.OPTIONS, **changes}
        if omit:
            del options[omit]
        script = Path(__file__).with_name("upgrade_comparison.py").resolve()
        with tempfile.TemporaryDirectory() as cwd:
            return subprocess.run([sys.executable, "-B", str(script),
                                   *(arg for pair in options.items() for arg in pair)],
                                  cwd=cwd, env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
                                  capture_output=True, text=True, timeout=10, check=False)

    def test_cli_json_from_another_cwd_and_measured_override(self):
        for changes, expected in (({}, 323.5), ({"--baseline-dry-mass": "400"}, 400)):
            completed = self.invoke(**changes)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertEqual(completed.stderr, "")
            data = json.loads(completed.stdout)
            self.assertEqual(data["baseline_dry_mass"], expected)
            self.assertEqual(data["light_dry_mass"], expected - 204)
            self.assertEqual(set(data["variants"]),
                             {"diesel_baseline", "diesel_light", "hydrogen_light"})
            self.assertEqual(data["shared_inputs"]["fuel_mb"], 4000)
            self.assertIn("No runtime or cargo-mass qualification", data["estimate_scope"])

    def test_cli_requires_every_comparison_input(self):
        for option in self.OPTIONS:
            with self.subTest(option=option):
                completed = self.invoke(omit=option)
                self.assertEqual(completed.returncode, 2)
                self.assertEqual(completed.stdout, "")
                self.assertIn("required", completed.stderr)
                self.assertNotIn("Traceback", completed.stderr)

    def test_cli_rejects_invalid_values_cleanly(self):
        for option, invalids in (("--gravity", ("nan", "inf", "0", "-1")),
                                 ("--fuel-mb", ("nan", "-1")),
                                 ("--cargo-mass", ("inf", "-1")),
                                 ("--reserve-fraction", ("nan", "1", "-1")),
                                 ("--lift-nozzles", ("1.5", "-1")),
                                 ("--baseline-dry-mass", ("nan", "inf", "0", "204"))):
            for invalid in invalids:
                with self.subTest(option=option, value=invalid):
                    completed = self.invoke(**{option: invalid})
                    self.assertEqual(completed.returncode, 2)
                    self.assertEqual(completed.stdout, "")
                    self.assertIn("error:", completed.stderr)
                    self.assertNotIn("Traceback", completed.stderr)


if __name__ == "__main__":
    unittest.main()
