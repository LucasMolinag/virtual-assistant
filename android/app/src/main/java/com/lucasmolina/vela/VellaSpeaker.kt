package com.lucasmolina.vela

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

class VellaSpeaker(
    context: Context,
    private val onFinishedUtterance: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val packageManager = context.packageManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var usingGoogleTts = false
    private var chosenVoice: Voice? = null
    private var activeUtterance = ""
    private var utteranceSerial = 0
    private val speechQueue = ArrayDeque<String>()

    init {
        val listener = TextToSpeech.OnInitListener { status -> onTtsReady(status) }
        if (googleTtsInstalled()) {
            usingGoogleTts = true
            tts = TextToSpeech(appContext, listener, GOOGLE_TTS)
        } else {
            tts = TextToSpeech(appContext, listener)
        }
        register(this)
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        mainHandler.post {
            speechQueue.add(text)
            flushSpeech()
        }
    }

    fun stop() {
        val task = Runnable {
            speechQueue.clear()
            activeUtterance = ""
            tts?.stop()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run()
        else mainHandler.post(task)
    }

    fun shutdown() {
        unregister(this)
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private fun onTtsReady(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            if (usingGoogleTts) {
                usingGoogleTts = false
                tts?.shutdown()
                tts = TextToSpeech(appContext) { next -> onTtsReady(next) }
            }
            return
        }
        val engine = tts ?: return
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                mainHandler.post { finishUtterance(utteranceId) }
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {}
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post { finishUtterance(utteranceId) }
            }
        })
        applyEnglishVoice(engine)
        ttsReady = true
        flushSpeech()
        mainHandler.postDelayed({
            val current = tts ?: return@postDelayed
            if (chosenVoice == null || current.voice?.name != chosenVoice?.name) {
                applyEnglishVoice(current)
                flushSpeech()
            }
        }, 700)
    }

    private fun flushSpeech() {
        if (!ttsReady) return
        val engine = tts ?: return
        chosenVoice?.let { voice ->
            if (engine.voice?.name != voice.name) engine.voice = voice
        }
        while (speechQueue.isNotEmpty()) {
            val text = speechQueue.removeFirst()
            val utteranceId = "vella-${++utteranceSerial}"
            activeUtterance = utteranceId
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                chosenVoice?.let { engine.voice = it }
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
        }
    }

    private fun finishUtterance(utteranceId: String?) {
        if (utteranceId == null || utteranceId != activeUtterance) return
        activeUtterance = ""
        onFinishedUtterance()
    }

    private fun applyEnglishVoice(engine: TextToSpeech) {
        engine.setPitch(1f)
        engine.setSpeechRate(1f)
        val voice = englishVoice3(engine)
        chosenVoice = voice
        if (voice != null) engine.voice = voice
        else engine.setLanguage(Locale.US)
    }

    private fun englishVoice3(engine: TextToSpeech): Voice? {
        val installed = engine.voices.orEmpty().filter { voice ->
            val features = voice.features.orEmpty()
            !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) && isAmericanEnglish(voice)
        }
        val voiceThree = installed.filter { personaKey(it.name).endsWith("x-iog") }
        if (voiceThree.isNotEmpty()) return voiceThree.maxByOrNull { it.quality }
        val numbered = installed.filter { isSettingsVoiceThree(it.name) }
        if (numbered.isNotEmpty()) return numbered.maxByOrNull { it.quality }
        val personas = installed.groupBy { personaKey(it.name) }
        val ordered = personas.keys.sorted()
        val chosen = ordered.getOrNull(2) ?: return null
        return personas[chosen].orEmpty().maxByOrNull { it.quality }
    }

    private fun isAmericanEnglish(voice: Voice): Boolean {
        val name = voice.name.orEmpty()
        if (name.endsWith("-language", ignoreCase = true)) return false
        val locale = voice.locale
        if (locale != null && locale.language.equals("en", ignoreCase = true) && locale.country.equals("US", ignoreCase = true)) {
            return true
        }
        return name.contains("en-us", ignoreCase = true)
    }

    private fun isSettingsVoiceThree(name: String): Boolean {
        val key = personaKey(name)
        return Regex("(?i)[#_](?:female|male)_3(?:-|$)").containsMatchIn(key)
    }

    private fun personaKey(name: String): String {
        return name.lowercase().replace(Regex("-(local|network|phone-hmm|diphone-usel).*$"), "")
    }

    private fun googleTtsInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo(GOOGLE_TTS, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    companion object {
        private const val GOOGLE_TTS = "com.google.android.tts"
        private val speakers = Collections.newSetFromMap(WeakHashMap<VellaSpeaker, Boolean>())

        private fun register(speaker: VellaSpeaker) {
            synchronized(speakers) { speakers.add(speaker) }
        }

        private fun unregister(speaker: VellaSpeaker) {
            synchronized(speakers) { speakers.remove(speaker) }
        }

        fun interruptAll() {
            val current = synchronized(speakers) { speakers.toList() }
            current.forEach { it.stop() }
        }
    }
}
