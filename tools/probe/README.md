# Dev probe scripts

Scripted in-game checks for the development client (`-Dstarfold.probe=<script>`, see `DevProbe`).
Working copies live in `build/probe/` (ignored); these are the versioned originals.

- `dev.sh <script> [timeout]` restarts the dev server, runs one scripted client session, dumps `jstack` of anything still running, then stops the server.
- `gen_sim.py <out> [face-size origin-x origin-z]` builds the seam interaction fixture (redstone, pistons, water, light, mob chase) on an unrotated and a rotated face seam.
- `gen_loop.py 1|2|3 [a-chest x,y,z] [b-chest x,y,z]` builds the three sessions of the A→B→A loop; phase 3 needs the chest positions printed by phase 1.

Datapack acceptance helpers (inert outside a probe):
- `system 1234` logs the received celestial model at the current simulation tick and at tick 1234, including every sun, planet centre, name, size, depth and spin; it does not change time.
- `sunlook 0` aims the camera at the zero-based authored sun, on a ready chart or in personal flight; it does not move the player.
- `breaknear 1 -1 0` sends the normal client block-breaking action at an offset from the player's client-chart block position. Use a server `check` afterward; an air target is not proof of mining.
