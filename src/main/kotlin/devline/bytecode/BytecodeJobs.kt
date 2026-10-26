package devline.bytecode

import com.google.gson.*
import devline.extensions.IdeExtensionException
import java.net.URLClassLoader
import java.nio.file.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Disposable compiler/parser processes. No application bytecode is loaded into the IDE JVM. */
internal class BytecodeJobs(private val directory: Path) : AutoCloseable {
    private val json = GsonBuilder().serializeNulls().create()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "devline-bytecode").apply { isDaemon = true } }
    private val jobs = linkedMapOf<String, Job>()
    @Volatile private var closed = false
    internal class Job(val id: String, val project: String, val input: BytecodeInput, val directory: Path) {
        @Volatile var state = "preparing"
        @Volatile var errorCode: String? = null
        @Volatile var message: String? = null
        @Volatile var process: Process? = null
        @Volatile var result: JsonObject? = null
        @Volatile var source: JsonObject? = null
        @Volatile var releaseOnFinish = false
        @Volatile var finished = false
        fun view() = JsonObject().apply { addProperty("id", id); addProperty("state", state); errorCode?.let { addProperty("errorCode", it) }; message?.let { addProperty("message", it) } }
    }
    @Synchronized fun start(project: String, input: BytecodeInput): Job {
        check(!closed)
        while (jobs.size >= 2) {
            val first = jobs.values.first(); first.releaseOnFinish = true
            cancel(first); jobs.remove(first.id); first.result = null
            if (first.finished) releaseFiles(first)
        }
        Files.createDirectories(directory)
        val id = UUID.randomUUID().toString(); val job = Job(id, project, input, Files.createDirectory(directory.resolve(id)))
        jobs[id] = job
        executor.submit { prepare(job) }
        return job
    }
    @Synchronized fun get(project: String, id: String): Job = jobs[id]?.takeIf { it.project == project }
        ?: throw IdeExtensionException("BYTECODE_SESSION_EXPIRED", "This inspection is no longer available. Inspect the file again.")
    fun cancel(job: Job) {
        synchronized(job) {
            if (job.state == "preparing") job.state = "cancelled"
            // Compilers run inside this exact worker JVM; never enumerate PID descendants.
            job.process?.destroyForcibly()
        }
    }
    @Synchronized fun cancelProject(project: String?) {
        jobs.values.filter { project == null || it.project == project }.forEach { job ->
            // Set before observing finished so completion cannot miss ownership release.
            job.releaseOnFinish = true
            cancel(job); jobs.remove(job.id); job.result = null
            if (job.finished) releaseFiles(job)
        }
    }
    private fun active(job: Job) { if (closed || job.state == "cancelled") throw java.util.concurrent.CancellationException() }
    private fun prepare(job: Job) {
        try {
            active(job)
            require(Files.size(job.input.file) <= if (job.input.kind == "jar") 64L * 1024 * 1024 else 4L * 1024 * 1024) { "File exceeds the inspection limit" }
            val request = JsonObject().apply { addProperty("kind", job.input.kind) }
            if (job.input.kind == "php") {
                request.addProperty("optimizationProfile", job.input.optimizationProfile); request.addProperty("modernSource", job.input.modernSource)
                request.addProperty("ownershipMode", job.input.ownershipMode)
            }
            val selected: Path
            if (job.input.kind in setOf("php", "kt", "java")) {
                val (root, relative, authoredPrefix) = BytecodeProjectFiles.copySource(job.input, job.directory.resolve("source"))
                selected = root.resolve(relative)
                request.addProperty("sourceRoot", root.toString()); request.addProperty("relative", relative)
                if (job.input.kind == "php" && job.input.modernSource) request.addProperty("authoredSourcePrefix", authoredPrefix)
            } else {
                selected = job.directory.resolve("input.${job.input.kind}")
                val limit = if (job.input.kind == "jar") 64L * 1024 * 1024 else 4L * 1024 * 1024
                Files.newInputStream(job.input.file).use { source -> Files.newOutputStream(selected, StandardOpenOption.CREATE_NEW).use { target ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) {
                        val count = source.read(buffer); if (count < 0) break
                        total += count; require(total <= limit) { "File exceeds the inspection limit" }; active(job); target.write(buffer, 0, count)
                    }
                } }
                require(job.input.version == BytecodeProjectFiles.version(job.input.file)) { "File changed while its snapshot was prepared" }
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(selected).use { source ->
                val buffer = ByteArray(65536)
                while (true) { val count = source.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            job.source = JsonObject().apply {
                addProperty("path", job.input.path); addProperty("kind", job.input.kind); addProperty("origin", job.input.origin)
                addProperty("sha256", sha); addProperty("generatedAt", Instant.now().toString())
                if (job.input.kind == "php" && job.input.origin == "file") addProperty("optimizationProfile", job.input.optimizationProfile)
                if (job.input.kind == "php" && job.input.origin == "file") addProperty("ownershipMode", job.input.ownershipMode)
            }
            request.addProperty("path", selected.toString())
            val input = job.directory.resolve("request.json"); val output = job.directory.resolve("result.json")
            Files.writeString(input, json.toJson(request))
            active(job)
            val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            val command = listOf(java.toString(), "-Xms32m", "-Xmx256m", "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-cp", workerClasspath(), "devline.bytecode.BytecodeWorkerKt", input.toString(), output.toString())
            val process = ProcessBuilder(command).directory(job.directory.toFile()).redirectErrorStream(true).redirectOutput(job.directory.resolve("worker.log").toFile()).start()
            job.process = process
            active(job)
            if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); throw IdeExtensionException("BYTECODE_TIMEOUT", "Bytecode inspection exceeded 90 seconds.") }
            active(job)
            if (!Files.isRegularFile(output)) throw IdeExtensionException("BYTECODE_COMPILE_FAILED", Files.readString(job.directory.resolve("worker.log")).takeLast(3000).ifBlank { "The compiler/parser process failed." })
            require(Files.size(output) <= 32L * 1024 * 1024) { "Analysis exceeds the inspection limit" }
            val result = JsonParser.parseString(Files.readString(output)).asJsonObject
            result["errorCode"]?.let { throw IdeExtensionException(it.asString, result["message"]?.asString ?: "Inspection failed.") }
            require(process.exitValue() == 0 && result.has("classes")) { "The compiler/parser process failed" }
            synchronized(job) { active(job); job.result = result; job.state = "ready" }
        } catch (_: java.util.concurrent.CancellationException) { job.state = "cancelled" }
        catch (failure: Throwable) {
            if (job.state != "cancelled") {
                job.errorCode = (failure as? IdeExtensionException)?.code ?: "BYTECODE_INSPECTION_FAILED"
                job.message = (failure.message ?: "Bytecode inspection failed.").take(4000); job.state = "failed"
            }
        } finally {
            val process = job.process
            try {
                process?.let { if (it.isAlive) it.destroyForcibly(); it.waitFor(3, TimeUnit.SECONDS) }
            } finally {
                job.process = null; job.finished = true
                if (closed || job.state == "cancelled" || job.releaseOnFinish) releaseFiles(job)
            }
        }
    }
    private fun workerClasspath(): String {
        val paths = linkedSetOf<Path>()
        fun add(value: String) { if (value.isNotBlank()) runCatching { Path.of(value).toAbsolutePath().normalize() }.getOrNull()?.takeIf(Files::exists)?.let(paths::add) }
        System.getProperty("java.class.path").split(java.io.File.pathSeparator).forEach(::add)
        var loader: ClassLoader? = javaClass.classLoader
        while (loader != null) {
            (loader as? URLClassLoader)?.urLs?.forEach { if (it.protocol == "file") add(Path.of(it.toURI()).toString()) }
            loader = loader.parent
        }
        for (name in listOf("devline.bytecode.BytecodeExtension", "devline.compiler.PhpCompiler", "com.google.gson.Gson", "php.runtime.Memory", "org.develnext.jphp.core.compiler.jvm.JvmCompiler", "org.objectweb.asm.ClassReader", "org.objectweb.asm.util.Printer")) {
            val type = Class.forName(name, false, javaClass.classLoader)
            type.protectionDomain.codeSource?.location?.let { add(Path.of(it.toURI()).toString()) }
        }
        System.getProperty("jdk.module.path", "").split(java.io.File.pathSeparator).filter(String::isNotBlank).forEach { entry ->
            val path = Path.of(entry)
            if (Files.isDirectory(path)) Files.list(path).use { stream -> stream.filter { it.toString().endsWith(".jar") }.forEach { add(it.toString()) } } else add(entry)
        }
        return paths.joinToString(java.io.File.pathSeparator)
    }
    @Synchronized override fun close() {
        closed = true; jobs.values.forEach(::cancel); executor.shutdownNow()
        jobs.values.filter { it.process == null && it.state != "cancelled" }.forEach(::releaseFiles)
        // Only exact directories created by this service instance may be removed.
        if (executor.awaitTermination(5, TimeUnit.SECONDS)) { jobs.values.forEach(::releaseFiles); jobs.clear() }
    }
    private fun releaseFiles(job: Job) = synchronized(job) { job.result = null; remove(job.directory) }
    private fun remove(path: Path) {
        val owner = directory.toAbsolutePath().normalize(); val target = path.toAbsolutePath().normalize()
        require(target.parent == owner && target.fileName.toString().matches(Regex("[0-9a-f-]{36}")))
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.walk(target).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
