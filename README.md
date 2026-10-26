# Bytecode Viewer

Bundled, enabled extension `devline.bytecode`, API 6, RPC namespace `bytecode`. The inspect command accepts PHP, PHB, class, JAR, Kotlin and Java files. Its viewer and Settings > Extensions page are declared by the extension, with RU/EN metadata and an embedded PNG icon generated from the SVG branding source.

Build with `./gradlew :devline-bytecode-viewer:extensionArchive`. The reproducible `.dlextension` contains the provider JAR, one self-contained webview and ASM 9.8 (core, util, tree, analysis). IDE extension/API, Gson, compiler and JPHP dependencies are compile-only and remain host-owned. Focused `bytecodeReaderCheck` and `bytecodeRuntimeCheck` tasks participate in `check`; Kotlin compiler dependencies are available to the runtime check but are not bundled into the extension.

The viewer uses the supported dock location; file commands and source navigation use generic host integration. The inspect command declares readonly:true and saveBeforeRun:false, so viewing does not save an edited source buffer implicitly. PHP compilation and class analysis run through the provider, with paginated RPC results rather than an unbounded command response. Settings are registered through the declared settings webview.

Analysis starts on demand. File mode reads a saved source/binary; Build mode reads the current or latest prepared application output, including outputs of a running application. It does not attach to a JVM or show native JIT code. Source compilation runs in a disposable 256 MiB worker with a 90-second deadline; it does not run the application's entrypoint. Closing the last viewer cancels that worker and releases retained results. Merely hiding a mounted viewer pauses polling and preserves its state.

Branding: `src/main/resources/devline/bytecode/branding/icon.svg` is the vector source; `icon.png` is its transparent 128×128 raster. The manifest embeds those exact PNG bytes. No external icon request is needed during extension discovery.

Version 0.1.1 requires the host's `compiler.php-optimization` capability. PHP
source analysis respects the project's integer profile and canonical source
provenance inside an owned worker snapshot. Changing that profile marks an
existing source result stale. Generated JPHP scalar bodies and guarded entries
remain visible under distinct labels when other synthetic members are hidden;
the original PHP ABI method remains available. A small scalar body does not
represent the complete cost of a PHP call. Implementation and measured scope:
`../docs/audits/jphp-scalar-optimization-20261026.md`.
