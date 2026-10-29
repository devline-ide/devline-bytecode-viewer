plugins { kotlin("jvm") }
version = "0.1.4"

dependencies {
    compileOnly(project(":devline-project-settings"))
    compileOnly(project(":devline-ide-extensions"))
    compileOnly(project(":devline-api"))
    compileOnly(project(":devline-compiler"))
    compileOnly("com.google.code.gson:gson:2.13.2")
    compileOnly("org.develnext.jphp:jphp-core:0.9.4-ozLTeam-SNAPSHOT")
    compileOnly("org.develnext.jphp:jphp-runtime:0.9.4-ozLTeam-SNAPSHOT")
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-util:9.8")
    testImplementation(project(":devline-ide-extensions"))
    testImplementation(project(":devline-api"))
    testImplementation(project(":devline-compiler"))
    testImplementation(project(":devline-project-settings"))
    testImplementation(project(":devline-kotlin-compiler"))
    testImplementation("com.google.code.gson:gson:2.13.2")
    testImplementation("org.develnext.jphp:jphp-core:0.9.4-ozLTeam-SNAPSHOT")
    testImplementation("org.develnext.jphp:jphp-runtime:0.9.4-ozLTeam-SNAPSHOT")
}

tasks.processResources {
    from("src/extension/extension.json") { rename { "bytecode-extension.json" } }
}

tasks.jar {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val panelInstall = tasks.register<Exec>("panelInstall") {
    workingDir(projectDir)
    commandLine("node", rootProject.file("tools/extensions/frontend.mjs").absolutePath, "extension", projectDir.absolutePath)
    inputs.files("package.json", "package-lock.json")
    inputs.dir("vendor")
    outputs.dir(layout.projectDirectory.dir("node_modules/@devline/extension-devkit"))
    outputs.dir(layout.projectDirectory.dir("node_modules/@devline/ui-web"))
    outputs.dir(layout.projectDirectory.dir("node_modules/@devline/animgraph"))
    outputs.dir(layout.projectDirectory.dir("node_modules/@devline/game-render-web"))
}
val panelBuild = tasks.register<Exec>("panelBuild") {
    dependsOn(panelInstall)
    workingDir(rootProject.projectDir)
    commandLine("node", rootProject.file("tools/extensions/frontend.mjs").absolutePath, "panel", projectDir.absolutePath)
    inputs.dir("web")
    inputs.files("package.json", "package-lock.json")
    inputs.dir("vendor")
    outputs.dir(layout.buildDirectory.dir("panel"))
}
val packPanel = tasks.register<Exec>("packPanel") {
    dependsOn(panelBuild)
    workingDir(rootProject.projectDir)
    commandLine("node", project.file("web/pack.mjs").absolutePath,
        layout.buildDirectory.dir("panel").get().asFile.absolutePath, layout.buildDirectory.dir("extension-web").get().asFile.absolutePath)
    inputs.dir(layout.buildDirectory.dir("panel"))
    outputs.dir(layout.buildDirectory.dir("extension-web"))
}
val extensionArchive = tasks.register<Zip>("extensionArchive") {
    group = "distribution"
    description = "Builds the independently installable Bytecode Viewer extension."
    archiveFileName.set("devline-bytecode-viewer-0.1.4.dlextension")
    destinationDirectory.set(layout.buildDirectory.dir("extension"))
    from("src/extension/extension.json")
    from(packPanel) { into("web"); include("bytecode.html") }
    into("lib") {
        from(tasks.jar)
        from(configurations.runtimeClasspath) {
            include("asm-9.8.jar", "asm-util-9.8.jar", "asm-tree-9.8.jar", "asm-analysis-9.8.jar")
        }
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

configurations.create("bundledExtension") { isCanBeConsumed = true; isCanBeResolved = false }
artifacts.add("bundledExtension", extensionArchive)

val bytecodeReaderCheck = tasks.register<JavaExec>("bytecodeReaderCheck") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("devline.bytecode.BytecodeReaderCheckKt")
    jvmArgs("-Djava.awt.headless=true")
}
val bytecodeRuntimeCheck = tasks.register<JavaExec>("bytecodeRuntimeCheck") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("devline.bytecode.BytecodeRuntimeCheckKt")
    jvmArgs("-Djava.awt.headless=true")
}
tasks.check { dependsOn(bytecodeReaderCheck, bytecodeRuntimeCheck) }

tasks.register<JavaExec>("bytecodeOwnershipWorkerCheck") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("devline.bytecode.BytecodeRuntimeCheckKt")
    args("ownership")
    jvmArgs("-Djava.awt.headless=true", "-Dfile.encoding=UTF-8")
}

tasks.register<JavaExec>("bytecodeReleaseCheck") {
    group = "verification"
    dependsOn(tasks.testClasses, extensionArchive)
    // Provider classes must come from the archive, never the test/main output.
    classpath = sourceSets.test.get().output + configurations.testRuntimeClasspath.get()
    mainClass.set("devline.bytecode.BytecodeReleaseCheckKt")
    args(extensionArchive.flatMap { it.archiveFile }.get().asFile.absolutePath)
    jvmArgs("-Djava.awt.headless=true", "-Dfile.encoding=UTF-8")
}
