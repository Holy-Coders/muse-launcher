#!/usr/bin/env python3
"""Fetch pinned caption assets; optional large model goes in debug builds only."""
import argparse
import hashlib
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
MODELS = [
    ("app/src/main/assets", "vosk-model-small-en-us-0.15", "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"),
    ("app/src/debug/assets", "vosk-model-en-us-0.22-lgraph", "d9838b4aaa82a75c4a17f5aca300eaca129aaab2a7cbf951bafbb500eb9c4334"),
]
def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--enhanced", action="store_true")
    args = parser.parse_args()
    for folder, name, expected in MODELS[:2 if args.enhanced else 1]:
        target = ROOT / folder / (name + ".zip")
        if target.exists() and digest(target) == expected:
            print(name + ": verified"); continue
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_suffix(".download")
        try:
            with urllib.request.urlopen("https://alphacephei.com/vosk/models/" + name + ".zip", timeout=60) as response, temporary.open("wb") as out:
                while chunk := response.read(65536):
                    out.write(chunk)
            if digest(temporary) != expected:
                raise ValueError("Model checksum mismatch: " + name)
            temporary.replace(target)
            print(name + ": downloaded and verified")
        finally:
            temporary.unlink(missing_ok=True)
if __name__ == "__main__":
    main()
