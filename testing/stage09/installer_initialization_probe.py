"""Fault injection for runner filesystem protocol, no sudo, Docker, SSH or network."""
import contextlib,importlib.util,json,pathlib,tempfile,time,uuid
from unittest.mock import patch
ROOT=pathlib.Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('installer_audit_runner',ROOT/'installer/v1/runner.py')
r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)
manifest=json.loads((ROOT/'installer/v1/release.json').read_text())
bundle={n:(ROOT/'installer/v1'/n).read_bytes() for n in ('runner.py','release.json','release.sig','trust-root.pem','docker.asc')}
results=[]
def code(call):
 try: call(); return 'SUCCESS'
 except r.InstallError as e:return e.code
 except OSError:return 'INJECTED_IO_FAILURE'
for stop in ('managed.json','state.json'):
 with tempfile.TemporaryDirectory(prefix='meetspace-stage09-audit-') as d:
  base=pathlib.Path(d); target=base/'install'; job=str(uuid.uuid4())
  plan={'issuedAt':int(time.time()),'expiresAt':int(time.time())+900,'options':{'domain':'fixture.example.invalid'},'docker':{'installed':True},'commit':manifest['commit'],'releaseVersion':manifest['version'],'releaseSha256':r.digest(r.canonical(manifest))}
  plan['hash']=r.digest(r.canonical(plan)); request={'plan':plan,'confirmedPlanHash':plan['hash'],'jobId':job,'acceptAcmeTerms':True}
  original_write=r.write_atomic;original_lock=r.lock_file
  def write(path,data,mode=0o600):
   if pathlib.Path(path).name==stop:raise OSError('intentional local fault')
   original_write(path,data,mode)
  def lock(path,*args,**kwargs):return original_lock(base/pathlib.Path(path).name,*args,**kwargs)
  with patch.object(r,'ROOT',target),patch.object(r,'no_symlink'),patch.object(r,'create_plan',return_value=plan),patch.object(r,'lock_file',side_effect=lock),patch.object(r,'start_unit',return_value=None),patch.object(r,'unit_active',return_value=True):
   with patch.object(r,'write_atomic',side_effect=write): first=code(lambda:r.apply(request,manifest,bundle))
   retry=code(lambda:r.apply(request,manifest,bundle))
   status=code(lambda:r.status(job))
   results.append({'faultBefore':stop,'initialResult':first,'sameJobRetryResult':retry,'sameJobStatusResult':status,'managedMarkerExists':(target/'managed.json').exists(),'jobPlanExists':(target/'jobs'/job/'plan.json').exists(),'jobStateExists':(target/'jobs'/job/'state.json').exists(),'temporaryAtomicFiles':[p.name for p in target.rglob('.*') if p.is_file()]})
print(json.dumps({'scope':'TemporaryDirectory only; ownership guard and host preconditions patched; actual apply/write_atomic/managed_marker/status code. No network, SSH, Docker or systemd execution.', 'cases':results},indent=2))
