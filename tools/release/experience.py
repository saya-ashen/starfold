"""Package built player jars and a Modrinth Experience pack; never publishes.

Run after ./gradlew check build. Optional --verify-downloads downloads the exact
pinned dependencies and checks their size, hashes and archive integrity.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import shutil
import tomllib
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
MODULES = {'starfold': '.', **{f'starfold_{m}': m for m in ('worlds', 'extras', 'atlas', 'materials', 'biotech')}}
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('version')
parser.add_argument('--output', type=Path, default=ROOT / 'build/release')
parser.add_argument('--verify-downloads', action='store_true')
args = parser.parse_args()
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=True)
pins = json.loads(Path(__file__).with_name('experience-dependencies.json').read_text())
for entry in pins['files']:
    if args.verify_downloads:
        request = urllib.request.Request(entry['downloads'][0], headers={'User-Agent': 'Starfold-release-tools/1'})
        with urllib.request.urlopen(request, timeout=90) as response:
            data = response.read()
        assert len(data) == entry['fileSize'], entry['path']
        for algorithm, expected in entry['hashes'].items():
            assert hashlib.new(algorithm, data).hexdigest() == expected, entry['path']
        with zipfile.ZipFile(io.BytesIO(data)) as jar:
            assert jar.testzip() is None, entry['path']
        print('Verified dependency:', entry['path'])

jars = []
for mod, directory in MODULES.items():
    name = f'{mod}-{args.version}.jar'
    source = ROOT / directory / 'build/libs' / name
    with zipfile.ZipFile(source) as jar:
        assert jar.testzip() is None, name
        metadata = tomllib.loads(jar.read('META-INF/neoforge.mods.toml').decode())
        assert any(m['modId'] == mod and m['version'] == args.version for m in metadata['mods']), name
        assert not any(n.startswith(('dev/starfold/monsoon/', 'dev/starfold/probes/', 'dev/starfold/devtools/')) for n in jar.namelist()), name
    destination = output / name
    shutil.copyfile(source, destination)
    jars.append(destination)

installation = '''Starfold Experience — Starward

Import the .mrpack into a launcher supporting Modrinth packs, then create a NEW
world with the Starfold: Experience world type. Minecraft 1.21.1 and NeoForge
21.1.247 are pinned. Worlds from Starfold 0.2.0 are not supported.

The pack includes Core, Worlds, Extras, Atlas, Materials and Biotech. Its pinned
external dependencies are downloaded by the launcher. Developer probes,
profilers, the seasonal display module, and the unfinished survival quest pack
are not included. Materials balance and the Host encounter remain provisional.

For manual installation, install the core jar plus Sable 2.0.5 or 2.0.6.
Worlds adds experience planets. Extras requires Worlds, Create and Create
Aeronautics; Immersive Aircraft adds the starter aircraft. Atlas adds maps.
Biotech requires Worlds and exactly GeckoLib 4.8.2. Keep all Starfold modules
on this release version and remove old Starfold jars before installing.

Single-player alpha; vanilla renderer required, no shader packs. Full survival
progression and multiplayer are not accepted. This Experience pack provides a
starter ship; it does not contain the unfinished survival quest progression.
'''
(output / 'INSTALL.txt').write_text(installation)
index = {'formatVersion': 1, 'game': 'minecraft', 'versionId': args.version,
         'name': 'Starfold Experience',
         'summary': 'Starward: Atlas maps, modular planet content and a starter ship. Single-player alpha; create a new world.',
         'files': pins['files'], 'dependencies': pins['dependencies']}
pack = output / f'Starfold-Experience-{args.version}.mrpack'
with zipfile.ZipFile(pack, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    archive.writestr('modrinth.index.json', json.dumps(index, indent=2) + '\n')
    archive.writestr('overrides/STARFOLD-INSTALL.txt', installation)
    for jar in jars:
        archive.write(jar, f'overrides/mods/{jar.name}')
with zipfile.ZipFile(pack) as archive:
    assert archive.testzip() is None
    assert len([n for n in archive.namelist() if n.startswith('overrides/mods/')]) == len(MODULES)
assets = jars + [pack, output / 'INSTALL.txt']
(output / 'SHA256SUMS.txt').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in assets))
print('Packaged', len(jars), 'player modules and', pack.name)
