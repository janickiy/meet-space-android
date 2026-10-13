#!/usr/bin/env python3
"""Summarize observed counters, without inferring leaks or causality from RSS/timing alone."""
import csv,hashlib,io,json,pathlib,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[2]; E=ROOT/'docs/android/evidence/09'; P=ROOT/'.audit/stage09'
result={'condition':'Debug/API36 ARM64 4KiB emulator; same workload, explicit GC, fixture only; instrumentation and dump overhead included. Differences are observations, not a controlled speedup or physical battery benchmark.','workloads':{}}
for method in ['native-factory','real-call','activity-recording','auth']:
 result['workloads'][method]={}
 for label in ['baseline','after']:
  path=E/f'{label}-{method}.json'
  if not path.exists():continue
  j=json.loads(path.read_text()); samples=j['samples']; warm=next(s for s in samples if s['phase']=='warmup'); end=next(s for s in samples if s['phase']=='settled'); idle=next((s for s in samples if s['phase']=='idle-settled'),None)
  fields=['javaUsedBytes','nativeHeapAllocatedBytes','pssTotalKb','rssKb','threads','fds']
  row={'status':j['status'],'warmupCycles':j['warmupCycles'],'measuredCycles':j['measuredCycles'],'sourceSha256':hashlib.sha256(path.read_bytes()).hexdigest(),'warmup':{k:warm[k] for k in fields},'settled':{k:end[k] for k in fields},'weakReferences':end['weakReferences'],'processCpuMsDuringMeasuredWork':end['processCpuMs']-warm['processCpuMs'],'elapsedMsDuringMeasuredWork':end['elapsedRealtimeMs']-warm['elapsedRealtimeMs'],'uidRxBytesDuringMeasuredWork':end['uidRxBytes']-warm['uidRxBytes'],'uidTxBytesDuringMeasuredWork':end['uidTxBytes']-warm['uidTxBytes']}
  row['allocationAndGcDelta']={k:end['runtimeStats'][k]-v for k,v in warm['runtimeStats'].items() if isinstance(v,int) and isinstance(end['runtimeStats'].get(k),int)}
  if idle:row['extraIdle']={'delayMs':j['extraIdleSettleMs'],**{k:idle[k] for k in fields},'weakReferences':idle['weakReferences']}
  result['workloads'][method][label]=row
for label in ['baseline','after']:
 path=E/f'{label}-startup.json'
 if path.exists():result[label+'Startup']=json.loads(path.read_text())
result['sfuLifecycle']={}
for label in ['baseline','after']:
 path=E/f'{label}-sfu-lifecycle.json'
 if not path.exists():continue
 data=json.loads(path.read_text()); samples=data['samples']; before=next(s for s in samples if s['phase']=='before'); final=next(s for s in reversed(samples) if s['phase']=='after')
 initial_pc=before['sfu']['peerConnectionTotal']; excluded=[]
 for sample in samples:
  delta=sample['sfu']['peerConnectionTotal']-initial_pc
  if sample['phase'] in ['settled','idle-settled','after'] and delta<sample['completedCycles']:
   excluded.append({'phase':sample['phase'],'completedCycles':sample['completedCycles'],'peerConnectionCounterDelta':delta,'reason':'Premature phase read from prior device-cache result; exclude from current-run curve. Historical JSON preserved.'})
 result['sfuLifecycle'][label]={'sourceSha256':hashlib.sha256(path.read_bytes()).hexdigest(),'scope':'Real fixture Pion/TURN control/transport join-leave; Android camera/mic/screen disabled; no permanent peer assumption. Host open-file records include non-FD mappings.','excludedSamples':excluded,'before':before,'final':final,'peerConnectionCounterDelta':final['sfu']['peerConnectionTotal']-initial_pc,'limitation':('Historical observer phase is invalid and no complete batch curve is asserted. ' if excluded else '')+'Sampling is asynchronous to Android cycles. No active-media throughput or encoded-media stress is measured here.'}
(E/'measurement-summary.json').write_text(json.dumps(result,indent=2)+'\n')
processor=ROOT/'.audit/tools/perfetto-v58.2/trace_processor_shell'
if processor.is_file():
 for label in ['baseline','after']:
  trace=P/f'stage09-quality-{label}-activity-recording.perfetto-trace'
  if not trace.exists():continue
  target="upid IN (SELECT upid FROM thread WHERE is_main_thread=1 AND name='m.meetspace.app')"
  def query(sql):
   p=subprocess.run([str(processor),str(trace),'-Q',sql],text=True,capture_output=True,check=True)
   return list(csv.DictReader(io.StringIO(p.stdout)))
  frames=query('SELECT jank_type,count(*) AS frames,round(avg(dur)/1e6,3) AS mean_ms FROM actual_frame_timeline_slice WHERE '+target+' AND layer_name IS NOT NULL GROUP BY jank_type;')
  cpu=query('SELECT count(*) AS slices,round(sum(s.dur)/1e6,2) AS cpu_ms FROM sched s JOIN thread t USING(utid) WHERE t.'+target+';')
  errors=query("SELECT name,value FROM stats WHERE severity='error' AND value>0;")
  frame_count=sum(int(r['frames']) for r in frames);app_missed=sum(int(r['frames']) for r in frames if 'App Deadline Missed' in r['jank_type'])
  out={'label':label,'traceSha256':hashlib.sha256(trace.read_bytes()).hexdigest(),'configuredDurationMs':45000,'scope':'First 45 seconds of debug instrumented recording/recreation workload with SwiftShader, GC and heap dump. Main process identified by unique main-thread Linux comm m.meetspace.app because process names are unavailable. Surface frames only. Not isolated user scroll or physical-device jank.','processorVersion':'58.2','processorSha256':'d29864d1ba3b36855527bb1b0ca3aa7f703cdce338b9680bb922c5c151b358fa','traceErrors':errors,'scheduledCpu':cpu,'surfaceFrames':frame_count,'appDeadlineMissedFrames':app_missed,'frameCategories':frames}
  (E/f'{label}-perfetto.json').write_text(json.dumps(out,indent=2)+'\n')
print('Numeric summaries updated')
