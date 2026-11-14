# Contributing

Thanks for improving Room Watchdog. Small, focused changes with behavioural
tests are easiest to review.

## Set up a development environment

Install JDK 27 and Android SDK 37, then clone the repository. Android Studio can
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

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
yamllint .
find . -name '*.sh' -not -path './*/build/*' -exec shellcheck {} +
git diff --check
```

Android lint intentionally reports two non-failing warnings: the app permits
constrained LAN cleartext for the ESP32, and adaptive launcher icons must remain
in `mipmap-anydpi-v26` for Android resource packaging. Do not suppress either
warning globally.

## Pull requests

Explain the user-visible effect, why the change is needed, and exactly how it was
tested. Include screenshots for visual changes when they make review materially
easier. Do not include credentials or recordings in screenshots, logs, fixtures,
or issue reports.

Security vulnerabilities belong in a private report as described in
[SECURITY.md](SECURITY.md), not a public issue.
