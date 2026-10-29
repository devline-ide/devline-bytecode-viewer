# Contributing

Use Issues for reproducible defects and Pull Requests for focused changes.
Describe the actual and expected behavior, platform and extension/Studio version.
Never include API keys, passwords, personal project files or account credentials.

## Build without private repositories

Install JDK 25, Python 3.11+ and Node 22.13+. From this checkout run:

```sh
python ci/public_build.py
```

The command downloads only the immutable SDK pinned in `sdk.lock.json`, verifies
its SHA-256 and builds the actual extension and public checks. It does not clone
DevLine Studio, read GitHub credentials or install into a running IDE.
Output: `standalone/build/extension/*.dlextension`.

The Sound Studio native payload is currently Windows amd64 only; other extensions
can be built on Windows, Linux and macOS. This is build support, not proof that
native hardware or external paid services were exercised by CI.

The built-in AI Assistant integration tests remain in product CI. Public Tellfair
checks cover its independent service/account/persistence behavior without a paid
request. Do not replace host SDK contracts with local mock implementations.

## Review and delivery

PR checks use GitHub-hosted workers with read-only permissions and no publication
secrets. The release workflow uses trusted maintainers' tag/manual events on the
self-hosted worker. Never use `pull_request_target` to execute contributor code,
and do not add PR events to the release workflow. Release binaries are separate
immutable artifacts and require marketplace validation before installation.

Keep public APIs and archive compatibility truthful. Preserve existing projects
and stored settings. Follow the surrounding formatting and submit the smallest
useful change. Contributions are under the repository Apache-2.0 license.
