#!/usr/bin/env bash
# Screenshots of every tab in every look, from the phone or emulator connected by adb, into build/look-shots/.
# It runs LookScreenshotsTest with `am instrument`, which (unlike connectedAndroidTest) leaves the app and its data
# alone, then removes the test package. Extra arguments go to the test, e.g.: scripts/look-shots.sh -e looks Modern,Eki
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew -q installDebug installDebugAndroidTest
adb shell am instrument -w "$@" -e class com.aniliphy.ui.LookScreenshotsTest \
    com.aniliphy.test/androidx.test.runner.AndroidJUnitRunner
rm -rf build/look-shots
mkdir -p build
adb pull /sdcard/Android/data/com.aniliphy/files/look-shots build/look-shots >/dev/null
adb uninstall com.aniliphy.test >/dev/null || true
# One sheet per look, its tabs side by side, when ffmpeg is there.
if command -v ffmpeg >/dev/null; then
    for look in $(ls build/look-shots | sed 's/_.*//' | sort -u); do
        files=(build/look-shots/"${look}"_*.png)
        inputs=(); filters=""; labels=""
        for i in "${!files[@]}"; do
            inputs+=(-i "${files[$i]}")
            filters+="[$i]scale=360:-1[s$i];"
            labels+="[s$i]"
        done
        ffmpeg -loglevel error -y "${inputs[@]}" -filter_complex "${filters}${labels}hstack=${#files[@]}" "build/look-shots/sheet_${look}.png"
    done
fi
echo "Saved in build/look-shots/"
