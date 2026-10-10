package com.lucasmolina.vela

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pageReady = false
    private var pageLoaded = false
    private var pendingCommand: String? = null
    private var pendingAdd = false
    private var recognizer: SpeechRecognizer? = null
    private lateinit var speaker: VellaSpeaker

    private val requestCalendar = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        loadPage()
    }

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen()
        else pageStatus("Allow the microphone, then tap Tell Vella again.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.web)
        webView.setBackgroundColor(0xFF101214.toInt())
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        @Suppress("DEPRECATION")
        webView.settings.allowFileAccessFromFileURLs = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.addJavascriptInterface(VellaBridge(), "VellaNative")
        speaker = VellaSpeaker(this)
        takeCommand(intent)
        takeAdd(intent)
        val missing = calendarPermissions().filter { !granted(it) }
        if (missing.isNotEmpty()) requestCalendar.launch(missing.toTypedArray())
        else loadPage()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeCommand(intent)
        takeAdd(intent)
        maybeListen()
    }

    override fun onResume() {
        super.onResume()
        VelaWidgetProvider.updateAll(this)
        maybeListen()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        if (::speaker.isInitialized) speaker.shutdown()
        super.onDestroy()
    }

    fun onPageReady() {
        mainHandler.post {
            pageReady = true
            pendingCommand?.let {
                pendingCommand = null
                deliver(it)
            }
            if (pendingAdd) {
                pendingAdd = false
                deliverAdd()
            }
        }
    }

    fun speak(text: String) {
        if (::speaker.isInitialized) speaker.speak(text)
    }

    fun stopSpeaking() {
        if (::speaker.isInitialized) speaker.stop()
    }

    fun listen() {
        stopSpeaking()
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            pageStatus("Speech recognition is not available on this phone.")
            return
        }
        val current = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        current.setRecognitionListener(listener)
        current.startListening(recognizeIntent())
    }

    private fun loadPage() {
        if (pageLoaded) return
        pageLoaded = true
        webView.loadUrl("file:///android_asset/www/index.html")
    }

    private fun wantsListen(): Boolean = intent.getBooleanExtra(EXTRA_LISTEN, false)

    private fun takeCommand(source: Intent?) {
        val text = source?.getStringExtra(EXTRA_COMMAND)?.trim().orEmpty()
        if (text.isEmpty()) return
        source?.removeExtra(EXTRA_COMMAND)
        submit(text)
    }

    private fun takeAdd(source: Intent?) {
        if (source?.getBooleanExtra(EXTRA_ADD, false) != true) return
        source.removeExtra(EXTRA_ADD)
        if (!pageReady) {
            pendingAdd = true
            return
        }
        deliverAdd()
    }

    private fun deliverAdd() {
        webView.evaluateJavascript("window.__vellaAdd && window.__vellaAdd()", null)
    }

    private fun maybeListen() {
        if (!pageLoaded || !wantsListen()) return
        intent.removeExtra(EXTRA_LISTEN)
        listen()
    }

    private fun calendarPermissions() = listOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    private fun granted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun recognizeIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell Vella what to change")
            putStringArrayListExtra(
                "android.speech.extra.BIASING_STRINGS",
                arrayListOf("add", "move", "cancel", "undo", "tomorrow", "today"),
            )
        }
    }

    private fun submit(text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) {
            pageStatus("I didn't hear anything. Tap the mic and try again.")
            return
        }
        if (!pageReady) {
            pendingCommand = cleaned
            return
        }
        deliver(cleaned)
    }

    private fun deliver(text: String) {
        webView.evaluateJavascript("window.__vellaSubmit(${JSONObject.quote(text)})", null)
    }

    private fun pageListening(active: Boolean) {
        if (!pageReady) return
        webView.evaluateJavascript("window.__vellaListening && window.__vellaListening($active)", null)
    }

    private fun pageStatus(text: String) {
        if (!pageReady) return
        webView.evaluateJavascript("window.__vellaStatus && window.__vellaStatus(${JSONObject.quote(text)})", null)
    }

    private fun pageDraft(text: String) {
        if (!pageReady || text.isBlank()) return
        webView.evaluateJavascript("window.__vellaDraft && window.__vellaDraft(${JSONObject.quote(text)})", null)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            pageListening(true)
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            pageListening(false)
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                pageStatus("I didn't hear anything. Tap the mic and try again.")
            } else if (error != SpeechRecognizer.ERROR_CLIENT) {
                pageStatus("I didn't catch that. Tap the mic and try again.")
            }
        }
        override fun onResults(results: Bundle?) {
            pageListening(false)
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            submit(text)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            pageDraft(text)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private inner class VellaBridge {
        @JavascriptInterface
        fun list(startIso: String, endIso: String): String = PhoneCalendar.list(this@MainActivity, startIso, endIso)

        @JavascriptInterface
        fun create(json: String): String = PhoneCalendar.create(this@MainActivity, json)

        @JavascriptInterface
        fun update(id: String, json: String): String = PhoneCalendar.update(this@MainActivity, id, json)

        @JavascriptInterface
        fun remove(id: String): String = PhoneCalendar.remove(this@MainActivity, id)

        @JavascriptInterface
        fun pageReady() = onPageReady()

        @JavascriptInterface
        fun speak(text: String) = this@MainActivity.speak(text)

        @JavascriptInterface
        fun silence() = stopSpeaking()

        @JavascriptInterface
        fun listen() {
            mainHandler.post { this@MainActivity.listen() }
        }
    }

    companion object {
        const val EXTRA_LISTEN = "listen"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_ADD = "add"
    }
}
