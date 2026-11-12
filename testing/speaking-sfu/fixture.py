#!/usr/bin/env python3
"""Run the real SFU from an isolated backend commit; synthetic conference signaling only.

Requires Go + openssl, no production endpoints, Docker or database. Private TLS keys
and compiled backend stay in a marked ignored directory. Android instrumentation
trusts only this disposable CA, and only on an emulator.
"""
import argparse
import io
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import ssl
import subprocess
import tarfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).resolve().parent
MARKER = 'meetspace-disposable-speaking-sfu-v1'


def command(args, log, cwd=None):
    subprocess.run([str(value) for value in args], cwd=cwd, stdout=log, stderr=log, check=True)


def stop(home):
    if not (home / 'fixture-marker').is_file() or (home / 'fixture-marker').read_text().strip() != MARKER:
        raise RuntimeError('Not a marked speaking fixture')
    pid = json.loads((home / 'pid.json').read_text())['pid']
    process = subprocess.run(['ps', '-p', str(pid), '-o', 'command='], capture_output=True, text=True)
    if process.returncode == 0 and process.stdout.strip().startswith(str(home / 'speaking-fixture') + ' '):
        os.kill(pid, signal.SIGTERM)


def start(home, source, revision):
    if home.exists():
        raise RuntimeError('Choose a new fixture home; existing artifacts are never overwritten')
    for port, kind in [(18477, socket.SOCK_STREAM), (50377, socket.SOCK_DGRAM)]:
        with socket.socket(socket.AF_INET, kind) as probe:
            probe.bind(('127.0.0.1', port))
    commit = subprocess.check_output(['git', '-C', str(source), 'rev-parse', revision + '^{commit}'], text=True).strip()
    archive = subprocess.check_output(['git', '-C', str(source), 'archive', commit])
    home.mkdir(parents=True, mode=0o700)
    (home / 'fixture-marker').write_text(MARKER)
    (home / 'backend-commit.txt').write_text(commit + '\n')
    backend = home / 'backend'
    backend.mkdir()
    with tarfile.open(fileobj=io.BytesIO(archive)) as contents:
        contents.extractall(backend, filter='data')
    destination = backend / 'cmd/android-speaking-fixture'
    destination.mkdir()
    shutil.copyfile(SOURCE / 'main.go', destination / 'main.go')
    tls = home / 'tls'
    tls.mkdir(mode=0o700)
    (tls / 'ca.cnf').write_text('[req]\ndistinguished_name=dn\nx509_extensions=ca\nprompt=no\n[dn]\nCN=MeetSpace disposable speaking CA\n[ca]\nbasicConstraints=critical,CA:TRUE\nkeyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid:always\n')
    (tls / 'server.ext').write_text('subjectAltName=IP:127.0.0.1,IP:10.0.2.2,DNS:localhost\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\n')
    with (home / 'build.log').open('w') as log:
        command(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', tls / 'ca-key.pem', '-out', tls / 'ca.pem', '-days', '2', '-config', tls / 'ca.cnf'], log)
        command(['openssl', 'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-keyout', tls / 'server-key.pem', '-out', tls / 'server.csr', '-subj', '/CN=127.0.0.1'], log)
        command(['openssl', 'x509', '-req', '-in', tls / 'server.csr', '-CA', tls / 'ca.pem', '-CAkey', tls / 'ca-key.pem', '-CAcreateserial', '-out', tls / 'server.pem', '-days', '2', '-extfile', tls / 'server.ext'], log)
        for key in tls.glob('*key.pem'):
            key.chmod(0o600)
        command(['go', 'build', '-o', home / 'speaking-fixture', './cmd/android-speaking-fixture'], log, backend)
    with (home / 'fixture.log').open('w') as log:
        process = subprocess.Popen([str(home / 'speaking-fixture'), str(tls / 'server.pem'), str(tls / 'server-key.pem')], stdout=log, stderr=log, start_new_session=True)
    (home / 'pid.json').write_text(json.dumps({'pid': process.pid}))
    trust = ssl.create_default_context(cafile=str(tls / 'ca.pem'))
    for _ in range(30):
        try:
            with urllib.request.urlopen('https://127.0.0.1:18477/status', context=trust, timeout=2) as response:
                if response.status == 200:
                    print('Disposable SFU ready. CA:', tls / 'ca.pem', 'Backend commit:', commit)
                    return
        except Exception:
            time.sleep(.1)
    stop(home)
    raise RuntimeError('Fixture did not become ready; inspect private fixture.log')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['start', 'stop'])
    parser.add_argument('--home', type=Path, default=ROOT / '.audit/speaking-sfu')
    parser.add_argument('--backend-source', type=Path)
    parser.add_argument('--revision', default='HEAD')
    args = parser.parse_args()
    if args.mode == 'stop':
        stop(args.home.resolve())
    else:
        if args.backend_source is None:
            parser.error('--backend-source is required for start')
        start(args.home.resolve(), args.backend_source.resolve(), args.revision)


if __name__ == '__main__':
    main()
