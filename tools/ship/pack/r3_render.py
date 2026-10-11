"""Shape-aware stdlib offline renderer. Original models, not game textures."""
import math

def boxes(g,name,pr):
    lo=[0.,0.,0.];hi=[1.,1.,1.]
    if name.endswith('_stairs'):
        upper=pr.get('half')=='top'
        base=([0,.5 if upper else 0,0],[1,1 if upper else .5,1])
        a=[0.,0. if upper else .5,0.];b=[1.,.5 if upper else 1.,1.]
        axis=0 if pr.get('facing') in ('east','west') else 2
        if pr.get('facing') in ('east','south'):a[axis]=.5
        else:b[axis]=.5
        return [base,(a,b)]
    if name.endswith('_slab'):
        if pr.get('type')=='top':lo[1]=.5
        elif pr.get('type')!='double':hi[1]=.5
    if name.endswith('_bed'):hi[1]=.56
    if name.endswith('_trapdoor'):lo[1]=.8125
    if name.endswith('_door'):lo[2]=.42;hi[2]=.58
    if name.endswith('ladder'):hi[0]=.18
    if name.endswith('lever'):lo=[.3,0.,.3];hi=[.7,.6,.7]
    if name.endswith('button'):
        lo=[.3,.3,.3];hi=[.7,.7,.7]
        if pr.get('face')=='floor':lo[1]=0.;hi[1]=.125
        elif pr.get('face')=='ceiling':lo[1]=.875;hi[1]=1.
        else:
            axis=0 if pr.get('facing') in ('east','west') else 2
            if pr.get('facing') in ('east','south'):lo[axis]=0.;hi[axis]=.125
            else:lo[axis]=.875;hi[axis]=1.
    if name=='create:metal_girder':
        return [([.3,0,.3],[.7,1,.7]),([0,0,0],[1,.18,1]),([0,.82,0],[1,1,1])]
    return [(lo,hi)]

def blendpoly(im,pts,rgb,alpha):
    low=max(0,math.floor(min(p[1] for p in pts)));high=min(im.h-1,math.ceil(max(p[1] for p in pts)))
    for y in range(low,high+1):
        yy=y+.5;xs=[]
        for a,b in zip(pts,pts[1:]+pts[:1]):
            if a[1]<=yy<b[1] or b[1]<=yy<a[1]:xs.append(a[0]+(yy-a[1])*(b[0]-a[0])/(b[1]-a[1]))
        if len(xs)<2:continue
        l=max(0,math.ceil(min(xs)-.5));r=min(im.w,math.ceil(max(xs)-.5))
        for x in range(l,r):
            off=(y*im.w+x)*3
            im.p[off:off+3]=bytes(int(im.p[off+i]*(1-alpha)+rgb[i]*alpha) for i in range(3))

def render(g,v,view,width,height,silhouette):
    cam=g.norm(g.VIEWS[view]);right=g.norm(g.cross((0,1,0),cam)) if view!='plan' else (1,0,0);up=g.cross(cam,right)
    if view=='cutaway':
        # Remove pressure roof, port wall, upper fin and selected bay deck only.
        bx=v.meta['bay_clear']['min'][0]
        b={p:s for p,s in v.b.items() if not (p[1]>=7 or (-15<=p[0]<=6 and p[1]>=5 and p[2]>=4) or (bx-1<=p[0]<=bx+2 and p[1]==4 and abs(p[2])<=1))}
    else:b=v.b
    colors=dict(g.COLORS);colors.update({'minecraft:smooth_quartz_stairs':(218,228,233),'minecraft:polished_deepslate_stairs':(48,62,73),'minecraft:polished_deepslate_slab':(48,62,73),'minecraft:waxed_copper_block':(192,117,76),'minecraft:waxed_exposed_copper':(154,120,92),'minecraft:cyan_stained_glass':(38,189,210)})
    transparent={g.PARTS['glass'],g.PARTS['window'],'minecraft:cyan_stained_glass'}
    faces=[]
    for p,s in b.items():
        name,pr=g.splitstate(s);color=colors.get(name,(155,163,169));shapes=boxes(g,name,pr)
        for low,high in shapes:
            low=[p[i]+low[i] for i in range(3)];high=[p[i]+high[i] for i in range(3)]
            for axis in range(3):
                for sign in (-1,1):
                    normal=tuple(sign if i==axis else 0 for i in range(3))
                    if g.dot(normal,cam)<=1e-6:continue
                    neighbor=b.get(g.add(p,normal))
                    if neighbor and len(shapes)==1 and low==list(p) and high==[a+1 for a in p]:
                        nn,np=g.splitstate(neighbor)
                        if nn not in transparent and boxes(g,nn,np)==[([0.,0.,0.],[1.,1.,1.])]:continue
                    ij=[i for i in range(3) if i!=axis];pts=[]
                    for a,c in ((0,0),(1,0),(1,1),(0,1)):
                        q=list(low);q[axis]=(high if sign>0 else low)[axis];q[ij[0]]=(high if a else low)[ij[0]];q[ij[1]]=(high if c else low)[ij[1]];pts.append(tuple(q))
                    center=tuple(sum(q[i] for q in pts)/4 for i in range(3))
                    shade=1. if axis==1 and sign>0 else .58 if axis==1 else .82 if axis==2 else .69
                    if view=='rear':shade=max(shade,.72)
                    rgb=tuple(int(c*shade) for c in color)
                    faces.append((g.dot(center,cam),pts,rgb,name,pr,normal))
    projected=[(g.dot(q,right),-g.dot(q,up)) for _,pts,*_ in faces for q in pts]
    xmin=min(q[0] for q in projected);xmax=max(q[0] for q in projected);ymin=min(q[1] for q in projected);ymax=max(q[1] for q in projected)
    # Same scale for orthographic silhouette tests: lengths and spans remain comparable.
    scale=min((width-160)/(xmax-xmin),(height-245)/(ymax-ymin))
    if view in ('side','plan'):scale=min((width-160)/41,(height-245)/23)
    def project(q):return ((g.dot(q,right)-(xmin+xmax)/2)*scale+width/2,(-g.dot(q,up)-(ymin+ymax)/2)*scale+height/2+25)
    bg=(224,232,235) if silhouette else (17,26,39);im=g.Image(width,height,bg)
    # Separate mask gives a true 1px outer outline, not dark cracks on every voxel.
    mask=g.Image(width,height,(0,0,0))
    for depth,pts,rgb,name,pr,normal in sorted(faces,key=lambda f:f[0]):
        pp=[project(q) for q in pts];mask.poly(pp,(255,255,255))
        if silhouette:im.poly(pp,(31,52,67));continue
        center=tuple(sum(q[i] for q in pp)/4 for i in range(2))
        if name in transparent:
            blendpoly(im,pp,(40,185,206),.48)
            # Restrained dark edge frame with translucent inner pane.
            for a,bp in zip(pp,pp[1:]+pp[:1]):
                dx=bp[0]-a[0];dy=bp[1]-a[1];n=max(1,int(max(abs(dx),abs(dy))))
                for j in range(n+1):im.rect(a[0]+dx*j/n,a[1]+dy*j/n,1,1,(48,92,105))
        else:
            im.poly(pp,tuple(int(c*.9) for c in rgb))
            inset=[tuple(center[i]+(q[i]-center[i])*.987 for i in range(2)) for q in pp];im.poly(inset,rgb)
        if name in (g.PARTS['thruster'],g.PARTS['scope']) and g.DIR.get(pr.get('facing'))==normal:
            im.poly([tuple(center[i]+(q[i]-center[i])*.72 for i in range(2)) for q in pp],(8,19,27))
            im.poly([tuple(center[i]+(q[i]-center[i])*.28 for i in range(2)) for q in pp],(37,136,154))
    for y in range(150,height-100):
        for x in range(60,width-60):
            off=(y*width+x)*3
            if mask.p[off] and (not mask.p[off-3] or not mask.p[off+3] or not mask.p[off-width*3] or not mask.p[off+width*3]):im.p[off:off+3]=bytes((7,17,26))
    ink=(37,64,80) if silhouette else (196,217,226)
    im.rect(50,50,7,64,(14,171,188));im.text(78,50,f'{v.name} / {g.NAMES[v.name]}',5,ink)
    im.text(80,98,g.TITLES[view]+(' / OUTLINE ONLY' if silhouette else ''),2,ink);im.text(width-255,55,f'R3 PASS {v.meta["iteration"]}',3,ink)
    im.rect(50,height-75,width-100,2,(70,99,109));im.text(55,height-54,f'{len(v.b)} BLOCKS / BOW -X / 41 BLOCK LENGTH CLASS',2,ink)
    im.text(55,height-30,'OFFLINE / FLAT SILHOUETTE / EQUAL SCALE' if silhouette else 'OFFLINE / SHADED VOXELS / TRANSLUCENT GLASS / NO GAME TEXTURES',2,ink)
    return im
