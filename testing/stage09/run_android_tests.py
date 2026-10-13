#!/usr/bin/env python3
"""Run selected instrumentation and publish only test identity/status, not UI/backend logs."""
import argparse, json, os, pathlib, re, subprocess, time
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--label',required=True);p.add_argument('--classes',required=True);p.add_argument('--arg',action='append',default=[]);a=p.parse_args()
assert re.fullmatch(r'[a-zA-Z0-9_-]+',a.label)
root=pathlib.Path(__file__).resolve().parents[2]; log=root/'.audit/stage09'/f'{a.label}.log';dest=root/'docs/android/evidence/09/android-runs';dest.mkdir(exist_ok=True)
adb=[str(pathlib.Path(os.environ.get('ANDROID_HOME',pathlib.Path.home()/'Library/Android/sdk'))/'platform-tools/adb'),'-s',a.serial]
args=['shell','am','instrument','-w','-r','-e','class',a.classes]
for arg in a.arg:
 k,v=arg.split('=',1); args+=['-e',k,v]
start=time.monotonic()
with log.open('w') as f: process=subprocess.run(adb+args+['com.meetspace.app.test/com.meetspace.app.MeetSpaceTestRunner'],stdout=f,stderr=subprocess.STDOUT)
raw=log.read_text();tests=[];current={}
for line in raw.splitlines():
 for key in ('class','test'):
  prefix='INSTRUMENTATION_STATUS: '+key+'='
  if line.startswith(prefix): current[key]=line[len(prefix):]
 if line.startswith('INSTRUMENTATION_STATUS_CODE: '):
  code=int(line.split(': ',1)[1])
  if code!=1 and 'test' in current: tests.append(dict(current,statusCode=code));current={}
result={'label':a.label,'elapsedSeconds':round(time.monotonic()-start,2),'serial':a.serial,'sdk':subprocess.check_output(adb+['shell','getprop','ro.build.version.sdk'],text=True).strip(),'pageSize':int(subprocess.check_output(adb+['shell','getconf','PAGE_SIZE'],text=True).strip()),'tests':tests,'passed':sum(t['statusCode']==0 for t in tests),'failed':sum(t['statusCode'] in (-1,-2) for t in tests),'skipped':sum(t['statusCode'] in (-3,-4) for t in tests),'runnerSuccess':bool(re.search(r'OK \(\d+ tests?\)',raw)) and process.returncode==0 and 'FAILURES!!!' not in raw}
(dest/f'{a.label}.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({k:result[k] for k in ['label','passed','failed','skipped','runnerSuccess','elapsedSeconds']}))
raise SystemExit(0 if result['runnerSuccess'] and result['failed']==0 else 1)
