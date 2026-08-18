# Architecture

Room Watchdog is a single-module Android application. The module is intentionally
small; dependencies flow from UI and services toward data and platform adapters.

## Runtime components

- `ui/` contains the Compose screens and `AppViewModel`. It owns screen state and
  user actions but not network or file implementations.
- `service/AlertConnectionService` owns the long-lived Gotify WebSocket,
  reconnect backoff, missed-message synchronization, and background routing.
- `data/device/` handles authenticated ESP32 status, calibration, and audio
  requests, plus the `/ws` telemetry socket the firmware pushes sensor frames on.
- `data/gotify/` handles Gotify REST calls, WebSocket decoding, and conversion to
  domain events.
- `data/network/EndpointPolicy` is the shared trust boundary for configured URLs.
  Gotify requires HTTPS; device cleartext is restricted to local/private hosts.
- `audio/PcmStreamSession` owns the ESP32 audio connection and fans PCM frames
  out to playback, metering, and optional recording.
- `audio/NoiseFilter` cleans the captured frames (DC block, learnt spectral noise
  profile, low-pass) and follows the filter switch shown while listening.
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

ESP32 /ws -> TelemetryStream -> AppViewModel (live room state, sound events)

ESP32 /audio.pcm -> PcmStreamSession -> NoiseFilter -> AudioTrack
                                                    |-> level meter
                                                    `-> M4aRecorder -> RecordingStore
```

Everything the device answers on its API port - `/status`, `/calibrate` and the
`/ws` telemetry socket - is one authenticated async server; `/audio.pcm` has a
second, synchronous server on its own port because an async chunked response
cannot sustain 48 kHz. The socket's hello frame announces that port. Telemetry
is the live source while the socket is up; the REST poll is only a fallback, and
it pauses during a listening session so firmware that still shares one port
cannot have its stream cut short.

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
  matching the firmware constraint. The telemetry socket is dropped whenever the
  app leaves the foreground, since the device keeps only one such client.

## Tests

JVM unit tests cover endpoint trust rules, Gotify event parsing, and the audio
chain (noise learning, suppression state). Android lint validates resources,
manifest declarations, API levels, and Compose usage. Both debug and minified
release variants are assembled in CI to exercise resource shrinking and R8.
