#!/usr/bin/env python3
"""Original 16px metal housing and cool glass lens; north is the unrotated facing."""
from pathlib import Path
import json, struct, zlib
root=Path('src/main/resources')
def js(path,data):
 p=root/path; p.parent.mkdir(parents=True,exist_ok=True); p.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
def png(name,pixel,size=16,alpha=False,folder='block'):
 def chunk(t,b): return struct.pack('>I',len(b))+t+b+struct.pack('>I',zlib.crc32(t+b)&0xffffffff)
 data=b''.join(b'\0'+bytes(c for x in range(size) for c in pixel(x,y)) for y in range(size))
 p=root/f'assets/starfold/textures/{folder}/{name}.png';p.parent.mkdir(parents=True,exist_ok=True)
 p.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',size,size,8,6 if alpha else 2,0,0,0))+chunk(b'IDAT',zlib.compress(data))+chunk(b'IEND',b''))
png('scope_housing',lambda x,y:(155,171,182) if (x,y) in ((2,2),(13,2),(2,13),(13,13)) else (38,46,55) if min(x,y,15-x,15-y)<2 else (65+(x+y)%3*5,76+(x+y)%3*5,88+(x+y)%3*5))
png('scope_lens',lambda x,y:(153,222,241) if abs(x-y+3)<2 else (28+x*2,69+y*3,101+y*4))
png('space_dust',lambda x,y:(255,255,255,round(255*(1-abs((y-3.5)/4))*(1-abs((x-3.5)/4)))),8,True,'particle')
a='assets/starfold/'
variants={}
for d,x,y in [('north',0,0),('south',0,180),('west',0,270),('east',0,90),('up',270,0),('down',90,0)]:
 v={'model':'starfold:block/scope'}
 if x:v['x']=x
 if y:v['y']=y
 variants['facing='+d]=v
js(a+'blockstates/scope.json',{'variants':variants})
def element(lo,hi,lens=False):return {'from':lo,'to':hi,'faces':{f:{'texture':'#lens' if lens and f=='north' else '#housing'} for f in ['north','south','east','west','up','down']}}
js(a+'models/block/scope.json',{'parent':'minecraft:block/block','textures':{'particle':'starfold:block/scope_housing','housing':'starfold:block/scope_housing','lens':'starfold:block/scope_lens'},'elements':[element([2,2,3],[14,14,16]),element([4,4,0],[12,12,3],True)]})
js(a+'models/item/scope.json',{'parent':'starfold:block/scope','display':{'gui':{'rotation':[25,225,0],'translation':[0,0,0],'scale':[.65,.65,.65]}}})
js('data/starfold/recipe/scope.json',{'type':'minecraft:crafting_shaped','category':'redstone','pattern':['III','ISI','III'],'key':{'I':{'item':'minecraft:iron_ingot'},'S':{'item':'minecraft:spyglass'}},'result':{'id':'starfold:scope','count':1}})
js('data/starfold/loot_table/blocks/scope.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'starfold:scope'}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
