package devline.bytecode

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import devline.extensions.IdeExtensionContext
import devline.extensions.IdeExtensionException
import devline.extensions.IdeExtensionWebviewLifecycle
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider

/** Runs the real public runtime API and its disposable child workers, never a user's project. */
fun main(args: Array<String>) {
    if (args.singleOrNull() == "ownership") { ownershipWorkerCheck(); return }
    val root = Files.createTempDirectory("devline-bytecode-runtime-").toRealPath()
    val project = Files.createDirectory(root.resolve("project"))
    val other = Files.createDirectory(root.resolve("other-project"))
    val projectFile = project.resolve("test.dlproject").also { Files.writeString(it, "{}") }.toString()
    val otherFile = other.resolve("other.dlproject").also { Files.writeString(it, "{}") }.toString()
    val context = RuntimeContext(root.resolve("profile"), mapOf(projectFile to project, otherFile to other))
    val runtime = BytecodeRuntime(context)
    val owned = linkedSetOf<Process>()
    val sentinel = root.resolve("USER_CODE_EXECUTED")
    var primaryFailure: Throwable? = null
    try {
        val noProject = runtime.call("state")
        check(!noProject.has("projectFile") && noProject.getAsJsonArray("files").isEmpty)
        check(noProject.getAsJsonObject("settings")["showDebugInfo"].asBoolean)
        check(runtime.call("settings").getAsJsonObject("settings")["showSynthetic"].asBoolean == false)
        expectRuntimeError("NO_PROJECT") { runtime.call("inspect", "path" to "src/app/Child.php") }
        context.projectFile = projectFile

        val source = Files.createDirectories(project.resolve("src/app"))
        val marker = sentinel.toString().replace('\\', '/').replace("'", "\\'")
        Files.writeString(source.resolve("Grandparent.php"), """
            <?php declare(strict_types=1); namespace fixture;
            file_put_contents('$marker', 'grandparent executed');
            class Grandparent { public function grandparentValue():int { return 4; } }
        """.trimIndent())
        Files.writeString(source.resolve("Contract.php"), """
            <?php declare(strict_types=1); namespace fixture;
            file_put_contents('$marker', 'interface executed');
            interface Contract { public function parentValue():int; }
        """.trimIndent())
        Files.writeString(source.resolve("SharedValue.php"), """
            <?php declare(strict_types=1); namespace fixture;
            file_put_contents('$marker', 'trait executed');
            trait SharedValue { public function sharedValue():int { return 5; } }
        """.trimIndent())
        Files.writeString(source.resolve("ParentType.php"), """
            <?php declare(strict_types=1); namespace fixture;
            file_put_contents('$marker', 'parent executed');
            class ParentType extends Grandparent implements Contract {
                use SharedValue;
                public function parentValue():int { return ${'$'}this->grandparentValue()+${'$'}this->sharedValue(); }
            }
        """.trimIndent())
        Files.writeString(source.resolve("Child.php"), """
            <?php declare(strict_types=1); namespace fixture;
            file_put_contents('$marker', 'entry executed');
            class Child extends ParentType {
                private int ${'$'}counter=2;
                public function value(int ${'$'}step):int {
                    ${'$'}this->counter += ${'$'}step;
                    return ${'$'}this->counter;
                }
            }
        """.trimIndent())
        val inventory = project.resolve("src/META-INF/devline/php85-sources.list")
        Files.createDirectories(inventory.parent)
        Files.writeString(inventory, "app/Child.php\napp/Grandparent.php\napp/Contract.php\napp/SharedValue.php\napp/ParentType.php\n")
        val javaSource = project.resolve("src/JvmFixture.java")
        Files.writeString(javaSource, """
            package sample;
            public final class JvmFixture {
                static { try { java.nio.file.Files.writeString(java.nio.file.Path.of(${Gson().toJson(sentinel.toString())}), "static initializer executed"); }
                    catch (Exception failure) { throw new ExceptionInInitializerError(failure); } }
                public int large(int x) {
                    ${"x += 1;\n".repeat(700)}
                    return x;
                }
            }
        """.trimIndent())
        val slowSource = project.resolve("src/Slow.java")
        Files.writeString(slowSource, "public final class Slow {\n" + (0 until 1500).joinToString("\n") { "public int method$it(int value) { return value + $it; }" } + "\n}")
        val artifactRoot = Files.createDirectories(project.resolve(".devline/build/application/owned-fixture"))
        val classRoot = Files.createDirectory(artifactRoot.resolve("classes"))
        val javac = ToolProvider.getSystemJavaCompiler() ?: error("Runtime check needs a JDK javac")
        check(javac.run(null, null, null, "-proc:none", "-g", "--release", "21", "-d", classRoot.toString(), javaSource.toString()) == 0)
        val classFile = classRoot.resolve("sample/JvmFixture.class")
        val projectClass = project.resolve("src/compiled/JvmFixture.class")
        Files.createDirectories(projectClass.parent); Files.copy(classFile, projectClass)
        val unicodeClass = project.resolve("src/compiled/Unicode.class")
        Files.write(unicodeClass, unicodeFixture())
        val phbRoot = artifactRoot.resolve("phb")
        compilePhb(root, project.resolve("src"), phbRoot, owned)
        check(!Files.exists(sentinel)) { "Preparing a PHB fixture executed authored PHP" }
        val jar = artifactRoot.resolve("fixture.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            val modules = listOf("Grandparent", "Contract", "SharedValue", "ParentType", "Child").map { "app/$it.phb" to phbRoot.resolve("app/$it.phb") }
            for ((name, file) in modules + ("sample/JvmFixture.class" to classFile)) {
                zip.putNextEntry(ZipEntry(name)); Files.copy(file, zip); zip.closeEntry()
            }
        }
        // A selected-file inspection must ignore an unrelated malformed application module.
        Files.writeString(source.resolve("Bad.php"), "<?php namespace fixture; file_put_contents('$marker', 'unrelated executed'); function broken( {")
        Files.writeString(inventory, "app/Bad.php\n", java.nio.file.StandardOpenOption.APPEND)
        val originals = Files.walk(project.resolve("src")).use { files -> files.filter(Files::isRegularFile).toList().associateWith(Files::readAllBytes) }
        val state = runtime.call("state")
        check(state.getAsJsonArray("files").any { it.asJsonObject["path"].asString == "src/app/Child.php" })
        check(state.getAsJsonArray("buildFiles").isEmpty)

        val php = runtime.inspect("src/app/Child.php", projectFile)
        runtime.await(php, owned)
        val phpClasses = runtime.allPages("classes", php, "items")
        check(phpClasses.isNotEmpty())
        val phpHeaders = phpClasses.map { runtime.call("classInfo", "id" to php, "classIndex" to it.asJsonObject["index"].asInt) }
        check(phpHeaders.any { it["sourcePath"]?.asString == "src/app/Child.php" })
        check(listOf("src/app/ParentType.php", "src/app/Grandparent.php").all { expected -> phpHeaders.any { it["sourcePath"]?.asString == expected } }) {
            "Local parent autoload metadata was lost: " + JsonArray().apply {
                phpHeaders.take(12).forEach { info -> add(JsonObject().apply {
                    val value = info.getAsJsonObject("class")
                    add("name", value["name"]); add("sourceFile", value["sourceFile"] ?: JsonNull.INSTANCE)
                    add("sourcePath", info["sourcePath"] ?: JsonNull.INSTANCE)
                }) }
            }.toString().take(3000)
        }
        val phpSource = runtime.call("classes", "id" to php).getAsJsonObject("source")
        check(phpSource["sha256"].asString == BytecodeProjectFiles.sha(originals.getValue(source.resolve("Child.php"))))
        val mapped = phpHeaders.first { it["sourcePath"]?.asString == "src/app/Child.php" }
        val mappedIndex = phpHeaders.indexOf(mapped)
        val mappedClassIndex = phpClasses[mappedIndex].asJsonObject["index"].asInt
        check(mapped.getAsJsonArray("methods").size() > 0)
        val phpMethods = runtime.allPages("classInfo", php, "methods", "classIndex" to mappedClassIndex)
        val phpCode = phpMethods.flatMap { method -> runtime.allPages("method", php, "instructions", "classIndex" to mappedClassIndex, "methodIndex" to method.asJsonObject["index"].asInt) }
        val line = phpCode.firstOrNull { it.asJsonObject["line"]?.let { value -> !value.isJsonNull && value.asInt > 0 } == true }?.asJsonObject?.get("line")?.asInt
            ?: error("Compiled typed PHP lost source lines")
        check(runtime.call("openSource", "id" to php, "classIndex" to mappedClassIndex, "line" to line).let { it["path"].asString == "src/app/Child.php" && it["line"].asInt == line })
        val selectedSource = source.resolve("Child.php")
        try {
            Files.writeString(selectedSource, Files.readString(selectedSource) + "\n// source changed after inspection\n")
            expectRuntimeError("FILE_CONFLICT") { runtime.call("openSource", "id" to php, "classIndex" to mappedClassIndex, "line" to line) }
        } finally { Files.write(selectedSource, originals.getValue(selectedSource)) }
        check(runtime.call("openSource", "id" to php, "classIndex" to mappedClassIndex, "line" to line)["path"].asString == "src/app/Child.php")
        val requiredParent = source.resolve("ParentType.php")
        try {
            Files.writeString(requiredParent, "<?php namespace fixture; class ParentType extends Grandparent { public function broken( {")
            val brokenDependency = runtime.inspect("src/app/Child.php", projectFile)
            val failed = runtime.await(brokenDependency, owned, expectReady = false)
            check(failed["state"].asString == "failed" && failed["errorCode"].asString.isNotBlank() && failed["message"].asString.contains("ParentType")) {
                "A required malformed parent was silently skipped or lost its diagnostic: $failed"
            }
            check(runtime.call("job", "id" to php)["state"].asString == "ready")
            check(!Files.exists(sentinel))
        } finally { Files.write(requiredParent, originals.getValue(requiredParent)) }

        val binary = runtime.inspect("src/compiled/JvmFixture.class", projectFile)
        runtime.await(binary, owned)
        val classRows = runtime.allPages("classes", binary, "items")
        check(classRows.single().asJsonObject["name"].asString == "sample/JvmFixture")
        val classInfo = runtime.call("classInfo", "id" to binary, "classIndex" to 0)
        check(classInfo["sourcePath"].asString == "src/JvmFixture.java")
        val large = runtime.allPages("classInfo", binary, "methods", "classIndex" to 0).single { it.asJsonObject["name"].asString == "large" }.asJsonObject
        val firstPage = runtime.call("method", "id" to binary, "classIndex" to 0, "methodIndex" to large["index"].asInt)
        check(firstPage.has("nextOffset")) { "Fixture did not exercise pagination" }
        val instructions = runtime.allPages("method", binary, "instructions", "classIndex" to 0, "methodIndex" to large["index"].asInt)
        check(instructions.size > 700 && instructions.last().asJsonObject["index"].asInt > 700)
        check(runtime.call("method", "id" to binary, "classIndex" to 0, "methodIndex" to large["index"].asInt, "query" to "IRETURN").getAsJsonArray("instructions").size() == 1)
        check(runtime.call("openSource", "id" to binary, "classIndex" to 0, "line" to 6)["path"].asString == "src/JvmFixture.java")

        val unicode = runtime.inspect("src/compiled/Unicode.class", projectFile)
        runtime.await(unicode, owned)
        val unicodeCode = runtime.allPages("method", unicode, "instructions", "classIndex" to 0, "methodIndex" to 0)
        check(unicodeCode.count { it.asJsonObject["opcode"].asInt == Opcodes.LDC } == 20)
        expectRuntimeError("BYTECODE_SESSION_EXPIRED") { runtime.call("job", "id" to php) }

        context.artifacts = artifactRoot
        val builds = runtime.call("state").getAsJsonArray("buildFiles").map { it.asJsonObject["path"].asString }
        for (ending in listOf("app/Grandparent.phb", "fixture.jar")) {
            val path = builds.single { it.endsWith(ending) }
            val id = runtime.inspect(path, projectFile, "build"); runtime.await(id, owned)
            val classes = runtime.allPages("classes", id, "items")
            check(classes.isNotEmpty())
            if (ending.endsWith("jar")) check(classes.any { it.asJsonObject["name"].asString == "sample/JvmFixture" })
        }
        check(!Files.exists(sentinel)) { "Inspecting source/PHB/class/JAR executed user code" }
        context.projectFile = otherFile
        expectRuntimeError("PROJECT_CHANGED") { runtime.call("inspect", "expectedProjectFile" to projectFile, "path" to "src/app/Child.php") }
        context.projectFile = projectFile
        expectRuntimeError("FILE_NOT_FOUND") { runtime.call("inspect", "path" to "../outside.class") }
        Files.writeString(project.resolve("src/unsupported.txt"), "unsupported")
        expectRuntimeError("FILE_NOT_FOUND") { runtime.call("inspect", "path" to "src/unsupported.txt") }
        BytecodeJobs(context.profileDirectory.resolve("bytecode-viewer/unsupported-sessions")).use { service ->
            val file = project.resolve("src/unsupported.txt")
            val job = service.start(projectFile, BytecodeInput("src/unsupported.txt", "bin", file, project, "file", BytecodeProjectFiles.version(file)))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (job.state == "preparing" && System.nanoTime() < deadline) { job.process?.let(owned::add); Thread.sleep(20) }
            check(job.state == "failed" && job.errorCode == "UNSUPPORTED_INPUT") { "Unsupported worker input was not isolated: ${job.view()}" }
        }

        for ((name, content) in listOf("Malformed.class" to byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte(), 0, 0, 0), "Malformed.phb" to "not a module".toByteArray())) {
            Files.write(project.resolve("src/$name"), content)
            val id = runtime.inspect("src/$name", projectFile)
            val failed = runtime.await(id, owned, expectReady = false)
            check(failed["state"].asString == "failed" && failed["errorCode"].asString.isNotBlank())
            check(runtime.call("state")["projectFile"].asString == projectFile) { "Worker failure broke the host" }
        }

        val running = runtime.inspect("src/Slow.java", projectFile)
        val runningProcess = runtime.awaitWorker(running).also(owned::add)
        val queued = runtime.inspect("src/compiled/JvmFixture.class", projectFile)
        check(runtime.call("cancel", "id" to queued)["state"].asString == "cancelled")
        check(runtime.call("job", "id" to queued)["state"].asString == "cancelled")
        runtime.projectClosed()
        awaitStopped(listOf(runningProcess))
        expectRuntimeError("BYTECODE_SESSION_EXPIRED") { runtime.call("job", "id" to running) }
        expectRuntimeError("BYTECODE_SESSION_EXPIRED") { runtime.call("job", "id" to queued) }
        check(Files.list(context.profileDirectory.resolve("bytecode-viewer/sessions")).use { it.findAny().isEmpty })
        val fresh = runtime.inspect("src/compiled/JvmFixture.class", projectFile)
        runtime.await(fresh, owned)
        val viewerJob = runtime.inspect("src/Slow.java", projectFile)
        val viewerProcess = runtime.awaitWorker(viewerJob).also(owned::add)
        val lifecycle: IdeExtensionWebviewLifecycle = runtime
        lifecycle.webviewClosed(projectFile, "settings")
        lifecycle.webviewClosed(otherFile, "viewer")
        check(runtime.call("job", "id" to viewerJob)["state"].asString == "preparing") { "An unrelated view/project cancelled the viewer worker" }
        lifecycle.webviewClosed(projectFile, "viewer")
        expectRuntimeError("BYTECODE_SESSION_EXPIRED") { runtime.call("job", "id" to viewerJob) }
        expectRuntimeError("BYTECODE_SESSION_EXPIRED") { runtime.call("job", "id" to fresh) }
        awaitStopped(listOf(viewerProcess))
        for (id in listOf(viewerJob, fresh)) awaitRemoved(context.profileDirectory.resolve("bytecode-viewer/sessions/$id"))
        val inventoryDirectory = Files.createDirectory(project.resolve("src/000-inventory"))
        try {
            repeat(501) { index -> Files.write(inventoryDirectory.resolve("${index.toString().padStart(4, '0')}.class"), byteArrayOf(0)) }
            val selected = "src/app/Child.php"
            check(runtime.call("state").getAsJsonArray("files").none { it.asJsonObject["path"].asString == selected }) { "Inventory fixture did not exceed the default cap" }
            val pinned = runtime.call("state", "path" to selected).getAsJsonArray("files")[0].asJsonObject
            check(pinned["path"].asString == selected && pinned["version"].asString == BytecodeProjectFiles.version(source.resolve("Child.php")))
        } finally {
            Files.list(inventoryDirectory).use { files -> files.forEach(Files::delete) }
            Files.delete(inventoryDirectory)
        }
        val closing = runtime.inspect("src/Slow.java", projectFile)
        check(closing.isNotBlank())
        val closingProcess = runtime.awaitWorker(closing).also(owned::add)
        runtime.close(); awaitStopped(listOf(closingProcess))
        check(Files.list(context.profileDirectory.resolve("bytecode-viewer/sessions")).use { it.findAny().isEmpty })
        originals.forEach { (file, bytes) -> check(Files.readAllBytes(file).contentEquals(bytes)) { "Inspection modified $file" } }
        check(!Files.exists(sentinel))
        println("BYTECODE_RUNTIME_TYPED_PHP_AUTOLOAD_PHB_CLASS_JAR_SOURCE_PAGING_SCOPE_CANCEL_TEARDOWN_NO_EXECUTION_PASS")
    } catch (failure: Throwable) { primaryFailure = failure; throw failure }
    finally { cleanupRuntime(runtime, owned, root, primaryFailure) }
}

private class RuntimeContext(override val profileDirectory: Path, private val projects: Map<String, Path>) : IdeExtensionContext {
    @Volatile var projectFile: String? = null
    var artifacts: Path? = null
    override fun enabled() = true
    override fun currentProjectFile() = projectFile
    override fun projectRoot(projectFile: String): String {
        check(projectFile == this.projectFile && Files.isRegularFile(Path.of(projectFile)))
        return projects.getValue(projectFile).toRealPath().toString()
    }
    override fun callProject(projectFile: String, method: String, arguments: JsonObject): JsonElement {
        projectRoot(projectFile)
        check(method == "application.inspection")
        return artifacts?.let { JsonObject().apply { addProperty("operationId", "runtime-fixture"); add("roots", JsonArray().apply { add(it.toString()) }) } } ?: JsonNull.INSTANCE
    }
}

private fun BytecodeRuntime.call(method: String, vararg values: Pair<String, Any>): JsonObject {
    val params = JsonObject()
    for ((key, value) in values) when (value) { is String -> params.addProperty(key, value); is Number -> params.addProperty(key, value); else -> error("Unsupported fixture parameter") }
    val result = request(method, params).asJsonObject
    check(result.toString().toByteArray(Charsets.UTF_8).size <= 128 * 1024) { "$method response exceeded the SDK byte cap" }
    return result
}

private fun BytecodeRuntime.inspect(path: String, project: String, origin: String = "file") = call("inspect", "path" to path, "expectedProjectFile" to project, "origin" to origin)["id"].asString

private fun BytecodeRuntime.await(id: String, owned: MutableSet<Process>, expectReady: Boolean = true): JsonObject {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(100)
    while (System.nanoTime() < deadline) {
        workerProcess(id)?.let(owned::add)
        val view = call("job", "id" to id)
        if (view["state"].asString != "preparing") {
            if (expectReady) check(view["state"].asString == "ready") { "Worker failed: $view" }
            return view
        }
        Thread.sleep(20)
    }
    error("Bytecode inspection did not complete")
}

private fun BytecodeRuntime.allPages(method: String, id: String, key: String, vararg values: Pair<String, Any>): List<JsonElement> {
    val rows = ArrayList<JsonElement>(); var offset = 0
    repeat(10_000) {
        val result = call(method, "id" to id, "offset" to offset, *values)
        rows += result.getAsJsonArray(key).toList()
        val next = result["nextOffset"]?.asInt ?: return rows
        check(next > offset); offset = next
    }
    error("Non-terminating pagination")
}

private fun expectRuntimeError(code: String, action: () -> Unit) {
    val failure = runCatching(action).exceptionOrNull()
    check(failure is IdeExtensionException && failure.code == code) { "Expected $code, got $failure" }
}

private fun BytecodeRuntime.awaitWorker(id: String): Process {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < deadline) {
        val view = call("job", "id" to id)
        check(view["state"].asString == "preparing") { "Inspection completed before its worker was observable: $view" }
        val process = workerProcess(id)
        if (process?.isAlive == true) return process
        Thread.sleep(5)
    }
    error("Owned inspection worker did not start: ${call("job", "id" to id)}")
}

private fun awaitStopped(processes: Iterable<Process>) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
    for (process in processes) {
        val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
        check(process.waitFor(remaining, TimeUnit.NANOSECONDS) && !process.isAlive) {
            "Owned inspection worker survived teardown: pid=${process.pid()}"
        }
    }
}

private fun awaitRemoved(directory: Path) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (Files.exists(directory)) {
        check(System.nanoTime() < deadline) { "Owned inspection directory survived teardown: $directory" }
        Thread.sleep(10)
    }
}

private fun cleanupRuntime(runtime: BytecodeRuntime, owned: Set<Process>, root: Path, primary: Throwable?) {
    var failure = primary
    fun attempt(action: () -> Unit) {
        try { action() } catch (next: Throwable) {
            val current = failure
            if (current == null) failure = next else if (next !== current) current.addSuppressed(next)
        }
    }
    attempt { runtime.close() }
    attempt { awaitStopped(owned) }
    // The only fallback kill targets are exact ProcessBuilder children captured from their jobs.
    for (process in owned) if (process.isAlive) attempt {
        process.destroyForcibly()
        check(process.waitFor(5, TimeUnit.SECONDS) && !process.isAlive) { "Unable to clean owned worker ${process.pid()}" }
    }
    attempt { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    if (primary == null) failure?.let { throw it }
}

private fun unicodeFixture(): ByteArray {
    val writer = ClassWriter(0); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "sample/Unicode", null, "java/lang/Object", null)
    writer.visitMethod(Opcodes.ACC_STATIC, "unicode", "()V", null, null).apply {
        visitCode(); repeat(20) { visitLdcInsn("Ж".repeat(12_000)); visitInsn(Opcodes.POP) }; visitInsn(Opcodes.RETURN); visitMaxs(1, 0); visitEnd()
    }
    writer.visitEnd(); return writer.toByteArray()
}

private fun compilePhb(root: Path, source: Path, destination: Path, owned: MutableSet<Process>, ownershipMode: String? = null) {
    val request = root.resolve("compile-request.json"); val result = root.resolve("compile-result.json"); val trace = root.resolve("compile-trace.log")
    Files.writeString(request, JsonObject().apply {
        addProperty("sourceRoot", source.toString()); addProperty("destination", destination.toString()); addProperty("debug", true); add("extensions", JsonArray())
        ownershipMode?.let { addProperty("ownershipMode", it) }
    }.toString())
    val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
    val output = root.resolve("compile-worker.log")
    val process = ProcessBuilder(java.toString(), "-Xms32m", "-Xmx256m", "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-cp", System.getProperty("java.class.path"),
        "devline.compiler.PhpCompilerWorkerKt", request.toString(), result.toString(), trace.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start()
    owned += process
    try {
        check(process.waitFor(90, TimeUnit.SECONDS)) { "Fixture compiler timed out" }
        check(process.exitValue() == 0 && JsonParser.parseString(Files.readString(result)).asJsonObject["success"].asBoolean) { "Fixture compiler failed: ${Files.readString(output)}" }
    } finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) } }
}

/** Real extension -> staged request -> compiler worker -> PHB reader, with no application execution. */
private fun ownershipWorkerCheck() {
    val root = Files.createTempDirectory("devline-bytecode-ownership-").toRealPath()
    val project = Files.createDirectory(root.resolve("mixed"))
    val descriptor = project.resolve("Mixed.dlproject")
    val context = RuntimeContext(root.resolve("profile"), mapOf(descriptor.toString() to project)).apply { projectFile = descriptor.toString() }
    val runtime = BytecodeRuntime(context)
    val owned = linkedSetOf<Process>()
    var primaryFailure: Throwable? = null
    val marker = root.resolve("USER_CODE_EXECUTED")
    val scalar = "__\$scalar\$"
    val scalarEntry = "__\$scalarEntry\$"
    val ownershipSymbols = listOf("php/runtime/PhpRuntimeOwnership", "php/runtime/PhpOwnershipExecution", "JPHP_Ownership_Frame_v1", "JPHP_Runtime_Frame_v1")
    fun put(relative: String, text: String) { val file = project.resolve(relative); Files.createDirectories(file.parent); Files.writeString(file, text) }
    fun setting(mode: String) = Files.writeString(descriptor, """{"schemaVersion":1,"name":"Mixed","language":"kotlin","sourceFormat":"dlproject","sourceFingerprint":"native","controllerPolicy":"native","settings":{"compiler.phpRuntimeOwnership":"$mode"}}""")
    data class Listing(val methods: List<String>, val operands: List<String>, val source: JsonObject, val authored: List<String>) {
        fun calls(symbols: List<String>) = operands.any { operand -> symbols.any(operand::contains) }
    }
    fun inspect(path: String): Listing {
        val id = runtime.inspect(path, descriptor.toString())
        runtime.await(id, owned)
        val methods = mutableListOf<String>(); val operands = mutableListOf<String>()
        for (clazz in runtime.allPages("classes", id, "items")) {
            val classIndex = clazz.asJsonObject["index"].asInt
            for (method in runtime.allPages("classInfo", id, "methods", "classIndex" to classIndex)) {
                methods += method.asJsonObject["name"].asString
                operands += runtime.allPages("method", id, "instructions", "classIndex" to classIndex,
                    "methodIndex" to method.asJsonObject["index"].asInt).map { it.asJsonObject["operand"].asString }
            }
        }
        val inventory = context.profileDirectory.resolve("bytecode-viewer/sessions/$id/source/META-INF/devline/php85-sources.list")
        val authored = if (Files.isRegularFile(inventory)) Files.readAllLines(inventory) else emptyList()
        return Listing(methods, operands, runtime.call("classes", "id" to id).getAsJsonObject("source"), authored)
    }
    try {
        val sentinel = marker.toString().replace('\\', '/')
        val phpSource = """
            <?php file_put_contents('$sentinel','executed');
            final class ModeKernel {
                public static function typed(int ${'$'}a,int ${'$'}b):int{return ${'$'}a+${'$'}b;}
                public static function sum(){${'$'}a=5;${'$'}b=5;return ${'$'}a+${'$'}b;}
            }
        """.trimIndent()
        put("src/app/Kernel.php", phpSource)
        put("src/vendor/Library.php", phpSource.replace("ModeKernel", "VendorKernel"))
        put("vendor/External.php", phpSource.replace("ModeKernel", "ExternalKernel"))
        put("Loose.php", phpSource.replace("ModeKernel", "LooseKernel"))
        for (mode in listOf("off", "checked", "auto")) {
            setting(mode)
            check(runtime.call("state")["ownershipMode"].asString == mode)
            val listing = inspect("src/app/Kernel.php")
            check(listing.source["ownershipMode"].asString == mode)
            check(listing.methods.any { it.startsWith(scalar + "sum\$") } == (mode != "checked")) { "Worker ignored $mode inferred-kernel policy: ${listing.methods}" }
            check(listing.methods.any { it.startsWith(scalarEntry) } == (mode != "checked")) { "Worker ignored $mode scalar-entry policy" }
            check(listing.calls(ownershipSymbols) == (mode != "off")) { "Worker ignored $mode ownership instrumentation" }
            check(!Files.exists(marker))
            println("BYTECODE_OWNERSHIP_WORKER_${mode.uppercase()}_REAL_CODEGEN_PASS")
        }
        setting("off")
        for (path in listOf("src/vendor/Library.php", "vendor/External.php")) {
            val vendor = inspect(path)
            check(vendor.methods.none { it.startsWith(scalar) || it.startsWith(scalarEntry) } &&
                vendor.authored.none { it == path.removePrefix("src/") || it == path } && vendor.authored.any { it.endsWith("app/Kernel.php") }) {
                "Project OFF escaped into SDK/legacy source: $path; inventory=${vendor.authored}"
            }
        }
        devline.compiler.PhpProjectPackage.materialize(project.resolve("src"), "fixture")
        val compiled = root.resolve("phb")
        compilePhb(root, project.resolve("src"), compiled, owned, "off")
        val artifact = project.resolve("src/compiled/Kernel.phb")
        Files.createDirectories(artifact.parent); Files.copy(compiled.resolve("app/Kernel.phb"), artifact)
        Files.newInputStream(artifact).use { check(php.runtime.loader.dump.ModuleDumper.readHeader(it).ownershipMode == php.runtime.common.PhpRuntimeOwnershipMode.OFF) }
        setting("checked")
        val immutable = inspect("src/compiled/Kernel.phb")
        check(immutable.methods.any { it.startsWith(scalar + "sum\$") } && !immutable.calls(ownershipSymbols)) { "Current project setting changed stored PHB policy" }
        setting("off")
        val outside = inspect("Loose.php")
        check(outside.methods.none { it.startsWith(scalar) || it.startsWith(scalarEntry) } && "Loose.php" !in outside.authored) {
            "PHP outside canonical src inherited authored OFF policy; inventory=${outside.authored}"
        }
        val inventory = project.resolve("src/META-INF/devline/php85-sources.list")
        Files.writeString(inventory, "")
        val emptyInventorySdk = inspect("src/vendor/Library.php")
        check(emptyInventorySdk.authored.isNotEmpty() && "vendor/Library.php" !in emptyInventorySdk.authored)
        Files.writeString(inventory, "app/Kernel.php\nvendor/Library.php\n")
        val staleInventorySdk = inspect("src/vendor/Library.php")
        check("vendor/Library.php" !in staleInventorySdk.authored && staleInventorySdk.methods.none { it.startsWith(scalar) })
        check(!Files.exists(marker)) { "Bytecode inspection executed user PHP" }
        println("BYTECODE_OWNERSHIP_MIXED_SOURCE_OFF_CHECKED_AUTO_VENDOR_LEGACY_OUTSIDE_SRC_PHB_FROZEN_NO_EXECUTION_PASS")
    } catch (failure: Throwable) { primaryFailure = failure; throw failure }
    finally { cleanupRuntime(runtime, owned, root, primaryFailure) }
}
