#!/usr/bin/env bash
#
# Probes the running ESP32 and prints what the app expects, side by side.
#
#   scripts/probe.sh                 # uses watchdog.deviceUrl from local.properties
#   DEVICE_URL=http://192.168.1.20 API_TOKEN=... scripts/probe.sh
#
# Never prints the token. Exits 0 even when the device is unreachable: the output
# is the finding, not the exit code.
set -uo pipefail

repo="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)}"
props="$repo/local.properties"
src="$repo/app/src/main/kotlin/com/patbaumgartner/roomwatchdog/data/device"

read_prop() { [ -f "$props" ] && grep -E "^$1=" "$props" | cut -d= -f2- | tr -d '\r'; }

url="${DEVICE_URL:-$(read_prop 'watchdog\.deviceUrl')}"
token="${API_TOKEN:-$(read_prop 'watchdog\.apiToken')}"

if [ -z "$url" ] || [ -z "$token" ]; then
    echo "No device URL or API token. Set DEVICE_URL and API_TOKEN, or fill in $props." >&2
    exit 1
fi

host="${url#*://}"
host="${host%%/*}"
host="${host%%:*}"
scheme="${url%%://*}"
auth=(-H "Authorization: Bearer $token")

echo "== device: $host =="

code=$(curl -s -o /tmp/fw-status.json -w '%{http_code}' --max-time 5 "${auth[@]}" "$scheme://$host/status")
echo "GET  /status            -> $code"
if [ "$code" = "200" ]; then
    echo "     fields: $(grep -oE '"[a-zA-Z]+":' /tmp/fw-status.json | tr -d '":' | tr '\n' ' ')"
    echo "     audioStreaming: $(grep -oE '"audioStreaming":[a-z]+' /tmp/fw-status.json | cut -d: -f2)"
fi

# 400 means the endpoint exists and the token was accepted; curl just is not a websocket.
key=$(head -c 16 /dev/urandom | base64)
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "${auth[@]}" \
    -H "Connection: Upgrade" -H "Upgrade: websocket" \
    -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: $key" \
    "$scheme://$host/ws")
echo "GET  /ws                -> $code   (101 upgraded, 400 present but handshake refused, 404 absent, 401 token rejected)"

for port in 81 80; do
    bytes=$(curl -s --max-time 3 "${auth[@]}" "$scheme://$host:$port/audio.pcm" 2>/dev/null | wc -c)
    if [ "$bytes" -gt 4096 ]; then
        echo "GET  :$port/audio.pcm     -> streaming ($bytes bytes in 3s)"
    else
        echo "GET  :$port/audio.pcm     -> no stream ($bytes bytes; busy, absent, or wrong port)"
    fi
done

echo
echo "== what the app expects =="
grep -nE 'DEFAULT_AUDIO_PORT|DEFAULT_AUDIO_PATH|SAMPLE_RATE|addPathSegment' \
    "$src/DeviceClient.kt" 2>/dev/null | sed 's/^/  DeviceClient.kt:/'
grep -nE 'val [a-zA-Z]+:' "$src/TelemetryFrame.kt" 2>/dev/null | sed 's/^/  TelemetryFrame.kt:/'

echo
echo "Compare the two halves. A field the device sends that the app has no property for is"
echo "silently dropped by ignoreUnknownKeys; a port or path the app hardcodes is a live bug."
