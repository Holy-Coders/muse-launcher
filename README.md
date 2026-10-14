# Muse Launcher

An open source Android companion for your own Muse. Put a spare phone on your
desk or in your pocket: tap or hold your avatar to talk, share photos or short videos, and let a
small animated companion present replies and notifications.

**Community preview, 0.7.0.** Android 11+ on a 64-bit arm64 or x86_64 device.
Jelly Star on Android 13 has device evidence; other phones need testing. No root
is required for screen controls, pairing, camera or optional Home-screen use.

[Download community preview](https://github.com/Holy-Coders/muse-launcher/releases) ·
[Install and pair](docs/INSTALL.md) · [Compatibility](docs/COMPATIBILITY.md) ·
[Privacy](docs/PRIVACY.md) · [Contribute](CONTRIBUTING.md)

## What works

- Tap the avatar once to record and again to send, or hold it and release to send.
  Double tap opens the camera. A double tap does not start the microphone. Fast offline English captions
  show words while recording. Muse receives the original audio.
- Take a photo or hold the camera's video control to record. The attachment goes
  with your next voice question, or send the video with its own audio. No
  automatic description request is added.
- One persistent Muse conversation, reply bubbles you can swipe away, and
  optional Android read-aloud.
- Optional notification access: local source-labeled bubbles, an unread count,
  swipe dismissal and Clear all.
- Optional hardware-button calibration on phones that expose an extra key.
  The firmware-bound physical test gate is preserved.

## Phones and the red button

Muse Launcher does not require a Jelly Star or a red button. It targets Android 11+ with
64-bit Android (arm64 or x86_64); 32-bit systems are not supported. Normal phones
use the avatar and camera controls. Camera and BLE advertising support vary.

On the Jelly Star, the extra red button can be physically calibrated in Setup.
Other extra keys may work if their press and release reach Muse Launcher while it is open.
Short press wakes, double tap opens camera, hold records voice and release sends;
in camera, a short tap takes a photo and hold/release records/stops video. Volume,
power, Home and Back keys are excluded from calibration. No key is armed by
installation. Locked/background activation is a separate device-specific capability
and is not universally supported or certified. See [hardware buttons](docs/BUTTONS.md).

## Connect Muse

Use **guided setup** on first launch. Each person supplies their own personal
SDK token and pairs from Muse on a **second phone**. No shared developer token,
backend account or credentials ship in the app. A correlated reply test verifies
connection before setup marks it successful. Same-phone Bluetooth pairing has
not been established.

Muse's source SDK is Apache-2.0, but service tokens have separate
[personal-use terms](https://gadgets.muse.ai/sdk-terms). Token-bearing public
products are restricted; we have not established permission for a public app-store
release with user-supplied tokens. This credential-free community preview lets each user build or install the app
and supply their own token. Public app-store/service distribution still needs
clarification from Meta; this is not an approved commercial integration. Muse Launcher is independent
and is not made by or endorsed by Meta.

## Build

Install JDK 21, Python 3.11, Android SDK Platform 36 / Build Tools 36.0.0, and set
`sdk.dir` in your local `local.properties` (or set `ANDROID_HOME`).

```sh
python3.11 scripts/fetch-models.py
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk`. A Mac helper is available:
`./scripts/gradle-mac.sh`. The optional large English caption model is fetched
with `--enhanced` and bundled only in debug builds. Release uses the fast model.

[Release process](docs/RELEASING.md) explains signing, source exports and checks.
CI builds and checks source without credentials; live Muse tests are opt-in.

## Limits

Locked/suspended hardware activation is device-specific and not certified.
Setting Muse Launcher as Home does not turn an ordinary phone into an enforced kiosk.
No reset, rooting, device-owner enrollment or navigation restriction is needed
for the normal setup. Calls and message drafts open the user's phone apps;
those apps own the final Call/Send. Native Muse push delivery remains unverified;
notification presentation uses Android's optional local notification listener.
Video is SD and capped at 8 MB. Captions are English and read-aloud uses Android
voices, not a Muse voice API. Custom Muse avatars are animated locally with procedural breathing, bobbing,
tilting, speaking motion and a notification look-up pose. Original artwork is
kept intact; no paid image service or developer account is required. Generic
Muse profiles use the built-in animated companion. Profile export remains
service-dependent; a missing export keeps the existing/fallback companion.

Notifications place your avatar at the bottom-left, looking toward the bubbles
above it. Gesture instructions live in the **Info** pop-up, keeping Home minimal.
Standalone video sends its own audio through a locally extracted WAV alongside
the MP4 when present; video with a separate voice question uses that recording.
No describe-video instruction is inserted.

## Developer mode

Open **Settings → Developer mode** for hardware diagnostics, Android settings,
optional server configuration and appliance maintenance. Ordinary use needs none
of those controls. This is separate from Muse's own Developer mode, which you
still enable in Muse on the pairing phone. Tap gestures are the primary controls;
a verified hardware key remains optional.

## License

Muse Launcher's original code: Apache-2.0. Third-party notices are in `third_party/`.
Personal avatars and Muse's Jollybot artwork are not distributed. The source
export excludes private development files, recordings and account history.
