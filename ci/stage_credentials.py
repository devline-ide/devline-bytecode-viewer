"""Lease the current job token to the isolated stage through its forced SSH command."""
from __future__ import annotations

import argparse
import csv
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("activate", "clear"))
    args = parser.parse_args()
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("DEVLINE_DELIVERY_MODE") != "stage":
        raise ValueError("Only a trusted stage job can use the provider lease")
    lease = "-".join(os.environ[name] for name in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_REPOSITORY_ID"))
    if not re.fullmatch(r"[0-9]+-[0-9]+-[0-9]+", lease):
        raise ValueError("Invalid Actions lease identity")
    body = {"leaseId": lease}
    if args.action == "activate":
        token = os.environ["GITHUB_TOKEN"]
        if not token or any(char.isspace() for char in token):
            raise ValueError("Expected the transient current job token")
        body["token"] = token
        configuration = json.loads((Path(__file__).parent / "external-release.json").read_text("utf8"))
        request = urllib.request.Request("https://api.github.com/repos/" + configuration["distributionRepository"],
            headers={"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json", "User-Agent": "DevLine-stage-token-preflight"})
        with urllib.request.urlopen(request, timeout=15) as response:
            repository = json.load(response)
        if str(repository["id"]) != str(configuration["distributionRepositoryId"]) or repository["private"]:
            raise ValueError("The transient job token cannot read the bound public binary repository")
    private = os.environ["DEVLINE_STAGE_RELAY_KEY"]
    known = os.environ["DEVLINE_STAGE_RELAY_KNOWN_HOSTS"]
    directory = Path(tempfile.mkdtemp(prefix="devline-stage-lease-", dir=os.environ.get("RUNNER_TEMP")))
    try:
        key = directory / "id_ed25519"; key.write_text(private.rstrip() + "\n", encoding="utf8")
        hosts = directory / "known_hosts"; hosts.write_text(known.rstrip() + "\n", encoding="utf8")
        if os.name == "nt":
            sid_rows = csv.reader(io.StringIO(subprocess.check_output(["whoami", "/user", "/fo", "csv", "/nh"], text=True)))
            sid = next(sid_rows)[1]
            subprocess.run(["icacls", str(key), "/inheritance:r", "/grant:r", "*" + sid + ":(R)"], check=True, capture_output=True)
        else:
            key.chmod(0o600)
        ssh = shutil.which("ssh")
        if not ssh:
            raise ValueError("OpenSSH client is required for the stage lease")
        # This identity has a forced command, no shell/forwarding, and no production access.
        result = subprocess.run([ssh, "-i", str(key), "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes",
            "-o", "StrictHostKeyChecking=yes", "-o", "UserKnownHostsFile=" + str(hosts), "-o", "ConnectTimeout=15",
            "devline-stage-ci@206.223.245.243", args.action], input=json.dumps(body), text=True,
            capture_output=True, timeout=120)
        if result.returncode:
            # No credential-bearing stderr/stdout is surfaced to logs or artifacts.
            raise RuntimeError("The isolated stage provider lease command failed")
        print("Isolated stage provider lease " + args.action + " completed")
    finally:
        shutil.rmtree(directory)


if __name__ == "__main__": main()
