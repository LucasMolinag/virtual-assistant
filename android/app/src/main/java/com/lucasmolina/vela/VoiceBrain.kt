package com.lucasmolina.vela

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.view.ContextThemeWrapper
import org.json.JSONObject

class VoiceBrain(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var pageReady = false
    private var pending: String? = null
    var onReply: (text: String, followUp: Boolean) -> Unit = { _, _ -> }

    fun start() {
        if (webView != null) return
        val themed = ContextThemeWrapper(context.applicationContext, R.style.Theme_Vela)
        val view = WebView(themed)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = true
        @Suppress("DEPRECATION")
        view.settings.allowFileAccessFromFileURLs = true
        view.addJavascriptInterface(Bridge(), "VellaNative")
        view.onResume()
        view.resumeTimers()
        webView = view
        view.loadUrl("file:///android_asset/www/index.html?overlay=1")
    }

    fun deliver(text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        if (!pageReady || webView == null) {
            pending = cleaned
            return
        }
        pending = null
        webView?.evaluateJavascript("window.__vellaSubmit(${JSONObject.quote(cleaned)})", null)
    }

    fun shutdown() {
        val view = webView
        view?.destroy()
        webView = null
        pageReady = false
        pending = null
    }

    private fun ready() {
        handler.post {
            pageReady = true
            pending?.let {
                pending = null
                deliver(it)
            }
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun list(startIso: String, endIso: String): String = PhoneCalendar.list(context, startIso, endIso)

        @JavascriptInterface
        fun create(json: String): String = PhoneCalendar.create(context, json)

        @JavascriptInterface
        fun update(id: String, json: String): String = PhoneCalendar.update(context, id, json)

        @JavascriptInterface
        fun remove(id: String, startIso: String): String = PhoneCalendar.remove(context, id, startIso)

        @JavascriptInterface
        fun pageReady() = ready()

        @JavascriptInterface
        fun reply(text: String, followUp: Boolean) {
            handler.post { onReply(text, followUp) }
        }

        @JavascriptInterface
        fun saveUndo(json: String) = UndoStore.save(context, json)

        @JavascriptInterface
        fun loadUndo(): String = UndoStore.load(context)

        @JavascriptInterface
        fun lookupPlace(query: String): String = PlaceLookup.lookup(context, query)

        @JavascriptInterface
        fun ask(question: String): String = WebAnswers.answer(context, question)
    }
}
