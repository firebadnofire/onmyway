# OnMyWay Release APK Workflow

`.forgejo/workflows/release-apk.yml` builds a signed OnMyWay APK for a pushed
`v*` or `V*` tag. It then creates and verifies an armored detached OpenPGP
signature and publishes both files:

```text
onmyway-<tag>.apk
onmyway-<tag>.apk.asc
```

Forgejo publishing is required. GitHub mirroring is optional and defaults to
`firebadnofire/OnMyWay`.

## Runner environment

The job uses the local `ubuntu-22.04` Forgejo runner in an Ubuntu 22.04
container attached to `ci-network`. The bootstrap configures the runner-local
APT proxy at `apt-cacher-ng:3142`, installs the command-line dependencies and
GnuPG, and installs Node 20 when required by the external actions.

The workflow uses:

```yaml
uses: actions/checkout@v4
uses: actions/setup-java@v4
uses: https://github.com/android-actions/setup-android@v3
```

The full URL for the Android setup action is intentional for this Forgejo host.

## Required secrets

| Secret | Purpose |
| --- | --- |
| `KEY_ALIAS` | Alias of the Android application signing key. |
| `KEY_PASSWORD` | Password for the Android application signing key. |
| `KEYSTORE_BASE64` | Base64-encoded Android keystore. |
| `KEYSTORE_PASSWORD` | Password for the Android keystore. |
| `CI_KEY` | Base64-encoded armored or binary OpenPGP private key. |
| `CI_KEY_PASSPHRASE` | Passphrase for the OpenPGP private key. |

Do not commit any of these values or their decoded files.

The OpenPGP secret must contain exactly one primary secret key. Its fingerprint
must be:

```text
7D6E F134 D851 C8DA 0862 D974 94F3 1AF3 74E2 EE3C
```

CI imports it into an isolated temporary GnuPG home, checks the full fingerprint,
creates the detached signature with loopback pinentry, verifies that signature,
and removes the temporary keyring when the step exits. A missing input, wrong
fingerprint, bad passphrase, empty artifact, or failed verification stops the
release before publication.

Optional GitHub mirroring uses:

| Setting | Purpose |
| --- | --- |
| `GH_KEY` | GitHub token with release `Contents: Read and write` access. |
| `GITHUB_RELEASE_OWNER` | Optional owner override. |
| `GITHUB_RELEASE_REPO` | Optional repository override. |

The two override variables must be set together. If `GH_KEY` is absent and no
override is configured, GitHub publishing is skipped. Forgejo publishing still
runs.

## Preparing secret values

Create base64 values without adding them to the repository. On Linux:

```sh
base64 -w 0 release.keystore
gpg --armor --export-secret-keys 7D6EF134D851C8DA0862D97494F31AF374E2EE3C \
  | base64 -w 0
```

Verify the exported private key fingerprint in a temporary GnuPG home before
storing `CI_KEY`. The workflow independently repeats this check.

## Android signing behavior

The workflow decodes the keystore to a mode-`0600` temporary file and exports:

```text
RELEASE_KEYSTORE_PATH
KEYSTORE_PASSWORD
KEY_ALIAS
KEY_PASSWORD
```

`app/build.gradle.kts` attaches the signing configuration to the release build
only when all four values are present. A partially populated environment fails
with the missing variable names. With no values, local release builds retain
Android Gradle's normal unsigned release behavior.

Releases must keep using the same Android application key or Android will reject
upgrades over existing installations.

## Release verification

Download the APK and its `.asc`, recover the public key using any one method,
then verify both the fingerprint and artifact:

```sh
gpg --keyserver hkps://keys.openpgp.org --recv-keys 7D6EF134D851C8DA0862D97494F31AF374E2EE3C
# Or:
gpg --keyserver hkps://keyserver.ubuntu.com --recv-keys 7D6EF134D851C8DA0862D97494F31AF374E2EE3C
# Or:
curl --proto '=https' --tlsv1.2 -fsSLo william.asc https://archuser.org/gpg/william.asc
gpg --import william.asc

gpg --fingerprint 7D6EF134D851C8DA0862D97494F31AF374E2EE3C
gpg --verify onmyway-<tag>.apk.asc onmyway-<tag>.apk
```

Confirm the fingerprint is exactly
`7D6E F134 D851 C8DA 0862 D974 94F3 1AF3 74E2 EE3C` before trusting a
successful signature verification.

The detached OpenPGP signature authenticates the downloaded APK as a release
artifact. Android's application signature separately authenticates the installed
package and controls upgrade compatibility; both signatures are intentional.

## Publication and reruns

For each host, the workflow looks up the release by tag, creates it if missing,
or updates it if present. Before upload it deletes assets with the same two
deterministic filenames. This makes a same-tag rerun idempotent while ensuring
the APK and `.asc` are replaced as a pair.

The GitHub target defaults to `firebadnofire/OnMyWay`. The copied workflow does
not push the Forgejo tag to GitHub; the target release repository must be able to
resolve the tag according to GitHub's release behavior.

## Local validation

Use JDK 17 or newer and an installed Android SDK, then run:

```powershell
.\gradlew.bat --no-daemon tasks --all
.\gradlew.bat --no-daemon test
.\gradlew.bat --no-daemon lint
.\gradlew.bat --no-daemon clean assembleRelease
git diff --check
```

The full OpenPGP signing step cannot be exercised without the private-key
secrets. Hosted publication likewise requires a pushed tag and repository
credentials; local build success does not prove either one.

## Troubleshooting

- No workflow run: confirm the pushed tag starts with `v` or `V`.
- Android build is unsigned in CI: the workflow should have stopped during input
  validation; confirm all four Android signing secrets are in this repository's
  Forgejo Actions scope.
- Fingerprint mismatch: replace `CI_KEY` with the correct key. Do not change the
  pinned fingerprint merely to accept an unexpected key.
- GPG signing failure: confirm `CI_KEY_PASSPHRASE` matches the exported secret
  key and that `CI_KEY` is base64 without truncation.
- Duplicate asset: rerun the workflow; its publisher deletes same-name assets
  before uploading. If it still fails, inspect the release for a server-side
  incomplete upload.
- Android setup action clone failure: retain its full GitHub URL because this
  Forgejo host cannot resolve that action through its default mirror.
