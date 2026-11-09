"""Original Host geometry, animations and data; deterministic, standard library only. --check never writes.
Entity textures come from tools/organic-textures.py, painted over host-uv-layout.json."""
import argparse
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources'
NS = 'starfold_biotech'
ASSETS = f'assets/{NS}'
OUTPUTS = {}
UV = {}
# Geo coordinates are pixels (16 = 1 block); renderer scale stays 1.
COLORS = {'flesh': (108, 28, 43, 255), 'shell': (67, 43, 31, 255),
          'tendon': (78, 22, 36, 255), 'engine': (246, 115, 155, 255),
          'membrane': (186, 64, 99, 255), 'tip': (27, 13, 21, 255),
          'rock': (43, 38, 36, 255)}


def resource(path, value):
    OUTPUTS[path] = (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode()


def model(name, specs):
    bones = []
    islands = []
    for spec in specs:
        bone, parent, pivot, box, material = spec[:5]
        value = {'name': bone, 'pivot': pivot}
        if len(spec) == 6:
            value['rotation'] = spec[5]
        if parent:
            value['parent'] = parent
        if box:
            index = len(islands)
            x, y = (index % 8) * 32, (index // 8) * 32
            assert y + 32 <= 256
            # Each face deliberately shares the same square; no box-UV spill on large cubes.
            uv = {face: {'uv': [x, y], 'uv_size': [32, 32]} for face in ('north', 'south', 'east', 'west', 'up', 'down')}
            value['cubes'] = [{'origin': origin, 'size': size, 'uv': uv} for origin, size in box]
            islands.append({'bone': bone, 'material': material, 'color_rgba': COLORS[material],
                            'rectangle': [x, y, x + 32, y + 32]})
        bones.append(value)
    names = {b['name'] for b in bones}
    assert len(names) == len(bones)
    assert all(not b.get('parent') or b['parent'] in names for b in bones)
    resource(f'{ASSETS}/geo/{name}.geo.json', {'format_version': '1.12.0', 'minecraft:geometry': [{
        'description': {'identifier': f'geometry.{name}', 'texture_width': 256, 'texture_height': 256,
                        'visible_bounds_width': 24 if name == 'host' else 6,
                        'visible_bounds_height': 20 if name == 'host' else 6,
                        'visible_bounds_offset': [0, 6 if name == 'host' else 1.5, 0]}, 'bones': bones}]})
    UV[name] = islands
    return names


def animation(name, animations, names):
    for data in animations.values():
        assert set(data['bones']) <= names
    resource(f'{ASSETS}/animations/{name}.animation.json', {'format_version': '1.8.0', 'animations': animations})


def clip(length, bones, loop=False):
    return {'loop': loop, 'animation_length': length, 'bones': bones}


def lobe(cx, y, cz, width, height, depth):
    # Five overlapping cross sections bevel the silhouette without a giant box or extra bones.
    return [([round(cx - width * taper / 2, 3), round(y + height * start, 3), round(cz - depth * taper / 2, 3)],
             [round(width * taper, 3), round(height * band, 3), round(depth * taper, 3)])
            for start, band, taper in [(0, .18, .65), (.16, .22, .88), (.36, .28, 1), (.62, .22, .88), (.82, .18, .65)]]


def ring(cx, y, cz, half_width, half_depth, height, wall):
    # Four walls leave the nozzle open from below, so the glowing throat shows.
    return [([cx - half_width, y, cz - half_depth], [half_width * 2, height, wall]),
            ([cx - half_width, y, cz + half_depth - wall], [half_width * 2, height, wall]),
            ([cx - half_width, y, cz - half_depth + wall], [wall, height, half_depth * 2 - wall * 2]),
            ([cx + half_width - wall, y, cz - half_depth + wall], [wall, height, half_depth * 2 - wall * 2])]


# A squat meteorite hides an old, egg-bloated parasite; front is -Z.
specs = [('root', None, [0, 0, 0], None, None),
         ('body', 'root', [0, 28, 8], None, None),
         ('abdomen', 'body', [-4, 52, 18], lobe(-4, 18, 18, 88, 104, 94), 'flesh', [3, -4, -3]),
         ('thorax', 'body', [0, 66, -20], lobe(0, 30, -20, 64, 72, 44), 'flesh'),
         ('eggs', 'abdomen', [0, 64, 24],
          [box for x, y, z, w, h, d in [(-23, 94, 24, 29, 32, 31), (8, 102, 17, 25, 27, 28),
           (24, 85, 35, 30, 36, 32), (-24, 57, 57, 31, 36, 26), (13, 30, 59, 34, 32, 24),
           (-38, 28, 30, 24, 30, 28), (32, 22, 27, 26, 33, 30),
           (-24, 28, -37, 25, 30, 24), (22, 36, -38, 24, 28, 25),
           (-8, 69, -37, 24, 27, 24), (9, 48, -42, 19, 23, 20)]
           for box in lobe(x, y, z, w, h, d)], 'membrane'),
         ('neck', 'thorax', [0, 68, -35], lobe(0, 57, -45, 27, 26, 30), 'tendon', [-8, -3, 2]),
         ('head', 'neck', [0, 66, -59], lobe(0, 43, -60, 32, 47, 28), 'membrane'),
         ('mouth', 'head', [0, 64, -74], [([-6, 47, -75], [12, 34, 3])], 'tip'),
         ('lips', 'mouth', [0, 64, -74], [([-12, 46, -76], [6, 36, 4]),
          ([6, 46, -76], [6, 36, 4]), ([-7, 43, -75], [14, 5, 4]), ([-7, 81, -75], [14, 5, 4])], 'tendon'),
         ('teeth', 'mouth', [0, 64, -74],
          [([x, 49 + i * 6, -77], [4, 2, 3]) for x in (-6, 2) for i in range(5)], 'shell'),
         ('shell', 'body', [0, 68, 8], None, None),
         ('engine', 'body', [0, 60, 4], None, None),
         ('roots', 'root', [0, 0, 0], None, None),
         ('hanging_roots', 'body', [0, 28, 8], None, None)]
# Big staggered chunks, not chitin plates. The openings expose eggs and flank organs.
rocks = [(-17, 79, -6, 101, 58, 109, -5, -8, 7),
         (37, 67, 10, 66, 63, 101, 6, 7, -9),
         (-38, 9, -42, 59, 106, 49, -5, -9, -8),
         (40, 12, -39, 55, 92, 53, 7, 8, 9),
         (-32, 8, 47, 71, 105, 45, -5, 8, -6),
         (34, 13, 48, 65, 98, 46, 6, -10, 8),
         (-54, 8, 3, 39, 42, 67, 4, -7, -9),
         (54, 10, 8, 37, 36, 63, -7, 6, 6)]
for i, (x, y, z, w, h, d, rx, ry, rz) in enumerate(rocks):
    specs.append((f'rock_{i}', 'shell', [x, y + h / 2, z], lobe(x, y, z, w, h, d), 'rock', [rx, ry, rz]))
specs.append(('thrusters', 'body', [0, 64, 10], None, None))
for side in (-1, 1):
    s = 'l' if side < 0 else 'r'
    cy, cz = (63, 0) if side < 0 else (59, 5)
    specs += [(f'engine_{s}', 'engine', [side * 50, cy, cz], lobe(side * 51, cy - 43, cz, 44, 86, 88), 'membrane', [side * 3, side * 4, side * -3]),
              (f'ribs_{s}', f'engine_{s}', [side * 51, cy, cz],
               [box for j, y in enumerate(range(cy - 36, cy + 36, 9))
                for box in lobe(side * 51, y, cz, 46 - abs(j - 3.5) * 2, 4, 92 - abs(j - 3.5) * 5)], 'tendon'),
              (f'blaze_{s}', f'engine_{s}', [side * 51, cy, cz],
               [box for j, y in enumerate(range(cy - 31, cy + 36, 9))
                for box in lobe(side * 51, y, cz, 49 - abs(j - 3.5) * 1.5, 6, 99 - abs(j - 3.5) * 4)], 'engine'),
              (f'vent_{s}', f'engine_{s}', [side * 51, cy - 40, cz], lobe(side * 51, cy - 51, cz, 28, 19, 46), 'membrane'),
              (f'legs_{s}', 'body', [side * 34, 24, 0],
               [box for z in (-32, 31) for box in lobe(side * 43, 0, z, 26, 30, 28)], 'tendon'),
              # Phase-two thrusters stand off the flanks so the flight organs read apart from the abdomen.
              (f'thruster_{s}', 'thrusters', [side * 66, 64, 10],
               lobe(side * 100, 40, 10, 30, 62, 46) + [([side * 100 - 5, 37, 5], [10, 3, 10])], 'engine'),
              (f'thruster_ribs_{s}', f'thruster_{s}', [side * 66, 64, 10],
               lobe(side * 80, 62, 10, 34, 14, 18) + lobe(side * 100, 40, 10, 46, 5, 62)
               + [box for y in (52, 66, 80, 94) for box in lobe(side * 100, y, 10, 35, 5, 51)]
               + ring(side * 100, 28, 10, 8, 8, 14, 3) + ring(side * 100, 16, 10, 11, 11, 13, 3)
               + ring(side * 100, 13, 10, 12, 12, 4, 3), 'tendon')]
# Eight two-joint roots spread to eight blocks, with separate gravity-dangling flight roots.
for i in range(8):
    angle = i * 45 + 12
    specs += [(f'ground_{i}', 'roots', [0, 0, 0],
               [([-9, 1, 30], [18, 14, 40]), ([-7, 0, 66], [14, 10, 22])], 'tendon', [0, angle, 0]),
              (f'ground_tip_{i}', f'ground_{i}', [0, 4, 84],
               [([-6, 0, 82], [12, 8, 24]), ([-4, 0, 104], [8, 5, 24])], 'flesh', [0, 18, 0])]
    x, z = round(math.sin(math.radians(angle)) * 36, 3), round(math.cos(math.radians(angle)) * 36 + 8, 3)
    length = (48, 63, 53, 72, 58, 67, 45, 61)[i]
    specs += [(f'hang_{i}', 'hanging_roots', [x, 30, z],
               lobe(x, 30 - length, z, 7 + i % 3, length + 2, 8), 'tendon', [i % 3 * 3 - 3, 0, i % 4 * 2 - 3]),
              (f'hang_tip_{i}', f'hang_{i}', [x, 32 - length, z],
               lobe(x + 2, -length - 10 - i * 3, z + 3, 4, 44 + i * 3, 5), 'tendon', [4, 0, -5])]
names = model('host', specs)
pulse = {'scale': ['1 + math.sin(query.anim_time * 120) * 0.025', '1 + math.sin(query.anim_time * 120) * 0.025', 1]}
idle = {'abdomen': {'scale': ['1 + math.sin(query.anim_time * 60) * 0.015',
                             '1 + math.sin(query.anim_time * 60) * 0.022', 1]},
        'shell': {'rotation': [0, 0, 'math.sin(query.anim_time * 60) * 0.4']},
        'neck': {'rotation': ['math.sin(query.anim_time * 60 - 30) * 1.2', 0, 0]}}
roots = {'body': {'scale': {'0.0': [1, 1, 1], '0.35': [1.035, .94, 1.035], '0.7': [1, 1.04, 1], '1.2': [1, 1, 1]}},
         'head': {'rotation': {'0.0': [0, 0, 0], '0.35': [-6, 0, -3], '1.2': [0, 0, 0]}}}
vent = {'abdomen': {'scale': {'0.0': [1, 1, 1], '0.8': [1.04, 1.02, 1.04], '1.0': [.98, 1, .98], '1.5': [1, 1, 1]}},
        'head': {'rotation': {'0.0': [0, 0, 0], '0.8': [-7, 0, 2], '1.0': [6, 0, 0], '1.5': [0, 0, 0]}}}
fly = {'body': {'position': [0, 'math.sin(query.anim_time * 90) * 2', 0],
                'rotation': ['math.sin(query.anim_time * 90) * 1.5', 0, 'math.sin(query.anim_time * 90) * 1']}}
takeoff = {'body': {'rotation': {'0.0': [0, 0, 0], '0.5': [-4, 0, 0], '2.0': [0, 0, 0]}}}
dive = {'body': {'rotation': {'0.0': [0, 0, 0], '0.4': [-48, 0, 0], '1.45': [-48, 0, 0],
                             '1.8': [12, 0, 0], '2.25': [0, 0, 0]}}}
death = {'body': {'rotation': {'0.0': [0, 0, 0], '0.65': [6, 0, -5], '1.6': [10, 0, 8], '2.2': [4, 0, 13], '3.0': [4, 0, 13]},
                  'position': {'0.0': [0, 0, 0], '1.6': [0, 0, 0], '2.2': [0, -16, 0], '3.0': [0, -16, 0]},
                  'scale': {'0.0': [1, 1, 1], '1.6': [1, 1, 1], '2.2': [1.08, .72, 1.06], '3.0': [1.08, .72, 1.06]}},
         'neck': {'rotation': {'0.0': [0, 0, 0], '2.2': [24, 0, -12], '3.0': [24, 0, -12]}}}
shedding = {'body': {'scale': {'0.0': [1, 1, 1], '0.35': [1.04, 1.04, 1.04], '0.55': [1, 1, 1], '2.0': [1, 1, 1]}}}
for i, (x, y, z, w, h, d, rx, ry, rz) in enumerate(rocks):
    dx, dz = (1 if x > 0 else -1) * (64 + i * 5), z * 1.5
    shedding[f'rock_{i}'] = {'position': {'0.0': [0, 0, 0], '0.3': [x * .07, 3, z * .07],
                                         '0.85': [dx * .65, 32 + i * 2, dz * .65], '2.0': [dx, -y - h, dz]},
                             'rotation': {'0.0': [0, 0, 0], '2.0': [70 + i * 13, i * 21, dx]}}
for side in (-1, 1):
    s = 'l' if side < 0 else 'r'
    idle[f'engine_{s}'] = {'scale': ['1 + math.sin(query.anim_time * 60 - 40) * 0.015', 1, 1]}
    fly[f'engine_{s}'] = {'scale': pulse['scale']}
    fly[f'blaze_{s}'] = {'scale': [1, '1 + math.sin(query.anim_time * 720) * 0.08', 1]}
    vent[f'engine_{s}'] = {'scale': {'0.0': [1, 1, 1], '0.8': [1.12, 1.08, 1.12], '1.0': [.94, .96, .94], '1.5': [1, 1, 1]}}
    vent[f'vent_{s}'] = {'scale': {'0.0': [1, 1, 1], '0.8': [1.3, .8, 1.3], '1.0': [1.65, 2.4, 1.65], '1.5': [1, 1, 1]},
                           'position': {'0.0': [0, 0, 0], '0.8': [0, 0, 0], '1.0': [0, -8, 0], '1.5': [0, 0, 0]}}
    takeoff[f'engine_{s}'] = {'scale': {'0.0': [1, 1, 1], '0.7': [1.1, 1.08, 1.1], '2.0': [1, 1, 1]}}
    takeoff[f'blaze_{s}'] = {'scale': {'0.0': [.2, .2, .2], '0.8': [1.08, 1, 1.08], '2.0': [1, 1, 1]}}
    death[f'blaze_{s}'] = {'scale': {'0.0': [1, 1, 1], '0.3': [.3, .3, .3], '0.45': [1, 1, 1],
                                    '0.7': [.2, .2, .2], '0.85': [.8, .8, .8], '1.1': [0, 0, 0], '3.0': [0, 0, 0]}}
    death[f'engine_{s}'] = {'scale': {'0.0': [1, 1, 1], '1.2': [.8, .85, .8], '3.0': [.7, .8, .7]}}
    takeoff[f'thruster_{s}'] = {'scale': {'0.0': [.2, .2, .2], '0.8': [1.1, 1.1, 1.1], '2.0': [1, 1, 1]},
                                'position': {'0.0': [-side * 14, 0, 0], '0.8': [0, 0, 0]}}
    fly[f'thruster_{s}'] = {'rotation': ['math.sin(query.anim_time * 90) * 4', 0, side * 6]}
    dive[f'thruster_{s}'] = {'rotation': {'0.0': [0, 0, side * 6], '0.4': [50, 0, side * 6],
                                          '1.45': [50, 0, side * 6], '2.25': [0, 0, side * 6]}}
    death[f'thruster_{s}'] = {'scale': {'0.0': [1, 1, 1], '0.3': [.7, .7, .7], '0.45': [1, 1, 1],
                                        '0.7': [.6, .6, .6], '1.1': [.5, .5, .5], '3.0': [.5, .5, .5]}}
for i in range(8):
    idle[f'ground_{i}'] = {'scale': [1, f'1 + math.sin(query.anim_time * 60 - {i * 35}) * 0.06', 1]}
    idle[f'ground_tip_{i}'] = {'scale': [1, f'1 + math.sin(query.anim_time * 60 - {i * 35 + 40}) * 0.08', 1]}
    for joint, delay in [('ground', 0), ('ground_tip', .12)]:
        roots[f'{joint}_{i}'] = {'position': {'0.0': [0, 0, 0], str(round(.3 + delay + i * .025, 3)): [0, 6, 0],
                                             str(round(.6 + delay + i * .025, 3)): [0, 0, 0], '1.2': [0, 0, 0]}}
    for joint, bend in [('hang', 7), ('hang_tip', 12)]:
        bone = f'{joint}_{i}'
        fly[bone] = {'rotation': [f'math.sin(query.anim_time * 90 - {i * 40}) * {bend}', 0,
                                  f'math.sin(query.anim_time * 90 - {i * 40 + 60}) * {bend * .6}']}
        takeoff[bone] = {'rotation': {'0.0': [-20, 0, 12], '1.2': [10, 0, -6], '2.0': [0, 0, 0]}}
        dive[bone] = {'rotation': {'0.0': [0, 0, 0], '0.5': [38, 0, 0], '1.5': [38, 0, 0], '1.9': [-18, 0, 0], '2.25': [0, 0, 0]}}
        death[bone] = {'rotation': {'0.0': [0, 0, 0], '1.6': [12, 0, 8], '2.2': [42, 0, 20], '3.0': [42, 0, 20]}}
animation('host', {
    'idle': clip(6, idle, True),
    'roots': clip(1.2, roots),
    'vent': clip(1.5, vent),
    'spit': clip(1, {'mouth': {'scale': {'0.0': [1, 1, 1], '0.25': [1.4, 1.12, 1], '0.45': [.8, 1.25, 1], '1.0': [1, 1, 1]}},
                     'neck': {'rotation': {'0.0': [0, 0, 0], '0.3': [-12, 0, 0], '0.5': [14, 0, 0], '1.0': [0, 0, 0]}}}),
    'shell_break': clip(2, shedding),
    'takeoff': clip(2, takeoff),
    'fly': clip(4, fly, True),
    'dive': clip(2.25, dive),
    'egg_drop': clip(1, {'abdomen': {'scale': {'0.0': [1, 1, 1], '0.35': [.9, .92, .9], '0.6': [1.06, 1.02, 1.06], '1.0': [1, 1, 1]}},
                         'eggs': {'position': {'0.0': [0, 0, 0], '0.45': [0, -7, 2], '1.0': [0, 0, 0]}}}),
    'death': clip(3, death)}, names)

names = model('host_sac', [('root', None, [0, 0, 0], None, None),
    ('sac', 'root', [0, 8, 0], lobe(0, 6, 0, 36, 38, 36), 'flesh'),
    ('collar', 'root', [0, 0, 0], lobe(0, 0, 0, 40, 10, 40), 'shell'),
    ('cap', 'sac', [0, 40, 0], lobe(0, 39, 0, 20, 9, 20), 'membrane')])
animation('host_sac', {'idle': clip(2, {'sac': pulse}, True), 'rupture': clip(1, {
    'sac': {'scale': {'0.0': [1, 1, 1], '0.4': [1.3, 1.1, 1.3], '1.0': [.2, .1, .2]}},
    'cap': {'position': {'0.0': [0, 0, 0], '1.0': [0, 16, 0]}}})}, names)

names = model('host_drone', [('root', None, [0, 6, 0], None, None),
    ('body', 'root', [0, 6, 0], lobe(0, 0, 0, 12, 12, 14), 'flesh'),
    ('shell', 'body', [0, 10, 0], lobe(0, 8, 0, 12, 5, 12), 'shell'),
    ('wing_l', 'body', [-4, 8, 0], [([-8, 7, -6], [4, 2, 12])], 'membrane'),
    ('wing_r', 'body', [4, 8, 0], [([4, 7, -6], [4, 2, 12])], 'membrane')])
animation('host_drone', {'fly': clip(.4, {
    'wing_l': {'rotation': [0, 0, 'math.sin(query.anim_time * 1800) * 45']},
    'wing_r': {'rotation': [0, 0, '-math.sin(query.anim_time * 1800) * 45']}}, True)}, names)

resource(f'{ASSETS}/models/item/host_ganglion.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'{NS}:item/host_ganglion'}})
for name in ('host', 'host_sac', 'host_drone'):
    resource(f'{ASSETS}/models/item/{name}_spawn_egg.json', {'parent': 'minecraft:item/template_spawn_egg'})

resource(f'data/{NS}/loot_table/entities/host.json', {'type': 'minecraft:entity', 'pools': [
    {'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'{NS}:host_ganglion', 'functions': [{'function': 'minecraft:set_count', 'count': 4}]}]},
    {'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'{NS}:chitin_plate', 'functions': [{'function': 'minecraft:set_count', 'count': {'type': 'minecraft:uniform', 'min': 2, 'max': 4}}]}]}]})
for name in ('host_sac', 'host_drone'):
    resource(f'data/{NS}/loot_table/entities/{name}.json', {'type': 'minecraft:entity', 'pools': []})

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    # Machine-readable replacement guide is kept alongside the generator, not shipped in the mod.
    OUTPUTS['../../../tools/host-uv-layout.json'] = (json.dumps(UV, ensure_ascii=False, indent=2) + '\n').encode()
    for path, data in OUTPUTS.items():
        target = ROOT / path
        if args.check:
            assert target.is_file() and target.read_bytes() == data, f'Stale generated asset: {target}'
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    print(f'Host assets: {len(OUTPUTS)} deterministic files {"verified" if args.check else "generated"}.')
