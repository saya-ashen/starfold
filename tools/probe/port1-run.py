from pathlib import Path
import subprocess,sys,re,os,json,hashlib,fcntl
root=Path.cwd(); assert (root/'build.gradle').exists(),'run from the repository root'
lock=open('build/space/port1-run.lock','w');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
name,script,preset=sys.argv[1:4];base=root/'build/space'/('port1-'+name);base.mkdir(exist_ok=False)
pattern=r'^([^ ]*/)?java .* net\.neoforged\.devlaunch\.Main @'+re.escape(str(root))+r'/build/moddev/(server|client)RunProgramArgs\.txt( |$)'
def absent():return not subprocess.run(['timeout','4','pgrep','-f',pattern],capture_output=True,text=True).stdout.strip()
assert absent(),'THIS checkout game already running'
mem=subprocess.run(['timeout','4','free','-g'],capture_output=True,text=True,check=True).stdout
(base/'memory.txt').write_text(mem);print(mem,flush=True)
assert int(next(s for s in mem.splitlines() if s.startswith('Mem:')).split()[-1])>=8
p=root/'run/server.properties';o=root/'run/options.txt';pb=p.read_bytes();ob=o.read_bytes()
assert hashlib.sha256(pb).hexdigest().startswith('b0eaeb6d') and hashlib.sha256(ob).hexdigest().startswith('f48b6f65')
(base/'server.properties.original').write_bytes(pb);(base/'options.original').write_bytes(ob)
try:
 text=pb.decode()
 for k,v in {'level-name':'port1-'+name,'view-distance':8,'simulation-distance':6,'max-tick-time':-1,'force-gamemode':'false','level-type':preset.replace(':',r'\:'),'level-seed':'20261009','difficulty':'normal','enable-rcon':'false'}.items():
  text=re.sub('^'+k+'=.*$',lambda m:k+'='+str(v),text,flags=re.M)
 p.write_text(text);o.write_text(re.sub(r'^renderDistance:.*$','renderDistance:8',ob.decode(),flags=re.M))
 env=os.environ.copy();env['JAVA_TOOL_OPTIONS']='-Dstarfold.portProbe=true -Dstarfold.packProbe=true -Dstarfold.helmProbe=true'
 with (base/'run.log').open('w') as out:
  result=subprocess.run(['timeout','2400','bash','tools/probe/dev.sh',script,sys.argv[4] if len(sys.argv)>4 else '650'],env=env,stdout=out,stderr=subprocess.STDOUT)
 for kind in ('client','server'):
  src=Path('build/dev-'+kind+'.log')
  if src.exists():(base/(kind+'.log')).write_bytes(src.read_bytes())
 c=(base/'client.log').read_text() if (base/'client.log').exists() else '';s=(base/'server.log').read_text() if (base/'server.log').exists() else ''
 receipt=dict(exit=result.returncode,complete='PROBE COMPLETE' in c,saved='All dimensions are saved' in s)
 (base/'result.json').write_text(json.dumps(receipt));print(receipt,flush=True)
finally:
 assert absent(),'game survived cleanup; STOP'
 p.write_bytes(pb);o.write_bytes(ob)
 hashes={str(f):hashlib.sha256(f.read_bytes()).hexdigest() for f in (p,o)}
 (base/'restored.json').write_text(json.dumps(hashes,indent=2));assert p.read_bytes()==pb and o.read_bytes()==ob
 with (root/'build/space/port1.md').open('a') as f:f.write('\nRun '+name+': '+str(locals().get('receipt','failed'))+'; configs restored byte-for-byte. See `port1-'+name+'/`.\n')
