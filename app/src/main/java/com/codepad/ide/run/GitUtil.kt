package com.codepad.ide.run

import com.codepad.ide.App
import java.io.File

/** 内置 git 的封装：跑命令、读状态，不依赖系统 git */
object GitUtil {

    fun isReady(): Boolean = File(CToolchain.root, "bin/git").isFile

    fun hasRepo(dir: File): Boolean = File(dir, ".git").isDirectory

    fun run(dir: File, args: List<String>): String {
        val cmd = ArrayList<String>()
        cmd.add(File(CToolchain.root, "bin/git").absolutePath)
        cmd.add("-c")
        cmd.add("gc.auto=0")
        cmd.add("-c")
        cmd.add("maintenance.auto=false")
        cmd.addAll(args)
        val pb = ProcessBuilder(cmd)
        pb.directory(dir)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["LD_LIBRARY_PATH"] = CToolchain.libPath()
        env["HOME"] = App.ctx.filesDir.absolutePath
        env["GIT_PAGER"] = "cat"
        env["GIT_TERMINAL_PROMPT"] = "0"
        env["GIT_TEMPLATE_DIR"] = File(CToolchain.root, "share/git-core/templates").absolutePath
        env["GIT_AUTHOR_NAME"] = "CodePad"
        env["GIT_AUTHOR_EMAIL"] = "codepad@local"
        env["GIT_COMMITTER_NAME"] = "CodePad"
        env["GIT_COMMITTER_EMAIL"] = "codepad@local"
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    fun status(dir: File): List<String> =
        run(dir, listOf("status", "--porcelain")).lines().filter { it.isNotBlank() }

    fun diff(dir: File): String {
        val unstaged = run(dir, listOf("diff", "--no-color"))
        val staged = run(dir, listOf("diff", "--cached", "--no-color"))
        return (unstaged + staged).trim()
    }

    fun branch(dir: File): String {
        val sym = run(dir, listOf("symbolic-ref", "--short", "HEAD")).trim()
        if (sym.isNotBlank() && !sym.contains("fatal")) return sym
        val rev = run(dir, listOf("rev-parse", "--abbrev-ref", "HEAD")).trim()
        return rev.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.contains("fatal") && !it.contains("unknown revision") && it != "HEAD" }
            ?: "master"
    }

    fun diffFor(dir: File, path: String): String {
        val a = run(dir, listOf("diff", "--no-color", "--", path))
        val b = run(dir, listOf("diff", "--cached", "--no-color", "--", path))
        val out = (a + b).trim()
        if (out.isNotBlank()) return out
        // 未跟踪文件用 --no-index 跟 /dev/null 比
        val untracked = run(dir, listOf("diff", "--no-index", "--no-color", "--", "/dev/null", path)).trim()
        return untracked
    }

    fun add(dir: File, path: String) = run(dir, listOf("add", "--", path))

    fun addAll(dir: File) = run(dir, listOf("add", "-A"))

    fun unstage(dir: File, path: String) = run(dir, listOf("restore", "--staged", "--", path))

    fun discard(dir: File, path: String) = run(dir, listOf("checkout", "--", path))

    fun logLines(dir: File, count: Int = 20): List<String> =
        run(dir, listOf("log", "-$count", "--pretty=format:%h %s"))
            .lines().map { it.trim() }.filter { it.isNotBlank() }

    fun lastCommit(dir: File): String {
        val out = run(dir, listOf("log", "-1", "--pretty=format:%h %s")).trim()
        return if (out.contains("fatal") || out.contains("does not have any commits")) "" else out
    }
}
