package com.lucasmolina.vela

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
    private var greetThenListen = false
    private var session = false
    private var listening = false
    private var reopenMic = false
    private val reopenSoon = Runnable {
        if (session && !reopenMic) startCapture()
    }

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
        speaker = VellaSpeaker(this) {
            if (greetThenListen) {
                greetThenListen = false
                startCapture()
                return@VellaSpeaker
            }
            if (reopenMic && session) {
                reopenMic = false
                mainHandler.removeCallbacks(reopenSoon)
                startCapture()
            }
        }
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
        refreshUi = {
            mainHandler.post {
                if (pageReady) webView.evaluateJavascript("window.__vellaRefresh && window.__vellaRefresh()", null)
            }
        }
        VelaWidgetProvider.updateAll(this)
        maybeListen()
        if (pageReady) webView.evaluateJavascript("window.__vellaRefresh && window.__vellaRefresh()", null)
        WakeService.stop(this)
        if (WakeService.enabled(this)) openSession()
    }

    override fun onPause() {
        refreshUi = null
        greetThenListen = false
        reopenMic = false
        session = false
        listening = false
        mainHandler.removeCallbacks(reopenSoon)
        recognizer?.cancel()
        pageListening(false)
        MicGate.busy = false
        WakeService.stop(this)
        super.onPause()
    }

    override fun onDestroy() {
        refreshUi = null
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
        if (!::speaker.isInitialized || text.isBlank()) return
        mainHandler.post {
            mainHandler.removeCallbacks(reopenSoon)
            if (session) {
                recognizer?.cancel()
                listening = false
                pageListening(false)
                reopenMic = true
            }
        }
        speaker.speak(text)
    }

    fun turnDone() {
        mainHandler.post {
            mainHandler.removeCallbacks(reopenSoon)
            if (!session || reopenMic) return@post
            startCapture()
        }
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
        openSession()
    }

    private fun openSession() {
        if (!granted(Manifest.permission.RECORD_AUDIO) || !SpeechRecognizer.isRecognitionAvailable(this)) return
        session = true
        MicGate.busy = true
        if (listening) return
        startCapture()
    }

    private fun startCapture() {
        if (!session || listening || !granted(Manifest.permission.RECORD_AUDIO) || !SpeechRecognizer.isRecognitionAvailable(this)) return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        val current = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        current.setRecognitionListener(listener)
        listening = true
        try {
            current.startListening(recognizeIntent())
        } catch (_: Exception) {
            listening = false
            mainHandler.postDelayed({ if (session) startCapture() }, 200)
        }
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
                arrayListOf("one", "two", "three", "first", "second", "add", "move", "cancel", "undo", "tomorrow", "today"),
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

    private fun keepListening() {
        if (!session) return
        listening = false
        startCapture()
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
            listening = false
            val visible = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            if (!session || !visible) return
            if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                recognizer?.destroy()
                recognizer = null
                mainHandler.postDelayed({ if (session) startCapture() }, 200)
                return
            }
            startCapture()
        }
        override fun onResults(results: Bundle?) {
            pageListening(false)
            listening = false
            recognizer?.cancel()
            val text = TranscriptChoice.best(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
            if (text.isBlank()) {
                if (session) startCapture()
                return
            }
            submit(text)
            if (session) {
                mainHandler.removeCallbacks(reopenSoon)
                mainHandler.postDelayed(reopenSoon, 400)
            }
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
        fun remove(id: String, startIso: String): String = PhoneCalendar.remove(this@MainActivity, id, startIso)

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

        @JavascriptInterface
        fun turnDone() = this@MainActivity.turnDone()

        @JavascriptInterface
        fun saveUndo(json: String) = UndoStore.save(this@MainActivity, json)

        @JavascriptInterface
        fun loadUndo(): String = UndoStore.load(this@MainActivity)

        @JavascriptInterface
        fun lookupPlace(query: String): String = PlaceLookup.lookup(this@MainActivity, query)

        @JavascriptInterface
        fun ask(question: String): String = WebAnswers.answer(this@MainActivity, question)

        @JavascriptInterface
        fun setWake(enabled: Boolean) {
            mainHandler.post { enableWake(enabled) }
        }

        @JavascriptInterface
        fun setAlarm(iso: String, label: String): String = PhoneAlarms.schedule(this@MainActivity, iso, label, 0)

        @JavascriptInterface
        fun setAlarm(iso: String, label: String, relativeSeconds: Double): String = PhoneAlarms.schedule(this@MainActivity, iso, label, relativeSeconds.toInt())
    }

    private fun releaseMic() {
        session = false
        listening = false
        reopenMic = false
        MicGate.busy = false
        recognizer?.cancel()
        pageListening(false)
    }

    private fun enableWake(enabled: Boolean) {
        WakeService.setEnabled(this, enabled)
        if (!enabled) {
            releaseMic()
            return
        }
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            requestWake.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) openSession()
    }

    private fun prepareBackgroundListening() {
        val missingLocation = listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ).filter { !granted(it) }
        if (missingLocation.isNotEmpty()) requestLocation.launch(missingLocation.toTypedArray())
        val power = getSystemService(PowerManager::class.java)
        if (!power.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            try {
                startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    private val requestLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val requestWake = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted(Manifest.permission.RECORD_AUDIO)) enableWake(true)
    }

    companion object {
        const val EXTRA_LISTEN = "listen"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_ADD = "add"
        const val EXTRA_INTERRUPT = "interrupt"

        @Volatile
        var refreshUi: (() -> Unit)? = null
    }
}
