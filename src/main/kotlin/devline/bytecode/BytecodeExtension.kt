package devline.bytecode

import com.google.gson.*
import devline.extensions.*

class BytecodeExtension : IdeExtensionProvider {
    override val manifest: IdeExtensionManifest = javaClass.getResourceAsStream("/bytecode-extension.json")!!.reader(Charsets.UTF_8).use {
        Gson().fromJson(JsonParser.parseReader(it).asJsonObject["extension"], IdeExtensionManifest::class.java)
    }
    override fun activate(context: IdeExtensionContext): IdeExtensionRuntime = BytecodeRuntime(context)
}

internal class BytecodeRuntime(private val context: IdeExtensionContext) : IdeExtensionRuntime, IdeExtensionWebviewLifecycle {
    private val json = GsonBuilder().serializeNulls().create()
    private val files = BytecodeProjectFiles(context)
    private val settings = BytecodeSettings(context.profileDirectory.resolve("bytecode-viewer"))
    private var jobs = BytecodeJobs(context.profileDirectory.resolve("bytecode-viewer/sessions"))
    private fun project(params: JsonObject): String {
        val project = params["expectedProjectFile"]?.asString ?: context.currentProjectFile()
            ?: throw IdeExtensionException("NO_PROJECT", "Open a project first.")
        if (project != context.currentProjectFile()) throw IdeExtensionException("PROJECT_CHANGED", "The project changed.")
        files.root(project)
        return project
    }
    private fun string(params: JsonObject, name: String): String = params[name]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.length <= 4096 }
        ?: throw IdeExtensionException("INVALID_REQUEST", "Missing or invalid $name.")
    private fun offset(params: JsonObject) = params["offset"]?.asInt?.also { require(it in 0..1_000_000) } ?: 0
    private fun session(params: JsonObject) = jobs.get(project(params), string(params, "id"))
    private fun result(job: BytecodeJobs.Job): JsonObject = job.result?.takeIf { job.state == "ready" }
        ?: throw IdeExtensionException("BYTECODE_NOT_READY", "Inspection has not completed.")
    private fun clazz(job: BytecodeJobs.Job, params: JsonObject): JsonObject {
        val index = params["classIndex"]?.asInt ?: throw IdeExtensionException("INVALID_REQUEST", "Choose a class.")
        val classes = result(job).getAsJsonArray("classes")
        require(index in 0 until classes.size()); return classes[index].asJsonObject
    }
    private fun header(value: JsonObject, vararg excluded: String): JsonObject = JsonObject().apply {
        value.entrySet().forEach { (key, item) -> if (key !in excluded) add(key, item.deepCopy()) }
    }
    private fun paged(values: List<JsonElement>, offset: Int, key: String, metadata: JsonObject, maximum: Int = 100): JsonObject {
        require(offset <= values.size)
        val selected = values.drop(offset).take(maximum).toMutableList()
        while (true) {
            val response = metadata.deepCopy().apply {
                add(key, JsonArray().apply { selected.forEach(::add) })
                if (offset + selected.size < values.size) addProperty("nextOffset", offset + selected.size)
            }
            if (response.toString().toByteArray(Charsets.UTF_8).size <= 110 * 1024) return response
            if (selected.size <= 1) throw IdeExtensionException("BYTECODE_OUTPUT_LIMIT", "This metadata exceeds the viewer limit.")
            selected.removeAt(selected.lastIndex)
        }
    }
    override fun request(method: String, params: JsonObject): JsonElement = when (method.substringAfterLast('.')) {
        "settings" -> settings.view()
        "configure" -> settings.configure(params.getAsJsonObject("settings") ?: throw IdeExtensionException("INVALID_REQUEST", "Settings are required."), string(params, "version"))
        "reset" -> settings.reset(string(params, "version"))
        "state" -> {
            val view = settings.view(); val current = context.currentProjectFile()
            val source = current?.let(files::inventory).orEmpty().take(500).toMutableList()
            if (current != null && params.has("path")) {
                val selected = try { files.resolve(current, string(params, "path"), "file") }
                    catch (failure: IdeExtensionException) { if (failure.code == "FILE_NOT_FOUND") null else throw failure }
                if (selected != null) { source.removeAll { it.path == selected.path }; source.add(0, selected); while (source.size > 500) source.removeLast() }
            }
            val build = current?.let(files::builds).orEmpty().take(500).toMutableList()
            fun encode(list: List<BytecodeInput>) = json.toJsonTree(list.map { mapOf("path" to it.path, "kind" to it.kind, "version" to it.version) })
            val response = JsonObject().apply {
                current?.let { addProperty("projectFile", it) }; add("settings", view["settings"]); add("settingsVersion", view["version"])
                addProperty("optimizationProfile", current?.let { devline.settings.PhpOptimizationSettings.forProject(java.nio.file.Path.of(it)) }
                    ?: devline.settings.PhpOptimizationSettings.COMPATIBLE)
                addProperty("ownershipMode", current?.let { devline.settings.PhpRuntimeOwnershipSettings.forProject(java.nio.file.Path.of(it)) }
                    ?: devline.settings.PhpRuntimeOwnershipSettings.AUTO)
            }
            do {
                response.add("files", encode(source)); response.add("buildFiles", encode(build))
                if (response.toString().toByteArray(Charsets.UTF_8).size <= 110 * 1024) break
                if (source.size >= build.size) source.removeLast() else build.removeLast()
            } while (source.isNotEmpty() || build.isNotEmpty())
            response
        }
        "inspect" -> {
            val project = project(params); val origin = params["origin"]?.asString ?: "file"
            val input = files.resolve(project, string(params, "path"), origin)
            jobs.start(project, input.copy(optimizationProfile = devline.settings.PhpOptimizationSettings.forProject(java.nio.file.Path.of(project)),
                modernSource = devline.settings.PhpRuntimeOwnershipSettings.supports(java.nio.file.Path.of(project)),
                ownershipMode = devline.settings.PhpRuntimeOwnershipSettings.forProject(java.nio.file.Path.of(project)))).view()
        }
        "job" -> session(params).view()
        "cancel" -> session(params).also(jobs::cancel).view()
        "classes" -> {
            val job = session(params); val value = result(job); val query = params["query"]?.asString.orEmpty().take(200)
            val rows = value.getAsJsonArray("classes").mapIndexedNotNull { index, entry ->
                val item = entry.asJsonObject
                if (!item["name"].asString.contains(query, true)) null else header(item, "methods", "fields", "interfaces").apply {
                    addProperty("index", index); addProperty("methodCount", item.getAsJsonArray("methods").size()); addProperty("fieldCount", item.getAsJsonArray("fields").size())
                }
            }
            paged(rows, offset(params), "items", JsonObject().apply { add("source", job.source); addProperty("total", rows.size); add("warnings", value["warnings"] ?: JsonArray()) }, 50)
        }
        "classInfo" -> {
            val job = session(params); val value = clazz(job, params)
            val methods = value.getAsJsonArray("methods").mapIndexed { index, entry -> header(entry.asJsonObject, "instructions", "locals", "tryCatch", "exceptions", "signature").apply {
                addProperty("index", index); addProperty("instructionCount", entry.asJsonObject.getAsJsonArray("instructions").count { it.asJsonObject["opcode"].asInt >= 0 })
            } }
            val metadata = JsonObject().apply {
                add("class", header(value, "methods", "fields")); add("fields", value.getAsJsonArray("fields").deepCopy())
                addProperty("totalMethods", methods.size)
                files.sourcePath(job.project, job.input, value["sourceFile"]?.takeUnless(JsonElement::isJsonNull)?.asString)?.let { addProperty("sourcePath", it) }
            }
            paged(methods, offset(params), "methods", metadata)
        }
        "method" -> {
            val job = session(params); val value = clazz(job, params).getAsJsonArray("methods")
            val index = params["methodIndex"]?.asInt ?: -1; require(index in 0 until value.size())
            val selected = value[index].asJsonObject
            val query = params["query"]?.asString.orEmpty().take(200)
            val instructions = selected.getAsJsonArray("instructions").filter { query.isEmpty() || it.toString().contains(query, true) }
            val metadata = JsonObject().apply { add("method", header(selected, "instructions")); addProperty("total", instructions.size) }
            paged(instructions, offset(params), "instructions", metadata, 200)
        }
        "openSource" -> {
            val job = session(params); val value = clazz(job, params)
            if (job.input.origin == "file" && job.input.kind in setOf("php", "kt", "java")) {
                val current = job.input.file.toRealPath()
                val data = java.nio.file.Files.newInputStream(current).use { it.readNBytes(4 * 1024 * 1024 + 1) }
                if (!current.startsWith(job.input.projectRoot) || data.size > 4 * 1024 * 1024 || BytecodeProjectFiles.sha(data) != job.source?.get("sha256")?.asString)
                    throw IdeExtensionException("FILE_CONFLICT", "The source changed after inspection. Refresh before navigating.")
            }
            val line = params["line"]?.asInt ?: 1; require(line in 1..10_000_000)
            val path = files.sourcePath(job.project, job.input, value["sourceFile"]?.takeUnless(JsonElement::isJsonNull)?.asString)
                ?: throw IdeExtensionException("SOURCE_NOT_FOUND", "No unambiguous project source matches this class.")
            JsonObject().apply { addProperty("path", path); addProperty("line", line) }
        }
        else -> throw IdeExtensionException("UNKNOWN_METHOD", "Unknown bytecode action.")
    }
    /** Internal test observation of the exact ProcessBuilder child; never exposed through RPC. */
    internal fun workerProcess(id: String): Process? = session(JsonObject().apply { addProperty("id", id) }).process
    override fun projectClosed() { jobs.close(); jobs = BytecodeJobs(context.profileDirectory.resolve("bytecode-viewer/sessions")) }
    override fun webviewClosed(projectFile: String?, viewId: String) { if (viewId == "viewer") jobs.cancelProject(projectFile) }
    override fun close() = jobs.close()
}
