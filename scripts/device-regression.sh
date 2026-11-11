#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

save_failure_diagnostics() {
  local result=$?
  if (( result != 0 )); then
    mkdir -p build/device-diagnostics
    python3 - <<'PY' | tee build/device-diagnostics/test-failures.txt || true
from pathlib import Path
import xml.etree.ElementTree as ET

for report in sorted(Path('app/build/outputs/androidTest-results').rglob('*.xml')):
    try:
        suite = ET.parse(report).getroot()
    except (ET.ParseError, OSError) as error:
        print(f'Could not read {report}: {error}')
        continue
    for case in suite.iter('testcase'):
        for failure in case:
            if failure.tag in ('failure', 'error'):
                print(f"\nFAILED {case.get('classname', '')}#{case.get('name', '')} ({report})")
                print(failure.text or failure.get('message', 'No failure details'))
PY
    adb shell dumpsys jobscheduler > build/device-diagnostics/jobscheduler.txt 2>&1 || true
    adb shell dumpsys connectivity > build/device-diagnostics/connectivity.txt 2>&1 || true
    adb logcat -d -t 1500 > build/device-diagnostics/logcat.txt 2>&1 || true
  fi
  exit "$result"
}
trap save_failure_diagnostics EXIT

# Read the actual baseline APK metadata so fixtures remain upgrades after version bumps.
bash ./gradlew --no-daemon :app:assembleDebug
fixture_metadata=$(python3 - <<'PY'
import json
import re

with open('app/build/outputs/apk/debug/output-metadata.json', encoding='utf-8') as source:
    metadata = json.load(source)['elements'][0]
version = metadata['versionName']
if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version):
    raise SystemExit(f'Expected a semantic baseline version, got {version!r}')
major, minor, patch = map(int, version.split('.'))
code = int(metadata['versionCode']) + 1
if not (0 < code <= 2_100_000_000 and max(major, minor, patch + 1) <= 99_999):
    raise SystemExit('Cannot generate a newer Android update fixture within supported version limits')
print(f'{major}.{minor}.{patch + 1} {code}')
PY
)
read -r fixture_version fixture_code <<< "$fixture_metadata"

# Build same-key and different-key upgrades before returning to the baseline version.
bash ./gradlew --no-daemon :app:assembleDebug "-PnativeVersionName=$fixture_version" "-PnativeVersionCode=$fixture_code"
mkdir -p build/update-fixtures
cp app/build/outputs/apk/debug/app-debug.apk build/update-fixtures/updated.apk
rm -f build/update-fixtures/wrong-signer.jks
keytool -genkeypair -keystore build/update-fixtures/wrong-signer.jks -storepass fixturepass -keypass fixturepass -alias fixture -keyalg RSA -validity 2 -dname 'CN=Migration Fixture' -noprompt
"$ANDROID_HOME/build-tools/36.0.0/apksigner" sign --ks build/update-fixtures/wrong-signer.jks --ks-pass pass:fixturepass --key-pass pass:fixturepass --out build/update-fixtures/wrong-signer.apk build/update-fixtures/updated.apk
adb push build/update-fixtures/updated.apk /data/local/tmp/lx-native-update-fixture.apk
adb push build/update-fixtures/wrong-signer.apk /data/local/tmp/lx-native-wrong-signer.apk
# Exercise first-use permission dialogs before download tests grant legacy storage access.
bash ./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=cn.toside.music.nativeapp.ui.PermissionFlowTest
mkdir -p build/permission-flow/reports build/permission-flow/results
cp -a app/build/reports/androidTests/. build/permission-flow/reports/
cp -a app/build/outputs/androidTest-results/. build/permission-flow/results/
# Grant legacy storage access at installation, before instrumentation starts.
# Keep the first-use permission pass above on the default, ungranted install.
regression_options=()
device_api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if (( device_api <= 28 )); then
  regression_options+=(-PnativeTestGrantPermissions=true)
fi
bash ./gradlew --no-daemon :app:connectedDebugAndroidTest "${regression_options[@]}" \
  -Pandroid.testInstrumentationRunnerArguments.notClass=cn.toside.music.nativeapp.ui.PermissionFlowTest \
  -Pandroid.testInstrumentationRunnerArguments.runDownloadJobs=true \
  -Pandroid.testInstrumentationRunnerArguments.updateFixture=/data/local/tmp/lx-native-update-fixture.apk \
  -Pandroid.testInstrumentationRunnerArguments.wrongSignerFixture=/data/local/tmp/lx-native-wrong-signer.apk
