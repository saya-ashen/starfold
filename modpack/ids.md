# ID inventory

`ids.json` is the machine-readable inventory: item/block/fluid/entity/advancement/dimension
IDs mapped to exact resource paths, including nested bundled jars. Regenerate from
the repository root with `python3 tools/modpack/inventory.py --fetch --output modpack/ids.json`. Each final pinned jar is
downloaded only if absent, then verified against its packwiz hash on every run.
Downloads remain in `build/pi/jars/`, never Git or a pack export. CDN URLs are derived
from packwiz's CurseForge file IDs and original filenames (recorded in `ids.json`).
This is static resource evidence, not a runtime registry dump; IDs with only a lang
key are not accepted as item/block evidence without matching assets.

## Rule-critical IDs

| Purpose | Real IDs / evidence |
|---|---|
| Ship | `starfold:thruster`, `starfold:helm`, `starfold:gyro`, `starfold:scope`, `starfold:scope_nav`, `starfold:scope_precise`, `starfold:airstair`, `starfold:aircraft_dock`, `starfold:interstellar_drive` — local resources |
| Hull components | `starfold_materials:aluminum_hull_plating`, `starfold_materials:aluminum_hull_plating_slab`, `starfold_materials:osmium_pressure_hull`, `starfold_materials:osmium_pressure_hull_slab` — `materials/src/main/resources` |
| Aluminum | `starfold_materials:bauxite_ore`, `starfold_materials:deepslate_bauxite_ore`, `starfold_materials:raw_aluminum`, `starfold_materials:aluminum_ingot`, `starfold_materials:aluminum_nugget`, `starfold_materials:raw_aluminum_block`, `starfold_materials:aluminum_block` — local Materials resources |
| Common metal tags | `c:ingots/aluminum` (Materials), `c:ingots/osmium` (Mekanism), `c:ingots/iron`; hull recipes remain provider-independent |
| Assembly | `simulated:physics_assembler`, `simulated:gyroscopic_mechanism` — nested Simulated jar |
| Create | `create:precision_mechanism`, `create:brass_sheet`, `create:iron_sheet`, `create:electron_tube`, `create:blaze_burner`, `create:zinc_ore` |
| Fuel line | Items: `createdieselgenerators:oil_scanner`, `createdieselgenerators:pumpjack_bearing`, `createdieselgenerators:distillation_controller`. Block only: `createdieselgenerators:distillation_tank` (converted from placed `create:fluid_tank`; no separate inventory item). |
| Tier 1 fluids | `createdieselgenerators:diesel`, `createdieselgenerators:gasoline` — lang + fluid tags; buckets have `_bucket` suffix |
| Tier 2 fluid | **`mekanism:hydrogen`**, not a guessed `liquid_hydrogen`; `data/mekanism/recipe/rotary/hydrogen.json` explicitly outputs that fluid; bucket `mekanism:hydrogen_bucket` |
| Electric bridge | `createaddition:alternator`, `createaddition:connector`, `createaddition:copper_wire`, `createaddition:capacitor` |
| Mekanism gate | `mekanism:osmium_ore`, `mekanism:raw_osmium`, `mekanism:ingot_osmium`, `mekanism:basic_control_circuit`, `mekanism:advanced_control_circuit` |
| Frozen resources | `mekanism:tin_ore`, `mekanism:lead_ore` (+ `deepslate_` variants) |
| Crystal resources | `mekanism:fluorite_ore`, `mekanism:fluorite_gem`; vanilla quartz/amethyst |
| Dry resources | `mekanism:uranium_ore`, `mekanism:raw_uranium` |
| Tier 3 candidate | **`mekanismgenerators:deuterium`**, not `mekanism:deuterium` — fluid tag; bucket `mekanismgenerators:deuterium_bucket`. Enabled as the skimmed tier-3 fuel; heavy-water separation is removed, rotary conversion needs existing stock. |
| Host gate | Entity `starfold_biotech:host` (local entity lang key), item `starfold_biotech:host_ganglion` (lang + model); Biotech `loot_table/entities/host.json` yields four. Planet type `starfold_biotech:flesh` is appended to the experience system/universe. |
| Low flight | `immersive_aircraft:engine`, `immersive_aircraft:airship`, `immersive_aircraft:biplane`; Aeronautics propellers/envelopes from nested jars |
| Existing travel proof | `starfold:ship/lift_off`, `starfold:ship/space`, `starfold:ship/other_planet`, `starfold:ship/home`, `starfold:ship/return_space` — real local advancements granted by Starfold, not fabricated dimension IDs |
| Dimensions | `starfold:native_planet` plus vanilla dimensions — existing experience world preset. All planet classes and space share the same level. |

## Missing or deliberately unused

- `starfold:interstellar_drive` is a registered block/item in
  `starfold:interstellar_drives`. Outside the home system, thrust requires this
  hardware and interstellar deuterium in the pack; inertial coasting remains possible.
  Its provisional pack recipe uses four osmium pressure hull blocks, four elite
  circuits, a deuterium bucket and one Host Ganglion via Create mechanical crafting.
  The ganglion gates fabrication, not a separate runtime permission flag.
- Aluminum comes from Materials bauxite on desert planets only. The module adds no
  world generation. No additional metal provider is required.
- No solid oil-ore block exists in Diesel Generators: oil fields are not ordinary
  `minerals.ores` entries. `crude_oil` is a fluid block; Starfold rejects fluid ore blocks.
- No fluid is registered by Starfold; skimming uses Mekanism Generators' real deuterium fluid.
- No Atlas items are known on this branch. Atlas is a local jar placeholder only.
- Architectury, FTB Library/Teams/Quests, Rhino/KubeJS, Cloth Config, CERBON API,
  GeckoLib, JEI, Jade, ModernFix, FerriteCore and Better Advanced Tooltips need no
  item/block/fluid IDs for the authored progression; they supply infrastructure/UI.
- MezzConfig is also infrastructure only (JEI dependency); no quest registry IDs are used.
