#!/usr/bin/env python3
"""Original aerospace sill and flight: helm/scope gunmetal, cyan instruments, amber warning paint."""
from pathlib import Path
import json, struct, zlib
root=Path('src/main/resources')
def js(path,data):
 p=root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
def png(name,pixel):
 def chunk(t,b):return struct.pack('>I',len(b))+t+b+struct.pack('>I',zlib.crc32(t+b)&0xffffffff)
 data=b''.join(b'\0'+bytes(c for x in range(16) for c in pixel(x,y)) for y in range(16))
 (root/f'assets/starfold/textures/block/airstair_{name}.png').write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',16,16,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(data))+chunk(b'IEND',b''))
png('metal',lambda x,y:(155,171,182) if x in (0,15) else (65+(x+y)%3*5,76+(x+y)%3*5,88+(x+y)%3*5))
png('plate',lambda x,y:(119,138,149) if (x+y)%8 in (0,1) and (x-y)%8 in (0,1,2) else (38,46,55) if min(x,y,15-x,15-y)==0 else (65+(x+y)%3*3,76+(x+y)%3*3,88+(x+y)%3*3))
png('hazard',lambda x,y:(242,184,65) if (x+abs(y-8))%8<4 else (38,46,55))
png('cyan',lambda x,y:(60,225,225))
png('panel',lambda x,y:(60,225,225) if 11<=x<=13 and y==3 else (155,171,182) if (x,y) in ((2,2),(13,13),(2,13)) else (38,46,55) if y%3==0 or min(x,y,15-x,15-y)<2 else (65,76,88))
a='assets/starfold/'
js(a+'blockstates/airstair.json',{'variants':{'facing='+d:dict(model='starfold:block/airstair',y=y) for d,y in [('north',0),('east',90),('south',180),('west',270)]}})
def element(lo,hi,texture):return {'from':lo,'to':hi,'faces':{f:{'texture':'#'+texture} for f in ('north','south','east','west','up','down')}}
js(a+'models/block/airstair.json',{'parent':'minecraft:block/block','textures':{'particle':'starfold:block/airstair_panel','housing':'starfold:block/helm_housing','panel':'starfold:block/airstair_panel','metal':'starfold:block/airstair_metal','cyan':'starfold:block/airstair_cyan'},'elements':[element([0,0,1],[16,16,16],'housing'),element([.5,1,0],[15.5,14,1],'panel'),element([0,14,-.3],[16,16,1.2],'metal'),element([11,11,-.05],[14,12,0],'cyan')]})
js(a+'models/item/airstair.json',{'parent':'starfold:block/airstair'})
js('data/starfold/recipe/airstair.json',{'type':'minecraft:crafting_shaped','category':'redstone','pattern':['III','ITI'],'key':{'I':{'item':'minecraft:iron_ingot'},'T':{'item':'minecraft:iron_trapdoor'}},'result':{'id':'starfold:airstair','count':1}})
js('data/starfold/loot_table/blocks/airstair.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'starfold:airstair'}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
