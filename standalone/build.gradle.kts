import groovy.json.JsonSlurper

plugins { kotlin("jvm") version "2.4.20" }
repositories { mavenCentral(); maven { url = uri("https://repo.gradle.org/gradle/libs-releases") } }

val extensionRoot = projectDir.parentFile
val declaration = JsonSlurper().parse(extensionRoot.resolve("extension-build.json")) as Map<*, *>
val module = declaration["module"].toString()
val sdk = file(requireNotNull(providers.gradleProperty("devlineSdkDir").orNull) { "Run python ci/public_build.py first." })
val raw = JsonSlurper().parse(extensionRoot.resolve(declaration["manifest"].toString())) as Map<*, *>
val manifest = (raw["extension"] as? Map<*, *>) ?: raw
val releaseVersion = manifest["version"].toString()
val sound = module == "devline-audio-studio"
version = if (module == "devline-bytecode-viewer") releaseVersion else "0.1.0"
base { archivesName.set(module) }
kotlin { jvmToolchain(25) }
sourceSets {
    main {
        kotlin.srcDir(extensionRoot.resolve("src/main/kotlin"))
        java.srcDir(extensionRoot.resolve("src/main/java"))
        resources.srcDir(extensionRoot.resolve("src/main/resources"))
    }
    test {
        kotlin.srcDir(extensionRoot.resolve("src/test/kotlin"))
        java.srcDir(extensionRoot.resolve("src/test/java"))
        // Integration with the private built-in assistant is verified in product CI.
        if (module == "devline-tellfair") kotlin.exclude("**/AgentSpeechCheck.kt")
    }
}
dependencies {
    compileOnly(fileTree(sdk.resolve("lib")) { include("*.jar") })
    testImplementation(fileTree(sdk.resolve("lib")) { include("*.jar") })
    if (module in setOf("devline-gradle", "devline-tellfair", "devline-audio-studio")) implementation("com.google.code.gson:gson:2.13.2")
    if (module == "devline-gradle") implementation("org.gradle:gradle-tooling-api:9.7.1")
    if (module == "devline-bytecode-viewer") {
        implementation("org.ow2.asm:asm:9.8"); implementation("org.ow2.asm:asm-util:9.8")
    }
    if (module == "devline-steamdeck") {
        implementation(files(extensionRoot.resolve("vendor/devline-deck-tools-0.1.1.jar")))
        implementation("org.apache.commons:commons-compress:1.28.0")
    }
    if (sound) implementation(files(sdk.resolve("lib/devline-audio-core-0.1.0.jar")))
}
tasks.processResources {
    if (!sound) from(extensionRoot.resolve("src/extension/extension.json")) {
        rename { when (module) {
            "devline-python" -> "python-extension.json"
            "devline-gradle" -> "gradle-extension.json"
            "devline-tellfair" -> "tellfair-extension.json"
            "devline-bytecode-viewer" -> "bytecode-extension.json"
            "devline-steamdeck" -> "steamdeck-extension.json"
            else -> error("Unknown extension")
        } }
    }
}
tasks.jar { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }
// Verification is implemented by the explicit JavaExec checks below, not JUnit.
tasks.test { failOnNoDiscoveredTests = false }
val panelInstall = tasks.register<Exec>("panelInstall") {
    workingDir(extensionRoot)
    commandLine("node", extensionRoot.resolve("ci/frontend.mjs").absolutePath, "extension", extensionRoot.absolutePath)
}
val panelBuild = tasks.register<Exec>("panelBuild") {
    dependsOn(panelInstall); workingDir(extensionRoot)
    commandLine("node", extensionRoot.resolve("ci/frontend.mjs").absolutePath, "panel", extensionRoot.absolutePath)
}
val packPanel = tasks.register<Exec>("packPanel") {
    dependsOn(panelBuild); workingDir(extensionRoot)
    val args = mutableListOf("node", extensionRoot.resolve("web/pack.mjs").absolutePath,
        extensionRoot.resolve("build/panel").absolutePath, layout.buildDirectory.dir("payload").get().asFile.absolutePath)
    if (sound) {
        require(System.getProperty("os.name").startsWith("Windows")) { "Sound Studio native SDK is currently Windows amd64 only." }
        args.add(sdk.resolve("native/windows-amd64/bin").absolutePath)
    }
    commandLine(args)
}
val archive = tasks.register<Zip>("extensionArchive") {
    group = "distribution"
    dependsOn(packPanel, tasks.jar)
    archiveFileName.set("$module-$releaseVersion.dlextension")
    destinationDirectory.set(layout.buildDirectory.dir("extension"))
    if (!sound) {
        from(extensionRoot.resolve("src/extension/extension.json"))
        into("web") { from(layout.buildDirectory.dir("payload")) }
    } else from(layout.buildDirectory.dir("payload"))
    into("lib") {
        from(tasks.jar)
        from(configurations.runtimeClasspath) { exclude("kotlin-stdlib-*.jar", "annotations-*.jar", "error_prone_annotations-*.jar") }
    }
    isPreserveFileTimestamps = false; isReproducibleFileOrder = true
}
val checks = when (module) {
    "devline-python" -> mapOf("pythonCheck" to "devline.python.PythonCheckKt")
    "devline-gradle" -> mapOf("gradleCheck" to "devline.gradle.GradleExtensionCheckKt")
    "devline-tellfair" -> mapOf("tellfairCheck" to "devline.tellfair.TellfairCheckKt", "tellfairAccountCheck" to "devline.tellfair.TellfairAccountCheckKt", "savedSpeechCheck" to "devline.tellfair.SavedSpeechCheckKt")
    "devline-steamdeck" -> mapOf("steamDeckCheck" to "devline.steamdeck.SteamDeckCheckKt")
    "devline-audio-studio" -> mapOf("documentToolsCheck" to "devline.audio.studio.SoundStudioDocumentsCheckKt")
    "devline-bytecode-viewer" -> mapOf("bytecodeReaderCheck" to "devline.bytecode.BytecodeReaderCheckKt", "bytecodeRuntimeCheck" to "devline.bytecode.BytecodeRuntimeCheckKt")
    else -> error("Unsupported extension")
}
checks.forEach { (name, main) ->
    val check = tasks.register<JavaExec>(name) {
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set(main)
        jvmArgs("-Djava.awt.headless=true", "-Dfile.encoding=UTF-8")
        if (name == "gradleCheck") args(layout.buildDirectory.dir("checks").get().asFile.absolutePath, gradle.gradleHomeDir!!.absolutePath)
        if (name == "tellfairAccountCheck") args(layout.buildDirectory.dir("account-check").get().asFile.absolutePath)
        if (module in setOf("devline-python", "devline-steamdeck")) {
            dependsOn(archive)
            classpath = sourceSets.test.get().output + configurations.testRuntimeClasspath.get()
            systemProperty(if (module == "devline-python") "devline.python.archive" else "devline.deck.archive", archive.flatMap { it.archiveFile }.get().asFile.absolutePath)
            jvmArgs("--enable-native-access=ALL-UNNAMED")
        }
    }
    tasks.check { dependsOn(check) }
}
