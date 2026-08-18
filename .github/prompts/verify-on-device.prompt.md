---
description: "Build, install and prove the app works on a connected Android device: asserts the Gotify socket connected and captures a screenshot as evidence."
name: "Verify on device"
argument-hint: "optional: what to check, e.g. 'listening survives a minute'"
agent: agent
---

Build the debug APK, install it on the connected device, and report what you **observed** — not
what you expect. Compiling is not evidence.

## 1. Build and install

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :app:assembleDebug
scripts/deploy-device.sh
```

If `$ANDROID_HOME/platform-tools/adb devices` lists nothing, check whether `adb.exe devices` does
— under WSL the device is attached to the Windows adb server, and `deploy-device.sh` cannot see
it. In that case install through the Windows binary, staging the APK on a path Windows can read:

```bash
cp app/build/outputs/apk/debug/app-debug.apk /mnt/c/temp/rw.apk
adb.exe install -r 'C:\temp\rw.apk'
adb.exe shell am start -n com.patbaumgartner.roomwatchdog/.ui.MainActivity
```

**If the install hangs, the phone is locked.** Confirm with
`adb shell dumpsys power | grep mWakefulness` and ask the user to unlock it — `adb push` works on
a locked device but `pm install` does not, and no amount of retrying changes that.

## 2. Assert, do not assume

The app writes no logs, so read its state through `dumpsys`:

```bash
adb shell dumpsys notification --noredact | grep -A20 pkg=com.patbaumgartner.roomwatchdog
adb shell dumpsys activity activities | grep topResumedActivity
adb shell dumpsys power | grep mWakefulness
```

Required evidence:

- **Process alive** — `adb shell pidof com.patbaumgartner.roomwatchdog` returns a pid
- **Gotify connected** — the ongoing notification's title is `Watchdog connected`, which is set
  only in `GotifyStream.onOpen`. `Reconnecting…` or `Sign in again…` means the handshake failed.
- **No crash** — `adb logcat -d -b crash | grep roomwatchdog` is empty
- **Live room data** — the screenshot shows a state and distance, not `Not connected`

## 3. Screenshot

```bash
adb shell screencap -p /sdcard/v.png && adb pull /sdcard/v.png /tmp/verify.png && adb shell rm /sdcard/v.png
```

View it. A screen that renders is the only proof a UI change did not break layout.

## 4. Exercising audio

Tapping Listen from a shell needs the screen awake and unlocked — `input tap` silently does
nothing behind the keyguard, so screenshot first and aim from what you see. A listening session
cannot be started with `am start`: the notification actions carry a random token that
`AlertNotifier.autoStartFrom` verifies, and a forged intent is refused by design.

Confirm a session from the device side instead:

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://<device>/status | grep -o '"audioStreaming":[a-z]*'
```

Sample it on both sides of whatever you are testing — a session that ended halfway invalidates
the conclusion.

## 5. Report

State each check and the observation that backs it. If something could not be verified — locked
phone, no device, session would not start — say so explicitly rather than implying it passed.
