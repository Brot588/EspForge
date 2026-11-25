package com.espforge.ide

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.webkit.WebViewAssetLoader

class MainActivity : ComponentActivity() {

    lateinit var controller: IdeController
        private set

    private val exportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> if (uri != null) controller.finishExport(uri) }

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) controller.finishImport(uri) }

    private val packLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) controller.importPack(uri) }

    /** 让用户从系统文件选择器挑 ESP-IDF 资源包（zip） */
    fun launchPackImport() {
        try {
            packLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        } catch (t: Throwable) {
            android.util.Log.w("ESPStudio", "pack launcher failed", t)
        }
    }

    fun launchExport(name: String) {
        try {
            exportLauncher.launch(name)
        } catch (t: Throwable) {
            android.util.Log.w("CodePad", "export launcher failed", t)
        }
    }

    fun launchImport() {
        try {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        } catch (t: Throwable) {
            android.util.Log.w("CodePad", "import launcher failed", t)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        controller = IdeController(this)
        setContent {
            CodePadTheme {
                IdeScreen(controller)
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!controller.handleBack()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onDestroy() {
        try {
            controller.session.close()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }
}

private val CodePadColors = darkColorScheme(
    primary = Color(0xFF4EA1FF),
    onPrimary = Color(0xFF06121F),
    primaryContainer = Color(0xFF16324D),
    secondary = Color(0xFF7CE38B),
    background = Color(0xFF12141A),
    onBackground = Color(0xFFD7DCE5),
    surface = Color(0xFF171A21),
    onSurface = Color(0xFFD7DCE5),
    surfaceVariant = Color(0xFF21252F),
    onSurfaceVariant = Color(0xFF9AA4B2),
    outline = Color(0xFF2E3440),
    error = Color(0xFFFF6B6B),
)

@Composable
fun CodePadTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CodePadColors, content = content)
}


/** WebView 嵌在 Compose 里时，点击不一定能把输入法接到 WebView 上，这里强制接管。 */
fun attachSoftKeyboard(ctx: Context, wv: WebView) {
    wv.isFocusable = true
    wv.isFocusableInTouchMode = true
    wv.setOnTouchListener { v, event ->
        if (event.action == android.view.MotionEvent.ACTION_UP) {
            v.requestFocus()
        }
        false
    }
}
@SuppressLint("SetJavaScriptEnabled")
fun createEditorWebView(ctx: Context, c: IdeController): WebView {
    val wv = WebView(ctx)
    android.util.Log.i("ESPStudioJS", "createEditorWebView")
    wv.setBackgroundColor(0xFF12141A.toInt())
    WebView.setWebContentsDebuggingEnabled(true)
    wv.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = true
        cacheMode = WebSettings.LOAD_NO_CACHE
        setSupportZoom(false)
        builtInZoomControls = false
        mediaPlaybackRequiresUserGesture = false
    }
    wv.isVerticalScrollBarEnabled = false
    wv.setOnFocusChangeListener { _, hasFocus -> c.editorFocused = hasFocus }
    attachSoftKeyboard(ctx, wv)
    wv.addJavascriptInterface(c.editorBridge, "Android")
    wv.webChromeClient = object : android.webkit.WebChromeClient() {
        override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
            android.util.Log.i("ESPStudioJS", "[editor] ${m?.message()} @${m?.sourceId()}:${m?.lineNumber()}")
            return true
        }
    }
    val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
        .build()
    wv.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            val url = request?.url ?: return null
            val res = loader.shouldInterceptRequest(url)
            android.util.Log.i("ESPStudioJS", "req ${url} -> ${if (res == null) "PASS" else "asset"}")
            return res
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: android.webkit.WebResourceError?,
        ) {
            android.util.Log.w("ESPStudioJS", "err ${request?.url} ${error?.description}")
        }

        // 页面加载完别自动抢焦点，否则首次启动输入法会自己弹出来挡住半个屏幕
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            try {
                view?.clearFocus()
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(view?.windowToken, 0)
            } catch (_: Throwable) {
            }
        }
    }
    wv.clearCache(true)
    wv.loadUrl("https://appassets.androidplatform.net/assets/editor/index.html")
    c.editorView = wv
    return wv
}

@SuppressLint("SetJavaScriptEnabled")
fun createTerminalWebView(ctx: Context, c: IdeController): WebView {
    val wv = WebView(ctx)
    wv.setBackgroundColor(0xFF0E1015.toInt())
    wv.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        cacheMode = WebSettings.LOAD_NO_CACHE
        setSupportZoom(false)
        builtInZoomControls = false
    }
    attachSoftKeyboard(ctx, wv)
    wv.addJavascriptInterface(
        com.espforge.ide.editor.TerminalBridge(
            onInput = { bytes -> c.terminalInput(bytes) },
            onReady = { c.onTerminalReady() },
            onResize = { cols, rows -> c.terminalResize(cols, rows) },
        ),
        "Android",
    )
    val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
        .build()
    wv.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            val url = request?.url ?: return null
            return loader.shouldInterceptRequest(url)
        }
    }
    wv.clearCache(true)
    wv.loadUrl("https://appassets.androidplatform.net/assets/terminal/index.html")
    c.terminalView = wv
    return wv
}

/** 串口监视器：一个独立的 xterm 实例，输入直接写进 USB 串口 */
@SuppressLint("SetJavaScriptEnabled")
fun createSerialWebView(ctx: Context, c: IdeController): WebView {
    val wv = WebView(ctx)
    wv.setBackgroundColor(0xFF0E1015.toInt())
    wv.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        cacheMode = WebSettings.LOAD_NO_CACHE
        setSupportZoom(false)
        builtInZoomControls = false
    }
    wv.addJavascriptInterface(
        com.espforge.ide.editor.TerminalBridge(
            onInput = { bytes -> c.serialInput(bytes) },
            onReady = {
                c.serialReady = true
                c.serialJs("CP.setFontSize(${c.terminalFontSize})")
            },
            onResize = { _, _ -> },
        ),
        "Android",
    )
    wv.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? = null
    }
    wv.clearCache(true)
    wv.loadUrl("https://appassets.androidplatform.net/assets/terminal/index.html")
    c.serialView = wv
    return wv
}
