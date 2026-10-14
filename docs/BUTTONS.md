# Hardware buttons

The red button belongs to the Jelly Star; Android phones do not all have one.
Muse Launcher uses screen controls on phones without a verified extra key. Installation
never assigns a hardware key, requests root or changes volume/power behavior.

## Extra-key setup

1. Open Muse Launcher, then tap Settings for Setup, then enable Developer mode.
2. If the phone has OEM shortcut/PTT settings, configure the extra button so
   Muse Launcher can receive actual press/release events while in the foreground. Merely
   launching Muse Launcher from a shortcut may not deliver those events. OEM settings
   and support differ; there is no universal Android mapping instruction.
3. Tap Observe physical button, then physically press/release the extra key
   three times. Save only when the observed code and pairs correspond to that key.
4. Check short wake, double-tap camera, hold voice and release-to-send. In camera,
   check short photo and hold/release video separately. Test interruption too.

Muse Launcher excludes Android Home, Back, volume, power, Menu and Recents keys from
calibration, and ignores printable keys. The saved mapping is bound to the
current firmware fingerprint. A firmware change disables it until physically
revalidated; no key code is copied from the maintainer's Jelly to another phone.
Synthetic input tests do not certify hardware behavior.

## Locked, background and sleeping phones

Foreground key delivery does not establish locked or suspended operation. Stock
Android may not deliver the extra key to Muse Launcher when it is not foreground. Muse Launcher
cannot dismiss an existing PIN or promise a universal power-button replacement.
Advanced root/device-owner experiments are separate and are not installed by
ordinary setup. The root helper's locked/suspend operation remains unverified.

If calibration cannot observe both edges, use the screen hold control. Do not
install random accessibility remappers or root a phone just to make an uncertain
button profile work. Notification access and Home selection are separate optional
features; neither proves background hardware key delivery.
