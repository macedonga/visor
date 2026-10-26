# Visor

Android client for Meta's glasses. The phone speaks the glasses' protocol over Bluetooth (LE L2CAP,
RFCOMM) and Wi-Fi Direct. It talks to Meta to log in and take ownership during setup, and to fetch
the frame style and language packs. Works on real glasses as of 2026-10-05.

## Modules

- `core/protocol`: wire format, ciphers, handshake messages. Plain JVM.
- `core/transport`: LE L2CAP, RFCOMM and TCP byte pipes, and the wire log.
- `core/session`: the link: handshake, channel mux, reconnect ladder.
- `core/identity`: owner key store, svc36 trust, Meta login (`token/`), change owner (`provision/`).
- `core/connection`: `GlassesConnection` and `GlassesState`, svc100 canary, svc28 device status.
- `core/media`: airship (svc20) file receiver, the svc18 capture list, and `GlassesConfig` (MobileConfig flags
  read from a bug report, written back as mctables on airship `mobileconfig`).
- `core/settings`: glasses settings over svc31 and RLDrive (svc33), the catalogue `settings_ui.json`, contacts.
- `core/assistant`: plays Meta's server on svc15 (ev57 in, VARM out) with the user's own model.
- `core/design`: colours and type.
- `app`: `GlassesService`, the screens (`VisorNav`), the event log, setup, media import.

## Working on it

- `reference/lensbreak`, a snapshot of the desktop Python client, already does most of what
  this app needs. Look there first; the protocol write-ups are on the GitHub wiki.
- The glasses are the test. Builds get tried on hardware, so there are no test suites.
- Keep it small: short comments, no planning documents.
- No backend, analytics or crash reporting.

## Protocol traps

- EndLinkSetup UUIDs must be RFC 4122 v4: `(b[6] & 0xf0) == 0x40`, `(b[8] & 0xc0) == 0x80`.
  Otherwise the glasses tear the channel down silently.
- Only the opening frame carries service id and message type. Continuations report 0 and are padded.
- The channel registry keys on the originator bit (high bit of the channel id).
- Framing is `4 + length`, not `header_len + length`.
- Trust is signed with `NONEwithECDSA` over a prehashed digest, coordinates fixed at 32 bytes,
  `s` low-half. Any mistake shows up as `0x1043 INVALID_APP_IDENTITY`.
- Airship: accepting a file (or refusing it) must end with a drop on type 7, or the glasses'
  send queue stalls behind it.
- DataX OK on the wire is status 0. 0x1000 is svc36's own result; sent as OK, it made the Display's
  image loader retry every image.
- Replies on a channel the glasses opened go on `DataXConnection.mirror`. Replies on a channel Visor
  opened keep the originator bit.

Display:

- svc28 device state comes wrapped in HNDeviceState `{1: stateCommon}`. Unwrapped as a plain
  DeviceState, the battery reads 0%.
- svc41108: the Display takes the phone's internet state only on a channel the phone opens. Until
  then its apps stay off the phone's internet.
- svc26 must be opened with an empty type 3 on every connection, or every share fails ("No remote
  channel at identifier: MWA").
- svc70 folder names are lowercase. With "SENT" a sent text reloads as still sending.
- A text notification needs app id `com.android.sms`, provider 3 and the SMS thread id, or the
  Messages app drops it.
- Captions and translation turn on only through a svc29 pack install and FEATURE_CHANGE. The same
  pack on airship `language_resource` turns nothing on.
- Each Meta `/login` exchange ends the older sessions. Keep one token per run.
- With an Android phone the music screen ignores AVRCP cover art. The cover comes only over svc9012.
- The Phone app's recent calls wait on svc70 type 11, once per provider. svc11006 does not fill them.

## Build

JDK from Android Studio, SDK `platforms/android-37.0`. Versions only in `gradle/libs.versions.toml`.

    ./gradlew :app:assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Releases: push a tag `vX.Y.Z`. `.github/workflows/release.yml` builds the APK, signs it with the key in the repo
secrets and publishes it. Run it by hand for a draft. A release key never goes in the repo.

Emulator: the `medium_phone` AVD. Close it when done. Debug builds write every Bluetooth frame
to `files/wire.log`: `adb exec-out run-as com.macedonga.visor cat files/wire.log` (`adb shell` mangles binary).
