#!/usr/bin/env python3
"""Allowlist-only source export, excluding personal history, assets and build output."""
import argparse
import hashlib
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parent.parent
TOP = {"LICENSE", "NOTICE", ".gitignore", "CONTRIBUTING.md", "SECURITY.md", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat"}
PUBLIC_DOCS = {"INSTALL.md", "PRIVACY.md", "RELEASING.md", "COMPATIBILITY.md", "BUTTONS.md"}
SKIP_PARTS = {"build", "__pycache__", ".pytest_cache", "assets"}
SCRIPT_NAMES = {"python-build.sh", "gradle-mac.sh", "fetch-models.py", "source-release.py", "sign-release.sh"}

def exported_files():
    files = {Path(name) for name in TOP if (ROOT / name).is_file()}
    for directory in ("app", "core", "gradle", "third_party", "tests", ".github"):
        for path in (ROOT / directory).rglob("*"):
            relative = path.relative_to(ROOT)
            if path.is_file() and not (set(relative.parts) & SKIP_PARTS) and path.suffix not in {".pyc", ".png", ".jpg", ".mp4", ".apk", ".zip"}:
                files.add(relative)
    files.add(Path("app/src/main/assets/OPEN_SOURCE_NOTICES.txt"))
    files |= {Path("appliance/licenses/README.md"), Path("appliance/licenses/vosk-Apache-2.0.txt")}
    files |= {Path("scripts") / name for name in SCRIPT_NAMES}
    files |= {Path("docs") / name for name in PUBLIC_DOCS}
    return sorted(files)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    entries = [(Path("README.md"), ROOT / "README.md")]
    entries += [(path, ROOT / path) for path in exported_files()]
    # No embedded service tokens, PEM private keys or Android keystores.
    forbidden = re.compile(rb"mgst_[A-Za-z0-9_-]{16,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")
    for name, path in entries:
        if not path.is_file(): raise ValueError("Missing release source: " + str(name))
        if forbidden.search(path.read_bytes()): raise ValueError("Credential-like content in " + str(name))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, path in entries:
            archive.write(path, "muse-launcher/" + name.as_posix())
    print(f"Exported {len(entries)} files; models fetched separately")
    print(hashlib.sha256(args.output.read_bytes()).hexdigest() + "  " + args.output.name)
if __name__ == "__main__": main()
