#!/usr/bin/env python3
"""Offline ideal multi-leg fuel estimate; no flight or round-trip certification.

Run: python3 tools/ship/round_trip_budget.py --plan plan.json
The JSON object requires fuel (diesel/gasoline/hydrogen/deuterium), dry_mass (>0),
initial_fuel_mb (>=0), reserve_mb (0..initial_fuel_mb), and a nonempty legs list.
Optional root drive_installed is a boolean (default false). Each leg needs
an explicit unique nonblank name and delta_v (>=0). Optional per-leg
outside_home_system is a boolean (default false): positive delta_v there
requires drive_installed=true AND the local fuel JSON's interstellar=true.
Zero delta_v coasts skip this gate; optional static TWR still applies.
One fuel is stored per plan; all stored fuel, including reserve and ineligible
ordinary fuel, counts toward wet mass. Dry mass must include installed drive
hardware; drive_installed adds no inferred mass. Optional added_mass (>=0)
is loaded BEFORE that leg and persists. It means supported physical mass in
Sable units, not an inventory item count. Optional effective_gravity (>0) and
lift_nozzles (integer >=0) must appear together to check departure wet TWR.

Delta-v is caller-supplied ideal propulsion demand in consistent game units;
include gravity losses and other allowances yourself. No route is inferred.
Output includes drive_installed and fuel_interstellar. Each evaluated leg reports
outside_home_system, propulsion_required (delta_v > 0), eligibility_checked
(outside AND propulsion_required), and propulsion_eligible (home OR drive AND
interstellar fuel). A coast may complete with propulsion_eligible=false; it
requests no propulsion. Feasible means only feasible within this ideal model.
Exit codes: 0 feasible within this model, 1 infeasible, 2 invalid input/source.
"""
import argparse
import json
import math
from pathlib import Path

from flight_budget import (FUEL_DIR, FUELS, REPO_ROOT, THRUST_SOURCE,
                           finite_number, load_parameters)


ESTIMATE_SCOPE = (
    "Offline ideal estimate only; no flight or round-trip certification. "
    "Each supplied delta_v is ideal propulsion demand: the caller must supply "
    "gravity losses, drag, steering, braking, landing and other allowances. "
    "Fuel eligibility checks only declared outside_home_system legs with positive "
    "delta_v: both declared drive_installed and local fuel interstellar=true "
    "are required. These booleans do not verify installed hardware, location, "
    "tank access or runtime capability. Zero delta_v coasts skip the gate. "
    "No route, orbit, transfer or burn time is verified. One fuel is stored per "
    "plan; all stored fuel counts as mass even when ineligible. Dry mass must "
    "include drive hardware; no hardware mass is inferred from the boolean. "
    "Fuel uses the ideal rocket equation with changing wet mass "
    "and a constant-mass limit for zero-mass fuel. Reserve stays aboard as mass "
    "and is never burnable. Added mass means supported physical mass, not an "
    "arbitrary inventory count, and persists from its specified departure. "
    "Optional TWR checks only departure static weight at explicitly supplied "
    "constant effective gravity with aligned, available, unboosted full-power "
    "lift nozzles; TWR >= 1 meets that static check only. Omitted TWR inputs "
    "mean lift was not checked. Mass, force and delta-v use consistent game "
    "Sable units; fuel amounts use mB. Evaluation stops before the first "
    "infeasible burn; subsequent legs are not evaluated."
)


# Deliberately local: upgrade comparisons and hover budgets use ordinary FUELS.
ROUND_TRIP_FUELS = (*FUELS, "deuterium")


def _boolean(label, value):
    if not isinstance(value, bool):
        raise ValueError(f"{label} must be a boolean")
    return value


def _load_fuel_parameters(fuel, repo_root):
    """Reuse shared numeric/thrust validation; read eligibility from local data."""
    impulse, mass, thrust = load_parameters(
        fuel, repo_root, allowed_fuels=ROUND_TRIP_FUELS)
    data = json.loads((Path(repo_root) / FUEL_DIR / f"{fuel}.json").read_text(
        encoding="utf-8"), object_pairs_hook=_unique_object, parse_constant=_reject_constant)
    interstellar = _boolean("fuel interstellar", data.get("interstellar", False))
    return impulse, mass, thrust, interstellar


def _fields(value, label, required, optional=()):
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be a JSON object")
    missing = set(required) - value.keys()
    unknown = value.keys() - set(required) - set(optional)
    if missing:
        raise ValueError(f"{label} missing fields: {', '.join(sorted(missing))}")
    if unknown:
        raise ValueError(f"{label} unknown fields: {', '.join(sorted(map(str, unknown)))}")


def _validate_plan(plan):
    _fields(plan, "plan", ("fuel", "dry_mass", "initial_fuel_mb", "reserve_mb", "legs"),
            ("drive_installed",))
    if plan["fuel"] not in ROUND_TRIP_FUELS:
        raise ValueError(f"fuel must be one of {', '.join(ROUND_TRIP_FUELS)}")
    _boolean("drive_installed", plan.get("drive_installed", False))
    dry_mass = finite_number("dry_mass", plan["dry_mass"], positive=True)
    initial = finite_number("initial_fuel_mb", plan["initial_fuel_mb"])
    reserve = finite_number("reserve_mb", plan["reserve_mb"])
    if reserve > initial:
        raise ValueError("reserve_mb must not exceed initial_fuel_mb")
    if not isinstance(plan["legs"], list) or not plan["legs"]:
        raise ValueError("legs must be a nonempty list")
    legs = []
    names = set()
    for index, leg in enumerate(plan["legs"]):
        label = f"legs[{index}]"
        _fields(leg, label, ("name", "delta_v"),
                ("added_mass", "effective_gravity", "lift_nozzles", "outside_home_system"))
        name = leg["name"]
        if not isinstance(name, str) or not name.strip():
            raise ValueError(f"{label}.name must be a nonempty, nonblank string")
        if name.strip() in names:
            raise ValueError(f"duplicate leg name: {name.strip()}")
        names.add(name.strip())
        normalized = {
            "name": name,
            "delta_v": finite_number(f"{label}.delta_v", leg["delta_v"]),
            "added_mass": finite_number(f"{label}.added_mass", leg.get("added_mass", 0)),
            "outside_home_system": _boolean(
                f"{label}.outside_home_system", leg.get("outside_home_system", False)),
        }
        if ("effective_gravity" in leg) != ("lift_nozzles" in leg):
            raise ValueError(f"{label} requires effective_gravity and lift_nozzles together")
        if "effective_gravity" in leg:
            normalized["effective_gravity"] = finite_number(
                f"{label}.effective_gravity", leg["effective_gravity"], positive=True)
            nozzles = leg["lift_nozzles"]
            finite_number(f"{label}.lift_nozzles", nozzles)
            if isinstance(nozzles, bool) or not isinstance(nozzles, int):
                raise ValueError(f"{label}.lift_nozzles must be a nonnegative integer")
            normalized["lift_nozzles"] = nozzles
        legs.append(normalized)
    return dry_mass, initial, reserve, legs


def _required_burn(total_mass, delta_v, density, impulse):
    """Invert dv = I/rho * log(M0/Mf), retaining the rho=0 limit."""
    scaled_dv = finite_number("delta_v / fuel impulse", delta_v / impulse)
    exponent = finite_number("rocket equation exponent", scaled_dv * density)
    if exponent <= 1:
        # Algebraically M0 * -expm1(-dv*rho/I) / rho. This form also
        # retains the limit when rho or the exponent underflows to zero.
        correction = -math.expm1(-exponent) / exponent if exponent else 1.0
        burn = total_mass * (scaled_dv * correction)
    else:
        burn = (total_mass * -math.expm1(-exponent)) / density
    return finite_number("required fuel burn", burn)


def _available_delta_v(nonfuel_mass, available, reserve, density, impulse):
    # Use the retained mass explicitly: inverse burns approach M0/rho and
    # can round to all available fuel even when the requested dv is too high.
    retained_mass = finite_number("retained mass", nonfuel_mass + reserve * density,
                                  positive=True)
    burned_mass = finite_number("burnable fuel mass", available * density)
    ratio = finite_number("burnable-to-retained mass ratio", burned_mass / retained_mass)
    correction = math.log1p(ratio) / ratio if ratio else 1.0
    return finite_number("available delta_v", (available / retained_mass) * impulse * correction)


def calculate_round_trip(plan, repo_root=REPO_ROOT):
    """Validate the whole plan, then evaluate sequentially without spending reserve.

    Malformed plans (including reserve above initial fuel) raise ValueError.
    Valid but infeasible plans return feasible=false and retain fuel at the
    failed departure; they do not pretend that a partial burn completed a leg.
    Added mass on that departure is retained. Only evaluated legs have mass
    and burn reports; remaining_leg_names explicitly identifies later legs.
    """
    dry_mass, initial, reserve, legs = _validate_plan(plan)
    impulse, mass_per_bucket, nozzle_force, interstellar = _load_fuel_parameters(
        plan["fuel"], repo_root)
    drive_installed = plan.get("drive_installed", False)
    density = mass_per_bucket / 1000
    remaining = initial
    # Sum debits separately from the large reserved/stored amount. fsum keeps
    # sub-ULP burns from vanishing during repeated rounded fuel subtraction.
    fuel_balance = [initial - reserve]
    available = fuel_balance[0]
    nonfuel_mass = dry_mass
    reports = []
    reasons = []
    completed_legs = 0
    for leg in legs:
        nonfuel_mass = finite_number("non-fuel mass", nonfuel_mass + leg["added_mass"],
                                     positive=True)
        fuel_mass = finite_number("stored fuel mass", remaining * density)
        departure_mass = finite_number("departure total mass", nonfuel_mass + fuel_mass,
                                       positive=True)
        required = _required_burn(departure_mass, leg["delta_v"], density, impulse)
        available_dv = _available_delta_v(nonfuel_mass, available, reserve, density, impulse)
        leg_reasons = []
        propulsion_required = leg["delta_v"] > 0
        eligibility_checked = leg["outside_home_system"] and propulsion_required
        # Eligibility describes propulsion at this location even on a coast;
        # only an actual propulsion demand can make that gate fail a leg.
        propulsion_eligible = not leg["outside_home_system"] or (
            drive_installed and interstellar)
        if eligibility_checked:
            if not drive_installed:
                leg_reasons.append(f"{leg['name']}: outside-home-system propulsion "
                                   "requires drive_installed=true")
            if not interstellar:
                leg_reasons.append(f"{leg['name']}: outside-home-system propulsion "
                                   "requires fuel definition interstellar=true")
        # Apply roundoff tolerance in dv, not in fuel: near exhaustion a tiny
        # fuel discrepancy can represent a large shortfall in propulsion.
        enough_fuel = leg["delta_v"] <= available_dv or math.isclose(
            leg["delta_v"], available_dv, rel_tol=1e-14, abs_tol=0.0)
        if not enough_fuel:
            leg_reasons.append(f"{leg['name']}: insufficient non-reserved fuel "
                               f"(requires {required:g} mB, available {available:g} mB; "
                               f"requested delta_v {leg['delta_v']:g}, available {available_dv:g})")
        lift = weight = twr = overweight = None
        if "effective_gravity" in leg:
            lift = finite_number("total lift", nozzle_force * leg["lift_nozzles"])
            weight = finite_number("departure weight",
                                   departure_mass * leg["effective_gravity"], positive=True)
            twr = finite_number("departure thrust-to-weight ratio", lift / weight)
            overweight = weight > lift
            if overweight:
                leg_reasons.append(f"{leg['name']}: departure wet TWR below 1; "
                                   "full-power lift is below static weight")
        burn = min(required, available) if not leg_reasons else 0.0
        departure_fuel = remaining
        departure_available = available
        fuel_balance.append(-burn)
        available = max(0.0, math.fsum(fuel_balance))
        remaining = finite_number("remaining fuel", reserve + available)
        arrival_mass = finite_number("arrival total mass", nonfuel_mass + remaining * density,
                                     positive=True)
        reports.append({
            **leg,
            "propulsion_required": propulsion_required,
            "eligibility_checked": eligibility_checked,
            "propulsion_eligible": propulsion_eligible,
            "departure_nonfuel_mass": nonfuel_mass,
            "departure_fuel_mb": departure_fuel,
            "departure_total_mass": departure_mass,
            "burnable_fuel_mb": departure_available,
            "available_delta_v": available_dv,
            "required_burn_mb": required,
            "fuel_burn_mb": burn,
            "remaining_fuel_mb": remaining,
            "arrival_total_mass": arrival_mass,
            "total_lift": lift,
            "departure_weight": weight,
            "departure_thrust_to_weight_ratio": twr,
            "overweight": overweight,
            "completed": not leg_reasons,
            "reasons": leg_reasons,
        })
        if leg_reasons:
            reasons.extend(leg_reasons)
            break
        completed_legs += 1
    return {
        "feasible": not reasons,
        "reasons": reasons,
        "fuel": plan["fuel"],
        "drive_installed": drive_installed,
        "fuel_interstellar": interstellar,
        "dry_mass": dry_mass,
        "initial_fuel_mb": initial,
        "reserve_mb": reserve,
        "fuel_impulse_per_mb": impulse,
        "fuel_mass_per_1000mb": mass_per_bucket,
        "full_power_force_per_nozzle": nozzle_force,
        "total_burn_mb": math.fsum(-debit for debit in fuel_balance[1:]),
        "remaining_fuel_mb": remaining,
        "remaining_burnable_fuel_mb": available,
        "remaining_nonfuel_mass": nonfuel_mass,
        "remaining_total_mass": reports[-1]["arrival_total_mass"],
        "completed_legs": completed_legs,
        "legs": reports,
        "remaining_leg_names": [leg["name"] for leg in legs[len(reports):]],
        "estimate_scope": ESTIMATE_SCOPE,
        "sources": {"fuel": str(FUEL_DIR / f"{plan['fuel']}.json"),
                    "thrust": str(THRUST_SOURCE)},
    }


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON field: {key}")
        result[key] = value
    return result


def _reject_constant(value):
    raise ValueError(f"non-finite JSON number: {value}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path, required=True, help="path to a JSON plan")
    args = parser.parse_args(argv)
    try:
        plan = json.loads(args.plan.read_text(encoding="utf-8"),
                          object_pairs_hook=_unique_object, parse_constant=_reject_constant)
        result = calculate_round_trip(plan)
        output = json.dumps(result, indent=2, allow_nan=False)
    except (OSError, ValueError, OverflowError, RecursionError) as exc:
        parser.error(str(exc))
    print(output)
    return 0 if result["feasible"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
