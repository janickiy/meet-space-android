import importlib.util, pathlib, tempfile, unittest, json, os, dataclasses, copy, contextlib, types, uuid
from unittest.mock import patch
ROOT=pathlib.Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('runner',ROOT/'installer/v1/runner.py');r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)

class InstallerTests(unittest.TestCase):
 def setUp(self):
  self.bundle={n:(ROOT/'installer/v1'/n).read_bytes() for n in ['runner.py','trust-root.pem','release.json','release.sig','docker.asc']}
  self.manifest=json.loads(self.bundle['release.json'])
  self.options={'domain':'meet.example.com','publicIpv4':'8.8.8.8','acmeEmail':'owner@example.com'}
 def error(self,code,fn,*args,**kwargs):
  with self.assertRaises(r.InstallError) as caught:fn(*args,**kwargs)
  self.assertEqual(code,caught.exception.code)
 def verify(self,b):return r.verify_bundle(b['runner.py'],b['trust-root.pem'],b['release.json'],b['release.sig'],b['docker.asc'])
 def test_bundle_signature_and_integrity(self):self.assertEqual(self.manifest,self.verify(self.bundle))
 def test_modified_runner_rejected(self):
  self.error('RELEASE_CHECKSUM',self.verify,dict(self.bundle,**{'runner.py':self.bundle['runner.py']+b'\n'}))
 def test_modified_manifest_rejected(self):
  self.error('RELEASE_SIGNATURE',self.verify,dict(self.bundle,**{'release.json':self.bundle['release.json']+b' '}))
 def test_docker_trust_key_tamper_rejected(self):self.error('RELEASE_CHECKSUM',self.verify,dict(self.bundle,**{'docker.asc':b'wrong'}))
 def test_duplicate_json_keys_rejected(self):self.error('INVALID_REQUEST',r.strict_json,b'{"x":1,"x":2}')
 def test_non_object_and_oversize_json_rejected(self):
  for v in [b'[]',b'x'*(r.MAX_JSON+1)]:self.error('INVALID_REQUEST',r.strict_json,v)
 def test_domain_injection_rejected(self):
  for d in ['a;touch.x','$(id).example.com','a\n.example.com','--flag.com','8.8.8.8','a..com','A.example.com']:
   self.error('INVALID_DOMAIN',r.validate_options,dict(self.options,domain=d),self.manifest)
 def test_production_rejects_private_ip(self):self.error('PUBLIC_DOMAIN_REQUIRED',r.validate_options,dict(self.options,publicIpv4='192.168.5.15'),self.manifest)
 def test_optional_email_normalizes_missing_and_preserves_legacy_value(self):
  omitted={key:value for key,value in self.options.items() if key!='acmeEmail'}
  expected=dict(self.options,acmeEmail='')
  self.assertEqual(expected,r.validate_options(omitted,self.manifest))
  self.assertEqual(expected,r.validate_options(expected,self.manifest))
  self.assertEqual(self.options,r.validate_options(self.options,self.manifest))
 def test_optional_email_still_rejects_invalid_values_and_unknown_options(self):
  for email in [None,False,1,[],{},' ','invalid','owner@example.com\n','a'*255+'@example.com']:
   with self.subTest(email=email):self.error('INVALID_EMAIL',r.validate_options,dict(self.options,acmeEmail=email),self.manifest)
  self.error('INVALID_REQUEST',r.validate_options,dict(self.options,unexpected='x'),self.manifest)
 def test_certbot_registration_argv_without_email_and_with_legacy_email(self):
  class StopAfterCertbot(Exception):pass
  for email in ['',self.options['acmeEmail']]:
   with self.subTest(email=email):
    worker=object.__new__(r.Worker);worker.options=dict(self.options,acmeEmail=email);worker.manifest=self.manifest
    worker.images_file=pathlib.Path('/unused/images.json');worker.env_file=pathlib.Path('/private/server.env')
    with patch.object(worker,'write_nginx'),patch.object(worker,'compose'),patch.object(worker,'copy_certificate',side_effect=StopAfterCertbot),patch.object(r,'read_json',return_value={'certbot':'sha256:'+'a'*64}),patch.object(r,'run') as execute:
     with self.assertRaises(StopAfterCertbot):worker.phase_https()
    execute.assert_called_once();argv=execute.call_args.args[0]
    self.assertIn('--agree-tos',argv);self.assertIn('--non-interactive',argv)
    if email:
     self.assertEqual(email,argv[argv.index('--email')+1]);self.assertNotIn('--register-unsafely-without-email',argv)
    else:
     self.assertIn('--register-unsafely-without-email',argv);self.assertNotIn('--email',argv);self.assertNotIn('-m',argv)
    self.assertNotIn('--env-file',argv);self.assertNotIn(str(worker.env_file),argv)
    self.assertIsNone(execute.call_args.kwargs.get('env'))
 def test_test_profile_is_separate_signed_metadata(self):
  options=dict(self.options,domain='stage04.test',publicIpv4='192.168.5.15')
  self.error('PUBLIC_DOMAIN_REQUIRED',r.validate_options,options,self.manifest)
  self.assertEqual(options,r.validate_options(options,dict(self.manifest,profile='integration-v1')))
 def test_dns_mismatch_and_ipv6_rejected(self):
  with patch.object(r.socket,'getaddrinfo',return_value=[(None,None,None,None,('::1',443))]):self.error('DNS_MISMATCH',r.dns_check,self.options)
 def test_ss_reads_local_not_peer_port(self):
  with patch.object(r.shutil,'which',return_value='/usr/bin/ss'),patch.object(r,'run',return_value=b'LISTEN 0 128 0.0.0.0:443 0.0.0.0:*\n'):
   self.error('PORT_OCCUPIED',r.check_ports)
 def test_uuid_cannot_be_path_or_command(self):
  for name in ['../evil','x;id','--help','0'*36]:self.error('INVALID_JOB',r.job_id,name)
 def test_plan_rejects_unconfirmed_and_tampered(self):
  self.error('CONFIRMATION_REQUIRED',r.apply,{'plan':{'hash':'a'}},self.manifest,self.bundle)
  self.error('PLAN_CHANGED',r.apply,{'plan':{'hash':'a'},'confirmedPlanHash':'a'},self.manifest,self.bundle)
 def test_canonical_hash_independent_of_dict_order(self):self.assertEqual(r.digest(r.canonical({'b':1,'a':2})),r.digest(r.canonical({'a':2,'b':1})))
 def test_dockerfile_pins_all_external_stages(self):
  result=r.pinned_dockerfile('FROM --platform=$BUILDPLATFORM foo:latest AS build\nFROM build AS runtime\n',{'foo:latest':'foo@sha256:'+'a'*64})
  self.assertNotIn('latest',result);self.assertIn('FROM build AS runtime',result)
  self.error('UNPINNED_BASE_IMAGE',r.pinned_dockerfile,'FROM bad:latest',{})
 def test_atomic_public_permissions_survive_private_umask(self):
  with tempfile.TemporaryDirectory() as d, patch.object(r,'no_symlink'):
   old=os.umask(0o077)
   try:r.write_atomic(pathlib.Path(d)/'public',b'key',0o644)
   finally:os.umask(old)
   self.assertEqual(0o644,(pathlib.Path(d)/'public').stat().st_mode&0o777)
 def test_existing_symlink_rejected(self):
  with tempfile.TemporaryDirectory(dir=ROOT) as d:
   p=pathlib.Path(d)/'bad';p.symlink_to(ROOT)
   self.error('UNSAFE_PATH',r.no_symlink,p,require_root=False)
 def test_cancel_journal_checkpoint_and_resume(self):
  with tempfile.TemporaryDirectory() as d,patch.object(r,'no_symlink'):
   path=pathlib.Path(d);r.state_write(path,'running','source',completed=['docker'])
   r.state_write(path,'cancelled','images')
   state=r.read_json(path/'state.json');self.assertEqual(['docker'],state['completedPhases']);self.assertEqual(2,state['sequence'])
 def test_archive_path_traversal_and_links_rejected(self):
  import tarfile,io
  for name,kind in [('meet-space-'+'a'*40+'/../escape',tarfile.REGTYPE),('meet-space-'+'a'*40+'/link',tarfile.SYMTYPE)]:
   with tempfile.TemporaryDirectory() as d:
    archive=pathlib.Path(d)/'bad.tar.gz'
    with tarfile.open(archive,'w:gz') as output:
     member=tarfile.TarInfo(name);member.type=kind;member.linkname='/etc/passwd' if kind==tarfile.SYMTYPE else '';output.addfile(member,io.BytesIO(b''))
    self.error('SOURCE_INVALID',r.extract_source,archive,pathlib.Path(d)/'target','a'*40)
 def test_extracted_public_source_is_readable_inside_non_root_images(self):
  import tarfile,io
  with tempfile.TemporaryDirectory() as d,patch.object(r,'no_symlink'):
   archive=pathlib.Path(d)/'source.tar.gz'; destination=pathlib.Path(d)/'out'; prefix='meet-space-'+'a'*40
   with tarfile.open(archive,'w:gz') as output:
    folder=tarfile.TarInfo(prefix+'/database');folder.type=tarfile.DIRTYPE;folder.mode=0o700;output.addfile(folder)
    member=tarfile.TarInfo(prefix+'/database/migrate.sql');member.mode=0o600;member.size=8;output.addfile(member,io.BytesIO(b'SELECT 1'))
   old=os.umask(0o077)
   try:r.extract_source(archive,destination,'a'*40)
   finally:os.umask(old)
   self.assertEqual(0o755,(destination/'database').stat().st_mode&0o777)
   self.assertEqual(0o644,(destination/'database/migrate.sql').stat().st_mode&0o777)
 def test_control_entry_failure_is_nonzero_for_systemd_renewal(self):
  import io,contextlib
  with patch.object(r,'main',side_effect=r.InstallError('ACME_FAILED')),contextlib.redirect_stdout(io.StringIO()) as output:
   self.assertFalse(r.entry('renew','unused'))
  self.assertEqual('ACME_FAILED',json.loads(output.getvalue())['errorCode'])
 def test_extra_bootstrap_file_cannot_be_written(self):
  with patch.object(r,'BOOTSTRAP_BUNDLE',dict(self.bundle,unexpected=b'x'),create=True):self.error('RELEASE_INVALID',r.load_bundle)
 def test_compose_only_expected_public_services_and_immutable_images(self):
  with tempfile.TemporaryDirectory() as d,patch.object(r,'no_symlink'):
   w=object.__new__(r.Worker);w.directory=pathlib.Path(d)/'job';w.config=pathlib.Path(d);w.compose_file=w.config/'compose.json';w.env_file=w.config/'server.env'
   values={'RABBIT_MQ_USER':'u','RABBIT_MQ_PASSWORD':'secret','MINIO_ROOT_USER':'u','MINIO_ROOT_PASSWORD':'secret','MINIO_BUCKET':'b','APP_ENV':'production','PUBLIC_FRONTEND_URL':'https://example.com','MINIO_PUBLIC_ENDPOINT':'https://example.com'}
   images={n:'sha256:'+'a'*64 for n in [*r.APP_SERVICES,'proxy','postgres','redis','rabbitmq','coturn','certbot']}
   w.write_compose(values,images);doc=r.read_json(w.compose_file)
   self.assertEqual({'proxy','media-worker','coturn'},{n for n,v in doc['services'].items() if 'ports' in v})
   self.assertTrue(all(v['image'].startswith('sha256:') for v in doc['services'].values()))
   self.assertEqual(['ALL'],doc['services']['coturn']['cap_drop'])
   self.assertEqual(['NET_BIND_SERVICE'],doc['services']['coturn']['cap_add'])
   self.assertEqual(['no-new-privileges:true'],doc['services']['coturn']['security_opt'])
   self.assertTrue(any('/config' not in v.split(':')[-2] for v in doc['services']['proxy']['volumes']))
   self.assertIn(':/etc/nginx/conf.d:ro',doc['services']['proxy']['volumes'][0])

class UbuntuPlatformTests(unittest.TestCase):
 def setUp(self):
  self.legacy=json.loads((ROOT/'installer/v1/release.json').read_bytes())
  self.legacy.pop('dockerPackagesByUbuntu',None)
  self.manifest=copy.deepcopy(self.legacy)
  self.manifest['dockerPackagesByUbuntu']={version:{arch:{package:pin.replace('~ubuntu.24.04~noble','~ubuntu.'+version+'~'+suite) for package,pin in pins.items()} for arch,pins in self.legacy['dockerPackages'].items()} for version,suite in r.UBUNTU_SUITES.items()}
  self.options={'domain':'meet.example.com','publicIpv4':'8.8.8.8','acmeEmail':''}
 def rejected(self,code,fn,*args,**kwargs):
  with self.assertRaises(r.InstallError) as error:fn(*args,**kwargs)
  self.assertEqual(code,error.exception.code)
 def facts(self,version='22.04',arch='amd64'):
  return {'os':'ubuntu','osVersion':version,'arch':arch,'cpus':2,'memoryKiB':3_840_000}
 def create(self,version='22.04',arch='amd64',manifest=None):
  with patch.object(r,'platform_facts',return_value=self.facts(version,arch)),patch.object(r,'managed_marker',return_value=None),patch.object(r,'disk_free',return_value=20*1024**3),patch.object(r,'docker_facts',return_value={'installed':False,'version':None,'composeVersion':None}),patch.object(r,'check_ports'),patch.object(r,'dns_check',return_value=['8.8.8.8']),patch.object(r.pathlib.Path,'exists',return_value=False):
   return r.create_plan(self.options,manifest or self.manifest,issued_at=123)
 def test_platform_facts_accepts_both_lts_versions_and_architectures(self):
  for version in r.UBUNTU_SUITES:
   for machine,arch in [('x86_64','amd64'),('aarch64','arm64')]:
    def contents(path):
     return 'ID=ubuntu\nVERSION_ID="'+version+'"\nVERSION_CODENAME=ignored\n' if str(path)=='/etc/os-release' else 'MemTotal: 3840000 kB\n'
    with self.subTest(version=version,arch=arch),patch.object(r.pathlib.Path,'read_text',contents),patch.object(r.pathlib.Path,'is_dir',return_value=True),patch.object(r.shutil,'which',return_value='/usr/bin/systemd-run'),patch.object(r.os,'uname',return_value=types.SimpleNamespace(machine=machine)),patch.object(r.os,'cpu_count',return_value=2):
     self.assertEqual(self.facts(version,arch),r.platform_facts())
 def test_unsupported_distributions_versions_architectures_and_systemd_fail(self):
  for distro,version,machine in [('ubuntu','20.04','x86_64'),('ubuntu','22.10','x86_64'),('debian','22.04','x86_64'),('ubuntu','24.04','riscv64')]:
   with self.subTest(distro=distro,version=version,machine=machine),patch.object(r.pathlib.Path,'read_text',return_value='ID='+distro+'\nVERSION_ID='+version+'\n'),patch.object(r.os,'uname',return_value=types.SimpleNamespace(machine=machine)):
    self.rejected('UNSUPPORTED_PLATFORM',r.platform_facts)
  with patch.object(r.pathlib.Path,'read_text',return_value='ID=ubuntu\nVERSION_ID=22.04\n'),patch.object(r.os,'uname',return_value=types.SimpleNamespace(machine='x86_64')),patch.object(r.pathlib.Path,'is_dir',return_value=False):
   self.rejected('SYSTEMD_REQUIRED',r.platform_facts)
 def test_automatic_baseline_and_exact_os_arch_pins_are_hashed(self):
  for version in r.UBUNTU_SUITES:
   for arch in r.SUPPORTED_ARCHES:
    with self.subTest(version=version,arch=arch):
     plan=self.create(version,arch)
     self.assertEqual(self.facts(version,arch),plan['platform'])
     self.assertEqual('compact-test',plan['resourceProfile'])
     self.assertEqual(self.manifest['dockerPackagesByUbuntu'][version][arch],plan['dockerPackages'])
     self.assertEqual(plan['hash'],r.digest(r.canonical({key:value for key,value in plan.items() if key!='hash'})))
     self.assertEqual(self.manifest,r.manifest_for_plan(self.manifest,plan))
 def test_legacy_noble_manifest_and_plan_remain_unchanged(self):
  plan=self.create('24.04',manifest=self.legacy)
  self.assertEqual(self.legacy['dockerPackages']['amd64'],plan['dockerPackages'])
  self.assertEqual(self.legacy,r.manifest_for_plan(self.legacy,plan))
  self.assertEqual(self.legacy['dockerPackages']['amd64'],r.docker_packages(self.legacy,{'arch':'amd64'},legacy=True))
  self.rejected('RELEASE_INVALID',self.create,'22.04',manifest=self.legacy)
 def test_present_matrix_does_not_fall_back_to_legacy_noble_pins(self):
  for matrix in [None,{}, {'24.04':{}}, {'24.04':{'arm64':self.legacy['dockerPackages']['arm64']}}]:
   with self.subTest(matrix=matrix):
    manifest=dict(self.manifest,dockerPackagesByUbuntu=matrix)
    self.rejected('RELEASE_INVALID',r.docker_packages,manifest,self.facts('24.04'))
 def test_manifest_rejects_missing_wrong_suite_or_unsafe_pins(self):
  r.validate_docker_manifest(self.legacy);r.validate_docker_manifest(self.manifest)
  cases=[]
  absent_arch=copy.deepcopy(self.manifest);del absent_arch['dockerPackagesByUbuntu']['22.04']['arm64'];cases.append(absent_arch)
  absent_os=copy.deepcopy(self.manifest);del absent_os['dockerPackagesByUbuntu']['22.04'];cases.append(absent_os)
  for pin in ['1.0;touch /tmp/x','--latest','1.0\n','5:29.9.0-1~ubuntu.24.04~noble',None]:
   manifest=copy.deepcopy(self.manifest);manifest['dockerPackagesByUbuntu']['22.04']['amd64']['docker-ce']=pin;cases.append(manifest)
  extra_package=copy.deepcopy(self.manifest);extra_package['dockerPackagesByUbuntu']['22.04']['amd64']['unexpected']='1.0~ubuntu.22.04~jammy';cases.append(extra_package)
  for manifest in cases:
   with self.subTest(manifest=manifest['dockerPackagesByUbuntu']):self.rejected('RELEASE_INVALID',r.validate_docker_manifest,manifest)
  for facts in [None,{},self.facts('20.04'),dict(self.facts(),arch=[]),dict(self.facts(),osVersion=[]),dict(self.facts(),os='debian')]:
   with self.subTest(facts=facts):self.rejected('RELEASE_INVALID',r.docker_packages,self.manifest,facts)
 def test_rehashed_package_and_platform_tamper_is_rejected(self):
  for change in ['pins','platform']:
   plan=self.create()
   if change=='pins':plan['dockerPackages']=self.legacy['dockerPackages']['amd64']
   else:plan['platform']['osVersion']='24.04'
   plan['hash']=r.digest(r.canonical({key:value for key,value in plan.items() if key!='hash'}))
   with self.subTest(change=change):self.rejected('PLAN_CHANGED',r.manifest_for_plan,self.manifest,plan)
 def test_docker_phase_uses_exact_signed_suite_arch_and_package_pins(self):
  key=(ROOT/'installer/v1/docker.asc').read_bytes()
  for version,suite in r.UBUNTU_SUITES.items():
   for arch in r.SUPPORTED_ARCHES:
    with self.subTest(version=version,arch=arch),tempfile.TemporaryDirectory() as directory:
     root=pathlib.Path(directory);key_file=root/'installer/v1/docker.asc';key_file.parent.mkdir(parents=True);key_file.write_bytes(key);(root/'host/etc/apt').mkdir(parents=True)
     paths=types.SimpleNamespace(Path=lambda path:root/'host'/str(path).lstrip('/'))
     worker=object.__new__(r.Worker);worker.manifest=self.manifest
     worker.plan={'platform':self.facts(version,arch),'dockerPackages':self.manifest['dockerPackagesByUbuntu'][version][arch]}
     with patch.object(r,'ROOT',root),patch.object(r,'pathlib',paths),patch.object(r,'docker_facts',side_effect=[{'installed':False},{'installed':True}]),patch.object(r,'write_atomic') as write,patch.object(r,'run') as execute:
      worker.phase_docker()
     source=write.call_args_list[1].args[1].decode()
     self.assertIn('Suites: '+suite+'\n',source);self.assertIn('Architectures: '+arch+'\n',source)
     install=execute.call_args_list[1].args[0]
     self.assertEqual(['apt-get','-o','DPkg::Lock::Timeout=120','install','-y','--no-install-recommends',*[package+'='+pin for package,pin in sorted(worker.plan['dockerPackages'].items())]],install)
 def test_docker_phase_rejects_package_mismatch_before_host_mutations(self):
  worker=object.__new__(r.Worker);worker.manifest=self.manifest
  worker.plan={'platform':self.facts(),'dockerPackages':self.legacy['dockerPackages']['amd64']}
  with patch.object(r,'docker_facts') as inspect,patch.object(r,'write_atomic') as write,patch.object(r,'run') as execute:
   self.rejected('PLAN_CHANGED',worker.phase_docker)
  inspect.assert_not_called();write.assert_not_called();execute.assert_not_called()
 def test_resume_checks_frozen_os_and_arch_before_journal_or_service_changes(self):
  plan=self.create()
  for facts in [self.facts('24.04'),self.facts(arch='arm64')]:
   with self.subTest(facts=facts),patch.object(r,'job_directory',return_value=pathlib.Path('/unused/job')),patch.object(r,'read_json',return_value=plan),patch.object(r,'lock_file',return_value=contextlib.nullcontext()),patch.object(r,'status',return_value={'state':'failed'}),patch.object(r,'unit_active',return_value=False),patch.object(r,'platform_facts',return_value=facts),patch.object(r,'state_write') as state,patch.object(r,'start_unit') as start:
    self.rejected('PLAN_CHANGED',r.control,'resume',{'jobId':str(uuid.uuid4()),'planHash':plan['hash'],'confirmedPlanHash':plan['hash']},self.manifest)
   state.assert_not_called();start.assert_not_called()
  r.same_platform({'platform':self.facts()},dict(self.facts(),cpus=4))
  r.same_platform({'platform':{'arch':'amd64'}},self.facts('24.04'))
  self.rejected('PLAN_CHANGED',r.same_platform,{'platform':{'arch':'amd64'}},self.facts('22.04'))

if __name__=='__main__':unittest.main(verbosity=2)
