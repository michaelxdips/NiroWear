"""Explicit credential-free NiroWear_Beta_Clean only; never Gradle all-device discovery."""
import argparse
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERIAL = 'emulator-5562'

def adb(*args, timeout=60):
    name = subprocess.check_output(['adb', '-s', SERIAL, 'emu', 'avd', 'name'], timeout=10).decode().splitlines()[0]
    assert name == 'NiroWear_Beta_Clean', 'Wrong AVD; no operation permitted'
    return subprocess.check_output(['adb', '-s', SERIAL, *map(str, args)], timeout=timeout)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--class-name')
    parser.add_argument('--seed', action='store_true')
    parser.add_argument('--fixture-action', choices=['verify', 'cleanup'])
    args = parser.parse_args()
    for p in ['app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
        assert b'Success' in adb('install', '-r', ROOT / p)
    command = ['shell', 'am', 'instrument', '-w', '-r']
    if args.seed or args.fixture_action:
        action = args.fixture_action or 'seed'
        method = {'seed':'seedDocuments', 'verify':'verifyDocuments', 'cleanup':'cleanupDocuments'}[action]
        command += ['-e', 'wearAcceptance', action, '-e', 'class', 'com.nirowear.chat.WearAcceptanceSeedTest#'+method]
    else:
        command += ['-e', 'annotation', 'com.nirowear.HostDrivenGate']
        if args.class_name:
            command += ['-e', 'class', args.class_name]
    command += ['com.nirowear.test/androidx.test.runner.AndroidJUnitRunner']
    literal = adb(*command, timeout=1200)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(literal)
    assert b'OK (' in literal and b'FAILURES!!!' not in literal, 'Instrumentation failed; literal preserved'
    subprocess.check_call(['python', str(ROOT / 'scripts/instrumentation-xml.py'), str(args.output)])
    print(literal.decode(errors='replace')[-400:])

if __name__ == '__main__':
    main()
