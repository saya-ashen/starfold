"""Round-3 original geometry, using shipgen's existing voxel/export/render APIs.
No runtime access. Iterations 0/1/2 are retained, reproducible review snapshots.
"""
import argparse, copy, json, math

def build(g,kind,it,refine=None):
    v=g.Voxel(kind,3+it)
    slab=lambda mat='smooth_quartz',half='bottom':g.state('minecraft:'+mat+'_slab',type=half,waterlogged=False)
    stair=lambda facing='west',mat='smooth_quartz',half='bottom':g.state('minecraft:'+mat+'_stairs',facing=facing,half=half,shape='straight',waterlogged=False)
    # The pressure volume is not the wing: nine external / seven clear blocks.
    # C keeps five-wide fore/aft spars and one short habitable blister.
    for x in range(-20,21):
        t=x+20
        if kind=='A':
            w=min(4,1+t//3); top=min(8,3+t//2); low=max(1,3-t//4)
            if x>6: top=max(4,8-(x-6)//4)
        elif kind=='B':
            w=min(4,1+t//2) if x<=4 else 2
            top=min(10,4+t//2) if x<=-4 else (8 if x<=4 else 5)
            low=max(1,3-t//3)
        else:
            w=0 if t==0 else (1 if t<5 else (2 if x< -10 or x>5 else 4))
            top=min(8,3+t//2) if x<=5 else max(4,7-(x-6)//6)
            low=max(1,3-t//4)
        for y in range(low,top+1):
            wy=max(0,w-1) if y in (low,top) else w
            for z in range(-wy,wy+1):
                s='frame' if y<=2 else 'floor' if y==3 else 'stripe' if y==4 and abs(z)==w else 'shell'
                v.put(x,y,z,s)
        # slab chines soften the pressure hull without enlarging the cabin.
        if w:
            for z in (-w,w): v.put(x,top,z,stair('north' if z<0 else 'south'))
    # Long dark keel; the nose remains a taper in both plan and profile.
    for x in range(-14,19):
        width=3 if kind!='C' or -10<=x<=5 else 1
        v.fill(x,0,-width,x,1,width,'frame')
    if kind=='A':
        # Genuine thin delta. Trailing edge engines are buried in its deep roots.
        for x in range(-13,21):
            w=min(10,4+(x+13)//4) if it==0 else min(10,4+(x+13)//3)
            if it>=2:w=min(10,4+(x+13)//5)
            elif x>17: w=10-(x-17)//2
            for z in range(4,w+1):
                v.mirror(x,2,z,'frame');v.mirror(x,3,z,'shell')
            v.mirror(x,3,w,stair('west') if it==0 else slab())
            if it>=1 and x<18:v.mirror(x,3,max(4,w-1),'stripe' if x%5!=0 else 'shell')
        for x in range(10,21):
            for z in (-6,6):
                for y in range(2,7):
                    for dz in range(-2,3):
                        if abs(dz)==2 and y in (2,6):continue
                        v.put(x,y,z+dz,'frame' if y<4 or x>=18 else 'shell')
        # Twin outward-stepped winglet blades, not floating diagonal blocks.
        for side in (-1,1):
            for y in range(4,8 if it==0 else 10):
                z=side*(8+(y-4)//3)
                for x in range(12+(y-4),19):v.put(x,y,z,'shell' if y<8 else 'stripe')
                if y>4 and (y-4)%3==0:
                    for x in range(12+y-4,19):v.put(x,y-1,z,'frame')
    elif kind=='B':
        # A long, open ventral frame beneath the narrow dorsal spine.
        fw=3 if it>=2 else 4
        for x in range(5,15):
            v.fill(x,0,-fw,x,0,fw,'floor')
            v.fill(x,1,-4,x,3,4,'air')
            for z in (-fw,fw):
                v.put(x,0,z,'rib');v.put(x,4,z,'rib')
                if x in (5,9,14):
                    for y in range(1,4):v.put(x,y,z,'rib')
            if it>=1:
                for z in (-fw,fw):
                    y=1+(x-5)%4
                    if y<4:v.put(x,y,z,'rib')
            for z in (-2,2):v.put(x,5,z,slab())
        # Two round-section nacelles separated from spine by daylight.
        for side in (-1,1):
            for x in range(9 if it==0 else 8,21):
                for dy in range(-3,4):
                    for dz in range(-3,4):
                        if dy*dy+dz*dz>10:continue
                        if x==8 and dy*dy+dz*dz<5:continue
                        v.put(x,4+dy,side*(8 if it>=2 else 7)+dz,'frame' if dy<0 or x in (8,18,19,20) else 'shell')
            for x in (10,11,15,16):
                for z in range(2,7):v.mirror(x,4,z,'rib' if x%2==0 else 'frame')
        # Stubby swept foreplanes, well ahead of the boarding doors.
        for x in range(-15,-10):
            w=5+(x+15)//2
            for z in range(3,w+1):
                v.mirror(x,2,z,'frame');v.mirror(x,3,z,slab())
    else:
        # Long thin outriggers, carried by swept midships wings, not hull corners.
        for x in range(-3 if it>=2 else -1,21):
            for z in ((-9,9) if it==0 else (-8,8)):
                radius=1 if it==0 or (it>=2 and x==-3) else 2
                for dy in range(-radius,radius+1):
                    for dz in range(-radius,radius+1):
                        if it>=1 and dy*dy+dz*dz>5:continue
                        v.put(x,4+dy,z+dz,'frame' if dy<0 or x in ((-3 if it>=2 else -1),18,19,20) else 'shell')
                if it>=1:v.put(x,6,z,slab())
                if x in (0,1):v.put(x,4,z,'floor')
        for z in range(3,10):
            lead=-1+(z-3)
            for x in range(lead,lead+5 if it==0 else lead+4):
                v.mirror(x,2,z,'frame');v.mirror(x,3,z,'shell')
            v.mirror(lead,3,z,stair())
            if it>=1:v.mirror(lead+1,3,z,'stripe')
        # Single tall swept dorsal blade. Each layer overlaps the layer below.
        for y in range(7,12):
            start=0+(y-7)*2 if it==0 else 2+(y-7)*2
            for x in range(start,17):v.put(x,y,0,'shell' if x<15 else 'stripe')
            v.put(start,y,0,stair())
    # Seven-clear-wide cabin; C is intentionally a short pressure blister.
    for x in range(-10,6):
        roof=10 if kind=='B' and x<=-4 else 8
        v.fill(x,4,-3,x,4,3,'deck')
        v.fill(x,5,-3,x,roof-1,3,'air')
        v.fill(x,roof,-3,x,roof,3,'shell')
        for z in (-4,4):
            for y in range(5,roof):v.put(x,y,z,'shell')
            v.put(x,4,z,'stripe')
    # Close cabin ends with central walkable bridge entry.
    for x in (-11,6):v.fill(x,5,-3,x,7,3,'shell')
    if kind=='B':v.fill(-11,8,-3,-11,9,3,'shell')
    # Sloped windscreen: three vertical risers distributed over five X stations.
    # Glass remains full-block for assembly; dark pillars and half stairs soften frame.
    canopy_start=-15 if kind!='C' else -14
    for x in range(canopy_start,-9):
        rise=min(3,(x-canopy_start+1)//2)
        roof=5+rise+(2 if kind=='B' else 0)
        half=2 if it==0 else min(3,1+(x-canopy_start)//2)
        for z in range(-half,half+1):
            v.put(x,roof,z,'glass')
            if x>canopy_start:
                prev=5+min(3,(x-1-canopy_start+1)//2)+(2 if kind=='B' else 0)
                for y in range(prev,roof):v.put(x,y,z,'glass')
            for y in range(5,roof):
                if abs(z)<half:v.put(x,y,z,'air')
        for z in (-half-1,half+1):
            v.put(x,roof,z,stair('west','polished_deepslate'))
            if it>=1:v.put(x,roof-1,z,'frame')
    for x in range(-10,-5):
        roof=10 if kind=='B' else 8
        for z in range(-2,3):v.put(x,roof,z,'glass')
        for z in (-4,4):
            for y in range(6,roof):v.put(x,y,z,'glass' if x!=-7 else 'frame')
    # Keep the front vestibule connected, not a glass sculpture with a solid wall.
    v.fill(-11,5,-1,-10,6 if it>=2 else 7,1,'air')
    v.put(-9,5,0,g.state('helm',facing='west'))
    for z in (-3,3):v.put(-9,5,z,'lamp')
    for z in (-3,3):
        v.put(0,5,z,g.state('minecraft:cyan_bed',part='foot',facing='east',occupied=False))
        v.put(1,5,z,g.state('minecraft:cyan_bed',part='head',facing='east',occupied=False))
        v.put(3,5,z,g.state('minecraft:chest',facing='west',type='single',waterlogged=False),{'id':'minecraft:chest','Items':(10,[])})
        v.put(4,5,z,g.state('minecraft:furnace',facing='west',lit=False),{'id':'minecraft:furnace'})
        v.put(2,5,z,'minecraft:crafting_table')
    v.put(-7,5,2,g.state('minecraft:lectern',facing='west',has_book=False,powered=False),{'id':'minecraft:lectern'})
    v.put(-7,5,-2,g.state('minecraft:lectern',facing='west',has_book=False,powered=False),{'id':'minecraft:lectern'})
    for x in (-3,2,5):
        for z in (-2,2):v.put(x,8,z,'light')
    # Empty 3-cube bay and full floor hatch; B extends it into the exposed frame.
    bx=6 if kind=='B' else 2
    v.fill(bx,1,-1,bx+2,3,1,'air')
    v.fill(bx,0,-1,bx+2,0,1,g.state('minecraft:iron_trapdoor',facing='west',half='top',open=False,powered=False,waterlogged=False))
    lx=bx-1
    v.fill(lx,1,0,lx,6,0,'air')
    for y in range(1,4 if it>=2 else 6):
        v.put(lx-1,y,0,'frame');v.put(lx,y,0,g.state('minecraft:ladder',facing='east',waterlogged=False))
    if it>=2:
        v.put(lx,4,0,g.state('minecraft:iron_trapdoor',facing='west',half='top',open=False,powered=False,waterlogged=False))
        for z in (-1,1):v.put(lx,5,z,g.state('minecraft:lever',face='floor',facing='west',powered=False))
    v.meta['bay_clear']={'min':[bx,1,-1],'max':[bx+2,3,1],'floor_hatch':[bx+1,0,0],'ladder':[lx,1,0]}
    v.meta['cabin_clear_width']=7
    # Side doors lie ahead of all mid/aft wings. Cut the entire deployment columns.
    for side in (-1,1):
        facing='south' if side>0 else 'north'
        for x in (-5,-4):
            for z in range(5,12):
                for y in range(-1,9):v.put(x,y,side*z,'air')
            v.put(x,4,side*4,g.state('sill',facing=facing))
            for y,half in ((5,'lower'),(6,'upper')):
                v.put(x,y,side*4,g.state('door',facing=g.OPP[facing],half=half,hinge='left' if (x==-5)==(side==1) else 'right',open=False,powered=False))
        for x in (-6,-3):
            v.put(x,5,side*4,'amber')
            for z,f in ((side*5,facing),(side*3,g.OPP[facing])):v.put(x,5,z,g.state('minecraft:stone_button',face='wall',facing=f,powered=False))
    v.meta['door_x']=[-5,-4];v.meta['sill_y']=4;v.meta['door_z']=4
    # Motors are mounted in the double floor, never added as balancing ballast.
    for z in (-2,2):
        v.put(-2,1,z,'frame');v.put(-2,2,z,g.state('gyro',powered=True))
        v.put(-2,3,z,g.state('minecraft:lever',face='floor',facing='north',powered=True))
        v.put(0,2,z,'booster')
    v.put(-20,3,0,g.state('scope',facing='west'))
    v.put(-10,0,0,g.state('scope',facing='down'))
    # Recessed panel seams; light gray is restricted to the inset backing.
    for x in (-1,5,11,16):
        for z in (-4,4):
            if (x,6,z) in v.b and v.b[x,6,z]==g.PARTS['shell']:
                v.put(x,6,z,'air');v.put(x,6,z-(1 if z>0 else -1),'panel')
                if it>=1:v.put(x,7,z-(1 if z>0 else -1),'frame')
    if it>=2:
        # Second review: chamfered pressure shoulders and descending aft roof.
        # Seven clear at foot/eye level, 2.5 blocks of headroom under outer bevels.
        for x in range(-5,6):
            roof=10 if kind=='B' and x<=-4 else 8
            for side in (-1,1):
                v.put(x,roof,side*3,'air')
                v.put(x,roof,side*4,'air')
                v.put(x,roof-1,side*4,'air')
                v.put(x,roof-1,side*3,stair('north' if side<0 else 'south',half='top'))
                v.put(x,roof-1,side*2,'frame')
            if x>=1 or (kind=='B' and x>=-3):
                for z in range(-2,3):
                    v.put(x,roof,z,'air');v.put(x,7,z,'shell')
                for side in (-1,1):v.put(x,7,side*3,slab(half='top'))
        for x in (2,5):
            for z in (-2,2):v.put(x,7,z,'light')
        if kind=='A':
            # End the cabin at its rear bulkhead; do not retain a roof-height rail
            # over the unpressurized engine spine just to preserve the old box.
            v.fill(7,7,-3,18,11,3,'air')
            for x in range(7,19):v.fill(x,6,-2,x,6,2,'shell')
            for x in range(8,17):
                for z in (-3,3):
                    ys=[p[1] for p in v.b if p[0]==x and p[2]==z]
                    if ys:v.put(x,max(ys),z,slab())
        if kind=='B':
            # Vertical rear bridge riser seals the 10-to-7 roof transition.
            v.fill(-3,8,-3,-3,9,3,'shell')
            for x in range(6,15):
                for z in (-2,2):v.put(x,5,z,slab('polished_deepslate'))
        if kind=='C':
            for x in range(7,17):v.put(x,6,0,'frame')
    v.meta['iteration']=it
    # Rebuild fittings from unchanged hull each trial. Move jets, never mass ballast.
    if refine is not None: refine(v)
    base=copy.deepcopy(v);target=g.stats(base)['centre_of_mass'];best=None;best_error=float('inf')
    for attempt in range(8):
        v=copy.deepcopy(base);fit_jets(g,v,target,it)
        st=g.stats(v);error=max(t['offset_length'] for t in st['thrust'].values()) if len(st['thrust'])==6 else float('inf')
        if error<best_error:best=copy.deepcopy(v);best_error=error
        if it<2 and error<=.5:break
        new_target=st['centre_of_mass']
        if it>=2 and new_target==target:break
        target=new_target
    if best is not None:v=best
    v.meta['balance_fit_iterations']=attempt+1
    v.meta['balance_strategy']='Move nozzle locations about uniform COM; unchanged base hull, no ballast.'
    return v

def fit_jets(g,v,com,it):
    # Half-block mean height, up to four jets per axial direction.
    yc=round((com[1]-.5)*2)/2
    ys=(math.floor(yc),math.ceil(yc)) if yc%1 else (int(yc)-1,int(yc)+1)
    xc=round((com[0]-.5)*2)/2
    xs=(math.floor(xc)-8,math.ceil(xc)+8) if it<2 else (-7,round(2*xc+7))
    # Dark 3x3 flush rims one block forward of the nozzle, not copper warts.
    def vent(p,f):
        d=g.DIR[f];ts=[i for i in range(3) if not d[i]]
        v.put(*g.add(p,tuple(-n for n in d)),'frame')
        for a in (-1,0,1):
            for b in (-1,0,1):
                if a==b==0:continue
                q=list(g.add(p,d));q[ts[0]]+=a;q[ts[1]]+=b
                if it==0 or tuple(q) in v.b:v.put(*q,'floor')
        if it>=1:
            # Tangential backing joins nozzle to surrounding hull and seals cavity.
            for axis in ts:
                for sign in (-1,1):
                    q=list(p);q[axis]+=sign
                    if tuple(q) not in v.b:v.put(*q,'frame')
        v.put(*p,g.state('thruster',facing=f,power=0))
        for n in range(1,50):v.put(*(p[i]+n*d[i] for i in range(3)),'air')
    # Two copper bells, each 5x5 with clipped corners, recessed luminous bulkhead.
    ez=6 if v.name=='A' else (8 if it>=2 else 7) if v.name=='B' else (9 if it==0 else 8)
    mid=round(yc)
    for side in (-1,1):
        zc=side*ez
        for dy in range(-2,3):
            for dz in range(-2,3):
                if abs(dy)==abs(dz)==2:continue
                y=mid+dy;z=zc+dz
                v.put(17,y,z,'frame')
                v.put(18,y,z,'light' if abs(dy)<=1 and abs(dz)<=1 else 'floor')
                v.put(19,y,z,'air');v.put(20,y,z,'air')
                if max(abs(dy),abs(dz))==2:
                    v.put(19,y,z,'ring');v.put(20,y,z,'minecraft:waxed_copper_block')
                elif abs(dz)==1 and dy==0:v.put(19,y,z,'minecraft:cyan_stained_glass')
        for y in ys:
            v.put(18,y,zc,g.state('thruster',facing='east',power=0))
            v.put(19,y,zc,'air');v.put(20,y,zc,'air')
    # Bow braking vents on dark lower shoulders, not on the pointed nose.
    for z in ((-3,3) if it==0 else (-ez,ez)):
        for y in ys:vent(((-12 if it==0 else (11 if it>=2 else 10) if v.name=='A' else (12 if 'mining_rig' in v.meta else 9) if v.name=='B' else (-2 if it>=2 else 0)),y,z),'west')
    # Side jets straddle COM for pure opposite-force yaw couples.
    for x in xs:
        for side in (-1,1):
            zmax=max((abs(z) for xx,y,z in v.b if xx==x and y in ys),default=4)
            zmax=max(4,min(11 if it>=2 else 9,zmax))
            for y in ys:vent((x,y,side*(zmax-1)),'south' if side>0 else 'north')
    # Roof/keel jets also straddle COM; z=+/-2 leaves central circulation open.
    for x in xs:
        for z in (-2,2):
            ys_here=[y for xx,y,zz in v.b if xx==x and zz==z]
            low=min(ys_here,default=0);top=max(ys_here,default=6)
            vent((x,low+1,z),'down');vent((x,top-1,z),'up')
    v.meta['jet_station_x']=list(xs)

def main(g):
    p=argparse.ArgumentParser();p.add_argument('--design-round',type=int,choices=(3,),required=True)
    p.add_argument('--iteration',type=int,choices=(0,1,2),default=2)
    p.add_argument('--only',choices=('A','B','C'));p.add_argument('--views',choices=('all','review'),default='all')
    a=p.parse_args();folder=g.ROOT/'r3'/f'round{a.iteration}';folder.mkdir(parents=True,exist_ok=True)
    summary={};sheets={k:[] for k in ('front','plan','side')}
    for key in ('A','B','C'):
        if a.only and key!=a.only:continue
        v=build(g,key,a.iteration);out=folder/key;summary[key]=g.export(v,out)
        views=g.VIEWS if a.views=='all' else ('front','rear','plan','side')
        for view in views:
            im=g.render(v,view);im.save(out/f'{view}.png')
            if view=='front':sheets[view].append(im)
            if view in ('plan','side'):
                si=g.render(v,view,silhouette=True);si.save(out/f'{view}-silhouette.png');sheets[view].append(si)
        print(key,len(v.b),summary[key]['connected_components'],summary[key]['centre_of_mass'],{k:round(t['offset_length'],3) for k,t in summary[key]['thrust'].items()},flush=True)
    for view,images in sheets.items():
        if len(images)!=3:continue
        sheet=g.Image(2100,590);sheet.text(35,22,'ROUND 3 / '+('FRONT QUARTERS' if view=='front' else view.upper()+' SILHOUETTES'),4)
        for i,im in enumerate(images):sheet.paste_scaled(im,i*700,70,700,500)
        sheet.save(folder/('contact.png' if view=='front' else f'contact-{view}.png'))
    (folder/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
