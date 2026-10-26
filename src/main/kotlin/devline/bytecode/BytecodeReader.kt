package devline.bytecode

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.util.Printer
import java.util.IdentityHashMap

/** Reads data only. No class loading, resolution, bootstrap invocation or user code execution. */
class BytecodeReader {
    fun read(bytes: ByteArray): BytecodeClass {
        if (bytes.size > BytecodeLimits.MAX_CLASS_BYTES) limit("Class file exceeds ${BytecodeLimits.MAX_CLASS_BYTES} bytes")
        val input = bytes.copyOf()
        try {
            ClassBounds(input).validate()
            val visitor = ListingVisitor()
            ClassReader(input).accept(visitor, ClassReader.SKIP_FRAMES)
            return visitor.result()
        } catch (error: BytecodeReadException) {
            throw error
        } catch (error: RuntimeException) {
            throw BytecodeReadException("INVALID_CLASS", "Unsupported, truncated or malformed JVM class file")
        }
    }
}

private fun malformed(message: String): Nothing = throw BytecodeReadException("INVALID_CLASS", message)
private fun limit(message: String): Nothing = throw BytecodeReadException("LIMIT_EXCEEDED", message)

private class ListingVisitor : ClassVisitor(Opcodes.ASM9) {
    private var name = ""
    private var superName: String? = null
    private var version = 0
    private var access = 0
    private var sourceFile: String? = null
    private var interfaces = emptyList<String>()
    private val fields = ArrayList<BytecodeField>()
    private val methods = ArrayList<BytecodeMethod>()
    private var instructionCount = 0
    private var textChars = 0
    private var constantPieces = 0
    private var metadataCount = 0

    private fun text(value: String): String {
        if (value.length > BytecodeLimits.MAX_STRING_CHARS) limit("A class string exceeds ${BytecodeLimits.MAX_STRING_CHARS} characters")
        textChars += value.length
        if (textChars > BytecodeLimits.MAX_TEXT_CHARS) limit("Bytecode listing exceeds ${BytecodeLimits.MAX_TEXT_CHARS} characters")
        return value
    }

    override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
        this.name = text(name); this.superName = superName?.let(::text)
        this.version = version and 0xffff; this.access = access
        this.interfaces = interfaces?.map(::text).orEmpty()
    }

    override fun visitSource(source: String?, debug: String?) { sourceFile = source?.let(::text) }

    override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
        if (fields.size >= BytecodeLimits.MAX_FIELDS) limit("Too many class fields")
        fields += BytecodeField(text(name), text(descriptor), access, value?.let { text(constant(it)) })
        return null
    }

    private fun constant(value: Any): String {
        if (++constantPieces > 100_000) limit("Constant formatting exceeds reader limit")
        return when (value) {
        is String -> buildString {
            append('"')
            for (char in value) when (char) {
                '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                '\\' -> append("\\\\"); '"' -> append("\\\"")
                else -> if (char.code < 32 || char.code == 127) append("\\u" + char.code.toString(16).padStart(4, '0')) else append(char)
            }.also { if (length > BytecodeLimits.MAX_STRING_CHARS) limit("String constant exceeds reader limit") }
            append('"')
        }
        is Type -> value.descriptor
        is Handle -> "handle[${value.tag}${if (value.isInterface) ",interface" else ""}] ${value.owner}.${value.name}${value.desc}"
        is ConstantDynamic -> {
            buildString {
                append("constant-dynamic ${value.name}:${value.descriptor} ${constant(value.bootstrapMethod)} (")
                for (i in 0 until value.bootstrapMethodArgumentCount) {
                    if (i > 0) append(", ")
                    append(constant(value.getBootstrapMethodArgument(i)))
                    if (length > BytecodeLimits.MAX_STRING_CHARS) limit("Dynamic constant exceeds reader limit")
                }
                append(')')
            }
        }
        else -> value.toString()
    }.also { if (it.length > BytecodeLimits.MAX_STRING_CHARS) limit("Instruction operand exceeds ${BytecodeLimits.MAX_STRING_CHARS} characters") }
    }

    override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
        if (methods.size >= BytecodeLimits.MAX_METHODS) limit("Too many class methods")
        val methodName = text(name); val methodDescriptor = text(descriptor)
        val methodSignature = signature?.let(::text); val methodExceptions = exceptions?.map(::text).orEmpty()
        return object : MethodVisitor(Opcodes.ASM9) {
            val instructions = ArrayList<BytecodeInstruction>()
            val handlers = ArrayList<BytecodeTryCatch>()
            val locals = ArrayList<BytecodeLocal>()
            val labels = IdentityHashMap<Label, String>()
            var index = 0; var line: Int? = null; var maxStack = 0; var maxLocals = 0
            fun label(value: Label): String = labels.getOrPut(value) { "L${labels.size}" }
            fun instruction(opcode: Int, operand: String = "") {
                if (++instructionCount > BytecodeLimits.MAX_INSTRUCTIONS) limit("Too many bytecode instructions")
                instructions += BytecodeInstruction(index++, line, opcode, Printer.OPCODES.getOrNull(opcode) ?: malformed("Invalid opcode"), text(operand))
            }
            override fun visitLabel(value: Label) { instructions += BytecodeInstruction(index, line, -1, "LABEL", text(label(value))) }
            override fun visitLineNumber(line: Int, start: Label) { this.line = line }
            override fun visitInsn(opcode: Int) = instruction(opcode)
            override fun visitIntInsn(opcode: Int, operand: Int) = instruction(opcode, operand.toString())
            override fun visitVarInsn(opcode: Int, variable: Int) = instruction(opcode, variable.toString())
            override fun visitTypeInsn(opcode: Int, type: String) = instruction(opcode, type)
            override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) = instruction(opcode, "$owner.$name:$descriptor")
            override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) = instruction(opcode, "$owner.$name$descriptor${if (isInterface) " interface" else ""}")
            override fun visitInvokeDynamicInsn(name: String, descriptor: String, bootstrapMethodHandle: Handle, vararg bootstrapMethodArguments: Any) =
                instruction(Opcodes.INVOKEDYNAMIC, "$name$descriptor ${constant(bootstrapMethodHandle)} (${bootstrapMethodArguments.joinToString(", ") { constant(it) }})")
            override fun visitJumpInsn(opcode: Int, target: Label) = instruction(opcode, label(target))
            override fun visitLdcInsn(value: Any) = instruction(Opcodes.LDC, constant(value))
            override fun visitIincInsn(variable: Int, increment: Int) = instruction(Opcodes.IINC, "$variable $increment")
            override fun visitTableSwitchInsn(min: Int, max: Int, default: Label, vararg targets: Label) =
                instruction(Opcodes.TABLESWITCH, "default:${label(default)} " + targets.mapIndexed { i, target -> "${min.toLong() + i}:${label(target)}" }.joinToString(" "))
            override fun visitLookupSwitchInsn(default: Label, keys: IntArray, targets: Array<out Label>) =
                instruction(Opcodes.LOOKUPSWITCH, "default:${label(default)} " + keys.indices.joinToString(" ") { "${keys[it]}:${label(targets[it])}" })
            override fun visitMultiANewArrayInsn(descriptor: String, dimensions: Int) = instruction(Opcodes.MULTIANEWARRAY, "$descriptor $dimensions")
            override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
                if (++metadataCount > 16_384) limit("Too many debug or exception records")
                handlers += BytecodeTryCatch(text(label(start)), text(label(end)), text(label(handler)), type?.let(::text))
            }
            override fun visitLocalVariable(name: String, descriptor: String, signature: String?, start: Label, end: Label, index: Int) {
                if (++metadataCount > 16_384) limit("Too many debug or exception records")
                locals += BytecodeLocal(text(name), text(descriptor), index, text(label(start)), text(label(end)), signature?.let(::text))
            }
            override fun visitMaxs(maxStack: Int, maxLocals: Int) { this.maxStack = maxStack; this.maxLocals = maxLocals }
            override fun visitEnd() {
                methods += BytecodeMethod(methodName, methodDescriptor, access, maxStack, maxLocals, instructions.toList(), handlers.toList(), locals.toList(), methodSignature, methodExceptions)
            }
        }
    }

    fun result() = BytecodeClass(name, superName, version, access, sourceFile, fields.toList(), methods.toList(), interfaces)
}

/** Reject hostile lengths before ASM allocates labels, switch arrays or bootstrap arguments. */
private class ClassBounds(private val bytes: ByteArray) {
    private lateinit var tags: IntArray
    private lateinit var offsets: IntArray
    private val bootstrap = ArrayList<IntArray>()
    private var instructionCount = 0
    private var annotationValues = 0
    private var bootstrapArguments = 0

    private inner class Cursor(var at: Int, val end: Int) {
        fun need(count: Int) { if (count < 0 || at > end - count) malformed("Truncated JVM class file") }
        fun u1(): Int { need(1); return bytes[at++].toInt() and 255 }
        fun u2(): Int = (u1() shl 8) or u1()
        fun u4(): Long = (u2().toLong() shl 16) or u2().toLong()
        fun i4(): Int = u4().toInt()
        fun skip(count: Int) { need(count); at += count }
        fun section(size: Long): Cursor {
            if (size > Int.MAX_VALUE) malformed("Invalid JVM attribute length")
            need(size.toInt()); val part = Cursor(at, at + size.toInt()); at = part.end; return part
        }
    }

    fun validate() {
        val cursor = Cursor(0, bytes.size)
        if (cursor.u4() != 0xcafebabeL) malformed("Not a JVM class file")
        cursor.skip(4)
        val count = cursor.u2()
        if (count == 0) malformed("Invalid constant pool")
        tags = IntArray(count); offsets = IntArray(count)
        var index = 1
        while (index < count) {
            val tag = cursor.u1(); tags[index] = tag; offsets[index] = cursor.at
            when (tag) {
                1 -> { val length = cursor.u2(); if (length > BytecodeLimits.MAX_STRING_CHARS * 2) limit("Class string exceeds reader limit"); cursor.skip(length) }
                3, 4, 9, 10, 11, 12, 17, 18 -> cursor.skip(4)
                5, 6 -> { cursor.skip(8); if (++index >= count) malformed("Invalid wide constant") }
                7, 8, 16, 19, 20 -> cursor.skip(2)
                15 -> cursor.skip(3)
                else -> malformed("Unknown JVM constant tag")
            }
            index++
        }
        cursor.skip(6); cursor.skip(cursor.u2() * 2)
        members(cursor, BytecodeLimits.MAX_FIELDS, false); members(cursor, BytecodeLimits.MAX_METHODS, true)
        attributes(cursor, classAttributes = true)
        if (cursor.at != cursor.end) malformed("Trailing data after JVM class file")
        validateDynamicConstants()
    }

    private fun utf8(index: Int, value: String): Boolean {
        if (index <= 0 || index >= tags.size || tags[index] != 1) malformed("Invalid attribute name")
        val cursor = Cursor(offsets[index], bytes.size)
        val size = cursor.u2()
        return size == value.length && value.indices.all { (bytes[cursor.at + it].toInt() and 255) == value[it].code }
    }

    private fun members(cursor: Cursor, maximum: Int, methods: Boolean) {
        val count = cursor.u2()
        if (count > maximum) limit("Too many JVM members")
        repeat(count) { cursor.skip(6); attributes(cursor, allowCode = methods) }
    }

    private fun attributes(cursor: Cursor, allowCode: Boolean = false, classAttributes: Boolean = false) {
        val count = cursor.u2()
        if (count > 4096) limit("Too many JVM attributes")
        repeat(count) {
            val name = cursor.u2(); val part = cursor.section(cursor.u4())
            when {
                utf8(name, "Code") -> { if (!allowCode) malformed("Code attribute outside a method"); code(part) }
                utf8(name, "BootstrapMethods") -> {
                    if (!classAttributes) malformed("Bootstrap table outside a class")
                    val methods = part.u2()
                    if (methods > 4096 || bootstrap.isNotEmpty()) limit("Too many bootstrap methods")
                    repeat(methods) {
                        part.u2(); val size = part.u2(); bootstrapArguments += size
                        if (size > 256 || bootstrapArguments > 16_384) limit("Too many bootstrap arguments")
                        bootstrap += IntArray(size) { part.u2() }
                    }
                }
                utf8(name, "Record") -> {
                    if (!classAttributes) malformed("Record attribute outside a class")
                    val components = part.u2()
                    if (components > 4096) limit("Too many record components")
                    repeat(components) { part.skip(4); attributes(part) }
                }
                utf8(name, "RuntimeVisibleAnnotations") || utf8(name, "RuntimeInvisibleAnnotations") -> repeat(part.u2()) { annotation(part, 0) }
                utf8(name, "RuntimeVisibleParameterAnnotations") || utf8(name, "RuntimeInvisibleParameterAnnotations") -> repeat(part.u1()) { repeat(part.u2()) { annotation(part, 0) } }
                utf8(name, "RuntimeVisibleTypeAnnotations") || utf8(name, "RuntimeInvisibleTypeAnnotations") -> repeat(part.u2()) { typeAnnotation(part) }
                utf8(name, "AnnotationDefault") -> element(part, 0)
                else -> part.at = part.end
            }
            if (part.at != part.end) malformed("Invalid JVM attribute size")
        }
    }

    private fun annotation(cursor: Cursor, depth: Int) {
        if (depth > 32 || ++annotationValues > 16_384) limit("Annotation nesting or values exceed reader limit")
        cursor.u2(); repeat(cursor.u2()) { cursor.u2(); element(cursor, depth + 1) }
    }

    private fun element(cursor: Cursor, depth: Int) {
        if (depth > 32 || ++annotationValues > 16_384) limit("Annotation nesting or values exceed reader limit")
        when (cursor.u1().toChar()) {
            'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> cursor.skip(2)
            'e' -> cursor.skip(4)
            '@' -> annotation(cursor, depth + 1)
            '[' -> repeat(cursor.u2()) { element(cursor, depth + 1) }
            else -> malformed("Invalid annotation value")
        }
    }

    private fun typeAnnotation(cursor: Cursor) {
        when (cursor.u1()) {
            0, 1, 22 -> cursor.skip(1)
            16, 17, 18, 23, 66, 67, 68, 69, 70 -> cursor.skip(2)
            19, 20, 21 -> Unit
            64, 65 -> cursor.skip(cursor.u2() * 6)
            in 71..75 -> cursor.skip(3)
            else -> malformed("Invalid type annotation target")
        }
        cursor.skip(cursor.u1() * 2); annotation(cursor, 0)
    }

    private fun code(cursor: Cursor) {
        cursor.skip(4)
        val length = cursor.u4()
        if (length !in 1..65_535) malformed("Invalid JVM method code length")
        val code = cursor.section(length); val base = code.at
        val starts = BooleanArray(length.toInt() + 1); val targets = ArrayList<Int>()
        fun target(offset: Int, relative: Int) {
            val result = offset.toLong() + relative
            if (result < 0 || result >= length) malformed("Branch target outside JVM method")
            targets += result.toInt()
        }
        while (code.at < code.end) {
            val offset = code.at - base; starts[offset] = true
            if (++instructionCount > BytecodeLimits.MAX_INSTRUCTIONS) limit("Too many bytecode instructions")
            when (val opcode = code.u1()) {
                16, 18, in 21..25, in 54..58, 169, 188 -> code.skip(1)
                17, 19, 20, 132, in 178..184, 187, 189, 192, 193 -> code.skip(2)
                in 153..168, 198, 199 -> { val relative = code.u2().toShort().toInt(); target(offset, relative) }
                185, 186 -> code.skip(4)
                197 -> code.skip(3)
                200, 201 -> target(offset, code.i4())
                170, 171 -> {
                    code.skip((4 - ((code.at - base) and 3)) and 3)
                    target(offset, code.i4())
                    if (opcode == 170) {
                        val low = code.i4(); val high = code.i4(); val count = high.toLong() - low + 1
                        if (count < 0 || count > (code.end - code.at) / 4) malformed("Invalid tableswitch size")
                        repeat(count.toInt()) { target(offset, code.i4()) }
                    } else {
                        val count = code.i4()
                        if (count < 0 || count > (code.end - code.at) / 8) malformed("Invalid lookupswitch size")
                        var previous: Int? = null
                        repeat(count) {
                            val key = code.i4(); if (previous != null && key <= previous!!) malformed("Unsorted lookupswitch keys")
                            previous = key; target(offset, code.i4())
                        }
                    }
                }
                196 -> when (code.u1()) { 132 -> code.skip(4); in 21..25, in 54..58, 169 -> code.skip(2); else -> malformed("Invalid wide opcode") }
                in 0..15, in 26..53, in 59..131, in 133..152, in 172..177, 190, 191, 194, 195 -> Unit
                else -> malformed("Invalid JVM opcode")
            }
        }
        starts[length.toInt()] = true
        if (targets.any { !starts[it] }) malformed("Branch target is not an instruction boundary")
        val handlers = cursor.u2()
        if (handlers > 4096) limit("Too many exception handlers")
        repeat(handlers) {
            val start = cursor.u2(); val end = cursor.u2(); val handler = cursor.u2(); cursor.u2()
            if (start >= end || end > length || handler >= length || !starts[start] || !starts[end] || !starts[handler]) malformed("Invalid exception handler range")
        }
        attributes(cursor)
    }

    private fun validateDynamicConstants() {
        val states = ByteArray(tags.size); val heights = IntArray(tags.size); var edges = 0
        fun visit(index: Int, depth: Int): Int {
            if (index <= 0 || index >= tags.size) malformed("Invalid bootstrap constant")
            if (tags[index] != 17 && tags[index] != 18) return 0
            if (depth > 32) limit("Dynamic constant nesting exceeds reader limit")
            if (states[index].toInt() == 1) malformed("Cyclic dynamic constants")
            if (states[index].toInt() == 2) {
                if (depth + heights[index] > 32) limit("Dynamic constant nesting exceeds reader limit")
                return heights[index]
            }
            states[index] = 1
            val cursor = Cursor(offsets[index], bytes.size); val method = cursor.u2()
            if (method >= bootstrap.size) malformed("Invalid bootstrap method index")
            var height = 0
            for (argument in bootstrap[method]) {
                if (++edges > 100_000) limit("Dynamic constant graph exceeds reader limit")
                height = maxOf(height, 1 + visit(argument, depth + 1))
            }
            if (height + depth > 32) limit("Dynamic constant nesting exceeds reader limit")
            states[index] = 2; heights[index] = height
            return height
        }
        for (index in tags.indices) if (tags[index] == 17 || tags[index] == 18) visit(index, 0)
    }
}
