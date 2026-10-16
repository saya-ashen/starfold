# Celestial numerical baseline

`C1-baseline.tsv` was captured from unchanged production code at
`dd02e67780730ad7487664a960e62b12ba99c39d` and committed before production changes
in `11aafb5`. It has never been regenerated.

`dev.starfold.planet.CelestialBaselineTest` has no update mode. It requires the
exact key set and array lengths and compares every literal using
`1e-9 * max(abs(expected), 1e-12)` (absolute tolerance `1e-21` at zero).

## Retained contracts

The resource now has **688 rows / 3,025 scalars**:

- `bounds`: surface count, extent, drift, turn;
- `frame/<slot>/<tick>`: centre xyz, quaternion xyzw, velocity xyz, angular xyz
  (165 complete Frames);
- `least/<slot>/<point>`: lower distance bound (396 queries);
- `star/<point>`: nearest light xyz and radius (96 queries);
- `stars` and `star-list/<index>`: ordered lights (15 spheres).

Ticks are 0, 1, 12345, 1,000,000 and 999,999,999,999. The twelve fixed points
include a midpoint nearest-light tie. Configurations are solar defaults and its
spinning variant; groups `[2]`, `[3]`, `[2,3]`, `[4,1,2]`; authored
`twin_lanterns` materialized with seed 42; and a `[2,3]` home with two discovered
suns (seed 20261004, default endless layout, explicit visit order in the test).
The discovered model protects parent remapping after a home with additional
planets and a second sun.

## Authorized deletions

C1b drops the sunless model. Every row under `defaults/` and
`defaults-spinning/` was deleted: **98 rows / 422 scalars** (49 rows each).
The lit two-planet path remains pinned by groups `[2]`.

The one-surface solar model no longer contains a phantom companion. Only these
rows were deleted for that reason: **34 rows / 154 scalars** (17 rows each):

- `solar/frame/1/{0,1,12345,1000000,999999999999}`;
- `solar-spinning/frame/1/{0,1,12345,1000000,999999999999}`;
- `solar/least/1/{0..11}`;
- `solar-spinning/least/1/{0..11}`.

Every other original line, including comments, is byte-for-byte unchanged.
