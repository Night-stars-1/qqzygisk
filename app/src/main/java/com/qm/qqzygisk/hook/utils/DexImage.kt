package com.qm.qqzygisk.hook.utils

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.ZipFile

/**
 * 只读 DEX 视图：类层次、方法体里的调用、字符串和字段访问。
 *
 * 微信每个版本都会位移混淆短名，反射签名不够区分标题、摘要、表情路径和上传队列。
 * 这里按 DEX 规范解码这些形状，不执行、不改宿主字节码。多 DEX 时先出现的类定义优先。
 */
internal class HostDex(private val images: List<DexImage>) {
    private val located = HashMap<String, DexImage>()

    init {
        for (image in images) {
            for (name in image.classNames()) located.putIfAbsent(name, image)
        }
        check(located.isNotEmpty()) { "宿主 DEX 中没有类" }
    }

    fun classNames(packageName: String, direct: Boolean = false): List<String> {
        val prefix = "$packageName."
        val found = ArrayList<String>()
        for (name in located.keys) {
            if (!name.startsWith(prefix)) continue
            if (direct && '.' in name.substring(prefix.length)) continue
            found.add(name)
        }
        return found
    }

    fun hasClass(name: String): Boolean = name in located

    fun superName(name: String): String? = located[name]?.superName(name)

    fun interfacesOf(name: String): List<String> = located[name]?.interfacesOf(name).orEmpty()

    fun accessOf(name: String): Int = located[name]?.accessOf(name) ?: 0

    fun methods(name: String): List<DexBody> = located[name]?.methods(name).orEmpty()

    fun forEachMethodId(block: (DexId) -> Unit) {
        val seen = HashSet<String>()
        for (image in images) image.forEachMethodId { id ->
            if (seen.add(id.key())) block(id)
        }
    }

    /** 只物化参数和返回类型都匹配的方法，不把整张方法表变成字符串。 */
    fun forEachMethod(parameterTypes: List<String>, returnType: String?, block: (DexId) -> Unit) {
        val seen = HashSet<String>()
        for (image in images) image.forEachMethodMatching(parameterTypes, returnType) { id ->
            if (seen.add(id.key())) block(id)
        }
    }

    fun forEachField(block: (DexField) -> Unit) {
        val seen = HashSet<String>()
        for (image in images) image.forEachField { field ->
            if (seen.add("${field.owner}.${field.name}:${field.type}")) block(field)
        }
    }

    fun forEachField(owner: String?, type: String, block: (DexField) -> Unit) {
        val seen = HashSet<String>()
        for (image in images) image.forEachFieldMatching(owner, type) { field ->
            if (seen.add("${field.owner}.${field.name}:${field.type}")) block(field)
        }
    }

    fun methodsReferencing(
        text: String,
        parameterTypes: List<String>? = null,
        returnType: String? = null,
        staticOnly: Boolean = false,
    ): List<DexId> {
        val found = LinkedHashMap<String, DexId>()
        for (image in images) {
            for (id in image.methodsReferencing(text, parameterTypes, returnType, staticOnly)) {
                found.putIfAbsent(id.key(), id)
            }
        }
        return found.values.toList()
    }

    fun isSubclass(typeName: String, ancestor: String): Boolean {
        if (typeName == ancestor) return true
        val seen = HashSet<String>()
        var current = typeName
        while (seen.add(current)) {
            val parent = superName(current) ?: return false
            if (parent == ancestor) return true
            current = parent
        }
        return false
    }

    fun isImageView(typeName: String): Boolean = IMAGE_VIEWS.any { typeName == it || isSubclass(typeName, it) }

    fun isActivity(typeName: String): Boolean = ACTIVITIES.any { typeName == it || isSubclass(typeName, it) }

    private companion object {
        val IMAGE_VIEWS = listOf(
            "android.widget.ImageView",
            "android.widget.ImageButton",
            "androidx.appcompat.widget.AppCompatImageView",
            "androidx.appcompat.widget.AppCompatImageButton",
        )
        val ACTIVITIES = listOf(
            "android.app.Activity",
            "androidx.appcompat.app.AppCompatActivity",
            "androidx.fragment.app.FragmentActivity",
            "androidx.activity.ComponentActivity",
        )
    }
}

internal fun readHostDex(files: List<File>): HostDex {
    val images = ArrayList<DexImage>()
    for (file in files) {
        when {
            !file.isFile -> continue
            file.name.endsWith(".apk") || file.name.endsWith(".zip") || file.name.endsWith(".xapk") ->
                images += readZipDexes(file)
            else -> if (isDex(file)) images += DexImage(mapReadOnly(file))
        }
    }
    return HostDex(images)
}

internal fun readZipDexes(file: File): List<DexImage> = ZipFile(file).use { zip ->
    zip.entries().asSequence()
        .map { it.name }
        .filter { entry -> entry.matches(DEX_ENTRY) }
        .sortedBy { dexOrder(it.substringAfterLast('/')) }
        .map { name ->
            val tmp = File.createTempFile("qqzygisk-", ".dex")
            tmp.deleteOnExit()
            zip.getInputStream(zip.getEntry(name)).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            DexImage(mapReadOnly(tmp))
        }
        .toList()
}

private fun mapReadOnly(file: File): ByteBuffer =
    FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.READ).use { channel ->
        channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.LITTLE_ENDIAN)
    }

private fun dexOrder(name: String): Int =
    if (name == "classes.dex") 1 else name.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: Int.MAX_VALUE

private fun isDex(file: File): Boolean {
    if (file.length() < 8) return false
    return file.inputStream().use { input ->
        val magic = ByteArray(4)
        input.read(magic) == 4 && magic[0] == 0x64.toByte() && magic[1] == 0x65.toByte() &&
            magic[2] == 0x78.toByte() && magic[3] == 0x0A.toByte()
    }
}

private val DEX_ENTRY = Regex("""(?:.*/)?classes\d*\.dex""")

internal class DexId(
    val owner: String,
    val name: String,
    val parameterTypes: List<String>,
    val returnType: String,
    val access: Int,
) {
    fun isStatic(): Boolean = access and ACC_STATIC != 0
    fun isBridge(): Boolean = access and ACC_BRIDGE != 0
    fun isAbstract(): Boolean = access and ACC_ABSTRACT != 0
    fun describe(): String = "$owner.$name(${parameterTypes.joinToString(",")})$returnType"
    fun key(): String = "$owner#$name(${parameterTypes.joinToString(",")})$returnType"
}

internal class DexField(
    val owner: String,
    val name: String,
    val type: String,
    val write: Boolean = false,
    val static: Boolean = false,
)

internal class DexBody(
    val id: DexId,
    val steps: List<DexStep>,
    val complete: Boolean,
) {
    val invokes: List<DexId> = steps.mapNotNull { (it as? DexStep.Call)?.method }
    val strings: List<String> = steps.mapNotNull { (it as? DexStep.Text)?.value }
    val fields: List<DexField> = steps.mapNotNull { (it as? DexStep.Field)?.field }
}

internal sealed interface DexStep {
    class Call(val method: DexId) : DexStep
    class Text(val value: String) : DexStep
    class Field(val field: DexField) : DexStep
}

internal class DexImage(source: ByteBuffer) {
    private val bytes = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    private val protos = HashMap<Int, Pair<String, List<String>>>()
    private val classes = HashMap<String, ClassMeta>()
    private val methodCache = HashMap<String, List<DexBody>>()

    init {
        val count = u32(96)
        val base0 = u32(100)
        for (index in 0 until count) {
            val base = base0 + index * 32
            val name = javaName(typeAt(u32(base)))
            val access = u32(base + 4)
            val ifaceOff = u32(base + 12)
            val ifaces = ArrayList<String>()
            if (ifaceOff != 0) {
                val size = u32(ifaceOff)
                for (slot in 0 until size) ifaces += javaName(typeAt(u16(ifaceOff + 4 + slot * 2)))
            }
            val superIdx = u32(base + 8)
            val superName = if (superIdx == -1) null else javaName(typeAt(superIdx))
            classes[name] = ClassMeta(superName, ifaces, u32(base + 24), access)
        }
    }

    fun classNames(): Set<String> = classes.keys

    fun superName(name: String): String? = classes[name]?.superName

    fun interfacesOf(name: String): List<String> = classes[name]?.interfaces.orEmpty()

    fun accessOf(name: String): Int = classes[name]?.access ?: 0

    fun methods(className: String): List<DexBody> = methodCache.getOrPut(className) {
        val meta = classes[className] ?: return@getOrPut emptyList()
        if (meta.dataOff == 0) return@getOrPut emptyList()
        val out = ArrayList<DexBody>()
        readMembers(meta.dataOff) { methodIndex, access, code ->
            val ident = methodId(methodIndex, access)
            if (ident.owner != className) return@readMembers
            if (code == 0) {
                out += DexBody(ident, emptyList(), true)
                return@readMembers
            }
            out += try {
                decode(ident, code)
            } catch (_: IndexOutOfBoundsException) {
                DexBody(ident, emptyList(), false)
            }
        }
        out
    }

    fun forEachMethodId(block: (DexId) -> Unit) {
        val count = u32(88)
        for (index in 0 until count) block(methodId(index, 0))
    }

    fun forEachField(block: (DexField) -> Unit) {
        val count = u32(80)
        for (index in 0 until count) block(fieldUse(index, write = false, static = false))
    }

    fun methodsReferencing(
        text: String,
        parameterTypes: List<String>? = null,
        returnType: String? = null,
        staticOnly: Boolean = false,
    ): List<DexId> {
        val wanted = stringIndexes(text)
        if (wanted.isEmpty()) return emptyList()
        val params = parameterTypes?.map { typeIndex(it) }
        if (params != null && params.any { it < 0 }) return emptyList()
        val ret = returnType?.let { typeIndex(it) } ?: -1
        if (returnType != null && ret < 0) return emptyList()
        val found = ArrayList<DexId>()
        for ((name, meta) in classes) {
            if (meta.dataOff == 0) continue
            readMembers(meta.dataOff) { methodIndex, access, code ->
                if (code == 0) return@readMembers
                if (staticOnly && access and ACC_STATIC == 0) return@readMembers
                if (params != null && !protoMatches(u16(u32(92) + methodIndex * 8 + 2), ret, params)) {
                    return@readMembers
                }
                if (!containsString(code, wanted)) return@readMembers
                val ident = methodId(methodIndex, access)
                if (ident.owner == name) found += ident
            }
        }
        return found
    }

    fun forEachMethodMatching(parameterTypes: List<String>, returnType: String?, block: (DexId) -> Unit) {
        val params = parameterTypes.map { typeIndex(it) }
        if (params.any { it < 0 }) return
        val ret = returnType?.let { typeIndex(it) } ?: -1
        if (returnType != null && ret < 0) return
        val count = u32(88)
        val base0 = u32(92)
        for (index in 0 until count) {
            if (!protoMatches(u16(base0 + index * 8 + 2), ret, params)) continue
            block(methodId(index, 0))
        }
    }

    fun forEachFieldMatching(owner: String?, type: String, block: (DexField) -> Unit) {
        val typeIdx = typeIndex(type)
        val ownerIdx = owner?.let { typeIndex(it) } ?: -1
        if (typeIdx < 0 || (owner != null && ownerIdx < 0)) return
        val count = u32(80)
        val base0 = u32(84)
        for (index in 0 until count) {
            val base = base0 + index * 8
            if (u16(base + 2) != typeIdx) continue
            if (owner != null && u16(base) != ownerIdx) continue
            block(fieldUse(index, write = false, static = false))
        }
    }

    private val typeIndexes = HashMap<String, Int>()

    private fun typeIndex(javaName: String): Int = typeIndexes.getOrPut(javaName) {
        val raw = descriptorOf(javaName)
        val count = u32(64)
        val types = u32(68)
        val strings = u32(60)
        var found = -1
        for (index in 0 until count) {
            val data = u32(strings + u32(types + index * 4) * 4)
            if (utf8Equals(data, raw)) {
                found = index
                break
            }
        }
        found
    }

    private fun protoMatches(protoIdx: Int, returnType: Int, params: List<Int>): Boolean {
        val base = u32(76) + protoIdx * 12
        if (returnType >= 0 && u32(base + 4) != returnType) return false
        val paramsOff = u32(base + 8)
        val size = if (paramsOff == 0) 0 else u32(paramsOff)
        if (size != params.size) return false
        for (index in params.indices) {
            if (u16(paramsOff + 4 + index * 2) != params[index]) return false
        }
        return true
    }

    private fun stringIndexes(text: String): Set<Int> {
        val raw = text.toByteArray(Charsets.UTF_8)
        val found = HashSet<Int>()
        val count = u32(56)
        val off = u32(60)
        for (index in 0 until count) {
            if (utf8Equals(u32(off + index * 4), raw)) found += index
        }
        return found
    }

    /** 比较 DEX 的 MUTF-8 原文。定位用的标记都是 ASCII，和 UTF-8 相同。 */
    private fun utf8Equals(dataOff: Int, raw: ByteArray): Boolean {
        var cursor = dataOff
        while (u8(cursor) and 0x80 != 0) cursor++
        cursor++
        var index = 0
        while (index < raw.size) {
            val bite = u8(cursor)
            if (bite == 0 || bite != (raw[index].toInt() and 0xFF)) return false
            cursor++
            index++
        }
        return u8(cursor) == 0
    }

    private fun descriptorOf(javaName: String): ByteArray = when (javaName) {
        "void" -> "V"
        "boolean" -> "Z"
        "byte" -> "B"
        "short" -> "S"
        "char" -> "C"
        "int" -> "I"
        "long" -> "J"
        "float" -> "F"
        "double" -> "D"
        else -> "L${javaName.replace('.', '/')};"
    }.toByteArray(Charsets.US_ASCII)

    private fun containsString(code: Int, wanted: Set<Int>): Boolean {
        var found = false
        val complete = try {
            walk(code) { event ->
                if (event is RawEvent.Text && event.index in wanted) found = true
            }
        } catch (_: IndexOutOfBoundsException) {
            false
        }
        return found && complete
    }

    private fun decode(ident: DexId, code: Int): DexBody {
        val steps = ArrayList<DexStep>()
        val complete = walk(code) { event ->
            when (event) {
                is RawEvent.Call -> steps += DexStep.Call(methodId(event.index, 0))
                is RawEvent.Text -> steps += DexStep.Text(stringAt(event.index))
                is RawEvent.FieldUse -> steps += DexStep.Field(fieldUse(event.index, event.write, event.static))
            }
        }
        return DexBody(ident, steps, complete)
    }

    private fun walk(code: Int, emit: (RawEvent) -> Unit): Boolean {
        val insnCount = u32(code + 12)
        if (insnCount <= 0 || code + 16 + insnCount * 2 > bytes.capacity()) return false
        val base = code + 16
        val insns = IntArray(insnCount) { u16(base + it * 2) }
        var cursor = 0
        while (cursor < insnCount) {
            val word = insns[cursor]
            if (word == 0x0100) {
                if (cursor + 1 >= insnCount) return false
                cursor += 4 + insns[cursor + 1] * 2
                continue
            }
            if (word == 0x0200) {
                if (cursor + 1 >= insnCount) return false
                cursor += 2 + insns[cursor + 1] * 4
                continue
            }
            if (word == 0x0300) {
                if (cursor + 3 >= insnCount) return false
                val width = insns[cursor + 1]
                val size = insns[cursor + 2] or (insns[cursor + 3] shl 16)
                cursor += 4 + (size * width + 1) / 2
                continue
            }
            val op = word and 0xFF
            val width = WIDTH[op]
            if (width <= 0 || cursor + width > insnCount) return false
            when (op) {
                0x1A -> emit(RawEvent.Text(insns[cursor + 1]))
                0x1B -> emit(RawEvent.Text(insns[cursor + 1] or (insns[cursor + 2] shl 16)))
                0x6E, 0x6F, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78 ->
                    emit(RawEvent.Call(insns[cursor + 1]))
                in 0x52..0x6D -> {
                    val write = op in 0x59..0x5F || op in 0x67..0x6D
                    val static = op in 0x60..0x6D
                    emit(RawEvent.FieldUse(insns[cursor + 1], write, static))
                }
            }
            cursor += width
        }
        return true
    }

    private fun readMembers(dataOff: Int, onMethod: (Int, Int, Int) -> Unit) {
        val cursor = intArrayOf(dataOff)
        val staticFields = uleb(cursor)
        val instanceFields = uleb(cursor)
        val direct = uleb(cursor)
        val virtual = uleb(cursor)
        repeat(staticFields + instanceFields) {
            uleb(cursor)
            uleb(cursor)
        }
        for (count in intArrayOf(direct, virtual)) {
            var methodIndex = 0
            repeat(count) {
                methodIndex += uleb(cursor)
                val access = uleb(cursor)
                val code = uleb(cursor)
                onMethod(methodIndex, access, code)
            }
        }
    }

    private fun methodId(index: Int, access: Int): DexId {
        val base = u32(92) + index * 8
        val (ret, params) = proto(u16(base + 2))
        return DexId(javaName(typeAt(u16(base))), stringAt(u32(base + 4)), params, ret, access)
    }

    private fun fieldUse(index: Int, write: Boolean, static: Boolean): DexField {
        val base = u32(84) + index * 8
        return DexField(
            javaName(typeAt(u16(base))),
            stringAt(u32(base + 4)),
            javaName(typeAt(u16(base + 2))),
            write,
            static,
        )
    }

    private fun proto(index: Int): Pair<String, List<String>> = protos.getOrPut(index) {
        val base = u32(76) + index * 12
        val ret = javaName(typeAt(u32(base + 4)))
        val paramsOff = u32(base + 8)
        val params = ArrayList<String>()
        if (paramsOff != 0) {
            val size = u32(paramsOff)
            for (slot in 0 until size) params += javaName(typeAt(u16(paramsOff + 4 + slot * 2)))
        }
        ret to params
    }

    private fun typeAt(index: Int): String = stringAt(u32(u32(68) + index * 4))

    private fun stringAt(index: Int): String {
        val start = u32(u32(60) + index * 4)
        var cursor = start
        var length = 0
        var shift = 0
        while (true) {
            val bite = u8(cursor)
            cursor++
            length = length or ((bite and 0x7F) shl shift)
            if (bite and 0x80 == 0) break
            shift += 7
        }
        val raw = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val bite = bytes.get(cursor)
            if (bite.toInt() == 0) break
            cursor++
            raw[filled] = bite
            filled++
        }
        return String(raw, 0, filled, Charsets.UTF_8)
    }

    private fun uleb(cursor: IntArray): Int {
        var value = 0
        var shift = 0
        var off = cursor[0]
        while (true) {
            val bite = u8(off)
            off++
            value = value or ((bite and 0x7F) shl shift)
            if (bite and 0x80 == 0) {
                cursor[0] = off
                return value
            }
            shift += 7
        }
    }

    private fun u8(off: Int): Int = bytes.get(off).toInt() and 0xFF

    private fun u32(off: Int): Int = bytes.getInt(off)

    private fun u16(off: Int): Int = bytes.getShort(off).toInt() and 0xFFFF

    private class ClassMeta(
        val superName: String?,
        val interfaces: List<String>,
        val dataOff: Int,
        val access: Int,
    )

    private sealed interface RawEvent {
        class Call(val index: Int) : RawEvent
        class Text(val index: Int) : RawEvent
        class FieldUse(val index: Int, val write: Boolean, val static: Boolean) : RawEvent
    }

    private companion object {

        val WIDTH = IntArray(256) { -1 }.also { width ->
            intArrayOf(
                0x00, 0x01, 0x04, 0x07, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12,
                0x1D, 0x1E, 0x21, 0x27, 0x28,
            ).forEach { width[it] = 1 }
            for (op in 0x7B until 0x90) width[op] = 1
            for (op in 0x90 until 0xB0) width[op] = 2
            for (op in 0xB0 until 0xD0) width[op] = 1
            for (op in 0xD0 until 0xD8) width[op] = 2
            for (op in 0xD8 until 0xE3) width[op] = 2
            intArrayOf(0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1A, 0x1C, 0x1F, 0x20, 0x22, 0x23, 0x29)
                .forEach { width[it] = 2 }
            for (op in 0x2D until 0x3E) width[op] = 2
            for (op in 0x44 until 0x6E) width[op] = 2
            width[0xFE] = 2
            width[0xFF] = 2
            intArrayOf(
                0x03, 0x06, 0x09, 0x14, 0x17, 0x1B, 0x24, 0x25, 0x26, 0x2A, 0x2B, 0x2C,
                0x6E, 0x6F, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78, 0xFC, 0xFD,
            ).forEach { width[it] = 3 }
            width[0xFA] = 4
            width[0xFB] = 4
            width[0x18] = 5
        }
    }
}

private fun javaName(descriptor: String): String = when (descriptor) {
    "V" -> "void"
    "Z" -> "boolean"
    "B" -> "byte"
    "S" -> "short"
    "C" -> "char"
    "I" -> "int"
    "J" -> "long"
    "F" -> "float"
    "D" -> "double"
    else -> if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
        descriptor.substring(1, descriptor.length - 1).replace('/', '.')
    } else {
        descriptor
    }
}

internal const val ACC_INTERFACE = 0x0200
internal const val ACC_ABSTRACT = 0x0400
internal const val ACC_STATIC = 0x0008
internal const val ACC_BRIDGE = 0x0040
