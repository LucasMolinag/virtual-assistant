package com.lucasmolina.vela

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date

class AlarmActivity : AppCompatActivity() {
    private var player: MediaPlayer? = null
    private var wake: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm)
        val id = intent.getIntExtra(PhoneAlarms.EXTRA_ID, 0)
        val label = intent.getStringExtra(PhoneAlarms.EXTRA_LABEL).orEmpty().ifBlank { "Alarm" }
        val at = intent.getLongExtra(PhoneAlarms.EXTRA_AT, System.currentTimeMillis())
        findViewById<TextView>(R.id.alarm_label).text = label
        findViewById<TextView>(R.id.alarm_time).text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
        findViewById<TextView>(R.id.alarm_dismiss).setOnClickListener {
            PhoneAlarms.silence(this, id)
            finish()
        }
        findViewById<TextView>(R.id.alarm_snooze).setOnClickListener {
            PhoneAlarms.snooze(this, id, label)
            finish()
        }
        holdWake()
        ring()
    }

    override fun onDestroy() {
        player?.run {
            try {
                stop()
            } catch (_: Exception) {
            }
            release()
        }
        player = null
        vibrator?.cancel()
        if (wake?.isHeld == true) wake?.release()
        wake = null
        super.onDestroy()
    }

    private fun holdWake() {
        val power = getSystemService(PowerManager::class.java) ?: return
        wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vella:alarm").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L)
        }
    }

    private fun ring() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        try {
            val sound = MediaPlayer()
            sound.setDataSource(this, uri)
            sound.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            sound.isLooping = true
            sound.prepare()
            sound.start()
            player = sound
        } catch (_: Exception) {
        }
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 500), 0))
    }
}
