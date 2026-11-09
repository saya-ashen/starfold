# Pelagia surface-height regression

`pelagia-20261005.bin.gz` contains the unchanged `TerrainPlan.write` payload from ev19 (`3e64f9f8`), gzip compressed. Source: performance investigation's newly created experience world `New World (1)`, `dimensions/starfold/native_planet/data/planet-plans/3.dat`; extracted from its `plan` entry after decoding. No structures, entities, user edits, or scatter policy are included.

The archived plan identity is `80688913a00754cf2272483fabac52d16209772cb15f1b388d6a0aeeea54032d`. Planet seed is `20261005`, size `1536`, depth `489`; the exact first failing column found by the offline scan is WEST / x=360 / z=449. Its unbounded planned height is `547.7527967034682`, generated ground is `318`, interpolated lake level is `531.9938768488355`; prior surface cap `531` read AIR because blocks stop at `319`. The regression retains the full fixed terrain so the original bicubic stencil, volcanic landforms, lake classification and seam topology remain reproducible.
