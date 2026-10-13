#!/usr/bin/env python3
"""Supply private disposable fixture inputs via stdin, never shell args; one fixture at a time."""
import argparse,os,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--stage',type=int,choices=[6,7,8],required=True);a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[2]
adb=[str(pathlib.Path(os.environ.get('ANDROID_HOME',pathlib.Path.home()/'Library/Android/sdk'))/'platform-tools/adb'),'-s',a.serial]
names={6:('call',18466),7:('messaging',18477),8:('recordings',18488)}
name,port=names[a.stage];home=root/f'.audit/stage0{a.stage}-fixture'
# These helpers are intentionally local-audit tools: never target an unowned personal device.
subprocess.run(adb+['shell','am','force-stop','com.meetspace.app'],check=True)
subprocess.run(adb+['shell','run-as','com.meetspace.app','mkdir','-p','files'],check=True)
for n,_ in names.values():
 subprocess.run(adb+['shell','run-as','com.meetspace.app','rm','-f',f'files/live-{n}.properties',f'files/live-{n}-ca.pem'],check=True)
props=(home/'live.properties').read_text().replace(f'origin=https://127.0.0.1:{port}',f'origin=https://10.0.2.2:{port}').encode()
for filename,contents in [(f'live-{name}.properties',props),(f'live-{name}-ca.pem',(home/'tls/ca.pem').read_bytes())]:
 subprocess.run(adb+['exec-in','run-as','com.meetspace.app','dd','of=files/'+filename,'bs='+str(len(contents)),'count=1'],input=contents,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 subprocess.run(adb+['shell','run-as','com.meetspace.app','chmod','600','files/'+filename],check=True)
print('Private fixture inputs copied')
