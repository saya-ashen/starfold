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

CURSEFORGE_MODULES = {'starfold': (1739128, 9792443), 'starfold_atlas': (1821211, 9792446),
                      'starfold_mining': (1821221, 9792452)}


def export(side, version, output, curseforge_modules=None):
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
    jars = []
    if curseforge_modules is None:
        for mod, root in MODULES.items():
            jar = root / 'build/libs' / f'{mod}-{version}.jar'
            with ZipFile(jar) as archive:
                metadata = tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode())
                if [(entry['modId'], entry['version']) for entry in metadata['mods']] != [(mod, version)]:
                    raise ValueError(f'Unexpected mod ID or version in {jar}')
            jars.append(jar)
    else:
        for project, file in curseforge_modules.values():
            if project in {entry['projectID'] for entry in files}:
                raise ValueError(f'Duplicate CurseForge project ID: {project}')
            files.append({'projectID': project, 'fileID': file, 'required': True})
    manifest = {
        'manifestType': 'minecraftModpack', 'manifestVersion': 1,
        'name': pack['name'], 'version': version if curseforge_modules is not None else pack['version'],
        'author': pack['author'],
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
    parser.add_argument('--version', default='0.4.0-alpha.3', help='Gradle candidateVersion used for the jars')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--curseforge-worlds-file-id', type=int, help='Approved file containing Worlds and Biotech')
    parser.add_argument('--curseforge-materials-file-id', type=int, help='Approved Materials file with schematic blocks')
    args = parser.parse_args()
    module_pins = None
    if args.curseforge_worlds_file_id is not None or args.curseforge_materials_file_id is not None:
        worlds, materials = args.curseforge_worlds_file_id, args.curseforge_materials_file_id
        if not worlds or not materials or worlds < 1 or materials < 1:
            parser.error('both CurseForge file IDs must be positive integers')
        if worlds == 9734410 or materials == 9734415:
            parser.error('the original Worlds and Materials files lack the content required by this pack')
        module_pins = {**CURSEFORGE_MODULES, 'starfold_worlds': (1821220, worlds),
                       'starfold_materials': (1821222, materials)}
    export(args.side, args.version, args.output, module_pins)
