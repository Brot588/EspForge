package com.espstudio.ide.fs

import com.espstudio.ide.App
import org.json.JSONObject
import java.io.File

/** 一个工程 = files/projects 下的一个文件夹 */
data class Project(
    val id: String,
    val name: String,
    val dir: File,
    val createdAt: Long,
)

object Projects {

    private const val META = ".espstudio.json"

    val root: File
        get() = File(App.ctx.filesDir, "projects").also { it.mkdirs() }

    fun list(): List<Project> {
        val dirs = root.listFiles() ?: return emptyList()
        return dirs.filter { it.isDirectory && !it.name.startsWith(".") }
            .map { read(it) }
            .sortedBy { it.createdAt }
    }

    fun read(dir: File): Project {
        var name = dir.name
        var created = dir.lastModified()
        try {
            val m = File(dir, META)
            if (m.isFile) {
                val o = JSONObject(m.readText())
                name = o.optString("name", name)
                created = o.optLong("createdAt", created)
            }
        } catch (_: Throwable) {
        }
        return Project(dir.name, name, dir, created)
    }

    /** 工程名 → 可直接做文件夹名的 slug */
    fun slug(name: String): String {
        var s = name.trim()
        s = s.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "-")
        s = s.replace(Regex("\\s+"), "-")
        s = s.trim('-', '.', ' ')
        if (s.isEmpty()) s = "project"
        if (s.length > 40) s = s.take(40).trimEnd('-', '.')
        return s
    }

    private fun uniqueDir(base: String): File {
        var dir = File(root, base)
        var i = 2
        while (dir.exists()) {
            dir = File(root, "$base-$i")
            i++
        }
        return dir
    }

    fun create(name: String, template: String): Project {
        val dir = uniqueDir(slug(name.ifBlank { "未命名工程" }))
        dir.mkdirs()
        val meta = JSONObject()
            .put("name", name)
            .put("createdAt", System.currentTimeMillis())
            .put("template", template)
        File(dir, META).writeText(meta.toString(2))
        Templates.apply(dir, template)
        return read(dir)
    }

    /** 改显示名，同时把工程文件夹也改成同名 */
    fun renameProject(project: Project, newName: String): Project {
        val clean = newName.trim()
        if (clean.isEmpty()) return project
        val m = File(project.dir, META)
        val o = try {
            if (m.isFile) JSONObject(m.readText()) else JSONObject()
        } catch (_: Throwable) {
            JSONObject()
        }
        o.put("name", clean)
        m.writeText(o.toString(2))

        val wanted = slug(clean)
        if (wanted == project.dir.name) return read(project.dir)
        val target = uniqueDir(wanted)
        return if (project.dir.renameTo(target)) {
            if (currentId() == project.id) setCurrent(target.name)
            read(target)
        } else {
            read(project.dir)
        }
    }

    fun delete(project: Project) {
        Workspace.deleteRecursively(project.dir)
    }

    fun currentId(): String? = prefs().getString("current_project", null)

    fun setCurrent(id: String?) {
        prefs().edit().putString("current_project", id).apply()
    }

    /** 首次启动：把旧版的 files/workspace 迁移成工程，并保证至少有一个工程 */
    fun bootstrap(): Project {
        migrateLegacy()
        normalizeNames()
        val all = list()
        val saved = currentId()?.let { id -> all.firstOrNull { it.id == id } }
        if (saved != null) return saved
        val p = all.firstOrNull() ?: create("blink", Templates.BLINK)
        setCurrent(p.id)
        return p
    }

    /** 把早期的时间戳 id 目录改成可读的工程名 */
    private fun normalizeNames() {
        list().forEach { p ->
            val wanted = slug(p.name)
            if (p.dir.name == wanted) return@forEach
            val target = uniqueDir(wanted)
            if (p.dir.renameTo(target)) {
                if (currentId() == p.id) setCurrent(target.name)
            }
        }
    }

    private fun migrateLegacy() {
        val legacy = File(App.ctx.filesDir, "workspace")
        if (!legacy.isDirectory) return
        if (root.listFiles()?.any { it.isDirectory && !it.name.startsWith(".") } == true) return
        val target = File(root, "legacy")
        if (target.exists()) return
        if (!legacy.renameTo(target)) {
            copyRecursively(legacy, target)
            Workspace.deleteRecursively(legacy)
        }
        val meta = JSONObject().put("name", "旧工作区").put("createdAt", System.currentTimeMillis())
        File(target, META).writeText(meta.toString(2))
    }

    private fun copyRecursively(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyRecursively(it, File(dst, it.name)) }
        } else {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
        }
    }

    private fun prefs() = App.ctx.getSharedPreferences("espstudio", 0)
}
