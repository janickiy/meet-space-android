"""Resolve once, bind to the reviewed plan, and never follow a moving ref on resume."""
import copy, contextlib, io, json, pathlib, tarfile, tempfile, time, unittest, urllib.error, uuid
from unittest.mock import patch, Mock
from test_runner import r, ROOT

class LatestSourceTests(unittest.TestCase):
    def setUp(self):
        self.recipe = json.loads((ROOT/'installer/v1/release.json').read_bytes())
        self.commit = 'a'*40
        self.files = {name:'b'*64 for name in self.recipe['sourceFiles']}
        self.lock = dict(repository='janickiy/meet-space',channel='release',ref='0.3.2',version='0.3.2',commit=self.commit,
            builtAt='2026-10-16T02:23:09Z',sourceUrl=r.source_url(self.commit),sourceSha256='c'*64,sourceFiles=self.files)
        self.metadata = {'sha':self.commit,'commit':{'committer':{'date':self.lock['builtAt']}}}
        self.plan = dict(sourceLock=self.lock,recipeSha256=r.digest(r.canonical(self.recipe)),
            releaseSha256=r.digest(r.canonical(r.locked_manifest(self.recipe,self.lock))),commit=self.commit,releaseVersion='0.3.2',
            issuedAt=int(time.time()),expiresAt=int(time.time())+900,options={'domain':'meet.example.com','publicIpv4':'8.8.8.8','acmeEmail':'owner@example.com'},docker={'installed':True})
        self.rehash(self.plan)
    def rehash(self, plan):
        plan['hash']=r.digest(r.canonical({k:v for k,v in plan.items() if k!='hash'}));return plan
    def rejected(self, code, fn, *args):
        with self.assertRaises(r.InstallError) as caught:fn(*args)
        self.assertEqual(code,caught.exception.code)
    def test_latest_release_resolves_tag_to_commit_not_target_branch(self):
        release={'tag_name':'0.3.2','target_commitish':'moving-main','draft':False,'prerelease':False}
        with patch.object(r,'github_json',side_effect=[release,[self.metadata]]) as lookup,patch.object(r,'source_snapshot',return_value=('c'*64,self.files)):
            self.assertEqual(self.lock,r.resolve_source(self.recipe))
            self.assertEqual(['/releases/latest','/commits?sha=0.3.2&per_page=1'],[x.args[0] for x in lookup.call_args_list])
            self.assertEqual({'expected_type':list},lookup.call_args_list[-1].kwargs)
    def test_no_release_uses_repository_default_branch_and_exact_sha(self):
        with patch.object(r,'github_json',side_effect=[None,{'default_branch':'stable/main'},[self.metadata]]) as lookup,patch.object(r,'source_snapshot',return_value=('c'*64,self.files)):
            selected=r.resolve_source(self.recipe)
            self.assertEqual('default-branch',selected['channel']);self.assertEqual('0.0.0-source.'+self.commit[:12],selected['version'])
            self.assertEqual('/commits?sha=stable%2Fmain&per_page=1',lookup.call_args_list[-1].args[0])
    def test_large_release_diff_uses_compact_metadata_without_raising_request_limit(self):
        release={'tag_name':'0.3.3','draft':False,'prerelease':False}
        large=json.dumps(dict(self.metadata,files=[{'patch':'x'*(2*r.MAX_JSON)}])).encode()
        compact=json.dumps([self.metadata]).encode()
        self.assertGreater(len(large),r.MAX_JSON)
        self.assertEqual(256*1024,r.MAX_JSON)
        replies={'/releases/latest':json.dumps(release).encode(),
                 '/commits/0.3.3':large,'/commits?sha=0.3.3&per_page=1':compact}
        opener=Mock()
        opener.open.side_effect=lambda request,**kwargs:io.BytesIO(replies[request.full_url.removeprefix(r.GITHUB_API)])
        with patch.object(r.urllib.request,'build_opener',return_value=opener),patch.object(r,'source_snapshot',return_value=('c'*64,self.files)):
            selected=r.resolve_source(self.recipe)
        self.assertEqual(self.commit,selected['commit']);self.assertEqual('0.3.3',selected['version'])
        self.assertEqual([r.GITHUB_API+'/releases/latest',r.GITHUB_API+'/commits?sha=0.3.3&per_page=1'],
                         [call.args[0].full_url for call in opener.open.call_args_list])
        self.rejected('INVALID_REQUEST',r.strict_json,large)
    def test_malformed_and_oversized_github_json_are_source_errors(self):
        for raw in (b'{',b'null',b'[]',b'{"sha":1,"sha":2}',b'{"padding":"'+b'x'*r.MAX_JSON+b'"}'):
            with self.subTest(raw_bytes=len(raw)):
                opener=Mock();opener.open.return_value=io.BytesIO(raw)
                with patch.object(r.urllib.request,'build_opener',return_value=opener):
                    self.rejected('SOURCE_INVALID',r.github_json,'/releases/latest')
        for raw in (b'{}',b'[{"sha":1,"sha":2}]'):
            opener=Mock();opener.open.return_value=io.BytesIO(raw)
            with patch.object(r.urllib.request,'build_opener',return_value=opener):
                self.rejected('SOURCE_INVALID',lambda:r.github_json('/commits?sha=0.3.3&per_page=1',expected_type=list))
    def test_invalid_commit_list_and_metadata_are_rejected_before_archive_download(self):
        release={'tag_name':'0.3.3','draft':False,'prerelease':False}
        invalid=[{},[],[self.metadata,self.metadata],[None],[{'sha':None}],
                 [dict(self.metadata,commit=None)],[dict(self.metadata,commit={'committer':[]})],
                 [dict(self.metadata,commit={'committer':{'date':'invalid'}})]]
        for entries in invalid:
            with self.subTest(entries=entries),patch.object(r,'github_json',side_effect=[release,entries]),patch.object(r,'source_snapshot') as archive:
                self.rejected('SOURCE_INVALID',r.resolve_source,self.recipe)
                archive.assert_not_called()
    def test_only_404_can_be_treated_as_no_release(self):
        for status in (404,403,429,500):
            opener=Mock();opener.open.side_effect=urllib.error.HTTPError('https://api.github.com',status,'unused',{},None)
            with patch.object(r.urllib.request,'build_opener',return_value=opener):
                if status==404:self.assertIsNone(r.github_json('/releases/latest',missing_ok=True))
                else:self.rejected('SOURCE_RATE_LIMIT' if status in (403,429) else 'SOURCE_LOOKUP_FAILED',lambda:r.github_json('/releases/latest',missing_ok=True))
    def test_lookup_failure_does_not_fall_back_to_embedded_old_commit(self):
        with patch.object(r,'github_json',side_effect=r.InstallError('SOURCE_LOOKUP_FAILED')) as lookup:
            self.rejected('SOURCE_LOOKUP_FAILED',r.resolve_source,self.recipe);self.assertEqual(1,lookup.call_count)
    def test_invalid_release_and_commit_are_rejected(self):
        for release in ({'tag_name':'bad;command','draft':False,'prerelease':False},{'tag_name':'1.0.0','draft':True,'prerelease':False}):
            with patch.object(r,'github_json',return_value=release),patch.object(r,'source_snapshot') as archive:
                with self.assertRaises(r.InstallError):r.resolve_source(self.recipe)
                archive.assert_not_called()
    def test_fixed_snapshot_changes_only_source_fields(self):
        effective=r.manifest_for_plan(self.recipe,self.plan)
        self.assertEqual(self.commit,effective['commit']);self.assertEqual('0.3.2',effective['version'])
        for key in ('images','baseImages','dockerPackages','acmeDirectory','runnerSha256','profile'):
            self.assertEqual(self.recipe[key],effective[key])
    def test_tamper_rejected_even_when_outer_plan_hash_recomputed(self):
        for key,value in [('sourceUrl','https://evil.invalid/source'),('repository','other/repo'),('version','bad;command'),('sourceFiles',{})]:
            plan=copy.deepcopy(self.plan);plan['sourceLock'][key]=value;self.rehash(plan)
            self.rejected('PLAN_CHANGED',r.manifest_for_plan,self.recipe,plan)
        plan=copy.deepcopy(self.plan);plan['sourceLock']['commit']='d'*40;self.rehash(plan)
        self.rejected('PLAN_CHANGED',r.manifest_for_plan,self.recipe,plan)
    def test_worker_and_status_use_frozen_plan_without_network_resolution(self):
        job=str(uuid.uuid4())
        with tempfile.TemporaryDirectory() as directory,patch.object(r,'no_symlink'),patch.object(r,'job_directory',return_value=pathlib.Path(directory)),patch.object(r,'read_json',return_value=self.plan),patch.object(r,'resolve_source',side_effect=AssertionError('must not resolve')),patch.object(r,'status',return_value={'state':'running'}):
            worker=r.Worker(job,self.recipe)
            self.assertEqual(self.commit,worker.manifest['commit'])
            self.assertEqual({'state':'running'},r.control('status',{'jobId':job,'planHash':self.plan['hash']},self.recipe))
    def test_apply_preconditions_reuse_original_lock(self):
        job=str(uuid.uuid4())
        with patch.object(r,'lock_file',return_value=contextlib.nullcontext()),patch.object(r,'managed_marker',return_value=None),patch.object(r,'create_plan',return_value=self.plan) as fresh,patch.object(r,'initialize_layout'),patch.object(r,'job_directory'),patch.object(r,'start_unit'),patch.object(r,'status',return_value={}),patch.object(r,'resolve_source',side_effect=AssertionError('must not resolve')):
            r.apply({'plan':self.plan,'confirmedPlanHash':self.plan['hash'],'jobId':job,'acceptAcmeTerms':True},self.recipe,{})
            self.assertEqual(self.lock,fresh.call_args.kwargs['source_lock'])
    def test_omitted_email_has_same_canonical_plan_as_explicit_empty_email(self):
        options={key:value for key,value in self.plan['options'].items() if key!='acmeEmail'}
        facts={'os':'ubuntu','osVersion':'24.04','arch':'amd64','cpus':4,'memoryKiB':8*1024*1024}
        with patch.object(r,'platform_facts',return_value=facts),patch.object(r,'managed_marker',return_value=None),patch.object(r,'disk_free',return_value=40*1024**3),patch.object(r,'docker_facts',return_value={'installed':False,'version':None,'composeVersion':None}),patch.object(r,'check_ports'),patch.object(r,'dns_check',return_value=['8.8.8.8']),patch.object(r.pathlib.Path,'exists',return_value=False):
            omitted=r.create_plan(options,self.recipe,issued_at=123,source_lock=self.lock)
            empty=r.create_plan(dict(options,acmeEmail=''),self.recipe,issued_at=123,source_lock=self.lock)
            legacy=r.create_plan(self.plan['options'],self.recipe,issued_at=123,source_lock=self.lock)
        self.assertEqual('',omitted['options']['acmeEmail']);self.assertEqual(omitted['hash'],empty['hash'])
        self.assertEqual(r.canonical(omitted),r.canonical(empty))
        self.assertEqual(self.plan['options'],legacy['options'])
        self.assertEqual(self.lock,legacy['sourceLock'])
        self.assertEqual(self.commit,r.manifest_for_plan(self.recipe,legacy)['commit'])
    def test_email_free_plan_still_requires_explicit_acme_terms_consent(self):
        self.plan['options']['acmeEmail']='';self.rehash(self.plan)
        job=str(uuid.uuid4())
        for consent in (None,False):
            with self.subTest(consent=consent),patch.object(r,'lock_file',return_value=contextlib.nullcontext()),patch.object(r,'managed_marker',return_value=None),patch.object(r,'create_plan',return_value=self.plan),patch.object(r,'initialize_layout') as initialize:
                request={'plan':self.plan,'confirmedPlanHash':self.plan['hash'],'jobId':job}
                if consent is not None:request['acceptAcmeTerms']=consent
                self.rejected('ACME_TERMS_REQUIRED',r.apply,request,self.recipe,{})
                initialize.assert_not_called()
    def test_missing_plugin_plan_requires_package_consent_before_initialization(self):
        facts={'os':'ubuntu','osVersion':'24.04','arch':'amd64','cpus':2,'memoryKiB':4*1024*1024}
        for missing_compose,missing_buildx in ((True,False),(False,True),(True,True)):
            docker={'installed':True,'version':'29.1.3','composeVersion':None if missing_compose else 'v2.30.3'}
            expected_actions=[]
            if missing_compose:
                docker['installCompose']=True;expected_actions.append('install-compose')
            if missing_buildx:
                docker['installBuildx']=True;expected_actions.append('install-buildx')
            with self.subTest(missing_compose=missing_compose,missing_buildx=missing_buildx):
                with patch.object(r,'platform_facts',return_value=facts),patch.object(r,'managed_marker',return_value=None),patch.object(r,'disk_free',return_value=20*1024**3),patch.object(r,'docker_facts',return_value=docker),patch.object(r,'check_ports'),patch.object(r,'dns_check',return_value=['8.8.8.8']),patch.object(r.pathlib.Path,'exists',return_value=False),patch.object(r,'run',return_value=b'unrelated-vpn\n'):
                    plan=r.create_plan(self.plan['options'],self.recipe,source_lock=self.lock)
                self.assertEqual(expected_actions,plan['actions'][:plan['actions'].index('fetch-verified-source')])
                self.assertNotIn('install-docker',plan['actions'])
                self.assertEqual('compact-test',plan['resourceProfile'])
                request={'plan':plan,'confirmedPlanHash':plan['hash'],'jobId':str(uuid.uuid4()),'acceptAcmeTerms':True}
                for consent in (None,False,True):
                    with self.subTest(consent=consent),patch.object(r,'lock_file',return_value=contextlib.nullcontext()),patch.object(r,'managed_marker',return_value=None),patch.object(r,'create_plan',return_value=plan),patch.object(r,'initialize_layout') as initialize,patch.object(r,'job_directory'),patch.object(r,'start_unit'),patch.object(r,'status',return_value={'state':'running'}):
                        submitted=dict(request)
                        if consent is not None:submitted['confirmDockerInstall']=consent
                        if consent:
                            self.assertEqual({'state':'running'},r.apply(submitted,self.recipe,{}))
                            initialize.assert_called_once()
                        else:
                            self.rejected('DOCKER_CONFIRMATION_REQUIRED',r.apply,submitted,self.recipe,{})
                            initialize.assert_not_called()
    def test_existing_job_plan_does_not_check_latest_release(self):
        with patch.object(r,'managed_marker',return_value={'jobId':str(uuid.uuid4())}),patch.object(r,'no_symlink'),patch.object(r,'read_json',return_value=self.plan),patch.object(r,'create_plan',return_value=self.plan) as create,patch.object(r,'resolve_source',side_effect=AssertionError('must not resolve')):
            self.assertEqual(self.plan,r.plan_latest(self.plan['options'],self.recipe))
            self.assertEqual(self.lock,create.call_args.kwargs['source_lock'])
    def archive(self, missing=False, unknown_base=False):
        buffer=io.BytesIO();prefix='meet-space-'+self.commit
        with tarfile.open(fileobj=buffer,mode='w:gz') as archive:
            root=tarfile.TarInfo(prefix);root.type=tarfile.DIRTYPE;archive.addfile(root)
            paths=list(self.files)
            if missing:paths=paths[:-1]
            for path in paths:
                data=(b'FROM unknown:latest\n' if unknown_base else b'FROM scratch\n') if path.endswith('Dockerfile') else b'fixture\n'
                member=tarfile.TarInfo(prefix+'/'+path);member.size=len(data);archive.addfile(member,io.BytesIO(data))
        return buffer.getvalue()
    def test_archive_hash_required_inputs_and_base_image_guard(self):
        for missing,unknown in ((False,False),(True,False),(False,True)):
            data=self.archive(missing,unknown);opener=Mock();opener.open.return_value=io.BytesIO(data)
            with patch.object(r.urllib.request,'build_opener',return_value=opener):
                if missing:self.rejected('SOURCE_INCOMPATIBLE',r.source_snapshot,self.commit,self.recipe)
                elif unknown:self.rejected('UNPINNED_BASE_IMAGE',r.source_snapshot,self.commit,self.recipe)
                else:
                    checksum,files=r.source_snapshot(self.commit,self.recipe)
                    self.assertEqual(r.digest(data),checksum);self.assertEqual(set(self.files),set(files))

if __name__=='__main__':unittest.main(verbosity=2)
