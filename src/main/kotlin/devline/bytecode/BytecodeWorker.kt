package devline.bytecode

import com.google.gson.Gson
import com.google.gson.JsonParser
import devline.compiler.PhpCompiler
import php.runtime.common.PhpLanguageProfile
import php.runtime.env.CompileScope
import php.runtime.env.Context
import php.runtime.env.Environment
import php.runtime.ext.support.Extension
import php.runtime.loader.dump.ModuleDumper
import php.runtime.reflection.ClassEntity
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.io.Writer
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider

private const val MAX_INPUT_BYTES = 64L * 1024 * 1024
private const val MAX_EXPANDED_BYTES = 16 * 1024 * 1024
private const val MAX_CLASSES = 128
private const val MAX_ENTRIES = 5000
private const val MAX_RESULT_CHARS = 32 * 1024 * 1024
private val trustedSdkExtensions = listOf("devline.ui.jphp.UiExtension", "devline.platform.PlatformExtension", "devline.localization.LocalesExtension")

private data class WorkerResult(val classes: List<BytecodeClass> = emptyList(), val warnings: List<String> = emptyList(),
    val errorCode: String? = null, val message: String? = null, val stack: String? = null)
private class WorkerFailure(val code: String, message: String) : IllegalArgumentException(message)

private fun boundedStack(failure: Throwable): String = buildString {
    append(failure.javaClass.name); append(": "); append(failure.message?.take(512)); append('\n')
    for (frame in failure.stackTrace.take(32)) {
        if (length >= 3600) break
        append("  at "); append(frame.toString().take(384)); append('\n')
    }
}.take(4000)

/** Resolves decoded dependency declarations without ClassEntity.initEnvironment or application class loading. */
private class MetadataEnvironment(scope: CompileScope) : Environment(scope, OutputStream.nullOutputStream()) {
    // Environment's constructor calls virtual fetchClass before Kotlin field initialization.
    private var declarations: MutableMap<String, ClassEntity>? = null
    private fun declaration(name: String) = declarations?.get(name.trimStart('\\').lowercase(java.util.Locale.ROOT))
    fun remember(entity: ClassEntity) {
        val entries = declarations ?: hashMapOf<String, ClassEntity>().also { declarations = it }
        entries.putIfAbsent(entity.name.trimStart('\\').lowercase(java.util.Locale.ROOT), entity)
    }
    override fun autoloadCall(name: String, lowerName: String): ClassEntity? = null
    override fun fetchClass(name: String): ClassEntity? = declaration(name) ?: super.fetchClass(name, false)
    override fun fetchClass(name: String, autoLoad: Boolean): ClassEntity? = declaration(name) ?: super.fetchClass(name, false)
    override fun fetchClass(name: String, autoLoad: Boolean, profile: PhpLanguageProfile): ClassEntity? = declaration(name) ?: super.fetchClass(name, false, profile)
    override fun fetchClass(name: String, lowerName: String, autoLoad: Boolean): ClassEntity? = declaration(name) ?: super.fetchClass(name, lowerName, false)
}

/** Separate bounded child process. User classes are read as data, never defined, initialized or invoked. */
fun main(args: Array<String>) {
    if (args.size != 2) { System.err.println("BytecodeWorker request.json output.json"); return }
    val output = Path.of(args[1]).toAbsolutePath().normalize()
    var emergency: ByteArray? = ByteArray(256 * 1024)
    val result = try {
        val requestPath = Path.of(args[0]).toAbsolutePath().normalize()
        require(Files.size(requestPath) <= 64 * 1024) { "Inspection request is too large" }
        val request = JsonParser.parseString(Files.readString(requestPath, Charsets.UTF_8)).asJsonObject
        require(request.keySet().all { it in setOf("path", "kind", "sourceRoot", "relative", "optimizationProfile", "modernSource", "ownershipMode", "authoredSourcePrefix") }) { "Unknown inspection request field" }
        val path = Path.of(request["path"].asString)
        require(path.isAbsolute && Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Staged input file is missing" }
        val root = request["sourceRoot"]?.takeUnless { it.isJsonNull }?.asString?.let(Path::of)
        if (root != null) require(root.isAbsolute && Files.isDirectory(root, NOFOLLOW_LINKS) && path.toRealPath().startsWith(root.toRealPath())) { "Input is outside its staged source root" }
        val profile = devline.settings.PhpOptimizationSettings.profile(request["optimizationProfile"]?.asString)
        val ownership = devline.settings.PhpRuntimeOwnershipSettings.mode(request["ownershipMode"]?.asString)
        val authoredPrefix = request["authoredSourcePrefix"]?.asString ?: ""
        require(authoredPrefix.length <= 4096 && ':' !in authoredPrefix && '\\' !in authoredPrefix && !authoredPrefix.startsWith('/') &&
            (authoredPrefix.isEmpty() || authoredPrefix.endsWith('/') && authoredPrefix.dropLast(1).split('/').none { it.isEmpty() || it in setOf(".", "..") })) {
            "Invalid authored source prefix"
        }
        BytecodeInspection(output.parent, profile, request["modernSource"]?.asBoolean == true, ownership, authoredPrefix).inspect(path.toRealPath(), request["kind"].asString, root?.toRealPath(), request["relative"]?.takeUnless { it.isJsonNull }?.asString)
    } catch (failure: Throwable) {
        emergency = null
        val cause = if (failure is InvocationTargetException) failure.targetException else failure
        val code = when (cause) {
            is WorkerFailure -> cause.code
            is BytecodeReadException -> cause.code
            is OutOfMemoryError -> "LIMIT_EXCEEDED"
            else -> "INSPECTION_FAILED"
        }
        WorkerResult(errorCode = code, message = (cause.message ?: cause.javaClass.simpleName).take(4000), stack = boundedStack(cause))
    }
    // Keep a small reserve available until parsing has completed, including malformed PHB allocation failures.
    if (emergency != null) emergency = null
    Files.createDirectories(output.parent)
    val temporary = Files.createTempFile(output.parent, ".bytecode-result-", ".json")
    try {
        Files.newBufferedWriter(temporary, Charsets.UTF_8).use { target ->
            var characters = 0
            val bounded = object : Writer() {
                override fun write(buffer: CharArray, offset: Int, length: Int) {
                    characters += length
                    if (characters > MAX_RESULT_CHARS) throw WorkerFailure("LIMIT_EXCEEDED", "Inspection result exceeds 32 MiB")
                    target.write(buffer, offset, length)
                }
                override fun flush() = target.flush()
                override fun close() = Unit
            }
            Gson().toJson(result, bounded)
        }
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
    } catch (failure: Throwable) {
        Files.writeString(output, Gson().toJson(WorkerResult(errorCode = "LIMIT_EXCEEDED", message = (failure.message ?: "Unable to write inspection result").take(4000))), Charsets.UTF_8)
    } finally { Files.deleteIfExists(temporary) }
}

private class BytecodeInspection(private val outputRoot: Path, private val optimizationProfile: String, private val modernSource: Boolean,
    private val ownershipMode: String, private val authoredPrefix: String) {
    private val reader = BytecodeReader()
    private val classes = linkedMapOf<String, BytecodeClass>()
    private val warnings = linkedSetOf<String>()
    private var expanded = 0L
    private var listingCharacters = 0L
    private val metadataEnvironment by lazy {
        val scope = CompileScope()
        sdkExtensions().forEach { scope.registerExtension(it) }
        MetadataEnvironment(scope)
    }

    fun inspect(path: Path, kind: String, sourceRoot: Path?, relative: String?): WorkerResult {
        if (Files.size(path) > MAX_INPUT_BYTES) fail("LIMIT_EXCEEDED", "Input exceeds 64 MiB")
        when (kind) {
            "class" -> add(readBounded(path, BytecodeLimits.MAX_CLASS_BYTES), path.fileName.toString(), required = true)
            "phb" -> phb(readBounded(path, MAX_EXPANDED_BYTES), path.fileName.toString())
            "jar" -> jar(path)
            "php", "java", "kt" -> compile(path, kind, sourceRoot, relative)
            else -> fail("UNSUPPORTED_INPUT", "Unsupported inspection kind: $kind")
        }
        if (classes.isEmpty()) fail("NO_BYTECODE", "No JVM class bytes were found${if (warnings.isEmpty()) "" else ": ${warnings.first()}"}")
        return WorkerResult(classes.values.toList(), warnings.toList())
    }

    private fun warn(message: String) { if (warnings.size < 32) warnings += message.take(512) }
    private fun fail(code: String, message: String): Nothing = throw WorkerFailure(code, message)
    private fun readBounded(path: Path, limit: Int): ByteArray = Files.newInputStream(path).use { stream ->
        val bytes = stream.readNBytes(limit + 1)
        if (bytes.size > limit) fail("LIMIT_EXCEEDED", "${path.fileName} exceeds $limit bytes")
        bytes
    }
    private fun add(bytes: ByteArray?, label: String, required: Boolean = false) {
        if (bytes == null || bytes.size < 4 || bytes[0] != 0xca.toByte() || bytes[1] != 0xfe.toByte() || bytes[2] != 0xba.toByte() || bytes[3] != 0xbe.toByte()) {
            if (required) fail("INVALID_CLASS", "$label is not a JVM class file")
            warn("$label contains no JVM class bytes"); return
        }
        if (classes.size >= MAX_CLASSES) { warn("Only the first $MAX_CLASSES classes are shown"); return }
        val value = reader.read(bytes)
        if (value.name in classes) return
        var text = value.name.length.toLong()
        value.fields.forEach { text += it.name.length + it.descriptor.length + (it.value?.length ?: 0) }
        value.methods.forEach { method ->
            text += method.name.length + method.descriptor.length
            method.instructions.forEach { text += it.mnemonic.length + it.operand.length + 64 }
            method.locals.forEach { text += it.name.length + it.descriptor.length + 64 }
        }
        listingCharacters += text
        if (listingCharacters > MAX_RESULT_CHARS / 2) fail("LIMIT_EXCEEDED", "Combined bytecode listing exceeds inspection limit")
        classes[value.name] = value
    }

    private fun sdkExtensions(): List<String> = trustedSdkExtensions.filter { name ->
        try { Class.forName(name, false, javaClass.classLoader).asSubclass(Extension::class.java); true }
        catch (_: ClassNotFoundException) { false }
        catch (_: LinkageError) { false }
    }

    private fun phb(bytes: ByteArray, label: String) {
        val env = metadataEnvironment
        try {
            val module = ModuleDumper(Context(bytes.inputStream(), label, Charsets.UTF_8), env, true).load(bytes.inputStream())
            add(module.data, label)
            module.classes.forEach { add(it.data, "$label:${it.name}") }
            module.deferredClasses.forEach { add(it.data, "$label:${it.name}") }
            module.functions.forEach { add(it.data, "$label:${it.name}") }
            module.closures.forEach { add(it.data, "$label:closure") }
            module.generators.forEach { add(it.data, "$label:generator") }
            module.classes.forEach(env::remember)
        } catch (failure: WorkerFailure) { throw failure }
        catch (failure: BytecodeReadException) { throw failure }
        catch (failure: Exception) {
            fail("PHB_METADATA_UNAVAILABLE", "$label: ${failure.message ?: failure.javaClass.simpleName}. Referenced parent types may require a trusted SDK; project code was not executed.")
        }
        // Do not run env.doFinal(): this metadata-only environment must not invoke PHP destructors/shutdown handlers.
    }

    private fun jar(path: Path) {
        ZipFile(path.toFile()).use { zip ->
            if (zip.size() > MAX_ENTRIES) fail("LIMIT_EXCEEDED", "Archive exceeds $MAX_ENTRIES entries")
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(".class", true) && !entry.name.endsWith(".phb", true)) continue
                if (classes.size >= MAX_CLASSES) { warn("Only the first $MAX_CLASSES classes are shown"); break }
                val limit = if (entry.name.endsWith(".class", true)) BytecodeLimits.MAX_CLASS_BYTES else MAX_EXPANDED_BYTES
                val remaining = MAX_EXPANDED_BYTES - expanded.toInt()
                if (entry.size > minOf(limit, remaining)) fail("LIMIT_EXCEEDED", "Archive class/module payload exceeds the 16 MiB expanded limit")
                val bytes = zip.getInputStream(entry).use { it.readNBytes(minOf(limit, remaining) + 1) }
                if (bytes.size > limit || bytes.size > remaining) fail("LIMIT_EXCEEDED", "Archive class/module payload exceeds the 16 MiB expanded limit")
                expanded += bytes.size
                try {
                    if (entry.name.endsWith(".phb", true)) phb(bytes, entry.name.take(512)) else add(bytes, entry.name.take(512), required = true)
                } catch (failure: WorkerFailure) {
                    if (failure.code == "LIMIT_EXCEEDED") throw failure
                    warn("${entry.name}: ${failure.message}")
                } catch (failure: BytecodeReadException) {
                    if (failure.code == "LIMIT_EXCEEDED") throw failure
                    warn("${entry.name}: ${failure.message}")
                }
            }
        }
    }

    private fun compile(path: Path, kind: String, sourceRoot: Path?, relative: String?) {
        if (Files.size(path) > BytecodeLimits.MAX_CLASS_BYTES) fail("LIMIT_EXCEEDED", "Source exceeds 4 MiB")
        Files.createDirectories(outputRoot)
        val directory = Files.createTempDirectory(outputRoot, ".bytecode-compile-").toRealPath()
        val destination = Files.createDirectory(directory.resolve("classes"))
        try {
            when (kind) {
                "php" -> {
                    val root = sourceRoot ?: path.parent
                    val entry = relative ?: root.relativize(path).toString().replace('\\', '/')
                    if (entry.startsWith('/') || '\\' in entry || entry.split('/').any { it.isBlank() || it == "." || it == ".." } || root.resolve(entry).toRealPath() != path)
                        fail("INVALID_REQUEST", "Invalid staged PHP source path")
                    val sourceIndex = root.resolve("META-INF/devline/php85-sources.list")
                    if (modernSource) {
                        val authoredRoot = root.resolve(authoredPrefix).normalize()
                        require(authoredRoot.startsWith(root) && Files.isDirectory(authoredRoot, NOFOLLOW_LINKS) && authoredRoot.toRealPath() == authoredRoot) {
                            "Authored PHP source root is outside its snapshot"
                        }
                        val authored = Files.walk(authoredRoot).use { files ->
                            val inventory = files.limit(50001).toList()
                            require(inventory.size <= 50000) { "Source profile inventory exceeds 50000 entries" }
                            inventory.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && it.fileName.toString().endsWith(".php") }
                                .map { root.relativize(it).toString().replace('\\', '/') }
                                .filter { devline.settings.PhpRuntimeOwnershipSettings.isAuthoredSource(it.removePrefix(authoredPrefix)) }.sorted()
                        }
                        require(authored.size <= 10000) { "Source profile inventory exceeds 10000 files" }
                        Files.createDirectories(sourceIndex.parent); Files.writeString(sourceIndex, authored.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
                    }
                    val compiled = PhpCompiler(sdkExtensions(), emptyList(), optimizationProfile, ownershipMode).compileFile(root, entry, destination)
                    compiled.forEach { item ->
                        if (classes.size >= MAX_CLASSES) { warn("Only the first $MAX_CLASSES classes are shown"); return@forEach }
                        val file = destination.resolve(item.bytecode).normalize()
                        require(file.startsWith(destination)) { "Compiler output escapes its directory" }
                        val bytes = readBounded(file, MAX_EXPANDED_BYTES)
                        expanded += bytes.size
                        if (expanded > MAX_EXPANDED_BYTES) fail("LIMIT_EXCEEDED", "Compiler output exceeds 16 MiB")
                        phb(bytes, item.source)
                    }
                }
                "java" -> { java(path, destination, Files.createDirectory(directory.resolve("empty-sourcepath"))); readClasses(destination) }
                "kt" -> { kotlin(path, destination); readClasses(destination) }
            }
        } finally {
            // This exact real directory was created by this worker, outside staged sources.
            check(directory.startsWith(outputRoot.toRealPath()))
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun trustedClasspath(): String = System.getProperty("java.class.path").split(File.pathSeparator)
        .filter(String::isNotBlank).map { Path.of(it).toAbsolutePath().normalize() }.filter(Files::exists).joinToString(File.pathSeparator)

    private fun java(path: Path, destination: Path, sourcepath: Path) {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: fail("SOURCE_COMPILER_UNAVAILABLE", "The bundled runtime has no Java compiler")
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, java.util.Locale.ROOT, Charsets.UTF_8).use { manager ->
            val options = listOf("-proc:none", "-implicit:none", "-g", "-encoding", "UTF-8", "-Xmaxerrs", "20", "-Xmaxwarns", "20",
                "-classpath", trustedClasspath(), "-sourcepath", sourcepath.toString(), "-d", destination.toString())
            val success = compiler.getTask(null, manager, diagnostics, options, null, manager.getJavaFileObjects(path.toFile())).call()
            val messages = diagnostics.diagnostics.take(20).joinToString("\n") { "${it.source?.name ?: path.fileName}:${it.lineNumber}:${it.columnNumber}: ${it.getMessage(java.util.Locale.ROOT)}" }
            if (!success) fail("SOURCE_COMPILATION_FAILED", messages.take(4000))
            if (messages.isNotBlank()) warn(messages)
        }
    }

    private fun kotlin(path: Path, destination: Path) {
        val compiler = try { Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", false, javaClass.classLoader) }
        catch (_: ClassNotFoundException) { fail("SOURCE_COMPILER_UNAVAILABLE", "The bundled runtime has no Kotlin compiler") }
        val output = object : OutputStream() {
            val bytes = ByteArrayOutputStream()
            override fun write(value: Int) { if (bytes.size() < 64 * 1024) bytes.write(value) }
        }
        val arguments = arrayOf("-no-reflect", "-no-stdlib", "-jvm-target", Runtime.version().feature().coerceAtMost(25).toString(),
            "-classpath", trustedClasspath(), "-d", destination.toString(), path.toString())
        val result = PrintStream(output, true, Charsets.UTF_8).use { stream ->
            compiler.getMethod("exec", PrintStream::class.java, Array<String>::class.java)
                .invoke(compiler.getConstructor().newInstance(), stream, arguments as Any)
        }
        val diagnostics = output.bytes.toString(Charsets.UTF_8)
        if (result.toString() != "OK") fail("SOURCE_COMPILATION_FAILED", diagnostics.take(4000))
        if (diagnostics.isNotBlank()) warn(diagnostics)
    }

    private fun readClasses(destination: Path) {
        Files.walk(destination).use { paths -> paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && it.fileName.toString().endsWith(".class") }.sorted().limit((MAX_CLASSES + 1).toLong()).forEach { file ->
            if (classes.size >= MAX_CLASSES) warn("Only the first $MAX_CLASSES classes are shown")
            else {
                val bytes = readBounded(file, BytecodeLimits.MAX_CLASS_BYTES)
                expanded += bytes.size
                if (expanded > MAX_EXPANDED_BYTES) fail("LIMIT_EXCEEDED", "Compiler output exceeds 16 MiB")
                add(bytes, destination.relativize(file).toString())
            }
        } }
    }
}
