"""Build a local CurseForge-format archive from the pinned Packwiz source and Starfold jars."""
import argparse
import hashlib
import json
from pathlib import Path
import tomllib
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[2]
PACK = ROOT / 'modpack'
MODULES = {'starfold': ROOT, **{
    f'starfold_{module}': ROOT / module
    for module in ('worlds', 'atlas', 'materials', 'mining', 'biotech')
}}


def export(side, version, output):
    pack = tomllib.loads((PACK / 'pack.toml').read_text())
    index_path = PACK / pack['index']['file']
    if hashlib.sha256(index_path.read_bytes()).hexdigest() != pack['index']['hash']:
        raise ValueError('Stale pack index hash')
    index = tomllib.loads(index_path.read_text())
    entries = []
    files = []
    for entry in index['files']:
        source = PACK / entry['file']
        content = source.read_bytes()
        if hashlib.sha256(content).hexdigest() != entry['hash']:
            raise ValueError(f'Stale index entry: {source}')
        if entry.get('metafile'):
            mod = tomllib.loads(content.decode())
            if mod['side'] in ('both', side):
                pin = mod['update']['curseforge']
                files.append({'projectID': pin['project-id'], 'fileID': pin['file-id'], 'required': True})
        else:
            entries.append((f"overrides/{entry['file']}", content))
    jars = [root / 'build/libs' / f'{mod}-{version}.jar' for mod, root in MODULES.items()]
    for jar, mod in zip(jars, MODULES, strict=True):
        with ZipFile(jar) as archive:
            metadata = tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode())
            if [(entry['modId'], entry['version']) for entry in metadata['mods']] != [(mod, version)]:
                raise ValueError(f'Unexpected mod ID or version in {jar}')
    manifest = {
        'manifestType': 'minecraftModpack', 'manifestVersion': 1,
        'name': pack['name'], 'version': pack['version'], 'author': pack['author'],
        'minecraft': {'version': pack['versions']['minecraft'], 'modLoaders': [
            {'id': f"neoforge-{pack['versions']['neoforge']}", 'primary': True}]},
        'files': files, 'overrides': 'overrides',
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, 'w', ZIP_DEFLATED) as archive:
        archive.writestr('manifest.json', json.dumps(manifest, indent=2) + '\n')
        for relative, content in entries:
            archive.writestr(relative, content)
        for jar in jars:
            archive.write(jar, f'overrides/mods/{jar.name}')
    print(f'{output}: {len(files)} pinned projects, {len(jars)} Starfold modules')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--side', choices=['client', 'server'], required=True)
    parser.add_argument('--version', default='0.4.0-alpha.1', help='Gradle candidateVersion used for the jars')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    export(args.side, args.version, args.output)
