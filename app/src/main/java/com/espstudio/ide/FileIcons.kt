package com.espstudio.ide

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caverock.androidsvg.SVG
import java.util.concurrent.ConcurrentHashMap

/**
 * 直接渲染 Material Icon Theme 的 SVG（资产目录 icons/），按尺寸缓存成 Bitmap。
 */
object SvgIcons {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    fun get(assets: AssetManager, name: String, px: Int): ImageBitmap? {
        val key = "$name#$px"
        cache[key]?.let { return it }
        return try {
            assets.open("icons/$name.svg").use { input ->
                val svg = SVG.getFromInputStream(input)
                val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                svg.setDocumentWidth(px.toFloat())
                svg.setDocumentHeight(px.toFloat())
                svg.renderToCanvas(canvas)
                val image = bmp.asImageBitmap()
                cache[key] = image
                image
            }
        } catch (_: Throwable) {
            null
        }
    }
}

/** 扩展名 → Material Icon Theme 图标名 */
fun iconNameFor(fileName: String, isDir: Boolean): String {
    if (isDir) return "folder-base"
    val n = fileName.lowercase()
    when {
        n == ".gitignore" || n == ".gitattributes" || n == ".gitmodules" -> return "git"
        n == "makefile" || n == "gnumakefile" -> return "makefile"
        n == "dockerfile" || n.startsWith("docker-compose") -> return "docker"
        n == "license" || n.startsWith("license.") -> return "license"
        n.startsWith("readme") -> return "readme"
        n.endsWith(".lock") -> return "lock"
        n.startsWith(".env") -> return "lock"
    }
    return when (n.substringAfterLast('.', "")) {
        "c", "h" -> "c"
        "cpp", "cc", "cxx", "hpp", "hxx", "hh" -> "cpp"
        "py", "pyw", "pyi" -> "python"
        "js", "mjs", "cjs", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        "rs" -> "rust"
        "go" -> "go"
        "java" -> "java"
        "kt", "kts" -> "kotlin"
        "json", "json5" -> "json"
        "md", "markdown", "mdx" -> "markdown"
        "html", "htm", "vue", "svelte" -> "html"
        "css", "scss", "sass", "less" -> "css"
        "sh", "bash", "zsh", "fish", "bat", "ps1" -> "console"
        "yml", "yaml" -> "yaml"
        "xml", "plist" -> "xml"
        "sql", "db", "sqlite" -> "database"
        "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico" -> "image"
        "pdf" -> "pdf"
        "zip", "gz", "tar", "7z", "rar", "xz", "bz2" -> "zip"
        "apk", "aab" -> "android"
        "txt", "log", "ini", "conf", "cfg", "properties" -> "document"
        else -> "document"
    }
}

private data class Badge(val label: String, val color: Color, val onColor: Color = Color.White)

private val badgeFallbacks = mapOf(
    "python" to Badge("PY", Color(0xFF3572A5)),
    "javascript" to Badge("JS", Color(0xFFF7DF1E), Color(0xFF15171C)),
    "rust" to Badge("RS", Color(0xFFDEA584), Color(0xFF15171C)),
    "markdown" to Badge("MD", Color(0xFF519ABA)),
    "json" to Badge("{}", Color(0xFFF5C25B), Color(0xFF15171C)),
)

@Composable
fun FileTypeIcon(
    fileName: String,
    isDir: Boolean,
    isOpen: Boolean,
    size: Dp = 16.dp,
) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(8)
    val icon = remember(fileName, isDir) { iconNameFor(fileName, isDir) }
    val bitmap = remember(icon, px) { SvgIcons.get(ctx.assets, icon, px) }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(size),
        )
        return
    }

    val badge = badgeFallbacks[icon] ?: Badge("·", Color(0xFF546E7A))
    Box(
        Modifier
            .size(size)
            .background(badge.color, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = badge.label,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            color = badge.onColor,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}
