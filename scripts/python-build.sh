#!/bin/sh
set -eu
# Homebrew's current Python bottles may need its Expat instead of macOS's older copy.
if [ -d /opt/homebrew/opt/expat/lib ]; then
    export DYLD_LIBRARY_PATH="/opt/homebrew/opt/expat/lib${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}"
fi
exec "${PUCK_BUILD_PYTHON:-python3.11}" "$@"
