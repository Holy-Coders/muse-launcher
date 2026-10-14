# Install and pair

1. Download the signed APK from [GitHub Releases](https://github.com/Holy-Coders/muse-launcher/releases),
   or make a personal build from README. Install it through
   Android's normal installer, allowing that source when Android asks. Never
   install an APK carrying somebody else's SDK token or account credentials.
2. Open **Muse Launcher Companion**. Guided setup explains data use before you pair.
3. Open the linked Muse token page, sign into your own account, read the token
   terms, and create a personal token. Paste it into Muse Launcher's password field and
   save. It clears from the field and is encrypted using Android Keystore.
4. Start Bluetooth pairing. Grant Nearby devices permission and enable Bluetooth
   when Android asks. Keep Muse Launcher visible during the ten-minute pairing window.
5. On a second phone, sign into your Muse account. In Muse → Settings → Devices,
   enable Developer mode, add a device, and select the `MuseGadget…` name shown
   in Muse Launcher. Select **Current phone connection**; Muse Launcher already has internet, so
   it does not need a Wi-Fi password. Same-phone discovery is not supported yet.
6. In Muse Launcher tap Connect Muse, then Test my connection. **Muse replied — connected**
   verifies a correlated response in your saved Muse Launcher conversation.
7. Grant microphone/camera permissions if you want those features. Setting Muse Launcher
   as your Home screen is optional. Tap Start Muse Launcher. Tap your avatar once to record and again to send;
   or hold it, speak, and release to send. Double tap opens the camera. Sliding outside the control cancels the recording.

Tap **Settings** above the companion for Setup; **Connect Muse / guided setup** reopens
pairing. The camera has a photo button and hold-to-record video control on phones
without a verified hardware key. Notification access and read-aloud are optional
Setup features. Android's ordinary PIN and other apps remain available.

## Trouble connecting

- No second phone: the current Bluetooth pairing flow requires one. There is
  no verified same-phone or QR pairing route in this Android port.
- Device not found: Bluetooth must be on, Nearby devices permission granted,
  Muse Launcher's pairing window open, and Muse Developer mode enabled on the other phone.
  BLE advertising support is required. Try closing and reopening the pairing window.
- Paired but offline: keep internet available, tap Connect/Reconnect and retest.
  Pairing or an upload acknowledgment alone does not prove reply delivery.
- Permissions denied: grant them in Android's app settings. Denial never starts
  recording. A hardware mapping is optional; The avatar works without one.
- Hardware button: Settings → Developer mode → Observe physical button. Press/release the extra key
  three times and save only the observed mapping. New firmware needs revalidation.
  This does not establish background/locked/suspended button behavior.

To disconnect, use Setup's Disconnect/forget control, then remove Muse Launcher in Muse's
Devices UI to revoke remote access. Uninstall removes Muse Launcher's local app data.
Changing the Android Home app is done through Settings → Default apps → Home.

## Avatar and developer controls

A single tap starts hands-free voice recording after the 350ms double-tap
window; tap again to send. A hold starts after 450ms and sends on release. Double
tap opens camera without recording voice. Dragging away, touch cancellation or
leaving the app cancels recording. The 60-second limit cancels without sending.
The camera still offers its own photo and hold-video controls. Videos can go with
your next recorded question or with their own audio; no describe-video request
is inserted.

Settings → Get my Muse status update requests a check-in. Settings → Developer
mode reveals optional hardware, Android settings and server/appliance controls.
This does not switch on Developer mode in Muse or in Android itself.

Home's **Info** button opens gesture instructions. With notifications present,
your avatar sits bottom-left and looks toward the source-labeled bubbles above.
