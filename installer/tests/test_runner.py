import importlib.util, pathlib, tempfile, unittest, json, os, dataclasses, copy
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

if __name__=='__main__':unittest.main(verbosity=2)
