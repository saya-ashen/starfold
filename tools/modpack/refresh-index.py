"""Refresh the packwiz index from runtime source only; never downloads or exports."""
from pathlib import Path
import hashlib
import re

pack = Path(__file__).resolve().parents[2] / 'modpack'
files = sorted(path for directory in ('config', 'kubejs', 'mods', 'schematics')
               for path in (pack / directory).rglob('*')
               if path.is_file() and (directory != 'mods' or path.name.endswith('.pw.toml')))
lines = ['hash-format = "sha256"', '']
for path in files:
    lines += ['[[files]]', f'file = "{path.relative_to(pack).as_posix()}"',
              f'hash = "{hashlib.sha256(path.read_bytes()).hexdigest()}"']
    if path.name.endswith('.pw.toml'):
        lines.append('metafile = true')
    lines.append('')
index = ('\n'.join(lines)).encode()
(pack / 'index.toml').write_bytes(index)
metadata = pack / 'pack.toml'
text = metadata.read_text()
text, count = re.subn(r'(?m)^hash = "[0-9a-f]{64}"$',
                     f'hash = "{hashlib.sha256(index).hexdigest()}"', text)
if count != 1:
    raise ValueError('Expected exactly one index hash in pack.toml')
metadata.write_text(text)
print(f'Refreshed {len(files)} runtime entries and pack index hash')
