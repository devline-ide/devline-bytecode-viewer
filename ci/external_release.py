"""Publish binary-only immutable releases from trusted private source tag jobs."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import zipfile

import external_ci_handoff as handoff


class Api:
    def __init__(self, token):
        self.token = token

    def call(self, path, method="GET", data=None, upload=False):
        base = "https://uploads.github.com/" if upload else "https://api.github.com/"
        body = data if isinstance(data, bytes) else json.dumps(data).encode() if data is not None else None
        headers = {"Authorization": "Bearer " + self.token, "User-Agent": "DevLine-binary-release",
                   "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2026-03-10",
                   "Content-Type": "application/octet-stream" if upload else "application/json"}
        try:
            with urllib.request.build_opener(handoff.NoRedirect()).open(urllib.request.Request(base + path, data=body, headers=headers, method=method), timeout=120) as response:
                raw = response.read(2 * 1024 * 1024)
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"GitHub {method} {path.split('?')[0]} failed ({error.code})") from None


def worker_credential():
    # Existing trusted worker credential, never a new secret or a public artifact.
    result = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n",
        text=True, capture_output=True, check=True,
        env={**os.environ, "GIT_TERMINAL_PROMPT": "0", "GCM_INTERACTIVE": "Never"})
    token = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line).get("password")
    if not token or any(char.isspace() for char in token):
        raise ValueError("The trusted release worker needs its existing GitHub credential")
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print("::add-mask::" + token, flush=True)
    return token


def checked_output(output, configuration):
    receipt = json.loads((output / "release.json").read_text("utf-8"))
    artifact = output / "extension.dlextension"
    if artifact.is_symlink() or not artifact.is_file():
        raise ValueError("Expected a regular built archive")
    data = artifact.read_bytes()
    if receipt.get("artifact") != artifact.name or receipt.get("distribution") != "dlextension":
        raise ValueError("A complete standalone extension build is required")
    if len(data) != receipt.get("size") or hashlib.sha256(data).hexdigest() != receipt.get("sha256"):
        raise ValueError("Build receipt does not match archive bytes")
    with zipfile.ZipFile(artifact) as archive:
        items = [item for item in archive.infolist() if item.filename == "extension.json"]
        if len(items) != 1 or items[0].file_size > 128 * 1024:
            raise ValueError("Expected one bounded extension descriptor")
        descriptor = json.loads(archive.read(items[0]))
    manifest = descriptor["extension"]
    if manifest["id"] != configuration["itemId"] or manifest["id"] != receipt["extensionId"] or manifest["version"] != receipt["version"]:
        raise ValueError("Archive identity differs from the exact producer binding")
    if receipt["platform"] not in configuration["platforms"]:
        raise ValueError("This native platform has not been accepted for publication")
    if (os.environ.get("GITHUB_REPOSITORY") != configuration["sourceRepository"] or
        os.environ.get("GITHUB_ACTIONS") != "true" or
        os.environ.get("GITHUB_REF") != "refs/tags/v" + manifest["version"] or
        os.environ.get("GITHUB_SHA") != receipt["sourceCommit"] or
        os.environ.get("GITHUB_WORKFLOW_REF") != configuration["sourceRepository"] + "/.github/workflows/build.yml@refs/tags/v" + manifest["version"]):
        raise ValueError("Only the exact trusted source tag workflow can publish")
    return receipt, descriptor, data


def public_assets(receipt, descriptor, data):
    manifest = descriptor["extension"]
    filename = f"{manifest['id']}-{manifest['version']}-{receipt['platform']}.dlextension"
    # Deliberate allowlist: no private build receipt, commit, source repository or CI run.
    public = {"schemaVersion": 1, "id": manifest["id"], "version": manifest["version"],
              "platform": receipt["platform"], "name": manifest["name"], "description": manifest["description"],
              "publisher": manifest["publisher"], "apiVersion": descriptor["apiVersion"],
              "sha256": receipt["sha256"], "size": receipt["size"], "artifact": filename}
    if "compatibility" in descriptor:
        public["compatibility"] = descriptor["compatibility"]
    return {filename: data, "release-" + receipt["platform"] + ".json": (json.dumps(public, ensure_ascii=False, indent=2) + "\n").encode(),
            "SHA256SUMS-" + receipt["platform"] + ".txt": (receipt["sha256"] + "  " + filename + "\n").encode()}


def publish(output, configuration, api):
    receipt, descriptor, data = checked_output(output, configuration)
    source = api.call("repos/" + configuration["sourceRepository"])
    visibility = "private" if source["private"] else "public"
    if visibility != configuration.get("sourceVisibility", "private") or str(source["id"]) != str(configuration["sourceRepositoryId"]) or str(source["owner"]["id"]) != str(configuration["ownerId"]):
        raise ValueError("Bound source repository identity or visibility changed")
    repository = configuration["distributionRepository"]
    info = api.call("repos/" + repository)
    if info["private"] or str(info["id"]) != str(configuration["distributionRepositoryId"]) or str(info["owner"]["id"]) != str(configuration["ownerId"]):
        raise ValueError("Public binary repository identity changed")
    if not api.call("repos/" + repository + "/immutable-releases")["enabled"]:
        raise ValueError("Public release immutability must be enabled")
    tag = "v" + receipt["version"]
    try:
        release = api.call(f"repos/{repository}/releases/tags/{tag}")
    except RuntimeError as error:
        if "(404)" not in str(error):
            raise
        # The target is the public metadata repository, never a private source commit.
        release = api.call(f"repos/{repository}/releases", "POST", {"tag_name": tag, "target_commitish": info["default_branch"],
            "name": descriptor["extension"]["name"]["en"] + " " + receipt["version"], "draft": True,
            "prerelease": receipt["channel"] == "preview", "body": "DevLine extension package. Review compatibility in DevLine Studio before installing."})
    expected = public_assets(receipt, descriptor, data)
    current = {item["name"]: item for item in api.call(f"repos/{repository}/releases/{release['id']}/assets?per_page=100")}
    if set(current) - set(expected):
        raise ValueError("Public release contains unexpected assets; do not finalize")
    for name, content in expected.items():
        digest = "sha256:" + hashlib.sha256(content).hexdigest()
        if name in current:
            if current[name].get("digest") != digest or current[name]["size"] != len(content):
                raise ValueError("Refusing to overwrite an existing public release asset")
        else:
            if not release["draft"]:
                raise ValueError("Published release is missing an expected asset")
            current[name] = api.call(f"repos/{repository}/releases/{release['id']}/assets?name={urllib.parse.quote(name)}", "POST", content, upload=True)
            if current[name].get("digest") != digest:
                raise ValueError("Uploaded public asset digest mismatch")
    if release["draft"]:
        # Current accepted producers have exactly one complete platform per tag.
        # Expand this only with an explicit all-platform aggregation job.
        if configuration["platforms"] != [receipt["platform"]]:
            raise ValueError("All platforms must be aggregated before finalization")
        release = api.call(f"repos/{repository}/releases/{release['id']}", "PATCH", {"draft": False})
    release = api.call(f"repos/{repository}/releases/{release['id']}")
    if release.get("immutable") is not True or release["draft"]:
        raise ValueError("GitHub has not finalized an immutable public release")
    filename = next(name for name in expected if name.endswith(".dlextension"))
    return str(current[filename]["id"]), receipt


def restore(output, configuration):
    if output.exists():
        raise ValueError("Use a fresh checkout for exact existing release acceptance")
    token = os.environ["GITHUB_TOKEN"]
    api = Api(token); repo = configuration["sourceRepository"]
    tag = os.environ["GITHUB_REF"].removeprefix("refs/tags/")
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?", tag):
        raise ValueError("Existing artifacts require an exact source release tag")
    release = api.call(f"repos/{repo}/releases/tags/{tag}")
    assets = {item["name"]: item for item in api.call(f"repos/{repo}/releases/{release['id']}/assets?per_page=100")}
    platform = configuration["platforms"][0]
    names = {"release.json": "release-" + platform + ".json", "extension.dlextension": f"{configuration['itemId']}-{tag[1:]}-{platform}.dlextension"}
    output.mkdir()
    for target, name in names.items():
        request = urllib.request.Request(f"https://api.github.com/repos/{repo}/releases/assets/{assets[name]['id']}",
            headers={"Authorization": "Bearer " + token, "Accept": "application/octet-stream", "User-Agent": "DevLine-existing-release"})
        try:
            response = urllib.request.build_opener(handoff.NoRedirect()).open(request, timeout=120)
        except urllib.error.HTTPError as error:
            if error.code != 302 or not error.headers.get("Location", "").startswith("https://release-assets.githubusercontent.com/"):
                raise RuntimeError("Unexpected private artifact download response") from None
            response = urllib.request.urlopen(error.headers["Location"], timeout=120)
        with response:
            content = response.read(64 * 1024 * 1024 + 1)
        if len(content) > 64 * 1024 * 1024 or "sha256:" + hashlib.sha256(content).hexdigest() != assets[name].get("digest"):
            raise ValueError("Existing private release asset checksum mismatch")
        (output / target).write_bytes(content)
    checked_output(output, configuration)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("release-output"))
    parser.add_argument("--configuration", type=Path, default=Path("ci/external-release.json"))
    parser.add_argument("--restore", action="store_true")
    args = parser.parse_args(); configuration = json.loads(args.configuration.read_text("utf-8"))
    if args.restore:
        restore(args.output, configuration); return
    mode = os.environ.get("DEVLINE_DELIVERY_MODE", "legacy")
    if mode not in ("stage", "external"):
        raise ValueError("External release requires the staged or accepted delivery mode")
    checked_output(args.output, configuration)
    asset_id, receipt = publish(args.output, configuration, Api(worker_credential()))
    prefix = "DEVLINE_STAGE_" if mode == "stage" else "DEVLINE_EXTERNAL_"
    origin = os.environ[prefix + "ORIGIN"]; link = os.environ[prefix + "REPOSITORY_LINK_ID"]
    payload, _ = handoff.plan(args.output, link, asset_id)
    environment = {**os.environ, "DEVLINE_EXTERNAL_CI_HANDOFF_ENABLED": "true", "DEVLINE_CATALOG_ORIGIN": origin,
                   "DEVLINE_CI_AUDIENCE": origin + "/v1/marketplace/ci"}
    result = handoff.submit(payload, receipt, environment)
    (args.output / (mode + "-registration.json")).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
