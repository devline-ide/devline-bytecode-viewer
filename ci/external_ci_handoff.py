"""Plan an external release registration; submitting is separately gated and JSON-only."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

ENDPOINT = "/v1/marketplace/ci/external/releases"
FIELDS = {"repositoryLinkId", "kind", "itemId", "version", "platform", "tag", "assetId",
          "expectedSha256", "expectedSize", "name", "description", "priceMinor"}
PLATFORMS = {"universal", "windows-amd64", "windows-arm64", "linux-amd64", "linux-arm64",
             "macosx-amd64", "macosx-arm64"}
ALIASES = {"macos-amd64": "macosx-amd64", "macos-arm64": "macosx-arm64"}


def platform(value: str) -> str:
    if not isinstance(value, str):
        raise ValueError("One platform string is required")
    value = ALIASES.get(value, value)
    if value not in PLATFORMS:
        raise ValueError("Unsupported platform")
    return value


def validate(payload: dict) -> None:
    if set(payload) != FIELDS or payload["kind"] != "extension" or type(payload["priceMinor"]) is not int or payload["priceMinor"] != 0:
        raise ValueError("Expected the exact free-extension registration fields")
    if str(uuid.UUID(payload["repositoryLinkId"])) != payload["repositoryLinkId"]:
        raise ValueError("Expected a canonical repository link UUID")
    if not isinstance(payload["assetId"], str) or not re.fullmatch(r"[1-9][0-9]{0,19}", payload["assetId"]):
        raise ValueError("Expected a numeric GitHub release asset ID")
    if not isinstance(payload["itemId"], str) or not re.fullmatch(r"[a-z0-9][a-z0-9._-]{1,127}", payload["itemId"]):
        raise ValueError("Invalid extension identity")
    version = payload["version"]
    if not isinstance(version, str) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.+-]+)?", version):
        raise ValueError("Invalid extension version")
    if payload["tag"] not in (version, "v" + version):
        raise ValueError("The artifact/source tag must match the version")
    if platform(payload["platform"]) != payload["platform"]:
        raise ValueError("Expected a canonical platform")
    if not re.fullmatch(r"[a-f0-9]{64}", payload["expectedSha256"]) or type(payload["expectedSize"]) is not int or payload["expectedSize"] <= 0:
        raise ValueError("Invalid archive checksum or size")
    for field, maximum in (("name", 256), ("description", 4096)):
        values = payload[field]
        if not isinstance(values, dict) or not values or len(values) > 32:
            raise ValueError("Expected localized " + field)
        if any(not isinstance(key, str) or not re.fullmatch(r"[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*", key)
               or not isinstance(text, str) or not text.strip() or len(text) > maximum for key, text in values.items()):
            raise ValueError("Invalid localized " + field)


def plan(output: Path, repository_link_id: str, asset_id: str, tag: str | None = None) -> tuple[dict, dict]:
    output = output.resolve(strict=True)
    metadata = json.loads((output / "release.json").read_text(encoding="utf-8"))
    if metadata.get("distribution") != "dlextension" or metadata.get("artifact") != "extension.dlextension":
        raise ValueError("Only an actual extension build output can be registered")
    archive = output / "extension.dlextension"
    if archive.is_symlink() or not archive.is_file():
        raise ValueError("Expected a regular built extension archive")
    with archive.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    size = archive.stat().st_size
    if digest != metadata.get("sha256") or size != metadata.get("size"):
        raise ValueError("Archive bytes differ from the build receipt")
    with zipfile.ZipFile(archive) as package:
        entries = [entry for entry in package.infolist() if entry.filename == "extension.json"]
        if len(entries) != 1 or entries[0].file_size > 128 * 1024:
            raise ValueError("Expected one bounded extension descriptor")
        descriptor = json.loads(package.read(entries[0]))["extension"]
    if descriptor["id"] != metadata.get("extensionId") or descriptor["version"] != metadata.get("version"):
        raise ValueError("Archive identity differs from its build receipt")
    payload = {"repositoryLinkId": repository_link_id, "kind": "extension", "itemId": descriptor["id"],
               "version": descriptor["version"], "platform": platform(metadata["platform"]),
               "tag": tag or "v" + descriptor["version"], "assetId": asset_id,
               "expectedSha256": digest, "expectedSize": size,
               "name": descriptor["name"], "description": descriptor["description"], "priceMinor": 0}
    validate(payload)
    return payload, metadata


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, url):
        return None


def request_json(url: str, bearer: str, payload: dict | None = None) -> tuple[int, dict]:
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    request = urllib.request.Request(url, data=data, headers={"Authorization": "Bearer " + bearer,
        "Accept": "application/json", "Content-Type": "application/json", "User-Agent": "DevLine-external-CI-draft"})
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=90 if data else 15) as response:
            raw = response.read(256 * 1024 + 1)
            if len(raw) > 256 * 1024:
                raise RuntimeError("CI response exceeds the metadata limit")
            value = json.loads(raw)
            if not isinstance(value, dict):
                raise RuntimeError("Expected a JSON metadata response")
            return response.status, value
    except urllib.error.HTTPError as error:
        # Do not print tokens, response bodies or redirect locations. A retry
        # starts a new process/request and obtains a fresh OIDC JWT/JTI.
        raise RuntimeError(f"CI HTTP request failed ({error.code}); no automatic retry") from None
    except (urllib.error.URLError, OSError, ValueError):
        raise RuntimeError("CI metadata request failed; no automatic retry") from None


def submit(payload: dict, metadata: dict, environment: dict, transport=None) -> dict:
    validate(payload)
    if environment.get("DEVLINE_EXTERNAL_CI_HANDOFF_ENABLED") != "true":
        raise ValueError("External CI handoff is disabled")
    repository = environment.get("GITHUB_REPOSITORY", "")
    sha = environment.get("GITHUB_SHA", "")
    ref = "refs/tags/" + payload["tag"]
    workflow = environment.get("DEVLINE_CI_WORKFLOW_PATH", ".github/workflows/build.yml")
    if (environment.get("GITHUB_ACTIONS") != "true" or environment.get("GITHUB_REF") != ref
        or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository)
        or not re.fullmatch(r"[a-f0-9]{40}(?:[a-f0-9]{24})?", sha) or sha != metadata.get("sourceCommit")
        or environment.get("GITHUB_WORKFLOW_REF") != repository + "/" + workflow + "@" + ref):
        raise ValueError("Expected the bound source tag, commit and workflow context")
    origin = environment.get("DEVLINE_CATALOG_ORIGIN") or "https://devline.ozliginus.com"
    target = urllib.parse.urlsplit(origin)
    if target.scheme != "https" or target.username or target.password or not target.hostname or target.path or target.query or target.fragment:
        raise ValueError("Expected an HTTPS backend origin without credentials or a path")
    audience = environment.get("DEVLINE_CI_AUDIENCE") or origin + "/v1/marketplace/ci"
    issuer = urllib.parse.urlsplit(environment.get("ACTIONS_ID_TOKEN_REQUEST_URL", ""))
    if (issuer.scheme != "https" or issuer.username or issuer.password or issuer.fragment
        or not (issuer.hostname or "").endswith(".actions.githubusercontent.com")):
        raise ValueError("Expected the GitHub Actions OIDC request endpoint")
    issuer_token = environment.get("ACTIONS_ID_TOKEN_REQUEST_TOKEN", "")
    if not issuer_token:
        raise ValueError("GitHub Actions OIDC is unavailable")
    query = urllib.parse.parse_qsl(issuer.query, keep_blank_values=True)
    query = [(key, value) for key, value in query if key != "audience"] + [("audience", audience)]
    issuer_url = urllib.parse.urlunsplit(issuer._replace(query=urllib.parse.urlencode(query)))
    http = transport or request_json
    status, issued = http(issuer_url, issuer_token)
    token = issued.get("value")
    if status != 200 or not isinstance(token, str) or not token or any(character.isspace() for character in token):
        raise RuntimeError("OIDC token acquisition failed")
    status, result = http(origin + ENDPOINT, token, payload)
    if status not in (200, 201) or not isinstance(result.get("registration"), dict) or not result.get("submissionId"):
        raise RuntimeError("Unexpected external registration response")
    registration = result["registration"]
    identifiers = (result["submissionId"], registration.get("id"))
    if any(not isinstance(value, str) or not value or len(value) > 256 or any(ord(char) < 32 for char in value) for value in identifiers):
        raise RuntimeError("Unexpected registration identifiers")
    # Private source claims, auth tokens and full server responses are never logged.
    return {"httpStatus": status, "submissionId": result["submissionId"], "registrationId": registration.get("id"),
            "itemId": payload["itemId"], "version": payload["version"], "platform": payload["platform"]}


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="Existing build release-output directory")
    parser.add_argument("--repository-link-id", required=True)
    parser.add_argument("--asset-id", required=True)
    parser.add_argument("--tag")
    parser.add_argument("--submit", action="store_true", help="Separately gated; obtains fresh OIDC and posts JSON only")
    args = parser.parse_args()
    body, receipt = plan(args.output, args.repository_link_id, args.asset_id, args.tag)
    if args.submit:
        print(json.dumps(submit(body, receipt, dict(os.environ))))
    else:
        print(json.dumps(body, ensure_ascii=False, indent=2))
