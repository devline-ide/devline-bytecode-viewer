package devline.bytecode

import com.google.gson.JsonObject
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import devline.extensions.IdeExtensionException
import devline.extensions.IdeExtensionFiles
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Global user preferences. The supplied directory is profileDirectory/bytecode-viewer. */
class BytecodeSettings(directory: Path) {
    private val directory = directory.toAbsolutePath().normalize()
    private val file = this.directory.resolve("settings.json")
    private val lock = this.directory.resolve("settings.lock")

    fun view(): JsonObject = guarded { document().view() }

    fun configure(values: JsonObject, expectedVersion: String): JsonObject {
        val patch = validate(values)
        return update(expectedVersion) { current ->
            val settings = current.settings.deepCopy()
            patch.entrySet().forEach { (name, value) -> settings.add(name, value) }
            IdeExtensionFiles.writeAtomic(file, settings.toString() + "\n")
        }
    }

    fun reset(expectedVersion: String): JsonObject = update(expectedVersion) { Files.deleteIfExists(file) }

    private fun update(expectedVersion: String, action: (Document) -> Unit): JsonObject = guarded {
        synchronized(processLock) {
            Files.createDirectories(directory)
            if (Files.exists(lock, NOFOLLOW_LINKS) && !Files.isRegularFile(lock, NOFOLLOW_LINKS)) storageError()
            FileChannel.open(lock, CREATE, WRITE).use { channel ->
                val deadline = System.nanoTime() + 2_000_000_000L
                while (true) {
                    val held = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (held != null) {
                        held.use {
                            val current = document()
                            if (current.version != expectedVersion) throw IdeExtensionException("FILE_CONFLICT", "Bytecode Viewer settings changed. Reload before saving.")
                            action(current)
                            return@guarded document().view()
                        }
                    }
                    if (System.nanoTime() >= deadline) throw IdeExtensionException("BYTECODE_SETTINGS_BUSY", "Bytecode Viewer settings are being saved by another process. Try again.")
                    Thread.sleep(20)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable settings lock state")
    }

    private data class Document(val settings: JsonObject, val version: String) {
        fun view() = JsonObject().apply { add("settings", settings.deepCopy()); addProperty("version", version) }
    }

    private fun document(): Document {
        if (!Files.exists(file, NOFOLLOW_LINKS)) return Document(defaults(), digest("bytecode-viewer:absent".toByteArray(Charsets.UTF_8)))
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) storageError()
        val bytes = Files.newInputStream(file).use { it.readNBytes(4097) }
        if (bytes.size > 4096) storageError()
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        val settings = defaults()
        JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.STRICT
            if (reader.peek() != JsonToken.BEGIN_OBJECT) storageError()
            reader.beginObject()
            val seen = HashSet<String>()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name !in keys || !seen.add(name) || reader.peek() != JsonToken.BOOLEAN) storageError()
                settings.addProperty(name, reader.nextBoolean())
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT) storageError()
        }
        return Document(settings, digest(bytes))
    }

    private fun validate(values: JsonObject): JsonObject {
        if (values.size() > keys.size || values.entrySet().any { (name, value) -> name !in keys || !value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean }) {
            throw IdeExtensionException("BYTECODE_INVALID_SETTINGS", "Only showDebugInfo and showSynthetic Boolean settings are supported.")
        }
        return values.deepCopy()
    }

    private inline fun <T> guarded(action: () -> T): T = try { action() }
    catch (error: IdeExtensionException) { throw error }
    catch (error: InterruptedException) { Thread.currentThread().interrupt(); storageError() }
    catch (_: Exception) { storageError() }

    private fun storageError(): Nothing = throw IdeExtensionException("BYTECODE_SETTINGS_ERROR", "Could not read or save Bytecode Viewer settings. Existing settings were retained.")
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private val processLock = Any()
        private val keys = setOf("showDebugInfo", "showSynthetic")
        private fun defaults() = JsonObject().apply { addProperty("showDebugInfo", true); addProperty("showSynthetic", false) }
    }
}
