#!/usr/bin/env python3
"""Offline B6-F hull/fuel comparison at the SAME supplied fuel volume (mB).

Required inputs: effective gravity, total stored fuel mB, modelled cargo mass,
reserve fraction and available aligned lift count. No actual inventory payload
mass is inferred. No runtime, cargo-mass or flight qualification is established.

The reference dry mass is summed from first_ship.layout() and local physics
JSON, using the limited category/solidity assumptions reported in the output.
An optional measured --baseline-dry-mass replaces that reference estimate.
"""
import argparse
from collections import Counter
import json
import math
from pathlib import Path

from first_ship import layout
from flight_budget import REPO_ROOT, calculate_budget, finite_number


SABLE_DATA = Path("research/sable/common/src/main/resources/data/sable")
CORE_HEAVY_TAG = Path("src/main/resources/data/sable/tags/block/heavy.json")
PLATING = "starfold_materials:aluminum_hull_plating"
PLATING_PROPERTIES = Path(
    "materials/src/main/resources/data/starfold_materials/physics_block_properties/"
    "aluminum_hull_plating.json")
COBBLESTONE = "minecraft:cobblestone"

MASS_ASSUMPTIONS = (
    "Reference layout only, not measured assembled mass. Read local Sable physics "
    "JSON and the core heavy tag on each call; this is a limited B6-F model, not "
    "a runtime datapack/tag/shape resolver. Cobblestone uses #sable:heavy through "
    "#c:cobblestones; oak planks and barrels use #sable:light; both placed halves "
    "of each oak door use #sable:super_light. Core ship components must be explicit "
    "members of the current core heavy tag. Empty Create fluid tanks use the "
    "normal/default category. Levers are assumed non-solid and contribute zero "
    "mass, matching Sable PhysicsBlockPropertyHelper.getMass's non-solid rule. "
    "Other listed blocks, including door halves, are assumed solid; full blocks "
    "use base properties, without slab/double-state overrides. Unknown layout "
    "blocks fail instead of receiving a guessed mass. No external pack overrides, "
    "entities, contraptions, contents or fuel are included in reference dry mass."
)
ESTIMATE_SCOPE = (
    "Offline ideal comparison at the SAME total fuel volume (mB), supplied cargo "
    "mass, reserve fraction, effective gravity and aligned unboosted lift count. "
    "Only every cobblestone in first_ship.layout() is replaced, one for one, with "
    "full aluminum hull plating; all other layout cells are retained. Cargo mass "
    "is an explicit hypothetical physical mass in Sable units, not inferred from "
    "inventory items. No runtime or cargo-mass qualification; no flight "
    "certification. No handling, stability, tank capacity, fuel eligibility or "
    "structural qualification. Hover and ideal vacuum delta-v are alternative "
    "uses of the same non-reserved fuel, not additive mission budgets. Each "
    "variant's estimate_scope states the flight_budget assumptions."
)


def _object(root, path):
    data = json.loads((root / path).read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return data


def _property_mass(root, path, selector):
    data = _object(root, path)
    properties = data.get("properties")
    if data.get("selector") != selector or not isinstance(properties, dict):
        raise ValueError(f"{path} must define mass for selector {selector}")
    return finite_number(f"{path} sable:mass", properties.get("sable:mass"))


def _tag_values(root, path):
    values = _object(root, path).get("values")
    if not isinstance(values, list):
        raise ValueError(f"{path} must contain a tag values list")
    return values


def load_mass_model(repo_root=REPO_ROOT):
    """Sum placed blocks, including BOTH door halves; never sum item counts.

    Category membership is deliberately limited to the known B6-F blocks. The
    directly used local tag entries are checked; vanilla/common tag expansion
    and block solidity are explicit assumptions, not a simulated game registry.
    """
    root = Path(repo_root)
    categories = {}
    sources = {"core_heavy_tag": str(CORE_HEAVY_TAG),
               "plating": str(PLATING_PROPERTIES), "layout": "tools/ship/first_ship.py"}
    for category in ("heavy", "light", "super_light", "normal"):
        path = SABLE_DATA / "physics_block_properties" / f"{category}.json"
        categories[category] = _property_mass(root, path, f"#sable:{category}")
        sources[category] = str(path)
    for category, required in (("heavy", ("#c:cobblestones",)),
                               ("light", ("#minecraft:planks", "minecraft:barrel")),
                               ("super_light", ("#minecraft:doors",))):
        path = SABLE_DATA / "tags/block" / f"{category}.json"
        values = _tag_values(root, path)
        if any(entry not in values for entry in required):
            raise ValueError(f"{path} no longer supports the reference category assumptions")
        sources[f"{category}_tag"] = str(path)
    core_heavy = _tag_values(root, CORE_HEAVY_TAG)
    plating_mass = _property_mass(root, PLATING_PROPERTIES, PLATING)
    counts = Counter(state.split("[", 1)[0] for state in layout().values())
    if not counts[COBBLESTONE]:
        raise ValueError("B6-F layout contains no cobblestone to replace")
    known = {COBBLESTONE: "heavy", "minecraft:oak_planks": "light",
             "minecraft:barrel": "light", "minecraft:oak_door": "super_light",
             "create:fluid_tank": "normal", "minecraft:lever": "non_solid"}
    rows = []
    for block, count in sorted(counts.items()):
        if block.startswith("starfold:") and block in core_heavy:
            category = "heavy"
        elif block in known:
            category = known[block]
        else:
            raise ValueError(f"unsupported B6-F mass category for {block}")
        mass = 0.0 if category == "non_solid" else categories[category]
        rows.append({"block": block, "placed_count": count, "category": category,
                     "mass_per_block": mass,
                     "subtotal": finite_number(f"{block} subtotal", count * mass)})
    baseline_mass = finite_number("reference baseline dry mass",
                                  math.fsum(row["subtotal"] for row in rows), positive=True)
    reduction = counts[COBBLESTONE] * (categories["heavy"] - plating_mass)
    # A future balance edit could make this heavier. Do not silently compare a
    # heavier design while labelling it the light variant.
    reduction = finite_number("hull mass reduction", reduction)
    return {"reference_baseline_dry_mass": baseline_mass,
            "replacement_count": counts[COBBLESTONE],
            "original_block": COBBLESTONE, "replacement_block": PLATING,
            "cobblestone_mass_per_block": categories["heavy"],
            "plating_mass_per_block": plating_mass, "dry_mass_reduction": reduction,
            "placed_blocks": sum(counts.values()), "block_masses": rows,
            "assumptions": MASS_ASSUMPTIONS, "sources": sources}


def calculate_comparison(*, gravity, fuel_mb, cargo_mass, reserve_fraction,
                         lift_nozzles, baseline_dry_mass=None, repo_root=REPO_ROOT):
    """Reuse flight_budget for all three cases, retaining reserve as wet mass."""
    model = load_mass_model(repo_root)
    baseline_mass = (model["reference_baseline_dry_mass"] if baseline_dry_mass is None
                     else finite_number("baseline dry mass", baseline_dry_mass, positive=True))
    if baseline_mass <= model["dry_mass_reduction"]:
        raise ValueError("baseline dry mass must exceed the hull mass reduction")
    light_mass = finite_number("light dry mass", baseline_mass - model["dry_mass_reduction"],
                               positive=True)
    shared = dict(gravity=gravity, fuel_mb=fuel_mb, cargo_mass=cargo_mass,
                  reserve_fraction=reserve_fraction, lift_nozzles=lift_nozzles)
    variants = {}
    for name, fuel, mass in (("diesel_baseline", "diesel", baseline_mass),
                             ("diesel_light", "diesel", light_mass),
                             ("hydrogen_light", "hydrogen", light_mass)):
        variants[name] = calculate_budget(dry_mass=mass, fuel=fuel, repo_root=repo_root, **shared)
    baseline = variants["diesel_baseline"]
    return {"design": "B6-F", "mass_model": model,
            "baseline_dry_mass": baseline_mass, "light_dry_mass": light_mass,
            "baseline_dry_mass_source": ("source-derived reference estimate"
                                         if baseline_dry_mass is None
                                         else "caller-supplied measured dry mass"),
            "shared_inputs": {key: baseline[key] for key in
                              ("effective_gravity", "fuel_mb", "cargo_mass",
                               "reserve_fraction", "lift_nozzles")},
            "variants": variants, "estimate_scope": ESTIMATE_SCOPE}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gravity", type=float, required=True,
                        help="explicit effective gravity in consistent Sable units")
    parser.add_argument("--fuel-mb", type=float, required=True,
                        help="total stored mB, identical in every variant")
    parser.add_argument("--cargo-mass", type=float, required=True,
                        help="hypothetical physical cargo mass; explicitly use 0 for none")
    parser.add_argument("--reserve-fraction", type=float, required=True)
    parser.add_argument("--lift-nozzles", type=int, required=True,
                        help="available aligned, unboosted lift count")
    parser.add_argument("--baseline-dry-mass", type=float,
                        help="measured baseline mass including empty tanks, excluding fuel/cargo; "
                             "otherwise use the documented reference model")
    args = parser.parse_args(argv)
    try:
        result = calculate_comparison(**vars(args))
        output = json.dumps(result, indent=2, allow_nan=False)
    except (OSError, ValueError, OverflowError) as exc:
        parser.error(str(exc))
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
