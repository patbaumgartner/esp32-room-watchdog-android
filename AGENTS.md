# Room Watchdog — Agent Guide

Android companion for a self-hosted ESP32 room sensor. Start with [README.md](README.md)
for the product, [docs/architecture.md](docs/architecture.md) for the design, and
[CONTRIBUTING.md](CONTRIBUTING.md) for the contributor workflow. This file only covers what
those don't, or what is easy to get wrong.

Two workflows are scripted because skipping them is what has broken this app before:
`/firmware-contract-check` before trusting anything about the device's API, and
`/verify-on-device` before claiming a change works.

This guide describes conventions as they are today. If the manual DI, the absent linter or the
single-ViewModel design ever change, correct this file in the same commit — a stale guide here
misleads rather than merely ages.

## Build and validate

Gradle needs the SDK location from `ANDROID_HOME` or `sdk.dir` in `local.properties`, or it
stops with `SDK location not found`. Builds on JDK 17, same as CI.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
yamllint .
find . -name '*.sh' -not -path './*/build/*' -exec shellcheck {} +
```

That is the full CI gate — run it before claiming a change is done. `scripts/deploy-device.sh`
and `scripts/deploy-emulator.sh` build, install and launch.

## Build traps

- **Do not apply `org.jetbrains.kotlin.android`.** AGP 9 has Kotlin built in and the build
  fails with "no longer required since AGP 9.0". Exactly three plugins are applied; there is
  deliberately no `kotlin { compilerOptions { jvmTarget } }` block either.
- `app/build.gradle.kts` reads `local.properties` (or `WATCHDOG_*` env vars) into five
  debug-only `BuildConfig` fields, escaped by `asBuildConfigLiteral()`. Keep the escaping —
  naive interpolation lets a token break the generated source. Release re-declares all five
  as empty so a release build can never carry a secret.
- The configuration cache is on; a change that reads files at configuration time can break it.

## Architecture

- Manual DI: one [AppContainer](app/src/main/kotlin/com/patbaumgartner/roomwatchdog/di/AppContainer.kt)
  built in `RoomWatchdogApp.onCreate()`, reached via `(application as RoomWatchdogApp).container`.
  No Hilt, no Koin. No Room, no DataStore — `SharedPreferences` plus kotlinx.serialization JSON.
- Three OkHttp clients from one pool: `apiHttpClient` (bounded timeouts), `streamingHttpClient`
  for both WebSockets, and `audioHttpClient` for the PCM stream. The streaming clients drop the
  call timeout because a session may run for hours, so each needs its own liveness bound: the
  sockets ping, the audio client bounds every individual read. Anything else added on top of
  them needs one too, or an unresponsive server hangs it forever.
- State is `MutableStateFlow` + `asStateFlow()`, backing field `_name`. Mutate with
  `update { }`, never `value = value.copy(...)`: the UI thread and background loops write the
  same flows. Repositories that write from several threads are `@Synchronized`.
- One `AppViewModel` and one `HomeState`; navigation is a `sealed interface AppScreen`, not
  Navigation-Compose. Screens live in one file each under `ui/` and take `(state, viewModel)`.

## Keep the core JVM-testable

There are no instrumentation, Robolectric or Compose tests, and that is deliberate — it works
only because the interesting logic has no Android imports. Preserve that split:

- `audio/` DSP (`NoiseFilter`, `Fft`) is pure Kotlin. Only `PcmStreamSession`
  touches `AudioTrack`/`AudioManager`.
- `data/network/`, `data/model/`, `data/device/`, `data/gotify/` are framework-free and depend
  only on OkHttp and kotlinx.serialization.

Putting an `android.*` import into those packages silently makes the behaviour untestable.
Validation runs before any I/O so clients can be tested against a real `OkHttpClient` with no
server.

## Test DSP in the loop the app actually runs

An acoustic echo canceller shipped here and had to be withdrawn. Its unit tests passed at
>12 dB of cancellation, because they fed it a far-end signal that was independent of the
capture. The real session has no such signal: it plays back what it just captured and handed
the canceller *its own output* as the reference, which closed a feedback loop. Measured on a
real 12-second capture, the shipped chain amplified a −40 dBFS room to −2.4 dBFS.

So when changing anything in `audio/`, drive it the way `PcmStreamSession` does — same chunk
sizes, output fed back where the app feeds it back — and measure against a real capture:

```bash
curl -s --max-time 12 -H "Authorization: Bearer $TOKEN" http://<device>:81/audio.pcm -o /tmp/room.pcm
```

Then print level per second for input versus output. A stage that raises the level, or that
moves it more than it claims to, is broken however green the unit tests are.

## Conventions

- User-facing text belongs in `strings.xml`; non-Compose classes take a `Context`.
- No logging at all — no `android.util.Log`, no `println`. Failures become state
  (`HomeState.error`) or are recovered deliberately. See *Observing behaviour* for how to
  inspect a running build without it.
- Alerts are suppressed while `AppVisibility.isAttendingRoom` — the UI is on screen *or* a
  listening session is running. Keep that rule in `AppVisibility`; both the service and the
  notifier consult it, and a second copy of the condition will drift.
- Network clients return `Result<T>` and map failures onto a typed `XException(kind)` with a
  nested `Kind` enum. HTTP status mapping is explicit (`401/403 → Auth`, `409 → Busy`).
- British spelling (`Unauthorised`, `behaviour`), `internal` for module-private API, trailing
  commas, 120-column soft limit, `PascalCase` enum entries.
- KDoc only where the rationale is not obvious; comments explain *why*. No file headers, no
  restating the next line. There is no ktlint or detekt — don't reorder existing imports as a
  side effect.
- Tests are JUnit 4 with backtick sentence names and no mocking library; assertions carry a
  message first (`assertTrue("expected >20 dB, got $after", …)`).
- Conventional Commit subjects.

## Security invariants

- `data/network/EndpointPolicy` is the single trust boundary: Gotify must be HTTPS, device
  cleartext only on hosts that parse as loopback/link-local/private addresses or that are
  named in a local zone. Route every new URL through it, and classify IP literals as addresses
  rather than by their spelling — `feature.example.com` once passed as `fe80::/10`.
- Anything the device or the Gotify server sends is untrusted input: bound what is buffered
  from a response, and range-check values such as the announced audio port before use.
- Credentials go in headers, never in URLs or query strings, and never into logs or state.
- Tokens are stored through `SecretStore` (Keystore AES/GCM), never plain preferences.
- Never commit `local.properties`, tokens, APKs or recordings.
- `lintDebug` reports two warnings on purpose (LAN cleartext, adaptive icons). Don't suppress
  them globally to get a clean run.

## The device contract changes — verify it

The firmware in [esp32-room-watchdog](https://github.com/patbaumgartner/esp32-room-watchdog)
has moved `/audio.pcm` between ports more than once, and the deployed firmware is usually older
than that repo's docs. Run `/firmware-contract-check` before changing anything that talks to the
device — it probes the running hardware and diffs it against what the app assumes.

Constraints that shape the code: the device accepts one audio client and one telemetry client,
the `/ws` hello frame announces the audio port (follow it rather than hardcoding), and firmware
that still serves audio from the API port drops the stream if `/status` is polled during a
session.

## Verify on hardware

Compiling is not evidence. Run `/verify-on-device` for audio, notification and lifecycle
changes, and cite what was observed — a measurement, a `dumpsys` reading, a screenshot.

## Observing behaviour without logging

Since the app logs nothing, `dumpsys` is the instrument:

```bash
adb shell dumpsys notification --noredact | grep -A20 pkg=com.patbaumgartner.roomwatchdog
adb shell dumpsys activity activities | grep topResumedActivity
adb shell dumpsys power | grep mWakefulness
adb shell am start -W -n com.patbaumgartner.roomwatchdog/.ui.MainActivity   # cold-start timing
```

The foreground notification's title is the Gotify connection state, so `Watchdog connected`
is proof the socket handshake succeeded. Alert notifications carry `channel=watchdog_alerts`
and a `when=` epoch, which dates them against `date +%s000`.

Three traps that invalidate a test or block one outright:

- **A locked or dozing phone stops the activity**, so "the app is open" is not true while the
  screen is off. Confirm `mWakefulness=Awake` and a `topResumedActivity` before concluding
  anything about foreground behaviour — otherwise an alert firing is correct, not a bug.
- **`adb install` hangs on the lock screen.** `adb push` still works; the install needs the
  device unlocked.
- **A listening session cannot be started from a shell.** The notification actions carry a
  random token that `AlertNotifier.autoStartFrom` verifies, so a forged `am start` is refused
  by design. Tap the notification action on the device instead.
