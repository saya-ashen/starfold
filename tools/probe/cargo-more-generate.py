from pathlib import Path
import runpy,re
v=runpy.run_path('tools/probe/cargo-generate.py')
setup=v['setup']; cargo=v['cargo']; end=v['end']
# Load historical nearby hull sites before assembly. Census again after each removal.
extra='''cmd tp @s 5125 180 857
wait 240
cmd sable info @e
cmd sable remove @e
cmd kill @e[tag=cargo]
wait 80
cmd sable info @e
cmd tp @s 5264 310 864
wait 200
scandown 5264 864 330 250
cmd sable info @e
cmd sable remove @e
wait 80
cmd sable info @e
'''
setup=setup.replace('waitworld\ncmd gamemode creative',extra+'waitworld\ncmd gamemode creative')
# Give fixture its own western site, while checking the old one above.
base=setup.replace('cmd tp @s 5264.5 262 864.5 180 20','cmd tp @s 4120.5 202 864.5 180 20')
finish='''sample 0
trace off
cargowatch 0
cargo end
shot cargo-G2-end
wait 40
quit
'''
Path('tools/probe/cargo-g2-ordinary.txt').write_text(base+cargo+'''log G2-ordinary-before
cargo ordinary-before
press 87 1
wait 220
press 87 0
wait 80
log G2-ordinary-after
cargo ordinary-after
hullblocks ordinary-after
'''+finish)
# Rotate all construction-relative coordinates a quarter turn: old -X becomes +Z.
def rotate(line):
 if not line.startswith('cmd '):return line
 a=line.split()
 if len(a)>4 and a[1] in ('fill','setblock'):
  starts=[2,5] if a[1]=='fill' else [2]
  def num(x):return float(x[1:] or 0)
  def rel(x):return '~'+(f'{x:g}' if x else '')
  for i in starts:
   x,z=num(a[i]),num(a[i+2]);a[i],a[i+2]=rel(z),rel(-x)
  line=' '.join(a)
  line=re.sub(r'facing=(east|west|north|south)',lambda m:'facing='+dict(east='north',west='south',north='west',south='east')[m[1]],line)
 return line
rot='\n'.join(rotate(l) for l in base.splitlines())+'\n'
rot=rot.replace('4120.5 202 864.5','5120.5 202 2024.5').replace('assemble area ~-5 ~-16 ~-3 ~5 ~-12 ~3','assemble area ~-3 ~-16 ~-5 ~3 ~-12 ~5').replace('~0.5\nfly off','~0.5\nfly off').replace('cmd tp @s ~-0.5 ~-14.8 ~0.5','cmd tp @s ~0.5 ~-14.8 ~0.5')
# Spawn same manifest in the narrower x / wider z cabin.
rc=cargo.replace('~3 ~ ~-1','~-1 ~ ~3').replace('~3 ~ ~1','~1 ~ ~3').replace('~-2 ~ ~1','~1 ~ ~-2').replace('~-2 ~ ~-1','~-1 ~ ~-2')
Path('tools/probe/cargo-g2-rotated.txt').write_text(rot+rc+'''log G2-rotated-before
cargo rotated-before
press 87 1
wait 220
press 87 0
wait 80
log G2-rotated-after
cargo rotated-after
hullblocks rotated-after
'''+finish.replace('G2-end','G2-rotated-end'))
# Retest G1 at a different clear launch column, with initial upward clearance logged.
g1=Path('tools/probe/cargo-g1.txt').read_text().replace(v['setup'],setup).replace('5264.5 262 864.5','5700.5 402 1200.5').replace('until y< 170 2400','until y< 170 2400')
# Keep descent bounded and stop misleading post-timeout phase propagation via strict.
g1=g1.replace('waitworld\nlog R-clean-login','strict\nwaitworld\nlog R-clean-login',1)
Path('tools/probe/cargo-g1-clear.txt').write_text(g1)
