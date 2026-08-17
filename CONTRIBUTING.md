# Contributing

Thanks for improving Room Watchdog. Small, focused changes with behavioural
tests are easiest to review.

## Set up a development environment

Install JDK 17 and Android SDK 37, then clone the repository. Android Studio can
import the root directory directly; command-line builds use the checked-in
Gradle wrapper.

```bash
git clone https://github.com/patbaumgartner/esp32-room-watchdog-android.git
cd esp32-room-watchdog-android
./gradlew :app:assembleDebug
```

Real devices and credentials are not required for unit tests or compilation. For
manual device testing, copy `local.properties.example` to the ignored
`local.properties` and use development credentials only.

## Make a change

1. Create a branch from `main`.
2. Keep the change scoped to one concern.
3. Add or update tests for changed behaviour.
4. Keep user-facing text in `app/src/main/res/values/strings.xml`.
5. Never commit `local.properties`, tokens, APKs, keystores, or generated build
   output.

Use Conventional Commit subjects, for example
`fix: reject insecure Gotify endpoints` or `docs: clarify device setup`.

## Validate

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
shellcheck scripts/*.sh
git diff --check
```

Android lint intentionally reports two non-failing warnings: the app permits
constrained LAN cleartext for the ESP32, and adaptive launcher icons must remain
in `mipmap-anydpi-v26` for Android resource packaging. Do not suppress either
warning globally.

## Pull requests

Explain the user-visible effect, why the change is needed, and exactly how it was
tested. Include screenshots for visual changes when they make review materially
easier. Do not include credentials or recordings in screenshots, logs, fixtures,
or issue reports.

Security vulnerabilities belong in a private report as described in
[SECURITY.md](SECURITY.md), not a public issue.
