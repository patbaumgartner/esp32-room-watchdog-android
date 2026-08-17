#!/usr/bin/env bash
#
# Builds Room Watchdog and installs it on a physical Android device over USB (or adb over Wi-Fi).
#
#   scripts/deploy-device.sh                 # uses the only attached physical device
#   DEVICE_SERIAL=RF8N123ABCD scripts/deploy-device.sh
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-${HOME}/Android/Sdk}"
PACKAGE_NAME="com.patbaumgartner.roomwatchdog"
ACTIVITY_NAME=".ui.MainActivity"
ADB="${ANDROID_HOME}/platform-tools/adb"

if [[ $# -ne 0 ]]; then
    echo "Usage: scripts/deploy-device.sh" >&2
    exit 2
fi

if [[ ! -x "$ADB" ]]; then
    echo "adb not found under: $ANDROID_HOME" >&2
    exit 1
fi

cd "$ROOT_DIR"

"$ADB" start-server >/dev/null

# Physical devices are everything adb reports that is not an emulator instance.
devices=()
while IFS= read -r device; do
    devices+=("$device")
done < <("$ADB" devices | awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ { print $1 }')

if [[ -n "${DEVICE_SERIAL:-}" ]]; then
    serial="$DEVICE_SERIAL"
elif [[ ${#devices[@]} -eq 1 ]]; then
    serial="${devices[0]}"
elif [[ ${#devices[@]} -eq 0 ]]; then
    echo "No physical device found." >&2
    echo "Plug the phone in, enable USB debugging, and accept the pairing prompt." >&2
    "$ADB" devices -l >&2
    exit 1
else
    echo "More than one device is attached. Pick one with DEVICE_SERIAL=<serial>:" >&2
    printf '  %s\n' "${devices[@]}" >&2
    exit 1
fi

if [[ "$("$ADB" -s "$serial" get-state 2>/dev/null | tr -d '\r')" != "device" ]]; then
    echo "Device $serial is not ready (unauthorised or offline)." >&2
    exit 1
fi

model=$("$ADB" -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
release=$("$ADB" -s "$serial" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
echo "Target: ${model:-unknown} (Android ${release:-?}, $serial)"

# Both Gradle and adb pick the target up from ANDROID_SERIAL.
export ANDROID_SERIAL="$serial"

echo "Building and installing Room Watchdog..."
./gradlew :app:installDebug --no-daemon

echo "Launching Room Watchdog..."
"$ADB" -s "$serial" shell am start -n "${PACKAGE_NAME}/${ACTIVITY_NAME}" >/dev/null

echo "Room Watchdog is running on ${model:-$serial}"
echo "Logs: $ADB -s $serial logcat --pid=\$($ADB -s $serial shell pidof -s $PACKAGE_NAME)"
