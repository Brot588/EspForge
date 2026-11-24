package com.codepad.ide

import android.app.Application
import java.io.File

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            com.codepad.ide.fs.Projects.bootstrap()
        } catch (_: Throwable) {
        }
    }

    companion object {
        lateinit var instance: App
            private set

        val ctx: App get() = instance

        val extensionsRoot: File get() = File(instance.filesDir, "extensions")

        /** 首次运行时把 tcc 自带的头文件释放到私有目录 */
        fun tccIncludeDir(): File {
            val dir = File(instance.filesDir, "tcc/include")
            if (File(dir, "stdio.h").exists()) return dir
            dir.mkdirs()
            try {
                val assets = instance.assets
                assets.list("tcc/include")?.forEach { name ->
                    assets.open("tcc/include/$name").use { input ->
                        File(dir, name).outputStream().use { out -> input.copyTo(out) }
                    }
                }
            } catch (_: Throwable) {
            }
            return dir
        }
    }
}
