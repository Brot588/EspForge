package com.codepad.ide.fs

import com.codepad.ide.App
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

    private const val META = ".codepad.json"

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
        val p = all.firstOrNull() ?: create("示例工程", Templates.MULTI)
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

    private fun prefs() = App.ctx.getSharedPreferences("codepad", 0)
}

object Templates {
    const val EMPTY = "empty"
    const val C = "c"
    const val PYTHON = "python"
    const val MULTI = "multi"
    const val RUST = "rust"
    const val CMAKE = "cmake"
    const val CPP = "cpp"
    const val WEB = "web"

    private val readme = """
        # 新工程

        这个目录就是工程根目录，所有文件都保存在这里。

        - 左上角「工程」下拉可以切换 / 新建工程
        - 资源管理器里长按文件或文件夹，可以新建、重命名、删除
        - 右上角 ▶ 运行当前文件
    """.trimIndent()

    private val cMain = """
        #include <stdio.h>

        int main(void) {
            printf("Hello from C!\n");
            for (int i = 1; i <= 5; i++) {
                printf("%d squared = %d\n", i, i * i);
            }
            return 0;
        }
    """.trimIndent()

    private val pyMain = """
        import sys, platform

        def fib(n):
            a, b = 0, 1
            for _ in range(n):
                a, b = b, a + b
            return a

        print("Hello from Python!")
        print("version:", sys.version.split()[0])
        print("machine:", platform.machine())
        print("fib(20) =", fib(20))
    """.trimIndent()

    private val jsMain = """
        const nums = [1, 2, 3, 4, 5];
        const squares = nums.map(n => n * n);
        console.log("Hello from JavaScript!");
        console.log("squares:", JSON.stringify(squares));
        console.log("sum:", squares.reduce((a, b) => a + b, 0));
    """.trimIndent()

    private val rsMain = """
        // Rust 语法高亮与文件管理已支持。
        // 编译需要你自己提供的 rustc（见 README）。
        fn main() {
            println!("Hello from Rust!");
        }
    """.trimIndent()

    /** 工程名 → 合法的 crate 名 */
    private fun crateName(raw: String): String {
        var s = raw.lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_')
        while (s.contains("__")) s = s.replace("__", "_")
        if (s.isEmpty()) s = "app"
        if (!s[0].isLetter()) s = "app_$s"
        return s
    }

    fun apply(dir: File, template: String) {
        dir.mkdirs()
        File(dir, "README.md").writeText(readme + "\n")
        when (template) {
            C -> File(dir, "main.c").writeText(cMain + "\n")
            WEB -> {
                File(dir, "style.css").writeText(
                    "* { box-sizing: border-box; }\n" +
                        "body {\n" +
                        "  margin: 0;\n" +
                        "  font-family: system-ui, sans-serif;\n" +
                        "  background: #0f1117;\n" +
                        "  color: #d7dce5;\n" +
                        "  display: flex;\n" +
                        "  align-items: center;\n" +
                        "  justify-content: center;\n" +
                        "  height: 100vh;\n" +
                        "}\n" +
                        ".card {\n" +
                        "  padding: 24px 32px;\n" +
                        "  border-radius: 14px;\n" +
                        "  background: #171a21;\n" +
                        "  border: 1px solid #2e3440;\n" +
                        "  text-align: center;\n" +
                        "}\n" +
                        "h1 { margin: 0 0 8px; font-size: 22px; }\n" +
                        "p { margin: 0 0 16px; color: #9aa4b2; }\n" +
                        "button {\n" +
                        "  padding: 8px 18px;\n" +
                        "  border-radius: 8px;\n" +
                        "  border: 0;\n" +
                        "  background: #4ea1ff;\n" +
                        "  color: #06121f;\n" +
                        "  font-weight: 600;\n" +
                        "}\n"
                )
                File(dir, "script.js").writeText(
                    "const btn = document.getElementById('btn');\n" +
                        "const out = document.getElementById('out');\n" +
                        "let count = 0;\n" +
                        "btn.addEventListener('click', () => {\n" +
                        "  count++;\n" +
                        "  out.textContent = `点了 \${count} 次`;\n" +
                        "});\n"
                )
                File(dir, "index.html").writeText(
                    "<!DOCTYPE html>\n" +
                        "<html lang=\"zh-CN\">\n" +
                        "<head>\n" +
                        "  <meta charset=\"utf-8\">\n" +
                        "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
                        "  <title>CodePad Web</title>\n" +
                        "  <link rel=\"stylesheet\" href=\"style.css\">\n" +
                        "</head>\n" +
                        "<body>\n" +
                        "  <div class=\"card\">\n" +
                        "    <h1>Hello Web</h1>\n" +
                        "    <p>编辑 index.html / style.css / script.js，点 ▶ 预览</p>\n" +
                        "    <button id=\"btn\">点我</button>\n" +
                        "    <p id=\"out\"></p>\n" +
                        "  </div>\n" +
                        "  <script src=\"script.js\"></script>\n" +
                        "</body>\n" +
                        "</html>\n"
                )
            }
            CPP -> {
                val crate = crateName(dir.name)
                File(dir, "src").mkdirs()
                File(dir, "include").mkdirs()
                File(dir, "CMakeLists.txt").writeText(
                    "cmake_minimum_required(VERSION 3.20)\n" +
                        "project($crate CXX)\n\n" +
                        "set(CMAKE_CXX_STANDARD 17)\n" +
                        "include_directories(include)\n\n" +
                        "add_executable($crate main.cpp src/util.cpp)\n"
                )
                File(dir, "include/util.h").writeText(
                    "#ifndef UTIL_H\n#define UTIL_H\n\nint square(int x);\n\n#endif\n"
                )
                File(dir, "src/util.cpp").writeText(
                    "#include \"util.h\"\n\nint square(int x) {\n    return x * x;\n}\n"
                )
                File(dir, "main.cpp").writeText(
                    "#include <iostream>\n#include \"util.h\"\n\nint main() {\n" +
                        "    for (int i = 0; i <= 5; i++) {\n" +
                        "        std::cout << i << \" squared = \" << square(i) << std::endl;\n" +
                        "    }\n    return 0;\n}\n"
                )
                File(dir, ".gitignore").writeText("/build\n")
            }
            CMAKE -> {
                val crate = crateName(dir.name)
                File(dir, "src").mkdirs()
                File(dir, "include").mkdirs()
                File(dir, "CMakeLists.txt").writeText(
                    "cmake_minimum_required(VERSION 3.20)\n" +
                        "project($crate C)\n\n" +
                        "set(CMAKE_C_STANDARD 11)\n" +
                        "include_directories(include)\n\n" +
                        "add_executable($crate main.c src/util.c)\n"
                )
                File(dir, "include/util.h").writeText(
                    "#ifndef UTIL_H\n#define UTIL_H\n\nint square(int x);\n\n#endif\n"
                )
                File(dir, "src/util.c").writeText(
                    "#include \"util.h\"\n\nint square(int x) {\n    return x * x;\n}\n"
                )
                File(dir, "main.c").writeText(
                    "#include <stdio.h>\n#include \"util.h\"\n\nint main(void) {\n" +
                        "    for (int i = 0; i <= 5; i++) {\n" +
                        "        printf(\"%d squared = %d\\n\", i, square(i));\n" +
                        "    }\n    return 0;\n}\n"
                )
                File(dir, ".gitignore").writeText("/build\n")
            }
            RUST -> {
                // 直接生成标准 cargo 工程结构，不需要在新建时调用 cargo
                val crate = crateName(dir.name)
                File(dir, "src").mkdirs()
                File(dir, "Cargo.toml").writeText(
                    "[package]\n" +
                        "name = \"$crate\"\n" +
                        "version = \"0.1.0\"\n" +
                        "edition = \"2021\"\n\n" +
                        "[dependencies]\n"
                )
                File(dir, "src/main.rs").writeText(
                    "struct Point {\n" +
                        "    x: i32,\n" +
                        "    y: i32,\n" +
                        "}\n\n" +
                        "fn main() {\n" +
                        "    let p = Point { x: 3, y: 4 };\n" +
                        "    let name = String::from(\"hello rust\");\n" +
                        "    let nums = vec![10u32, 20, 30];\n" +
                        "    let total: u32 = nums.iter().sum();\n" +
                        "    println!(\"{} {} {} {:?} {}\", p.x, p.y, name, nums, total);\n" +
                        "}\n"
                )
                File(dir, ".gitignore").writeText("/target\n")
            }
            PYTHON -> File(dir, "main.py").writeText(pyMain + "\n")
            MULTI -> {
                File(dir, "main.c").writeText(cMain + "\n")
                File(dir, "main.py").writeText(pyMain + "\n")
                File(dir, "main.js").writeText(jsMain + "\n")
                File(dir, "lib.rs").writeText(rsMain + "\n")
                File(dir, "src").mkdirs()
                File(dir, "src/util.c").writeText(
                    """
                    #include <stdio.h>

                    int add(int a, int b) {
                        return a + b;
                    }
                    """.trimIndent() + "\n"
                )
            }
        }
    }
}
