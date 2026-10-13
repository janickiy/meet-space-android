#!/usr/bin/env python3
"""Start/stop isolated staging; credentials and keys exist only in its private home."""
import argparse
import json
import os
import pathlib
import secrets
import shutil
import signal
import socket
import ssl
import subprocess
import time
import urllib.request

COMMIT = '6ecd8b648a2aa94fc9bcc844d57784423e2c6f69'
SOURCE = pathlib.Path(__file__).resolve().parent
DEFAULT_HOME = SOURCE.parents[1]/'.audit/stage07-fixture'
MARKER = 'meetspace-disposable-stage07'

def run(arguments, log, cwd=None):
    subprocess.run([str(value) for value in arguments], cwd=cwd, stdout=log, stderr=log, check=True)

def pg_bin(raw):
    if raw:
        return pathlib.Path(raw).resolve()
    binary=shutil.which('initdb')
    if binary: return pathlib.Path(binary).parent
    brew=shutil.which('brew')
    if brew:
        for formula in ['postgresql@14','postgresql@16','postgresql@17']:
            result=subprocess.run([brew,'--prefix',formula],capture_output=True,text=True)
            path=pathlib.Path(result.stdout.strip())/'bin'
            if result.returncode==0 and (path/'initdb').is_file(): return path
    raise RuntimeError('PostgreSQL initdb/pg_ctl/createdb tools required')

def available(port):
    with socket.socket() as test:
        test.bind(('127.0.0.1',port))

def stop(home, remove=False):
    marker=home/'fixture-marker'
    if not marker.is_file() or marker.read_text().strip()!=MARKER:
        raise RuntimeError('Only a marked disposable stage07 home can be stopped')
    if (home/'pid.json').exists():
        info=json.loads((home/'pid.json').read_text())
        result=subprocess.run(['ps','-p',str(info['pid']),'-o','command='],capture_output=True,text=True)
        # Never kill a reused PID belonging to another process.
        if result.returncode==0 and result.stdout.strip().startswith(str(home/'fixture')+' '+str(home/'settings.json')):
            os.kill(info['pid'],signal.SIGTERM)
    pg=pg_bin(json.loads((home/'fixture-config.json').read_text())['postgresBin'])
    with (home/'stop.log').open('a') as log:
        if (home/'pg-data/postmaster.pid').exists():
            run([pg/'pg_ctl','-D',home/'pg-data','-m','fast','stop'],log)
    metadata=json.loads((home/'fixture-config.json').read_text())
    for cid in metadata.get('containers',[]):
        probe=subprocess.run(['docker','inspect','--format','{{index .Config.Labels "meetspace.fixture"}}',cid],capture_output=True,text=True)
        if probe.returncode==0 and probe.stdout.strip()==MARKER:
            subprocess.run(['docker','stop',cid],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
    if remove: shutil.rmtree(home)

def start(args):
    home=args.home.resolve()
    if home.exists(): raise RuntimeError('Choose a new private home or stop --remove the previous marked fixture')
    available(args.pg_port); available(args.https_port); available(16377); available(19077); available(19477)
    pg=pg_bin(args.postgres_bin)
    home.mkdir(parents=True,mode=0o700)
    home.chmod(0o700)
    (home/'fixture-marker').write_text(MARKER)
    (home/'fixture-config.json').write_text(json.dumps({'postgresBin':str(pg),'pgPort':args.pg_port,'httpsPort':args.https_port,'backendCommit':COMMIT,'containers':[]}))
    for child in ['tls','pg-socket']:
        (home/child).mkdir(mode=0o700)
    try:
        with (home/'setup.log').open('w') as log:
            lan=args.lan_address or subprocess.check_output(['ipconfig','getifaddr','en0'],text=True).strip()
            socket.inet_aton(lan)
            minio_password=secrets.token_urlsafe(36)
            (home/'minio.env').write_text('MINIO_ROOT_USER=meetspacefixture\nMINIO_ROOT_PASSWORD='+minio_password+'\n')
            (home/'minio.env').chmod(0o600)
            for name,image,extra in [('redis','redis:7-alpine',['-p','127.0.0.1:16377:6379']),('minio',args.minio_image,['-p','127.0.0.1:19077:9000','--env-file',str(home/'minio.env')])]:
                command=['docker','run','--rm','-d','--label','meetspace.fixture='+MARKER,'--name','meetspace-android-stage07-'+name]+extra+[image]
                command+=['redis-server','--save','','--appendonly','no'] if name=='redis' else ['server','/data']
                cid=subprocess.check_output(command,text=True,stderr=log).strip()
                metadata=json.loads((home/'fixture-config.json').read_text()); metadata['containers'].append(cid)
                (home/'fixture-config.json').write_text(json.dumps(metadata))
            for _ in range(60):
                try:
                    with urllib.request.urlopen('http://127.0.0.1:19077/minio/health/ready',timeout=1) as response:
                        if response.status==200: break
                except Exception: time.sleep(0.5)
            else: raise RuntimeError('Private MinIO readiness failed')
            backend=home/'backend'
            # Local source is optional. Clone always excludes untracked/dirty source changes.
            source=str(args.backend_source.resolve()) if args.backend_source else 'https://github.com/janickiy/meet-space.git'
            run(['git','clone','--no-hardlinks',source,backend],log)
            run(['git','checkout','--detach',COMMIT],log,backend)
            command=backend/'cmd/android-stage07-fixture'
            command.mkdir()
            shutil.copyfile(SOURCE/'main.go',command/'main.go')
            run([pg/'initdb','-D',home/'pg-data','-A','trust','--no-locale','-U','meetspacefixture'],log)
            run([pg/'pg_ctl','-D',home/'pg-data','-l',home/'postgres.log','-o',f'-h 127.0.0.1 -p {args.pg_port} -k {home / "pg-socket"}','start'],log)
            run([pg/'createdb','-h','127.0.0.1','-p',args.pg_port,'-U','meetspacefixture','meetspace_stage07'],log)
            tls=home/'tls'
            (tls/'ca.cnf').write_text('[req]\ndistinguished_name=dn\nx509_extensions=ca\nprompt=no\n[dn]\nCN=MeetSpace disposable stage07 CA\n[ca]\nbasicConstraints=critical,CA:TRUE\nkeyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid:always\n')
            (tls/'server.ext').write_text('subjectAltName=IP:127.0.0.1,IP:10.0.2.2,DNS:localhost,IP:'+lan+'\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\n')
            run(['openssl','req','-x509','-newkey','rsa:3072','-nodes','-keyout',tls/'ca-key.pem','-out',tls/'ca.pem','-days','2','-config',tls/'ca.cnf'],log)
            run(['openssl','req','-new','-newkey','rsa:3072','-nodes','-keyout',tls/'server-key.pem','-out',tls/'server.csr','-subj','/CN=127.0.0.1'],log)
            run(['openssl','x509','-req','-in',tls/'server.csr','-CA',tls/'ca.pem','-CAkey',tls/'ca-key.pem','-CAcreateserial','-out',tls/'server.pem','-days','2','-extfile',tls/'server.ext'],log)
            settings={'DSN':f'host=127.0.0.1 port={args.pg_port} user=meetspacefixture dbname=meetspace_stage07 sslmode=disable','JWTSecret':secrets.token_urlsafe(48),'Certificate':str(tls/'server.pem'),'PrivateKey':str(tls/'server-key.pem'),'Listen':f'127.0.0.1:{args.https_port}','Redis':'127.0.0.1:16377','MinIO':'127.0.0.1:19077','MinIOAccess':'meetspacefixture','MinIOSecret':minio_password,'StorageListen':'0.0.0.0:19477','StorageOrigin':'https://'+lan+':19477'}
            (home/'settings.json').write_text(json.dumps(settings)); (home/'settings.json').chmod(0o600)
            for key in tls.glob('*key.pem'): key.chmod(0o600)
            run(['go','build','-o',home/'fixture','./cmd/android-stage07-fixture'],log,backend)
        with (home/'fixture.log').open('w') as log:
            process=subprocess.Popen([str(home/'fixture'),str(home/'settings.json')],cwd=backend,stdout=log,stderr=log,start_new_session=True)
        (home/'pid.json').write_text(json.dumps({'pid':process.pid}))
        origin=f'https://127.0.0.1:{args.https_port}'
        context=ssl.create_default_context(cafile=str(home/'tls/ca.pem'))
        for _ in range(60):
            try:
                with urllib.request.urlopen(origin+'/version',context=context,timeout=2) as response:
                    if json.load(response)['commit']==COMMIT: break
            except Exception: time.sleep(0.5)
        else: raise RuntimeError('Fixture failed readiness')
        accounts=[]
        for index in range(4):
            email='stage07-'+secrets.token_hex(6)+'@example.test'; password=secrets.token_urlsafe(24)
            body={'email':email,'password':password,'displayName':'Disposable staging '+str(index+1)}
            request=urllib.request.Request(origin+'/api/v1/auth/register',data=json.dumps(body).encode(),headers={'Content-Type':'application/json'},method='POST')
            with urllib.request.urlopen(request,context=context,timeout=20) as response:
                if response.status!=201: raise RuntimeError('Fixture registration failed')
            accounts.append((email,password))
        properties='origin='+origin+'\ncaPath='+str(home/'tls/ca.pem')+'\nemail='+accounts[0][0]+'\npassword='+accounts[0][1]+'\nsecondEmail='+accounts[1][0]+'\nsecondPassword='+accounts[1][1]+'\n'
        properties+=''.join('email'+str(i)+'='+a[0]+'\npassword'+str(i)+'='+a[1]+'\n' for i,a in enumerate(accounts))
        (home/'live.properties').write_text(properties); (home/'live.properties').chmod(0o600)
        print('Disposable validated HTTPS fixture ready. Private home:',home)
    except Exception:
        stop(home)
        raise

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('mode',choices=['start','stop'])
    parser.add_argument('--home',type=pathlib.Path,default=DEFAULT_HOME)
    parser.add_argument('--postgres-bin')
    parser.add_argument('--backend-source',type=pathlib.Path)
    parser.add_argument('--pg-port',type=int,default=15477)
    parser.add_argument('--https-port',type=int,default=18477)
    parser.add_argument('--minio-image',default='go-recorder-minio:local',help='Existing real MinIO server image; no mock storage')
    parser.add_argument('--lan-address',help='Reachable host IP for signed HTTPS S3 downloads; defaults to macOS en0')
    parser.add_argument('--remove',action='store_true')
    args=parser.parse_args()
    if args.mode=='start': start(args)
    else: stop(args.home.resolve(),args.remove); print('Disposable fixture stopped'+(' and removed' if args.remove else ''))

if __name__=='__main__':
    try: main()
    except Exception as error:
        # Child output stays in a private file; never expose SQL, credentials or bodies.
        print('Disposable fixture operation failed:',type(error).__name__)
        raise SystemExit(1)
