"""Crash regressions for real installer filesystem code, without Linux services or privileges."""
import contextlib
import importlib.util
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from unittest.mock import patch

WORKSPACE = pathlib.Path(__file__).resolve().parents[2]
RUNNER = WORKSPACE / 'installer/v1/runner.py'
spec = importlib.util.spec_from_file_location('stage09_installer_runner', RUNNER)
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)

class InstallerInitializationTests(unittest.TestCase):
    def setUp(self):
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        # A fresh private directory under the test user home retains real symlink/mode checks.
        self.base = pathlib.Path(self.stack.enter_context(tempfile.TemporaryDirectory(prefix='.meetspace-installer09-test-', dir=pathlib.Path.home())))
        self.target = self.base / 'meetspace'
        self.bundle = {name:(WORKSPACE/'installer/v1'/name).read_bytes() for name in ('runner.py','release.json','release.sig','trust-root.pem','docker.asc')}
        self.manifest = json.loads(self.bundle['release.json'])
        self.job = str(uuid.uuid4())
        self.platform = {'os':'ubuntu','osVersion':'24.04','arch':'amd64','cpus':4,'memoryKiB':7*1024**2}
        self.plan = {'issuedAt':int(time.time()),'expiresAt':int(time.time())+900,
            'options':{'domain':'fixture.example.com','publicIpv4':'8.8.8.8','acmeEmail':'fixture@example.com'},'docker':{'installed':True},
            'platform':self.platform,'dockerPackages':r.docker_packages(self.manifest,self.platform),
            'commit':self.manifest['commit'],'releaseVersion':self.manifest['version'],
            'releaseSha256':r.digest(r.canonical(self.manifest))}
        self.plan['hash'] = r.digest(r.canonical(self.plan))
        self.request = {'plan':self.plan,'confirmedPlanHash':self.plan['hash'],'jobId':self.job,'acceptAcmeTerms':True}
        self.starts = []
        original_guard, original_lock = r.no_symlink, r.lock_file
        for replacement in (
            patch.object(r,'ROOT',self.target),
            patch.object(r,'no_symlink',side_effect=lambda path,require_root=True:original_guard(path,require_root=False)),
            patch.object(r,'lock_file',side_effect=lambda path,*a,**kw:original_lock(self.base/pathlib.Path(path).name,*a,**kw)),
            patch.object(r,'create_plan',return_value=self.plan),
            patch.object(r,'start_unit',side_effect=lambda job:self.starts.append(job)),
            patch.object(r,'unit_active',return_value=True),
            patch.object(r,'dns_check',return_value=['192.0.2.1']),
            patch.object(r,'platform_facts',return_value=self.platform),
            patch.object(r,'disk_free',return_value=40*1024**3),
        ): self.stack.enter_context(replacement)

    def apply(self): return r.apply(self.request,self.manifest,self.bundle)

    def complete_layout(self):
        self.assertEqual(self.job,r.read_json(self.target/'managed.json')['jobId'])
        job = self.target/'jobs'/self.job
        self.assertEqual(self.plan,r.read_json(job/'plan.json'))
        state = r.read_json(job/'state.json')
        self.assertEqual(self.job,state['jobId'])
        self.assertEqual([],state['completedPhases'])
        for name,data in self.bundle.items():
            file=self.target/'installer/v1'/name
            self.assertEqual(data,file.read_bytes())
            self.assertEqual(0o600,file.stat().st_mode&0o777)
        self.assertEqual(0o700,self.target.stat().st_mode&0o777)

    def error(self,code,call):
        with self.assertRaises(r.InstallError) as error:call()
        self.assertEqual(code,error.exception.code)

    def remove_own_layout(self):
        if self.target.exists():shutil.rmtree(self.target)
        self.starts.clear()

    def test_fresh_and_existing_empty_root_publish_complete_private_layout(self):
        for empty in (False,True):
            with self.subTest(existingEmptyRoot=empty):
                if empty:self.target.mkdir(mode=0o700)
                self.assertEqual('running',self.apply()['state'])
                self.complete_layout()
                self.assertEqual([self.job],self.starts)
                self.assertFalse(list(self.base.glob('.meetspace-init-*')))
                self.remove_own_layout()

    def test_each_initial_write_failure_cleans_staging_and_same_job_retries(self):
        original=r.write_atomic
        for stop in range(1,9):
            with self.subTest(write=stop):
                count=0
                def fail_write(*args,**kwargs):
                    nonlocal count
                    count+=1
                    if count==stop:raise OSError('injected initialization write failure')
                    return original(*args,**kwargs)
                with patch.object(r,'write_atomic',side_effect=fail_write):
                    with self.assertRaises(OSError):self.apply()
                self.assertFalse(self.target.exists())
                self.assertFalse(list(self.base.glob('.meetspace-init-*')))
                self.assertEqual([],self.starts)
                self.apply();self.complete_layout();self.assertEqual([self.job],self.starts)
                self.remove_own_layout()

    def kill_child(self,point):
        # os._exit intentionally skips Python finally/context cleanup, like a killed worker.
        # The child is restricted by test patches to this test's newly created directory.
        code=r'''
import importlib.util,json,pathlib,os,sys,contextlib
from unittest.mock import patch
source,target,raw,point=sys.argv[1:]
spec=importlib.util.spec_from_file_location('crashed_installer',source);r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)
target=pathlib.Path(target);request=json.loads(raw);plan=request['plan'];directory=pathlib.Path(source).parent
bundle={n:(directory/n).read_bytes() for n in ('runner.py','release.json','release.sig','trust-root.pem','docker.asc')};manifest=json.loads(bundle['release.json'])
original_guard,original_lock,original_write,original_replace=r.no_symlink,r.lock_file,r.write_atomic,r.os.replace
count=0
def write(*a,**kw):
 global count
 original_write(*a,**kw);count+=1
 if point==str(count):os._exit(91)
def replace(a,b):
 original_replace(a,b)
 if point=='published' and pathlib.Path(b)==target:os._exit(91)
with contextlib.ExitStack() as stack:
 for item in (patch.object(r,'ROOT',target),patch.object(r,'no_symlink',side_effect=lambda p,require_root=True:original_guard(p,require_root=False)),patch.object(r,'lock_file',side_effect=lambda p,*a,**kw:original_lock(target.parent/pathlib.Path(p).name,*a,**kw)),patch.object(r,'create_plan',return_value=plan),patch.object(r,'start_unit',side_effect=AssertionError('systemd forbidden')),patch.object(r,'write_atomic',side_effect=write),patch.object(r.os,'replace',side_effect=replace)):stack.enter_context(item)
 r.apply(request,manifest,bundle)
raise AssertionError('crash hook was not reached')
'''
        result=subprocess.run([sys.executable,'-c',code,str(RUNNER),str(self.target),json.dumps(self.request),str(point)],capture_output=True,timeout=10)
        self.assertEqual(91,result.returncode,'Child did not reach the intended crash boundary')

    def test_process_death_at_each_initial_write_never_publishes_partial_root(self):
        for stop in range(1,9):
            with self.subTest(write=stop):
                self.kill_child(stop)
                self.assertFalse(self.target.exists())
                orphan=list(self.base.glob('.meetspace-init-*'))
                self.assertEqual(1,len(orphan))
                self.assertEqual(0o700,orphan[0].stat().st_mode&0o777)
                self.assertFalse(list(orphan[0].rglob('server.env')))
                self.apply();self.complete_layout();self.assertEqual([self.job],self.starts)
                # Cleanup only this test's captured, exclusively owned staging path.
                shutil.rmtree(orphan[0]);self.remove_own_layout()

    def test_death_after_publication_has_status_and_explicit_resume(self):
        self.kill_child('published');self.complete_layout()
        statefile=self.target/'jobs'/self.job/'state.json'
        state=r.read_json(statefile);state['at']=int(time.time())-20;r.write_atomic(statefile,r.canonical(state))
        with patch.object(r,'unit_active',return_value=False):
            self.assertEqual('needs_recovery',self.apply()['state'])
            self.assertEqual([],self.starts,'Retry must reconcile, not automatically launch the worker')
            r.control('resume',{'jobId':self.job,'planHash':self.plan['hash'],'confirmedPlanHash':self.plan['hash']},self.manifest)
        self.assertEqual([self.job],self.starts)
        self.complete_layout()

    def test_publication_failure_preserves_existing_empty_root(self):
        self.target.mkdir(mode=0o700);original=r.os.replace
        def replace(source,target):
            if pathlib.Path(target)==self.target:raise OSError('injected publication failure')
            return original(source,target)
        with patch.object(r.os,'replace',side_effect=replace):
            with self.assertRaises(OSError):self.apply()
        self.assertEqual([],list(self.target.iterdir()));self.assertFalse(list(self.base.glob('.meetspace-init-*')))
        self.apply();self.complete_layout()

    def test_unknown_nonempty_directory_and_symlink_are_not_adopted(self):
        self.target.mkdir(mode=0o700);unknown=self.target/'owner-data';unknown.write_bytes(b'preserve')
        self.error('EXISTING_INSTALLATION',self.apply);self.assertEqual(b'preserve',unknown.read_bytes());self.assertEqual([],self.starts)
        self.remove_own_layout();external=self.base/'other';external.mkdir(mode=0o700);self.target.symlink_to(external,target_is_directory=True)
        self.error('UNSAFE_PATH',self.apply);self.assertTrue(self.target.is_symlink());self.assertEqual([],list(external.iterdir()))

    def test_parent_symlink_is_rejected_before_staging(self):
        actual=self.base/'actual';actual.mkdir(mode=0o700);alias=self.base/'alias';alias.symlink_to(actual,target_is_directory=True)
        with patch.object(r,'ROOT',alias/'meetspace'):self.error('UNSAFE_PATH',self.apply)
        self.assertEqual([],list(actual.iterdir()));self.assertEqual([],self.starts)

    def test_conflicting_job_is_rejected_and_same_job_is_idempotent(self):
        self.apply();state=self.apply();self.assertEqual(self.job,state['jobId']);self.assertEqual([self.job],self.starts)
        other=dict(self.request,jobId=str(uuid.uuid4()))
        self.error('EXISTING_INSTALLATION',lambda:r.apply(other,self.manifest,self.bundle))
        self.complete_layout();self.assertEqual([self.job],self.starts)

if __name__=='__main__':unittest.main(verbosity=2)
