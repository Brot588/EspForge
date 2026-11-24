package com.codepad.ide

import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 把当前工程目录映射到 https://appassets.androidplatform.net/preview/ 下，
 * 编辑器页面里的 iframe 就能直接加载本地网页（相对路径的 css/js 也能用）。
 * 未保存的编辑内容放在 overrides 里优先返回，做到「边改边看」不用先保存。
 */
object PreviewFiles {

    @Volatile
    var dir: File? = null

    private val overrides = ConcurrentHashMap<String, String>()

    fun setRoot(d: File?) {
        dir = d?.absoluteFile
    }

    fun putOverride(path: String, text: String) {
        overrides[path] = text
    }

    fun clearOverride(path: String) {
        overrides.remove(path)
    }

    fun clearAllOverrides() {
        overrides.clear()
    }

    private fun mimeOf(f: File): String = when (f.extension.lowercase()) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs" -> "application/javascript"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        else -> "application/octet-stream"
    }

    val handler: WebViewAssetLoader.PathHandler = object : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val root = dir ?: return null
            val decoded = try {
                java.net.URLDecoder.decode(path, "UTF-8")
            } catch (_: Throwable) {
                path
            }
            val f = File(root, decoded)

            overrides[f.absolutePath]?.let { text ->
                val resp = WebResourceResponse(
                    mimeOf(f),
                    "utf-8",
                    ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),
                )
                resp.setResponseHeaders(mapOf("Cache-Control" to "no-store"))
                return resp
            }
            if (!f.isFile) return null
            return try {
                if (!f.canonicalPath.startsWith(root.canonicalPath)) return null
                val resp = WebResourceResponse(mimeOf(f), "utf-8", FileInputStream(f))
                resp.setResponseHeaders(mapOf("Cache-Control" to "no-store"))
                resp
            } catch (_: Throwable) {
                null
            }
        }
    }
}
