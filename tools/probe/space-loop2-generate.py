from pathlib import Path
p=Path('tools/probe')
old=(p/'space-loop-s1.txt').read_text()
setup=old[:old.index('cmd execute at @s run summon marker')]
setup=setup.replace('leadsite 4800 262','cmd tp @s 5114.5 262 1561.5 180 20')
setup='strict\n'+setup
assemble=old[old.index('wait 10\ncmd tp @s ~ ~14 ~'):old.index('log S1-A-checks')].replace('throttle 15','throttle 0')
A='5122 260 1569'
def coord(base,dx=0,dy=0,dz=0):
 if base=='b':return '{baseb:'+f'{dx},{dy},{dz}'+'}'
 x,y,z=map(int,A.split());return f'{x+dx} {y+dy} {z+dz}'
def checks(base,stage):
 c=coord(base,0,-10 if base=='b' else 0,0)
 items=[('copper_ingot',7),('coal',13),('raw_iron',11)] if base=='b' else [('cobblestone',17),('iron_ingot',3),('oak_log',9)]
 s=f'check {stage}-chest if block {c} chest\n'
 for slot in range(27):
  if slot in [0,5,26]:
   item,n=items[[0,5,26].index(slot)];cond=f'if data block {c} Items[{{Slot:{slot}b,id:"minecraft:{item}",count:{n}}}]'
  else:cond=f'unless data block {c} Items[{{Slot:{slot}b}}]'
  s+=f'check {stage}-slot{slot} {cond}\n'
 for xyz,block in zip([(1,0,0),(2,0,0),(2,1,0)],['copper_block','amethyst_block','emerald_block'] if base=='b' else ['gold_block','lapis_block','redstone_block']):s+=f'check {stage}-{block} if block {coord(base,*xyz)} {block}\n'
 if base=='b':
  for y in range(1,11):s+=f'check {stage}-shaft{y} if block {coord(base,-1,-y,0)} air\n'
  s+=f'cmd data get block {coord(base,3,0,0)}\n'
 return s+f'loopstate {stage}\nwait 20\n'
def fixture(base):
 c=coord(base,0,-10 if base=='b' else 0,0)
 items=[('copper_ingot',7),('coal',13),('raw_iron',11)] if base=='b' else [('cobblestone',17),('iron_ingot',3),('oak_log',9)]
 s=f'cmd setblock {c} chest\nwait 4\n'
 for slot,(item,n) in zip([0,5,26],items):s+=f'cmd item replace block {c} container.{slot} with {item} {n}\nwait 4\n'
 for xyz,block in zip([(1,0,0),(2,0,0),(2,1,0)],['copper_block','amethyst_block','emerald_block'] if base=='b' else ['gold_block','lapis_block','redstone_block']):s+=f'cmd setblock {coord(base,*xyz)} {block}\nwait 4\n'
 return s
helm='find typewriter\nwait 6\nusehit first\nwait 40\nuseifidle\nwait 40\nrequiretypewriter\nfind throttle\nwait 6\nthrottle 0\n'
def launch():return 'throttle 15\nsample 100\nuntil region 4000\nthrottle 0\n'
def land(i):return f'steer {i} 540\nrequirechart\nrequireplanet {i}\naboardelsequit\nrequiretypewriter\npress 340 0\nwait 40\npress 340 1\nlandguard 4000\npress 340 0\nwait 40\nloopstate landed-{i}\n'
def exitship(tag):return f'sample 0\nrecordpos board{tag}\nfind typewriter\nwait 6\nusehit release\nwait 40\ncmd execute at @s positioned ~12 ~ ~ run spreadplayers ~ ~ 0 1 false @s\nwait 120\nfly off\n'
end='sample 0\ncmd save-all flush\nwait 80\nloopstate final-save\nsolar final-save\nwait 20\nshot space-loop2-final\nquit\n'
s1=setup+fixture('a')+checks('a','S1-A')+assemble+'loopstate hull-baseline\n'+launch()+land(1)+exitship('b')+'look 90 0\nkey jump on\nwalk20 800\nkey jump off\nwait 40\nrecord baseb\n'+f'cmd fill {coord("b",-1,-10,0)} {coord("b",0,-1,0)} air\nwait 10\n'+fixture('b')+f'cmd setblock {coord("b",3,0,0)} furnace\nwait 4\ncmd item replace block {coord("b",3,0,0)} container.0 with raw_iron 64\nwait 4\ncmd item replace block {coord("b",3,0,0)} container.1 with coal 16\nwait 10\ncheck furnace-total if data block {coord("b",3,0,0)} {{CookTimeTotal:200}}\ncmd data get block {coord("b",3,0,0)}\nloopstate furnace-start\nwait 240\n'+checks('b','S1-B')+end
head='strict\nwaitworld\nloopstate login\nsolar login\nwait 160\n'
s2=head+checks('b','S2-B')+'solar S2-B\nwait 240\ncmd data get block {baseb:3,0,0}\nloopstate furnace-240\ncmd tp @s {boardb}\nwait 100\naboardelsequit\n'+helm+launch()+'steer 0 50\nrequireref 1\nloopstate space-stop\n'+end
s3=head+'aboardelsequit\n'+helm+land(0)+exitship('a')+'cmd tp @s 5122.5 260 1569.5\nwait 160\n'+checks('a','S3-A')+end
s4=head+checks('a','S4-A')+'cmd tp @s {boarda}\nwait 160\naboardelsequit\n'+helm+launch()+land(1)+exitship('b_return')+'cmd tp @s {baseb}\nwait 160\n'+checks('b','S4-B')+'wait 240\ncmd data get block {baseb:3,0,0}\nloopstate furnace-240\n'+end
for i,s in enumerate([s1,s2,s3,s4],1):(p/f'space-loop2-s{i}.txt').write_text(s)
