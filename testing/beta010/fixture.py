#!/usr/bin/env python3
"""Disposable real registration/direct-call/SFU assembly. Never targets production."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import ssl
import subprocess
import time
import urllib.request

SOURCE = Path(__file__).resolve().parent
ROOT = SOURCE.parents[1]
DEFAULT_HOME = ROOT / '.audit/beta010-fixture'
MARKER = 'meetspace-disposable-beta010'
PORTS = {'pg':15410, 'redis':16310, 'api':18410, 'media':18910, 'sfu':50110, 'turn':13410, 'minio':19010}

def private_write(target, text):
    target.write_text(text)
    target.chmod(0o600)

def run(arguments, log, cwd=None, env=None):
    return subprocess.run([str(v) for v in arguments], cwd=cwd, env=env, stdout=log, stderr=log, check=True)

def toolchain():
    cached = Path.home() / 'go/pkg/mod/golang.org/toolchain@v0.0.1-go1.26.9.darwin-arm64/bin/go'
    return str(cached) if cached.is_file() else shutil.which('go')

def stop(home, remove=False):
    if not (home/'fixture-marker').is_file() or (home/'fixture-marker').read_text().strip()!=MARKER:
        raise RuntimeError('Marked beta010 home required')
    info = json.loads((home/'fixture-config.json').read_text())
    if (home/'pid.json').is_file():
        pid=json.loads((home/'pid.json').read_text())['pid']
        command=subprocess.run(['ps','-p',str(pid),'-o','command='],capture_output=True,text=True).stdout.strip()
        if command.startswith(str(home/'fixture')+' '+str(home/'settings.json')):
            os.kill(pid,signal.SIGTERM)
    with (home/'stop.log').open('a') as log:
        if (home/'pg-data/postmaster.pid').exists():
            run([Path(info['postgresBin'])/'pg_ctl','-D',home/'pg-data','-m','fast','stop'],log)
    for cid in info.get('containers',[]):
        label=subprocess.run(['docker','inspect','--format','{{index .Config.Labels "meetspace.fixture"}}',cid],capture_output=True,text=True)
        if label.returncode==0 and label.stdout.strip()==MARKER:
            subprocess.run(['docker','stop',cid],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
    if remove:
        # Go module directories are deliberately read-only; this is our private cloned cache.
        for directory, folders, files in os.walk(home, topdown=True, followlinks=False):
            os.chmod(directory,0o700)
        shutil.rmtree(home)

def start(args):
    os.umask(0o077)
    home=args.home.resolve()
    if home.exists(): raise RuntimeError('Use a new disposable home')
    pg=Path(args.postgres_bin).resolve()
    if not (pg/'initdb').is_file(): raise RuntimeError('PostgreSQL tools required')
    for name,port in PORTS.items():
        kind=socket.SOCK_DGRAM if name in ('sfu','turn') else socket.SOCK_STREAM
        with socket.socket(socket.AF_INET,kind) as test: test.bind(('127.0.0.1',port))
    lan=args.lan_address or subprocess.check_output(['ipconfig','getifaddr','en0'],text=True).strip()
    socket.inet_aton(lan)
    home.mkdir(parents=True,mode=0o700)
    private_write(home/'fixture-marker',MARKER+'\n')
    info={'postgresBin':str(pg),'containers':[],'ports':PORTS}
    private_write(home/'fixture-config.json',json.dumps(info))
    try:
        with (home/'setup.log').open('w') as log:
            for name,extra in [('redis',['redis:7-alpine','redis-server','--save','','--appendonly','no']),
                               ('minio',['-e','MINIO_ROOT_USER=beta010-fixture','-e','MINIO_ROOT_PASSWORD='+secrets.token_urlsafe(36),'go-recorder-minio:local','server','/data'])]:
                result=subprocess.run(['docker','run','--detach','--rm','--label','meetspace.fixture='+MARKER,
                    '--publish',f'127.0.0.1:{PORTS[name]}:'+('6379' if name=='redis' else '9000')]+extra,
                    stdout=subprocess.PIPE,stderr=log,check=True,text=True)
                info['containers'].append(result.stdout.strip())
                if name=='minio':
                    minio_secret=next(v.split('=',1)[1] for v in extra if v.startswith('MINIO_ROOT_PASSWORD='))
                private_write(home/'fixture-config.json',json.dumps(info))
            backend=home/'backend';backend.mkdir()
            # Preserve the actual working source, including current direct-call handlers. Read-only origin.
            for name in ['internal','database']:
                shutil.copytree(args.backend_source/name,backend/name)
            for name in ['go.mod','go.sum']: shutil.copy2(args.backend_source/name,backend/name)
            commit=subprocess.check_output(['git','-C',str(args.backend_source),'rev-parse','HEAD'],text=True).strip()
            manifest={str(file.relative_to(backend)):hashlib.sha256(file.read_bytes()).hexdigest()
                for file in backend.rglob('*') if file.is_file()}
            private_write(home/'source-snapshot.json',json.dumps({'baseCommit':commit,'sha256':manifest},sort_keys=True))
            command=backend/'cmd/android-beta010-fixture';command.mkdir(parents=True)
            shutil.copyfile(SOURCE/'main.go',command/'main.go')
            (home/'pg-socket').mkdir()
            run([pg/'initdb','-D',home/'pg-data','-A','trust','--no-locale','--encoding=UTF8','-U','meetspacefixture'],log)
            run([pg/'pg_ctl','-D',home/'pg-data','-l',home/'postgres.log','-o',f'-h 127.0.0.1 -p {PORTS["pg"]} -k {home/"pg-socket"}','start'],log)
            run([pg/'createdb','-h','127.0.0.1','-p',PORTS['pg'],'-U','meetspacefixture','meetspace_beta010'],log)
            tls=home/'tls';tls.mkdir()
            private_write(tls/'ca.cnf','[req]\ndistinguished_name=dn\nx509_extensions=ca\nprompt=no\n[dn]\nCN=MeetSpace disposable beta010 CA\n[ca]\nbasicConstraints=critical,CA:TRUE\nkeyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid:always\n')
            private_write(tls/'server.ext',f'subjectAltName=IP:127.0.0.1,IP:10.0.2.2,IP:{lan},DNS:localhost\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\n')
            run(['openssl','req','-x509','-newkey','rsa:3072','-nodes','-keyout',tls/'ca-key.pem','-out',tls/'ca.pem','-days','2','-config',tls/'ca.cnf'],log)
            run(['openssl','req','-new','-newkey','rsa:3072','-nodes','-keyout',tls/'server-key.pem','-out',tls/'server.csr','-subj','/CN=127.0.0.1'],log)
            run(['openssl','x509','-req','-in',tls/'server.csr','-CA',tls/'ca.pem','-CAkey',tls/'ca-key.pem','-CAcreateserial','-out',tls/'server.pem','-days','2','-extfile',tls/'server.ext'],log)
            settings={'LAN':lan,'Redis':f'127.0.0.1:{PORTS["redis"]}','TURNSecret':secrets.token_urlsafe(48),
                'DSN':f'host=127.0.0.1 port={PORTS["pg"]} user=meetspacefixture dbname=meetspace_beta010 sslmode=disable',
                'JWTSecret':secrets.token_urlsafe(48),'Certificate':str(tls/'server.pem'),'PrivateKey':str(tls/'server-key.pem'),
                'Listen':f'127.0.0.1:{PORTS["api"]}','BackendCommit':commit,'MinIO':f'127.0.0.1:{PORTS["minio"]}',
                'MinIOAccess':'beta010-fixture','MinIOSecret':minio_secret}
            private_write(home/'settings.json',json.dumps(settings))
            # APFS clone gives Go its own writable public module cache; origin/cache stay unchanged.
            module_cache=home/'go-mod-cache'
            run(['cp','-cR',Path.home()/'go/pkg/mod',module_cache],log)
            env=os.environ.copy();env['GOCACHE']=str(home/'go-cache');env['GOMODCACHE']=str(module_cache);env['GOTOOLCHAIN']='local'
            run([toolchain(),'build','-o',home/'fixture','./cmd/android-beta010-fixture'],log,backend,env)
        with (home/'fixture.log').open('w') as log:
            process=subprocess.Popen([str(home/'fixture'),str(home/'settings.json')],cwd=backend,stdout=log,stderr=log,start_new_session=True)
        private_write(home/'pid.json',json.dumps({'pid':process.pid}))
        origin=f'https://127.0.0.1:{PORTS["api"]}'
        context=ssl.create_default_context(cafile=str(tls/'ca.pem'))
        for _ in range(120):
            try:
                with urllib.request.urlopen(origin+'/version',context=context,timeout=2) as response:
                    if json.load(response)['version']=='beta010': break
            except Exception: time.sleep(.5)
        else: raise RuntimeError('Fixture readiness failed')
        accounts=[]
        for index in range(3):
            email='beta010-'+secrets.token_hex(8)+'@example.test';password=secrets.token_urlsafe(24)
            request=urllib.request.Request(origin+'/api/v1/auth/register',data=json.dumps({'email':email,'password':password,
                'displayName':'Disposable beta010 '+str(index+1)}).encode(),headers={'Content-Type':'application/json'},method='POST')
            with urllib.request.urlopen(request,context=context,timeout=20) as response:
                if response.status!=201:raise RuntimeError('Registration failed')
                user=json.load(response)['user']['id']
            accounts.append((email,password,user))
        properties=f'origin={origin}\ncaPath={tls/"ca.pem"}\n'
        properties+='\n'.join(f'{key}={value}' for key,value in {'email':accounts[0][0],'password':accounts[0][1],
            'peerEmail':accounts[1][0],'peerPassword':accounts[1][1],'secondEmail':accounts[1][0],'secondPassword':accounts[1][1],
            'callerUserId':accounts[0][2],'calleeUserId':accounts[1][2],'peerUserId':accounts[1][2]}.items())+'\n'
        private_write(home/'live.properties',properties)
        android=properties.replace('origin='+origin,f'origin=https://10.0.2.2:{PORTS["api"]}')
        android+='tlsCAPath=beta-direct-calls-ca.pem\n'
        private_write(home/'beta-direct-calls.properties',android)
        print('Disposable beta010 HTTPS/auth/direct-call/SFU fixture ready:',home)
    except Exception:
        stop(home)
        raise

def main():
    parser=argparse.ArgumentParser();parser.add_argument('mode',choices=['start','stop'])
    parser.add_argument('--home',type=Path,default=DEFAULT_HOME)
    parser.add_argument('--backend-source',type=Path,default=Path('/Users/aleksandranickij/htdocs/go-recorder'))
    parser.add_argument('--postgres-bin',default='/opt/homebrew/opt/postgresql@14/bin')
    parser.add_argument('--lan-address');parser.add_argument('--remove',action='store_true')
    args=parser.parse_args()
    if args.mode=='start':start(args)
    else:stop(args.home.resolve(),args.remove);print('Disposable beta010 fixture stopped.')

if __name__=='__main__':
    try:main()
    except Exception as error:
        print('Beta010 fixture operation failed:',type(error).__name__)
        raise SystemExit(1)
