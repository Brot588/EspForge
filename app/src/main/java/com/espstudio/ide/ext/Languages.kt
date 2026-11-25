package com.espstudio.ide.ext

import com.espstudio.ide.App
import org.json.JSONObject
import java.io.File

/**
 * 语言定义。这个 IDE 只做 ESP-IDF（C / C 头文件 / CMake / Makefile 一类），
 * 不再带 Python / Rust / JavaScript 的运行时。
 */
data class LangSpec(
    val id: String,
    val name: String,
    val monaco: String,
    val exts: List<String>,
    val builtin: Boolean = true,
)

object Languages {

    private val builtins = listOf(
        LangSpec("c", "C", "c", listOf("c", "h")),
        LangSpec("cpp", "C++", "cpp", listOf("cpp", "cc", "cxx", "hpp")),
        LangSpec("cmake", "CMake", "cmake", listOf("cmake")),
        LangSpec("makefile", "Makefile", "makefile", listOf("mk", "make")),
        LangSpec("kconfig", "Kconfig", "ini", listOf("kconfig")),
        LangSpec("json", "JSON", "json", listOf("json")),
        LangSpec("yaml", "YAML", "yaml", listOf("yml", "yaml")),
        LangSpec("csv", "CSV", "plaintext", listOf("csv")),
        LangSpec("markdown", "Markdown", "markdown", listOf("md", "markdown")),
        LangSpec("python", "Python", "python", listOf("py")),
        LangSpec("shell", "Shell", "shell", listOf("sh")),
        LangSpec("text", "Plain Text", "plaintext", listOf("txt", "log", "ini", "conf", "cfg", "defaults")),
    )

    @Volatile
    private var cache: List<LangSpec>? = null

    fun all(): List<LangSpec> {
        cache?.let { return it }
        val list = ArrayList(builtins)
        list.addAll(loadExtensions())
        cache = list
        return list
    }

    fun reload() {
        cache = null
    }

    fun forFile(file: File): LangSpec {
        val name = file.name.lowercase()
        // 无扩展名的几个特殊文件名
        when (name) {
            "cmakelists.txt" -> return byId("cmake") ?: all().first { it.id == "cmake" }
            "makefile", "gnumakefile" -> return byId("makefile") ?: all().first { it.id == "makefile" }
            "sdkconfig", "sdkconfig.defaults", "idf_component.yml" -> return byId("text")!!
        }
        val ext = name.substringAfterLast('.', "")
        return all().firstOrNull { it.exts.any { e -> e.equals(ext, true) } }
            ?: all().first { it.id == "text" }
    }

    fun byId(id: String): LangSpec? = all().firstOrNull { it.id == id }

    /**
     * 用户扩展：files/extensions/<name>/manifest.json
     * {
     *   "name": "Rust",
     *   "monaco": "rust",
     *   "extensions": [".rs"]
     * }
     * 只影响高亮与打开方式，不提供运行时。
     */
    private fun loadExtensions(): List<LangSpec> {
        val out = ArrayList<LangSpec>()
        val root = App.extensionsRoot
        if (!root.isDirectory) return out

        fun scan(dir: File, depth: Int) {
            if (depth > 3) return
            val manifest = File(dir, "manifest.json")
            if (manifest.isFile) {
                try {
                    val o = JSONObject(manifest.readText())
                    val name = o.optString("name", dir.name)
                    val monaco = o.optString("monaco", "plaintext")
                    val exts = o.optJSONArray("extensions")
                    val extList = ArrayList<String>()
                    if (exts != null) {
                        for (i in 0 until exts.length()) {
                            extList.add(exts.getString(i).removePrefix(".").lowercase())
                        }
                    }
                    out.add(
                        LangSpec(
                            id = "ext:" + dir.name,
                            name = name,
                            monaco = monaco,
                            exts = extList,
                            builtin = false,
                        )
                    )
                } catch (_: Throwable) {
                }
            } else {
                dir.listFiles()?.filter { it.isDirectory }?.forEach { scan(it, depth + 1) }
            }
        }

        scan(root, 0)
        return out
    }
}
