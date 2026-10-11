from pathlib import Path
out=Path('tools/probe')
prefix='''waitworld
wait 60
cmd gamemode creative
cmd starfold terrain
wait 160
cmd starfold face 0
wait 100
cmd effect clear @s
cmd gamerule doWeatherCycle false
cmd weather clear
'''
for mode in ['survival','creative']:
 s=prefix+'''cmd tp @s 141.4214 195 1906.5786 45 0
wait 80
fly off
wait 100
'''+f'cmd gamemode {mode}\nwait 20\ncornerwatch 20\ncorner start-200\nshot corner-{mode}-start\nlook 45 0\nkey forward on\n'
 for n,t in [('150',230),('80',325),('40',185),('stop',420)]:
  s+=f'wait {t}\ncorner {n}\nshot corner-{mode}-{n}\n'
 s+='key forward off\ncorner stopped\nkey jump on\nkey forward on\nwait 450\nkey forward off\nkey jump off\ncorner after-jumping\n'
 if mode=='creative':
  s+='fly on\nkey forward on\nwait 220\nkey forward off\ncorner flying-low\nkey jump on\nwait 160\nkey jump off\nkey forward on\nwait 220\nkey forward off\ncorner flying-over\nshot corner-creative-over\n'
 s+='cmd data get entity @s Pos\ncmd data get entity @s Health\ncmd save-all flush\nwait 60\nquit\n'
 (out/f'corner-foot-{mode}.txt').write_text(s)
