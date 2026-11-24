package com.codepad.ide

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/** 全屏 HTML 预览：独立 Activity，避免和 Compose 布局/输入法纠缠 */
class HtmlPreviewActivity : ComponentActivity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path")
        if (path == null) {
            finish()
            return
        }
        val root = FrameLayout(this)
        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        wv.setBackgroundColor(0xFFFFFFFF.toInt())
        wv.loadUrl("file://" + path)
        root.addView(wv, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        webView = wv
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (wv.canGoBack()) wv.goBack() else finish()
            }
        })
    }

    override fun onDestroy() {
        try {
            webView?.destroy()
        } catch (_: Throwable) {
        }
        webView = null
        super.onDestroy()
    }
}
