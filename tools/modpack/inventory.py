"""Download pinned jars once, hash-check, and read ZIP resources only. Run from repo root."""
from pathlib import Path
import hashlib
import io
import json
import re
import tomllib
import urllib.parse
import urllib.request
import zipfile

import argparse

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--fetch', action='store_true', help='Allow downloading missing pinned jars; otherwise use cached jars only.')
parser.add_argument('--output', type=Path, default=Path('build/modpack/ids.json'), help='Inventory destination; use modpack/ids.json explicitly to update source.')
args = parser.parse_args()
ROOT = Path('modpack')
CACHE = Path('build/pi/jars')
CACHE.mkdir(parents=True, exist_ok=True)
records = []
ids = {k: {} for k in ('item', 'block', 'fluid', 'entity', 'advancement', 'dimension')}


def add(kind, identifier, source):
    ids[kind].setdefault(identifier, source)


def scan(z, label):
    names = set(z.namelist())
    for name in sorted(names):
        if name.endswith('/lang/en_us.json'):
            for key in json.loads(z.read(name)):
                m = re.fullmatch(r'(item|block|fluid|entity)\.([a-z0-9_]+)\.([a-z0-9_]+)', key)
                if not m:
                    continue
                kind, ns, path = m.groups()
                identifier = f'{ns}:{path}'
                # Lang keys alone also include tooltips: require an asset for item/block IDs.
                if kind in ('fluid', 'entity') or f'assets/{ns}/models/item/{path}.json' in names or f'assets/{ns}/items/{path}.json' in names:
                    add('item' if kind == 'block' else kind, identifier, label + '!' + name + '#' + key)
                if kind == 'block' and f'assets/{ns}/blockstates/{path}.json' in names:
                    add(kind, identifier, label + '!assets/' + ns + '/blockstates/' + path + '.json')
        m = re.fullmatch(r'data/([^/]+)/(advancement|dimension)/(.+)\.json', name)
        if m:
            add(m[2], f'{m[1]}:{m[3]}', label + '!' + name)
        if '/worldgen/world_preset/' in name and name.endswith('.json'):
            for dimension in json.loads(z.read(name)).get('dimensions', {}):
                add('dimension', dimension, label + '!' + name)
        if '/tags/fluid/' in name and name.endswith('.json'):
            for value in json.loads(z.read(name)).get('values', []):
                if isinstance(value, str) and not value.startswith('#'):
                    add('fluid', value, label + '!' + name)
    if 'META-INF/jarjar/metadata.json' in names:
        for child in json.loads(z.read('META-INF/jarjar/metadata.json'))['jars']:
            scan(zipfile.ZipFile(io.BytesIO(z.read(child['path']))), label + '!' + child['path'])


for meta in sorted((ROOT / 'mods').glob('*.pw.toml')):
    d = tomllib.loads(meta.read_text())
    cf = d['update']['curseforge']
    file_id = cf['file-id']
    # CurseForge CDN path derived from packwiz's file ID and original filename.
    url = d['download'].get('url') or f'https://edge.forgecdn.net/files/{file_id // 1000}/{file_id % 1000:03d}/{urllib.parse.quote(d["filename"])}'
    jar = CACHE / d['filename']
    if not jar.exists():
        if not args.fetch:
            raise FileNotFoundError(f'{jar}: cache missing; use --fetch explicitly to download')
        with urllib.request.urlopen(url, timeout=120) as response:
            data = response.read()
        assert hashlib.new(d['download']['hash-format'], data).hexdigest() == d['download']['hash'], jar
        jar.write_bytes(data)
    assert hashlib.new(d['download']['hash-format'], jar.read_bytes()).hexdigest() == d['download']['hash'], jar
    scan(zipfile.ZipFile(jar), jar.name)
    records.append(dict(name=d['name'], project=cf['project-id'], file=file_id, jar=jar.name,
                        side=d['side'], url=url, hash_format=d['download']['hash-format'], hash=d['download']['hash']))

# Unpublished Starfold resources, including Materials aluminum, are sourced locally;
# they have no external packwiz pin and must not depend on a cached provider jar.
for resource_root in map(Path, ('src/main/resources', 'worlds/src/main/resources', 'materials/src/main/resources', 'biotech/src/main/resources')):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w') as z:
        for path in resource_root.rglob('*.json'):
            z.writestr(str(path.relative_to(resource_root)), path.read_bytes())
    scan(zipfile.ZipFile(buffer), str(resource_root))
output = {'note': 'Static resource evidence, not a runtime registry dump. Utility mods need no quest IDs. Nested jars are ZIP-read only.',
          'mods': records, 'ids': {k: dict(sorted(v.items())) for k, v in ids.items()}}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + '\n')
print(f'Verified {len(records)} pinned jars; ID evidence: ' + ', '.join(f'{k}={len(v)}' for k, v in ids.items()))
