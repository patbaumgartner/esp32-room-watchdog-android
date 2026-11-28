# Contributing

Thanks for improving Room Watchdog. Small, focused changes with behavioural
tests are easiest to review.

## Set up a development environment

Install JDK 27 and Android SDK Platform 37.1 (`compileSdkMinor = 1`; `targetSdk` remains 37),
then clone the repository. Android Studio can
import the root directory directly; command-line builds use the checked-in
Gradle wrapper and need `ANDROID_HOME` set, or an `sdk.dir` line in
`local.properties`.

```bash
git clone https://github.com/patbaumgartner/esp32-room-watchdog-android.git
cd esp32-room-watchdog-android
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :app:assembleDebug
```

Real devices and credentials are not required for unit tests or compilation. For
manual device testing, copy `local.properties.example` to the ignored
`local.properties` and use development credentials only.

## Make a change

1. Create a branch from `main`.
2. Keep the change scoped to one concern.
3. Add or update tests for changed behaviour.
4. Keep user-facing text in `app/src/main/res/values/strings.xml`.
5. Never commit `local.properties`, tokens, APKs, keystores, or generated build
   output.

Use Conventional Commit subjects, for example
`fix: reject insecure Gotify endpoints` or `docs: clarify device setup`.

## Releases

Downloadable builds are published from `.github/workflows/release.yml` (see
[ADR-0001](docs/adr/0001-release-signing-and-distribution.md)):

- Every push to `main` rebuilds the **Snapshot** prerelease with a signed APK.
- Pushing a signed tag `vX.Y.Z` publishes a **release**: signed APK + AAB +
  `SHA256SUMS`. The tag sets the version (`v0.1.0` → versionName `0.1.0`,
  versionCode `100`):

  ```bash
  git tag -s v0.1.0 -m "v0.1.0"
  git push origin main v0.1.0
  ```

Both jobs sign with the release key held in the repository secrets
`SR_KEYSTORE_BASE64`, `SR_KEYSTORE_PASSWORD`, `SR_KEY_ALIAS`, and
`SR_KEY_PASSWORD`; the workflow fails if they are missing. To set or rotate
the key, generate a PKCS12 keystore with `keytool` and upload it with
`gh secret set` — never commit keystore material. Keep one backup of the
keystore outside the repository (password manager / HSM); losing it means
updates can no longer be signed with the same key.

For local signed builds, either export the four `SR_*` environment variables
or create a git-ignored `keystore.properties` with `storeFile`,
`storePassword`, `keyAlias`, `keyPassword`, then run
`./gradlew :app:assembleRelease`.

## Validate

Validation needs Bash, `jq`, `yamllint`, ShellCheck and standard Unix utilities
(`awk`, `sed`, `grep`, `diff` and coreutils). `curl` is needed only for a live
firmware probe; its fixtures use a mock.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest bundleRelease
scripts/check-ci.sh
scripts/check-audio-contract.sh
scripts/test-dependabot-preflight.sh
scripts/test-firmware-probe.sh
yamllint .
find . -name '*.sh' -not -path './*/build/*' -exec shellcheck {} +
git diff --check
```

Android lint intentionally reports two non-failing warnings: the app permits
constrained LAN cleartext for the ESP32, and adaptive launcher icons must remain
in `mipmap-anydpi-v26` for Android resource packaging. Do not suppress either
warning globally.

## Hosted requirements

Pull requests require the **`Test, lint, and package`** check. This is the final
`verify` job in `ci.yml`, not either test job alone. It runs after both
**Build and unit tests** and **Instrumented tests (emulator)** and explicitly
asserts that both succeeded, including when either was cancelled or failed.
Keep the job name, its `always()` condition, both dependencies, and both shell
assertions intact; `scripts/check-ci.sh` statically guards this invariant. The
instrumented job must continue to run `connectedDebugAndroidTest` and upload its
failure diagnostics.

Dependabot auto-merge is allowed only after the metadata-readable, paginated
`rules/branches/main` API proves that active `main` branch rules require that
exact aggregate check context. Classic branch protection alone is not visible to
`GITHUB_TOKEN` and does not satisfy the preflight; the workflow does not call the
classic protection or detailed-ruleset APIs. Missing, inaccessible, or incomplete
rules fail closed with remediation output instead of queuing a merge.
`scripts/test-dependabot-preflight.sh` exercises the real inline predicate,
pagination, exact type/context matching, and API-failure path. The
`pull_request_target` workflow must never check out or execute pull-request code.

Fetch-metadata and the protection preflight use `GITHUB_TOKEN`; only the actual
merge step uses `AUTO_MERGE_TOKEN`. Configure that fine-grained token with
**Contents: write**, **Pull requests: write**, and **Workflows: write** so it can
merge workflow changes and trigger post-merge CI and Snapshot events. A
missing or empty token stops an eligible merge with an actionable error without
printing the token value; skipped major/unknown updates do not require it.

## Pull requests

Explain the user-visible effect, why the change is needed, and exactly how it was
tested. Include screenshots for visual changes when they make review materially
easier. Do not include credentials or recordings in screenshots, logs, fixtures,
or issue reports.

Security vulnerabilities belong in a private report as described in
[SECURITY.md](SECURITY.md), not a public issue.
