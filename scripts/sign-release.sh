#!/bin/sh
set -eu
# Keep the same maintainer key for every update. All credentials stay outside source.
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
: "${PUCK_KEYSTORE:?Set PUCK_KEYSTORE to your private keystore}"
: "${PUCK_KEY_ALIAS:?Set PUCK_KEY_ALIAS}"
: "${PUCK_KEY_PASSWORD_FILE:?Set PUCK_KEY_PASSWORD_FILE to a private password file}"
input="${1:?Unsigned APK path required}"
output="${2:?Output APK path required}"
tools="$ANDROID_HOME/build-tools/36.0.0"
"$tools/zipalign" -f -p 4 "$input" "$output"
"$tools/apksigner" sign --ks "$PUCK_KEYSTORE" --ks-key-alias "$PUCK_KEY_ALIAS" \
    --ks-pass "file:$PUCK_KEY_PASSWORD_FILE" "$output"
"$tools/apksigner" verify --verbose "$output"
