# OnMyWay Release Workflow Notes

The Forgejo workflow at `../workflows/release-apk.yml` builds an Android release
APK for each `v*` or `V*` tag, signs the APK with the Android application key,
creates an armored detached OpenPGP signature, and publishes both files to
Forgejo. GitHub mirroring to `firebadnofire/OnMyWay` is optional.

## Plan

1. Configure the Android keystore and OpenPGP signing secrets documented in
   `release-apk-workflow.md`.
2. Run the local validation commands without release secrets.
3. Push a version tag only after the app version and release contents are ready.
4. Confirm that the hosted release contains both the APK and matching `.asc`.

## Implementation

The release build reads Android signing values only from environment variables.
No keys, passphrases, aliases, tokens, or keystores belong in the repository.
Local release builds with no signing variables remain unsigned; partial signing
configuration fails during Gradle configuration.

CI requires these Forgejo secrets:

- `KEY_ALIAS`
- `KEY_PASSWORD`
- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `CI_KEY`
- `CI_KEY_PASSPHRASE`

Optional GitHub mirroring uses `GH_KEY`. `GITHUB_RELEASE_OWNER` and
`GITHUB_RELEASE_REPO` may override the default `firebadnofire/OnMyWay` target,
but they must be set together.

## Validation

Use JDK 17 or newer, then run:

```powershell
.\gradlew.bat --no-daemon tasks --all
.\gradlew.bat --no-daemon test
.\gradlew.bat --no-daemon lint
.\gradlew.bat --no-daemon clean assembleRelease
git diff --check
```

An unsigned local `assembleRelease` is expected when all four Android signing
environment variables are absent. Hosted CI requires them and will stop before
building if any required signing input is missing.

See `release-apk-workflow.md` for setup, security checks, release verification,
and troubleshooting details.
