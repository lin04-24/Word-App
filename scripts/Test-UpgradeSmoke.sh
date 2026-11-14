#!/usr/bin/env bash
set -euo pipefail
mkdir -p .test-tools
runner=com.example.moci.test/androidx.test.runner.AndroidJUnitRunner
adb shell appops set com.example.moci REQUEST_INSTALL_PACKAGES allow
adb shell am instrument -w -e upgradeSmoke true -e class 'com.example.moci.UpdateUpgradeSmokeTest#prepareSystemInstaller' "$runner" | tee .test-tools/upgrade-prepare.log
if ! rg -q 'OK \(1 test\)' .test-tools/upgrade-prepare.log; then
  echo 'Preparing the system installer failed.' >&2
  exit 1
fi
clicked=false
for attempt in $(seq 1 20); do
  adb shell uiautomator dump /sdcard/update-installer.xml >/dev/null
  adb pull /sdcard/update-installer.xml .test-tools/update-installer.xml >/dev/null
  point=$(python3 - <<'PY'
import re
import xml.etree.ElementTree as ET
for node in ET.parse('.test-tools/update-installer.xml').iter('node'):
    if node.get('text', '').lower() in ('update', 'install') and node.get('enabled') == 'true':
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds', '')))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  if [[ -n "$point" ]]; then
    read -r x y <<< "$point"
    adb shell input tap "$x" "$y"
    clicked=true
    break
  fi
  sleep 1
done
if [[ "$clicked" != true ]]; then echo 'System install confirmation was not found.' >&2; exit 1; fi
installed=false
for attempt in $(seq 1 60); do
  if adb shell dumpsys package com.example.moci | rg -q 'versionCode=5\b'; then installed=true; break; fi
  sleep 1
done
if [[ "$installed" != true ]]; then echo 'System installation did not complete.' >&2; exit 1; fi
echo 'Android system installer successfully upgraded the signed fixture to versionCode 5.'
# Restore the real debug application so instrumentation can inspect the retained Room database.
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
adb shell am instrument -w -e upgradeSmoke true -e class 'com.example.moci.UpdateUpgradeSmokeTest#verifyUpgradeData' "$runner" | tee .test-tools/upgrade-verify.log
if ! rg -q 'OK \(1 test\)' .test-tools/upgrade-verify.log; then
  echo 'Vocabulary or review history did not survive installation.' >&2
  exit 1
fi
adb shell appops set com.example.moci REQUEST_INSTALL_PACKAGES default
echo 'Vocabulary, review level, answer history and update preferences survived the system upgrade.'
