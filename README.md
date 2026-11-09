<p align="center">
  <img src="docs/banner.png" alt="Visor: a de-metaified Meta Glasses client" width="100%">
</p>

<p align="center">
  <b>An Android app for Meta glasses that replaces the Meta AI app.</b><br>
  Your photos stay on your phone, "Hey Meta" answers with the model you choose, and your apps use the glasses too.
</p>

<p align="center">
  <a href="https://github.com/macedonga/visor/releases/latest"><img src="https://img.shields.io/github/v/release/macedonga/visor?style=for-the-badge&label=Download&color=FFD2A1&labelColor=161617&v=1" alt="Download the latest release"></a>
  <a href="https://github.com/macedonga/visor/discussions"><img src="https://img.shields.io/badge/Discussions-ask%20and%20share-FFD2A1?style=for-the-badge&labelColor=161617" alt="Discussions"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-AGPL--3.0-FFD2A1?style=for-the-badge&labelColor=161617" alt="License: AGPL-3.0"></a>
</p>

<p align="center">
  <a href="https://visor.macedonga.com">Website</a> |
  <a href="#what-it-does">Features</a> |
  <a href="#apps-for-your-glasses">Apps</a> |
  <a href="#install">Install</a> |
  <a href="#what-it-sends-and-where">Privacy</a> |
  <a href="#good-to-know">Good to know</a> |
  <a href="#build-from-source">Build</a> |
  <a href="#get-involved">Get involved</a>
</p>

Visor works with Ray-Ban Meta, Oakley Meta and the other Meta glasses. It speaks the glasses' own protocol over Bluetooth and Wi-Fi Direct.

> [!NOTE]
> Visor is an independent project. It is not affiliated with, endorsed by or sponsored by Meta Platforms or EssilorLuxottica.

## What it does

| Feature | What it does |
|---|---|
| **Home** | A 3D model of your pair, lit by a light that shows their state: connected, charging, asleep, recording, out of reach. Under it: both batteries, free space, volume, temperature and your latest photos. |
| **Gallery** | Imports photos and videos over Wi-Fi Direct, on its own while the glasses charge or when you ask. Live photos become Motion Photos. Files go to `Pictures/Visor` and `Movies/Visor`. |
| **Live view** | The glasses' camera on your phone, with recording, an instant replay of the last minutes, snapshots, RTMP broadcast and Live AI. |
| **Assistant** | "Hey Meta" goes to your own model: Claude, or any OpenAI-compatible endpoint. The glasses read the answer in their own voice. A diary on the phone keeps every conversation, and you can continue one from the app. |
| **Notifications** | Message notifications go to the glasses, which announce the sender by the name in your contacts. |
| **Settings** | Every setting the glasses have, the way the Meta app shows them. Advanced holds the rest, assistant language packs and the firmware's feature flags. |
| **Outside the app** | A home screen widget, a Quick Settings tile, launcher shortcuts, and a Live Update notification while the Live view runs. |
| **More than one pair** | Keep several pairs on one phone. Home swipes between them, each with its name, batteries and a Connect button, and ends with a page to set up another. |
| **Meta Ray-Ban Display** | A teleprompter, live captions and translation, the Neural Band's gestures, the Briefing panel with your apps' own cards, and saved Wi-Fi networks. The glasses' own apps reach the internet through the phone, with Instagram's Reels and messages among them. Web apps get the phone's location, motion sensors and nearby Bluetooth devices, and can give your assistant tools. |
| **Neural Band** | Set up from the Display's settings, with its battery on Home and its wrist in Settings. Share it with the phone, and apps buzz it at once and read its gestures and motion while the Display keeps using it. |

## Apps for your glasses

Visor is also where your apps meet the glasses. Through the [Visor Development Kit](https://visor.macedonga.com/developers/)
(VDK), apps on your phone, programs and AI agents on your computer, and web apps on the Display use the camera and
captures, the Display's layouts and Briefing cards, the Neural Band's gestures and motion, voice, the assistant's
tools and the glasses' settings. No Meta account, no Meta AI app, no developer review.

| To | Do this |
|---|---|
| **Use an app** | It asks for what it needs the first time, on Visor's consent screen. **Settings, Apps** lists every app, program and web app with access, and takes any of it back. |
| **Connect a program or an AI agent** | In **Settings, Apps**, turn on **Let programs connect** and add the program, with its own token. Agents get the glasses over MCP. |
| **Build your own** | For Android, Python, TypeScript, or a web app on the Display: start with the [getting started guide](https://visor.macedonga.com/developers/docs/getting-started/). `visor fake` stands in for the glasses while you build. |
| **Automate** | Automation apps such as Tasker take photos and videos and hear about new captures through [Developer mode](https://visor.macedonga.com/developers/docs/developer-mode/)'s intents. |

## Requirements

- **Glasses:** a pair of Meta glasses, such as Ray-Ban Meta, Oakley Meta or the Meta Ray-Ban Display. Support for the Display and its Neural Band is newer and does not yet cover everything the Meta AI app does.
- **Phone:** Android 12 or later.
- **Meta account**, for setup. Log in with a password or an email code. Passkeys do not work.
- **For the assistant:** an API key from Anthropic or from an OpenAI-compatible service.

## Install

1. Download `visor-<version>.apk` from the [latest release](https://github.com/macedonga/visor/releases/latest).
2. Compare its SHA-256 with the `.sha256` file next to it.
3. Install the APK. Android asks you to allow installs from your browser or file manager.
4. Open Visor and follow the setup: permissions, Meta login, pairing, then pick your glasses.

> [!TIP]
> To put the glasses in pairing mode, fold both arms and hold the capture button for about 5 seconds. The LED inside the frame blinks blue.

<details>
<summary>Release signing certificate</summary>

```
CN=Visor
SHA-256: F6:01:C7:FE:63:EF:04:E0:7F:66:8A:EC:BA:5A:2B:9C:23:90:C4:C0:22:B5:2B:5F:B0:F6:D3:04:22:7C:B9:C7
```

</details>

## What it sends, and where

Visor has no server of its own, no analytics and no crash reporting. It connects only to these:

| To | When | What for |
|---|---|---|
| Meta (`accounts.meta.com`, `api.accounts.meta.com`, `meta.graph.meta.com`, `ar.graph.meta.com`, `graph.facebook-hardware.com`) | During setup | The login, and the ownership receipt that makes Visor the owner app of your glasses |
| Meta (`ar-genai.graph.meta.com`) | When it needs to | Your pair's frame style and lens colour, assistant language packs, and the Display's weather, sports and stocks |
| Meta (`ar.graph.meta.com`, `graph.facebook.com`, `i.instagram.com`) | When you link a Facebook, Instagram or Threads account to the glasses | That account's tokens and ids, which Visor hands to the glasses |
| Your model provider | When you ask the assistant | Your question, with your own API key |
| Your RTMP server | When you broadcast from the Live view | The broadcast |
| OpenStreetMap (`nominatim.openstreetmap.org`, `routing.openstreetmap.de`) | When Maps on the Display searches or plans a route | The search with your position, and the route's start and end |
| GitHub (`api.github.com`) | Twice a day | The latest release, to tell you about a new version. Turn it off in Settings, About. |

Everything else stays between the phone and the glasses.

## Good to know

> [!IMPORTANT]
> The glasses accept one session at a time. If the Meta AI app connects to them, Visor's session ends, and the other way round.

- **Your own builds and releases do not mix.** A build you made has a different signing key from a release, so uninstall one before you install the other. Uninstalling removes the login and the glasses' keys from the phone, and you set up again.
- **The first "Hey Meta" can go unheard.** For English answers in the glasses' own voice, Visor sometimes turns off a Meta setting and restarts the glasses' assistant when it connects.
- **The assistant is as good as your model.** Visor sends it the question, the recent conversation and, when you ask about what you see, the photo the glasses take. In a partner language, questions about what you see need a model with tool calls and images.
- **Partner-language camera commands** take a photo, record a video or hyperlapse, and stop recording. Photos need "Voice photos" on.

## Build from source

You need JDK 21 (the one bundled with Android Studio works) and the Android SDK with platform 37.

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> [!WARNING]
> Setup logs in to Meta with the Meta AI app's own client tokens, and they are not in this repository. Without them Visor builds and runs, but the Meta login and the ownership step of setup fail.

<details>
<summary>Adding the Meta client tokens</summary>

Extract the tokens from the official Meta AI app, where they are the strings that start with `HW|`, `AR|` and `FRL|`. Add them to `local.properties` before you build:

```
VISOR_META_HW_TOKEN=HW|...
VISOR_META_AR_TOKEN=AR|...
VISOR_META_FRL_TOKEN=FRL|...
```

Release builds in CI read them from the repository secrets with the same names.

</details>

<details>
<summary>Debug builds and releases</summary>

Debug builds write every frame to `files/wire.log` on the phone:

```
adb exec-out run-as com.macedonga.visor cat files/wire.log
```

They also have a broadcast receiver that drives the app from adb (`app/src/debug`). Release builds have neither.

A tag like `v1.2.3` builds the signed release APK in CI and publishes it as a GitHub release (`.github/workflows/release.yml`).

</details>

<details>
<summary>How the code is laid out</summary>

| Module | What it holds |
|---|---|
| `core/protocol` | Wire format, ciphers, handshake messages. Plain JVM. |
| `core/transport` | The LE L2CAP, RFCOMM and TCP byte pipes, and the wire log. |
| `core/session` | The link: handshake, channel mux, reconnect ladder. |
| `core/identity` | Owner key store, trust, Meta login, change of owner, language packs. |
| `core/connection` | `GlassesConnection` and `GlassesState`, device status. |
| `core/media` | Media import, the Live view, the glasses' MobileConfig. |
| `core/settings` | Glasses settings and contacts. |
| `core/assistant` | Plays Meta's assistant server on the glasses' side, with your model. |
| `core/design` | Colours and type. |
| `app` | The service, the screens, setup, the widget and the tile. |

[CLAUDE.md](CLAUDE.md) lists the protocol traps that cost the most time to find. The protocol itself, down to the bytes, is documented at
[visor.macedonga.com/protocol](https://visor.macedonga.com/protocol/).

</details>

## Get involved

| You have | Go to |
|---|---|
| A question, an idea, or a report of how Visor works on your pair | [Discussions](https://github.com/macedonga/visor/discussions) |
| A bug | [Issues](https://github.com/macedonga/visor/issues/new/choose), with the event log from Settings, Advanced, Event log |
| An accessibility problem | The accessibility form in [Issues](https://github.com/macedonga/visor/issues/new/choose). [ACCESSIBILITY.md](ACCESSIBILITY.md) says what works and what does not yet. |
| A security problem | A private report, as [SECURITY.md](SECURITY.md) explains. Never a public issue. |
| Code to contribute | [CONTRIBUTING.md](CONTRIBUTING.md) first. There are no test suites: changes are tried on real glasses, and a pull request says what was tried. |

## License

Visor is free software under the [GNU Affero General Public License v3.0](LICENSE).

Meta, Ray-Ban, Oakley, Ray-Ban Meta and Oakley Meta are trademarks of their owners. Visor uses the names only to say which glasses it works with.
