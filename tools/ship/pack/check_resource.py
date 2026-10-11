"""Regenerate the working Hauler and compare bytes/hash with the shipped data resource."""
import hashlib
import pathlib
import tempfile
import shipgen
import r3_geometry

root = pathlib.Path(__file__).resolve().parents[3]
with tempfile.TemporaryDirectory(dir=root / 'build/space', prefix='starter-hash-') as folder:
    hull = shipgen.runtime_build('B')
    shipgen.export(hull, pathlib.Path(folder))
    generated = (pathlib.Path(folder) / 'ship.nbt').read_bytes()
    resource = (root / 'src/main/resources/data/starfold/structure/starter_ship.nbt').read_bytes()
    assert generated == resource, 'starter_ship.nbt differs from runtime-r4 Hauler generator'
    print('Starter structure SHA256', hashlib.sha256(resource).hexdigest(), 'blocks', len(hull.b))
    weapon = (root / 'src/main/resources/data/starfold/structure/starter_ship_weapon.nbt').read_bytes()
    assert weapon == shipgen.weapon_structure_bytes(), 'starter_ship_weapon.nbt differs from dev.104 moving-drive mast generator'
    armed = shipgen.armed_hauler(hull)
    assert len(hull.b) == 2347 and len(armed.b) == 2370
    assert all(armed.b.get(p) == s for p, s in hull.b.items() if p[0] <= 10), 'mast changed cabin/chin/drill'
    assert not any(s == 'minecraft:air' for s in shipgen.weapon_module().b.values()), 'unexpected overlay air'
    module = shipgen.weapon_module()
    assert sum('creative_motor' in s for s in module.b.values()) == 1
    assert max(p[1] + shipgen.WEAPON_ORIGIN[1] for p in module.b) == 9
    assert sum('linear_chassis' in s for s in module.b.values()) == 6
    for variant in (hull, armed):
        assert [p for p,s in variant.b.items() if s == 'starfold:aircraft_dock'] == [(8,7,0)]
        assert variant.b[9,7,2] == shipgen.state('minecraft:waxed_oxidized_copper_trapdoor', facing='north', half='top', open=False, powered=False, waterlogged=False)
        assert shipgen.verify_sealed_cabin(variant) == 343
        for x in range(7,10):
            for z in range(-2,3):
                assert (x,7,z) in variant.b
                assert not any(xx == x and zz == z and y > 7 for xx,y,zz in variant.b)
        assert 10 * 3350 - shipgen.material_balance(variant)['mass'] * 8.25 > 0
        assert shipgen.stats(variant)['connected_components'] == [len(variant.b)]
        assert shipgen.verify_sealed_cabin(variant) > 200
        for at, name in variant.b.items():
            if 'lever' in name:
                powered = [shipgen.add(at, (0, -1, 0))]
                powered += [shipgen.add(q, d) for q in (at, powered[0]) for d in ((1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1))]
                assert not any('thruster' in variant.b.get(q, '') or 'booster' in variant.b.get(q, '') for q in powered), f'lever at {at} powers a thruster'
        assert max(t['offset_length'] for t in shipgen.stats(variant)['thrust'].values()) < .5
        assert max(t['offset_length'] for t in shipgen.material_balance(variant)['thrust'].values()) < .5
    print('Weapon structure SHA256', hashlib.sha256(weapon).hexdigest(), 'armed blocks', len(armed.b))
