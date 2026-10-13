#!/usr/bin/env python3
"""Run one opt-in measurement. Keep raw heaps/traces private; export numeric evidence only."""
import argparse,json,os,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--label',required=True);p.add_argument('--method',choices=['native-factory','activity-recording','auth'],required=True);p.add_argument('--trace',action='store_true');p.add_argument('--leakcanary',action='store_true');p.add_argument('--cycles',type=int);p.add_argument('--idle-settle-ms',type=int,default=0);a=p.parse_args()
assert a.label.replace('-','').replace('_','').isalnum()
root=pathlib.Path(__file__).resolve().parents[2];private=root/'.audit/stage09';output=root/'docs/android/evidence/09'
adb=[str(pathlib.Path(os.environ.get('ANDROID_HOME',pathlib.Path.home()/'Library/Android/sdk'))/'platform-tools/adb'),'-s',a.serial]
methods={'native-factory':('nativeFactoryDisposalBatches','qualityFactoryCycles'),'activity-recording':('activityAndRecordingLifecycleBatches','qualityActivityCycles'),'auth':('authLoginLogoutBatches','qualityAuthCycles')}
method,countarg=methods[a.method];stem=f'stage09-quality-{a.label}-{a.method}';trace=None
with (private/f'{a.label}-{a.method}.log').open('w') as log:
 if a.trace:
  trace=subprocess.Popen(adb+['shell','perfetto','--txt','-c','-','-o',f'/data/misc/perfetto-traces/{stem}.perfetto-trace'],stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT)
  trace.stdin.write((root/'testing/stage09/lifecycle.perfetto.txt').read_bytes()); trace.stdin.close()
 args=['shell','am','instrument','-w','-r','-e','quality09','true','-e','qualityLabel',a.label,'-e','qualityHprof','true']
 if a.leakcanary: args+=['-e','qualityLeakCanary','true']
 if a.cycles: args+=['-e',countarg,str(a.cycles)]
 if a.idle_settle_ms: args+=['-e','qualityIdleSettleMs',str(a.idle_settle_ms)]
 subprocess.run(adb+args+['-e','class','com.meetspace.app.QualityLifecycleTest#'+method,'com.meetspace.app.test/com.meetspace.app.MeetSpaceTestRunner'],stdout=log,stderr=subprocess.STDOUT,check=True)
 if trace: trace.wait(timeout=60)
if trace:
 subprocess.run(adb+['pull',f'/data/misc/perfetto-traces/{stem}.perfetto-trace',str(private/(stem+'.perfetto-trace'))],stdout=subprocess.DEVNULL,check=True)
 subprocess.run(adb+['shell','rm','-f',f'/data/misc/perfetto-traces/{stem}.perfetto-trace','/data/local/tmp/stage09.perfetto.txt'],check=True)
r=subprocess.run(adb+['exec-out','run-as','com.meetspace.app','cat','cache/'+stem+'.json'],capture_output=True,check=True)
(output/f'{a.label}-{a.method}.json').write_bytes(r.stdout);j=json.loads(r.stdout)
for h in j.get('hprofs',[]):
 if h['status']=='written':
  target=private/h['filename']
  with target.open('wb') as f: subprocess.run(adb+['exec-out','run-as','com.meetspace.app','cat','cache/'+h['filename']],stdout=f,check=True)
  target.chmod(0o600)
if a.leakcanary:
 r=subprocess.run(adb+['exec-out','run-as','com.meetspace.app','cat','cache/'+stem+'-leakcanary.json'],capture_output=True,check=True)
 (output/f'{a.label}-{a.method}-leakcanary.json').write_bytes(r.stdout)
print(json.dumps({'status':j['status'],'completedCycles':j['completedCycles']}))
if j['status']!='completed' or 'OK (1 test)' not in (private/f'{a.label}-{a.method}.log').read_text(): raise SystemExit(1)
