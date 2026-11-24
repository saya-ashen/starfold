"""Rebuild hull/aluminum resources and original 16x16 pixel textures.
All recipe, mass, pressure and hardness values are provisional; see BALANCE.md.
"""
import json
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources'
NS = 'starfold_materials'


def write(path, value):
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def png(name, pixels, folder='block', alpha=False):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    image = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 16, 16, 8, 6 if alpha else 2, 0, 0, 0))
    image += chunk(b'IDAT', zlib.compress(b''.join(pixels))) + chunk(b'IEND', b'')
    path = ROOT / f'assets/{NS}/textures/{folder}/{name}.png'
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(image)


def ship_shapes():
    outline = [(0, 0), (1, 0), (1, 1), (0, 1)]
    profiles = {
        'ramp': (lambda x, z: z, [outline]),
        'half_ramp': (lambda x, z: z / 2, [outline]),
        'high_ramp': (lambda x, z: .5 + z / 2, [outline]),
        'bevel': (lambda x, z: min(1, .5 + z),
                  [[(0, 0), (1, 0), (1, .5), (0, .5)], [(0, .5), (1, .5), (1, 1), (0, 1)]]),
        'corner': (min, [[(0, 0), (1, 0), (1, 1)], [(0, 0), (1, 1), (0, 1)]]),
    }
    for name, texture in [('hull_trim', f'{NS}:block/aluminum_hull_plating'),
                          ('dark_hull_trim', f'{NS}:block/osmium_pressure_hull'),
                          ('bridge_glass', f'{NS}:block/bridge_glass')]:
        glass = name == 'bridge_glass'
        directory = ROOT / f'assets/{NS}/models/block'
        (directory / f'{name}.mtl').write_text(f'newmtl surface\nKd 1 1 1\nmap_Kd {texture}\n')
        variants = {}
        for profile, (height, regions) in profiles.items():
            for half in ('bottom', 'top'):
                model = f'{name}_{profile}_{half}'
                lines = [f'mtllib {name}.mtl', 'usemtl surface']
                count = 0

                def face(points, axis):
                    nonlocal count
                    if half == 'top':
                        points = [(x, 1 - y, z) for x, y, z in reversed(points)]
                    points = list(dict.fromkeys(points))
                    if len(points) < 3:
                        return
                    u, v = [i for i in range(3) if i != axis]
                    for p in points:
                        lines.extend(('v ' + ' '.join(map(str, p)), f'vt {p[u]} {p[v]}'))
                    lines.append('f ' + ' '.join(f'{i}/{i}' for i in range(count + 1, count + 1 + len(points))))
                    count += len(points)

                def upper(x, z):
                    h = height(x, z)
                    return (x, .125 + .875 * h if glass else h, z)

                def lower(x, z):
                    return (x, .875 * height(x, z) if glass else 0, z)

                for region in regions:
                    face([upper(*p) for p in reversed(region)], 1)
                    face([lower(*p) for p in region], 1)
                # Open glass sides avoid doubled translucent faces between matching panels.
                if not glass:
                    edge = outline if profile != 'bevel' else [(0, 0), (1, 0), (1, .5), (1, 1), (0, 1), (0, .5)]
                    for a, b in zip(edge, edge[1:] + edge[:1]):
                        face([lower(*a), upper(*a), upper(*b), lower(*b)], 0 if a[0] == b[0] else 2)
                (directory / f'{model}.obj').write_text('\n'.join(lines) + '\n')
                write(f'assets/{NS}/models/block/{model}.json', {
                    'parent': 'minecraft:block/block', 'loader': 'neoforge:obj',
                    'model': f'{NS}:models/block/{model}.obj', 'automatic_culling': False,
                    'ambientocclusion': False, 'textures': {'particle': texture},
                    **({'render_type': 'minecraft:translucent'} if glass else {}),
                })
                for direction, rotation in [('north', 0), ('east', 90), ('south', 180), ('west', 270)]:
                    variants[f'facing={direction},half={half},shape={profile}'] = {
                        'model': f'{NS}:block/{model}', 'y': rotation,
                    }
        write(f'assets/{NS}/blockstates/{name}.json', {'variants': variants})
        write(f'assets/{NS}/models/item/{name}.json', {'parent': f'{NS}:block/{name}_ramp_bottom'})
        write(f'data/{NS}/loot_table/blocks/{name}.json', {'type': 'minecraft:block', 'pools': [
            {'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'{NS}:{name}'}],
             'conditions': [{'condition': 'minecraft:survives_explosion'}]
                            + ([{'condition': 'minecraft:match_tool', 'predicate': {'predicates': {
                                'minecraft:enchantments': [{'enchantments': 'minecraft:silk_touch', 'levels': {'min': 1}}],
                            }}}] if glass else [])}]})
        write(f'data/{NS}/recipe/{name}.json', {'type': 'minecraft:stonecutting',
            'ingredient': {'item': {'hull_trim': f'{NS}:aluminum_hull_plating',
                                    'dark_hull_trim': f'{NS}:osmium_pressure_hull',
                                    'bridge_glass': 'minecraft:glass'}[name]},
            'result': {'id': f'{NS}:{name}', 'count': 1}})
    png('bridge_glass', [bytes([0]) + b''.join(
        bytes((120, 194, 220, 145 if x == y or x == y + 1 else 52)) for x in range(16))
        for y in range(16)], alpha=True)


def hull_light():
    name = 'cyan_hull_light'
    model = f'{NS}:block/{name}'
    pixels = []
    for y in range(16):
        row = bytearray([0])
        for x in range(16):
            edge = min(x, y, 15 - x, 15 - y)
            row.extend((44, 124, 167) if edge == 0 else (104, 211, 248) if edge == 1 else (138, 230, 255))
        pixels.append(bytes(row))
    png(name, pixels)
    write(f'assets/{NS}/blockstates/{name}.json', {'variants': {'': {'model': model}}})
    write(f'assets/{NS}/models/block/{name}.json', {
        'parent': 'minecraft:block/cube_all', 'textures': {'all': model},
    })
    write(f'assets/{NS}/models/item/{name}.json', {'parent': model})
    write(f'data/{NS}/recipe/{name}.json', {'type': 'minecraft:crafting_shapeless',
        'ingredients': [{'item': 'minecraft:sea_lantern'}, {'item': 'minecraft:cyan_dye'}],
        'result': {'id': f'{NS}:{name}', 'count': 1}})
    write(f'data/{NS}/loot_table/blocks/{name}.json', {'type': 'minecraft:block', 'pools': [
        {'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'{NS}:{name}'}],
         'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})


def panel(name, base):
    pixels = []
    for y in range(16):
        row = bytearray([0])
        for x in range(16):
            shade = ((x * 13 + y * 7) % 5) - 2 + (15 - y) // 4
            if x in (0, 15) or y in (0, 15):
                shade = -35
            elif x == 1 or y == 1:
                shade = 20
            elif x == 14 or y == 14:
                shade = -18
            elif x == 7 and 4 <= y <= 11:
                shade = -12  # Recessed service-panel seam.
            if (x, y) in ((3, 3), (12, 3), (3, 12), (12, 12)):
                shade = 35
            elif (x, y) in ((4, 3), (13, 3), (4, 12), (13, 12), (3, 4), (12, 4), (3, 13), (12, 13)):
                shade = -35
            row.extend(max(0, min(255, c + shade)) for c in base)
        pixels.append(bytes(row))
    png(name, pixels)


names = ['aluminum_hull_plating', 'osmium_pressure_hull']
all_blocks = [name + suffix for name in names for suffix in ('', '_slab')]
for name, color, mass, metal in zip(names, [(180, 195, 208), (57, 74, 96)], [.5, 4], ['aluminum', 'osmium']):
    full = f'{NS}:{name}'
    slab = full + '_slab'
    texture = f'{NS}:block/{name}'
    panel(name, color)
    write(f'assets/{NS}/blockstates/{name}.json', {'variants': {'': {'model': f'{NS}:block/{name}'}}})
    write(f'assets/{NS}/models/block/{name}.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': texture}})
    write(f'assets/{NS}/blockstates/{name}_slab.json', {'variants': {
        'type=bottom': {'model': f'{NS}:block/{name}_slab'},
        'type=top': {'model': f'{NS}:block/{name}_slab_top'},
        'type=double': {'model': f'{NS}:block/{name}'}}})
    for suffix, parent in [('_slab', 'slab'), ('_slab_top', 'slab_top')]:
        write(f'assets/{NS}/models/block/{name}{suffix}.json', {'parent': f'minecraft:block/{parent}',
              'textures': {'bottom': texture, 'top': texture, 'side': texture}})
    for suffix in ('', '_slab'):
        identifier = full + suffix
        write(f'assets/{NS}/models/item/{name}{suffix}.json', {'parent': f'{NS}:block/{name}{suffix}'})
        functions = []
        if suffix:
            functions.append({'function': 'minecraft:set_count', 'count': 2, 'conditions': [
                {'condition': 'minecraft:block_state_property', 'block': slab, 'properties': {'type': 'double'}}]})
        functions.append({'function': 'minecraft:explosion_decay'})
        write(f'data/{NS}/loot_table/blocks/{name}{suffix}.json', {'type': 'minecraft:block', 'pools': [
            {'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': identifier, 'functions': functions}]}]})
        properties = {'priority': 1002, 'selector': identifier, 'properties': {'sable:mass': mass / 2 if suffix else mass}}
        if suffix:
            properties['overrides'] = {'type=double': {'sable:mass': mass}}
        write(f'data/{NS}/physics_block_properties/{name}{suffix}.json', properties)
    write(f'data/{NS}/tags/item/{name}.json', {'replace': False, 'values': [full]})
    write(f'data/{NS}/recipe/{name}_slab.json', {'type': 'minecraft:crafting_shaped', 'category': 'building',
          'pattern': ['HHH'], 'key': {'H': {'tag': f'{NS}:{name}'}}, 'result': {'id': slab, 'count': 6}})
    write(f'data/{NS}/recipe/{name}.json', {'type': 'minecraft:crafting_shaped', 'category': 'building',
          'pattern': ['AAA', 'A A', 'AAA'] if metal == 'aluminum' else ['OIO', 'IOI', 'OIO'],
          'key': {'A': {'tag': 'c:ingots/aluminum'}} if metal == 'aluminum' else
                 {'O': {'tag': 'c:ingots/osmium'}, 'I': {'tag': 'c:ingots/iron'}},
          'result': {'id': full, 'count': 4}})
write(f'data/{NS}/tags/block/pressure_hulls.json', {'replace': False, 'values': [f'{NS}:osmium_pressure_hull', f'{NS}:osmium_pressure_hull_slab']})
write(f'data/{NS}/starfold/hull_pressure/osmium_pressure_hull.json', {'blocks': f'#{NS}:pressure_hulls', 'pressure': .85})
aluminum_blocks = ['bauxite_ore', 'deepslate_bauxite_ore', 'raw_aluminum_block', 'aluminum_block']
write('data/minecraft/tags/block/mineable/pickaxe.json', {'replace': False, 'values': [f'{NS}:{b}' for b in all_blocks + aluminum_blocks + ['hull_trim', 'dark_hull_trim']]})
write('data/minecraft/tags/block/needs_stone_tool.json', {'replace': False, 'values': [f'{NS}:{b}' for b in all_blocks[:2] + aluminum_blocks + ['hull_trim']]})
write('data/minecraft/tags/block/needs_iron_tool.json', {'replace': False, 'values': [f'{NS}:{b}' for b in all_blocks[2:] + ['dark_hull_trim']]})
for locale, labels in [('en_us', ['Aluminum Alloy Hull Plating', 'Osmium Alloy Pressure Hull']),
                       ('zh_cn', ['铝合金外壳板', '锇合金耐压壳'])]:
    lang = {}
    for name, label in zip(names, labels):
        lang[f'block.{NS}.{name}'] = label
        lang[f'block.{NS}.{name}_slab'] = label + (' Slab' if locale == 'en_us' else '半砖')
    for name, en, zh in [('bauxite_ore', 'Bauxite Ore', '铝土矿石'),
                         ('deepslate_bauxite_ore', 'Deepslate Bauxite Ore', '深层铝土矿石'),
                         ('raw_aluminum_block', 'Block of Raw Aluminum', '粗铝块'),
                         ('aluminum_block', 'Block of Aluminum', '铝块'),
                         ('raw_aluminum', 'Raw Aluminum', '粗铝'),
                         ('aluminum_ingot', 'Aluminum Ingot', '铝锭'),
                         ('aluminum_nugget', 'Aluminum Nugget', '铝粒')]:
        kind = 'block' if name in aluminum_blocks else 'item'
        lang[f'{kind}.{NS}.{name}'] = en if locale == 'en_us' else zh
    lang[f'block.{NS}.hull_trim'] = 'Shaped Hull Plating' if locale == 'en_us' else '异形船体装甲'
    lang[f'block.{NS}.dark_hull_trim'] = 'Dark Hull Trim' if locale == 'en_us' else '深色船体饰板'
    lang[f'block.{NS}.cyan_hull_light'] = 'Cyan Hull Light' if locale == 'en_us' else '青蓝船体灯'
    lang[f'block.{NS}.bridge_glass'] = 'Sloped Bridge Glass' if locale == 'en_us' else '斜面舰桥玻璃'
    write(f'assets/{NS}/lang/{locale}.json', lang)

ship_shapes()
hull_light()

# Only assets, processing recipes and tags: no configured/placed features or biome modifiers.
for name in aluminum_blocks:
    full = f'{NS}:{name}'
    write(f'assets/{NS}/blockstates/{name}.json', {'variants': {'': {'model': f'{NS}:block/{name}'}}})
    write(f'assets/{NS}/models/block/{name}.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': f'{NS}:block/{name}'}})
    write(f'assets/{NS}/models/item/{name}.json', {'parent': f'{NS}:block/{name}'})
    if name.endswith('_ore'):
        entry = {'type': 'minecraft:alternatives', 'children': [
            {'type': 'minecraft:item', 'name': full, 'conditions': [
                {'condition': 'minecraft:match_tool', 'predicate': {'predicates': {
                    'minecraft:enchantments': [{'enchantments': 'minecraft:silk_touch', 'levels': {'min': 1}}]}}}]},
            {'type': 'minecraft:item', 'name': f'{NS}:raw_aluminum', 'functions': [
                {'function': 'minecraft:apply_bonus', 'enchantment': 'minecraft:fortune', 'formula': 'minecraft:ore_drops'},
                {'function': 'minecraft:explosion_decay'}]}]}
        pool = {'rolls': 1, 'entries': [entry]}
    else:
        pool = {'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': full}],
                'conditions': [{'condition': 'minecraft:survives_explosion'}]}
    write(f'data/{NS}/loot_table/blocks/{name}.json', {'type': 'minecraft:block', 'pools': [pool]})
    pixels = []
    for y in range(16):
        row = bytearray([0])
        for x in range(16):
            shade = (x * 13 + y * 7 + x * y * 3) % 19 - 9
            deposit = any((x - cx) ** 2 + (y - cy) ** 2 <= 3 for cx, cy in [(3, 3), (11, 2), (7, 8), (2, 12), (12, 12)])
            if name.endswith('_ore'):
                stone = 70 if name.startswith('deepslate') else 125
                base = (158, 76, 48) if deposit else (stone, stone, stone)
                if name.startswith('deepslate') and y % 4 == 0 and not deposit:
                    shade -= 14
            elif name == 'raw_aluminum_block':
                base = (170, 91, 61)
                shade += 15 if deposit else -12
            else:
                base = (202, 215, 226)
                shade = -28 if x in (0, 15) or y in (0, 15) else (12 if x == 1 or y == 1 else shade // 3)
            row.extend(max(0, min(255, c + shade)) for c in base)
        pixels.append(bytes(row))
    png(name, pixels)

for name in ['raw_aluminum', 'aluminum_ingot', 'aluminum_nugget']:
    write(f'assets/{NS}/models/item/{name}.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'{NS}:item/{name}'}})
    pixels = []
    for y in range(16):
        row = bytearray([0])
        for x in range(16):
            if name == 'aluminum_ingot':
                inside = 4 <= y <= 11 and 2 + max(0, 7 - y) // 2 <= x <= 13 - max(0, y - 8) // 2
            elif name == 'aluminum_nugget':
                inside = (x - 8) ** 2 + (y - 8) ** 2 <= 15
            else:
                inside = (x - 7) ** 2 + (y - 8) ** 2 < 32 or (x - 11) ** 2 + (y - 5) ** 2 < 9
            shade = 22 - y * 4 + (x * 3 + y * 7) % 11
            base = (181, 99, 66) if name == 'raw_aluminum' else (202, 215, 226)
            row.extend([*(max(0, min(255, c + shade)) for c in base), 255] if inside else [0, 0, 0, 0])
        pixels.append(bytes(row))
    png(name, pixels, 'item', alpha=True)

for kind, tags in {
    'ores/aluminum': aluminum_blocks[:2],
    'ores_in_ground/stone': ['bauxite_ore'],
    'ores_in_ground/deepslate': ['deepslate_bauxite_ore'],
    'raw_materials/aluminum': ['raw_aluminum'],
    'ingots/aluminum': ['aluminum_ingot'],
    'nuggets/aluminum': ['aluminum_nugget'],
    'storage_blocks/aluminum': ['aluminum_block'],
    'storage_blocks/raw_aluminum': ['raw_aluminum_block'],
}.items():
    for registry in (['block', 'item'] if kind.startswith(('ores', 'storage_blocks')) else ['item']):
        write(f'data/c/tags/{registry}/{kind}.json', {'replace': False, 'values': [f'{NS}:{v}' for v in tags]})
for registry in ['block', 'item']:
    for parent, children in {'ores': ['aluminum'], 'ores_in_ground': ['stone', 'deepslate'],
                             'storage_blocks': ['aluminum', 'raw_aluminum'],
                             **({'raw_materials': ['aluminum'], 'ingots': ['aluminum'], 'nuggets': ['aluminum']} if registry == 'item' else {})}.items():
        write(f'data/c/tags/{registry}/{parent}.json', {'replace': False, 'values': [f'#c:{parent}/{v}' for v in children]})

for small, large, tag in [('raw_aluminum', 'raw_aluminum_block', 'raw_materials/aluminum'),
                           ('aluminum_ingot', 'aluminum_block', 'ingots/aluminum'),
                           ('aluminum_nugget', 'aluminum_ingot', 'nuggets/aluminum')]:
    write(f'data/{NS}/recipe/{large}_from_{small}.json', {'type': 'minecraft:crafting_shaped', 'category': 'misc',
          'pattern': ['AAA', 'AAA', 'AAA'], 'key': {'A': {'tag': f'c:{tag}'}}, 'result': {'id': f'{NS}:{large}', 'count': 1}})
    write(f'data/{NS}/recipe/{small}_from_{large}.json', {'type': 'minecraft:crafting_shapeless', 'category': 'misc',
          'ingredients': [{'item': f'{NS}:{large}'}], 'result': {'id': f'{NS}:{small}', 'count': 9}})
for ingredient in ['raw_aluminum', 'bauxite_ore', 'deepslate_bauxite_ore']:
    for recipe_type, time in [('smelting', 200), ('blasting', 100)]:
        write(f'data/{NS}/recipe/aluminum_ingot_from_{ingredient}_{recipe_type}.json', {
            'type': f'minecraft:{recipe_type}', 'category': 'misc', 'ingredient': {'item': f'{NS}:{ingredient}'},
            'result': {'id': f'{NS}:aluminum_ingot'}, 'experience': .7, 'cookingtime': time})
