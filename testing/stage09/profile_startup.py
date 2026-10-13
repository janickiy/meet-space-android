#!/usr/bin/env python3
"""Measure process-cold launches on an explicitly selected disposable emulator, without clearing data."""
import argparse, datetime, hashlib, json, pathlib, re, statistics, subprocess, time

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--label', choices=['baseline', 'after'], required=True)
p.add_argument('--apk', type=pathlib.Path, required=True)
p.add_argument('--adb', default=str(pathlib.Path.home()/'Library/Android/sdk/platform-tools/adb'))
p.add_argument('--output', type=pathlib.Path, required=True)
a = p.parse_args()
assert a.serial.startswith('emulator-'), 'Use an explicitly owned test emulator'
base = [a.adb, '-s', a.serial]
def adb(*args):
    return subprocess.check_output(base+list(args), text=True).strip()

samples = []
for index in range(9):
    adb('shell', 'am', 'force-stop', 'com.meetspace.app')
    output = adb('shell', 'am', 'start', '-W', '-n', 'com.meetspace.app/.MainActivity')
    assert 'Status: ok' in output
    values = {key: int(re.search(r'^'+key+r': (\d+)$', output, re.M)[1]) for key in ['TotalTime', 'WaitTime']}
    values.update(iteration=index, excludedWarmup=index < 2)
    samples.append(values)
    time.sleep(1)
frames = adb('shell', 'dumpsys', 'gfxinfo', 'com.meetspace.app')
memory = adb('shell', 'dumpsys', 'meminfo', 'com.meetspace.app')
selected = [s['TotalTime'] for s in samples if not s['excludedWarmup']]
result = {'label':a.label, 'measuredAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'serial':a.serial, 'apkSha256':hashlib.sha256(a.apk.read_bytes()).hexdigest(),
    'condition':'Explicit Activity start after force-stop; 2 warmup launches excluded, 7 measured; no data clear; first-display TotalTime, not fully-drawn time.',
    'samples':samples, 'totalTimeMs':{'median':statistics.median(selected),'min':min(selected),'max':max(selected)},
    'fingerprint':adb('shell','getprop','ro.build.fingerprint'), 'pageSize':int(adb('shell','getconf','PAGE_SIZE')),
    'physicalDeviceBattery':'not measured', 'networkRequests':'not measured by this startup command'}
a.output.mkdir(parents=True, exist_ok=True)
(a.output/(a.label+'-startup.json')).write_text(json.dumps(result, indent=2)+'\n')
(a.output/(a.label+'-startup-gfxinfo.txt')).write_text(frames+'\n')
(a.output/(a.label+'-startup-meminfo.txt')).write_text(memory+'\n')
print(json.dumps({'label':a.label,'samples':len(selected),'totalTimeMs':result['totalTimeMs']}))
