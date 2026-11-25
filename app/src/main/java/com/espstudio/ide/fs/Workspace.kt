package com.espstudio.ide.fs

import java.io.File

data class FileNode(
    val file: File,
    val name: String,
    val isDir: Boolean,
    val depth: Int,
    val hasChildren: Boolean,
)

object Workspace {

    /** 树里不显示的内部文件 */
    private fun hidden(f: File): Boolean =
        f.name.startsWith(".") || f.name == ".codepad.json"

    fun children(dir: File): List<File> {
        val all = dir.listFiles() ?: return emptyList()
        return all.filter { !hidden(it) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /**
     * 把目录树拍平成可见列表：文件夹默认折叠，展开的目录（absolutePath 在 expanded 里）才递归。
     */
    fun visibleTree(root: File, expanded: Set<String>): List<FileNode> {
        val out = ArrayList<FileNode>()
        fun walk(dir: File, depth: Int) {
            children(dir).forEach { f ->
                val kids = if (f.isDirectory) children(f) else emptyList()
                out.add(FileNode(f, f.name, f.isDirectory, depth, kids.isNotEmpty()))
                if (f.isDirectory && expanded.contains(f.absolutePath)) {
                    walk(f, depth + 1)
                }
            }
        }
        walk(root, 0)
        return out
    }

    fun readText(file: File): String = try {
        file.readText()
    } catch (t: Throwable) {
        "// 无法读取: ${t.message}"
    }

    fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    fun uniqueChild(parent: File, name: String): File {
        var f = File(parent, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) {
            f = File(parent, "$base ($i)$ext")
            i++
        }
        return f
    }

    fun createFile(parent: File, name: String): File {
        val f = uniqueChild(parent, name.trim())
        f.parentFile?.mkdirs()
        f.createNewFile()
        return f
    }

    fun createDir(parent: File, name: String): File {
        val f = uniqueChild(parent, name.trim())
        f.mkdirs()
        return f
    }

    fun rename(file: File, newName: String): File? {
        val clean = newName.trim()
        if (clean.isEmpty() || clean == file.name) return null
        val target = File(file.parentFile, clean)
        if (target.exists()) return null
        return if (file.renameTo(target)) target else null
    }

    fun deleteRecursively(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
        file.delete()
    }

    fun relativePath(root: File, file: File): String {
        val r = root.absolutePath
        val p = file.absolutePath
        return if (p.startsWith(r)) p.removePrefix(r).trimStart('/') else p
    }

    /** 文件是否在某个目录下面（用于删除后清理标签） */
    fun isUnder(file: File, dir: File): Boolean =
        file.absolutePath == dir.absolutePath || file.absolutePath.startsWith(dir.absolutePath + "/")
}
