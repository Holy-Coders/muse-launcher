# Compatibility

| Capability | Requirement / evidence |
|---|---|
| Install | Android 11 / API 30+, arm64-v8a or x86_64. 32-bit-only phones unsupported. |
| Avatar gestures | Microphone permission; no special hardware mapping or root. |
| Photos/video | Camera hardware and permission; CameraX capability varies. SD MP4, 8 MB cap. |
| Muse pairing | Personal token, internet, BLE advertising and a second signed-in Muse phone for initial setup only. |
| Home | Optional standard Android Home selection; ordinary app use also supported. |
| Hardware hold | An observable extra key, three physical press/release pairs, unchanged firmware. |
| Locked hardware | Not universally supported or certified; no automatic PIN bypass or rooting. |
| Notifications | Optional Android notification-listener access; ongoing/protected alerts excluded. Optional tray mirroring occurs after posting. |
| Phone actions | Installed supported handlers; Call/Send remain with the destination app. |
| Captions | Bundled offline English model; captions are an untrusted preview. |
| Read-aloud | Installed Android TTS voices; no verified Muse voice API. |

The Jelly Star / Android 13 has physical-device evidence for voice, photo/video,
notification UI and an earlier physical extra-key mapping. Those results do not
certify other manufacturers, Android versions, screen-off operation or camera
configurations. Clean onboarding and touch-control checks are distinct from live
pairing and must be reported separately. The repository includes opt-in live and
physical-device tests; CI must not run them using shared account credentials.

The Android package remains `dev.puck.launcher` so existing personal earlier installs
can update without losing pairing or media when the signing key matches. Public
release uses one Home entry, named Muse Launcher; legacy launcher code is retained
for development/maintenance. Debug and release signing keys differ.
