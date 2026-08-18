---
name: firmware-contract-check
description: "Check this app against the ESP32 firmware it talks to. Use when changing DeviceClient, TelemetryStream, TelemetryFrame, PcmStreamSession or EndpointPolicy, when audio or telemetry stops working, when the firmware repo has been updated, or before assuming which port serves /audio.pcm. Probes the live device, reads the firmware repo docs, and reports where the app disagrees with reality."
argument-hint: 'optional: what changed, e.g. "audio port" or "telemetry fields"'
---

# Firmware Contract Check

Current firmware serves `/status`, `/calibrate` and `/ws` on port **80**, and `/audio.pcm` on
port **81** only — port 80 answers 404 for it. That split is deliberate: audio needs a
synchronous `WebServer`, whose header clashes with the async server's, so it lives in its own
translation unit. Treat 81 as the contract.

The port has still moved twice, and the flashed firmware is often older than the repo's docs, so
three sources have to agree before a change is safe:

1. **The firmware repo** — what the maintainer intends
2. **The running device** — what is actually deployed
3. **This app** — what the code assumes

Never trust one alone. The docs said port 80 while the device served 81; the app was changed to
match the docs and audio broke.

## Procedure

### 1. Probe the running device

```bash
bash .github/skills/firmware-contract-check/scripts/probe.sh
```

[probe.sh](./scripts/probe.sh) reads `watchdog.deviceUrl` / `watchdog.apiToken` from
`local.properties` (override with `DEVICE_URL` / `API_TOKEN`), prints the live endpoint
behaviour next to what the app hardcodes, and never echoes the token.

Reading the results:

| Result                    | Meaning                                                             |
| ------------------------- | ------------------------------------------------------------------- |
| `/ws -> 101`              | Socket upgraded; telemetry is available                             |
| `/ws -> 400`              | Endpoint exists, handshake refused — auth passed                    |
| `/ws -> 404`              | Old firmware without the telemetry socket                           |
| `/ws -> 401`              | Token rejected — check `watchdog.apiToken`, not the code            |
| `:81/audio.pcm` streaming | Expected: audio on its own port                                     |
| `:80/audio.pcm` streaming | Pre-revert firmware — the app cannot stream from it; reflash        |
| neither streaming         | Another client holds the single audio slot, or the app is listening |

A `:80` hit is not an alternative layout to support. Audio lived there twice, and the app cannot
use either: the first version answered `POST` only, and the single-port version announced
`audioPath` instead of `audioPort`, so the app falls back to 81 and finds nothing. The fix is to
update the device, not to teach the app a second layout.

Stop any listening session first — the device accepts **one** audio client.

### 2. Read the firmware repo

The sibling checkout is `../esp32-room-watchdog`. Read its `README.md` (HTTP API and Live
telemetry sections) and `docs/architecture.md`, then confirm against the source that generates
the frames rather than the prose:

```bash
grep -n 'sendHello\|telemetryJson' ../esp32-room-watchdog/src/ws.cpp
grep -n 'audio.pcm\|AsyncWebServer' ../esp32-room-watchdog/src/api.cpp
git -C ../esp32-room-watchdog --no-pager log --oneline -8
```

`api.cpp` prints its own layout at boot — the `Serial.printf("api: port %u ...")` line names
which port serves what, which is the fastest answer to "where does audio live now".

A recent commit touching ports or the socket is the usual reason the app and device disagree.

### 3. Compare and decide

Check each of these:

- **Audio port and path** — `DeviceClient.DEFAULT_AUDIO_PORT`, `deviceAudioUrl()` in
  `EndpointPolicy`, and the `hello` frame's `audioPort`. The socket announces the port; follow it
  rather than hardcoding, and keep the constant as the fallback for a device that never sends one.
  A port outside 1-65535 is treated as not sent at all.
- **Hello frame fields** — `TelemetryFrame.Hello` against `sendHello()` in `ws.cpp`.
- **Telemetry and status fields** — `DeviceStatus` against `telemetryJson()` in `ws.cpp` and the
  `/status` handler in `api.cpp`.
- **Event kinds** — `TelemetryFrame.Event.Kind` against the firmware's event strings.

`TelemetryFrameTest` pins the frames as the firmware sends them today, so a **renamed** field
fails a test. `ignoreUnknownKeys = true` still means a field the firmware **adds** is dropped in
silence — only this comparison catches that one.

### 4. When the device is older than the docs

Target the documented contract and keep the app working against what is deployed:

- Prefer values announced at runtime (the `hello` frame) over constants.
- Where a fallback is needed, make it explicit and comment _why_, as
  `AppViewModel.startStatusPolling` does for firmware that shares one port.
- Say plainly in the report which firmware version was verified against.

### 5. Prove it end to end

A contract change is not done until audio has actually played:

```bash
/verify-on-device
```

Then re-run the probe to confirm `audioStreaming` returns to `false` after the session stops.
