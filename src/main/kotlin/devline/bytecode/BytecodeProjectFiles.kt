package devline.bytecode

import com.google.gson.JsonObject
import devline.extensions.IdeExtensionContext
import devline.extensions.IdeExtensionException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal data class BytecodeInput(val path: String, val kind: String, val file: Path, val projectRoot: Path, val origin: String, val version: String,
    val optimizationProfile: String = devline.settings.PhpOptimizationSettings.COMPATIBLE, val modernSource: Boolean = false,
    val ownershipMode: String = devline.settings.PhpRuntimeOwnershipSettings.AUTO)

internal data class BytecodeSourceSnapshot(val root: Path, val relative: String, val authoredPrefix: String)

internal class BytecodeProjectFiles(private val context: IdeExtensionContext) {
    private val excluded = setOf(".git", ".devline", "node_modules", "build", "dist", ".gradle", ".idea")
    fun root(project: String): Path = Path.of(context.projectRoot(project)).toRealPath()
    fun kind(path: String): String = path.substringAfterLast('.', "").lowercase()
    fun inventory(project: String): List<BytecodeInput> = scan(root(project), "file", root(project), "", true)
    fun builds(project: String): List<BytecodeInput> {
        val snapshot = try { context.callProject(project, "application.inspection", JsonObject()) }
            catch (failure: IdeExtensionException) { if (failure.code in setOf("UNAVAILABLE", "UNKNOWN_METHOD")) return emptyList() else throw failure }
        if (snapshot.isJsonNull) return emptyList()
        val value = snapshot.asJsonObject; val root = root(project)
        val operation = value["operationId"].asString
        require(operation.matches(Regex("[A-Za-z0-9-]{1,100}")))
        return value.getAsJsonArray("roots").take(32).flatMapIndexed { index, entry ->
            val path = Path.of(entry.asString).toRealPath()
            // The host chooses roots; never accept an arbitrary path from the webview.
            require(path.startsWith(root.resolve(".devline/build/application").toRealPath()))
            if (Files.isDirectory(path)) scan(path, "build", root, "$operation/$index/", false)
            else listOf(BytecodeInput("$operation/$index/${path.fileName}", kind(path.toString()), path, root, "build", version(path)))
        }.take(1500)
    }
    private fun scan(base: Path, origin: String, root: Path, prefix: String, source: Boolean): List<BytecodeInput> {
        val result = ArrayList<BytecodeInput>(); var visited = 0
        Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (++visited > 20000 || result.size >= 1500) return FileVisitResult.TERMINATE
                return if (dir != base && (dir.fileName.toString() in excluded || Files.isSymbolicLink(dir))) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (++visited > 20000 || result.size >= 1500) return FileVisitResult.TERMINATE
                if (attrs.isRegularFile && !attrs.isSymbolicLink && kind(file.toString()) in if (source) setOf("php", "kt", "java", "class", "phb", "jar") else setOf("class", "phb", "jar")) {
                    val path = prefix + base.relativize(file).toString().replace('\\', '/')
                    result += BytecodeInput(path, kind(path), file, root, origin, "${attrs.lastModifiedTime().toMillis()}:${attrs.size()}")
                }
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
        })
        return result.sortedBy { it.path }
    }
    fun resolve(project: String, path: String, origin: String): BytecodeInput {
        require(path.length in 1..4096 && origin in setOf("file", "build"))
        if (origin == "file") {
            if (path.startsWith('/') || path.contains('\\') || path.contains(':') || path.split('/').any { it in setOf("", ".", "..") })
                throw IdeExtensionException("FILE_NOT_FOUND", "Choose a file inside this project.")
            val root = root(project)
            val file = try { root.resolve(path).normalize().toRealPath() } catch (_: java.io.IOException) { throw IdeExtensionException("FILE_NOT_FOUND", "The project file is unavailable.") }
            if (!file.startsWith(root) || !Files.isRegularFile(file) || kind(path) !in setOf("php", "phb", "class", "jar", "java", "kt"))
                throw IdeExtensionException("FILE_NOT_FOUND", "Choose a supported file in this project.")
            return BytecodeInput(path, kind(path), file, root, origin, version(file))
        }
        return builds(project).firstOrNull { it.path == path }
            ?: throw IdeExtensionException("FILE_NOT_FOUND", "Choose an available project file or prepared build output.")
    }
    fun sourcePath(project: String, input: BytecodeInput, source: String?): String? {
        val files = inventory(project).filter { it.kind in setOf("php", "kt", "java") }
        val normalized = source?.replace('\\', '/')?.removePrefix("res://")?.trimStart('/') ?: return null
        val exact = files.filter { it.path == normalized || it.path == "src/$normalized" }
        if (exact.size == 1) return exact.single().path
        val suffix = files.filter { it.path.substringAfterLast('/') == normalized.substringAfterLast('/') }
        if (suffix.size == 1) return suffix.single().path
        return input.path.takeIf { input.origin == "file" && input.kind in setOf("php", "kt", "java") && normalized.substringAfterLast('/') == input.file.fileName.toString() }
    }
    companion object {
        fun version(path: Path) = "${Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()}:${Files.size(path)}"
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun copySource(input: BytecodeInput, directory: Path): BytecodeSourceSnapshot {
            val src = input.projectRoot.resolve("src")
            val base = if (input.file.startsWith(src)) src else input.projectRoot
            val relative = base.relativize(input.file).toString().replace('\\', '/')
            var bytes = 0L; var count = 0
            Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir != base && (dir.fileName.toString() in setOf(".git", ".devline", "node_modules", "build", "dist", ".gradle") || Files.isSymbolicLink(dir))) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isSymbolicLink || !attrs.isRegularFile) return FileVisitResult.CONTINUE
                    val name = file.fileName.toString()
                    if (name.substringAfterLast('.') !in setOf("php", "kt", "java", "pkg") && name != "php85-sources.list") return FileVisitResult.CONTINUE
                    bytes += attrs.size(); require(++count <= 10000 && bytes <= 32L * 1024 * 1024 && attrs.size() <= 4L * 1024 * 1024) { "Source snapshot exceeds the inspection limit" }
                    val target = directory.resolve(base.relativize(file)); Files.createDirectories(target.parent)
                    val data = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(4 * 1024 * 1024 + 1) }
                    require(data.size <= 4 * 1024 * 1024) { "Source file exceeds the inspection limit" }
                    Files.write(target, data, StandardOpenOption.CREATE_NEW)
                    require(attrs.size() == Files.size(file) && attrs.lastModifiedTime() == Files.getLastModifiedTime(file)) { "Source changed while its snapshot was prepared" }
                    return FileVisitResult.CONTINUE
                }
            })
            require(Files.isRegularFile(directory.resolve(relative))) { "Selected source is missing" }
            val authoredPrefix = base.relativize(src).toString().replace('\\', '/').let { if (it.isEmpty()) "" else "$it/" }
            return BytecodeSourceSnapshot(directory, relative, authoredPrefix)
        }
    }
}
