# Room Watchdog for Android

Room Watchdog is a privacy-first Android companion for an ESP32 room sensor.
It shows live presence, plays and records audio only on demand, and receives
presence and sound alerts from your self-hosted Gotify server.

[![CI](https://github.com/patbaumgartner/esp32-room-watchdog-android/actions/workflows/ci.yml/badge.svg)](https://github.com/patbaumgartner/esp32-room-watchdog-android/actions/workflows/ci.yml)
[![Security](https://github.com/patbaumgartner/esp32-room-watchdog-android/actions/workflows/security.yml/badge.svg)](https://github.com/patbaumgartner/esp32-room-watchdog-android/actions/workflows/security.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

The companion firmware lives in
[esp32-room-watchdog](https://github.com/patbaumgartner/esp32-room-watchdog).

## Features

- Live room presence, distance, microphone level, and device connectivity
- On-demand PCM audio listening with independent mute
- Local AAC/M4A recording with playback, sharing, renaming, and deletion
- Background notifications for presence and sound events, suppressed while the
  app is visible
- In-app presence and recent-noise indicators
- Notification actions that start listening or recording after an explicit tap
- System-aware light and dark themes
- Keystore-backed encryption for the ESP32 API token and Gotify client token

## Requirements

- Android 8.0 (API 26) or newer
- JDK 17
- Android SDK 37 with the matching build tools
- An ESP32 running the companion firmware on a local network or VPN
- A Gotify server reachable over HTTPS and a Gotify **client** token (`gtfyc...`)

The first build downloads the Gradle distribution and Android dependencies. It
can use several gigabytes of disk space in Gradle and Android SDK caches.

## Build and run

```bash
git clone https://github.com/patbaumgartner/esp32-room-watchdog-android.git
cd esp32-room-watchdog-android
./gradlew :app:assembleDebug
```

Install on the only attached physical Android device:

```bash
scripts/deploy-device.sh
```

Or install on an Android Virtual Device named `myAndroidEmulatorDevice`:

```bash
scripts/deploy-emulator.sh
```

Override the emulator name with `AVD_NAME`; override the physical device with
`DEVICE_SERIAL`. Both scripts respect `ANDROID_HOME` and `JAVA_HOME` and
otherwise use the tools already available in the environment.

On first launch, enter the room name, ESP32 address and API key, Gotify HTTPS
address, and Gotify client token. Android 13 and newer will then ask for
notification permission.

## Development defaults

Debug builds can prefill onboarding fields without committing credentials. Copy
the example and edit the ignored file:

```bash
cp local.properties.example local.properties
```

The same values can be supplied as environment variables:

| `local.properties` | Environment variable |
| --- | --- |
| `watchdog.roomName` | `WATCHDOG_ROOM_NAME` |
| `watchdog.deviceUrl` | `WATCHDOG_DEVICE_URL` |
| `watchdog.apiToken` | `WATCHDOG_API_TOKEN` |
| `watchdog.gotifyUrl` | `WATCHDOG_GOTIFY_URL` |
| `watchdog.gotifyClientToken` | `WATCHDOG_GOTIFY_CLIENT_TOKEN` |

These values are compiled into the debug APK. Use development credentials only,
never production secrets. Release builds always leave the fields empty.

## Network and privacy model

- Gotify must use HTTPS. The client token is rejected before any connection when
  the URL is not encrypted.
- The ESP32 can use HTTPS anywhere. Plain HTTP is limited to loopback,
  private/link-local IP ranges, and local hostnames such as `.local`, `.lan`,
  and `.home.arpa`.
- ESP32 HTTPS uses Android's system certificate authorities and standard
  hostname verification. Self-signed, expired, or hostname-mismatched
  certificates are rejected.
- Tokens are encrypted with an Android Keystore AES-GCM key before storage.
- Recordings remain in app-private storage. They are shared through a temporary
  read-only content URI only when the user chooses Share or Play.
- Backups and device-to-device transfer are disabled for credentials, events,
  and recordings.
- The app has no analytics, advertising SDK, or cloud service dependency beyond
  the Gotify server configured by the user.

## Validation

Run the same primary gates used by CI:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
shellcheck scripts/*.sh
```

The release APK produced by a source checkout is unsigned. Signing and
distribution remain the responsibility of the person building the app.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the contributor workflow and
[docs/architecture.md](docs/architecture.md) for the design.

## License

Room Watchdog is licensed under the [Apache License 2.0](LICENSE). The bundled
Manrope fonts are licensed under the SIL Open Font License 1.1; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
