#!/usr/bin/env bash
set +e
gradle :app:connectedDebugAndroidTest --console=plain > device-test.log 2>&1
result=$?
cat device-test.log
if [ "$result" -ne 0 ]; then
  python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
logs = []
for file in Path('app/build/outputs/androidTest-results').rglob('*.xml'):
    try:
        root = ET.parse(file).getroot()
        for failure in root.iter('failure'):
            logs.append((failure.get('message', '') + ' ' + (failure.text or ''))[:1500])
    except ET.ParseError:
        pass
if not logs:
    logs = [Path('device-test.log').read_text(errors='replace')[-4000:]]
for message in logs[:5]:
    print('::error::' + message.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'))
PY
fi
exit "$result"
