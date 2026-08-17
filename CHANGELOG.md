# Changelog

All notable changes are documented here. The project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

No public release has been published yet.

### Added

- Live telemetry over the device's `/ws` socket, with the REST poll kept as a fallback
- Self-tuning noise reduction that learns the room in the first seconds of a session
- Acoustic echo cancellation, so phone and device can sit side by side
- Recording playback, sharing, renaming, and deletion
- Mute and compact live-session controls
- In-app presence and recent-noise indicators
- System-aware light and dark themes
- CI, CodeQL, dependency review, and Dependabot automation

### Changed

- Audio now streams from the device's dedicated audio port, announced by the socket
- Presence status now uses the blue brand colour
- Gotify setup now validates HTTPS and the client token before saving
- REST and live-stream clients now use separate timeout policies
- ESP32 HTTPS uses Android's system trust store and hostname verification

### Fixed

- Corrected the Android application ID and Kotlin namespace to
  `com.patbaumgartner.roomwatchdog`
- Stopping listening now finalizes an active recording
- Notification actions can no longer be spoofed by another installed app
- Unknown Gotify messages no longer become presence alerts
- Recorder shutdown is bounded and cleans incomplete output
- Presence and sound event notifications are suppressed while the app is visible,
  including when a second activity instance is started from a notification
- A listening session no longer stops after about a minute on a short network read
- Android 17 now requests local-network access before connecting to the ESP32
- Gotify background streaming no longer fails before opening its WebSocket
