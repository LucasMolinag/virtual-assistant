package com.lucasmolina.vela

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
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
    private var micAlive = false
    private var session = 0
    private var micHold: AudioRecord? = null
    private var micThread: Thread? = null
    @Volatile private var micHoldOpen = false
    private var micStartedAt = 0L
    private val wakeMic = WakeMic(
        onUtterance = { audio ->
            ensureBrain()
            brain?.hear(audio)
        },
        onEnded = {
            val delay = if (System.currentTimeMillis() - micStartedAt > 5_000) 1_000L else 30_000L
            handler.postDelayed({
                if (enabled(this) && !paused && !MicGate.busy) openMic()
            }, delay)
        },
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        showForeground()
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showForeground()
        if (!enabled(this)) {
            wakeMic.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            dismissNotification(this)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureBrain()
        if (!paused && !MicGate.busy) openMic()
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        recognizer?.destroy()
        recognizer = null
        wakeMic.stop()
        releaseMicHold()
        brain?.shutdown()
        brain = null
        speaker?.shutdown()
        speaker = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun pauseListening() {
        paused = true
        session += 1
        listening = false
        recognizer?.cancel()
        wakeMic.stop()
        releaseMicHold()
    }

    private fun resumeListening() {
        paused = false
        if (!MicGate.busy) openMic()
    }

    private fun openMic() {
        wakeMic.stop()
        val lock = wakeLock
        if (lock != null && !lock.isHeld) lock.acquire()
        if (!listening) listenSoon(0)
    }

    private fun listenSoon(delayMs: Long) {
        handler.removeCallbacks(listenAgain)
        handler.postDelayed(listenAgain, delayMs)
    }

    private val listenAgain = Runnable { listen() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOn = intent?.action != Intent.ACTION_SCREEN_OFF
            if (!paused && !MicGate.busy) openMic()
        }
    }

    private fun listen() {
        if (!enabled(this) || paused || MicGate.busy) return
        if (phase == Phase.COMMAND) {
            startRecognizer(command = true)
            return
        }
        if (phase == Phase.WAKE && listening) return
        if (phase != Phase.WAKE && phase != Phase.ANSWER && phase != Phase.GREETING) return
        startRecognizer(command = false)
    }

    private fun releaseWake() {
        paused = true
        handler.removeCallbacks(listenAgain)
        stopIdleMic()
    }

    private fun holdMic() {
        if (micHold != null) return
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return
        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                min * 2,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return
            }
            record.startRecording()
            micHold = record
            micHoldOpen = true
            val buffer = ByteArray(min)
            micThread = Thread({
                while (micHoldOpen && micHold === record) {
                    if (record.read(buffer, 0, buffer.size) < 0) break
                }
            }, "vella-mic").apply { isDaemon = true }
            micThread?.start()
        } catch (_: Exception) {
        }
    }

    private fun releaseMicHold() {
        micHoldOpen = false
        val record = micHold
        micHold = null
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            micThread?.join(300)
        } catch (_: Exception) {
        }
        micThread = null
        record?.release()
    }

    private fun screenIsOn(): Boolean {
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        return screenOn
    }

    private val wakeWatchdog = Runnable {
        if (phase != Phase.WAKE || !listening) return@Runnable
        if (micAlive) {
            micAlive = false
            armWakeWatchdog()
            return@Runnable
        }
        session += 1
        listening = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        if (!paused && !MicGate.busy && screenIsOn()) listenSoon(300)
    }

    private fun armWakeWatchdog() {
        handler.removeCallbacks(wakeWatchdog)
        if (phase == Phase.WAKE) handler.postDelayed(wakeWatchdog, 25_000)
    }

    private fun stopIdleMic() {
        session += 1
        listening = false
        handler.removeCallbacks(listenAgain)
        handler.removeCallbacks(wakeWatchdog)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        wakeMic.stop()
        releaseMicHold()
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
            created.onSpeak = { text -> speaker?.speak(text) }
            created.onReply = { text, _ ->
                MainActivity.refreshUi?.invoke()
                VelaWidgetProvider.updateAll(this)
                if (text.isNotBlank()) speaker?.speak(text)
            }
            created.start()
        }
    }

    private fun startRecognizer(command: Boolean) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            if (!command && phase == Phase.WAKE) listenSoon(2000)
            return
        }
        if (!command && phase == Phase.WAKE && listening) return
        val active = ++session
        val current = recognizer ?: createRecognizer().also { recognizer = it }
        current.setRecognitionListener(listenerFor(active))
        micAlive = false
        listening = true
        if (!command) armWakeWatchdog()
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
        return SpeechRecognizer.createSpeechRecognizer(this)
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
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                if (Build.VERSION.SDK_INT >= 33) putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, true)
            }
            putStringArrayListExtra(
                "android.speech.extra.BIASING_STRINGS",
                arrayListOf("Hey Vella", "Hey Bella", "Vella", "one", "two", "three", "first", "second", "add", "move", "cancel", "undo"),
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
                handler.postDelayed(fireWake, if (phase == Phase.WAKE) 200 else 150)
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
        handler.removeCallbacks(wakeWatchdog)
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
        MainActivity.refreshUi?.invoke()
        VelaWidgetProvider.updateAll(this)
        val spoken = text.ifBlank { if (followUp) "" else "Okay." }
        if (spoken.isBlank()) {
            resumeWake()
            return
        }
        phase = Phase.ANSWER
        speakThen(spoken) { resumeWake() }
    }

    private fun resumeWake() {
        phase = Phase.WAKE
        listening = false
        if (!paused && !MicGate.busy) listenSoon(150)
    }

    private fun returnToWake() {
        phase = Phase.WAKE
        releaseTurn()
        listening = false
        recognizer?.cancel()
    }

    private fun speakThen(text: String, then: () -> Unit) {
        afterSpeech = then
        val voice = speaker
        if (voice == null) {
            then()
            return
        }
        voice.speak(text)
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
        override fun onReadyForSpeech(params: android.os.Bundle?) {
            if (active == session) micAlive = true
        }
        override fun onBeginningOfSpeech() {
            if (active == session) micAlive = true
        }
        override fun onRmsChanged(rmsdB: Float) {
            if (active == session) micAlive = true
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            if (active != session) return
            listening = false
            handler.removeCallbacks(wakeWatchdog)
            val dead = error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_SERVER
            if (dead) {
                recognizer?.destroy()
                recognizer = null
            }
            when (phase) {
                Phase.WAKE -> if (!paused && !MicGate.busy) {
                    val delay = when (error) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> 0L
                        else -> 400L
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
            handler.removeCallbacks(wakeWatchdog)
            val lines = transcripts(results)
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
            val lines = transcripts(segmentResults)
            consider(lines, false)
        }
        override fun onEndOfSegmentedSession() {
            if (active != session) return
            listening = false
            if (phase == Phase.WAKE && screenOn && !paused && !MicGate.busy) listenSoon(400)
        }
        override fun onPartialResults(partialResults: android.os.Bundle?) {
            if (active != session || phase == Phase.COMMAND) return
            micAlive = true
            consider(transcripts(partialResults), true)
        }
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
    }

    private fun showForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Vella", NotificationManager.IMPORTANCE_MIN)
            channel.setSound(null, null)
            channel.enableVibration(false)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
        val notice: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setSilent(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notice)
        }
    }

    private enum class Phase { WAKE, GREETING, COMMAND, ANSWER }

    companion object {
        private const val PREFS = "vella_wake"
        private const val ENABLED = "enabled"
        private const val CHANNEL = "vella_wake_quiet"
        private const val NOTIFICATION_ID = 42
        private val wake = Regex(
            """\bhey\b[\s,.'’"!?-]*\b(?:vella|vela|velma|bella|villa|fella)\b""",
            RegexOption.IGNORE_CASE,
        )

        private fun transcripts(results: android.os.Bundle?): List<String> {
            if (results == null) return emptyList()
            val lines = ArrayList<String>()
            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let { lines.addAll(it) }
            results.getStringArrayList("android.speech.extra.UNSTABLE_TEXT")?.let { lines.addAll(it) }
            return lines
        }

        @Volatile
        private var instance: WakeService? = null

        fun enabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)
        }

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()
            if (on) start(context) else stop(context)
        }

        fun dismissNotification(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.cancel(NOTIFICATION_ID)
            manager.cancel(43)
            if (Build.VERSION.SDK_INT >= 26) manager.deleteNotificationChannel(CHANNEL)
            val pending = PendingIntent.getService(
                context,
                8,
                Intent(context, WakeService::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: return
            context.getSystemService(AlarmManager::class.java)?.cancel(pending)
            pending.cancel()
        }

        fun start(context: Context) {
            if (!enabled(context)) return
            val intent = Intent(context, WakeService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            instance?.releaseWake()
            context.stopService(Intent(context, WakeService::class.java))
            dismissNotification(context)
        }

        fun park() {
            instance?.releaseWake()
        }

        fun pause() {
            instance?.pauseListening()
        }

        fun resume() {
            instance?.resumeListening()
        }
    }
}
