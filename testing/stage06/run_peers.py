#!/usr/bin/env python3
"""Start 1–4 real Pion peers in a marked disposable fixture; stdout contains no credentials."""
import argparse, json, os, pathlib, signal, subprocess, shutil
from fixture import DEFAULT_HOME, MARKER

p=argparse.ArgumentParser();p.add_argument('--home',type=pathlib.Path,default=DEFAULT_HOME);p.add_argument('--count',type=int,choices=range(1,5),default=4)
p.add_argument('--screen-echo',action='store_true')
a=p.parse_args();home=a.home.resolve()
if (home/'fixture-marker').read_text().strip()!=MARKER:raise SystemExit('Not a stage06 disposable fixture')
command=home/'backend/cmd/android-stage06-peers';command.mkdir(exist_ok=True)
shutil.copyfile(pathlib.Path(__file__).with_name('peers.go'),command/'main.go')
with (home/'peers-build.log').open('w') as log:
 subprocess.run(['go','build','-o',str(home/'peers-next'),'./cmd/android-stage06-peers'],cwd=home/'backend',stdout=log,stderr=log,check=True)
if (home/'peers-pid.json').exists():
 pid=json.loads((home/'peers-pid.json').read_text())['pid']
 info=subprocess.run(['ps','-p',str(pid),'-o','command='],capture_output=True,text=True).stdout.strip()
 if info.startswith(str(home/'peers')+' '):os.kill(pid,signal.SIGTERM)
os.replace(home/'peers-next',home/'peers')
# Avoid duplicate properties or retaining a previous conference after a failed launch.
lines=[line for line in (home/'live.properties').read_text().splitlines() if not line.startswith(('conferenceId=','expectedPeers=','expectRemoteScreen='))]
(home/'live.properties').write_text('\n'.join(lines)+f'\nexpectedPeers={a.count+1}\nexpectRemoteScreen={str(a.screen_echo).lower()}\n')
env=dict(os.environ,MEETSPACE_STAGE06_PEERS=str(a.count),MEETSPACE_STAGE06_ECHO_SCREEN='1' if a.screen_echo else '0')
with (home/'peers.log').open('w') as log:
 process=subprocess.Popen([str(home/'peers'),str(home)],stdout=log,stderr=log,env=env,start_new_session=True)
(home/'peers-pid.json').write_text(json.dumps({'pid':process.pid}))
print('Real Pion peers launched. Readiness and RTP counters: peers-stats.json in the private fixture home.')
