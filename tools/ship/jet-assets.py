#!/usr/bin/env python3
"""dev.50 nozzle elements and deterministic 16px plating; Python stdlib only."""
import json
import struct
import zlib
from pathlib import Path

root = Path(__file__).resolve().parents[2] / 'src/main/resources/assets/starfold'

def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))

for name in ['side', 'back', 'nozzle', 'nozzle_low', 'nozzle_high']:
    data = bytearray()
    for y in range(16):
        data.append(0)
        for x in range(16):
            edge = min(x, y, 15-x, 15-y)
            color = (62, 76, 89) if (x+y)%3 else (66, 80, 93)
            if edge in (0, 3): color = (24, 33, 43)
            if edge == 1: color = (111, 130, 142)
            if name == 'side':
                if 5 <= x <= 10 and y in (5, 7, 9, 11): color = (20, 28, 35)
                if y == 13 and 4 <= x <= 11: color = (151, 98, 51)
            elif name == 'back':
                if 5 <= x <= 10 and 5 <= y <= 10: color = (15, 23, 31) if y%2 else (89, 108, 118)
            else:
                if 4 <= x <= 11 and 4 <= y <= 11:
                    color = (9, 17, 26)
                    if name != 'nozzle':
                        inner = min(x-4, y-4, 11-x, 11-y)
                        color = [(18, 70, 100), (37, 146, 204), (99, 213, 248), (181, 239, 255)][inner]
                        if name == 'nozzle_high': color = [(33, 120, 157), (73, 198, 247), (181, 240, 255), (240, 253, 255)][inner]
                elif edge == 2: color = (169, 116, 66)
            if (x,y) in [(2,2),(13,2),(2,13),(13,13)]: color = (188, 198, 203)
            data.extend((*color, 255))
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>2I5B',16,16,8,6,0,0,0)) + chunk(b'IDAT',zlib.compress(data)) + chunk(b'IEND',b'')
    (root/'textures/block'/('thruster_'+name+'.png')).write_bytes(png)

faces = ['north','south','east','west','up','down']
def element(start, end, recess=False):
    out = {'from':start, 'to':end, 'faces':{f:{'texture':'#'+f} for f in faces}}
    if recess:
        out['faces']['north']['uv'] = [4,4,12,12]
    return out

model = {'parent':'minecraft:block/block','textures':{'particle':'starfold:block/thruster_side', **{f:'starfold:block/thruster_'+('nozzle' if f=='north' else 'back' if f=='south' else 'side') for f in faces}},
         'elements':[element([2,2,4],[14,14,14]), element([1,1,0],[15,4,4]), element([1,12,0],[15,15,4]), element([1,4,0],[4,12,4]), element([12,4,0],[15,12,4]), element([4,4,2],[12,12,4],True)]}
(root/'models/block/thruster.json').write_text(json.dumps(model,separators=(',',':'))+'\n')
item = {'parent':'starfold:block/thruster','display':{'gui':{'rotation':[25,225,0],'translation':[0,0,0],'scale':[.75,.75,.75]}}}
(root/'models/item/thruster.json').write_text(json.dumps(item,separators=(',',':'))+'\n')
