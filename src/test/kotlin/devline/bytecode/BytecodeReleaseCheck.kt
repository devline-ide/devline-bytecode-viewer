package devline.bytecode

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import devline.extensions.IdeExtensionContext
import devline.extensions.IdeExtensionException
import devline.extensions.IdeExtensionHost
import devline.extensions.IdeExtensions
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Loads the actual archive with no provider classes on the parent classpath. */
fun main(args: Array<String>) {
    check(runCatching { Class.forName("devline.bytecode.BytecodeExtension") }.exceptionOrNull() is ClassNotFoundException)
    val archive = Path.of(args.single()).toRealPath()
    val root = Files.createTempDirectory("devline-bytecode-release-").toRealPath()
    val project = Files.createDirectories(root.resolve("project/src"))
    val descriptor = project.parent.resolve("Release.dlproject")
    val marker = root.resolve("USER_CODE_EXECUTED")
    Files.writeString(descriptor, """{"schemaVersion":1,"language":"php","sourceFormat":"dlproject","sourceFingerprint":"native","controllerPolicy":"native"}""")
    Files.writeString(project.resolve("Sum.php"), """<?php file_put_contents('${marker.toString().replace('\\', '/')}', 'executed'); final class ReleaseSum { public static function sum(int ${'$'}a, int ${'$'}b): int { return ${'$'}a + ${'$'}b; } }""")
    fun context(profile: Path) = object : IdeExtensionContext {
        override val profileDirectory = profile
        override fun enabled() = true
        override fun currentProjectFile() = descriptor.toString()
        override fun projectRoot(projectFile: String): String {
            check(projectFile == descriptor.toString())
            return project.parent.toString()
        }
        override fun callProject(projectFile: String, method: String, arguments: JsonObject): JsonElement {
            projectRoot(projectFile)
            check(method == "application.inspection")
            return JsonNull.INSTANCE
        }
    }
    val id = "devline.bytecode"
    try {
        val oldProfile = root.resolve("old-host")
        val oldHost = IdeExtensionHost.current.let { it.copy(capabilities = it.capabilities - "compiler.php-bytecode-inspection") }
        IdeExtensions(oldProfile, context(oldProfile), emptyList(), oldHost).use { registry ->
            val review = registry.inspect(archive.toString())
            check(review.compatibility?.compatible == false)
            check("compiler.php-bytecode-inspection" in review.compatibility!!.missingCapabilities)
            check((runCatching { registry.install(review.path, review.checksum, review.expectedVersion) }.exceptionOrNull() as? IdeExtensionException)?.code == "IDE_EXTENSION_INCOMPATIBLE")
            check(registry.overview().extensions.isEmpty())
        }
        val profile = root.resolve("supported-host")
        IdeExtensions(profile, context(profile), emptyList()).use { registry ->
            val review = registry.inspect(archive.toString())
            check(review.compatibility?.compatible == true)
            registry.install(review.path, review.checksum, review.expectedVersion)
        }
        IdeExtensions(profile, context(profile), emptyList()).use { registry ->
            registry.setEnabled(id, true, registry.overview().version)
            val item = registry.overview().extensions.single()
            check(item.manifest.version == "0.1.3" && item.compatibility?.compatible == true)
            check(item.manifest.commands.single().let { it.readonly == true && it.saveBeforeRun == false })
            fun call(method: String, values: JsonObject = JsonObject()) = registry.request("bytecode.$method", values).asJsonObject
            check(call("state")["projectFile"].asString == descriptor.toString())
            val job = call("inspect", JsonObject().apply { addProperty("path", "src/Sum.php") })["id"].asString
            val params = JsonObject().apply { addProperty("id", job) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(100)
            var status: JsonObject
            do {
                status = call("job", params)
                check(System.nanoTime() < deadline) { "Archive inspection worker timed out: $status" }
                if (status["state"].asString == "preparing") Thread.sleep(20)
            } while (status["state"].asString == "preparing")
            check(status["state"].asString == "ready") { "Archive inspection failed: $status" }
            val classes = call("classes", params).getAsJsonArray("items")
            var mappedSource = false
            val instructions = classes.flatMap { row ->
                val details = params.deepCopy().apply { addProperty("classIndex", row.asJsonObject["index"].asInt) }
                val info = call("classInfo", details)
                if (info["sourcePath"]?.asString != "src/Sum.php") emptyList() else {
                    mappedSource = true
                    info.getAsJsonArray("methods").flatMap { method ->
                        call("method", details.deepCopy().apply { addProperty("methodIndex", method.asJsonObject["index"].asInt) })
                            .getAsJsonArray("instructions").toList()
                    }
                }
            }
            check(mappedSource && instructions.any { it.asJsonObject["opcode"].asInt >= 0 })
            check(instructions.any { item -> item.asJsonObject.let {
                it["mnemonic"].asString in setOf("LADD", "IADD", "DADD") ||
                    it["operand"].asString.contains("addExact") || it["operand"].asString.contains("plus")
            } }) { "Compiled PHP addition was missing from the archive instruction listing" }
            check(!Files.exists(marker)) { "Archive inspection executed PHP" }
            val firstView = registry.openWebview(id, "viewer", descriptor.toString())
            val lastView = registry.openWebview(id, "viewer", descriptor.toString())
            registry.closeWebview(firstView.session)
            check(call("job", params)["state"].asString == "ready")
            registry.closeWebview(lastView.session)
            check((runCatching { call("job", params) }.exceptionOrNull() as? IdeExtensionException)?.code == "BYTECODE_SESSION_EXPIRED")
            println("BYTECODE_RELEASE_ARCHIVE_PARSER_PROVIDER_CAPABILITY_GATE_PHP_CODEGEN_LISTING_VIEW_LIFECYCLE_NO_EXECUTION_PASS")
        }
    } finally {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
