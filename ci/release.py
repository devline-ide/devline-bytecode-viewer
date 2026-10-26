"""Publish a verified tag build as immutable assets in its private GitHub Release."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.parse
import urllib.request


def publish(output: Path) -> None:
    metadata_bytes = (output / "release.json").read_bytes()
    metadata = json.loads(metadata_bytes)
    repository = os.environ["GITHUB_REPOSITORY"]
    token = os.environ["GITHUB_TOKEN"]
    tag = "v" + metadata["version"]
    if os.environ["GITHUB_REF"] != "refs/tags/" + tag or os.environ["GITHUB_SHA"] != metadata["sourceCommit"]:
        raise RuntimeError("Release ref/source does not match the verified build")
    artifact = output / metadata["artifact"]
    if artifact.parent != output or artifact.name not in ("extension.dlextension", "component.jar"):
        raise RuntimeError("Unexpected release artifact path")
    content = artifact.read_bytes()
    checksum = hashlib.sha256(content).hexdigest()
    if checksum != metadata["sha256"] or len(content) != metadata["size"]:
        raise RuntimeError("Release artifact bytes do not match their build receipt")

    def request(path: str, method: str = "GET", body=None, upload: bool = False):
        base = "https://uploads.github.com/" if upload else "https://api.github.com/"
        data = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else None
        call = urllib.request.Request(base + path, method=method, data=data, headers={
            "Authorization": "Bearer " + token, "User-Agent": "DevLine-extension-release",
            "Content-Type": "application/octet-stream" if upload else "application/json",
            "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"})
        with urllib.request.urlopen(call, timeout=120) as response:
            return json.load(response)

    if not request(f"repos/{repository}")["private"]:
        raise RuntimeError("First-party proprietary artifacts require a private repository")
    try:
        release = request(f"repos/{repository}/releases/tags/{urllib.parse.quote(tag)}")
        if request(f"repos/{repository}/commits/{urllib.parse.quote(tag)}")["sha"] != metadata["sourceCommit"]:
            raise RuntimeError("Existing release tag was preserved; its source differs")
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        release = request(f"repos/{repository}/releases", "POST", {
            "tag_name": tag, "target_commitish": metadata["sourceCommit"],
            "name": metadata["extensionId"] + " " + metadata["version"],
            "draft": False, "prerelease": metadata["channel"] == "preview",
            "body": "Verified private build.\n\nBuild: https://github.com/" + repository +
                    "/actions/runs/" + os.environ["GITHUB_RUN_ID"] +
                    "\n\nPublic marketplace approval is a separate publication step.",
        })
    platform = metadata["platform"]
    suffix = ".jar" if metadata["distribution"] == "builtin" else ".dlextension"
    archive_name = f"{metadata['extensionId']}-{metadata['version']}-{platform}{suffix}"
    assets = {archive_name: content, f"release-{platform}.json": metadata_bytes,
              f"SHA256SUMS-{platform}.txt": f"{checksum}  {archive_name}\n".encode()}
    current = {asset["name"]: asset for asset in request(f"repos/{repository}/releases/{release['id']}/assets")}
    for name, data in assets.items():
        expected = "sha256:" + hashlib.sha256(data).hexdigest()
        if name in current:
            if current[name].get("digest") != expected:
                raise RuntimeError(f"Refusing to overwrite immutable release asset: {name}")
            continue
        uploaded = request(f"repos/{repository}/releases/{release['id']}/assets?name={urllib.parse.quote(name)}",
                           "POST", data, upload=True)
        if uploaded.get("digest") != expected:
            raise RuntimeError(f"Uploaded release digest mismatch: {name}")
    print(release["html_url"])


if __name__ == "__main__":
    publish(Path(sys.argv[1]).resolve(strict=True))
