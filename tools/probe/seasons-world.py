#!/usr/bin/env python3
"""Generate an opt-in fresh Hearth preset, retaining the authored system and omitting its ship."""
import json
from pathlib import Path
root = Path(__file__).resolve().parents[2]
preset = json.loads((root / 'worlds/src/main/resources/data/starfold_worlds/worldgen/world_preset/experience.json').read_text())
out = root / 'probes/build/seasons-world'
files = {
    'data/starfold_monsoon_probe/worldgen/world_preset/hearth.json': preset,
    'data/minecraft/tags/worldgen/world_preset/normal.json': {'values': ['starfold_monsoon_probe:hearth']},
}
for name, value in files.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')
print(out)
