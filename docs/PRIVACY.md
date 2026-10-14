# Muse Launcher data use

Muse Launcher is a community Android app. It has no developer analytics, tracking SDK or
mandatory developer-hosted service. Normal Muse mode connects to your own Muse
account; Muse's own privacy and service terms apply there.

- A single avatar tap starts voice recording; a second tap submits it. Holding
  the avatar records until release, which submits it. A double tap opens the
  camera without starting voice capture. Leaving the app cancels voice capture.
  Recordings time out after 60 seconds without submitting. Original audio and any
  attached photo/video go to Muse. Captions run locally and do not execute commands.
- Photos and videos are captured only after your input and saved in private app
  storage. Photos/voice files are removed after confirmed successful turns. Saved
  MP4s remain until app data is removed; Dismiss hides a video and removes its
  pending attachment, rather than deleting the saved MP4.
- Replies, turn-state journals, caption models and optional personal avatars
  remain in private app storage. Personal avatar linking requests your own Muse
  identity; it may download the returned avatar through HTTPS. Custom animation
  frames are generated on this phone and are never sent to a developer service.
  A generic profile uses the built-in animation.
- Notification access is optional. Ordinary notification previews are stored in
  a bounded private inbox. They are not sent to Muse or interpreted as commands.
  Swipe/Clear all also dismiss clearable source notifications on the phone.
  Optional tray mirroring automatically dismisses ordinary clearable Android
  alerts after saving their private bubbles. Calls, alarms and ongoing alerts
  remain. Retained bubbles survive source cancellation and listener reconnect,
  until you swipe/Clear all or clear local app data. This is off by default.
- SDK and device credentials are encrypted with Android Keystore; Android backup
  is disabled. The maintainer receives no copy. Forget removes local connection
  secrets; remove Muse Launcher in Muse Devices for server-side revocation.
- Read-aloud defaults to installed offline Android voices. Opting into an online
  Android voice sends reply text to that voice provider. Automatic read-aloud is
  separately optional.
- Microphone/camera, Nearby devices and notification permissions are requested
  through Android. Bluetooth pairing temporarily changes the phone's Bluetooth
  name, restores it on closing, and lasts at most ten minutes.
- Optional navigation guard reads foreground package names. It does not read
  accessibility node trees, screen text, microphone input or hardware keys.
- Advanced optional HTTPS-server mode sends submitted turns to the configured
  operator. It is not needed for Muse and changes who receives your recordings.

Muse Launcher cannot remove prompts already submitted to Muse. To clear all local Muse Launcher
content, use Android → App info → Storage → Clear data, or uninstall. Do not send
credentials, personal screenshots or recordings with bug reports.

When sending a video without a separate voice question, Muse Launcher decodes
its embedded audio locally and sends a bounded mono WAV alongside the MP4. It
uses no microphone for this extraction and keeps no extra audio file. This makes
the video's own spoken input available through the voice attachment path.
