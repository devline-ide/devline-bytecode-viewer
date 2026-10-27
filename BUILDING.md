# Bytecode Viewer

Private independent source repository for `devline.bytecode`. Source provenance and
the original file hashes are in `migration-source.json`. The original IDE history
has not been rewritten. Extension source version: `0.1.2`.

The release declaration requires `bytecodeReaderCheck`, `bytecodeRuntimeCheck`,
`bytecodeOwnershipWorkerCheck` and `bytecodeReleaseCheck`. The last check loads
the exact archive through the host parser/provider loader, verifies capability
rejection on an older host and produces PHP instructions without executing PHP.
No provider classes are supplied by the test parent classpath.

## Local development

Keep this checkout at `../ozl.devline.extensions/devline-bytecode-viewer` beside the IDE. From the
IDE run `python tools/extensions/workspace.py bind --extensions-root ../ozl.devline.extensions`.
Gradle reads this external project directly; the IDE root contains no extension
folders or links. UI is consumed through the versioned npm packages described
below. The host SDK, audio subsystem and Deck tools remain IDE-owned dependencies.

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

## Web frontend dependency

The frontend uses the versioned `@devline/extension-devkit` package in `vendor/`.
`npm ci` verifies/installs its lockfile integrity; `vendor/extension-devkit.json`
records SHA-256 and the owning SDK source revision. No shell source, shell
TypeScript config or shell directory link is needed. The host still injects the
runtime bridge; the devkit exports types and build configuration only.

After cloning the SDK checkout, run its documented
`python tools/extensions/workspace.py bootstrap --extensions-root ../ozl.devline.extensions --locked`.
Then `node <sdk>/tools/extensions/frontend.mjs panel <this-repository>` type-checks
and builds the panel without configuring the whole IDE. Gradle's `panelBuild`
uses that same entry point. UI and frontend tools now come from the declared npm
dependencies described below. Steam Deck still uses the pinned shared Deck build
inventory. This does not publish or replace an existing immutable extension release.

## UI package dependency

The frontend imports `@devline/ui-web/controls` and
`@devline/ui-web/controls.css` from a compiled versioned UI package. It does not
read the IDE's UI source, Sass source tree or TypeScript paths. The complete UI
root API remains available to other applications; the controls entry keeps
extension webviews within their existing payload limits. Steam's scrollbar
mixin is the public `@devline/ui-web/scrollbar.scss` export.

UI and its animation/render companions are frozen tarball dependencies under
`vendor/`. `vendor/ui-packages.json` records their source revision, SHA-256 and
npm integrity. Public Vue/Vite/TypeScript/Sass tools are explicit dependencies
in this repository's lockfile. The original extension web devkit archive is
unchanged. After a clean clone, build this frontend with:

```sh
npm ci --ignore-scripts --no-audit --no-fund
npm run build
```

Steam Deck additionally needs its existing shared Deck inventory source from the
pinned SDK; the IDE bootstrap supplies that explicit remaining dependency. The
other five frontends need no sibling IDE checkout for this npm build. JVM/native
archive assembly remains the responsibility of `ci/build.py` and the Gradle SDK
build. No existing marketplace version or immutable Git tag is republished by
this dependency migration.
