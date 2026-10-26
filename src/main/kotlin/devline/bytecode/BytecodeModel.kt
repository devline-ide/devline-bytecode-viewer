package devline.bytecode

/** Names and descriptors use the JVM internal format. Version is the class-file major version. */
data class BytecodeClass(
    val name: String,
    val superName: String?,
    val version: Int,
    val access: Int,
    val sourceFile: String?,
    val fields: List<BytecodeField>,
    val methods: List<BytecodeMethod>,
    val interfaces: List<String> = emptyList(),
)

data class BytecodeField(val name: String, val descriptor: String, val access: Int, val value: String? = null)

data class BytecodeMethod(
    val name: String,
    val descriptor: String,
    val access: Int,
    val maxStack: Int,
    val maxLocals: Int,
    val instructions: List<BytecodeInstruction>,
    val tryCatch: List<BytecodeTryCatch>,
    val locals: List<BytecodeLocal>,
    val signature: String? = null,
    val exceptions: List<String> = emptyList(),
)

/**
 * Index counts instructions, never byte offsets. LABEL markers use the next instruction's index.
 * Opcodes use ASM's semantic normalization: ILOAD_0 becomes ILOAD 0, LDC_W becomes LDC,
 * and wide branches/variables use their base operation. Binary encodings are not retained.
 */
data class BytecodeInstruction(val index: Int, val line: Int?, val opcode: Int, val mnemonic: String, val operand: String)
data class BytecodeTryCatch(val start: String, val end: String, val handler: String, val type: String?)
data class BytecodeLocal(val name: String, val descriptor: String, val index: Int, val start: String, val end: String, val signature: String? = null)

object BytecodeLimits {
    const val MAX_CLASS_BYTES = 4 * 1024 * 1024
    const val MAX_FIELDS = 4096
    const val MAX_METHODS = 4096
    const val MAX_INSTRUCTIONS = 100_000
    const val MAX_STRING_CHARS = 16_384
    const val MAX_TEXT_CHARS = 4 * 1024 * 1024
}

class BytecodeReadException(val code: String, message: String) : IllegalArgumentException(message)
