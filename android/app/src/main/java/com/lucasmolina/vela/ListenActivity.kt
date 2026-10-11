package com.lucasmolina.vela

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject

class ListenActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var webView: WebView
    private lateinit var speaker: VellaSpeaker
    private var recognizer: SpeechRecognizer? = null
    private var pageReady = false
    private var pendingCommand: String? = null
    private var awaitingReply = false
    private var followUp = false
    private var presetCommand: String? = null
    private var greeted = false
    private var greetThenListen = false

    private val giveUp = Runnable {
        if (!isFinishing && !awaitingReply) finish()
    }

    private val commandTimedOut = Runnable {
        if (!isFinishing) fail("I couldn't update the calendar. Try again.")
    }

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted(Manifest.permission.RECORD_AUDIO)) begin()
        else fail("Allow the microphone, then tap Talk to Vella again.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_listen)
        statusView = findViewById(R.id.listen_status)
        webView = findViewById(R.id.listen_web)
        webView.setBackgroundColor(0x00000000)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        @Suppress("DEPRECATION")
        webView.settings.allowFileAccessFromFileURLs = true
        webView.addJavascriptInterface(Bridge(), "VellaNative")
        webView.loadUrl("file:///android_asset/www/index.html?overlay=1")
        speaker = VellaSpeaker(this) {
            if (isFinishing) return@VellaSpeaker
            if (greetThenListen) {
                greetThenListen = false
                listen()
                return@VellaSpeaker
            }
            if (followUp) {
                followUp = false
                listen()
            } else {
                finish()
            }
        }
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        holdMic()
        presetCommand = intent.getStringExtra(MainActivity.EXTRA_COMMAND)?.trim()?.takeIf { it.isNotEmpty() }
        val missing = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        ).filter { !granted(it) }
        if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
        else begin()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!intent.getBooleanExtra(MainActivity.EXTRA_INTERRUPT, false) && intent.getStringExtra(MainActivity.EXTRA_COMMAND).isNullOrBlank()) {
            return
        }
        speaker.stop()
        recognizer?.cancel()
        handler.removeCallbacks(commandTimedOut)
        val text = intent.getStringExtra(MainActivity.EXTRA_COMMAND)?.trim().orEmpty()
        if (text.isNotEmpty()) {
            holdMic()
            awaitingReply = true
            deliver(text)
        } else {
            awaitingReply = false
            followUp = false
            greeted = false
            listen()
        }
    }

    override fun onDestroy() {
        releaseForWake()
        handler.removeCallbacks(giveUp)
        handler.removeCallbacks(commandTimedOut)
        recognizer?.destroy()
        recognizer = null
        if (::speaker.isInitialized) speaker.shutdown()
        super.onDestroy()
    }

    private fun begin() {
        val command = presetCommand
        presetCommand = null
        if (!command.isNullOrEmpty()) {
            awaitingReply = true
            deliver(command)
        } else {
            listen()
        }
    }

    private fun holdMic() {
        MicGate.busy = true
        WakeService.pause()
    }

    private fun releaseForWake() {
        MicGate.busy = false
        WakeService.resume()
    }

    private fun listen() {
        if (awaitingReply || isFinishing) return
        holdMic()
        if (!greeted) {
            greeted = true
            greetThenListen = true
            val phrase = VellaPhrases.greeting()
            statusView.text = phrase
            speaker.speak(phrase)
            return
        }
        setFinishOnTouchOutside(true)
        speaker.stop()
        armGiveUp()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            fail("Speech recognition is not available on this phone.")
            return
        }
        statusView.setText(R.string.opening_mic)
        val current = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        current.setRecognitionListener(recognition)
        current.startListening(recognizeIntent())
    }

    private fun armGiveUp() {
        handler.removeCallbacks(giveUp)
        handler.postDelayed(giveUp, 45000)
    }

    private fun recognizeIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell Vella what to change")
            putStringArrayListExtra(
                "android.speech.extra.BIASING_STRINGS",
                arrayListOf("one", "two", "three", "first", "second", "add", "move", "cancel", "both", "undo", "tomorrow", "today"),
            )
        }
    }

    private fun heard(text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) {
            missed()
            return
        }
        awaitingReply = true
        setFinishOnTouchOutside(false)
        handler.removeCallbacks(giveUp)
        handler.removeCallbacks(commandTimedOut)
        handler.postDelayed(commandTimedOut, 20000)
        statusView.text = cleaned
        recognizer?.stopListening()
        deliver(cleaned)
    }

    private fun deliver(text: String) {
        if (!pageReady) {
            pendingCommand = text
            return
        }
        pendingCommand = null
        webView.evaluateJavascript("window.__vellaSubmit(${JSONObject.quote(text)})", null)
    }

    private fun onPageReady() {
        handler.post {
            pageReady = true
            pendingCommand?.let { deliver(it) }
        }
    }

    private fun onReply(text: String, next: Boolean) {
        handler.post {
            if (isFinishing) return@post
            handler.removeCallbacks(commandTimedOut)
            awaitingReply = false
            followUp = next
            if (text.isNotBlank()) statusView.text = text
            if (text.isBlank()) {
                if (next) listen() else finish()
                return@post
            }
            releaseForWake()
            speaker.speak(text)
        }
    }

    private fun missed() {
        awaitingReply = false
        followUp = false
        greeted = true
        greetThenListen = true
        statusView.text = "I didn't catch that."
        holdMic()
        speaker.speak("I didn't catch that.")
    }

    private fun fail(message: String) {
        handler.removeCallbacks(giveUp)
        handler.removeCallbacks(commandTimedOut)
        awaitingReply = false
        followUp = false
        statusView.text = message
        releaseForWake()
        speaker.speak(message)
    }

    private fun granted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private val recognition = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!awaitingReply) statusView.setText(R.string.listening)
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            if (awaitingReply || error == SpeechRecognizer.ERROR_CLIENT) return
            missed()
        }
        override fun onResults(results: Bundle?) {
            if (awaitingReply) return
            val lines = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            heard(bestTranscript(lines))
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank() && !awaitingReply) statusView.text = text
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun bestTranscript(lines: List<String>): String = TranscriptChoice.best(lines)

    private inner class Bridge {
        @JavascriptInterface
        fun list(startIso: String, endIso: String): String = PhoneCalendar.list(this@ListenActivity, startIso, endIso)

        @JavascriptInterface
        fun create(json: String): String = PhoneCalendar.create(this@ListenActivity, json)

        @JavascriptInterface
        fun update(id: String, json: String): String = PhoneCalendar.update(this@ListenActivity, id, json)

        @JavascriptInterface
        fun remove(id: String, startIso: String): String = PhoneCalendar.remove(this@ListenActivity, id, startIso)

        @JavascriptInterface
        fun pageReady() = onPageReady()

        @JavascriptInterface
        fun reply(text: String, followUp: Boolean) = onReply(text, followUp)

        @JavascriptInterface
        fun saveUndo(json: String) = UndoStore.save(this@ListenActivity, json)

        @JavascriptInterface
        fun loadUndo(): String = UndoStore.load(this@ListenActivity)

        @JavascriptInterface
        fun lookupPlace(query: String): String = PlaceLookup.lookup(this@ListenActivity, query)

        @JavascriptInterface
        fun ask(question: String): String = WebAnswers.answer(this@ListenActivity, question)
    }
}
