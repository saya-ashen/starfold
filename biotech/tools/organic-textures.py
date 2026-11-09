"""Build Biotech organic block textures from the AI-generated sources in biotech/art/source.

Writes the shipped 16x textures (animated where the block pulses) into the mod resources and the
128 px versions into biotech/hd-pack, an optional resource pack. Host entity atlases are painted from one
tile per material over tools/host-uv-layout.json: 32 px tiles in the mod, 128 px tiles in the pack. Needs Pillow and numpy:
  nix shell --impure --expr '(builtins.getFlake "nixpkgs").legacyPackages.x86_64-linux.python3.withPackages (p: [p.pillow p.numpy])' -c python3 biotech/tools/organic-textures.py
"""
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image

BIOTECH = Path(__file__).resolve().parent.parent
SOURCE = BIOTECH / 'art/source'
MOD = BIOTECH / 'src/main/resources/assets/starfold_biotech/textures/block'
HD = BIOTECH / 'hd-pack/assets/starfold_biotech/textures/block'
ENTITY = 'assets/starfold_biotech/textures/entity'
FRAMES = 8
SAC_FRAMES = 12


def rgb(path):
    return np.asarray(Image.open(path).convert('RGB')).astype(float)


def to16(px, size=16):
    """Area-resample a pixel-art grid to size x size, then snap every pixel back to the source palette."""
    if px.shape[0] == size:
        return px
    small = np.asarray(Image.fromarray(px.astype('uint8')).resize((size, size), Image.BOX)).astype(float)
    # Sampled generations carry slight colour noise; 12 colours keep the pixel-art look.
    return snap(small, px, 12)


def snap(values, source, colours):
    """Replace every pixel with the nearest of the source's main colours."""
    image = Image.fromarray(source.astype('uint8'))
    palette = np.unique(np.asarray(image.quantize(colours, dither=Image.Dither.NONE).convert('RGB')).reshape(-1, 3), axis=0)
    distance = ((values[:, :, None, :] - palette[None, None]) ** 2).sum(-1)
    return palette[distance.argmin(-1)]


def icon(path, size):
    """Cut a generated item drawing off its flat background and area-resample it to size x size with hard alpha."""
    a = rgb(path)
    background = np.median(a[:16, :16].reshape(-1, 3), axis=0)
    # Background is what the flat colour reaches from the border, so light parts inside the outline survive.
    flat = np.abs(a - background).sum(-1) <= 60
    outside = np.zeros_like(flat)
    outside[[0, -1], :] = outside[:, [0, -1]] = True
    outside &= flat
    while True:
        grown = outside.copy()
        grown[1:] |= outside[:-1]; grown[:-1] |= outside[1:]; grown[:, 1:] |= outside[:, :-1]; grown[:, :-1] |= outside[:, 1:]
        grown &= flat
        if (grown == outside).all():
            break
        outside = grown
    mask = ~outside
    ys, xs = np.nonzero(mask)
    side = int(max(np.ptp(ys), np.ptp(xs)) * 1.06)
    left, top = (xs.min() + xs.max() - side) // 2, (ys.min() + ys.max() - side) // 2
    def fit(values):
        image = Image.fromarray(values.astype('uint8')).crop((left, top, left + side, top + side))
        return np.asarray(image.resize((size, size), Image.BOX)).astype(float)
    cover = fit(mask * 255.) / 255
    colour = fit(a * mask[..., None]) / np.maximum(cover, 1e-6)[..., None]
    solid = cover > .5
    if size == 16:
        colour = snap(colour, a[mask][None], 12).astype(float)
        # Downsampling loses the drawing's dark outline; vanilla items keep one, so redraw it on the silhouette edge.
        inner = np.pad(solid, 1)
        inner = inner[:-2, 1:-1] & inner[2:, 1:-1] & inner[1:-1, :-2] & inner[1:-1, 2:]
        colour[solid & ~inner] *= .35
    return np.dstack([colour, solid * 255.])


def vein_mask(image):
    """Purple-magenta pixels (blue close to or above green, red dominant) are vessels."""
    r, g, b = image[..., 0], image[..., 1], image[..., 2]
    return np.clip((b - g) / 40, 0, 1) * (r > 60)


def glow_mask(image):
    luma = image @ [0.299, 0.587, 0.114]
    return np.clip((luma - luma.mean()) / (luma.max() - luma.mean() + 1e-6), 0, 1)


def flesh_frames(image):
    """A slow wave runs across the vessels; the tissue swells slightly with it."""
    h, w = image.shape[:2]
    y, x = np.mgrid[0:h, 0:w] / h
    veins = vein_mask(image)[..., None]
    out = []
    for f in range(FRAMES):
        wave = np.sin(2 * math.pi * (f / FRAMES - (x + y) / 2))[..., None]
        breath = 1 + 0.04 * math.sin(2 * math.pi * f / FRAMES)
        out.append(image * breath * (1 + 0.22 * veins * wave))
    return out


def sac_frames(image):
    """Slow breathing glow: the whole sac brightens smoothly and fades; no sharp steps that read as flicker."""
    glow = glow_mask(image)[..., None] ** 0.6
    out = []
    for f in range(SAC_FRAMES):
        k = 0.5 - 0.5 * math.cos(2 * math.pi * f / SAC_FRAMES)
        out.append(image * (1 + 0.18 * glow * k) + 10 * glow * k)
    return out


def resin_frames(image):
    """Highlights brighten and fade slowly, like light through a viscous secretion."""
    glow = glow_mask(image)[..., None]
    return [image * (1 + 0.12 * glow * math.sin(2 * math.pi * f / FRAMES)) for f in range(FRAMES)]


def save(frames, path, frametime):
    frames = [np.clip(f, 0, 255).astype('uint8') for f in frames]
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(np.concatenate(frames, axis=0)).save(path, optimize=True)
    meta = path.with_name(path.name + '.mcmeta')
    if len(frames) > 1:
        meta.write_text(json.dumps({'animation': {'frametime': frametime, 'interpolate': True}}, indent=2) + '\n')
    elif meta.exists():
        meta.unlink()


def hd(name):
    return np.asarray(Image.open(SOURCE / f'{name}-hd.png').convert('RGB').resize((128, 128), Image.LANCZOS)).astype(float)


BLOCKS = {
    # block id: (16x image, 128 px image, animation, ticks per frame)
    'hive_flesh': (lambda: to16(rgb(SOURCE / 'flesh-px20.png')), lambda: hd('flesh'), flesh_frames, 4),
    'chitin': (lambda: to16(rgb(SOURCE / 'chitin-px24.png')), lambda: hd('chitin'), None, 0),
    'resin': (lambda: to16(rgb(SOURCE / 'resin-px20.png')), lambda: hd('resin'), resin_frames, 6),
    'pulse_sac': (lambda: to16(rgb(SOURCE / 'sac-px48.png')), lambda: hd('sac'), sac_frames, 5),
}

for name, (low, high, animate, frametime) in BLOCKS.items():
    for image, folder in ((low(), MOD), (high(), HD)):
        save(animate(image) if animate else [image], folder / f'{name}.png', frametime)

LAYOUT = json.loads((BIOTECH / 'tools/host-uv-layout.json').read_text())
for tile, root in ((32, BIOTECH / 'src/main/resources'), (128, BIOTECH / 'hd-pack')):
    scale = tile // 32
    tiles = {m: to16(rgb(SOURCE / f'host-{m}.png'), tile) if tile == 32 else
             np.asarray(Image.open(SOURCE / f'host-{m}.png').convert('RGB').resize((tile, tile), Image.LANCZOS)).astype(float)
             for m in {i['material'] for islands in LAYOUT.values() for i in islands}}
    for entity, islands in LAYOUT.items():
        atlas = np.zeros((256 * scale, 256 * scale, 4))
        glow = np.zeros_like(atlas)
        for island in islands:
            x, y = island['rectangle'][0] * scale, island['rectangle'][1] * scale
            colour = tiles[island['material']]
            if entity == 'host' and island['bone'] == 'eggs':
                # Pale egg membranes stand out against the blood-red abdomen.
                colour = colour * [.55, .5, .45] + [120, 98, 77]
            if entity == 'host' and island['bone'].startswith('hang'):
                colour = colour * [.65, .6, .55] + [77, 45, 38]
            if island['bone'] == 'teeth':
                # Reuse the keratin pattern, but bleach the mouth's teeth to warm ivory.
                colour = (colour.mean(-1)[..., None] / 255 * .3 + .65) * [230, 206, 174]
            atlas[y:y + tile, x:x + tile] = np.dstack([colour, np.full((tile, tile), 255)])
            if island['material'] == 'engine':
                glow[y:y + tile, x:x + tile] = atlas[y:y + tile, x:x + tile]
        (root / ENTITY).mkdir(parents=True, exist_ok=True)
        Image.fromarray(atlas.astype('uint8')).save(root / ENTITY / f'{entity}.png', optimize=True)
        if entity == 'host':
            Image.fromarray(glow.astype('uint8')).save(root / ENTITY / 'host_glowmask.png', optimize=True)

for item in ('chitin_plate', 'resin_lump', 'host_ganglion', 'blood_bucket'):
    for size, root in ((16, BIOTECH / 'src/main/resources'), (64, BIOTECH / 'hd-pack')):
        folder = root / 'assets/starfold_biotech/textures/item'
        folder.mkdir(parents=True, exist_ok=True)
        Image.fromarray(icon(SOURCE / f'icon-{item}.png', size).astype('uint8')).save(folder / f'{item}.png', optimize=True)

# Blood: the still surface drifts one texel per frame diagonally, the flow scrolls down one texel per frame like water.
for name, low, high, frames, frametime, step in (('blood_still', 16, 128, 16, 6, (1, 1)), ('blood_flow', 32, 256, 32, 3, (1, 0))):
    for size, folder, source in ((low, MOD, f'{name}-px.png'), (high, HD, f'{name}-hd.png')):
        tile = to16(rgb(SOURCE / source), size) if size == low else np.asarray(
            Image.open(SOURCE / source).convert('RGB').resize((size, size), Image.LANCZOS)).astype(float)
        if name == 'blood_flow':
            # The flow source is a brighter red than the still one; both are the same liquid.
            tile = tile * rgb(SOURCE / 'blood_still-px.png').mean() / tile.mean()
        shift = size // low
        save([np.roll(tile, (f * step[0] * shift, f * step[1] * shift), (0, 1)) for f in range(frames)],
             folder / f'{name}.png', frametime)

(BIOTECH / 'hd-pack/pack.mcmeta').write_text(json.dumps(
    {'pack': {'pack_format': 34, 'description': 'Starfold: Biotech high-resolution organic textures'}}, indent=2) + '\n')
print('wrote', ', '.join([*BLOCKS, *LAYOUT, 'item icons']))
