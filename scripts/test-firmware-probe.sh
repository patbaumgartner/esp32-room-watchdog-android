#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
probe="$repo_root/.github/skills/firmware-contract-check/scripts/probe.sh"
work="$(mktemp -d "${TMPDIR:-/tmp}/room-watchdog-firmware-probe.XXXXXX")"
trap 'rm -rf -- "$work"' EXIT

mkdir -p "$work/bin"
cat > "$work/bin/curl" <<'EOF'
#!/usr/bin/env bash
set -u
url="${!#}"
printf '%s\n' "$url" >> "$MOCK_CURL_LOG"
case "$url" in
    */status | */ws)
        printf '404'
        ;;
    *)
        ;;
esac
EOF
chmod +x "$work/bin/curl"

run_probe() {
    local name="$1"
    local device_url="$2"
    local expected="$3"
    local log="$work/$name.urls"
    local output="$work/$name.output"
    local expected_file="$work/$name.expected"
    : > "$log"
    printf '%s\n' "$expected" > "$expected_file"

    MOCK_CURL_LOG="$log" \
    PATH="$work/bin:$PATH" \
    REPO_ROOT="$repo_root" \
    DEVICE_URL="$device_url" \
    API_TOKEN='fixture-token' \
        bash "$probe" > "$output" 2>&1

    if grep -Fq 'fixture-token' "$output" "$log"; then
        printf 'FAIL: %s exposed the fixture credential\n' "$name" >&2
        return 1
    fi
    if ! diff -u "$expected_file" "$log"; then
        printf 'FAIL: %s probed unexpected URLs\n' "$name" >&2
        return 1
    fi
    printf 'PASS: probe-%s\n' "$name"
}

run_probe 'https-custom-proxy' 'https://watchdog.example.com:8443' 'https://watchdog.example.com:8443/status
https://watchdog.example.com:8443/ws
https://watchdog.example.com:8443/audio.pcm'

run_probe 'ipv6-direct' 'http://[fd12::1]' 'http://[fd12::1]/status
http://[fd12::1]/ws
http://[fd12::1]:81/audio.pcm
http://[fd12::1]/audio.pcm'

run_probe 'ipv6-standard-proxy' 'http://[fd12::1]:80' 'http://[fd12::1]:80/status
http://[fd12::1]:80/ws
http://[fd12::1]:80/audio.pcm'

run_probe 'http-custom-proxy' 'http://proxy.lan:8080' 'http://proxy.lan:8080/status
http://proxy.lan:8080/ws
http://proxy.lan:8080/audio.pcm'
