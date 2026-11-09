"""Rebuild Biotech data, models and blockstates (standard library only)."""
import json
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1]
ROOT = MODULE / 'src/main/resources'
WORLDS = MODULE.parent / 'worlds/src/main/resources/data/starfold_worlds'
NS = 'starfold_biotech'


def write(path, value):
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


write(f'assets/{NS}/models/item/blood_bucket.json', {
    'parent': 'minecraft:item/generated', 'textures': {'layer0': f'{NS}:item/blood_bucket'}})
write(f'assets/{NS}/blockstates/blood.json', {'variants': {'': {'model': f'{NS}:block/blood'}}})
write(f'assets/{NS}/models/block/blood.json', {'textures': {'particle': f'{NS}:block/blood_still'}})
write(f'data/{NS}/loot_table/blocks/blood.json', {'type': 'minecraft:block', 'pools': []})

# Block and item textures come from tools/organic-textures.py; random rotations hide tiling on the shapeless ones.
blocks = ('chitin', 'hive_flesh', 'resin', 'pulse_sac')
for name in blocks:
    variant = {'model': f'{NS}:block/{name}'}
    variants = variant if name == 'chitin' else [dict(variant, y=y) if y else variant for y in (0, 90, 180, 270)]
    write(f'assets/{NS}/blockstates/{name}.json', {'variants': {'': variants}})
    model = {'parent': 'minecraft:block/cube_all', 'textures': {'all': f'{NS}:block/{name}'}}
    if name == 'resin':
        model['render_type'] = 'minecraft:translucent'
    write(f'assets/{NS}/models/block/{name}.json', model)
    write(f'assets/{NS}/models/item/{name}.json', {'parent': f'{NS}:block/{name}'})
    drop = {'chitin': 'chitin_plate', 'resin': 'resin_lump'}.get(name, name)
    functions = []
    if name == 'chitin':
        functions.append({'function': 'minecraft:set_count', 'count': {'type': 'minecraft:uniform', 'min': 1, 'max': 2}})
    functions.append({'function': 'minecraft:explosion_decay'})
    write(f'data/{NS}/loot_table/blocks/{name}.json', {'type': 'minecraft:block', 'pools': [
        {'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'{NS}:{drop}', 'functions': functions}]}]})
for name in ('chitin_plate', 'resin_lump'):
    write(f'assets/{NS}/models/item/{name}.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'{NS}:item/{name}'}})
for tool, names in {'pickaxe': ['chitin', 'resin'], 'hoe': ['hive_flesh', 'pulse_sac']}.items():
    write(f'data/minecraft/tags/block/mineable/{tool}.json', {'replace': False, 'values': [f'{NS}:{n}' for n in names]})
write('data/minecraft/tags/block/needs_stone_tool.json', {'replace': False, 'values': [f'{NS}:chitin']})

# planet_type/flesh.json is an authored terrain recipe, maintained alongside the other planet types.
biome = json.loads((WORLDS / 'worldgen/biome/crystal_barren.json').read_text())
biome['temperature'] = 1.7
biome['downfall'] = 0
biome['effects'].update({'sky_color': 0x501820, 'fog_color': 0x641e28,
                         'water_color': 0x541a20, 'water_fog_color': 0x2c0d14})
biome['effects'].pop('music')
write(f'data/{NS}/worldgen/biome/flesh_hive.json', biome)

# Ordinary planets are allocated before every moon. Append to the last moon list to retain all slots.
system = json.loads((WORLDS / 'starfold/system/experience.json').read_text())
system['suns'][0]['gas_giants'][0]['moons'].append({
    'type': f'{NS}:flesh', 'size': 768, 'seed': 20261101, 'name': f'planet.{NS}.flesh',
    'orbit': {'radius': 110000, 'phase': 0}, 'spin': {'period': 162000, 'tilt': 0}})
write('data/starfold_worlds/starfold/system/experience.json', system)
universe = json.loads((WORLDS / 'starfold/universe/experience.json').read_text())
universe['types'].append({'type': f'{NS}:flesh', 'weight': 1})
write('data/starfold_worlds/starfold/universe/experience.json', universe)
for lang, labels in {
    'en_us': ['Chitin', 'Hive Flesh', 'Resin', 'Pulse Sac', 'Chitin Plate', 'Resin Lump', 'Flesh', 'Flesh Hive'],
    'zh_cn': ['几丁质', '巢肉', '树脂', '搏动囊', '几丁质片', '树脂块', '血肉', '血肉巢区']
}.items():
    keys = [f'block.{NS}.{n}' for n in blocks] + [f'item.{NS}.chitin_plate', f'item.{NS}.resin_lump',
             f'planet.{NS}.flesh', f'biome.{NS}.flesh_hive']
    language = ROOT / f'assets/{NS}/lang/{lang}.json'
    text = json.loads(language.read_text()) if language.exists() else {}
    text.update(zip(keys, labels))
    text[f'planet_type.{NS}.flesh'] = 'Flesh' if lang == 'en_us' else '血肉'
    text[f'block.{NS}.blood'] = 'Blood' if lang == 'en_us' else '血液'
    text[f'item.{NS}.blood_bucket'] = 'Bucket of Blood' if lang == 'en_us' else '血液桶'
    write(f'assets/{NS}/lang/{lang}.json', text)
