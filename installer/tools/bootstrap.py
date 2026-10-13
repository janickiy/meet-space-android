#!/usr/bin/env python3
"""Generate bounded SSH stdin; output contains public code/metadata, never SSH credentials."""
import argparse, base64, json, pathlib
FILES = ('runner.py','trust-root.pem','release.json','release.sig','docker.asc')
def payload(directory, action, request):
    encoded = {name:base64.b64encode((directory/name).read_bytes()).decode() for name in FILES}
    # JSON is data inside a base64 Python string; no user input becomes shell or Python syntax.
    raw = base64.b64encode(json.dumps({'bundle':encoded,'action':action,'request':request},separators=(',',':')).encode()).decode()
    return ("import base64,json\n_d=json.loads(base64.b64decode('"+raw+"'))\n_b={k:base64.b64decode(v) for k,v in _d['bundle'].items()}\n_g={'__name__':'meetspace_bootstrap','BOOTSTRAP_BUNDLE':_b}\nexec(compile(_b['runner.py'],'meetspace-signed-installer','exec'),_g)\n_g['entry'](_d['action'],_d['request'])\n").encode()
if __name__ == '__main__':
    p=argparse.ArgumentParser(); p.add_argument('action',choices=['plan','apply','status','cancel','resume']); p.add_argument('request',type=pathlib.Path); p.add_argument('--bundle',type=pathlib.Path,default=pathlib.Path('installer/v1'))
    a=p.parse_args(); import sys; sys.stdout.buffer.write(payload(a.bundle,a.action,json.loads(a.request.read_bytes())))
