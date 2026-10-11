#!/usr/bin/env python3
"""Deterministic upgrade art/resources. Shares the basic scope geometry, not its lens/trim."""
from pathlib import Path
import json, struct, zlib
root=Path('src/main/resources')
def js(path,data):
 p=root/path; p.parent.mkdir(parents=True,exist_ok=True); p.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
def png(name,pixel):
 def chunk(t,b): return struct.pack('>I',len(b))+t+b+struct.pack('>I',zlib.crc32(t+b)&0xffffffff)
 data=b''.join(b'\0'+bytes(c for x in range(16) for c in pixel(x,y)) for y in range(16))
 (root/f'assets/starfold/textures/block/{name}.png').write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',16,16,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(data))+chunk(b'IEND',b''))
for name,lower,trim,part in [('scope_nav','scope',(65,192,209),'compass'),('scope_precise','scope_nav',(242,184,65),'diamond')]:
 png(name+'_housing',lambda x,y:(155,171,182) if (x,y) in ((2,2),(13,2),(2,13),(13,13)) else trim if min(x,y,15-x,15-y)<2 else (65+(x+y)%3*5,76+(x+y)%3*5,88+(x+y)%3*5))
 png(name+'_lens',lambda x,y:(220,244,255) if abs(x-y+3)<2 else tuple(round(c*(.35+.025*y)) for c in trim))
 for folder in ['blockstates','models/block','models/item']:
  data=(root/f'assets/starfold/{folder}/scope.json').read_text().replace('block/scope','block/'+name)
  js(f'assets/starfold/{folder}/{name}.json',json.loads(data))
 js(f'data/starfold/loot_table/blocks/{name}.json',json.loads((root/'data/starfold/loot_table/blocks/scope.json').read_text().replace('starfold:scope','starfold:'+name)))
 js(f'data/starfold/recipe/{name}.json',{'type':'minecraft:crafting_shaped','category':'redstone','pattern':[' R ','ISI',' P '],'key':{'R':{'item':'minecraft:redstone'},'I':{'item':'minecraft:gold_ingot'},'S':{'item':'starfold:'+lower},'P':{'item':'minecraft:'+part}},'result':{'id':'starfold:'+name,'count':1}})
