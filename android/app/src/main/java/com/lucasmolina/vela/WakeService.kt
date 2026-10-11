package com.lucasmolina.vela

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat

class WakeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var speaker: VellaSpeaker? = null
    private var brain: VoiceBrain? = null
    private var paused = false
    private var screenOn = true
    private var phase = Phase.WAKE
    private var afterSpeech: (() -> Unit)? = null
    private var wakeArmed = false
    private var listening = false
    private var session = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val power = getSystemService(PowerManager::class.java)
        screenOn = power.isInteractive
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vella:hey").apply {
            setReferenceCounted(false)
        }
        speaker = VellaSpeaker(this) {
            val next = afterSpeech
            afterSpeech = null
            next?.invoke()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        showForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (phase == Phase.WAKE && screenOn) listenSoon(400)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        recognizer?.destroy()
        recognizer = null
        brain?.shutdown()
        brain = null
        speaker?.shutdown()
        speaker = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (enabled(this)) {
            val restart = Intent(applicationContext, WakeService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) applicationContext.startForegroundService(restart)
                else applicationContext.startService(restart)
            } catch (_: Exception) {
            }
            scheduleRestart()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun pauseListening() {
        paused = true
        if (phase == Phase.WAKE) recognizer?.cancel()
    }

    private fun resumeListening() {
        paused = false
        if (phase == Phase.WAKE && screenOn) listenSoon(600)
    }

    private fun listenSoon(delayMs: Long) {
        handler.removeCallbacks(listenAgain)
        handler.postDelayed(listenAgain, delayMs)
    }

    private val listenAgain = Runnable { listen() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOn = intent?.action != Intent.ACTION_SCREEN_OFF
            if (screenOn) {
                if (phase == Phase.WAKE && !paused && !MicGate.busy) listenSoon(400)
            } else if (phase == Phase.WAKE) {
                stopIdleMic()
            }
        }
    }

    private fun listen() {
        if (!enabled(this)) return
        val barging = phase == Phase.ANSWER || phase == Phase.GREETING
        if (phase == Phase.COMMAND) {
            startRecognizer(command = true)
            return
        }
        if (paused || MicGate.busy) return
        if (phase == Phase.WAKE && (!screenOn || listening)) return
        if (phase != Phase.WAKE && !barging) return
        startRecognizer(command = false)
    }

    private fun stopIdleMic() {
        session += 1
        listening = false
        handler.removeCallbacks(listenAgain)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun holdTurn() {
        val lock = wakeLock ?: return
        if (!lock.isHeld) lock.acquire(5 * 60 * 1000L)
    }

    private fun releaseTurn() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    private fun ensureBrain() {
        if (brain != null) return
        brain = VoiceBrain(this).also { created ->
            created.onReply = { text, followUp -> onAnswer(text, followUp) }
            created.start()
        }
    }

    private fun startRecognizer(command: Boolean) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        if (!command && phase == Phase.WAKE && (!screenOn || listening)) return
        val active = ++session
        val current = recognizer ?: createRecognizer().also { recognizer = it }
        current.setRecognitionListener(listenerFor(active))
        listening = true
        try {
            current.startListening(recognizeIntent(command))
        } catch (_: Exception) {
            if (active == session) {
                listening = false
                recognizer?.destroy()
                recognizer = null
                listenSoon(800)
            }
        }
    }

    private fun createRecognizer(): SpeechRecognizer {
        val component = recognitionService()
        return if (component != null) SpeechRecognizer.createSpeechRecognizer(this, component)
        else SpeechRecognizer.createSpeechRecognizer(this)
    }

    private fun recognitionService(): ComponentName? {
        val matches = packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        val preferred = matches.firstOrNull { it.serviceInfo.packageName == "com.google.android.tts" }
            ?: matches.firstOrNull { it.serviceInfo.packageName == "com.google.android.googlequicksearchbox" }
            ?: matches.firstOrNull()
            ?: return null
        return ComponentName(preferred.serviceInfo.packageName, preferred.serviceInfo.name)
    }

    private fun recognizeIntent(command: Boolean): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            if (command) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            } else {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 120_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 120_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 120_000L)
            }
            putStringArrayListExtra(
                "android.speech.extra.BIASING_STRINGS",
                arrayListOf("Hey Vella", "one", "two", "three", "first", "second", "add", "move", "cancel", "undo"),
            )
        }
    }

    private fun consider(lines: List<String>, partial: Boolean) {
        if (paused || MicGate.busy) return
        if (phase != Phase.WAKE && phase != Phase.ANSWER && phase != Phase.GREETING) return
        val transcript = lines.firstOrNull { wake.containsMatchIn(it) } ?: return
        val match = wake.find(transcript) ?: return
        val command = transcript.substring(match.range.last + 1).trim().trim(',', '.', ' ')
        if (partial && command.isEmpty()) {
            if (phase == Phase.ANSWER || phase == Phase.GREETING) {
                afterSpeech = null
                VellaSpeaker.interruptAll()
            }
            if (!wakeArmed) {
                wakeArmed = true
                handler.postDelayed(fireWake, if (phase == Phase.WAKE) 1100 else 350)
            }
            return
        }
        beginTurn(command)
    }

    private val fireWake = Runnable {
        wakeArmed = false
        if (phase == Phase.WAKE || phase == Phase.ANSWER || phase == Phase.GREETING) beginTurn("")
    }

    private fun beginTurn(command: String) {
        if (phase == Phase.COMMAND) return
        session += 1
        wakeArmed = false
        handler.removeCallbacks(fireWake)
        listening = false
        recognizer?.cancel()
        afterSpeech = null
        VellaSpeaker.interruptAll()
        ensureBrain()
        holdTurn()
        if (command.isNotEmpty()) {
            phase = Phase.ANSWER
            brain?.deliver(command)
        } else {
            phase = Phase.GREETING
            speakThen(VellaPhrases.greeting()) { captureCommand() }
        }
    }

    private fun captureCommand() {
        phase = Phase.COMMAND
        listening = false
        recognizer?.destroy()
        recognizer = null
        listenSoon(200)
    }

    private fun onAnswer(text: String, followUp: Boolean) {
        val spoken = text.ifBlank { if (followUp) "" else "Okay." }
        if (spoken.isBlank()) {
            if (followUp) captureCommand() else returnToWake()
            return
        }
        phase = Phase.ANSWER
        speakThen(spoken) {
            if (followUp) captureCommand() else returnToWake()
        }
    }

    private fun returnToWake() {
        phase = Phase.WAKE
        releaseTurn()
        listening = false
        recognizer?.destroy()
        recognizer = null
        if (screenOn) listenSoon(600) else stopIdleMic()
    }

    private fun speakThen(text: String, then: () -> Unit) {
        afterSpeech = then
        val voice = speaker
        if (voice == null) {
            then()
            return
        }
        voice.speak(text)
        if (phase == Phase.ANSWER || phase == Phase.GREETING) listenSoon(200)
    }

    private fun heardCommand(lines: List<String>) {
        val text = TranscriptChoice.best(lines)
        val cleaned = text.trim()
        if (cleaned.isEmpty()) {
            missedCommand()
            return
        }
        phase = Phase.ANSWER
        brain?.deliver(cleaned)
    }

    private fun missedCommand() {
        phase = Phase.GREETING
        speakThen("I didn't catch that.") { captureCommand() }
    }

    private fun listenerFor(active: Int) = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            if (active != session) return
            listening = false
            val dead = error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
            if (dead) {
                recognizer?.destroy()
                recognizer = null
            }
            when (phase) {
                Phase.WAKE -> if (!paused && !MicGate.busy && screenOn) {
                    val delay = when (error) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> 900L
                        else -> 600L
                    }
                    listenSoon(delay)
                }
                Phase.COMMAND -> {
                    if (dead) {
                        listenSoon(400)
                        return
                    }
                    missedCommand()
                }
                Phase.ANSWER, Phase.GREETING -> if (!paused && !MicGate.busy) listenSoon(500)
                else -> {}
            }
        }
        override fun onResults(results: android.os.Bundle?) {
            if (active != session) return
            val lines = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            when (phase) {
                Phase.WAKE -> {
                    consider(lines, false)
                    if (phase == Phase.WAKE) {
                        listening = false
                        if (screenOn) listenSoon(500)
                    }
                }
                Phase.ANSWER, Phase.GREETING -> {
                    consider(lines, false)
                    if (phase == Phase.ANSWER || phase == Phase.GREETING) {
                        listening = false
                        listenSoon(400)
                    }
                }
                Phase.COMMAND -> {
                    listening = false
                    heardCommand(lines)
                }
            }
        }
        override fun onSegmentResults(segmentResults: android.os.Bundle) {
            if (active != session || phase == Phase.COMMAND) return
            val lines = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            consider(lines, false)
        }
        override fun onEndOfSegmentedSession() {
            if (active != session) return
            listening = false
            if (phase == Phase.WAKE && screenOn && !paused && !MicGate.busy) listenSoon(400)
        }
        override fun onPartialResults(partialResults: android.os.Bundle?) {
            if (active != session || phase == Phase.COMMAND) return
            val lines = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            consider(lines, true)
        }
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
    }

    private fun scheduleRestart() {
        val restart = Intent(applicationContext, WakeService::class.java)
        val pending = PendingIntent.getService(
            applicationContext,
            8,
            restart,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarm = getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + 1000
        if (Build.VERSION.SDK_INT >= 31 && !alarm.canScheduleExactAlarms()) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun showForeground() {
        val notice = notification()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notice)
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Hey Vella", NotificationManager.IMPORTANCE_MIN),
            )
        }
        manager.cancel(43)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Hey Vella")
            .setContentText("Listening for “Hey Vella”")
            .setSilent(true)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private enum class Phase { WAKE, GREETING, COMMAND, ANSWER }

    companion object {
        private const val PREFS = "vella_wake"
        private const val ENABLED = "enabled"
        private const val CHANNEL = "vella_wake_quiet"
        private const val NOTIFICATION_ID = 42
        private val wake = Regex("\\bhey\\s+(?:vella|vela|bella|villa)\\b", RegexOption.IGNORE_CASE)

        @Volatile
        private var instance: WakeService? = null

        fun enabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)
        }

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()
            if (on) start(context) else stop(context)
        }

        fun start(context: Context) {
            val intent = Intent(context, WakeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeService::class.java))
        }

        fun pause() {
            instance?.pauseListening()
        }

        fun resume() {
            instance?.resumeListening()
        }
    }
}
