#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source_root="$repo_root/app/src/main/kotlin/com/patbaumgartner/android/roomwatchdog"
view_model="$source_root/ui/AppViewModel.kt"
session="$source_root/audio/PcmStreamSession.kt"
coordinator="$source_root/audio/AudioStartCoordinator.kt"

require_wiring() {
    local file="$1"
    local line="$2"
    if ! grep -Fq -- "$line" "$file"; then
        printf 'Missing announced-audio-port wiring in %s: %s\n' "${file#"$repo_root"/}" "$line" >&2
        exit 1
    fi
}

require_wiring "$view_model" 'AudioStartCoordinator(settings.current.deviceUrl, ::onAudioEndpointChanged)'
require_wiring "$view_model" 'audioStarts.resetEndpoint('
require_wiring "$view_model" 'audioStarts.onHello(socketGeneration, frame.audioPort, frame.host)'
require_wiring "$view_model" 'requestAudioStart(alsoRecord = action == AlertNotifier.AutoStart.RECORD)'
require_wiring "$view_model" 'requestAudioStart(alsoRecord = false)'
require_wiring "$view_model" 'alsoRecord = start.alsoRecord,'
require_wiring "$view_model" 'audioPort = start.port,'
require_wiring "$view_model" 'helloHost = start.helloHost,'
require_wiring "$coordinator" 'fun onTimeout(wait: AudioStartDecision.Wait): AudioStartDecision.Start?'
require_wiring "$coordinator" 'fun cancelPendingStart()'
require_wiring "$coordinator" 'fun resetEndpoint(newEndpoint: String, force: Boolean = false): Boolean'
require_wiring "$session" 'audioPort: Int = DeviceClient.DEFAULT_AUDIO_PORT,'
require_wiring "$session" 'helloHost: String? = null,'
require_wiring "$session" 'job = scope.launch { stream(baseUrl, apiToken, audioPort, helloHost) }'
require_wiring "$session" 'deviceClient.audioRequest(baseUrl, apiToken, audioPort, helloHost)'
