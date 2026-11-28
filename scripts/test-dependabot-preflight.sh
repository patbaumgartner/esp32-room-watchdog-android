#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
workflow="$repo_root/.github/workflows/dependabot-auto-merge.yml"
work="$(mktemp -d "${TMPDIR:-/tmp}/room-watchdog-dependabot-preflight.XXXXXX")"
trap 'rm -rf -- "$work"' EXIT

preflight="$work/preflight.sh"
awk '
    /^      - name: Verify required check protection$/ { in_step = 1 }
    in_step && /^        run: \|$/ { in_run = 1; next }
    in_run && /^      - name: Enable auto-merge/ { exit }
    in_run {
        sub(/^          /, "")
        print
    }
' "$workflow" > "$preflight"

if ! grep -Fq "repos/\$GITHUB_REPOSITORY/rules/branches/main?per_page=100" "$preflight"; then
    printf 'FAIL: could not extract the real metadata-only Dependabot preflight from %s\n' "$workflow" >&2
    exit 1
fi
if grep -Eq 'branches/main/protection|/rulesets' "$preflight"; then
    printf 'FAIL: preflight still calls classic protection or detailed ruleset APIs\n' >&2
    exit 1
fi

merge="$work/merge.sh"
awk '
    /^      - name: Enable auto-merge/ { in_step = 1 }
    in_step && /^        run: \|$/ { in_run = 1; next }
    in_run && /^        env:/ { exit }
    in_run {
        sub(/^          /, "")
        print
    }
' "$workflow" > "$merge"
if ! grep -Fq 'UPDATE_TYPE' "$merge"; then
    printf 'FAIL: could not extract the real Dependabot merge guard from %s\n' "$workflow" >&2
    exit 1
fi
preflight_token_count="$(grep -Fc "GH_TOKEN: \${{ secrets.GITHUB_TOKEN }}" "$workflow" || true)"
if ! grep -Fq "github-token: \${{ secrets.GITHUB_TOKEN }}" "$workflow" ||
    [ "$preflight_token_count" -ne 1 ]; then
    printf 'FAIL: fetch-metadata and preflight must keep GITHUB_TOKEN\n' >&2
    exit 1
fi
merge_token_count="$(grep -Fc "GH_TOKEN: \${{ secrets.AUTO_MERGE_TOKEN }}" "$workflow" || true)"
if [ "$merge_token_count" -ne 1 ]; then
    printf 'FAIL: expected only the actual merge step to reference AUTO_MERGE_TOKEN\n' >&2
    exit 1
fi

mkdir -p "$work/bin"
cat > "$work/bin/gh" <<'EOF'
#!/usr/bin/env bash
set -u

if [ "${1:-}" = 'pr' ] && [ "${2:-}" = 'merge' ]; then
    printf '%s\n' "$*" >> "$MOCK_MERGE_LOG"
    exit 0
fi

if [ "${MOCK_GH_MODE:-}" = 'api-failure' ]; then
    echo 'mock GitHub API failure' >&2
    exit 1
fi

case " $* " in
    *'/rules/branches/main?per_page=100'*)
        cat "$MOCK_RULES"
        ;;
    *)
        printf 'Unexpected mock gh invocation: %s\n' "$*" >&2
        exit 2
        ;;
esac
EOF
chmod +x "$work/bin/gh"

write_rules() {
    local path="$1"
    local context="$2"
    local type="${3:-required_status_checks}"
    jq -n --arg context "$context" --arg type "$type" '
        [[{
            type: $type,
            ruleset_id: 27844588,
            parameters: {required_status_checks: [{context: $context}]}
        }]]
    ' > "$path"
}

run_case() {
    local name="$1"
    local mode="$2"
    local fixture="$3"
    local expected="$4"
    local expected_output="${5:-}"
    local case_dir="$work/$name"
    local output
    local rc
    mkdir -p "$case_dir"

    set +e
    output="$(
        PATH="$work/bin:$PATH" \
        GITHUB_REPOSITORY='owner/repo' \
        RUNNER_TEMP="$case_dir" \
        REQUIRED_CHECK='Test, lint, and package' \
        GH_TOKEN='test-token' \
        MOCK_GH_MODE="$mode" \
        MOCK_RULES="$fixture" \
        bash "$preflight" 2>&1
    )"
    rc=$?
    set -e
    printf '%s\n' "$output" | sed 's/^/  /'

    if [ "$expected" = 'success' ] && [ "$rc" -ne 0 ]; then
        printf 'FAIL: %s was rejected with exit %s\n' "$name" "$rc" >&2
        return 1
    fi
    if [ "$expected" = 'failure' ] && [ "$rc" -eq 0 ]; then
        printf 'FAIL: %s was accepted by the real inline predicate\n' "$name" >&2
        return 1
    fi
    if [ -n "$expected_output" ] && ! grep -Fq "$expected_output" <<< "$output"; then
        printf 'FAIL: %s did not report %s\n' "$name" "$expected_output" >&2
        return 1
    fi
    printf 'PASS: %s\n' "$name"
}

required="$work/required.json"
write_rules "$required" 'Test, lint, and package'
run_case 'required-context' 'rules' "$required" 'success'

second_page="$work/second-page.json"
jq -n '
    [[{type: "creation", ruleset_id: 27844588}],
     [{
        type: "required_status_checks",
        ruleset_id: 27844588,
        parameters: {required_status_checks: [{context: "Test, lint, and package"}]}
     }]]
' > "$second_page"
run_case 'required-on-second-page' 'rules' "$second_page" 'success'

missing="$work/missing.json"
write_rules "$missing" 'Another check'
run_case 'missing-context' 'rules' "$missing" 'failure'

wrong_type="$work/wrong-type.json"
write_rules "$wrong_type" 'Test, lint, and package' 'branch_name_pattern'
run_case 'wrong-rule-type' 'rules' "$wrong_type" 'failure'

empty="$work/empty.json"
printf '[[]]\n' > "$empty"
run_case 'empty-rules' 'rules' "$empty" 'failure'

run_case 'api-failure' 'api-failure' 'unused' 'failure' 'Unable to read active main branch rules'

run_merge_case() {
    local name="$1"
    local update_type="$2"
    local expect_merge="$3"
    local log="$work/merge-$name.log"
    local output
    output="$(
        PATH="$work/bin:$PATH" \
        UPDATE_TYPE="$update_type" \
        PR_URL='https://example.invalid/pull/1' \
        GH_TOKEN='test-token' \
        MOCK_MERGE_LOG="$log" \
        bash "$merge" 2>&1
    )"
    printf '%s\n' "$output" | sed 's/^/  /'

    if [ "$expect_merge" = 'yes' ]; then
        if ! grep -Fq 'pr merge --auto --rebase https://example.invalid/pull/1' "$log"; then
            printf 'FAIL: %s did not queue auto-merge\n' "$name" >&2
            return 1
        fi
    elif [ -s "$log" ]; then
        printf 'FAIL: %s unexpectedly queued auto-merge\n' "$name" >&2
        return 1
    fi
    printf 'PASS: merge-%s\n' "$name"
}

run_merge_case 'patch' 'version-update:semver-patch' 'yes'
run_merge_case 'minor' 'version-update:semver-minor' 'yes'
run_merge_case 'major' 'version-update:semver-major' 'no'
run_merge_case 'empty' '' 'no'
run_merge_case 'unknown' 'unexpected-metadata' 'no'

missing_log="$work/merge-missing-token.log"
set +e
missing_output="$(
    PATH="$work/bin:$PATH" \
    UPDATE_TYPE='version-update:semver-patch' \
    PR_URL='https://example.invalid/pull/1' \
    GH_TOKEN='' \
    MOCK_MERGE_LOG="$missing_log" \
    bash "$merge" 2>&1
)"
missing_rc=$?
set -e
printf '%s\n' "$missing_output" | sed 's/^/  /'
if [ "$missing_rc" -eq 0 ] || [ -s "$missing_log" ]; then
    printf 'FAIL: an empty AUTO_MERGE_TOKEN did not stop the merge\n' >&2
    exit 1
fi
if ! grep -Fq 'AUTO_MERGE_TOKEN is missing or empty' <<< "$missing_output"; then
    printf 'FAIL: missing-token error is not actionable\n' >&2
    exit 1
fi
printf 'PASS: merge-missing-token\n'
