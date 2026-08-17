# Architecture

Room Watchdog is a single-module Android application. The module is intentionally
small; dependencies flow from UI and services toward data and platform adapters.

## Runtime components

- `ui/` contains the Compose screens and `AppViewModel`. It owns screen state and
  user actions but not network or file implementations.
- `service/AlertConnectionService` owns the long-lived Gotify WebSocket,
  reconnect backoff, missed-message synchronization, and background routing.
- `data/device/` handles authenticated ESP32 status, calibration, and audio
  requests.
- `data/gotify/` handles Gotify REST calls, WebSocket decoding, and conversion to
  domain events.
- `data/network/EndpointPolicy` is the shared trust boundary for configured URLs.
  Gotify requires HTTPS; device cleartext is restricted to local/private hosts.
- `audio/PcmStreamSession` owns the ESP32 audio connection and fans PCM frames
  out to playback, metering, and optional recording.
- `recordings/` encodes AAC/M4A into app-private storage and persists small
  recording metadata.
- `data/settings/` stores ordinary configuration in private preferences and
  encrypts tokens with Android Keystore AES-GCM.
- `notifications/AlertNotifier` owns channels, event notifications, and
  authenticated notification action intents.

`AppContainer` constructs the process-wide object graph. REST clients have finite
call timeouts; only the live PCM and WebSocket clients have unbounded read
timeouts, with explicit cancellation and WebSocket pings controlling their
lifetimes.

## Event flow

```text
ESP32 -> Gotify -> AlertConnectionService -> WatchdogEvent parser
                                      |-> EventStore
                                      `-> AlertNotifier -> notification action
                                                               `-> AppViewModel

ESP32 /audio.pcm -> PcmStreamSession -> AudioTrack
                                    |-> level meter
                                    `-> M4aRecorder -> RecordingStore
```

Unknown Gotify messages are ignored after advancing the synchronization cursor.
This prevents unrelated applications on the same Gotify account from appearing
as room-presence alerts.

## Storage and lifecycle

- Settings, recent events, and recording metadata use app-private
  `SharedPreferences`.
- API tokens use a non-exportable Android Keystore key.
- M4A files use app-private files and temporary `.tmp` files until a valid
  end-of-stream is muxed.
- File sharing uses `FileProvider` and per-intent read grants; raw paths are
  never exposed.
- The foreground service is the only owner of the Gotify connection.
  `PcmStreamSession` is process-scoped and supports one device audio consumer,
  matching the firmware constraint.

## Tests

JVM unit tests cover endpoint trust rules and Gotify event parsing. Android lint
validates resources, manifest declarations, API levels, and Compose usage. Both
debug and minified release variants are assembled in CI to exercise resource
shrinking and R8.
