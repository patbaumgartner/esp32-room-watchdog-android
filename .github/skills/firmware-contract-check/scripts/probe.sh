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
src="$repo/app/src/main/kotlin/com/patbaumgartner/android/roomwatchdog"

read_prop() { [ -f "$props" ] && grep -E "^$1=" "$props" | cut -d= -f2- | tr -d '\r'; }

url="${DEVICE_URL:-$(read_prop 'watchdog\.deviceUrl')}"
token="${API_TOKEN:-$(read_prop 'watchdog\.apiToken')}"

if [ -z "$url" ] || [ -z "$token" ]; then
    echo "No device URL or API token. Set DEVICE_URL and API_TOKEN, or fill in $props." >&2
    exit 1
fi

base="${url%/}"
scheme="${base%%://*}"
authority_and_path="${base#*://}"
authority="${authority_and_path%%/*}"
if [[ "$authority" == *@* || "$base" == *\?* || "$base" == *\#* ]]; then
    echo "Device credentials belong in API_TOKEN, not in DEVICE_URL." >&2
    exit 1
fi
base_path=""
if [[ "$authority_and_path" == */* ]]; then
    base_path="/${authority_and_path#*/}"
fi
origin="$scheme://$authority$base_path"

explicit_port=false
if [[ "$authority" == \[* ]]; then
    bracket_suffix="${authority#*\]}"
    [[ "$bracket_suffix" == :* ]] && explicit_port=true
elif [[ "$authority" == *:* ]]; then
    explicit_port=true
fi

echo "== device: $origin =="

code=$(curl -s -o /tmp/fw-status.json -w '%{http_code}' --max-time 5 -H "Authorization: Bearer $token" \
    "$origin/status")
echo "GET  /status            -> $code"
if [ "$code" = "200" ]; then
    echo "     fields: $(grep -oE '"[a-zA-Z]+":' /tmp/fw-status.json | tr -d '":' | tr '\n' ' ')"
    echo "     audioStreaming: $(grep -oE '"audioStreaming":[a-z]+' /tmp/fw-status.json | cut -d: -f2)"
fi

# 400 means the endpoint exists and the token was accepted; curl just is not a websocket.
key=$(head -c 16 /dev/urandom | base64)
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -H "Authorization: Bearer $token" \
    -H "Connection: Upgrade" -H "Upgrade: websocket" \
    -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: $key" \
    "$origin/ws")
echo "GET  /ws                -> $code   (101 upgraded, 400 present but handshake refused, 404 absent, 401 token rejected)"

audio_urls=()
if [ "$scheme" = 'http' ] && [ "$explicit_port" = false ]; then
    # Migration-compatible direct probe. Explicit :80 is the standard-port proxy override.
    audio_urls+=("$scheme://$authority:81$base_path/audio.pcm" "$origin/audio.pcm")
else
    # HTTPS and every explicit HTTP port keep the configured proxy endpoint.
    audio_urls+=("$origin/audio.pcm")
fi
for audio_url in "${audio_urls[@]}"; do
    bytes=$(curl -s --max-time 3 -H "Authorization: Bearer $token" "$audio_url" 2>/dev/null | wc -c)
    if [ "$bytes" -gt 4096 ]; then
        echo "GET  $audio_url -> streaming ($bytes bytes in 3s)"
    else
        echo "GET  $audio_url -> no stream ($bytes bytes; busy, absent, or wrong port)"
    fi
done

echo
echo "== what the app expects =="
grep -nE 'DEFAULT_AUDIO_PORT|DEFAULT_AUDIO_PATH|SAMPLE_RATE|addPathSegment|audioRequest' \
    "$src/data/device/DeviceClient.kt" 2>/dev/null | sed 's/^/  DeviceClient.kt:/'
grep -nE 'deviceAudioUrl|base\.scheme|explicitPort|helloHost|audioPort' \
    "$src/data/network/EndpointPolicy.kt" 2>/dev/null | sed 's/^/  EndpointPolicy.kt:/'
grep -nE 'val [a-zA-Z]+:' "$src/data/device/TelemetryFrame.kt" 2>/dev/null | sed 's/^/  TelemetryFrame.kt:/'
grep -nE 'audioPort|helloHost|audioRequest' "$src/audio/PcmStreamSession.kt" "$src/audio/AudioStartCoordinator.kt" \
    "$src/ui/AppViewModel.kt" 2>/dev/null | sed "s#$src/##" | sed 's/^/  /'

echo
echo "Compare the two halves. A field the device sends that the app has no property for is"
echo "silently dropped by ignoreUnknownKeys; a port or path the app hardcodes is a live bug."
