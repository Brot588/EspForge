package com.espstudio.ide

import android.app.Application
import java.io.File

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            com.espstudio.ide.fs.Projects.bootstrap()
        } catch (_: Throwable) {
        }
        try {
            com.espstudio.ide.run.Toolchain.ensureDirs()
        } catch (_: Throwable) {
        }
    }

    companion object {
        lateinit var instance: App
            private set

        val ctx: App get() = instance

        val extensionsRoot: File get() = File(instance.filesDir, "extensions")
    }
}
