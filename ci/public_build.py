"""Build a community extension using only its public pinned binary SDK."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile


def sdk(root, override=None):
    lock = json.loads((root / "sdk.lock.json").read_text("utf-8"))
    destination = root / "build/public-sdk" / lock["version"]
    if destination.exists():
        manifest = json.loads((destination / "sdk.json").read_text("utf-8"))
        if manifest["version"] != lock["version"]:
            raise ValueError("Cached SDK version differs from its lock")
        for entry in manifest["files"]:
            path = destination / entry["path"]
            if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
                raise ValueError("Cached public SDK was modified; remove only build/public-sdk and retry")
        return destination
    with tempfile.TemporaryDirectory(prefix="devline-public-sdk-") as temporary:
        archive = Path(temporary) / "sdk.zip"
        if override:
            shutil.copyfile(override, archive)
        else:
            if not lock["url"].startswith("https://github.com/devline-ide/devline-extension-sdk/releases/download/"):
                raise ValueError("Unexpected SDK download origin")
            with urllib.request.urlopen(lock["url"], timeout=120) as response, archive.open("wb") as stream:
                shutil.copyfileobj(response, stream)
        if archive.stat().st_size != lock["size"] or hashlib.sha256(archive.read_bytes()).hexdigest() != lock["sha256"]:
            raise ValueError("Public SDK archive checksum mismatch")
        with zipfile.ZipFile(archive) as source:
            if sum(item.file_size for item in source.infolist()) > 1024 * 1024 * 1024:
                raise ValueError("Public SDK exceeds extraction limit")
            for item in source.infolist():
                path = Path(item.filename)
                if path.is_absolute() or ".." in path.parts or "\\" in item.filename or ":" in item.filename or item.external_attr >> 16 & 0o170000 == 0o120000:
                    raise ValueError("Unsafe public SDK archive member")
            destination.mkdir(parents=True)
            source.extractall(destination)
    return sdk(root)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk-archive", type=Path, help="Optional exact local copy; the committed hash still applies")
    parser.add_argument("tasks", nargs="*", default=["extensionArchive", "check"])
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    home = sdk(root, args.sdk_archive)
    wrapper = ["cmd", "/d", "/c", "gradlew.bat"] if os.name == "nt" else ["sh", "gradlew"]
    command = wrapper + ["-p", "standalone", "-PdevlineSdkDir=" + str(home), "--no-daemon", "--max-workers=2"] + args.tasks
    subprocess.run(command, cwd=root, check=True)
    if "extensionArchive" in args.tasks:
        archives = list((root / "standalone/build/extension").glob("*.dlextension"))
        if len(archives) != 1:
            raise ValueError("Expected exactly one community archive")
        java = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) if os.environ.get("JAVA_HOME") else "java"
        subprocess.run([java, "-Djava.awt.headless=true", "-cp", str(home / "lib/*"), str(root / "ci/ArchiveCheck.java"), str(archives[0])], cwd=root, check=True)


if __name__ == "__main__": main()
