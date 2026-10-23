#!/usr/bin/env python3
"""Offline Android build using only the official SDK tools, a JDK and Python 3.

SDK packages: platforms;android-35 and build-tools;35.0.0. No Maven dependencies.
Keep .signing/local.p12 to sign future APK updates with the same identity.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True, cwd=ROOT)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME'))
    parser.add_argument('--java', default='java')
    parser.add_argument('--keytool', default='keytool')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/quiet-tv.apk')
    args = parser.parse_args()
    if not args.sdk:
        parser.error('Pass --sdk /path/to/android-sdk, or set ANDROID_HOME')
    sdk = args.sdk.resolve()
    tools = sdk / 'build-tools/35.0.0'
    android = sdk / 'platforms/android-35/android.jar'
    suffix = '.exe' if os.name == 'nt' else ''
    aapt2 = tools / ('aapt2' + suffix)
    zipalign = tools / ('zipalign' + suffix)
    if not android.exists() or not aapt2.exists():
        parser.error('Install Android SDK Platform 35 and Build Tools 35.0.0 first')
    build = ROOT / 'build'
    # These directories contain generated files only; never clear sources or signing keys.
    for name in ('classes', 'generated', 'dex'):
        folder = build / name
        if folder.exists():
            shutil.rmtree(folder)
        folder.mkdir(parents=True)
    run(aapt2, 'compile', '--dir', ROOT / 'app/src/main/res', '-o', build / 'resources.zip')
    run(aapt2, 'link', '-o', build / 'resources.apk', '-I', android,
        '--manifest', ROOT / 'app/src/main/AndroidManifest.xml', '--java', build / 'generated',
        '--auto-add-overlay', build / 'resources.zip')
    sources = sorted((ROOT / 'app/src/main/java').rglob('*.java')) + sorted((build / 'generated').rglob('*.java'))
    bootclasspath = os.pathsep.join(str(path) for path in (tools / 'core-lambda-stubs.jar', android))
    run(args.java, 'com.sun.tools.javac.Main', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
        '-Xlint:-options', '-bootclasspath', bootclasspath, '-d', build / 'classes', *sources)
    with zipfile.ZipFile(build / 'classes.jar', 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted((build / 'classes').rglob('*.class')):
            archive.write(path, path.relative_to(build / 'classes').as_posix())
    run(args.java, '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--release',
        '--min-api', '26', '--lib', android, '--output', build / 'dex', build / 'classes.jar')
    shutil.copyfile(build / 'resources.apk', build / 'unsigned.apk')
    with zipfile.ZipFile(build / 'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted((build / 'dex').glob('*.dex')):
            archive.write(path, path.name)
    run(zipalign, '-f', '4', build / 'unsigned.apk', build / 'aligned.apk')
    signing = ROOT / '.signing'
    signing.mkdir(exist_ok=True, mode=0o700)
    key = signing / 'local.p12'
    if not key.exists():
        run(args.keytool, '-genkeypair', '-keystore', key, '-storetype', 'PKCS12', '-storepass', 'android',
            '-keypass', 'android', '-alias', 'quiet-tv', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
            '-dname', 'CN=Quiet TV Local Build')
        key.chmod(0o600)
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    run(args.java, '-jar', tools / 'lib/apksigner.jar', 'sign', '--ks', key, '--ks-key-alias', 'quiet-tv',
        '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', output, build / 'aligned.apk')
    run(args.java, '-jar', tools / 'lib/apksigner.jar', 'verify', '--verbose', output)
    print(f'APK: {output} ({output.stat().st_size:,} bytes)')


if __name__ == '__main__':
    main()
