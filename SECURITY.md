# Security Policy

## Supported versions

Room Watchdog is pre-1.0 software. Security fixes are applied to the latest
commit on `main`; older commits and locally modified APKs are not supported.

## Report a vulnerability

Please use GitHub's private vulnerability reporting flow at:

<https://github.com/patbaumgartner/esp32-room-watchdog-android/security/advisories/new>

Include the affected version or commit, Android version, impact, reproduction
steps, and any proposed mitigation. Do not include real API keys, Gotify tokens,
private recordings, or publicly exploitable details in an issue.

You should receive an acknowledgement within seven days. Valid reports will be
investigated privately, fixed on supported code, and disclosed after affected
users have a reasonable opportunity to update.

## Scope notes

The ESP32 API may use cleartext HTTP only on explicitly local/private addresses
because the companion firmware is designed for a trusted LAN or VPN. Gotify
credentials always require HTTPS. Reports that demonstrate crossing either trust
boundary are in scope.

Responses to the app's own HTTP requests are read with a size limit, and stored
event text is clipped before it reaches storage. Incoming WebSocket messages are
a known exception: OkHttp assembles a frame before handing it over and exposes no
limit for doing so, so a configured Gotify server or ESP32 can still make the app
allocate an arbitrarily large message. Both are endpoints the user chose and
authenticated to, so this is accepted rather than fixed; a report that reaches it
without controlling either endpoint is in scope.
