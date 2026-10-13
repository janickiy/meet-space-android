"""Local runner protocol regression checks: temp files, actual flock, no remote services."""
import importlib.util,pathlib,tempfile,time,uuid,json
from unittest.mock import patch
root=pathlib.Path(__file__).resolve().parents[2];spec=importlib.util.spec_from_file_location('installer_protocol_audit',root/'installer/v1/runner.py');r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)
checks={}
with tempfile.TemporaryDirectory(prefix='meetspace-stage09-protocol-') as d,patch.object(r,'no_symlink'):
 base=pathlib.Path(d)
 lock=base/'lock'
 with r.lock_file(lock):
  try:
   with r.lock_file(lock):raise AssertionError('concurrent lock succeeded')
  except r.InstallError as e:assert e.code=='JOB_LOCKED'
 with r.lock_file(lock):pass
 checks['realFlockContentionAndRelease']=True
 target=base/'private';r.write_atomic(target,b'old')
 with patch.object(r.os,'replace',side_effect=OSError('local injected rename failure')):
  try:r.write_atomic(target,b'new')
  except OSError:pass
 assert target.read_bytes()==b'old' and not list(base.glob('.private.*'))
 assert target.stat().st_mode&0o777==0o600
 checks['atomicFailurePreservesOldFileAndRemovesTemporary']=True
 job=str(uuid.uuid4());directory=base/'jobs'/job;directory.mkdir(parents=True)
 for index in range(100):r.state_write(directory,'running','images',completed=['docker','source'])
 state=r.read_json(directory/'state.json');assert state['sequence']==100 and len(state['events'])==64
 checks['journal100WritesKeeps64EventsAndCompletedPhases']=True
 manifest=json.loads((root/'installer/v1/release.json').read_text())
 plan={'hash':'f'*64,'options':{'domain':'fixture.example.invalid'},'commit':manifest['commit'],'releaseVersion':manifest['version'],'releaseSha256':r.digest(r.canonical(manifest))}
 r.write_atomic(directory/'plan.json',r.canonical(plan));state['at']=int(time.time())-20;r.write_atomic(directory/'state.json',r.canonical(state))
 with patch.object(r,'ROOT',base),patch.object(r,'unit_active',return_value=False):
  assert r.status(job)['state']=='needs_recovery' and r.status(job)['errorCode']=='WORKER_INTERRUPTED'
 checks['deadWorkerBecomesNeedsRecoveryNeverSuccess']=True
 real_lock=r.lock_file
 with patch.object(r,'ROOT',base),patch.object(r,'unit_active',return_value=True),patch.object(r,'lock_file',side_effect=lambda path,*args,**kw:real_lock(base/pathlib.Path(path).name,*args,**kw)):
  result=r.control('cancel',{'jobId':job,'planHash':plan['hash']},manifest)
  assert result['state']=='cancel_requested' and (directory/'cancel').exists()
  assert r.read_json(directory/'state.json')['completedPhases']==['docker','source']
 checks['cancelSignalDurableDoesNotOverwriteWorkerCheckpoint']=True
print(json.dumps({'scope':'Host-local protocol mechanics only; temp paths and patched root ownership/systemd liveness, not a VM deployment.', 'passed':len(checks),'checks':checks},indent=2))
