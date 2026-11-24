package com.codepad.ide.ext

import com.codepad.ide.App
import org.json.JSONObject
import java.io.File

enum class RunKind { C, CPP, PYTHON, JAVASCRIPT, RUST, SHELL, COMMAND, WEB, NONE }

data class LangSpec(
    val id: String,
    val name: String,
    val monaco: String,
    val exts: List<String>,
    val runKind: RunKind = RunKind.NONE,
    val command: String? = null,
    val builtin: Boolean = true,
)

object Languages {

    private val builtins = listOf(
        LangSpec("c", "C", "c", listOf("c", "h"), RunKind.C),
        LangSpec("cpp", "C++", "cpp", listOf("cpp", "cc", "cxx", "hpp"), RunKind.CPP),
        LangSpec("python", "Python", "python", listOf("py", "pyw"), RunKind.PYTHON),
        LangSpec("javascript", "JavaScript", "javascript", listOf("js", "mjs", "cjs"), RunKind.JAVASCRIPT),
        LangSpec("typescript", "TypeScript", "typescript", listOf("ts", "tsx"), RunKind.NONE),
        LangSpec("rust", "Rust", "rust", listOf("rs"), RunKind.RUST),
        LangSpec("shell", "Shell", "shell", listOf("sh", "bash", "zsh"), RunKind.SHELL),
        LangSpec("json", "JSON", "json", listOf("json")),
        LangSpec("markdown", "Markdown", "markdown", listOf("md", "markdown")),
        LangSpec("html", "HTML", "html", listOf("html", "htm"), RunKind.WEB),
        LangSpec("css", "CSS", "css", listOf("css", "scss", "less")),
        LangSpec("java", "Java", "java", listOf("java")),
        LangSpec("kotlin", "Kotlin", "kotlin", listOf("kt", "kts")),
        LangSpec("go", "Go", "go", listOf("go")),
        LangSpec("yaml", "YAML", "yaml", listOf("yml", "yaml")),
        LangSpec("sql", "SQL", "sql", listOf("sql")),
        LangSpec("text", "Plain Text", "plaintext", listOf("txt", "log", "ini", "conf")),
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
        val ext = name.substringAfterLast('.', "")
        return all().firstOrNull { it.exts.any { e -> e.equals(ext, true) } }
            ?: all().first { it.id == "text" }
    }

    fun byId(id: String): LangSpec? = all().firstOrNull { it.id == id }

    /**
     * 扩展格式：files/extensions/<id>/manifest.json
     * {
     *   "name": "Go",
     *   "monaco": "go",
     *   "extensions": [".go"],
     *   "run": "go run \"$FILE\""
     * }
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
                    val run = if (o.has("run")) o.getString("run") else null
                    out.add(
                        LangSpec(
                            id = "ext:" + dir.name,
                            name = name,
                            monaco = monaco,
                            exts = extList,
                            runKind = if (run != null) RunKind.COMMAND else RunKind.NONE,
                            command = run,
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
