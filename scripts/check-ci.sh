#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ci="$repo_root/.github/workflows/ci.yml"
release="$repo_root/.github/workflows/release.yml"

require_line() {
    local line="$1"
    if ! grep -Fq -- "$line" "$ci"; then
        printf 'Missing required CI invariant: %s\n' "$line" >&2
        exit 1
    fi
}

require_line '    name: Build and unit tests'
require_line '  verify:'
require_line '    name: Test, lint, and package'
require_line "    if: \${{ always() }}"
require_line '    needs: [build, instrumented]'
require_line '    timeout-minutes: 5'
require_line "test \"\${{ needs.build.result }}\" = \"success\""
require_line "test \"\${{ needs.instrumented.result }}\" = \"success\""
require_line 'connectedDebugAndroidTest'
require_line 'app/build/outputs/androidTest-results/'

require_line "\"\${ANDROID_SDK_ROOT:-\$ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager\""
require_line '"platforms;android-37.1"'

sdk_install_count="$(grep -Fxc '      - name: Install Android SDK 37.1' "$release" || true)"
if [ "$sdk_install_count" -ne 2 ]; then
    printf 'Expected both release jobs to install Android SDK 37.1 explicitly; found %s install steps.\n' \
        "$sdk_install_count" >&2
    exit 1
fi
for required in "\"\${ANDROID_SDK_ROOT:-\$ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager\"" '"platforms;android-37.1"'; do
    if ! grep -Fq -- "$required" "$release"; then
        printf 'Missing release SDK invariant: %s\n' "$required" >&2
        exit 1
    fi
done
