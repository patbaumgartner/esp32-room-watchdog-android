#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-${HOME}/Android/Sdk}"
AVD_NAME="${AVD_NAME:-myAndroidEmulatorDevice}"
PACKAGE_NAME="com.patbaumgartner.roomwatchdog"
ACTIVITY_NAME=".ui.MainActivity"
EMULATOR="${ANDROID_HOME}/emulator/emulator"
ADB="${ANDROID_HOME}/platform-tools/adb"
LOG_FILE="${TMPDIR:-/tmp}/room-watchdog-${AVD_NAME}.log"

if [[ ! -x "$EMULATOR" || ! -x "$ADB" ]]; then
    echo "Android SDK tools not found under: $ANDROID_HOME" >&2
    exit 1
fi

cd "$ROOT_DIR"

if ! "$ADB" devices | awk 'NR > 1 && $1 ~ /^emulator-/ && $2 == "device" { found = 1 } END { exit !found }'; then
    echo "Starting emulator: $AVD_NAME"
    nohup "$EMULATOR" -avd "$AVD_NAME" -no-snapshot -no-boot-anim >"$LOG_FILE" 2>&1 &
fi

echo "Waiting for an Android device..."
"$ADB" wait-for-device

boot_completed=""
for _ in $(seq 1 180); do
    boot_completed=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    [[ "$boot_completed" == "1" ]] && break
    sleep 1
done

if [[ "$boot_completed" != "1" ]]; then
    echo "Android did not finish booting. Emulator log: $LOG_FILE" >&2
    exit 1
fi

echo "Building and installing Room Watchdog..."
./gradlew :app:installDebug --no-daemon

echo "Launching Room Watchdog..."
"$ADB" shell am start -n "${PACKAGE_NAME}/${ACTIVITY_NAME}"
echo "Room Watchdog is running on $AVD_NAME"
