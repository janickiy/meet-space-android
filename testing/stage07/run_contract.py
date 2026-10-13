#!/usr/bin/env python3
"""Build/run the actual backend probe inside the marked private checkout."""
import argparse
import pathlib
import shutil
import subprocess

SOURCE = pathlib.Path(__file__).resolve().parent
DEFAULT_HOME = SOURCE.parents[1] / '.audit/stage07-fixture'

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--home', type=pathlib.Path, default=DEFAULT_HOME)
    args = parser.parse_args()
    home = args.home.resolve()
    if (home/'fixture-marker').read_text().strip() != 'meetspace-disposable-stage07':
        raise RuntimeError('Marked stage07 fixture required')
    target = home/'backend/cmd/android-stage07-contract'
    target.mkdir(exist_ok=True)
    shutil.copyfile(SOURCE/'contract.go', target/'main.go')
    # The executable only prints check names/counts; tokens and API bodies stay in memory.
    subprocess.run(['go', 'run', './cmd/android-stage07-contract', str(home)], cwd=home/'backend', check=True)

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Stage07 contract run failed:', type(error).__name__)
        raise SystemExit(1)
