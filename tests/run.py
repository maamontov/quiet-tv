#!/usr/bin/env python3
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
destination = root / 'build/tests'
destination.mkdir(parents=True, exist_ok=True)
subprocess.run(['java', 'com.sun.tools.javac.Main', '-d', str(destination),
                str(root / 'app/src/main/java/local/quiet/tv/GridNavigation.java'),
                str(root / 'tests/GridNavigationTest.java')], check=True)
subprocess.run(['java', '-cp', str(destination), 'local.quiet.tv.GridNavigationTest'], check=True)
