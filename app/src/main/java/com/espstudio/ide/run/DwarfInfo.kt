package com.espstudio.ide.run

/**
 * 极简 DWARF 解析：从 `llvm-dwarfdump --debug-info` 的文本里取出
 * 函数范围、帧基址寄存器和局部变量的位置/类型。
 */
data class DwarfLocation(
    val kind: String,      // fbreg / reg / breg / addr
    val reg: Int,
    val offset: Int,
    val addr: Long,
)

data class DwarfVar(
    val name: String,
    val location: DwarfLocation,
    val typeName: String,
    val typeOffset: Long = 0,
)

data class DwarfMember(
    val name: String,
    val offset: Int,
    val typeOffset: Long,
)

data class DwarfType(
    val offset: Long,
    val tag: String,
    val name: String,
    val byteSize: Int,
    val ref: Long,
    val members: List<DwarfMember>,
    val count: Int,
)

data class DwarfFunc(
    val name: String,
    val low: Long,
    val high: Long,
    val frameBaseReg: Int,
    val vars: List<DwarfVar>,
    val declFile: String = "",
)

class DwarfInfo(
    private val funcs: List<DwarfFunc>,
    private val types: Map<Long, DwarfType> = emptyMap(),
) {

    fun typeAt(offset: Long): DwarfType? = types[offset]

    fun baseTypeByName(name: String): DwarfType? =
        types.values.firstOrNull { it.name == name && it.tag == "DW_TAG_base_type" }
    fun at(addr: Long): DwarfFunc? =
        funcs.firstOrNull { addr >= it.low && addr < it.high }

    fun all(): List<DwarfFunc> = funcs
}

object DwarfParser {

    private class Die(
        val offset: Long,
        val tag: String,
        val indent: Int,
        val attrs: MutableMap<String, String> = mutableMapOf(),
        val children: MutableList<Die> = mutableListOf(),
    )

    private val dieRe = Regex("""^\s*0x([0-9a-fA-F]+):\s+(DW_TAG_\w+)\s*$""")
    private val attrRe = Regex("""^\s+DW_AT_(\w+)\s+\((.*)\)\s*$""")

    fun parse(text: String): DwarfInfo {
        val root = Die(-1, "root", -1)
        val stack = ArrayDeque<Die>()
        stack.addLast(root)
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isEmpty()) continue
            val dieMatch = dieRe.find(line)
            if (dieMatch != null) {
                val indent = line.indexOf("DW_TAG_")
                val die = Die(dieMatch.groupValues[1].toLong(16), dieMatch.groupValues[2], indent)
                while (stack.size > 1 && stack.last().indent >= indent) stack.removeLast()
                stack.last().children.add(die)
                stack.addLast(die)
                continue
            }
            val attr = attrRe.find(line)
            if (attr != null && stack.size > 1) {
                stack.last().attrs[attr.groupValues[1]] = attr.groupValues[2]
            }
        }

        val funcs = ArrayList<DwarfFunc>()
        collectFunctions(root, funcs)
        val types = HashMap<Long, DwarfType>()
        collectTypes(root, types)
        return DwarfInfo(funcs, types)
    }

    private fun collectFunctions(die: Die, out: MutableList<DwarfFunc>) {
        for (child in die.children) {
            if (child.tag == "DW_TAG_subprogram") {
                val low = hex(child.attrs["low_pc"])
                val high = highPc(child.attrs)
                if (low != null && high != null && high > low) {
                    val frameBase = frameBaseReg(child.attrs["frame_base"])
                    val vars = ArrayList<DwarfVar>()
                    collectVars(child, vars)
                    out.add(
                        DwarfFunc(
                            name = str(child.attrs["name"]) ?: "?",
                            low = low,
                            high = high,
                            frameBaseReg = frameBase,
                            vars = vars,
                            declFile = str(child.attrs["decl_file"]) ?: "",
                        )
                    )
                }
                // 子程序内部可能还有嵌套函数，继续递归但不要重复收变量
                collectFunctions(child, out)
            } else {
                collectFunctions(child, out)
            }
        }
    }

    private fun collectVars(die: Die, out: MutableList<DwarfVar>) {
        for (child in die.children) {
            when (child.tag) {
                "DW_TAG_variable", "DW_TAG_formal_parameter" -> {
                    val name = str(child.attrs["name"]) ?: continue
                    val loc = parseLocation(child.attrs["location"]) ?: continue
                    val typeName = typeName(child.attrs["type"]) ?: "int"
                    out.add(DwarfVar(name, loc, typeName, typeOffset(child.attrs["type"])))
                }
                "DW_TAG_subprogram" -> {
                    // 嵌套函数单独处理
                }
                else -> collectVars(child, out)
            }
        }
    }

    private fun typeOffset(raw: String?): Long {
        if (raw == null) return 0
        val token = raw.trim().substringBefore(' ').substringBefore('\t')
        return token.removePrefix("0x").toLongOrNull(16) ?: 0
    }

    private fun collectTypes(die: Die, out: MutableMap<Long, DwarfType>) {
        val tag = die.tag
        if (tag.startsWith("DW_TAG_") && (
                tag == "DW_TAG_structure_type" || tag == "DW_TAG_class_type" ||
                    tag == "DW_TAG_union_type" || tag == "DW_TAG_base_type" ||
                    tag == "DW_TAG_pointer_type" || tag == "DW_TAG_reference_type" ||
                    tag == "DW_TAG_typedef" || tag == "DW_TAG_const_type" ||
                    tag == "DW_TAG_array_type"
                )
        ) {
            val members = ArrayList<DwarfMember>()
            var count = 0
            for (c in die.children) {
                if (c.tag == "DW_TAG_member") {
                    val off = dieOffset(c.attrs["data_member_location"])
                    members.add(DwarfMember(str(c.attrs["name"]) ?: "?", off, typeOffset(c.attrs["type"])))
                } else if (c.tag == "DW_TAG_subrange_type") {
                    count = hex(c.attrs["count"])?.toInt() ?: 0
                }
            }
            out[die.offset] = DwarfType(
                offset = die.offset,
                tag = tag,
                name = str(die.attrs["name"]) ?: "",
                byteSize = hex(die.attrs["byte_size"])?.toInt() ?: 0,
                ref = typeOffset(die.attrs["type"]),
                members = members,
                count = count,
            )
        }
        for (c in die.children) collectTypes(c, out)
    }

    private fun dieOffset(raw: String?): Int {
        if (raw == null) return 0
        Regex("0x([0-9a-fA-F]+)").find(raw)?.let { return it.groupValues[1].toLongOrNull(16)?.toInt() ?: 0 }
        Regex("\\d+").find(raw)?.let { return it.value.toIntOrNull() ?: 0 }
        return 0
    }

    private fun highPc(attrs: Map<String, String>): Long? {
        val raw = attrs["high_pc"] ?: return null
        return hex(raw)
    }

    private fun hex(raw: String?): Long? {
        if (raw == null) return null
        val token = raw.trim().substringBefore(' ').substringBefore('\t')
        return token.removePrefix("0x").toLongOrNull(16)
    }

    private fun str(raw: String?): String? {
        if (raw == null) return null
        val m = Regex("\"(.*)\"").find(raw) ?: return null
        return m.groupValues[1]
    }

    private fun typeName(raw: String?): String? {
        if (raw == null) return null
        return Regex("\"(.*)\"").find(raw)?.groupValues?.get(1)
    }

    private fun frameBaseReg(raw: String?): Int {
        if (raw == null) return 29
        val m = Regex("DW_OP_reg(\\d+)").find(raw)
        if (m != null) return m.groupValues[1].toInt()
        // DW_OP_call_frame_cfa：aarch64 上 CFA ≈ x29 + 16，用 x29 近似
        return 29
    }

    private fun parseLocation(raw: String?): DwarfLocation? {
        if (raw == null) return null
        Regex("DW_OP_fbreg\\s*([+-]?\\d+)").find(raw)?.let {
            return DwarfLocation("fbreg", 0, it.groupValues[1].toInt(), 0)
        }
        Regex("DW_OP_breg(\\d+)\\s*\\S*?([+-]\\d+)").find(raw)?.let {
            return DwarfLocation("breg", it.groupValues[1].toInt(), it.groupValues[2].toInt(), 0)
        }
        Regex("DW_OP_reg(\\d+)").find(raw)?.let {
            return DwarfLocation("reg", it.groupValues[1].toInt(), 0, 0)
        }
        Regex("DW_OP_addr\\s*0x([0-9a-fA-F]+)").find(raw)?.let {
            return DwarfLocation("addr", 0, 0, it.groupValues[1].toLong(16))
        }
        return null
    }
}
