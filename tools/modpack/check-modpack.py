"""Offline assertions only; never loads Minecraft or executes a downloaded jar."""
from collections import Counter
import gzip
import hashlib
import io
import json
import math
from pathlib import Path
import subprocess
import sys
import tomllib
import zipfile

import argparse

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--with-jars', action='store_true', help='Also audit cached pinned jars and their dependency closure; never downloads.')
args = parser.parse_args()
pack = Path('modpack')
source = Path('worlds/src/main/resources/data')
data = pack / 'kubejs/data'
ids = json.loads((pack / 'ids.json').read_text())['ids']
for meta in (pack / 'mods').glob('*.pw.toml'):
    d = tomllib.loads(meta.read_text())
    assert d['pin'] is True, meta
    jar = Path('build/pi/jars') / d['filename']
    if args.with_jars:
        assert hashlib.new(d['download']['hash-format'], jar.read_bytes()).hexdigest() == d['download']['hash'], meta

pins = {p.stem.removesuffix('.pw'): tomllib.loads(p.read_text()) for p in (pack / 'mods').glob('*.pw.toml')}
assert 'bosses-of-mass-destruction-forge' not in pins
assert pins['geckolib']['filename'] == 'geckolib-neoforge-1.21.1-4.8.2.jar'
assert pins['geckolib']['update']['curseforge'] == {'project-id': 388172, 'file-id': 7023453}
if args.with_jars:
    with zipfile.ZipFile(Path('build/pi/jars') / pins['geckolib']['filename']) as z:
        gecko = tomllib.loads(z.read('META-INF/neoforge.mods.toml').decode())
        assert any(m['modId'] == 'geckolib' and m['version'] == '4.8.2' for m in gecko['mods'])
biotech = tomllib.loads(Path('biotech/src/main/resources/META-INF/neoforge.mods.toml').read_text())
assert any(d['modId'] == 'geckolib' and d['type'] == 'required' and d['versionRange'] == '[4.8.2]'
           for d in biotech['dependencies']['starfold_biotech'])
# Resolve real item tags from the pinned archives, without executing their code.
item_tags = {}
for meta in pins.values() if args.with_jars else ():
    with zipfile.ZipFile(Path('build/pi/jars') / meta['filename']) as z:
        for name in z.namelist():
            if name.startswith('data/') and '/tags/item/' in name and name.endswith('.json'):
                namespace, path = name[5:].split('/tags/item/', 1)
                tag = namespace + ':' + path[:-5]
                document = json.loads(z.read(name))
                if document.get('replace'):
                    item_tags[tag] = []
                item_tags.setdefault(tag, []).extend(document.get('values', []))
for path in Path('materials/src/main/resources/data').glob('*/tags/item/**/*.json'):
    namespace, name = path.relative_to('materials/src/main/resources/data').as_posix().split('/tags/item/', 1)
    tag = namespace + ':' + name[:-5]
    item_tags.setdefault(tag, []).extend(json.loads(path.read_text())['values'])
def members(tag, visiting=frozenset()):
    assert tag not in visiting, tag
    found = set()
    for entry in item_tags.get(tag, []):
        value = entry if isinstance(entry, str) else entry['id']
        found.update(members(value[1:], visiting | {tag}) if value.startswith('#') else {value})
    return found
for tag, expected in {'c:ingots/osmium': 'mekanism:ingot_osmium', 'c:ingots/tin': 'mekanism:ingot_tin',
                      'c:ingots/lead': 'mekanism:ingot_lead', 'c:ingots/uranium': 'mekanism:ingot_uranium',
                      'c:gems/fluorite': 'mekanism:fluorite_gem', 'c:ingots/zinc': 'create:zinc_ingot',
                      'c:ingots/aluminum': 'starfold_materials:aluminum_ingot',
                      'c:ores/aluminum': 'starfold_materials:bauxite_ore'}.items():
    variants = members(tag)
    if args.with_jars or expected.startswith('starfold_materials:'):
        assert expected in variants, (tag, variants)

for path in data.rglob('*.json'):
    json.loads(path.read_text())
planet_root = Path('starfold_worlds/starfold/planet_type')
assert len(list((data / planet_root).glob('*.json'))) == 12
for path in (data / planet_root).glob('*.json'):
    original = json.loads((source / planet_root / path.name).read_text())
    actual = json.loads(path.read_text())
    ores = actual['terrain'].pop('ores')
    # Version 2 terrain stores ores directly; every other authored field is preserved.
    original['terrain'].pop('ores', None)
    assert actual == original, f'Changed non-ore fields: {path}'
    assert 1 <= len(ores) <= 16
    for ore in ores:
        assert .5 <= ore['rarity'] <= .999 and 0 <= ore['from'] <= ore['to'] <= 1
        for key in ('block', 'deep'):
            if key in ore and not ore[key].startswith('minecraft:'):
                assert ore[key] in ids['block'], ore
    aluminum = [ore for ore in ores if ore['block'].startswith('starfold_materials:')]
    assert aluminum == ([{'block': 'starfold_materials:bauxite_ore',
                          'deep': 'starfold_materials:deepslate_bauxite_ore',
                          'from': 0., 'to': 1., 'rarity': .9}] if path.stem == 'desert' else [])
    if path.stem in ('home', 'lush', 'archipelago', 'mushroom'):
        assert not any('osmium' in str(ore) or 'diamond' in str(ore) for ore in ores)
    diamonds = [ore for ore in ores if ore['block'] == 'minecraft:diamond_ore']
    if path.stem in ('rocky', 'asteroid', 'crystal'):
        assert diamonds == [{'block': 'minecraft:diamond_ore', 'deep': 'minecraft:deepslate_diamond_ore',
                             'from': .65, 'to': 1.0, 'rarity': .995 if path.stem == 'crystal' else .99}]
    else:
        assert not diamonds

# Aluminum placement is solely controlled by planet ore tables.
assert not list(Path('materials/src/main/resources/data').glob('*/worldgen/**/*.json'))
assert not list(Path('materials/src/main/resources/data').glob('*/neoforge/biome_modifier/**/*.json'))
# Ore placement is pointwise terrain now; generation behavior is covered by Gradle tests.

# CDG otherwise accepts only vanilla bedrock; Starfold's natural shell is core_shell.
oil_tag = json.loads((pack / 'kubejs/data/createdieselgenerators/tags/block/oil_deposit.json').read_text())
assert oil_tag == {'replace': False, 'values': ['starfold:core_shell']}
assert 'starfold:core_shell' in ids['block']

preset_path = Path('starfold_worlds/worldgen/world_preset/experience.json')
original = json.loads((source / preset_path).read_text())
assert 'starter_ship' not in original['dimensions']['starfold:native_planet']['generator']
assert original == json.loads((data / preset_path).read_text())
# Resolve the same resource priority as the pack: KubeJS > Biotech > Worlds.
def experience_resource(relative):
    for root in (data, Path('biotech/src/main/resources/data'), source):
        path = root / relative
        if path.exists():
            return json.loads(path.read_text())
    raise AssertionError(relative)
generator = original['dimensions']['starfold:native_planet']['generator']
assert 'starfold_worlds:experience' in generator.values(), generator
system = experience_resource(Path('starfold_worlds/starfold/system/experience.json'))
moons = system['suns'][0]['gas_giants'][0]['moons']
assert moons[-1]['type'] == 'starfold_biotech:flesh'
base_system = json.loads((source / 'starfold_worlds/starfold/system/experience.json').read_text())
without_flesh = json.loads(json.dumps(system))
without_flesh['suns'][0]['gas_giants'][0]['moons'].pop()
assert without_flesh == base_system
universe = experience_resource(Path('starfold_worlds/starfold/universe/experience.json'))
assert universe['types'][-1] == {'type': 'starfold_biotech:flesh', 'weight': 1}
base_universe = json.loads((source / 'starfold_worlds/starfold/universe/experience.json').read_text())
without_flesh = json.loads(json.dumps(universe))
without_flesh['types'].pop()
assert without_flesh == base_universe
assert (Path('biotech/src/main/resources/data/starfold_biotech/starfold/planet_type/flesh.json')).exists()
normal = json.loads((data / 'minecraft/tags/worldgen/world_preset/normal.json').read_text())
assert normal == {'replace': True, 'values': ['starfold_worlds:experience']}
fuels = list((data / 'escape_velocity/starfold/thruster_fuel').glob('*.json'))
assert len(fuels) == 4
for path in fuels:
    fuel = json.loads(path.read_text())
    assert set(fuel) <= {'fluid', 'impulse', 'interstellar', 'mass'} and fuel['impulse'] > 0
    assert fuel['mass'] > 0
    assert fuel.get('interstellar', False) is (fuel['fluid'] == 'mekanismgenerators:deuterium')
    assert fuel['fluid'] in ids['fluid'], fuel

# Provisional rocket-equation initial values, not the superseded tiny-fixture calibration.
fuel_values = {json.loads(p.read_text())['fluid']: json.loads(p.read_text())['impulse'] for p in fuels}
assert fuel_values == {'createdieselgenerators:diesel': 940, 'createdieselgenerators:gasoline': 940,
                       'mekanism:hydrogen': 1880, 'mekanismgenerators:deuterium': 23500}
fuel_masses = {json.loads(p.read_text())['fluid']: json.loads(p.read_text())['mass'] for p in fuels}
assert fuel_masses == dict.fromkeys(fuel_values, 47)

# Read the shipped NBT with our existing offline parser, not Minecraft or downloaded code.
sys.path.insert(0, str(Path('tools/ship/pack').resolve()))
import shipgen
structure = shipgen.read_nbt(gzip.decompress(Path(
    'extras/src/main/resources/data/starfold_extras/structure/starter_ship.nbt').read_bytes()))
palette = structure['palette'][1]
blocks = Counter(palette[b['state']]['Name'] for b in structure['blocks'][1])
assert blocks.pop('minecraft:air') == 8969
assert sum(blocks.values()) == 2347
assert blocks['create:fluid_tank'] == 0  # Reference requires adding a tank; none is shipped.
heavy = set(json.loads(Path('src/main/resources/data/sable/tags/block/heavy.json').read_text())['values'])
assert sum(blocks[b] for b in heavy) == 58
mass_groups = Counter()
for block, count in blocks.items():
    mass_groups[2 if block in heavy else shipgen.SABLE_MASS.get(block, 1)] += count
assert mass_groups == {0: 9, .25: 25, .5: 119, 1: 2135, 2: 59}, mass_groups
hull_mass = sum(mass * count for mass, count in mass_groups.items())
assert hull_mass == 2318.75
# Sable mass tiers are independent of half/quarter volume; verified against pinned data.
if args.with_jars:
    with zipfile.ZipFile(Path('build/pi/jars') / pins['sable']['filename']) as z:
        for tier, mass in {'light': .5, 'super_light': .25, 'heavy': 2, 'super_heavy': 4}.items():
            assert json.loads(z.read(f'data/sable/physics_block_properties/{tier}.json'))['properties']['sable:mass'] == mass
# One extra default 8000 mB Create tank, block mass 1; cargo/aircraft excluded.
dry_mass, volume = hull_mass + 1, 8000
reserves = {}
for fluid, impulse in fuel_values.items():
    exhaust = 500000 if fluid.endswith(':deuterium') else 40000 if fluid.endswith(':hydrogen') else 20000
    assert math.isclose(impulse / (fuel_masses[fluid] / 1000), exhaust, abs_tol=1e-8)
    reserves[fluid] = exhaust * math.log1p(volume * fuel_masses[fluid] / 1000 / dry_mass)
first = reserves['createdieselgenerators:diesel']
assert 2990 < first < 3010, first
assert reserves['createdieselgenerators:gasoline'] == first
assert math.isclose(reserves['mekanism:hydrogen'], 2 * first)
assert math.isclose(reserves['mekanismgenerators:deuterium'], 25 * first)
assert .15 < volume * .047 / dry_mass < .17

# 1-D chart-ascent estimate only; deliberately not a native-physics/game acceptance.
assert blocks['starfold:thruster'] == 46
lift_count = sum(1 for b in structure['blocks'][1]
                 if palette[b['state']]['Name'] == 'starfold:thruster'
                 and palette[b['state']]['Properties']['facing'] == 'down')
assert lift_count == 10
for boost in (1, 4):
    fuel, height, velocity, seconds, dt = float(volume), 0., 0., 0., .0125
    while height < 2048 and seconds < 120 and fuel > 0:
        mass = dry_mass + fuel * .047
        fade = max(0., min(1., (height - 1024) / 2048))
        gravity = 8.25 * (1 - fade * fade * (3 - 2 * fade))
        force = max(0., min(lift_count * 3350 * boost, mass * (gravity + 4 * (160 * boost - velocity))))
        fuel -= force * dt / fuel_values['createdieselgenerators:diesel']
        velocity = (velocity + (force / mass - gravity) * dt) / (1 + .09 * dt)
        height += velocity * dt
        seconds += dt
    reserve = 20000 * math.log1p(fuel * .047 / dry_mass)
    assert height >= 2048 and .18 < (volume - fuel) / volume < .26, (boost, height, fuel)
    assert reserve > 2300, reserve
    print(f'ESTIMATE ONLY: chart ascent boost={boost}, {seconds:.2f}s, '
          f'{volume-fuel:.0f} mB spent, {reserve:.0f} blocks/s remaining delta-v')
print(f'PASS: provisional fuel tiers 1:2:25, hull mass {hull_mass}, '
      f'added-tank diesel delta-v {first:.2f} blocks/s')
skims = list(data.glob('*/starfold/gas_skim/*.json'))
assert len(skims) == 1
assert json.loads(skims[0].read_text()) == {'fluid': 'mekanismgenerators:deuterium', 'rate': 10}

# Inspect chemical/fluid outputs, not item-output filters. Rotary is a reversible
# conversion of existing skimmed stock; every other deuterium producer is removed.
deuterium_producers = set()
for jar in (Path('build/pi/jars') / meta['filename'] for meta in pins.values()) if args.with_jars else ():
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if not name.startswith('data/') or '/recipe/' not in name or not name.endswith('.json'):
                continue
            recipe = json.loads(z.read(name))
            if any('mekanismgenerators:deuterium' in json.dumps(value)
                   for key, value in recipe.items() if 'output' in key or key == 'result'):
                namespace, path = name[5:].split('/recipe/', 1)
                deuterium_producers.add(namespace + ':' + path[:-5])
if args.with_jars:
    assert deuterium_producers == {'mekanismgenerators:separator/heavy_water',
                                   'mekanismgenerators:rotary/deuterium'}

# Evaluate only our JS with a tiny KubeJS API mock, not any downloaded code.
mock = r'''
const assert = require('node:assert/strict');
const fs = require('node:fs');
const ids = JSON.parse(fs.readFileSync('modpack/ids.json')).ids.item;
const made = [], removed = [], removedIds = [];
global.ServerEvents = { recipes: fn => fn({
  custom: recipe => {
    assert.equal(recipe.type, 'create:mechanical_crafting');
    assert.deepEqual(recipe.result, {id: 'starfold:interstellar_drive', count: 1});
    assert.deepEqual(recipe.pattern, ['HCH', 'CDC', 'HCH', ' G ']);
    assert.deepEqual(recipe.key, {H: {item: 'starfold_materials:osmium_pressure_hull'}, C: {item: 'mekanism:elite_control_circuit'}, D: {item: 'mekanismgenerators:deuterium_bucket'}, G: {item: 'starfold_biotech:host_ganglion'}});
    for (const ingredient of Object.values(recipe.key)) assert(ingredient.item in ids, ingredient);
    assert.equal(recipe.accept_mirrored, false);
    made.push(recipe.result.id);
    return {id: id => assert.equal(id, 'escape_velocity:interstellar_drive')};
  },
  remove: f => f.id ? removedIds.push(f.id) : removed.push(f.output),
  shaped: (output, pattern, keys) => {
    assert(output in ids);
    assert(pattern.length >= 1 && pattern.length <= 3);
    assert(pattern.every(row => row.length === 3));
    for (const char of pattern.join('')) if (char !== ' ') assert(char in keys);
    const vanilla = new Set(['minecraft:glass', 'minecraft:copper_ingot', 'minecraft:coal_block', 'minecraft:redstone']);
    for (const item of Object.values(keys)) assert(item in ids || vanilla.has(item), item);
    if (output === 'create:blaze_burner') {
      assert.deepEqual(pattern, [' I ', 'ICI', ' B ']);
      assert.deepEqual(keys, {I: 'create:iron_sheet', C: 'minecraft:coal_block', B: 'minecraft:copper_ingot'});
    }
    if (output === 'create:rose_quartz') {
      assert.deepEqual(pattern, ['RRR', 'RGR', 'RRR']);
      assert.deepEqual(keys, {R: 'minecraft:redstone', G: 'minecraft:glass'});
    }
    if (output === 'starfold:scope_nav') {
      assert.deepEqual(pattern, [' M ', 'BSB', ' E ']);
      assert.deepEqual(keys, {M: 'create:precision_mechanism', B: 'create:brass_sheet', S: 'starfold:scope', E: 'create:electron_tube'});
    }
    if (output === 'starfold:gas_scoop') {
      assert.deepEqual(pattern, [' P ', 'CTC', ' H ']);
      assert.deepEqual(keys, {P: 'create:precision_mechanism', C: 'mekanism:advanced_control_circuit', T: 'create:fluid_tank', H: 'mekanism:rotary_condensentrator'});
    }
    if (output === 'starfold:scope_precise') {
      assert.deepEqual(keys, {F: 'mekanism:fluorite_gem', C: 'mekanism:advanced_control_circuit', S: 'starfold:scope_nav'});
    }
    made.push(output);
    return { id: id => assert(id.startsWith('escape_velocity:')) };
  }
}) };
require(process.cwd() + '/modpack/kubejs/server_scripts/progression.js');
assert.equal(made.length, 10);
assert.deepEqual(removed, made);
assert.equal(new Set(made).size, 10);
assert.deepEqual(removedIds, ['mekanismgenerators:separator/heavy_water']);
'''
subprocess.run(['node', '-e', mock], check=True)
print('PASS: pins, JSON, 12 ores-only overrides, ship-free preset, four fuel IDs, skim definition, ten recipe replacements, terrestrial deuterium removal')

# Quest files deliberately use JSON-compatible SNBT; the only extension is a header comment.
def snbt(path):
    return json.loads('\n'.join(line for line in path.read_text().splitlines() if not line.startswith('//')))

quest_root = pack / 'config/ftbquests/quests'
if quest_root.exists():
    tables = {locale: snbt(quest_root / f'lang/{locale}.snbt') for locale in ('en_us', 'zh_cn')}
    assert tables['en_us'].keys() == tables['zh_cn'].keys()
    assert '你' not in str(tables['zh_cn'])
    assert 'your ' not in str(tables['en_us']).lower()
    vanilla_items = set('coal_block oak_log crafting_table wooden_pickaxe cobblestone torch furnace coal iron_ingot iron_pickaxe copper_ingot bread white_bed shield glass golden_apple bow arrow ancient_debris netherite_scrap iron_chestplate iron_block'.split())
    quest_files = sorted((quest_root / 'chapters').glob('*.snbt'))
    assert len(quest_files) == 9
    seen = {'0000000000000001'}
    completed = set()
    mainline = set()
    quest_ids = set()
    def unique(identifier):
        assert len(identifier) == 16 and 0 < int(identifier, 16) < 2**63
        assert identifier not in seen, identifier
        seen.add(identifier)
    def item(identifier):
        assert identifier in ids['item'] or identifier.removeprefix('minecraft:') in vanilla_items, identifier
    total = 0
    kinds = set()
    expected_keys = {'file.0000000000000001.title'}
    for chapter_index, path in enumerate(quest_files):
        chapter = snbt(path)
        unique(chapter['id'])
        expected_keys.add(f'chapter.{chapter["id"]}.title')
        assert chapter['order_index'] == chapter_index and 6 <= len(chapter['quests']) <= (13 if chapter_index == 4 else 12)
        for quest in chapter['quests']:
            unique(quest['id'])
            quest_ids.add(quest['id'])
            for suffix in ('title', 'quest_desc'):
                key = f'quest.{quest["id"]}.{suffix}'
                expected_keys.add(key)
                for table in tables.values():
                    assert table.get(key), key
                    assert isinstance(table[key], list if suffix == 'quest_desc' else str)
            assert set(quest.get('dependencies', [])).issubset(mainline), quest
            completed.add(quest['id'])
            if not quest.get('optional', False):
                mainline.add(quest['id'])
            for task in quest['tasks']:
                unique(task['id'])
                kind = task['type']
                kinds.add(kind)
                assert kind in ('item', 'dimension', 'advancement', 'checkmark', 'kill')
                if kind == 'item':
                    item(task['item']['id'])
                    assert task['count'] > 0
                elif kind == 'kill':
                    assert task['entity'] in ids['entity'] and task['value'] == 1, task
                elif kind != 'checkmark':
                    assert task[kind] in ids[kind], task
                    assert task.get('dimension') not in ('minecraft:the_nether', 'minecraft:the_end'), task
            for reward in quest['rewards']:
                unique(reward['id'])
                assert reward['type'] == 'item'
                assert reward['item']['id'] in ('minecraft:bread', 'minecraft:iron_ingot')
                assert reward['count'] == 2 and reward['item']['count'] == 1
            total += 1
    assert tables['en_us'].keys() == expected_keys
    assert kinds == {'item', 'dimension', 'advancement', 'checkmark', 'kill'}
    final = snbt(quest_files[8])['quests']
    old_final = [q for q in final if q['id'] not in ('1000000000080900', '1000000000080A00')]
    assert [q['id'] for q in old_final] == [f'100000000008{i:02X}00' for i in range(1, 9)]
    assert final[4]['tasks'][0]['item']['id'] == 'starfold_materials:osmium_pressure_hull'
    assert final[5]['tasks'][0]['item']['id'] == 'starfold:gas_scoop'
    assert final[6]['tasks'][0]['item']['id'] == 'mekanismgenerators:deuterium_bucket'
    assert final[8]['id'] == '1000000000080A00'
    assert final[8]['tasks'][0]['item']['id'] == 'starfold:interstellar_drive'
    assert final[8]['dependencies'] == [final[7]['id'], '1000000000070A00']
    assert final[9]['dependencies'] == [final[8]['id']]
    assert final[9]['id'] == '1000000000080800'
    assert [final[i]['tasks'][0]['type'] for i in (7, 9)] == ['checkmark', 'checkmark']
    for q in (final[7], final[9]):
        assert '手动' in str(tables['zh_cn'][f'quest.{q["id"]}.quest_desc'])
    host = snbt(quest_files[7])['quests']
    assert [q['id'] for q in host] == [f'100000000007{i:02X}00' for i in (1, 2, 3, 4, 8, 9, 10, 11)]
    assert [q['tasks'][0]['type'] for q in host[-4:]] == ['checkmark', 'checkmark', 'kill', 'item']
    assert host[-2]['tasks'] == [{'id': '1000000000070A01', 'type': 'kill', 'entity': 'starfold_biotech:host', 'value': 1}]
    assert host[-1]['tasks'][0]['item']['id'] == 'starfold_biotech:host_ganglion'
    assert total == 86
    assert quest_ids == {f'{0x1000000000000000 + ci * 0x10000 + i * 0x100:016X}'
                         for ci, count in enumerate([6, 8, 12, 8, 12, 11, 10, 4, 8])
                         for i in range(1, count + 1)} | {'1000000000060B00', '1000000000080900', '1000000000080A00'} | {f'100000000007{i:02X}00' for i in range(8, 12)}
    expansion = snbt(quest_files[6])['quests']
    assert expansion[-1]['tasks'][0]['item']['id'] == 'starfold_materials:aluminum_hull_plating'
    orbit = snbt(quest_files[4])['quests']
    nav_index = next(i for i, q in enumerate(orbit) if q['tasks'][0].get('item', {}).get('id') == 'starfold:scope_nav')
    lift_index = next(i for i, q in enumerate(orbit) if q['tasks'][0].get('advancement') == 'starfold:ship/lift_off')
    assert nav_index < lift_index
    assert orbit[nav_index]['id'] == '1000000000050800'
    # Exactly two authoritative copies of the provisional name across pack runtime source.
    name = tomllib.loads((pack / 'pack.toml').read_text())['name']
    assert sum(p.read_text().count(name) for p in pack.rglob('*') if p.is_file() and p.suffix != '.jar') == 2
    print(f'PASS: 9 chapters / {total} quests, real task IDs, unique IDs, acyclic dependencies, bilingual key parity, supply-only rewards')

index = tomllib.loads((pack / 'index.toml').read_text())
assert hashlib.sha256((pack / 'index.toml').read_bytes()).hexdigest() == tomllib.loads((pack / 'pack.toml').read_text())['index']['hash']
for entry in index['files']:
    assert hashlib.sha256((pack / entry['file']).read_bytes()).hexdigest() == entry['hash'], entry['file']
excluded_sources = {'README.md', 'RULES.md', 'ids.md', 'ids.json', '.gitignore', '.packwizignore'}
assert 'RULES.md' in (pack / '.packwizignore').read_text().splitlines()
assert not any(entry['file'] in excluded_sources or entry['file'].endswith(('.py', '.jar')) for entry in index['files'])
print('PASS: packwiz index, pack hash and export input exclusions')

# Required-mod closure includes recursively bundled libraries, without loading classes.
audit = []
def descriptor(z, label):
    name = 'META-INF/neoforge.mods.toml'
    if name in z.namelist():
        d = tomllib.loads(z.read(name).decode())
        audit.append({'source': label, 'mods': d['mods'], 'dependencies': d.get('dependencies', {})})
    child_meta = 'META-INF/jarjar/metadata.json'
    if child_meta in z.namelist():
        for child in json.loads(z.read(child_meta))['jars']:
            descriptor(zipfile.ZipFile(io.BytesIO(z.read(child['path']))), label + '!' + child['path'])
for meta in sorted((pack / 'mods').glob('*.pw.toml')) if args.with_jars else ():
    d = tomllib.loads(meta.read_text())
    descriptor(zipfile.ZipFile(Path('build/pi/jars') / d['filename']), d['filename'])
assert not any(m['modId'] == 'bosses_of_mass_destruction' for entry in audit for m in entry['mods'])
available = {'minecraft', 'neoforge'} | {m['modId'] for entry in audit for m in entry['mods']}
for entry in audit:
    for dependencies in entry['dependencies'].values():
        for dep in dependencies:
            required = dep.get('type', 'required' if dep.get('mandatory', True) else 'optional') == 'required'
            if required:
                assert dep['modId'] in available, (entry['source'], dep)
if args.with_jars:
    report = Path('build/modpack/dependency-audit.json')
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(audit, indent=2) + '\n')
    print('PASS: cached hashes and required-mod presence including nested jars (not runtime validation)')
else:
    print('SKIP: cached jar hashes, upstream tags/recipes and dependency closure; use --with-jars to include them')

for side in ('client', 'server'):
    archive = Path(f'build/pi/escape-velocity-{side}.zip')
    if not archive.exists():
        continue
    with zipfile.ZipFile(archive) as z:
        manifest = json.loads(z.read('manifest.json'))
        assert manifest['minecraft']['version'] == '1.21.1'
        assert manifest['minecraft']['modLoaders'] == [{'id': 'neoforge-21.1.247', 'primary': True}]
        expected = set()
        for meta in (pack / 'mods').glob('*.pw.toml'):
            d = tomllib.loads(meta.read_text())
            if side == 'client' or d['side'] != 'client':
                cf = d['update']['curseforge']
                expected.add((cf['project-id'], cf['file-id']))
        assert {(m['projectID'], m['fileID']) for m in manifest['files']} == expected
        assert all(m['required'] for m in manifest['files'])
        names = z.namelist()
        assert not any(n.endswith('.jar') for n in names)
        assert not any(n.startswith('overrides/src/') for n in names)
        for excluded in ('README.md', 'RULES.md', 'ids.md', 'ids.json', '.gitignore', '.packwizignore'):
            assert f'overrides/{excluded}' not in names
        for entry in index['files']:
            if not entry.get('metafile'):
                target = 'overrides/' + entry['file']
                assert target in names, target
                assert hashlib.sha256(z.read(target)).hexdigest() == entry['hash']
    print(f'PASS: {side} export manifest ({len(expected)} projects), overrides and exclusion policy')
