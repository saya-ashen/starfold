#!/usr/bin/env python3
"""Offline fuel/lift budget in Sable's consistent mass, force and time units.

Pure hover assumes constant effective gravity, aligned lift nozzles, continuous
throttle and no translation, turning or climbing. It is not flight certification.
Space delta-v is a separate ideal vacuum budget, not an orbit/fuel requirement.
"""
import argparse
import json
import math
from pathlib import Path
import re


REPO_ROOT = Path(__file__).resolve().parents[2]
FUEL_DIR = Path("modpack/kubejs/data/escape_velocity/starfold/thruster_fuel")
THRUST_SOURCE = Path("src/main/java/dev/starfold/ship/Thrusters.java")
FUELS = ("diesel", "gasoline", "hydrogen")


def finite_number(name, value, *, positive=False):
    """Reject booleans, strings, non-finite values and invalid signs."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{name} must be a finite number")
    try:
        value = float(value)
    except OverflowError as exc:
        raise ValueError(f"{name} is outside the supported numeric range") from exc
    if not math.isfinite(value) or value < 0 or (positive and value == 0):
        bound = "positive" if positive else "nonnegative"
        raise ValueError(f"{name} must be finite and {bound}")
    return value


def load_parameters(fuel, repo_root=REPO_ROOT, *, allowed_fuels=FUELS):
    """Read live data and literal THRUST; default to ordinary fuels.

    Callers with a broader local fuel policy can supply an explicit allowlist
    without changing the hover calculator, CLI or shared FUELS collection.
    Never evaluate Java.
    """
    if fuel not in allowed_fuels:
        raise ValueError(f"fuel must be one of {', '.join(allowed_fuels)}")
    root = Path(repo_root)
    data = json.loads((root / FUEL_DIR / f"{fuel}.json").read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("fuel definition must be a JSON object")
    impulse = finite_number("fuel impulse", data.get("impulse"), positive=True)
    mass = finite_number("fuel mass per 1000 mB", data.get("mass", 0))
    source = (root / THRUST_SOURCE).read_text(encoding="utf-8")
    source = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
    declarations = re.findall(r"\bstatic\s+final\s+double\s+([^;]+);", source)
    values = [match.group(1) for declaration in declarations
              for match in re.finditer(r"(?:^|,)\s*THRUST\s*=\s*([^,]+)", declaration)]
    literal = r"[+-]?(?:\d[\d_]*(?:\.[\d_]*)?|\.[\d_]+)(?:[eE][+-]?\d[\d_]*)?[dDfF]?"
    if len(values) != 1 or not re.fullmatch(literal, values[0].strip()):
        raise ValueError("expected exactly one numeric literal THRUST in Thrusters.java")
    thrust = finite_number("THRUST", float(values[0].strip().rstrip("dDfF").replace("_", "")),
                           positive=True)
    return impulse, mass, thrust


def calculate_budget(*, dry_mass, fuel_mb, fuel, lift_nozzles, gravity,
                     cargo_mass=0, reserve_fraction=0.5, repo_root=REPO_ROOT):
    """Calculate independent hover and space budgets using non-reserved fuel.

    Let rho = fuel mass/mB, I = impulse/mB, B = burnable mB,
    Mf = dry + cargo + reserved fuel mass, M0 = Mf + rho*B.
    Hover: dB/dt = -g*M/I, so t = I/(rho*g)*ln(M0/Mf).
    Space: dv = I/rho*ln(M0/Mf). For rho=0 these have the finite
    limits I*B/(g*M0) and I*B/M0. log1p keeps small mass changes stable.
    """
    dry_mass = finite_number("dry mass", dry_mass, positive=True)
    cargo_mass = finite_number("cargo mass", cargo_mass)
    fuel_mb = finite_number("fuel mB", fuel_mb)
    gravity = finite_number("effective gravity", gravity, positive=True)
    reserve_fraction = finite_number("reserve fraction", reserve_fraction)
    if reserve_fraction >= 1:
        raise ValueError("reserve fraction must be in [0, 1)")
    finite_number("lift nozzles", lift_nozzles)
    if isinstance(lift_nozzles, bool) or not isinstance(lift_nozzles, int):
        raise ValueError("lift nozzles must be a nonnegative integer")
    impulse, mass_per_bucket, nozzle_force = load_parameters(fuel, repo_root)
    density = mass_per_bucket / 1000
    fuel_mass = finite_number("stored fuel mass", fuel_mb * density)
    reserved_mb = fuel_mb * reserve_fraction
    burnable_mb = fuel_mb * (1 - reserve_fraction)
    final_mass = finite_number("final mass", dry_mass + cargo_mass + reserved_mb * density,
                               positive=True)
    total_mass = finite_number("total mass", dry_mass + cargo_mass + fuel_mass, positive=True)
    total_lift = finite_number("total lift", nozzle_force * lift_nozzles)
    weight = finite_number("weight", total_mass * gravity, positive=True)
    twr = finite_number("thrust-to-weight ratio", total_lift / weight)
    hover_rate = finite_number("initial hover consumption", weight / impulse, positive=True)
    burned_mass = finite_number("burned mass", burnable_mb * density)
    ratio = finite_number("burned-to-final mass ratio", burned_mass / final_mass)
    # This expression also handles zero-mass fuel without division by density.
    correction = math.log1p(ratio) / ratio if ratio else 1.0
    delta_v = finite_number("space delta-v", (burnable_mb / final_mass) * impulse * correction)
    duration = finite_number("ideal hover duration", delta_v / gravity)
    overweight = weight > total_lift
    return {
        "fuel": fuel,
        "dry_mass": dry_mass,
        "cargo_mass": cargo_mass,
        "fuel_mb": fuel_mb,
        "fuel_impulse_per_mb": impulse,
        "fuel_mass_per_1000mb": mass_per_bucket,
        "stored_fuel_mass": fuel_mass,
        "total_mass": total_mass,
        "mass_after_budget_burn": final_mass,
        "reserve_fraction": reserve_fraction,
        "reserved_fuel_mb": reserved_mb,
        "burnable_fuel_mb": burnable_mb,
        "lift_nozzles": lift_nozzles,
        "full_power_force_per_nozzle": nozzle_force,
        "total_lift": total_lift,
        "effective_gravity": gravity,
        "weight": weight,
        "thrust_to_weight_ratio": twr,
        "initial_hover_consumption_mb_s": hover_rate,
        "ideal_hover_time_s": None if overweight else duration,
        "theoretical_space_delta_v": delta_v,
        "overweight": overweight,
        "lift_shortfall": max(0.0, weight - total_lift),
        "lift_report": ("overweight: full-power lift is below initial weight; cannot hover"
                        if overweight else "full-power lift meets initial static weight"),
        "estimate_scope": (
            "Pure hover only: constant explicitly supplied effective gravity, aligned "
            "unboosted full-power lift capacity and ideal continuous throttle; mass "
            "decreases during burn. No translation, turning or climbing. Hover and "
            "space delta-v are alternative uses of the same non-reserved fuel. "
            "Space delta-v uses the ideal vacuum rocket equation (constant-mass limit "
            "for zero-mass fuel), without drag, gravity losses or fuel eligibility "
            "checks. No flight certification or orbit fuel requirement. Mass and "
            "force use the game's Sable units; time is seconds."
        ),
        "sources": {"fuel": str(FUEL_DIR / f"{fuel}.json"), "thrust": str(THRUST_SOURCE)},
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-mass", type=float, required=True,
                        help="dry ship mass including tanks, excluding stored fuel and cargo")
    parser.add_argument("--cargo-mass", type=float, default=0)
    parser.add_argument("--fuel-mb", type=float, required=True)
    parser.add_argument("--fuel", choices=FUELS, required=True)
    parser.add_argument("--lift-nozzles", type=int, required=True,
                        help="number of aligned, available, unboosted lift nozzles")
    parser.add_argument("--gravity", type=float, required=True,
                        help="actual effective gravity in consistent game units; no default")
    parser.add_argument("--reserve-fraction", type=float, default=0.5)
    args = parser.parse_args(argv)
    try:
        result = calculate_budget(**vars(args))
        output = json.dumps(result, indent=2, allow_nan=False)
    except (OSError, ValueError, OverflowError) as exc:
        parser.error(str(exc))
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
