#!/usr/bin/env python3
"""Re-sign a reviewed recipe after changes. Private key is an explicit local input, never bundled."""
import argparse, hashlib, json, pathlib, subprocess
p = argparse.ArgumentParser()
p.add_argument('--bundle', type=pathlib.Path, default=pathlib.Path('installer/v1'))
p.add_argument('--key', type=pathlib.Path, required=True)
a = p.parse_args()
m = json.loads((a.bundle/'release.json').read_bytes())
for name, field in [('runner.py','runnerSha256'),('docker.asc','dockerKeySha256')]:
    m[field] = hashlib.sha256((a.bundle/name).read_bytes()).hexdigest()
(a.bundle/'release.json').write_bytes(json.dumps(m,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode())
subprocess.run(['openssl','pkey','-in',str(a.key),'-pubout','-out',str(a.bundle/'trust-root.pem')],check=True,stdout=subprocess.DEVNULL)
subprocess.run(['openssl','dgst','-sha256','-sign',str(a.key),'-out',str(a.bundle/'release.sig'),str(a.bundle/'release.json')],check=True)
print('Recipe signed:', m['version'], m['profile'])
