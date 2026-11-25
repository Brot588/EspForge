package com.espforge.ide.fs

import java.io.File

/** 工程内全局文本搜索 */
object SearchService {

    data class Hit(
        val path: String,
        val line: Int,
        val col: Int,
        val text: String,
    )

    private val skipDirs = setOf(
        ".git", "build", "target", "node_modules", ".gradle", ".kotlin", ".idea",
        "dist", ".webbuild", ".cxx", "__pycache__", ".dart_tool",
    )

    private val binaryExts = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "svgz",
        "zip", "gz", "tar", "xz", "zst", "7z", "rar",
        "apk", "aab", "jar", "class", "dex",
        "so", "a", "o", "obj", "dylib", "dll", "exe",
        "pdf", "ttf", "otf", "woff", "woff2", "mp3", "mp4", "wav", "webm",
        "rlib", "rmeta", "bin", "pyc",
    )

    private const val MAX_FILE = 400 * 1024
    private const val MAX_HITS = 300

    fun search(root: File, query: String, caseSensitive: Boolean): List<Hit> {
        if (query.isBlank()) return emptyList()
        val hits = ArrayList<Hit>()
        walk(root, query, caseSensitive, hits)
        return hits
    }

    private fun walk(dir: File, query: String, cs: Boolean, hits: MutableList<Hit>) {
        if (hits.size >= MAX_HITS) return
        val children = dir.listFiles() ?: return
        for (f in children.sortedBy { it.name.lowercase() }) {
            if (hits.size >= MAX_HITS) return
            if (f.isDirectory) {
                if (f.name.startsWith(".") || f.name in skipDirs) continue
                walk(f, query, cs, hits)
            } else {
                if (f.name.startsWith(".")) continue
                if (f.extension.lowercase() in binaryExts) continue
                if (f.length() > MAX_FILE) continue
                scanFile(f, query, cs, hits)
            }
        }
    }

    private fun scanFile(f: File, query: String, cs: Boolean, hits: MutableList<Hit>) {
        val bytes = try {
            f.readBytes()
        } catch (_: Throwable) {
            return
        }
        // 简单的二进制判断
        val probe = minOf(bytes.size, 8000)
        for (i in 0 until probe) {
            if (bytes[i] == 0.toByte()) return
        }
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (_: Throwable) {
            return
        }
        if (text.isEmpty()) return
        val needle = if (cs) query else query.lowercase()
        var lineNo = 1
        var lineStart = 0
        var idx = 0
        while (idx <= text.length) {
            if (hits.size >= MAX_HITS) return
            val nl = text.indexOf('\n', idx)
            val end = if (nl < 0) text.length else nl
            val line = text.substring(lineStart, end)
            val hay = if (cs) line else line.lowercase()
            val col = hay.indexOf(needle)
            if (col >= 0) {
                hits.add(Hit(f.absolutePath, lineNo, col + 1, line.trim().take(160)))
            }
            if (nl < 0) break
            lineNo++
            lineStart = nl + 1
            idx = nl + 1
        }
    }
}
