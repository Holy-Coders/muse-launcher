# Release process

A prepared APK is not an app-store listing, an approved Muse integration or proof
of compatibility with every Android phone. The public artifact is a credential-free, personal-use community preview. Resolve
the service-access question with Meta before an app-store/commercial product. Each user
must supply their own token; never bundle a maintainer token or personal avatar.

1. Install JDK 21, Python 3.11 and SDK 36 / Build Tools 36.0.0. Fetch the pinned
   model using `python3.11 scripts/fetch-models.py`.
2. Run core and Python protocol tests; build and lint debug/release. Run clean
   first-launch, permission-denial, touch hold/release/cancel and media tests on
   supported devices. Test live pairing with a separate personal account and
   generated fixtures. Preserve the physical-key gate; never certify injected keys.
3. Build `./gradlew :app:assembleRelease`. The output is unsigned. Keep a stable
   maintainer signing key outside this repo, backed up securely. Set `ANDROID_HOME`,
   `PUCK_KEYSTORE`, `PUCK_KEY_ALIAS`, `PUCK_KEY_PASSWORD_FILE`, then run:

   ```sh
   ./scripts/sign-release.sh app/build/outputs/apk/release/app-release-unsigned.apk dist/muse-launcher-preview.apk
   ```

   Do not mix debug and release keys: Android rejects an in-place update signed
   with a different key. Never uninstall a paired personal build just to work
   around this; it would delete account credentials and recordings.
4. Export with `python3.11 scripts/source-release.py dist/muse-launcher-source.zip`.
   This uses a file allowlist, blocks embedded token/private-key patterns, and
   excludes model ZIPs, build output, local.properties, signing files, private
   avatars, development evidence and personal history. Models download through
   the checked-in checksum script. Inspect the archive before publication.
5. Verify APK signatures, hashes, included license notices, permissions and source
   archive rebuild. Publish only credential-free source/APK previews with their limitations and
   token terms clearly stated. App-store/service integration approval is separate.
   CI exports review artifacts but does not sign or publish them.

## Public Muse integration question

Primary sources reviewed 2026-10-14:
[SDK license](https://github.com/facebookincubator/muse-gadget-sdk),
[token terms](https://gadgets.muse.ai/sdk-terms).

Section 7 separates open-source rights from service-token access. Section 2
restricts token-bearing publicly offered devices and requires written permission
for other distribution. We have not established whether a credential-free Android
APK, with each user entering a personal token, is an authorized public integration.
Ask Meta to clarify that exact flow; do not present our interpretation as approval.
No permission request has been sent by this project.
