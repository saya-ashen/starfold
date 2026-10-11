from pathlib import Path
import subprocess,json
root=Path('run/screenshots');out=Path('build/space')
files=['port1-after-home-orbit','port1-after-home-repeat','port1-home-legacy','port1-home-legacy-repeat','port1-home-new-again','port1-before-home-orbit','port1-before-home-repeat']
pairs=[(files[0],files[1]),(files[2],files[3]),(files[0],files[2]),(files[0],files[4]),(files[5],files[6]),(files[5],files[0])]
images={n:subprocess.check_output(['timeout','30','magick',str(root/(n+'.png')),'-crop','480x480+714+456','+repage','-depth','8','RGB:-']) for n in files}
stats=[]
for a,b in pairs:
 dif=bytes(abs(x-y) for x,y in zip(images[a],images[b])); stats.append(dict(a=a,b=b,roi='480x480+714+456',total=230400,pixels=sum(dif[i:i+3]!=b'\0\0\0' for i in range(0,len(dif),3)),max=max(dif)))
 subprocess.run(['timeout','30','magick','-size','480x480','-depth','8','RGB:-','-evaluate','multiply','10',str(out/(a+'--'+b+'-diff.png'))],input=dif,check=True)
(out/'port1-pixels.json').write_text(json.dumps(stats,indent=2));print(json.dumps(stats,indent=2))
