# OnMyWay

OnMyWay is a private, local Android utility for reminders that become useful
when the physical environment changes. It is an event/trigger engine, not a
navigation app. Rules, runtime state, and the most recent 200 trigger records are
stored in Room on the device. The app has no account, analytics, ads, cloud
service, or `INTERNET` permission. Android backup is disabled so precise rule
configuration is not uploaded by the platform backup service.

## Supported triggers

- Connected Wi-Fi name (SSID), evaluated from the current connection without scans.
- Connected Wi-Fi access point (BSSID), normalized and compared case-insensitively.
- Nearby Wi-Fi name (SSID), using best-effort Android scan results.
- Nearby Wi-Fi access point (BSSID), using best-effort Android scan results.
- GPS region, firing only after a confirmed outside-to-inside transition.
- Distance traveled in feet or meters, optionally including usable elevation.

All triggers are edge based. A recurring rule rearms only after its condition is
no longer satisfied. A one-time rule disables itself after firing; it remains in
the app and can be re-enabled.

SSID, BSSID, and GPS rules also offer **Invert**. With it enabled, the same
outside/inside edge logic fires when the device leaves the matching network or
GPS circle. The first observation establishes state; it does not fire merely
because the rule was created while already inside.

## Menu, sounds, themes, and backups

The left hamburger menu opens History and Diagnostics and controls global app
preferences. Material You colors are available on Android 12 and newer, and the
dark-theme setting applies to both dynamic and standard color schemes.

A global custom notification sound can be selected through Android's document
picker. Individual reminders can opt into their own custom sound when they are
created or edited; that sound takes precedence over the global default. On
Android 8 and newer, OnMyWay uses a sound-specific notification channel so the
selected audio is honored by the platform. Android's notification settings can
still override channel behavior.

Export writes a versioned JSON backup containing all reminders, notification
text and sound references, trigger configuration and runtime state, bounded
history, and global settings. Import validates the file and asks before replacing
current data. Audio itself is not copied into JSON: document references may not
be readable after moving a backup to another device, in which case notifications
fall back to the normal sound until the audio is selected again.

Example:

```text
Trigger: Distance traveled
Distance: 25 ft
Notification: KEYS.
One time: Yes
```

This lets someone create a reminder while upstairs and receive it after they
begin leaving, instead of guessing a clock time.

## Android requirements

- Kotlin, Jetpack Compose, Material 3, Room, coroutines, and Flow
- minSdk 29 (Android 10)
- compileSdk/targetSdk 36
- JDK 17 or newer for Gradle (CI uses Temurin 17)

Build from PowerShell:

```powershell
.\gradlew.bat --no-daemon test
.\gradlew.bat --no-daemon lint
.\gradlew.bat --no-daemon assembleDebug
```

The local release build is unsigned unless all four release-signing environment
variables are provided. See `.forgejo/docs/release-apk-workflow.md` for signed
tag releases and detached OpenPGP verification.

## Permissions and monitoring

On the first launch after installation, OnMyWay requests the runtime permissions
needed by its supported monitoring features. Later launches do not repeatedly
show permission dialogs; missing access is shown in Diagnostics and on event
cards. Depending on Android version and trigger, OnMyWay may need:

- notification permission;
- precise location to reveal Wi-Fi identity and scan results;
- Nearby Wi-Fi permission on Android 13+;
- foreground and background location for GPS/distance rules.

Android 11+ requires background location to be granted from the app’s system
settings. After the first-launch foreground permission dialog, OnMyWay opens its
system settings page when background location still needs to be granted. Event
cards and Diagnostics show missing permissions or disabled system services.

Connected Wi-Fi rules use `ConnectivityManager` callbacks and never request an
active scan. Nearby Wi-Fi rules listen for system scan broadcasts and request
scans at conservative battery-saver, balanced, or frequent intervals. Android
may throttle or reject any request; a rejected or cached scan never rearms a
rule. The UI therefore describes scanning as best effort rather than promising
an exact schedule.

GPS-circle and distance rules use a visible location foreground service because
they must work while the Activity is closed. The service runs only while at
least one enabled location rule requires it. Distance rules request higher
frequency updates and have higher battery use; GPS circles use slower updates.
Android may prevent a foreground service from starting from a background state,
especially after reboot. In that case Diagnostics asks the user to open OnMyWay.

## Accuracy and privacy limits

GPS radius, movement, and altitude are not surveying measurements. The trigger
engine ignores unusable locations, treats GPS-circle samples overlapping the
configured boundary as uncertain, and subtracts reported uncertainty from
movement before firing. Elevation contributes only when both altitude samples
have reasonable vertical accuracy. These choices favor a late reminder over a
false notification from stationary sensor noise.

OnMyWay does not store continuous location or Wi-Fi observations. It persists
only event configuration, edge/rearm state, a distance baseline where required,
and bounded trigger history.

## Release workflow

Pushing a `v*` or `V*` tag runs `.forgejo/workflows/release-apk.yml`. CI requires
the Android keystore secrets plus `CI_KEY` and `CI_KEY_PASSPHRASE`, verifies the
pinned OpenPGP fingerprint, builds the signed APK, generates and verifies an
armored `.asc`, and publishes both assets idempotently to Forgejo. Optional
GitHub mirroring defaults to `firebadnofire/OnMyWay`.
