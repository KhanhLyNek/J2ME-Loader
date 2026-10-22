#!/usr/bin/env bash
set -euo pipefail

APK="$(find app/build/outputs/apk/dev/debug -name '*.apk' -print -quit)"
test -n "$APK" && test -f "$APK"
PKG="ru.playsoftware.j2meloader.debug"

adb install -r "$APK"
# Special app access cannot be granted using pm grant.
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || true
mkdir -p ui-smoke

for MODE in yes no; do
    adb shell cmd uimode night "$MODE"
    adb shell am force-stop "$PKG"
    adb logcat -c
    adb shell am start -W -n "$PKG/ru.playsoftware.j2meloader.MainActivity"
    sleep 12

    if [ -z "$(adb shell pidof "$PKG" | tr -d '\r')" ]; then
        echo "App process died on startup in night mode=$MODE"
        adb logcat -d -v brief | tail -n 180
        exit 1
    fi
    if adb logcat -d -v threadtime | grep -A 15 'FATAL EXCEPTION' | grep -q "Process: $PKG"; then
        echo "FATAL EXCEPTION during launch, night mode=$MODE"
        adb logcat -d -v brief | tail -n 180
        exit 1
    fi

    echo "Launcher alive: night mode=$MODE"
    adb shell screencap -p "/sdcard/ui_home_$MODE.png"
    adb pull "/sdcard/ui_home_$MODE.png" "ui-smoke/ui_home_$MODE.png"
done
