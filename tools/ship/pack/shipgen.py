#!/usr/bin/env python3
"""Offline ship generator with runtime-reviewed r4 exports; never executes game commands.
Runtime-reviewed: python3 shipgen.py --runtime-r4 --output build/space/ships [--only B]
Legacy: python3 shipgen.py --revision 0|1|2 [--views all|front] [--only A|B|C]
Default writes are beside this script; --output chooses r4 output. Bow=-X, up=+Y, port=+Z.
Outputs are original procedural designs, not imported builds. NBT DataVersion 3955.
"""
from __future__ import annotations
import argparse, collections, gzip, hashlib, io, json, math, pathlib, re, struct, zlib
ROOT = pathlib.Path(__file__).resolve().parent
PARTS = {
 'shell':'minecraft:white_concrete', 'panel':'minecraft:light_gray_concrete',
 'trim':'minecraft:smooth_quartz', 'frame':'create:industrial_iron_block',
 'rib':'create:metal_girder', 'glass':'create:framed_glass',
 'window':'create:industrial_iron_window', 'stripe':'minecraft:cyan_concrete',
 'amber':'minecraft:orange_concrete', 'floor':'minecraft:polished_deepslate',
 'deck':'minecraft:smooth_stone', 'light':'minecraft:sea_lantern',
 'lamp':'create:rose_quartz_lamp', 'ring':'create:copper_casing',
 'thruster':'starfold:thruster', 'gyro':'starfold:gyro',
 'booster':'starfold:booster', 'helm':'starfold:helm',
 'scope':'starfold:scope_precise', 'sill':'starfold:airstair',
 'door':'minecraft:iron_door', 'bevel':'minecraft:smooth_quartz_slab',
}
DIR = {'west':(-1,0,0),'east':(1,0,0),'down':(0,-1,0),'up':(0,1,0),'north':(0,0,-1),'south':(0,0,1)}
OPP = dict(west='east',east='west',up='down',down='up',north='south',south='north')
def add(a,b): return tuple(x+y for x,y in zip(a,b))
def state(key, **props):
    s=PARTS.get(key,key)
    return s + ('['+','.join(f'{k}={str(v).lower()}' for k,v in sorted(props.items()))+']' if props else '')
def splitstate(s):
    name=s.split('[')[0]; p={}
    if '[' in s: p=dict(q.split('=') for q in s.split('[',1)[1][:-1].split(','))
    return name,p

def mirrored(s):
    name,p=splitstate(s)
    if p.get('facing') in ('north','south'): p['facing']=OPP[p['facing']]
    if 'hinge' in p: p['hinge']='left' if p['hinge']=='right' else 'right'
    return state(name,**p)

class Voxel:
    def __init__(self,name,revision): self.name=name; self.revision=revision; self.b={}; self.nbt={}; self.meta={}
    def put(self,x,y,z,s,nbt=None):
        p=(x,y,z)
        if s in ('air','minecraft:air'): self.b.pop(p,None); self.nbt.pop(p,None); return
        self.b[p]=PARTS.get(s,s)
        self.nbt.pop(p,None)
        if nbt is not None: self.nbt[p]=nbt
    def fill(self,x1,y1,z1,x2,y2,z2,s):
        for x in range(x1,x2+1):
            for y in range(y1,y2+1):
                for z in range(z1,z2+1): self.put(x,y,z,s)
    def mirror(self,x,y,z,s,nbt=None):
        self.put(x,y,z,s,nbt)
        if z: self.put(x,y,-z,mirrored(PARTS.get(s,s)),nbt)
    def bounds(self): return tuple(min(p[i] for p in self.b) for i in range(3)),tuple(max(p[i] for p in self.b) for i in range(3))


def hull(kind,rev):
    v=Voxel(kind,rev)
    for x in range(-13,14):
        t=x+13
        w=min(3,1+t//2)
        top=min(8,4+(t+1)//2)
        low=max(0,3-t//2)
        if kind=='A':
            top=min(top,7 if x<0 or x>8 else 8)
            if x>=3: w=min(5,3+(x-3)//3)
            if x==13: top=5
        elif kind=='B':
            if x>=7: w=2; top=6
            if x>=11: w=1; top=5
        else:
            top=min(top,7)
            if x>=7: w=2
            if x>=9: w=1; top=6
        for y in range(low,top+1):
            wy=w
            if rev>=1 and (y==top or y==low) and w>1: wy=w-1
            for z in range(-wy,wy+1):
                s='shell'
                if y<=1: s='frame' if abs(z)<=1 else 'panel'
                if y==3: s='floor'
                if y==4: s='deck'
                if abs(z)==wy and y==top: s='trim'
                if abs(z)==w and y==4: s='stripe'
                v.put(x,y,z,s)
    # Readable waist and chine: doors remain outside this wing volume.
    for x in range(2,13):
        width=5 if kind=='A' else (4 if kind=='B' else 3)
        if kind=='A': width=min(5,3+(x-1)//3)
        for z in range(3,width+1):
            for y in (2,3): v.mirror(x,y,z,'shell' if y==3 else 'frame')
    # Twin stern nacelles, different from Arrow's buried trailing-edge engines.
    if kind in ('B','C'):
        for side in (-1,1):
            for x in range(5 if kind=='B' else 7,14):
                for y in range(2,7):
                    for zz in range(3,6):
                        if rev>=1 and y in (2,6) and zz!=4: continue
                        v.put(x,y,side*zz,'frame' if x==13 else 'shell')
            for x in ((5,8) if kind=='B' else (7,10)):
                v.fill(x,3,min(side*2,side*4),x,4,max(side*2,side*4),'rib')
            if rev>=2:
                # Empty slit separates pod crown from the central pressure cabin.
                for x in range(9,13): v.put(x,6,side*3,'air')
    if kind=='C':
        for x in range(0,10):
            v.put(x,7,0,'frame')
            if x>=3: v.put(x,8,0,'panel' if x in (3,8,9) else 'shell')
    if rev>=1:
        # Geometric recessed grooves (not merely a painted black stripe).
        for x in (-5,0,5,10):
            for z in (-3,3):
                for y in (2,3,6):
                    if (x,y,z) in v.b:
                        v.put(x,y,z,'air'); v.put(x,y,z-(1 if z>0 else -1),'panel')
            for z in range(-2,3):
                ys=[y for xx,y,zz in v.b if xx==x and zz==z]
                if ys:
                    y=max(ys)
                    if y>6: v.put(x,y,z,'air'); v.put(x,y-1,z,'panel')
    if rev>=2:
        if kind=='A':
            # Longer delta chines and a lower aft shoulder, not a rectangular wing.
            for x in range(3,13):
                w=min(5,3+(x-1)//3)
                v.mirror(x,4,w,state('bevel',type='bottom',waterlogged=False))
            for x in range(9,13):
                for z in range(-2,3): v.put(x,7,z,'air'); v.put(x,6,z,'panel')
        elif kind=='B':
            # Industrial belly ribs and shortened aft bridge roof.
            for x in (-4,1,6):
                for z in (-3,3):
                    for y in (1,2,3): v.put(x,y,z,'rib')
            for x in range(3,7):
                for z in range(-2,3): v.put(x,8,z,'air'); v.put(x,7,z,'panel')
        else:
            for x in range(8,13):
                for z in (-2,2):
                    for y in (5,6,7): v.put(x,y,z,'air')
            for x in range(-12,-7):
                v.mirror(x,4,min(2,1+(x+13)//2),state('bevel',type='bottom',waterlogged=False))
    return v


def refine_one(v):
    """Round-zero review: too bus-like, dark floor bands and huge copper bow fists."""
    if v.revision<1: return v
    # Hide the structural deck band behind white fairings.
    for (x,y,z),s in list(v.b.items()):
        if y==3 and abs(z)>=2 and s==PARTS['floor']: v.put(x,y,z,'shell')
    # Flared lower chines put visual weight below the cabin, not into a square roof.
    for x in range(-9,8):
        width=4 if -6<=x<=6 else 3
        for z in range(2,width+1):
            for y in range(1,4):
                v.mirror(x,y,z,'shell' if y>1 else 'panel')
    if v.name=='A':
        for x in range(2,13):
            w=min(5,3+(x-1)//3)
            for z in range(3,w+1):
                for y in range(1,5): v.mirror(x,y,z,'shell' if y>1 else 'panel')
        # Lower delta shoulders: pressure cabin is only seven wide.
        for x in range(3,14):
            for z in (-5,-4,4,5):
                for y in range(5,9):v.put(x,y,z,'air')
    elif v.name=='B':
        for x in range(-7,8):
            for z in (-4,4):
                for y in range(0,4):v.put(x,y,z,'panel' if y==0 else 'shell')
        # Chamfer the four pod corners with true half-block quartz shoulders.
        for x in range(5,14):
            for z in (-5,-3,3,5):v.put(x,6,z,state('bevel',type='bottom',waterlogged=False))
    else:
        # Longer slim nacelles; short, lower pylons leave visible top-side slots.
        for x in range(3,8):
            for z in (-5,-4,4,5):
                for y in range(2,6):v.put(x,y,z,'shell')
        for x in (3,7):
            for z in (-3,3):
                for y in (2,3):v.put(x,y,z,'rib')
        for x in range(4,7):
            for z in (-3,3):
                for y in range(4,8):v.put(x,y,z,'air')
    return v


def refine_two(v):
    """Round-one review: tops too flat, undersized scantlings, pod slots disappear."""
    if v.revision<2: return v
    # One extra keel layer buys an engineered double bottom, not hidden ballast.
    # Hatch and camera/jet rays are punched through this layer later.
    for x in range(-9,13):
        w=3 if x in (-9,-8,11,12) else 4
        for z in range(-w,w+1):
            if v.name=='C' and x>=7 and abs(z)==2:continue
            v.put(x,-1,z,'frame' if abs(z)<=1 else 'shell')
            if (x,0,z) not in v.b:v.put(x,0,z,'panel')
    if v.name=='C':
        for x in range(3,13):
            for z in (-5,-4,-3,3,4,5):v.put(x,1,z,'frame' if abs(z)==4 else 'shell')
        # A real slot around a short pylon, rather than a painted seam.
        for x in range(7,13):
            if x in (7,10):continue
            for z in (-2,2):
                for y in range(0,7):v.put(x,y,z,'air')
    # Repeated roof panel bands use half-height setbacks; no floating roof tiles.
    for x in (-4,1,6):
        for z in (-3,3):
            v.put(x,6,z,'panel')
    return v


def interior(v):
    # Shared programme: 5-wide walkable cabin, no glass/wood box.
    v.fill(-8,5,-2,6,6,2,'air')
    # Raised bridge B; A low canopy; C low long roof + fin.
    if v.name=='B': v.fill(-8,7,-2,1,7,2,'air')
    elif v.name=='A': v.fill(1,7,-2,6,7,2,'air')
    for x in range(-8,7):
        for z in range(-2,3): v.put(x,4,z,'floor' if abs(z)==2 else 'deck')
    # Light partitions with a central two-block passage.
    for x in (-3,1):
        for z in (-2,2):
            for y in (5,6): v.put(x,y,z,'panel')
    if v.revision>=1:
        # Seal the pressure cabin after changing the outer silhouette. The earlier
        # narrow canopy left a gap at the raised bridge shoulder.
        for x in range(-8,7):
            top=8 if ((v.name=='B' and x<=1) or (v.name=='A' and x>=1)) else 7
            for z in range(-2,3):v.put(x,top,z,'shell')
            for z in (-3,3):
                for y in range(5,top):v.put(x,y,z,'shell')
    # Glazing band and canopy: solid tint in preview, full glass blocks in export.
    for x in range(-9,-3):
        for z in (-3,3):
            if (x,5,z) in v.b: v.put(x,5,z,'glass'); v.put(x,6,z,'glass')
    for z in range(-2,3):
        v.put(-9,5,z,'glass'); v.put(-9,6,z,'glass')
        if v.revision>=2 and v.name=='B':v.put(-9,7,z,'glass')
    for x in range(-8,-4):
        for z in range(-1,2): v.put(x,8 if v.name=='B' else 7,z,'glass')
    v.put(-7,5,0,state('helm',facing='west'))
    for z in (-2,2): v.put(-7,5,z,'lamp')
    # Mirror all furniture, retaining exact occupancy symmetry and equal block masses.
    for z in (-2,2):
        v.put(2,5,z,state('minecraft:cyan_bed',part='foot',facing='east',occupied=False))
        v.put(3,5,z,state('minecraft:cyan_bed',part='head',facing='east',occupied=False))
        v.put(4,5,z,state('minecraft:chest',facing='west',type='single',waterlogged=False),{'id':'minecraft:chest','Items':(10,[])})
        v.put(5,5,z,state('minecraft:furnace',facing='west',lit=False),{'id':'minecraft:furnace'})
        v.put(0,5,z,'minecraft:crafting_table')
    v.put(-4,5,0,state('minecraft:lectern',facing='east',has_book=False,powered=False),{'id':'minecraft:lectern'})
    # Ceiling fixtures are backed into the roof, not suspended in the headroom.
    for x in (-3,2,6):
        for z in (-1,1):
            top=8 if ((v.name=='B' and x<=1) or (v.name=='A' and x>=1)) else 7
            v.put(x,top,z,'light')
    # 3x3x3 protected mining envelope; access ladder adjacent, not inside it.
    v.fill(2,1,-1,4,3,1,'air')
    v.fill(2,0,-1,4,0,1,state('minecraft:iron_trapdoor',facing='west',half='top',open=False,powered=False,waterlogged=False))
    v.fill(2,-1,-1,4,-1,1,'air')
    for x in (1,5):
        for z in range(-2,3): v.put(x,0,z,'amber' if z in (-2,2) else 'frame')
    v.fill(5,1,0,5,6,0,'air')
    for y in range(1,6):
        v.put(6,y,0,'panel')
        v.put(5,y,0,state('minecraft:ladder',facing='west',waterlogged=False))
    # Give lower bay a two-high doorway to its access shaft.
    v.meta['bay_clear']={'min':[2,1,-1],'max':[4,3,1],'floor_hatch':[3,0,0],'ladder':[5,1,0]}
    # Sills face OUT; door normals face IN, exactly rocket.py's convention.
    for z,facing,hinges in ((3,'north',('left','right')),(-3,'south',('right','left'))):
        side=1 if z>0 else -1
        for x,hinge in zip((-1,0),hinges):
            v.put(x,4,z,state('sill',facing=OPP[facing]))
            for y,half in ((5,'lower'),(6,'upper')):
                v.put(x,y,z,state('door',facing=facing,half=half,hinge=hinge,open=False,powered=False))
            # External walking/ramp corridor clear all the way to bbox edge.
            for zz in (4*side,5*side):
                for y in range(0,8): v.put(x,y,zz,'air')
        # Both leaves have their own support block + inner AND outer buttons.
        for x in (-2,1):
            v.put(x,5,z,'amber')
            for zz,out in ((z+side,OPP[facing]),(z-side,facing)):
                v.put(x,5,zz,state('minecraft:stone_button',face='wall',facing=out,powered=False))
    for x in (2,4,6):
        for z in (-3,3): v.put(x,6,z,'window')
    # Maintain a single cyan flank stripe; interrupted at functional sills only.
    for x in range(-8,8):
        if x not in (-1,0):
            for z in (-3,3):
                if (x,4,z) in v.b: v.put(x,4,z,'stripe')
    v.put(-13,4,0,state('scope',facing='west'))
    v.put(-6,0,0,state('scope',facing='down'))
    v.put(-6,-1,0,'air')
    for z in (-1,1):
        v.put(-2,1,z,'frame')
        v.put(-2,2,z,state('gyro',powered=True))
        v.put(-2,3,z,state('minecraft:lever',face='floor',facing='north',powered=True))
        v.put(0,2,z*2,'booster')
    return v


def jet(v,p,nozzle):
    """Recess nozzle one voxel behind a copper lip, with an open exhaust ray."""
    axis=DIR[nozzle]; tangents=[i for i,n in enumerate(axis) if not n]
    # solid engine mount behind jet
    v.put(*add(p,tuple(-a for a in axis)),'frame')
    for d in (-1,1):
        for i in tangents:
            q=list(add(p,axis)); q[i]+=d
            # Keep overall envelope exactly 27x9x11.
            if -13<=q[0]<=13 and 0<=q[1]<=8 and -5<=q[2]<=5: v.put(*q,'ring')
            q=list(p); q[i]+=d
            if -13<=q[0]<=13 and 0<=q[1]<=8 and -5<=q[2]<=5: v.put(*q,'frame')
    v.put(*p,state('thruster',facing=nozzle,power=0))
    # Actual line of sight through the lip, not a painted fake nozzle.
    for n in range(1,30):
        q=tuple(p[i]+axis[i]*n for i in range(3))
        v.put(*q,'air')


def machinery(v):
    # Two mirrored stern/bow pairs straddle the vertical mass centre.
    # Side jets at bow and stern produce opposing forces with the SAME yaw sign.
    for y in ((2,4) if v.revision>=2 else (3,5)):
        for z in (-4,4): jet(v,(12,y,z),'east')
    for y in ((3,5) if v.revision==0 else (3,)):
        for z in (-2,2): jet(v,(-10,y,z),'west')
    for x in (-7,9):
        for side in (-1,1):
            jet(v,(x,3 if v.revision>=2 else 4,side*4),'south' if side>0 else 'north')
    vertical_x=(-6,9) if v.revision>=2 and v.name!='A' else (-5,9)
    for x in vertical_x:
        for z in (-2,2):
            jet(v,(x,1,z),'down'); jet(v,(x,7,z),'up')
    # Vertical pods need a structural spine between lower and upper jets;
    # they sit just outside the central walkway, never in the bay.
    for x in vertical_x:
        for z in (-2,2):
            for y in range(2,7):
                if (x,y,z) not in v.b: v.put(x,y,z,'frame')
    return v

def arrow(revision=2):
    if revision>=3: return large_ship('A',revision-3)
    return finish(machinery(interior(refine_two(refine_one(hull('A',revision))))))
def hauler(revision=2):
    if revision>=3: return large_ship('B',revision-3)
    return finish(machinery(interior(refine_two(refine_one(hull('B',revision))))))
def corvette(revision=2):
    if revision>=3: return large_ship('C',revision-3)
    return finish(machinery(interior(refine_two(refine_one(hull('C',revision))))))

def finish(v):
    if v.revision<2:return v
    # Final restrained roof detail on the shared cabin. A stays low at the bridge,
    # B steps down behind it, C gets an uninterrupted centerline dorsal blade.
    for x in (-4,1,6):
        top=8 if ((v.name=='B' and x<=1) or (v.name=='A' and x>=1)) else 7
        for z in (-2,-1,0,1,2):
            if (x,top,z) in v.b and v.b[x,top,z]==PARTS['shell']:
                v.put(x,top,z,state('minecraft:smooth_stone_slab',type='bottom',waterlogged=False))
    if v.name=='C':
        for x in range(1,9):v.put(x,8,0,'panel' if x in (1,8) else 'shell')
    # Four/five-block rhythm of backed structural ribs below the walking deck.
    for x in (-3,1,6):
        for z in (-2,2):
            for y in range(1,4):v.put(x,y,z,'rib')
    # Rear pressure bulkhead: pod chamfers must not open the living cabin.
    v.fill(7,5,-2,7,6,2,'panel')
    # Half-height underside bevels keep the reinforced keel from reading as a plinth.
    for (x,y,z),s in list(v.b.items()):
        if y==-1 and abs(z)>=2:v.put(x,y,z,state('bevel',type='top',waterlogged=False))
    # Clear the full two-wide airstair deployment columns, including new keel.
    for x in (-1,0):
        for z in (-5,-4,4,5):
            for y in range(-1,8):v.put(x,y,z,'air')
    return v
BUILDERS={'A':arrow,'B':hauler,'C':corvette}
NAMES={'A':'ARROW','B':'HAULER','C':'CORVETTE'}

# Round 3 geometry lives in a companion beside this script; exports, mass calculations,
# renderer and NBT codecs remain shared with the preserved round-2 toolchain.
def large_ship(kind,iteration):
    import r3_geometry
    return r3_geometry.build(__import__(__name__),kind,iteration)

# Minimal typed NBT. Supported types deliberately limited to those we write.
def nstr(s):
    b=s.encode('utf-8'); return struct.pack('>H',len(b))+b

class Byte(int):
    """NBT byte for vanilla inventory slots and TaCZ's chamber flag."""

def tagtype(v):
    if isinstance(v,Byte): return 1
    if isinstance(v,str): return 8
    if isinstance(v,int): return 3
    if isinstance(v,dict): return 10
    if isinstance(v,tuple): return 9
    raise TypeError(type(v))
def payload(t,v):
    if t==1: return struct.pack('>b',v)
    if t==3: return struct.pack('>i',v)
    if t==8: return nstr(v)
    if t==9:
        sub,items=v
        return bytes([sub])+struct.pack('>i',len(items))+b''.join(payload(sub,q) for q in items)
    if t==10:
        return b''.join(bytes([tagtype(q)])+nstr(k)+payload(tagtype(q),q) for k,q in v.items())+b'\0'
    raise ValueError(t)
def nbt_bytes(tree): return b'\x0a\x00\x00'+payload(10,tree)
def read_nbt(data):
    f=io.BytesIO(data)
    def exact(n):
        b=f.read(n)
        if len(b)!=n: raise ValueError('truncated NBT')
        return b
    def text(): return exact(struct.unpack('>H',exact(2))[0]).decode('utf-8')
    def val(t):
        if t==1: return Byte(struct.unpack('>b',exact(1))[0])
        if t==3: return struct.unpack('>i',exact(4))[0]
        if t==8: return text()
        if t==9:
            sub=exact(1)[0]; n=struct.unpack('>i',exact(4))[0]
            if n<0: raise ValueError('negative list length')
            return (sub,[val(sub) for _ in range(n)])
        if t==10:
            d={}
            while True:
                sub=exact(1)[0]
                if sub==0: return d
                k=text(); d[k]=val(sub)
        raise ValueError(f'unsupported NBT tag {t}')
    assert exact(1)==b'\x0a'; text(); result=val(10)
    assert not f.read(1),'trailing NBT'
    return result

def snbt(v):
    if isinstance(v,Byte): return str(v)+'b'
    if isinstance(v,str): return json.dumps(v,ensure_ascii=False)
    if isinstance(v,int): return str(v)
    if isinstance(v,dict): return '{'+','.join(k+':'+snbt(q) for k,q in v.items())+'}'
    if isinstance(v,tuple): return '['+','.join(snbt(q) for q in v[1])+']'
    raise TypeError(v)

# Sable 2.0.5 physics_block_properties: default=1; light=.5; super_light=.25.
# Concrete, polished deepslate, industrial iron and framed glass use DEFAULT, not iron storage-block mass.
# Only the authored palette is budgeted here; runtime always reads the live Sable mass tracker.
SABLE_MASS = {**dict.fromkeys(('minecraft:smooth_quartz_stairs','minecraft:smooth_quartz_slab',
    'minecraft:polished_deepslate_slab','minecraft:smooth_stone_slab','minecraft:chest',
    'create:shaft','create:cogwheel','create:andesite_funnel','minecraft:spruce_planks'), .5),
    **dict.fromkeys(('minecraft:birch_door','minecraft:oak_door','minecraft:oak_trapdoor',
    'minecraft:iron_trapdoor','minecraft:waxed_oxidized_copper_trapdoor','minecraft:ladder','minecraft:iron_bars'), .25),
    'minecraft:lever':0,'minecraft:oak_sign':0,'minecraft:oak_wall_sign':0,'minecraft:iron_block':4,
    'minecraft:stone':2,'create:redstone_link':.25,'create:piston_extension_pole':.5}

def material_balance(v):
    masses={p:SABLE_MASS.get(splitstate(s)[0],1) for p,s in v.b.items()}
    total=sum(masses.values());com=tuple(sum((p[i]+.5)*m for p,m in masses.items())/total for i in range(3))
    thrust={}
    for push in DIR:
        points=[p for p,s in v.b.items() if splitstate(s)[0]==PARTS['thruster'] and splitstate(s)[1]['facing']==OPP[push]]
        if not points:continue
        mean=tuple(sum(p[i]+.5 for p in points)/len(points) for i in range(3))
        off=tuple(0 if DIR[push][i] else mean[i]-com[i] for i in range(3))
        thrust[push]={'count':len(points),'perpendicular_offset':off,'offset_length':math.sqrt(sum(x*x for x in off))}
    return {'mass':total,'centre_of_mass':com,'thrust':thrust,
        'yaw_inertia':sum(m*((p[0]+.5-com[0])**2+(p[2]+.5-com[2])**2+1/6) for p,m in masses.items())}


def stats(v):
    b=v.b; n=len(b); lo,hi=v.bounds()
    com=tuple(sum(p[i]+.5 for p in b)/n for i in range(3))
    groups=collections.defaultdict(list)
    for p,s in b.items():
        name,props=splitstate(s)
        if name==PARTS['thruster']: groups[OPP[props['facing']]].append(p)
    thrust={}
    for push,points in sorted(groups.items()):
        mean=tuple(sum(p[i]+.5 for p in points)/len(points) for i in range(3))
        off=tuple(0 if DIR[push][i] else mean[i]-com[i] for i in range(3))
        thrust[push]={'nozzle':OPP[push],'count':len(points),'positions':points,'line_point':mean,'perpendicular_offset':off,'offset_length':math.sqrt(sum(q*q for q in off))}
    # Same torque-sign selection as NativeHelmCommand; A/D are yaw, not strafing.
    yaw={}
    for key,sign in (('left',1),('right',-1)):
        points=[]; force=[0,0,0]; torque=0
        for p,s in b.items():
            name,pr=splitstate(s)
            if name!=PARTS['thruster'] or pr['facing'] not in ('north','south'): continue
            push=DIR[OPP[pr['facing']]]
            arm=[p[i]+.5-com[i] for i in range(3)]
            ty=arm[2]*push[0]-arm[0]*push[2]
            if ty*sign>1e-6:
                points.append(p); torque+=ty
                force=[a+b for a,b in zip(force,push)]
        yaw[key]={'jets':points,'net_unit_force':force,'yaw_moment_per_unit_thrust':torque}
    seen=set(); components=[]
    for p in b:
        if p in seen: continue
        todo=[p]; seen.add(p); count=0
        while todo:
            q=todo.pop(); count+=1
            for d in DIR.values():
                r=add(q,d)
                if r in b and r not in seen: seen.add(r); todo.append(r)
        components.append(count)
    baymeta=v.meta.get('bay_clear',{'min':[2,1,-1],'max':[4,3,1]})
    bay=[(x,y,z) for x in range(baymeta['min'][0],baymeta['max'][0]+1) for y in range(baymeta['min'][1],baymeta['max'][1]+1) for z in range(baymeta['min'][2],baymeta['max'][2]+1)]
    return {'name':NAMES[v.name],'revision':v.revision,'block_count':n,'counts_by_id':dict(sorted(collections.Counter(splitstate(s)[0] for s in b.values()).items())),
        'bbox_min':lo,'bbox_max':hi,'size_xyz':[hi[i]-lo[i]+1 for i in range(3)],'centre_of_mass':com,
        'mass_model':'Uniform unit mass per placed block (including thin blocks); centers at index+0.5, NOT Sable material masses.',
        'sable_material_budget':material_balance(v),
        'thrust':thrust,'ground_yaw':yaw,'connected_components':sorted(components,reverse=True),
        'occupancy_mirror_mismatches':sum((x,y,-z) not in b for x,y,z in b),
        'bay_clear_27':all(p not in b for p in bay),'metadata':v.meta}

def rel(p): return ' '.join('~'+(str(n) if n else '') for n in p)

def settle_stair_shapes(v):
    """Match vanilla's neighbour-derived inner/outer corners before export.

    Runtime r4 found 8 A and 4 C stairs differing from authored straight;
    facing/half were correct. Do not fight normal neighbour updates in-world.
    """
    turn={'north':'west','west':'south','south':'east','east':'north'}
    def stair(p):
        name,props=splitstate(v.b.get(p,'minecraft:air'))
        return props if name.endswith('_stairs') else None
    for pos,value in list(v.b.items()):
        own=stair(pos)
        if own is None:continue
        facing=own['facing'];half=own['half'];shape='straight'
        def different(direction):
            other=stair(add(pos,DIR[direction]))
            return other is None or other.get('facing')!=facing or other.get('half')!=half
        front=stair(add(pos,DIR[facing]));back=stair(add(pos,DIR[OPP[facing]]))
        def perpendicular(other):
            return other and other.get('half')==half and other['facing'] not in (facing,OPP[facing])
        if perpendicular(front) and different(OPP[front['facing']]):
            shape='outer_left' if front['facing']==turn[facing] else 'outer_right'
        elif perpendicular(back) and different(back['facing']):
            shape='inner_left' if back['facing']==turn[facing] else 'inner_right'
        name,props=splitstate(value);props['shape']=shape;v.b[pos]=state(name,**props)

def refine_hauler(v):
    """Living cockpit and light cowls, before the dev.87 deployable devices."""
    # Cut both old nose bulkheads down below eye level, not just the roof glass.
    v.fill(-20,5,-5,-12,10,5,'air')
    v.fill(-11,5,-4,-6,10,4,'air')
    v.fill(-12,4,-3,-8,4,3,'deck')
    v.fill(-11,5,-2,-11,7,2,'glass')
    for side in (-1,1):
        for x in range(-11,-5):
            for y in range(5,9):v.put(x,y,side*3,'glass')
        v.put(-11,4,side*3,'trim')
    v.fill(-11,8,-2,-6,8,2,'glass')
    v.fill(-11,9,-3,-6,10,3,'air')
    v.put(-9,5,0,state('helm',facing='west'))
    for z in (-2,2):v.put(-9,4,z,'light')
    for z in (-2,2):v.put(-7,5,z,state('minecraft:lectern',facing='west',has_book=False,powered=False),{'id':'minecraft:lectern'})
    # 13-block pods become 9-block pods. Preserve bells at x=17..20.
    for side in (-1,1):
        v.fill(8,1,side*8-3,11,7,side*8+3,'air')
        for x in range(12,18):
            for dy in range(-3,4):
                for dz in range(-3,4):
                    p=(x,4+dy,side*8+dz)
                    radius=5 if x==12 else 8 if x==13 else 10
                    if dy*dy+dz*dz>radius:v.put(*p,'air')
                    elif p in v.b or x<14:
                        v.put(*p,'frame' if dy<0 else 'stripe' if dy==0 else 'shell')
        # Cyan continuity on the joining shoulder, never across the stair corridor.
        for x in (15,16):
            for z in range(3,8):v.put(x,4,side*z,'stripe')
    for x in range(-14,19,4):
        for z in range(-3,4):
            if (x,0,z) in v.b:v.put(x,0,z,'panel')
    for x in (-12,-8,-4,0,4,12,16):
        for z in (-1,1):
            if (x,0,z) in v.b:v.put(x,0,z,'light')
    v.put(-10,0,0,state('scope',facing='down'))
    for side in (-1,1):
        for x in (-6,-3):
            v.put(x,4,side*4,'amber');v.put(x,5,side*4,'amber')
        for x in (-5,-4):v.put(x,3,side*4,'amber')
    # Runner and trim: entry, reading nook and helm retain a clear centre aisle.
    for x in range(-10,11):v.put(x,4,0,'minecraft:cyan_terracotta')
    for x in range(-5,6):
        for z in (-4,4):v.put(x,6,z,'stripe')
    # Restore door upper halves overwritten by the wall trim.
    for side in (-1,1):
        for x in (-5,-4):
            v.put(x,6,side*4,state('door',facing='north' if side>0 else 'south',half='upper',hinge='left' if (x==-5)==(side==1) else 'right',open=False,powered=False))
    # Cockpit screen with wide open entry; cabin/aft engineering has a real door.
    for z in (-3,-2,2,3):
        for y in (5,6,7):v.put(-6,y,z,'glass' if y>5 else 'panel')
    # Keep BOTH inside door buttons accessible after fitting the cockpit screen.
    for side in (-1,1):v.put(-6,5,side*3,state('minecraft:stone_button',face='wall',facing='north' if side>0 else 'south',powered=False))
    for z in range(-3,4):
        for y in (5,6):v.put(5,y,z,'panel' if y==5 else 'glass')
    for y,half in ((5,'lower'),(6,'upper')):
        v.put(5,y,0,state('minecraft:oak_door',facing='east',half=half,hinge='left',open=False,powered=False))
    # Bunks on each side; raise this section's roof to preserve upper-bunk clearance.
    for x in range(-1,3):
        v.fill(x,7,-3,x,8,3,'air');v.fill(x,9,-3,x,9,3,'shell')
        for z in (-4,4):v.fill(x,7,z,x,9,z,'shell')
    for z in (-3,3):
        for x in (0,1):v.put(x,6,z,state('minecraft:smooth_stone_slab',type='top',waterlogged=False))
        for x,part in ((0,'foot'),(1,'head')):v.put(x,7,z,state('minecraft:cyan_bed',part=part,facing='east',occupied=False))
    for x,y in ((-7,8),(-2,9),(2,9),(7,7),(10,7)):
        for z in (-2,2):v.put(x,y,z,'light')
    # Engineering room above the existing ventral hatch, accessed from the cabin.
    v.fill(6,5,-2,10,6,2,'air');v.fill(6,7,-2,10,7,2,'shell')
    for x in range(6,11):
        v.fill(x,4,-2,x,4,2,'deck')
        for z in (-3,3):
            v.put(x,5,z,'panel');v.put(x,6,z,'glass')
    v.fill(11,5,-2,11,6,2,'panel')
    v.put(6,4,-1,state('minecraft:oak_trapdoor',facing='west',half='top',open=False,powered=False,waterlogged=False))
    for y in range(1,4):
        v.put(5,y,-1,'frame');v.put(6,y,-1,state('minecraft:ladder',facing='east',waterlogged=False))
    # Quarry is installed after the lift nozzle pass, in the ventral bay.
    # Upper supply locker is directly reachable from the engineering doorway.
    v.put(9,5,-2,state('minecraft:chest',facing='west',type='single',waterlogged=False),{'id':'minecraft:chest','Items':(10,[])})
    v.meta['bay_clear']['occupied_by']='integrated mining rig'
    close_hauler(v)
    finish_hauler(v)


def finish_hauler(v):
    """Stepped pressure roof, spine and provisions; no dorsal weapon access."""
    for x in list(range(-5,-1))+list(range(2,6)):
        v.fill(x,9,-4,x,9,4,'air')
        v.fill(x,8,-4,x,8,4,'shell')
    for x in range(-4,5):
        v.put(x,10 if -1<=x<=1 else 9,0,'panel')
    for y in (10,11):
        v.put(3,y,0,state('minecraft:iron_bars',north=False,south=False,east=False,west=False,waterlogged=False))
    for x in (-3,3):
        for z in (-2,2):v.put(x,8,z,'light')
    # The remotely operated mast needs no dorsal access ladder or hatch.
    def chest(p,items):
        v.put(*p,state('minecraft:chest',facing='west',type='single',waterlogged=False),
              {'id':'minecraft:chest','Items':(10,[{'Slot':Byte(i),'id':'minecraft:'+item,'count':count} for i,(item,count) in enumerate(items)])})
    chest((3,5,-3),[('bread',32),('cooked_beef',16)])
    chest((9,5,-2),[('torch',32),('iron_pickaxe',1),('water_bucket',1)])
    v.meta['provisions']={'cabin':[3,5,-3],'engineering':[9,5,-2],'book_slots':[4,5]}


def link_nbt(item, transmitter=False):
    return {'id':'create:redstone_link','Transmitter':Byte(transmitter),
            'FrequencyFirst':{'id':'minecraft:'+item,'count':1},
            'FrequencyLast':{'id':'minecraft:redstone','count':1}}


def sign(v,p,text,detail=''):
    v.put(*p,state('minecraft:oak_wall_sign',facing='south',waterlogged=False),
          {'id':'minecraft:sign','front_text':{'messages':(8,[json.dumps({'text':s},ensure_ascii=False) for s in (text,detail,'','')]),'color':'black','has_glowing_text':Byte(0)}})


def deployable_devices(v):
    """dev.87: drillrig1's connected pulley quarry, outside the pressure cabin."""
    # Remove the complete old coal/auger/funnel installation, including its NBT.
    for p in ((7,0,0),(7,1,0),(7,2,0),(7,3,0),(7,4,0),(7,5,0),
              (8,5,0),(8,6,0),(9,5,0),(8,2,0),(8,1,0),(9,1,0)):
        v.put(*p,'air')
    v.put(7,4,0,'deck')
    # Empty moat around the slime plate: it must never drag the fixed hull.
    for x in range(5,10):
        for y in (1,2):
            for z in range(-2,3):
                if x==9 and abs(z)==2:continue # existing lift nozzles/backing
                v.put(x,y,z,'air')
    v.fill(6,0,-1,8,0,1,'air')
    v.fill(6,3,-1,8,3,1,'air')
    v.put(7,3,0,state('create:rope_pulley',axis='x'),{'id':'create:rope_pulley','ScrollValue':1})
    v.put(7,2,0,'minecraft:stone')
    for x in range(6,9):
        for z in range(-1,2):
            v.put(x,1,z,'minecraft:slime_block')
            v.put(x,0,z,state('create:mechanical_drill',facing='down',waterlogged=False))
    v.put(6,2,0,state('minecraft:chest',facing='north',type='single',waterlogged=False),{'id':'minecraft:chest','Items':(10,[])})
    for x,kind in ((8,'gearshift'),(9,'shaft'),(10,'clutch')):
        v.put(x,3,0,state('create:'+kind,axis='x',**({'powered':True} if kind!='shaft' else {})))
    v.put(11,3,0,state('create:creative_motor',facing='west'),{'id':'create:creative_motor','ScrollValue':-32})
    # RX on the side rather than through the pressure floor; the same channel/polarity.
    for x,item in ((8,'iron_ingot'),(10,'gold_ingot')):
        v.put(x,3,1,state('create:redstone_link',facing='south',receiver=True),link_nbt(item))
    # Console aft of the pilot eye, outside the centre aisle and forward glass.
    for x,item,text,detail,on in ((-8,'iron_ingot','钻机 降下/收回','亮=收回 灭=降下',True),
                                  (-7,'gold_ingot','钻机 运转/停止','亮=停止 灭=运转',True),
                                  (-6,'diamond','炮塔 升起/收回','亮=升起 灭=收回',False)):
        for z in (-2,-1):v.put(x,4,z,'panel')
        # Lever inboard: a lever powers the panel under it, and the panel at z=-2 backs a side thruster.
        v.put(x,5,-1,state('minecraft:lever',face='floor',facing='north',powered=on))
        v.put(x,5,-2,state('create:redstone_link',facing='up',receiver=False),link_nbt(item,True))
        v.put(x,6,-3,'panel')
        sign(v,(x,6,-2),text,detail)
    v.put(-5,7,-3,'panel');sign(v,(-5,7,-2),'先按 R 离座再扳拉杆')
    # Both variants retain the same ordinary closed chin plate.
    v.fill(-15,4,-1,-12,4,1,'panel')
    v.meta['mining_rig']={'pulley':[7,3,0],'head':[7,0,0],'chest':[6,2,0],
        'stroke':'rope','placement_mode':1,'default':'stowed, iron ON; stopped, gold ON',
        'channels':['iron_ingot/redstone','gold_ingot/redstone','diamond/redstone']}
    v.meta['console']=[[-8,5,-2],[-7,5,-2],[-6,5,-2]]


WEAPON_ORIGIN=(11,4,-3)
WEAPON_SIZE=(4,6,5)

def weapon_module():
    """Upright three-block mast in the aft service space, behind bulkhead x=11.

    Arm stows at roof height y=9 and deploys at y=12. Poles stay outside the
    pressure cabin; the slime carrier has an empty moat, not a hull attachment.
    """
    v=Voxel('B',104)
    def put(x,y,z,s,nbt=None):
        p=tuple(q-o for q,o in zip((x,y,z),WEAPON_ORIGIN))
        v.put(*p,s,nbt)
    # One low motor, split BEFORE the reversing gearshift. Gearbox axis is
    # the axis WITHOUT a shaft (Create 6.0.10 GearboxBlock.hasShaftTowards).
    put(11,7,-1,'panel') # attachment to the unchanged aft bulkhead
    put(12,7,0,state('create:sticky_mechanical_piston',facing='up',axis_along_first=False),{'id':'create:mechanical_piston','ScrollValue':0})
    for y in (4,5,6):put(12,y,0,state('create:piston_extension_pole',facing='up'))
    put(14,7,-3,state('create:creative_motor',facing='south'),{'id':'create:creative_motor','ScrollValue':32})
    for x in (12,14):put(x,7,-2,state('create:gearbox',axis='y'))
    put(13,7,-2,state('create:gearshift',axis='x',powered=False))
    put(13,7,-3,state('create:redstone_link',facing='north',receiver=True),link_nbt('diamond'))
    for x in (12,14):put(x,7,-1,state('create:shaft',axis='z'))
    put(14,7,0,state('create:gearbox',axis='x'))
    put(14,8,0,state('create:cogwheel',axis='y'))
    # Moving train: bottom y=5 -> 8, top y=9 -> 12. Only the bottom cog
    # meshes with the fixed y=8 cog at full extension; shafts cannot mesh.
    put(12,8,0,'minecraft:slime_block')
    for y in range(5,10):
        put(13,y,0,state('create:cogwheel' if y in (5,9) else 'create:shaft',axis='y'))
    # Slime has only six neighbours; it does NOT pull a whole shaft column.
    # The bridge chassis touches the slime. Same-kind/axis chassis join via
    # ChassisBlockEntity.addAttachedChasses; each north glued face captures
    # exactly its one kinetic neighbour (getIncludedBlockPositionsLinear).
    # Persist the range as ScrollValue, not the UI-only selected range.
    for x,y,z in [(12,8,1)]+[(13,y,1) for y in range(5,10)]:
        put(x,y,z,state('create:linear_chassis',axis='z',sticky_top=False,sticky_bottom=True),
            {'id':'create:chassis','ScrollValue':1})
    # minigun_data.json: inventory feed, no ammo_amount -> GunData default 30;
    # first damage-adjust distance 50 -> min(256, 2*50)=100 range.
    put(12,9,0,state('sentrymechanicalarm:sentry_mechanical_arm',ceiling=False),{
        'id':'sentrymechanicalarm:sentry_mechanical_arm','ScrollValue':100,
        'SentryHeldItem':{'id':'tacz:modern_kinetic_gun','count':1,'components':{'minecraft:custom_data':{
            'GunId':'tacz:minigun','GunFireMode':'AUTO','GunCurrentAmmoCount':30,'HasBulletInBarrel':Byte(0)}}},
        'SentryAmmoBoxes':{'Items':(10,[{'Slot':Byte(0),'id':'tacz:ammo_box','count':1,'components':{
            'minecraft:custom_data':{'AllTypeCreative':Byte(1)}}}])}})
    return v


def weapon_structure_bytes():
    # Sparse service-space overlay: no air cuts into the chin or pressure cabin.
    v=weapon_module();palette=sorted(set(v.b.values()));pal=[]
    for s in palette:
        name,props=splitstate(s);pal.append({'Name':name,**({'Properties':props} if props else {})})
    blocks=[{'pos':(3,list(p)),'state':palette.index(v.b[p]),**({'nbt':v.nbt[p]} if p in v.nbt else {})} for p in sorted(v.b)]
    return gzip.compress(nbt_bytes({'DataVersion':3955,'size':(3,list(WEAPON_SIZE)),'palette':(10,pal),'blocks':(10,blocks),'entities':(10,[])}),mtime=0)


def armed_hauler(v):
    import copy
    armed=copy.deepcopy(v)
    module=weapon_module()
    for p,s in module.b.items():armed.put(*add(p,WEAPON_ORIGIN),s,module.nbt.get(p))
    return armed


def forward_wings(v):
    """Swept chine extensions below the glazing; leave the door columns clear."""
    # Retire the short, dark foreplanes rather than stack wings over them.
    for x in range(-15,-10):
        for z in range(5,8):
            for y in (2,3):
                for side in (-1,1):v.put(x,y,side*z,'air')
    for span in range(8):
        z=4+span; leading=-10+(2*span)//3
        for x in range(leading,-5):
            for side in (-1,1):
                # Two touching half-slabs straddle y=2: one block TOTAL thickness.
                # Below the y=3 manoeuvring jet ray; never bury a nozzle in a wing.
                v.put(x,1,side*z,state('minecraft:polished_deepslate_slab',type='top',waterlogged=False))
                v.put(x,2,side*z,state('bevel',type='bottom',waterlogged=False))
        if span==6:
            for x in range(leading,-5):
                for side in (-1,1):
                    v.put(x,1,side*z,'air')
                    v.put(x,2,side*z,'stripe')
    for side in (-1,1):
        # One luminous tip cell, flush with the wing; no upright post.
        v.put(-6,2,side*11,'light')
    v.meta['forward_wings']='Seven-block swept span, cyan tip band, one flush sea-lantern navigation light per tip.'


def close_hauler(v):
    """dev.77: continuous pressure shell, manual doors and straight bay ladder."""
    # Replace the old open chamfers, including both raised-roof end transitions.
    v.fill(-5,7,-3,5,8,3,'air')
    v.fill(-5,9,-4,5,9,4,'shell')
    for z in (-4,4):v.fill(-5,7,z,5,8,z,'shell')
    v.fill(-6,8,-3,-6,9,3,'shell')
    v.fill(6,7,-3,6,9,3,'shell')
    # Restore upper bunks after clearing the obsolete low ceiling.
    for z in (-3,3):
        for x,part in ((0,'foot'),(1,'head')):v.put(x,7,z,state('minecraft:cyan_bed',part=part,facing='east',occupied=False))
    for x in (-3,2):
        for z in (-2,2):v.put(x,9,z,'light')
    # Close recessed wall seams; light-gray is a full pressure backing.
    for x in range(-5,6):
        for z in (-4,4):
            if (x,6,z) not in v.b:v.put(x,6,z,'panel')
    for x in range(6,11):
        for z in (-3,3):v.put(x,7,z,'shell')
    # Vanilla birch doors: pale panels, no dependency-specific assembly behaviour.
    for p,s in list(v.b.items()):
        name,props=splitstate(s)
        if name=='minecraft:iron_door':v.put(*p,state('minecraft:birch_door',**props))
        if name=='minecraft:stone_button':v.put(*p,'air')
    # Dark only at the bottom two layers; full backing behind inset side lights.
    for x in range(-14,19):
        for z in (-4,-3,3,4):
            for y in (2,3):
                if (x,y,z) in v.b:v.put(x,y,z,'panel' if x%4 else 'shell')
    for x in (-10,-6,-2,2):
        for side in (-1,1):
            v.put(x,2,side*4,'air');v.put(x,2,side*3,'light')
    # Move ladder away from the partition: straight climb plus clear top exit.
    v.put(6,4,-1,'deck')
    for y in range(1,4):v.put(6,y,-1,'air')
    v.fill(9,1,-2,10,3,-1,'air')
    for y in range(1,5):
        v.put(11,y,-1,'panel')
        v.put(10,y,-1,state('minecraft:ladder',facing='west',waterlogged=False))
    v.put(10,4,-1,state('minecraft:oak_trapdoor',facing='east',half='top',open=False,powered=False,waterlogged=False))
    v.fill(9,0,-2,10,0,-1,'floor')
    v.meta['bay_clear']['ladder']=[10,1,-1]
    v.meta['bay_access']={'hatch':[10,4,-1],'top_exit':[9,5,-1],'stand':[10,1,-1]}


def verify_sealed_cabin(v):
    """Six-neighbour air flood with closed door/hatch cells as explicit plugs.

    Thin furniture is permeable: beds, ladders, controls and stairs cannot hide a
    leak. This is a conservative voxel shell check, not an oxygen simulation.
    """
    lo,hi=v.bounds()
    def wall(p):
        name,props=splitstate(v.b.get(p,'minecraft:air'))
        if name.endswith(('_door','_trapdoor')):return True
        return name!='minecraft:air' and not any(t in name for t in ('_bed','_slab','_stairs','ladder','lever','button','lectern','metal_girder','shaft','funnel','wall_sign','redstone_link'))
    seeds=[(x,6,z) for x in range(-10,11) for z in range(-2,3) if not wall((x,6,z))]
    seen=set(seeds);todo=collections.deque(seeds)
    while todo:
        p=todo.popleft()
        if any(p[i]<lo[i] or p[i]>hi[i] for i in range(3)):
            raise ValueError(f'Cabin leaks to outside at {p}')
        for d in DIR.values():
            q=add(p,d)
            if q not in seen and not wall(q):seen.add(q);todo.append(q)
    return len(seen)


def gravity_engines(v):
    """dev.85: ten recessed keel lifts and real 2x2 nacelle engine clusters."""
    # Retain the four dorsal jets. Retire all other old single fittings.
    for p,s in list(v.b.items()):
        name,props=splitstate(s)
        if name==PARTS['thruster'] and props['facing']!='up':v.put(*p,'frame')
    def nozzle(p,f):
        d=DIR[f]
        v.put(*add(p,tuple(-n for n in d)),'frame')
        v.put(*p,state('thruster',facing=f,power=0))
        for n in range(1,50):v.put(*(p[i]+n*d[i] for i in range(3)),'air')
    for side in (-1,1):
        for x in (-7,-1,4,9,15):
            # Recess within a dark keel well, backed below the pressure floor.
            for xx in (x-1,x,x+1):
                for zz in (side*2-1,side*2,side*2+1):
                    v.put(xx,0,zz,'floor')
            nozzle((x,1,side*2),'down')
        for y in (3,4):
            for z in (side*8,side*9):
                nozzle((18,y,z),'east')
                nozzle((12,y,z),'west')
            for x in (-8,-7,15,16):
                nozzle((x,y,side*(3 if x<0 else 10)),'south' if side>0 else 'north')
    # Retired short-pod nozzle mounts: no detached collar blocks after the
    # old nozzle-fit pass chooses its station from the changed device mass.
    for side in (-1,1):
        for y,z in ((2,8),(3,7),(4,7),(5,8)):v.put(9,y,side*z,'air')
    v.meta['gravity_engines']='10 lift; 8 each forward/reverse/left/right; 4 dorsal push-down; BOOST unchanged.'


def aircraft_hangar(v):
    """Open 3x5 well: forward pressure step and aft mast forbid a 5x5 pad.

    Hatch is diagonally outside the rotor/capture footprint. It is a copper
    trapdoor: metal, and opened by hand from either side, no lever to forget.
    """
    for x in range(7,11):
        for z in (-3,3):
            v.put(x,8,z,'frame')
            v.put(x,9,z,'glass')
    for z in range(-2,3):
        v.put(10,8,z,'panel')
        v.put(10,9,z,'frame')
    for x in range(7,10):
        for z in range(-2,3):
            v.put(x,7,z,'amber' if abs(z)==2 else 'panel')
    for x in (7,9):
        for z in (-1,1):v.put(x,7,z,'light')
    v.put(8,7,0,'starfold:aircraft_dock')
    for y in (5,6):
        v.put(9,y,2,state('minecraft:ladder',facing='north',waterlogged=False))
    v.put(9,7,2,state('minecraft:waxed_oxidized_copper_trapdoor',facing='north',half='top',open=False,powered=False,waterlogged=False))
    v.meta['aircraft_dock']=[8,7,0]
    v.meta['hangar']={'floor':[[7,7,-2],[9,7,2]],'hatch':[9,7,2],
        'ladder':[[9,5,2],[9,6,2]],
        'seal':'hand-operated copper hatch, closed', 'exit':[9,5,1]}


def runtime_build(key):
    """Runtime-r4 lineage, with dev.76 Hauler refinement before nozzle fitting."""
    import sys, r3_geometry
    v=r3_geometry.build(sys.modules[__name__],key,2,refine_hauler if key=='B' else None)
    # Keep the accepted manoeuvring stations: the winged COM still meets 0.5.
    if key=='B':
        forward_wings(v)
        # White pressure cowls conceal the inner/side backs of the cockpit jets.
        # No blocks in the exhaust rays or the eye-level forward glazing.
        for side in (-1,1):
            v.put(-7,6,side*2,'shell')
            v.put(-8,7,side*2,'shell')
            for y in (6,7):v.put(-7,y,side*3,'shell')
        gravity_engines(v)
        deployable_devices(v)
        aircraft_hangar(v)
        assert max(t['offset_length'] for t in stats(v)['thrust'].values())<.5
        assert max(t['offset_length'] for t in stats(armed_hauler(v))['thrust'].values())<.5
        for variant in (v,armed_hauler(v)):
            assert stats(variant)['connected_components']==[len(variant.b)]
            assert max(t['offset_length'] for t in material_balance(variant)['thrust'].values())<.5
    settle_stair_shapes(v)
    if key=='B':v.meta['sealed_cabin_cells']=verify_sealed_cabin(v)
    # Unpowered rose-quartz console lamps were dark. No new redstone wiring.
    for pos,value in list(v.b.items()):
        if splitstate(value)[0]=='create:rose_quartz_lamp':v.b[pos]='minecraft:sea_lantern'
        # Tone down only the Hauler's 24 bright-orange outer bell lip blocks.
        if key=='B' and value=='minecraft:waxed_copper_block':v.b[pos]='minecraft:waxed_exposed_copper'
    v.meta['runtime_r4']='Valid copper ID; neighbour-derived stair shapes; passive console lights; B toned-down bell lips.'
    return v

def export(v,out):
    out.mkdir(parents=True,exist_ok=True)
    lo,hi=v.bounds(); ordered=sorted(v.b,key=lambda p:(p[1],p[0],p[2]))
    records=[{'x':p[0],'y':p[1],'z':p[2],'blockstate':v.b[p],**({'nbt_snbt':snbt(v.nbt[p])} if p in v.nbt else {})} for p in ordered]
    (out/'blocks.json').write_text(json.dumps({'coordinate_system':'bow -X, up +Y, port +Z; origin is (0,0,0), below main deck at y=4','blocks':records},indent=2)+'\n')
    # No teleport, gamemode, world mutation, launchpad or quit: opt-in placement artifact only.
    # Every relative command assumes an unchanged executor origin. Bbox clear is intentional.
    commands=['strict',('# R4 placement checked; only B has bounded flight evidence. Not release acceptance.' if 'runtime_r4' in v.meta else '# DESK OUTPUT / TO TEST.'),
        '# Executor must stay fixed at chosen origin.',
        '# This file clears only its exact ship AABB; choose an empty isolated berth.', 'waitworld',
        'cmd fill '+rel(lo)+' '+rel(hi)+' minecraft:air','wait 40']
    if v.name=='B' and 'runtime_r4' in v.meta:
        commands.insert(4,'# Optional dev.104 mast is a separate sparse NBT overlay: one low motor, moving chassis/shaft train.')
    for p in ordered:
        commands.append('cmd setblock '+rel(p)+' '+v.b[p]+(snbt(v.nbt[p]) if p in v.nbt else ''))
    commands+=['wait 60','cmd sable assemble area '+rel(lo)+' '+rel(hi),'wait 200','cmd sable info @e']
    (out/'commands.txt').write_text('\n'.join(commands)+'\n')
    palette=sorted(set(v.b.values())|{'minecraft:air'}); index={s:i for i,s in enumerate(palette)}
    pal=[]
    for s in palette:
        name,props=splitstate(s); pal.append({'Name':name,**({'Properties':props} if props else {})})
    blocks=[]
    # Include air: loading into nonempty space must not fill the bay/cabin with old terrain.
    for y in range(lo[1],hi[1]+1):
        for x in range(lo[0],hi[0]+1):
            for z in range(lo[2],hi[2]+1):
                p=(x,y,z); q={'pos':(3,[p[i]-lo[i] for i in range(3)]),'state':index[v.b.get(p,'minecraft:air')]}
                if p in v.nbt: q['nbt']=v.nbt[p]
                blocks.append(q)
    tree={'DataVersion':3955,'size':(3,[hi[i]-lo[i]+1 for i in range(3)]),'palette':(10,pal),'blocks':(10,blocks),'entities':(10,[])}
    data=nbt_bytes(tree); target=out/'ship.nbt'; target.write_bytes(gzip.compress(data,mtime=0))
    loaded=read_nbt(gzip.decompress(target.read_bytes())); assert loaded==tree
    restored={}
    for q in loaded['blocks'][1]:
        p=tuple(q['pos'][1][i]+lo[i] for i in range(3)); s=palette[q['state']]
        if s!='minecraft:air': restored[p]=s
    assert restored==v.b
    st=stats(v); st['nbt_roundtrip_verified']=True; st['nbt_sha256']=hashlib.sha256(target.read_bytes()).hexdigest()
    st['structure_origin_offset']=lo; st['structure_block_entries_including_air']=len(blocks)
    (out/'stats.json').write_text(json.dumps(st,indent=2)+'\n')
    return st

# Original lightweight software renderer: opaque tinted glass with dark inset frame,
# directional face lighting, orthographic painter's algorithm, scanline polygons.
COLORS={
 'shell':(218,228,233),'panel':(151,167,178),'trim':(241,239,222),'frame':(57,71,85),
 'rib':(82,97,108),'glass':(92,183,201),'window':(108,172,184),'stripe':(13,142,160),
 'amber':(237,157,57),'floor':(69,81,91),'deck':(166,179,184),'light':(208,250,241),
 'lamp':(248,156,196),'ring':(170,112,74),'thruster':(39,48,62),'gyro':(114,130,152),
 'booster':(191,134,66),'helm':(30,198,204),'scope':(51,179,189),'sill':(90,106,119),
 'door':(193,210,214),'bevel':(241,239,222),
 'minecraft:cyan_bed':(18,141,158),'minecraft:chest':(179,133,79),'minecraft:furnace':(101,109,119),
 'minecraft:crafting_table':(160,123,86),'minecraft:lectern':(173,145,101),'minecraft:ladder':(183,158,118),
 'minecraft:iron_trapdoor':(109,131,144),'minecraft:waxed_oxidized_copper_trapdoor':(79,171,144),'minecraft:lever':(204,104,66),'minecraft:stone_button':(102,119,127),
 'minecraft:smooth_stone_slab':(162,179,189),
 'minecraft:red_concrete':(174,42,36),'minecraft:lime_concrete':(92,170,30),
 'minecraft:polished_deepslate_slab':(69,81,91)}
COLORS={PARTS.get(k,k):v for k,v in COLORS.items()}
FONT={
'A':['01110','10001','10001','11111','10001','10001','10001'],
'B':['11110','10001','10001','11110','10001','10001','11110'],
'C':['01111','10000','10000','10000','10000','10000','01111'],
'D':['11110','10001','10001','10001','10001','10001','11110'],
'E':['11111','10000','10000','11110','10000','10000','11111'],
'F':['11111','10000','10000','11110','10000','10000','10000'],
'G':['01111','10000','10000','10111','10001','10001','01111'],
'H':['10001','10001','10001','11111','10001','10001','10001'],
'I':['111','010','010','010','010','010','111'],
'J':['00111','00010','00010','00010','10010','10010','01100'],
'K':['10001','10010','10100','11000','10100','10010','10001'],
'L':['10000','10000','10000','10000','10000','10000','11111'],
'M':['10001','11011','10101','10101','10001','10001','10001'],
'N':['10001','11001','11001','10101','10011','10011','10001'],
'O':['01110','10001','10001','10001','10001','10001','01110'],
'P':['11110','10001','10001','11110','10000','10000','10000'],
'Q':['01110','10001','10001','10001','10101','10010','01101'],
'R':['11110','10001','10001','11110','10100','10010','10001'],
'S':['01111','10000','10000','01110','00001','00001','11110'],
'T':['11111','00100','00100','00100','00100','00100','00100'],
'U':['10001','10001','10001','10001','10001','10001','01110'],
'V':['10001','10001','10001','10001','10001','01010','00100'],
'W':['10001','10001','10001','10101','10101','10101','01010'],
'X':['10001','10001','01010','00100','01010','10001','10001'],
'Y':['10001','10001','01010','00100','00100','00100','00100'],
'Z':['11111','00001','00010','00100','01000','10000','11111'],
'0':['01110','10001','10011','10101','11001','10001','01110'],
'1':['010','110','010','010','010','010','111'],
'2':['01110','10001','00001','00010','00100','01000','11111'],
'3':['11110','00001','00001','01110','00001','00001','11110'],
'4':['10010','10010','10010','11111','00010','00010','00010'],
'5':['11111','10000','10000','11110','00001','00001','11110'],
'6':['01110','10000','10000','11110','10001','10001','01110'],
'7':['11111','00001','00010','00100','01000','01000','01000'],
'8':['01110','10001','10001','01110','10001','10001','01110'],
'9':['01110','10001','10001','01111','00001','00001','01110'],
'-':['000','000','000','111','000','000','000'],
'/':['00001','00010','00010','00100','01000','01000','10000'],
'.':['0','0','0','0','0','1','1'],
' ':['000']*7}
class Image:
    def __init__(self,w,h,bg=(17,26,39)): self.w=w; self.h=h; self.p=bytearray(bytes(bg)*(w*h))
    def rect(self,x,y,w,h,c):
        x0=max(0,int(x)); x1=min(self.w,int(x+w)); y0=max(0,int(y)); y1=min(self.h,int(y+h))
        if x1<=x0 or y1<=y0:return
        row=bytes(c)*(x1-x0)
        for yy in range(y0,y1): self.p[(yy*self.w+x0)*3:(yy*self.w+x1)*3]=row
    def poly(self,points,c):
        low=max(0,math.floor(min(p[1] for p in points))); high=min(self.h-1,math.ceil(max(p[1] for p in points)))
        for y in range(low,high+1):
            yy=y+.5; xs=[]
            for a,b in zip(points,points[1:]+points[:1]):
                if (a[1]<=yy<b[1]) or (b[1]<=yy<a[1]): xs.append(a[0]+(yy-a[1])*(b[0]-a[0])/(b[1]-a[1]))
            if len(xs)>=2:
                l=max(0,math.ceil(min(xs)-.5)); r=min(self.w,math.ceil(max(xs)-.5))
                if r>l: self.p[(y*self.w+l)*3:(y*self.w+r)*3]=bytes(c)*(r-l)
    def text(self,x,y,text,scale=3,color=(196,217,226)):
        for ch in text.upper():
            glyph=FONT.get(ch,FONT[' '])
            for yy,row in enumerate(glyph):
                for xx,b in enumerate(row):
                    if b=='1': self.rect(x+xx*scale,y+yy*scale,scale,scale,color)
            x+=(len(glyph[0])+1)*scale
    def save(self,path):
        def chunk(t,d): return struct.pack('>I',len(d))+t+d+struct.pack('>I',zlib.crc32(t+d)&0xffffffff)
        data=b''.join(b'\0'+self.p[y*self.w*3:(y+1)*self.w*3] for y in range(self.h))
        path.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',self.w,self.h,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(data,6))+chunk(b'IEND',b''))
    def paste_scaled(self,other,x,y,w,h):
        for yy in range(h):
            sy=min(other.h-1,int(yy*other.h/h))
            row=bytearray()
            for xx in range(w):
                sx=min(other.w-1,int(xx*other.w/w)); off=(sy*other.w+sx)*3; row.extend(other.p[off:off+3])
            self.p[((y+yy)*self.w+x)*3:((y+yy)*self.w+x+w)*3]=row

def norm(v):
    n=math.sqrt(sum(x*x for x in v)); return tuple(x/n for x in v)
def dot(a,b):return sum(x*y for x,y in zip(a,b))
def cross(a,b):return (a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
VIEWS={'front':(-1.5,1.1,1.7),'rear':(1.5,-.8,-1.7),'side':(0,0,1),'plan':(0,1,0),'bow':(-1,0,0),'cutaway':(-1,1.8,1.1)}
TITLES={'front':'FRONT LEFT / ABOVE','rear':'REAR RIGHT / BELOW','side':'PORT ELEVATION - BOW LEFT','plan':'PLAN - BOW LEFT','bow':'BOW ELEVATION','cutaway':'CUTAWAY / ROOF AND PORT WALL REMOVED'}
def render(v,view,width=1400,height=1000,silhouette=False):
    if v.revision>=3:
        import r3_render
        return r3_render.render(__import__(__name__),v,view,width,height,silhouette)
    cam=norm(VIEWS[view]); right=norm(cross((0,1,0),cam)) if view!='plan' else (1,0,0)
    up=cross(cam,right)
    b={p:s for p,s in v.b.items() if view!='cutaway' or (p[1]<7 and not(p[2]>=2 and p[1]>=5) and not(2<=p[0]<=5 and p[1]==4 and abs(p[2])<=1))}
    faces=[]
    for p,s in b.items():
        name,props=splitstate(s); color=COLORS.get(name,(163,134,111))
        # Thin solids represented with approximate geometry rather than misleading cubes.
        lower=[float(x) for x in p]; upper=[x+1. for x in p]
        if name.endswith('_slab'):
            if props.get('type')=='top':lower[1]+=.5
            elif props.get('type')!='double':upper[1]-=.5
        if name.endswith('_bed'):upper[1]=lower[1]+.56
        if name.endswith('_trapdoor'):lower[1]=upper[1]-.1875
        if name.endswith('_door'):
            if props.get('facing') in ('north','south'):lower[2]+=.42;upper[2]-=.42
        if name.endswith('ladder'): lower[0]+=.82
        if name.endswith('lever') or name.endswith('button'):
            lower=[x+.3 for x in lower];upper=[x-.3 for x in upper]
        for axis in range(3):
            for sign in (-1,1):
                normal=tuple(sign if i==axis else 0 for i in range(3))
                if dot(normal,cam)<=1e-6:continue
                neighbor=b.get(add(p,normal),'')
                thin=('_slab','_bed','_door','_trapdoor','ladder','lever','button')
                if neighbor and not splitstate(neighbor)[0].endswith(thin) and name not in (PARTS['glass'],PARTS['window']) and not name.endswith(thin):continue
                ij=[i for i in range(3) if i!=axis]; pts=[]
                for a,c in ((0,0),(1,0),(1,1),(0,1)):
                    q=list(lower);q[axis]=(upper if sign>0 else lower)[axis];q[ij[0]]=(upper if a else lower)[ij[0]];q[ij[1]]=(upper if c else lower)[ij[1]];pts.append(tuple(q))
                center=tuple(sum(q[i] for q in pts)/4 for i in range(3))
                shade=1.0 if axis==1 and sign>0 else .58 if axis==1 else .83 if axis==2 else .69
                if view=='rear':shade=max(shade,.70)
                rgb=tuple(int(c*shade) for c in color)
                faces.append((dot(center,cam),pts,rgb,name,props,normal))
    projected=[(dot(q,right),-dot(q,up)) for _,pts,*_ in faces for q in pts]
    xmin=min(q[0] for q in projected);xmax=max(q[0] for q in projected);ymin=min(q[1] for q in projected);ymax=max(q[1] for q in projected)
    scale=min((width-160)/(xmax-xmin),(height-245)/(ymax-ymin))
    def project(q):return ((dot(q,right)-(xmin+xmax)/2)*scale+width/2,(-dot(q,up)-(ymin+ymax)/2)*scale+height/2+25)
    im=Image(width,height)
    im.rect(50,50,7,64,(14,171,188));im.text(78,50,f'{v.name} / {NAMES[v.name]}',5)
    im.text(80,98,TITLES[view],2); im.text(width-210,55,f'REV {v.revision}',3)
    # Blueprint ruled background; no inaccurate ground shadow on bottom view.
    for x in range(50,width-49,50): im.rect(x,160,1,height-255,(23,35,49))
    for y in range(160,height-90,50): im.rect(50,y,width-100,1,(23,35,49))
    for depth,pts,rgb,name,props,normal in sorted(faces,key=lambda f:f[0]):
        pp=[project(q) for q in pts]
        edge=tuple(int(c*.80) for c in rgb);im.poly(pp,edge)
        center=tuple(sum(q[i] for q in pp)/4 for i in range(2))
        inset=[tuple(center[i]+(q[i]-center[i])*.975 for i in range(2)) for q in pp];im.poly(inset,rgb)
        if name in (PARTS['glass'],PARTS['window']):
            im.poly([tuple(center[i]+(q[i]-center[i])*.78 for i in range(2)) for q in pp],(72,130,148))
            im.poly([tuple(center[i]+(q[i]-center[i])*.62 for i in range(2)) for q in pp],rgb)
        if name in (PARTS['scope'],PARTS['thruster']) and DIR.get(props.get('facing'))==normal:
            im.poly([tuple(center[i]+(q[i]-center[i])*.73 for i in range(2)) for q in pp],(10,20,29))
            im.poly([tuple(center[i]+(q[i]-center[i])*.36 for i in range(2)) for q in pp],(32,144,162))
    im.rect(50,height-75,width-100,2,(37,69,83))
    im.text(55,height-54,f'{len(v.b)} BLOCKS / BOW -X / WHITE IRON CYAN',2)
    im.text(55,height-30,'OFFLINE SHAPE STUDY - NOT A GAME SCREENSHOT - GLASS IS TINTED OPAQUE',2)
    return im

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--revision',type=int,choices=(0,1,2),default=2);p.add_argument('--views',choices=('all','front'),default='all');p.add_argument('--only',choices=tuple(BUILDERS));args=p.parse_args()
    folder=ROOT/('final' if args.revision==2 else f'round{args.revision}')
    fronts=[];summary={}
    for key,builder in BUILDERS.items():
        if args.only and args.only!=key:continue
        v=builder(args.revision);out=folder/key; st=export(v,out);summary[key]=st
        for view in (VIEWS if args.views=='all' else ('front',)):
            im=render(v,view);im.save(out/f'{view}.png')
            if view=='front':fronts.append(im)
        print(key,len(v.b),st['connected_components'],st['bay_clear_27'],st['centre_of_mass'],flush=True)
    if len(fronts)==3:
        sheet=Image(2100,570);sheet.text(40,24,'STARFOLD / STARTER SHIP COMPETITION',4)
        for i,im in enumerate(fronts):sheet.paste_scaled(im,i*700,70,700,500)
        sheet.save(folder/'contact.png')
    (folder/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
if __name__=='__main__':
    import sys
    sys.dont_write_bytecode=True
    if '--runtime-r4' in sys.argv:
        parser=argparse.ArgumentParser(description=__doc__)
        parser.add_argument('--runtime-r4',action='store_true')
        parser.add_argument('--output',type=pathlib.Path,default=ROOT/'r4')
        parser.add_argument('--only',choices=('A','B','C'))
        args=parser.parse_args()
        for key in ('A', 'B', 'C'):
            if args.only and key!=args.only:continue
            result=export(runtime_build(key),args.output/key)
            print(key,result['block_count'],flush=True)
    elif '--design-round' in sys.argv:
        import r3_geometry
        r3_geometry.main(sys.modules[__name__])
    else: main()
