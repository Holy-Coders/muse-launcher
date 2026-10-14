# Contributing

Use JDK 21, Python 3.11, SDK 36 and the pinned Gradle wrapper. Fetch the caption
model with `scripts/fetch-models.py`. Keep the Kotlin UI/domain, durable journal,
restricted Python Muse adapter and firmware-bound physical button gate intact.

Run core tests, Python protocol tests and Android build/lint checks. Live Muse,
camera, microphone and hardware tests are opt-in; use generated fixtures when
uploading to Muse. Synthetic key or gesture tests do not certify physical or
locked-device behavior. Report Android version, architecture, phone model and
whether the failure happened in the foreground or while locked.

Keep tokens, private avatars, phone numbers, recordings, screenshots, local
configuration, keys and build outputs out of contributions. Respect upstream
licenses. Contributions are provided under this project's Apache-2.0 license.
