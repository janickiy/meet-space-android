#!/usr/bin/env python3
"""Run private-emulator call measurement; export only numeric application/SFU counters."""
import argparse, json, os, pathlib, subprocess, time
p=argparse.ArgumentParser(); p.add_argument('--serial', required=True); p.add_argument('--label', choices=['baseline','after'], required=True); a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[2]
adb=[str(pathlib.Path(os.environ.get('ANDROID_HOME', pathlib.Path.home()/'Library/Android/sdk'))/'platform-tools/adb'),'-s',a.serial]
fixture=root/'.audit/stage06-fixture'; private=root/'.audit/stage09'; output=root/'docs/android/evidence/09'
pid=json.loads((fixture/'pid.json').read_text())['pid']; samples=[]; last=-1
stem=f'stage09-quality-{a.label}-real-call'
def observe(phase,cycle):
 (fixture/'quality-gc-request').touch(); time.sleep(1.2)
 runtime=json.loads((fixture/'runtime-snapshot.json').read_text()); sfu=json.loads((fixture/'snapshot.json').read_text())
 fd=subprocess.run(['lsof','-p',str(pid),'-F','f'],capture_output=True,text=True)
 threads=subprocess.run(['ps','-M','-p',str(pid)],capture_output=True,text=True)
 samples.append({'phase':phase,'completedCycles':cycle,'runtime':runtime,'sfu':sfu,'hostOpenFileRecords':sum(l.startswith('f') for l in fd.stdout.splitlines()),'hostThreadRows':max(0,len(threads.stdout.splitlines())-1)})
 (output/f'{a.label}-sfu-lifecycle.json').write_text(json.dumps({'label':a.label,'condition':'Real Pion/TURN join/leave with Android camera/mic/screen disabled; no permanent peer assumed; explicit runtime.GC each observed batch; host lsof records include non-FD mappings','samples':samples},indent=2)+'\n')
# Only this invocation's exact result is reset. Otherwise the observer can read a
# prior completed measurement before the new instrumentation writes its first JSON.
subprocess.run(adb+['shell','run-as','com.meetspace.app','rm','-f','cache/'+stem+'.json'],check=True)
observe('before',0)
with (private/f'{a.label}-real-call.log').open('w') as log:
 proc=subprocess.Popen(adb+['shell','am','instrument','-w','-r','-e','quality09','true','-e','qualityLabel',a.label,'-e','qualityHprof','true','-e','qualityIdleSettleMs','65000','-e','class','com.meetspace.app.QualityLifecycleTest#realCallJoinLeaveBatches','com.meetspace.app.test/com.meetspace.app.MeetSpaceTestRunner'],stdout=log,stderr=subprocess.STDOUT)
 while proc.poll() is None:
  r=subprocess.run(adb+['exec-out','run-as','com.meetspace.app','cat','cache/'+stem+'.json'],capture_output=True,text=True)
  try:
   evidence=json.loads(r.stdout); seq=len(evidence['samples'])
   if seq>last:
    if seq: observe(evidence['samples'][-1]['phase'],evidence['samples'][-1]['completedCycles'])
    last=seq
  except (ValueError, KeyError): pass
  time.sleep(2)
 observe('after',110)
r=subprocess.run(adb+['exec-out','run-as','com.meetspace.app','cat','cache/'+stem+'.json'],capture_output=True,check=True)
(output/f'{a.label}-real-call.json').write_bytes(r.stdout)
evidence=json.loads(r.stdout)
for heap in evidence.get('hprofs',[]):
 if heap['status']=='written':
  with (private/heap['filename']).open('wb') as target:
   subprocess.run(adb+['exec-out','run-as','com.meetspace.app','cat','cache/'+heap['filename']],stdout=target,check=True)
  (private/heap['filename']).chmod(0o600)
print(json.dumps({'status':evidence['status'],'completedCycles':evidence['completedCycles']}))
if evidence['status']!='completed' or 'OK (1 test)' not in (private/f'{a.label}-real-call.log').read_text(): raise SystemExit(1)
