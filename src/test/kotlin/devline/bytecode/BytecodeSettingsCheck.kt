package devline.bytecode

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import devline.extensions.IdeExtensionException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun checkBytecodeSettings() {
    val root = Files.createTempDirectory("devline-bytecode-settings-")
    val directory = root.resolve("bytecode-viewer")
    val store = BytecodeSettings(directory)
    fun version(view: JsonObject) = view["version"].asString
    fun flags(view: JsonObject) = view.getAsJsonObject("settings")
    fun patch(text: String) = JsonParser.parseString(text).asJsonObject
    fun fails(code: String, operation: () -> Unit) {
        val error = runCatching(operation).exceptionOrNull()
        check(error is IdeExtensionException && error.code == code) { "Expected $code, got $error" }
    }
    try {
        val initial = store.view()
        check(flags(initial)["showDebugInfo"].asBoolean && !flags(initial)["showSynthetic"].asBoolean)
        check(version(initial).matches(Regex("[a-f0-9]{64}")) && !Files.exists(directory))
        val changed = store.configure(patch("""{"showSynthetic":true}"""), version(initial))
        check(flags(changed)["showSynthetic"].asBoolean && flags(changed)["showDebugInfo"].asBoolean)
        check(BytecodeSettings(directory).view() == changed)
        check(Files.readString(directory.resolve("settings.json")).contains("showSynthetic"))
        fails("FILE_CONFLICT") { store.configure(patch("""{"showDebugInfo":false}"""), version(initial)) }
        fails("FILE_CONFLICT") { store.reset(version(initial)) }
        for (text in listOf("""{"unknown":true}""", """{"showDebugInfo":"false"}""", """{"showSynthetic":null}""", """{"showSynthetic":0}""")) {
            fails("BYTECODE_INVALID_SETTINGS") { store.configure(patch(text), version(changed)) }
            check(store.view() == changed)
        }
        val reset = store.reset(version(changed))
        check(reset == initial && !Files.exists(directory.resolve("settings.json")))
        val current = store.configure(patch("""{"showSynthetic":true}"""), version(reset))
        val file = directory.resolve("settings.json")
        Files.writeString(file, Files.readString(file) + " ")
        fails("FILE_CONFLICT") { store.reset(version(current)) }
        val modified = store.view()
        check(flags(modified) == flags(current) && version(modified) != version(current))
        store.reset(version(modified))

        val start = CountDownLatch(1); val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks = (0..1).map { i -> executor.submit<String> {
                start.await()
                try { BytecodeSettings(directory).configure(patch(if (i == 0) """{"showDebugInfo":false}""" else """{"showSynthetic":true}"""), version(initial)); "saved" }
                catch (error: IdeExtensionException) { error.code }
            } }
            start.countDown()
            check(tasks.map { it.get(5, TimeUnit.SECONDS) }.sorted() == listOf("FILE_CONFLICT", "saved").sorted())
        } finally { executor.shutdownNow() }
        store.reset(version(store.view()))
        for (bad in listOf("not json", "{'showDebugInfo':false}", """{"showDebugInfo":true,"showDebugInfo":false}""", """{"extra":false}""", "[]", "{} {}", " ".repeat(4097))) {
            Files.writeString(file, bad)
            fails("BYTECODE_SETTINGS_ERROR") { store.view() }
            fails("BYTECODE_SETTINGS_ERROR") { store.configure(patch("{}"), version(initial)) }
            check(Files.readString(file) == bad) { "Invalid settings were overwritten" }
        }
        Files.write(file, byteArrayOf(0xc3.toByte(), 0x28))
        fails("BYTECODE_SETTINGS_ERROR") { store.view() }
        check(Files.list(directory).use { paths -> paths.noneMatch { it.fileName.toString().endsWith(".tmp") } })
        println("BYTECODE_SETTINGS_DEFAULT_PATCH_RESET_CONFLICT_CONCURRENT_STRICT_ATOMIC_PASS")
    } finally {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}
