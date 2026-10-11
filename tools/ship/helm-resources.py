#!/usr/bin/env python3
"""Original procedural controller console: screen aft, arrow on top points to FACING (north base)."""
from pathlib import Path
import json, struct, zlib
root=Path('src/main/resources')
def js(path,data):
 p=root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
def png(name,pixel):
 def chunk(t,b):return struct.pack('>I',len(b))+t+b+struct.pack('>I',zlib.crc32(t+b)&0xffffffff)
 data=b''.join(b'\0'+bytes(c for x in range(16) for c in pixel(x,y)) for y in range(16))
 p=root/f'assets/starfold/textures/block/{name}.png';p.parent.mkdir(parents=True,exist_ok=True)
 p.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',16,16,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(data))+chunk(b'IEND',b''))
png('helm_housing',lambda x,y:(154,172,182) if (x,y) in ((2,2),(13,2),(2,13),(13,13)) else (32,40,49) if min(x,y,15-x,15-y)<2 else (65+(x+y)%3*5,76+(x+y)%3*5,88+(x+y)%3*5))
png('helm_screen',lambda x,y:(60,225,195) if 3<=x<=12 and y in (4,7,10) and x<15-y//2 else (12,37,44) if 2<=x<=13 and 2<=y<=13 else (71,88,102))
png('helm_arrow',lambda x,y:(255,190,55) if (3<=y<=7 and abs(x-7.5)<=y-2) or (7<=y<=13 and 6<=x<=9) else (45+(x+y)%3*4,58+(x+y)%3*4,69+(x+y)%3*4))
a='assets/starfold/'
js(a+'blockstates/helm.json',{'variants':{'facing='+d:dict(model='starfold:block/helm',y=y) for d,y in [('north',0),('east',90),('south',180),('west',270)]}})
def element(lo,hi,console=False):return {'from':lo,'to':hi,'faces':{f:{'texture':'#screen' if console and f=='south' else '#arrow' if console and f=='up' else '#housing'} for f in ['north','south','east','west','up','down']}}
js(a+'models/block/helm.json',{'parent':'minecraft:block/block','textures':{'particle':'starfold:block/helm_housing','housing':'starfold:block/helm_housing','screen':'starfold:block/helm_screen','arrow':'starfold:block/helm_arrow'},'elements':[element([1,0,1],[15,4,15]),element([4,4,4],[12,10,12]),element([1,10,1],[15,16,15],True)]})
js(a+'models/item/helm.json',{'parent':'starfold:block/helm'})
js('data/starfold/recipe/helm.json',{'type':'minecraft:crafting_shaped','category':'redstone','pattern':['ICI','RLR','III'],'key':{'I':{'item':'minecraft:iron_ingot'},'C':{'item':'minecraft:compass'},'R':{'item':'minecraft:redstone'},'L':{'item':'minecraft:lever'}},'result':{'id':'starfold:helm','count':1}})
js('data/starfold/loot_table/blocks/helm.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'starfold:helm'}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
