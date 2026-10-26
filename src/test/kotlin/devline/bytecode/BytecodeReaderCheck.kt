package devline.bytecode

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import javax.tools.ToolProvider

private val reader = BytecodeReader()

fun main() {
    val property = "devline.bytecode.reader.executed"
    System.clearProperty(property)
    val bytes = fixture(property)
    val result = reader.read(bytes)
    check(result.name == "sample/Fixture" && result.superName == "java/lang/Object" && result.version == 65)
    check(result.sourceFile == "Fixture.java" && result.interfaces == listOf("java/io/Serializable"))
    check(result.fields.single().let { it.name == "TITLE" && it.descriptor == "Ljava/lang/String;" && it.value == "\"fixture\"" && it.access and Opcodes.ACC_STATIC != 0 })
    val choose = result.methods.single { it.name == "choose" }
    check(choose.descriptor == "(I)I" && choose.signature == "(I)I" && choose.exceptions == listOf("java/io/IOException"))
    check(choose.maxStack == 2 && choose.maxLocals == 2)
    check(choose.instructions.filter { it.opcode >= 0 }.map { it.index } == choose.instructions.filter { it.opcode >= 0 }.indices.toList())
    check(choose.instructions.first { it.opcode == Opcodes.ILOAD }.line == 12)
    check(choose.instructions.single { it.opcode == Opcodes.TABLESWITCH }.operand.let { "default:L" in it && "0:L" in it && "1:L" in it })
    val labels = choose.instructions.filter { it.opcode == -1 }.map { it.operand }.toSet()
    check(choose.instructions.filter { it.opcode == Opcodes.IFLT }.all { it.operand in labels })
    check(choose.tryCatch.single().let { it.start in labels && it.end in labels && it.handler in labels && it.type == "java/lang/RuntimeException" })
    check(choose.locals.single().let { it.name == "x" && it.descriptor == "I" && it.index == 0 && it.start in labels && it.end in labels })
    val sparse = result.methods.single { it.name == "sparse" }
    check(sparse.instructions.single { it.opcode == Opcodes.LOOKUPSWITCH }.operand.let { "-100:L" in it && "1000:L" in it })
    check(sparse.instructions.filter { it.opcode >= 0 }.all { it.line == null })
    check(result.methods.single { it.name == "concat" }.instructions.single { it.opcode == Opcodes.INVOKEDYNAMIC }.operand.let { "makeConcatWithConstants" in it && "StringConcatFactory" in it })
    check(result.methods.single { it.name == "constants" }.instructions.any { "constant-dynamic" in it.operand && "answer:I" in it.operand })
    check(result.methods.single { it.name == "wide" }.instructions.any { it.opcode == Opcodes.IINC && it.operand == "300 -1000" })
    check(result.methods.single { it.name == "abstractMethod" }.let { it.instructions.isEmpty() && it.access and Opcodes.ACC_ABSTRACT != 0 })
    check(System.getProperty(property) == null) { "Reading bytecode executed a static initializer" }

    for (size in listOf(0, 1, 7, bytes.size / 2, bytes.size - 1)) rejects(bytes.copyOf(size))
    rejects(ByteArray(BytecodeLimits.MAX_CLASS_BYTES + 1), "LIMIT_EXCEEDED")
    rejects(bytes + byteArrayOf(1))
    rejects(bytes.copyOf().also { putInt(it, firstCode(it), Int.MAX_VALUE) })
    rejects(switchFixture(false).let { file -> file.also { putInt(it, firstCode(it) + 4 + 12, Int.MAX_VALUE) } })
    rejects(switchFixture(true).let { file -> file.also { putInt(it, firstCode(it) + 4 + 8, Int.MAX_VALUE) } })
    rejects(fixtureWithManyInstructions(), "LIMIT_EXCEEDED")
    rejects(fixtureWithString(), "LIMIT_EXCEEDED")
    rejects(fixtureWithNestedAnnotations(), "LIMIT_EXCEEDED")
    rejects(dynamicFixture(40), "LIMIT_EXCEEDED")
    val cycle = dynamicFixture(1)
    val pool = pool(cycle)
    val dynamic = pool.tags.indices.single { pool.tags[it] == 17 }
    val bootstrap = classAttribute(cycle, pool, "BootstrapMethods")
    cycle[bootstrap + 6] = (dynamic ushr 8).toByte(); cycle[bootstrap + 7] = dynamic.toByte()
    rejects(cycle, "INVALID_CLASS")
    check(System.getProperty(property) == null)
    realJavacFixture(property)
    checkBytecodeSettings()
    println("BYTECODE_READER_METADATA_SWITCH_BRANCH_DEBUG_DYNAMIC_INVALID_LIMITS_NO_EXECUTION_PASS")
}

private fun rejects(bytes: ByteArray, code: String? = null) {
    val error = runCatching { reader.read(bytes) }.exceptionOrNull()
    check(error is BytecodeReadException) { "Expected a bounded reader error, got $error" }
    if (code != null) check(error.code == code) { "Expected $code, got ${error.code}: ${error.message}" }
    check(error.message.orEmpty().length < 300)
}

private fun fixture(property: String): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "sample/Fixture", null, "java/lang/Object", arrayOf("java/io/Serializable"))
    writer.visitSource("Fixture.java", null)
    writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "TITLE", "Ljava/lang/String;", null, "fixture").visitEnd()
    writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
        visitCode(); visitLdcInsn(property); visitLdcInsn("executed")
        visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "setProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false)
        visitInsn(Opcodes.POP); visitInsn(Opcodes.RETURN); visitMaxs(2, 0); visitEnd()
    }
    writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "choose", "(I)I", "(I)I", arrayOf("java/io/IOException")).apply {
        val start = Label(); val end = Label(); val negative = Label(); val zero = Label(); val one = Label(); val other = Label(); val handler = Label()
        visitCode(); visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException")
        visitLabel(start); visitLineNumber(12, start); visitVarInsn(Opcodes.ILOAD, 0); visitJumpInsn(Opcodes.IFLT, negative)
        visitVarInsn(Opcodes.ILOAD, 0); visitTableSwitchInsn(0, 1, other, zero, one)
        visitLabel(zero); visitInsn(Opcodes.ICONST_0); visitInsn(Opcodes.IRETURN)
        visitLabel(one); visitInsn(Opcodes.ICONST_1); visitInsn(Opcodes.IRETURN)
        visitLabel(other); visitInsn(Opcodes.ICONST_2); visitInsn(Opcodes.IRETURN)
        visitLabel(negative); visitInsn(Opcodes.ICONST_M1); visitInsn(Opcodes.IRETURN)
        visitLabel(end); visitLabel(handler); visitVarInsn(Opcodes.ASTORE, 1); visitInsn(Opcodes.ICONST_M1); visitInsn(Opcodes.IRETURN)
        visitLocalVariable("x", "I", null, start, end, 0); visitMaxs(2, 2); visitEnd()
    }
    writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "sparse", "(I)I", null, null).apply {
        val first = Label(); val second = Label(); val other = Label()
        visitCode(); visitVarInsn(Opcodes.ILOAD, 0); visitLookupSwitchInsn(other, intArrayOf(-100, 1000), arrayOf(first, second))
        for ((label, value) in listOf(first to Opcodes.ICONST_0, second to Opcodes.ICONST_1, other to Opcodes.ICONST_M1)) {
            visitLabel(label); visitInsn(value); visitInsn(Opcodes.IRETURN)
        }
        visitMaxs(1, 1); visitEnd()
    }
    val concat = Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants", "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false)
    writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "concat", "(I)Ljava/lang/String;", null, null).apply {
        visitCode(); visitVarInsn(Opcodes.ILOAD, 0); visitInvokeDynamicInsn("makeConcatWithConstants", "(I)Ljava/lang/String;", concat, "n=\u0001")
        visitInsn(Opcodes.ARETURN); visitMaxs(1, 1); visitEnd()
    }
    writer.visitMethod(Opcodes.ACC_STATIC, "constants", "()I", null, null).apply {
        visitCode(); visitLdcInsn(ConstantDynamic("answer", "I", bootstrapHandle(), 42)); visitInsn(Opcodes.IRETURN); visitMaxs(1, 0); visitEnd()
    }
    writer.visitMethod(Opcodes.ACC_STATIC, "wide", "()V", null, null).apply {
        visitCode(); visitInsn(Opcodes.ICONST_0); visitVarInsn(Opcodes.ISTORE, 300); visitIincInsn(300, -1000); visitInsn(Opcodes.RETURN); visitMaxs(1, 301); visitEnd()
    }
    writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_SYNTHETIC, "abstractMethod", "()V", null, null).visitEnd()
    writer.visitEnd(); return writer.toByteArray()
}

private fun bootstrapHandle() = Handle(Opcodes.H_INVOKESTATIC, "sample/Bootstrap", "value", "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;", false)

private fun dynamicFixture(depth: Int): ByteArray {
    var value: Any = 1
    repeat(depth) { value = ConstantDynamic("constant$it", "Ljava/lang/Object;", bootstrapHandle(), value) }
    return simpleClass { visitLdcInsn(value); visitInsn(Opcodes.POP); visitInsn(Opcodes.RETURN) }
}

private fun switchFixture(lookup: Boolean): ByteArray = simpleClass {
    val target = Label(); visitInsn(Opcodes.ICONST_0)
    if (lookup) visitLookupSwitchInsn(target, intArrayOf(1), arrayOf(target)) else visitTableSwitchInsn(0, 0, target, target)
    visitLabel(target); visitInsn(Opcodes.RETURN)
}

private fun simpleClass(body: org.objectweb.asm.MethodVisitor.() -> Unit): ByteArray {
    val writer = ClassWriter(0); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "sample/Simple", null, "java/lang/Object", null)
    writer.visitMethod(Opcodes.ACC_STATIC, "run", "()V", null, null).apply { visitCode(); body(); visitMaxs(2, 1); visitEnd() }
    writer.visitEnd(); return writer.toByteArray()
}

private fun fixtureWithManyInstructions(): ByteArray {
    val writer = ClassWriter(0); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "sample/Many", null, "java/lang/Object", null)
    repeat(2) { i -> writer.visitMethod(Opcodes.ACC_STATIC, "run$i", "()V", null, null).apply { visitCode(); repeat(60_000) { visitInsn(Opcodes.NOP) }; visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd() } }
    writer.visitEnd(); return writer.toByteArray()
}

private fun fixtureWithString() = simpleClass { visitLdcInsn("a".repeat(BytecodeLimits.MAX_STRING_CHARS + 1)); visitInsn(Opcodes.POP); visitInsn(Opcodes.RETURN) }

private fun fixtureWithNestedAnnotations(): ByteArray {
    val writer = ClassWriter(0); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "sample/Annotations", null, "java/lang/Object", null)
    val nested = ArrayList<org.objectweb.asm.AnnotationVisitor>(); var visitor = writer.visitAnnotation("Lsample/A;", true); nested += visitor
    repeat(40) { visitor = visitor.visitArray(if (it == 0) "value" else null); nested += visitor }
    visitor.visit(null, 1); nested.asReversed().forEach { it.visitEnd() }; writer.visitEnd(); return writer.toByteArray()
}

private fun realJavacFixture(property: String) {
    val compiler = ToolProvider.getSystemJavaCompiler() ?: error("Reader check needs a JDK javac")
    val directory = Files.createTempDirectory("devline-bytecode-reader-")
    try {
        val source = directory.resolve("ReaderFixture.java")
        Files.writeString(source, """
            public record ReaderFixture(int value) {
                static { System.setProperty("$property", "executed"); }
                public String describe(int input) {
                    try { return switch (input) { case 0, 1 -> "small=" + value; case 100 -> "large"; default -> Integer.toString(input); }; }
                    catch (RuntimeException error) { return error.getMessage(); }
                }
            }
        """.trimIndent())
        check(compiler.run(null, null, null, "-g", "--release", "21", "-d", directory.toString(), source.toString()) == 0)
        val value = reader.read(Files.readAllBytes(directory.resolve("ReaderFixture.class")))
        check(value.sourceFile == "ReaderFixture.java" && value.superName == "java/lang/Record")
        val method = value.methods.single { it.name == "describe" }
        check(method.locals.isNotEmpty() && method.tryCatch.isNotEmpty() && method.instructions.any { it.line != null })
        check(method.instructions.any { it.opcode == Opcodes.LOOKUPSWITCH || it.opcode == Opcodes.TABLESWITCH })
        check(method.instructions.any { it.opcode == Opcodes.INVOKEDYNAMIC })
        check(System.getProperty(property) == null) { "javac fixture was executed by the reader" }
    } finally {
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}

private data class Pool(val names: Map<Int, String>, val tags: IntArray, val end: Int)
private fun u2(bytes: ByteArray, at: Int) = ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255)
private fun i4(bytes: ByteArray, at: Int) = (u2(bytes, at) shl 16) or u2(bytes, at + 2)
private fun putInt(bytes: ByteArray, at: Int, value: Int) { repeat(4) { bytes[at + it] = (value ushr (24 - it * 8)).toByte() } }
private fun pool(bytes: ByteArray): Pool {
    var at = 10; var index = 1; val count = u2(bytes, 8); val names = HashMap<Int, String>(); val tags = IntArray(count)
    while (index < count) {
        val tag = bytes[at++].toInt() and 255; tags[index] = tag
        when (tag) {
            1 -> { val size = u2(bytes, at); at += 2; names[index] = String(bytes, at, size, Charsets.UTF_8); at += size }
            3, 4, 9, 10, 11, 12, 17, 18 -> at += 4
            5, 6 -> { at += 8; index++ }
            7, 8, 16, 19, 20 -> at += 2
            15 -> at += 3
            else -> error("Unexpected fixture tag")
        }
        index++
    }
    return Pool(names, tags, at)
}

private fun firstCode(bytes: ByteArray): Int {
    val pool = pool(bytes); var at = pool.end + 6; at += 2 + u2(bytes, at) * 2
    repeat(2) { group ->
        val count = u2(bytes, at); at += 2
        repeat(count) {
            at += 6; val attributes = u2(bytes, at); at += 2
            repeat(attributes) {
                val name = pool.names[u2(bytes, at)]; val size = i4(bytes, at + 2); at += 6
                if (group == 1 && name == "Code") return at + 4
                at += size
            }
        }
    }
    error("No fixture Code attribute")
}

private fun classAttribute(bytes: ByteArray, pool: Pool, wanted: String): Int {
    var at = pool.end + 6; at += 2 + u2(bytes, at) * 2
    repeat(2) {
        val count = u2(bytes, at); at += 2
        repeat(count) { at += 6; val attributes = u2(bytes, at); at += 2; repeat(attributes) { val size = i4(bytes, at + 2); at += 6 + size } }
    }
    val count = u2(bytes, at); at += 2
    repeat(count) { val name = pool.names[u2(bytes, at)]; val size = i4(bytes, at + 2); at += 6; if (name == wanted) return at; at += size }
    error("No fixture attribute $wanted")
}
