# Bytecode Viewer

Private independent source repository for `devline.bytecode`. Source provenance and
the original file hashes are in `migration-source.json`. The original IDE history
has not been rewritten. Extension source version: `0.1.1`.

## Local development

Keep this checkout at `../ozl.devline.extensions/devline-bytecode-viewer` beside the IDE. From the
IDE run `python tools/extensions/workspace.py bind --extensions-root ../ozl.devline.extensions`.
Gradle/Vite/test paths remain directory links to these sources. Shared host/UI,
audio engine and Deck tools remain IDE-owned dependencies.

Build a candidate using Python 3.11+, JDK 25, Node 22+ and the existing IDE checkout:

```sh
python ci/build.py --repository . --ide-root ../../ozl.devline.ide
```

Adjust `--ide-root` if the IDE is elsewhere. CI uses an isolated checkout of the exact
`extension-build.json` IDE commit and its locked peer extensions. It requires the
build account's read access to private `devline-ide` repositories. Never use the
running user's distribution as a build directory.

## Releases and workers

The source manifest owns the version. Release tag `v<version>` must match it.
`.github/workflows/build.yml` runs only on own workers labelled `devline-extension`
and their exact platform. GitHub-hosted paid fallback is absent. All seven workers
on one machine share a build lock to avoid competing JVM/native builds. No router
ports or public build domain are needed. Jobs are triggered by tags or manually;
untrusted pull requests do not execute on the worker.

Native platform options: windows-amd64, windows-arm64, linux-amd64, linux-arm64, macosx-amd64, macosx-arm64.
An option describes a supported build route, not a completed OS test. Windows
workers cannot claim Linux/macOS execution. Sound Studio remains Windows x64 until
other native builds are verified. Steam Deck's Linux target tools require their
existing Linux cross-build environment. AI Assistant remains a built-in JAR until
the host/UI contract supports independently replacing it.

`release-output/release.json` is generated from the actual built artifact and keeps
`published: false`. CI artifacts are short-lived candidates, not end-user delivery
URLs. Backend approval/public delivery and repo/tag-bound CI authentication are
separate; never hand private GitHub credentials or private download URLs to users.
Publishing uses short-lived Actions OIDC and is disabled unless the repository
variable `DEVLINE_MARKETPLACE_PUBLISH` is explicitly `true`, the ref is a version
tag and the backend already binds this exact repository/workflow to an approved
publisher. A successful submission still follows backend moderation policy.
