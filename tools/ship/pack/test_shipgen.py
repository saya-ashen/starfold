"""Offline regressions for the exact runtime-reviewed r4 generator.
Run: python3 -B -m unittest discover -s tools/ship/pack -p 'test_*.py'
These checks do not substitute for Minecraft placement/flight acceptance.
"""
import tempfile
import unittest
from pathlib import Path
import shipgen as g


class RuntimeShipTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ships={k:g.runtime_build(k) for k in 'ABC'}

    def test_counts_connectivity_and_thrust(self):
        for key,n in zip('ABC',(2621,2347,2542)):
            with self.subTest(ship=key):
                s=g.stats(self.ships[key])
                self.assertEqual(s['block_count'],n)
                self.assertEqual(s['connected_components'],[n])
                self.assertEqual(s['bay_clear_27'],key!='B')
                self.assertLess(max(t['offset_length'] for t in s['thrust'].values()),.5)

    def test_hangar_sky_capture_walkway_walls_and_markings(self):
        for v in (self.ships['B'],g.armed_hauler(self.ships['B'])):
            self.assertEqual([p for p,s in v.b.items() if s=='starfold:aircraft_dock'],[(8,7,0)])
            # Entire 3x5 well is open to sky, including the 3x3 rotor pad.
            for x in range(7,10):
                for z in range(-2,3):
                    self.assertIn((x,7,z),v.b)
                    self.assertFalse(any(xx==x and zz==z and y>7 for xx,y,zz in v.b))
            # AABB/cylinder intersection, not just block centres. The flush
            # supporting floor is deliberately below the usable capture air.
            for x,y,z in v.b:
                if not 8<=y<=10:continue
                dx=max(abs(x-8)-.5,0);dz=max(abs(z)-.5,0)
                self.assertGreaterEqual(dx*dx+dz*dz,1.5**2,(x,y,z))
            # Continuous eight-cell pedestrian ring with solid floor and 2-high headroom.
            for x in range(7,10):
                for z in range(-1,2):
                    if (x,z)==(8,0):continue
                    self.assertIn(v.b[x,7,z],(g.PARTS['panel'],g.PARTS['light']))
                    for y in (8,9):self.assertNotIn((x,y,z),v.b)
            for x in range(7,11):
                for z in (-3,3):
                    self.assertEqual(v.b[x,8,z],g.PARTS['frame'])
                    self.assertEqual(v.b[x,9,z],g.PARTS['glass'])
            for z in range(-2,3):
                for y in (8,9):
                    self.assertEqual(v.b[6,y,z],g.PARTS['shell'])
                self.assertEqual(v.b[10,9,z],g.PARTS['frame'])
                if z!=2:self.assertEqual(v.b[10,8,z],g.PARTS['panel'])
            for x in (7,9):
                for z in (-1,1):self.assertEqual(v.b[x,7,z],g.PARTS['light'])
            for x in range(7,10):self.assertEqual(v.b[x,7,-2],g.PARTS['amber'])
            self.assertFalse(any('immersive_aircraft:' in s for s in v.b.values()))
            self.assertFalse(any('immersive_aircraft:' in str(nbt) for nbt in v.nbt.values()))

    def test_hangar_bidirectional_route_and_closed_pressure_seal(self):
        import copy
        for v in (self.ships['B'],g.armed_hauler(self.ships['B'])):
            hatch=(9,7,2)
            self.assertEqual(g.splitstate(v.b[hatch]),('minecraft:waxed_oxidized_copper_trapdoor',
                dict(facing='north',half='top',open='false',powered='false',waterlogged='false')))
            for y in (5,6):
                self.assertEqual(v.b[9,y,2],g.state('minecraft:ladder',facing='north',waterlogged=False))
                self.assertIn(v.b[9,y,3],(g.PARTS['panel'],g.PARTS['glass']))
            # Opened by hand from above and below: nothing beside it to switch.
            self.assertFalse(any('lever' in v.b.get((x,y,z),'') for x in (8,9,10) for y in (6,7,8) for z in (1,2,3)))
            route=[(9,8,1),(9,8,2),(9,7,2),(9,6,2),(9,5,2),(9,5,1)]
            route += [(x,5,0) for x in range(9,-9,-1)]
            for path in (route,list(reversed(route))):
                for a,b in zip(path,path[1:]):self.assertEqual(sum(abs(a[i]-b[i]) for i in range(3)),1)
                for x,y,z in path:
                    for yy in (y,y+1):
                        name,props=g.splitstate(v.b.get((x,yy,z),'minecraft:air'))
                        self.assertIn(name,('minecraft:air','minecraft:ladder','minecraft:waxed_oxidized_copper_trapdoor','minecraft:oak_door'))
                        if name=='minecraft:waxed_oxidized_copper_trapdoor':self.assertEqual((x,yy,z),hatch)
                        if name=='minecraft:oak_door':self.assertEqual((x,z),(5,0))
                    if y in (5,8):self.assertIn((x,y-1,z),v.b)
            self.assertEqual(g.verify_sealed_cabin(v),343)
            broken=copy.deepcopy(v);broken.put(*hatch,'air')
            with self.assertRaisesRegex(ValueError,'leaks'):g.verify_sealed_cabin(broken)

    def test_hangar_preserves_other_hardware_and_weapon_overlay(self):
        from unittest.mock import patch
        def old_dock(v):v.put(8,7,0,'starfold:aircraft_dock')
        with patch.object(g,'aircraft_hangar',old_dock):before=g.runtime_build('B')
        after=self.ships['B']
        allowed={(x,y,z) for x in range(7,11) for y in (8,9) for z in (-3,3)}
        allowed|={(10,y,z) for y in (8,9) for z in range(-2,3)}
        allowed|={(x,7,z) for x in range(7,10) for z in range(-2,3)}
        allowed|={(9,y,2) for y in (5,6)}|{(10,6,2)}
        for p in before.b.keys()|after.b.keys():
            if p not in allowed:self.assertEqual(before.b.get(p),after.b.get(p),p)
        self.assertEqual(before.nbt,after.nbt) # every chest and device payload
        module=g.weapon_module()
        self.assertFalse(allowed & {g.add(p,g.WEAPON_ORIGIN) for p in module.b})
        # Existing moving-train tests also sweep all four mast positions.

    def test_material_mass_and_balance_of_both_variants(self):
        for v,m,hover,accel in ((self.ships['B'],2260.75,0.5567518656716418,11.854473073095212),
                                (g.armed_hauler(self.ships['B']),2277.5,0.5608768656716417,11.76728869374314)):
            budget=g.material_balance(v)
            self.assertEqual(budget['mass'],m)
            self.assertLess(max(t['offset_length'] for t in budget['thrust'].values()),.5)
            self.assertAlmostEqual(m*8.25/(10*3350),hover,places=12)
            self.assertAlmostEqual(8*3350/m,accel,places=12)
            self.assertGreater(10*3350-m*8.25,0)

    def test_hauler_living_spaces_and_safe_rig(self):
        v=self.ships['B']
        for z in range(-2,3):
            for y in range(5,8):self.assertEqual(v.b[-11,y,z],g.PARTS['glass'])
        for x in range(-20,-11):
            for y in (6,7):self.assertNotIn((x,y,0),v.b)
        self.assertEqual(sum(g.splitstate(s)[0]=='minecraft:cyan_bed' for s in v.b.values()),8)
        self.assertEqual(g.splitstate(v.b[7,0,0])[1]['facing'],'down')
        self.assertEqual(sum(g.splitstate(s)[0]=='create:mechanical_drill' for s in v.b.values()),9)
        self.assertFalse(any('auger' in s or 'portable_engine' in s for s in v.b.values()))
        self.assertEqual(g.splitstate(v.b[10,3,0])[1]['powered'],'true')
        self.assertEqual(g.splitstate(v.b[-7,5,-1])[1]['powered'],'true')
        self.assertEqual(v.nbt[11,3,0]['ScrollValue'],-32)
        self.assertEqual(v.nbt[7,3,0]['ScrollValue'],1)
        self.assertEqual(v.nbt[6,2,0]['Items'],(10,[]))
        self.assertEqual(g.splitstate(v.b[10,4,-1])[1]['open'],'false')
        # Measured pilot feet are x=-7.7 (index); eyes at y=6.62. Trace 100 degrees
        # horizontally through the front/side glass; thin HUD and frame borders are not walls.
        import math
        for angle in range(-50,51):
            a=math.radians(angle)
            for step in range(1,241):
                p=(math.floor(-7.7-math.cos(a)*step/10),6,math.floor(.489+math.sin(a)*step/10))
                value=v.b.get(p)
                self.assertTrue(value is None or g.splitstate(value)[0]==g.PARTS['glass'],(angle,p,value))
        for x in (-5,-4):
            for z in (-11,-10,-9,-8,-7,-6,6,7,8,9,10,11):
                for y in range(0,8):self.assertNotIn((x,y,z),v.b)

    def test_cabin_flood_rejects_roof_wall_and_floor_holes(self):
        import copy
        v=self.ships['B']
        self.assertGreater(g.verify_sealed_cabin(v),200)
        for p in ((0,9,2),(-1,8,4),(-8,4,1),(-11,6,0)):
            broken=copy.deepcopy(v);broken.put(*p,'air')
            if p==(-8,4,1):broken.fill(-8,0,1,-8,4,1,'air')
            with self.subTest(hole=p),self.assertRaisesRegex(ValueError,'leaks'):
                g.verify_sealed_cabin(broken)
        for s in v.b.values():
            self.assertNotEqual(g.splitstate(s)[0],'minecraft:iron_door')
            self.assertNotEqual(g.splitstate(s)[0],'minecraft:stone_button')
        for y in range(1,4):self.assertEqual(g.splitstate(v.b[10,y,-1])[0],'minecraft:ladder')
        for y in (5,6):self.assertNotIn((10,y,-1),v.b)

    def test_forward_wings_exhaust_and_navigation(self):
        v=self.ships['B']
        self.assertEqual(v.b[-6,2,11],g.PARTS['light'])
        self.assertEqual(v.b[-6,2,-11],g.PARTS['light'])
        self.assertEqual(v.meta['jet_station_x'],[-7,15])
        for side in (-1,1):
            for y in (3,4):self.assertNotIn((-6,y,side*11),v.b)
        for side in (-1,1):
            for span in range(1,8):
                z=side*(4+span);leading=-10+(2*span)//3
                self.assertIn((leading,2,z),v.b)
            # Full deployment corridor plus player headroom, not just door cells.
            for x in (-5,-4):
                for z in range(5,16):
                    for y in range(-10,9):self.assertNotIn((x,y,side*z),v.b)
        for p,s in v.b.items():
            name,props=g.splitstate(s)
            if name not in (g.PARTS['thruster'],g.PARTS['scope']):continue
            d=g.DIR[props['facing']]
            for n in range(1,20):
                q=tuple(p[i]+n*d[i] for i in range(3))
                self.assertNotIn(q,v.b,(p,q))

    def test_stepped_roof_provisions_and_optional_dorsal_mast(self):
        v=self.ships['B']
        for x in (-5,-2,2,5):
            self.assertNotIn((x,9,2),v.b)
            self.assertIn((x,8,2),v.b)
        self.assertIn((0,9,2),v.b)
        self.assertIn((3,11,0),v.b)
        for y in (5,6,7):self.assertNotIn('ladder',v.b.get((-3,y,3),''))
        self.assertNotIn('trapdoor',v.b.get((-3,8,3),''))
        self.assertEqual(v.nbt[3,5,-3]['Items'][1],[
            {'Slot':g.Byte(0),'id':'minecraft:bread','count':32},
            {'Slot':g.Byte(1),'id':'minecraft:cooked_beef','count':16}])
        self.assertEqual([(i['id'],i['count']) for i in v.nbt[9,5,-2]['Items'][1]],
            [('minecraft:torch',32),('minecraft:iron_pickaxe',1),('minecraft:water_bucket',1)])
        self.assertFalse(any('sentrymechanicalarm:' in s or 'tacz:' in s for s in v.b.values()))
        module=g.weapon_module();gun=module.nbt[1,5,3]
        self.assertEqual(g.WEAPON_ORIGIN,(11,4,-3))
        self.assertEqual(g.WEAPON_SIZE,(4,6,5))
        self.assertEqual(g.splitstate(module.b[2,1,3]),('create:cogwheel',{'axis':'y'}))
        for y in (0,1,2):self.assertEqual(g.splitstate(module.b[1,y,3]),('create:piston_extension_pole',{'facing':'up'}))
        self.assertEqual(g.splitstate(module.b[1,3,3])[1]['facing'],'up')
        self.assertEqual(g.splitstate(module.b[1,5,3])[1]['ceiling'],'false')
        self.assertEqual(gun['ScrollValue'],100)
        custom=gun['SentryHeldItem']['components']['minecraft:custom_data']
        self.assertEqual(custom['GunId'],'tacz:minigun');self.assertEqual(custom['GunCurrentAmmoCount'],30)
        self.assertEqual(custom['GunFireMode'],'AUTO')
        self.assertIsInstance(custom['HasBulletInBarrel'],g.Byte)
        ammo=gun['SentryAmmoBoxes']['Items'][1][0]
        self.assertIsInstance(ammo['Slot'],g.Byte)
        self.assertEqual(ammo['components']['minecraft:custom_data'],{'AllTypeCreative':g.Byte(1)})
        armed=g.armed_hauler(v);s=g.stats(armed)
        self.assertEqual(s['connected_components'],[len(armed.b)])
        self.assertLess(max(t['offset_length'] for t in s['thrust'].values()),.5)
        self.assertGreater(g.verify_sealed_cabin(armed),200)

    def test_devices_are_isolated_and_console_channels_are_distinct(self):
        v=self.ships['B'];armed=g.armed_hauler(v)
        # Every exposed side of the slime plate is empty; only head/chest/connector
        # are carried. No accidental hull capture on first assembly.
        for x in range(6,9):
            for z in range(-1,2):
                for d in g.DIR.values():
                    p=g.add((x,1,z),d)
                    if p in v.b:self.assertIn(g.splitstate(v.b[p])[0],('minecraft:slime_block','minecraft:stone','minecraft:chest','create:mechanical_drill'))
        channels=[]
        for x,on in ((-8,'true'),(-7,'true'),(-6,'false')):
            self.assertEqual(g.splitstate(v.b[x,5,-1])[1]['powered'],on)
            n=v.nbt[x,5,-2];channels.append(n['FrequencyFirst']['id'])
            self.assertEqual(n['FrequencyLast']['id'],'minecraft:redstone')
            self.assertEqual(n['Transmitter'],1)
            self.assertIn('wall_sign',v.b[x,6,-2])
        self.assertEqual(channels,['minecraft:iron_ingot','minecraft:gold_ingot','minecraft:diamond'])
        arm=next(p for p,s in armed.b.items() if 'sentry_mechanical_arm' in s)
        self.assertEqual(arm,(12,9,0))
        self.assertGreater(arm[1]+3,max(p[1] for p in v.b)) # deployed above the original hull
        self.assertEqual(v.nbt[-6,6,-2]['front_text']['messages'][1][:2],
                         ['{"text": "炮塔 升起/收回"}','{"text": "亮=升起 灭=收回"}'])
        self.assertGreater(g.verify_sealed_cabin(armed),200)
        self.assertEqual(g.stats(armed)['connected_components'],[len(armed.b)])

    def test_mast_moving_train_and_unchanged_cabin_chin(self):
        v=self.ships['B'];armed=g.armed_hauler(v);module=g.weapon_module()
        self.assertEqual(len(armed.b),2370)
        self.assertFalse(any(s=='minecraft:air' for s in module.b.values()))
        # Nothing inside the cabin or anywhere forward of its rear service space
        # is changed, including every old chin cell and all the drill hardware.
        self.assertEqual({p:s for p,s in v.b.items() if p[0]<=10},
                         {p:s for p,s in armed.b.items() if p[0]<=10})
        for x in range(-15,-11):
            for z in range(-1,2):self.assertEqual(armed.b[x,4,z],g.PARTS['panel'])
        self.assertEqual(g.verify_sealed_cabin(armed),g.verify_sealed_cabin(v))
        for p,s in v.b.items():
            if p[0]==11:self.assertEqual(armed.b[p],s) # pressure bulkhead unchanged
        self.assertEqual(g.splitstate(armed.b[12,7,0])[1]['axis_along_first'],'false')
        self.assertEqual(g.splitstate(armed.b[13,7,-2]),('create:gearshift',{'axis':'x','powered':'false'}))
        self.assertEqual(armed.nbt[13,7,-3],g.link_nbt('diamond'))
        motors=[g.add(p,g.WEAPON_ORIGIN) for p,s in module.b.items() if 'creative_motor' in s]
        self.assertEqual(motors,[(14,7,-3)])
        self.assertEqual(armed.nbt[14,7,-3]['ScrollValue'],32)
        self.assertEqual(g.splitstate(armed.b[14,7,-3])[1]['facing'],'south')
        chassis={(12,8,1)}|{(13,y,1) for y in range(5,10)}
        train={(13,y,0) for y in range(5,10)}
        moving=chassis|train|{(12,8,0),(12,9,0)}
        for p in chassis:
            self.assertEqual(g.splitstate(armed.b[p]),('create:linear_chassis',
                dict(axis='z',sticky_bottom='true',sticky_top='false')))
            self.assertEqual(armed.nbt[p],{'id':'create:chassis','ScrollValue':1})
        for p in train:
            self.assertEqual(armed.b[p],g.state('create:cogwheel' if p[1] in (5,9) else 'create:shaft',axis='y'))
        fixed={p:s for p,s in armed.b.items() if p not in moving}
        self.assertLess(max(p[1]+1 for p in fixed),12+2.42) # upright Sentry muzzle clears hull
        self.assertTrue(all(p[1]<=9 for p in fixed if p[0]>=11))
        self.assertTrue(all(g.add(p,g.WEAPON_ORIGIN)[1]<=9 for p in module.b))
        for stroke in range(4):
            shift=lambda p:g.add(p,(0,stroke,0))
            carried={shift(p):armed.b[p] for p in moving}
            self.assertFalse(set(carried)&set(fixed),'moving well obstructed')
            world=fixed|carried
            slime=shift((12,8,0));arm=shift((12,9,0))
            for d in g.DIR.values():
                q=g.add(slime,d)
                if q in fixed:self.assertEqual((stroke,q),(0,(12,7,0)))
            # Model the audited six-neighbour slime + same-axis chassis group
            # rules, not an assumption that touching shafts are glued.
            captured=set();pending=[slime]
            while pending:
                p=pending.pop()
                if p in captured or p not in world or p==(12,7,0):continue
                captured.add(p);name,props=g.splitstate(world[p])
                if name=='minecraft:slime_block':pending.extend(g.add(p,d) for d in g.DIR.values())
                if name=='create:linear_chassis':
                    pending.append(g.add(p,(0,0,-1))) # only glued face, range 1
                    pending.extend(q for d in g.DIR.values() if (q:=g.add(p,d)) in world
                        and world[q]==world[p])
            self.assertEqual(captured,set(carried))
            bottom=shift((13,5,0))
            meshes=[q for d in ((1,0,0),(-1,0,0),(0,0,1),(0,0,-1))
                    if fixed.get(q:=g.add(bottom,d))==g.state('create:cogwheel',axis='y')]
            self.assertEqual(meshes,[(14,8,0)] if stroke==3 else [])
            self.assertEqual(world[g.add(arm,(1,0,0))],g.state('create:cogwheel',axis='y'))
            if stroke==3:self.assertLess(max(p[1]+1 for p in world if p!=arm),arm[1]+2.42)
            if stroke<3:self.assertEqual(world[13,8,0],g.state('create:shaft',axis='y'))
        # All serialized coordinates fit the sparse structure's explicit size.
        for p in module.b:
            self.assertTrue(all(0<=p[i]<g.WEAPON_SIZE[i] for i in range(3)))

    def test_mast_shared_motor_kinetic_paths_and_polarity(self):
        # Bounded model of Create 6.0.10 RotationPropagator: axial connections,
        # small-cog side mesh, and source-relative gearbox/gearshift signs.
        module=g.weapon_module()
        stowed={g.add(p,g.WEAPON_ORIGIN):g.splitstate(s) for p,s in module.b.items()}
        moving={(12,8,0),(12,9,0),(12,8,1)}|{(13,y,z) for y in range(5,10) for z in (0,1)}
        def axis(d):return next(i for i,n in enumerate(d) if n)
        def ports(block):
            name,props=block
            if name=='create:creative_motor':return {g.DIR[props['facing']]}
            if name=='create:gearbox':return {d for d in g.DIR.values() if axis(d)!='xyz'.index(props['axis'])}
            if name=='create:sticky_mechanical_piston':return {(0,0,1),(0,0,-1)}
            if name in ('create:shaft','create:cogwheel','create:gearshift'):
                return {d for d in g.DIR.values() if axis(d)=='xyz'.index(props['axis'])}
            return set()
        def cog(block):return block[0] in ('create:cogwheel','sentrymechanicalarm:sentry_mechanical_arm')
        for stroke in range(4):
            world={g.add(p,(0,stroke,0)) if p in moving else p:b for p,b in stowed.items()}
            for on in (False,True):
                speeds={(14,7,-3):32};sources={};pending=[(14,7,-3)]
                while pending:
                    p=pending.pop();block=world[p];source=sources.get(p)
                    for d in g.DIR.values():
                        q=g.add(p,d)
                        if q not in world or q in speeds:continue
                        reverse=tuple(-n for n in d);other=world[q]
                        if d in ports(block) and reverse in ports(other):
                            modifier=1
                            if block[0]=='create:gearbox':
                                self.assertIsNotNone(source)
                                modifier=(-1 if axis(d)==axis(source) or sum(d)==sum(source) else 1)
                            elif block[0]=='create:gearshift' and on:modifier=-1
                        elif cog(block) and cog(other) and axis(d)!=1:modifier=-1
                        else:continue
                        speeds[q]=speeds[p]*modifier;sources[q]=reverse;pending.append(q)
                # UP piston movement = -speed/512: OFF retracts, ON extends.
                self.assertEqual(speeds[12,7,0],-32 if on else 32)
                self.assertEqual(speeds[14,8,0],-32) # upstream of reversing branch
                arm=(12,9+stroke,0)
                if stroke==3:self.assertEqual(speeds[arm],-32)
                else:self.assertNotIn(arm,speeds)

    def test_runtime_palette_fixes(self):
        for key,v in self.ships.items():
            ids=[g.splitstate(s)[0] for s in v.b.values()]
            self.assertNotIn('minecraft:waxed_copper',ids)
            self.assertNotIn('create:rose_quartz_lamp',ids)
            self.assertEqual(ids.count('minecraft:waxed_exposed_copper' if key=='B' else 'minecraft:waxed_copper_block'),24)
            self.assertEqual(ids.count('starfold:thruster'),46 if key=='B' else 24)
            if key=='B':
                self.assertEqual(ids.count('starfold:gyro'),2)
                self.assertEqual({k:t['count'] for k,t in g.stats(v)['thrust'].items()},dict(up=10,down=4,west=8,east=8,north=8,south=8))

    def test_observed_vanilla_stair_corners(self):
        # Independently read from the stopped game world after first successful placement.
        expected={'A':{(-15,5,-2):'outer_right',(-15,5,2):'outer_left',(-14,6,-3):'inner_left',(-14,6,3):'inner_right',(-13,6,-3):'outer_right',(-13,6,3):'outer_left',(-10,8,-4):'inner_right',(-10,8,4):'inner_left'},'C':{(-14,5,-2):'outer_right',(-14,5,2):'outer_left',(-13,6,-2):'outer_right',(-13,6,2):'outer_left'}}
        for key,positions in expected.items():
            for pos,shape in positions.items():
                self.assertEqual(g.splitstate(self.ships[key].b[pos])[1]['shape'],shape)

    def test_stair_settling_is_idempotent(self):
        for v in self.ships.values():
            before=dict(v.b);g.settle_stair_shapes(v);self.assertEqual(v.b,before)

    def test_export_roundtrip_and_chosen_commands(self):
        # Explicit task-folder temp location: no loose workspace-root artifacts.
        root=Path('build/space/shipview1-tests');root.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root) as folder:
            for key,v in self.ships.items():
                target=Path(folder)/key;s=g.export(v,target)
                self.assertTrue(s['nbt_roundtrip_verified'])
                if key=='B':
                    self.assertEqual((target/'commands.txt').read_bytes(),Path(__file__).with_name('commands.txt').read_bytes())


if __name__=='__main__':unittest.main()
