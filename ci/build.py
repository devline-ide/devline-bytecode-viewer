"""Build an extracted extension against a pinned IDE checkout on a native worker."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile


def run(command: list[str], cwd: Path) -> None:
    subprocess.run(command, cwd=cwd, check=True)


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


@contextmanager
def machine_build_lock():
    # One JVM/native build at a time across this user's seven repository workers.
    with (Path(tempfile.gettempdir()) / "devline-extension-build.lock").open("a+b") as stream:
        if os.name == "nt":
            import msvcrt
            stream.seek(0)
            stream.write(b"0")
            stream.flush()
            while True:
                try:
                    stream.seek(0)
                    msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                    break
                except OSError:
                    time.sleep(2)
            try:
                yield
            finally:
                stream.seek(0)
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream.fileno(), fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


def native_platform() -> str:
    system = {"Windows": "windows", "Linux": "linux", "Darwin": "macosx"}[platform.system()]
    architecture = {"AMD64": "amd64", "x86_64": "amd64", "aarch64": "arm64", "arm64": "arm64"}[platform.machine()]
    return f"{system}-{architecture}"


def build(repository: Path, ide_override: Path | None, check_only: bool) -> None:
    declaration = json.loads((repository / "extension-build.json").read_text(encoding="utf-8"))
    actual_platform = native_platform()
    if actual_platform not in declaration["buildPlatforms"]:
        raise RuntimeError(f"This extension does not yet support builds on {actual_platform}")
    manifest = json.loads((repository / declaration["manifest"]).read_text(encoding="utf-8"))
    manifest = manifest.get("extension", manifest)
    if manifest["id"] != declaration["id"]:
        raise RuntimeError("Build declaration/manifest ID mismatch")
    tag = os.environ.get("GITHUB_REF", "")
    if tag.startswith("refs/tags/") and tag != "refs/tags/v" + manifest["version"]:
        raise RuntimeError("Release tag does not match the manifest version")
    if check_only:
        print(json.dumps({"id": manifest["id"], "sourceVersion": manifest["version"],
                          "buildPlatform": actual_platform, "distribution": declaration["distribution"]}))
        return
    with machine_build_lock():
        ide = ide_override.resolve(strict=True) if ide_override else repository / "build/ide-source"
        if ide_override is None:
            if ide.exists():
                # Never reset an old worker checkout; callers use a fresh checkout per job.
                raise RuntimeError(f"Build workspace already exists; use a fresh job directory: {ide}")
            run(["git", "clone", "--no-checkout", declaration["ideRepository"], str(ide)], repository)
            run(["git", "checkout", "--detach", declaration["ideCommit"]], ide)
            run(["git", "submodule", "update", "--init", "--recursive"], ide)
            npm = ["cmd", "/d", "/c", "npm"] if os.name == "nt" else ["npm"]
            run(npm + ["ci", "--no-audit", "--no-fund"], ide)
            peers = repository / "build/extension-peers"
            peers.mkdir(parents=True, exist_ok=False)
            sys.path.insert(0, str(ide / "tools/extensions"))
            from workspace import bootstrap, link
            link(peers / declaration["module"], repository)
            # The current extension is the candidate; all its peers stay locked.
            bootstrap(ide, peers, locked=False)
        wrapper = ["cmd", "/d", "/c", "gradlew.bat"] if os.name == "nt" else ["sh", "gradlew"]
        task = "jar" if declaration["distribution"] == "builtin" else "extensionArchive"
        run(wrapper + [f":{declaration['module']}:{task}", "--no-daemon", "--max-workers=2"], ide)
        output = repository / "release-output"
        if output.exists():
            raise RuntimeError("Output exists; use a fresh job rather than overwriting a release candidate")
        output.mkdir()
        artifact_directory = repository / ("build/libs" if task == "jar" else "build/extension")
        artifacts = sorted(artifact_directory.glob("*.jar" if task == "jar" else "*.dlextension"))
        if len(artifacts) != 1:
            raise RuntimeError(f"Expected one freshly built artifact, got {len(artifacts)}")
        artifact = artifacts[0]
        metadata = {"schemaVersion": 1, "extensionId": manifest["id"], "version": manifest["version"],
                    "channel": "preview" if "-" in manifest["version"].split("+")[0] else "stable",
                    "platform": actual_platform if declaration["native"] else "universal",
                    "buildPlatform": actual_platform, "distribution": declaration["distribution"],
                    "sha256": digest(artifact), "size": artifact.stat().st_size,
                    "ideCommit": declaration["ideCommit"],
                    "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repository, text=True).strip(),
                    "published": False}
        if task != "jar":
            with zipfile.ZipFile(artifact) as archive:
                descriptor = json.loads(archive.read("extension.json"))
                if descriptor["extension"]["id"] != manifest["id"] or descriptor["extension"]["version"] != manifest["version"]:
                    raise RuntimeError("Built archive metadata does not match the source manifest")
                for library in descriptor["libraries"]:
                    if library not in archive.namelist():
                        raise RuntimeError(f"Missing declared library: {library}")
                metadata["apiVersion"] = descriptor["apiVersion"]
                metadata["compatibility"] = descriptor.get("compatibility")
        shutil.copy2(artifact, output / artifact.name)
        (output / "release.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(metadata))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--ide-root", type=Path)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    build(args.repository.resolve(strict=True), args.ide_root, args.check_only)
