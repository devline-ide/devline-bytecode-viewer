"""Build an extracted extension against a pinned IDE checkout on a native worker."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import platform
import re
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


def prepare_submodules(checkout: Path, mirror: Path | None) -> None:
    declaration = checkout / ".gitmodules"
    if not declaration.is_file():
        return
    output = subprocess.check_output(["git", "config", "--file", str(declaration),
                                      "--get-regexp", r"^submodule\..*\.path$"], cwd=checkout, text=True)
    for line in output.splitlines():
        key, relative = line.split(None, 1)
        source = mirror / relative if mirror is not None else None
        if source is not None and (source / ".git").exists():
            # Git's local clone reads committed objects, including the exact
            # gitlink revision; it never copies a dirty submodule working tree.
            name = key[len("submodule."):-len(".path")]
            run(["git", "config", f"submodule.{name}.url", str(source.resolve())], checkout)
            run(["git", "-c", "protocol.file.allow=always", "submodule", "update", "--init", "--", relative], checkout)
            prepare_submodules(checkout / relative, source)
        else:
            run(["git", "submodule", "update", "--init", "--recursive", "--", relative], checkout)


def extension_sdk_projects(ide: Path, module: str) -> list[str]:
    """Configure only the extension's source SDK dependency graph in the owned CI checkout.

    The full IDE includes unfinished worktree-only product modules. Independent
    extensions must not require those unrelated product projects to exist. Keep
    the original source/build scripts for every actual SDK dependency. The only
    desktop contribution needed here is its existing npm preparation task.
    """
    selected: set[str] = set()
    pending = [module]
    while pending:
        current = pending.pop()
        if current in selected:
            continue
        selected.add(current)
        if current == "devline-desktop":
            continue
        script = ide / current / "build.gradle.kts"
        if not script.is_file():
            raise RuntimeError(f"The pinned IDE lacks required SDK source: {current}")
        source = script.read_text(encoding="utf-8")
        dependencies = re.findall(r'project\(\s*":(devline-[a-z0-9-]+)"\s*\)', source)
        dependencies += re.findall(r'":(devline-[a-z0-9-]+):[a-zA-Z]', source)
        pending.extend(dependencies)
    settings_path = ide / "settings.gradle.kts"
    settings = settings_path.read_text(encoding="utf-8")

    def included(match: re.Match) -> str:
        names = [name for name in re.findall(r'"([a-z0-9-]+)"', match[1]) if name in selected]
        return 'include(' + ', '.join(json.dumps(name) for name in names) + ')' if names else ''

    settings = re.sub(r'(?m)^include\(([^\n]*)\)', included, settings)
    if "devline-desktop" in selected:
        desktop_source = (ide / "devline-desktop/build.gradle.kts").read_text(encoding="utf-8")
        start = desktop_source.index("val webDirectory =")
        end = desktop_source.index("val animGraphDirectory =", start)
        # Reuse the exact committed webInstall implementation, not a no-op task.
        (ide / "devline-desktop/extension-sdk.gradle.kts").write_text(desktop_source[start:end], encoding="utf-8")
        settings += '\nproject(":devline-desktop").buildFileName = "extension-sdk.gradle.kts"\n'
    settings_path.write_text(settings, encoding="utf-8")
    return sorted(selected)


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
    if os.name == "nt":
        # Process-scoped Git configuration, including nested submodule clones.
        # No machine-wide registry or user Git setting is changed.
        config_index = int(os.environ.get("GIT_CONFIG_COUNT", "0"))
        os.environ[f"GIT_CONFIG_KEY_{config_index}"] = "core.longpaths"
        os.environ[f"GIT_CONFIG_VALUE_{config_index}"] = "true"
        os.environ["GIT_CONFIG_COUNT"] = str(config_index + 1)
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
        source_directory = repository
        sdk_projects = None
        if ide_override is None:
            if ide.exists():
                # Never reset an old worker checkout; callers use a fresh checkout per job.
                raise RuntimeError(f"Build workspace already exists; use a fresh job directory: {ide}")
            mirror_value = os.environ.get("DEVLINE_IDE_MIRROR", "")
            mirror = Path(mirror_value) if mirror_value else None
            if mirror is not None and (mirror / ".git").exists():
                # Read Git objects only; checkout still uses the declared commit,
                # never the mirror's uncommitted working tree or build output.
                run(["git", "clone", "--shared", "--no-checkout", str(mirror), str(ide)], repository)
                run(["git", "remote", "set-url", "origin", declaration["ideRepository"]], ide)
            else:
                run(["git", "clone", "--filter=blob:none", "--no-checkout", declaration["ideRepository"], str(ide)], repository)
            run(["git", "sparse-checkout", "set", "--no-cone", "/*", "!/docs/audits/"], ide)
            run(["git", "checkout", "--detach", declaration["ideCommit"]], ide)
            prepare_submodules(ide, mirror if mirror is not None and (mirror / ".git").exists() else None)
            npm = ["cmd", "/d", "/c", "npm"] if os.name == "nt" else ["npm"]
            run(npm + ["ci", "--no-audit", "--no-fund"], ide)
            # Shared UI exports reference these workspace packages' dist entries.
            for workspace in ("@devline/animgraph", "@devline/game-render-web"):
                run(npm + ["run", "build", "--workspace", workspace], ide)
            peers = repository / "build/extension-peers"
            peers.mkdir(parents=True, exist_ok=False)
            sys.path.insert(0, str(ide / "tools/extensions"))
            from workspace import bootstrap
            source_directory = peers / declaration["module"]
            # Vite resolves real source paths. Keep the candidate beside its peer
            # sources/shared UI, rather than linking to the Actions checkout.
            revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repository, text=True).strip()
            run(["git", "clone", "--no-hardlinks", "--no-checkout", str(repository), str(source_directory)], repository)
            run(["git", "checkout", "--detach", revision], source_directory)
            # The current extension is the candidate; all its peers stay locked.
            bootstrap(ide, peers, locked=False)
            sdk_projects = extension_sdk_projects(ide, declaration["module"])
        wrapper = ["cmd", "/d", "/c", "gradlew.bat"] if os.name == "nt" else ["sh", "gradlew"]
        task = "jar" if declaration["distribution"] == "builtin" else "extensionArchive"
        run(wrapper + [f":{declaration['module']}:{task}", "--no-daemon", "--max-workers=2"], ide)
        output = repository / "release-output"
        if output.exists():
            raise RuntimeError("Output exists; use a fresh job rather than overwriting a release candidate")
        output.mkdir()
        artifact_directory = source_directory / ("build/libs" if task == "jar" else "build/extension")
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
        if sdk_projects is not None:
            metadata.update(buildMode="extension-sdk-source", sdkProjects=sdk_projects)
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
        artifact_name = "component.jar" if task == "jar" else "extension.dlextension"
        shutil.copy2(artifact, output / artifact_name)
        metadata["artifact"] = artifact_name
        if task != "jar":
            notes_path = repository / "release-notes.json"
            notes = json.loads(notes_path.read_text(encoding="utf-8")) if notes_path.exists() else {}
            publication = {key: metadata[key] for key in ("extensionId", "version", "channel", "platform")}
            publication["releaseNotes"] = notes
            (output / "publication.json").write_text(json.dumps(publication, indent=2) + "\n", encoding="utf-8")
        (output / "release.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(metadata))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--ide-root", type=Path)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    build(args.repository.resolve(strict=True), args.ide_root, args.check_only)
