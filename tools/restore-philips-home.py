#!/usr/bin/env python3
"""Restore the stock Philips Android TV home screen; keep Quiet TV installed."""
import argparse
import shlex
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='adb')
parser.add_argument('--serial', required=True)
args = parser.parse_args()
adb = [args.adb, '-s', args.serial]


def shell(*command):
    result = subprocess.run(adb + ['shell', shlex.join(command)], text=True, capture_output=True, check=True)
    print(result.stdout.strip(), flush=True)
    return result.stdout


shell('pm', 'default-state', '--user', '0', 'com.google.android.tvlauncher')
shell('cmd', 'package', 'set-home-activity', '--user', '0', 'com.google.android.tvlauncher/.MainActivity')
shell('am', 'start', '-W', '-n', 'com.google.android.tvlauncher/.MainActivity')
shell('cmd', 'notification', 'disallow_listener', 'local.quiet.tv/.NotificationFeed', '0')
print('Waiting for Android to save package settings…', flush=True)
time.sleep(40)
shell('sync')
print('Stock launcher restored. Quiet TV is still installed; its notification access has been revoked.', flush=True)
