package com.lucasmolina.vela

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * One microphone capture for as long as wake listening is on.
 */
class WakeMic(
    private val onUtterance: (String) -> Unit,
    private val onEnded: (() -> Unit)? = null,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var open = false

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (record != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return
        val audio = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                min.coerceAtLeast(rate) * 2,
            )
        } catch (_: Exception) {
            return
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            return
        }
        try {
            audio.startRecording()
        } catch (_: Exception) {
            audio.release()
            return
        }
        record = audio
        open = true
        thread = Thread({ readLoop(audio) }, "vella-wake").apply { isDaemon = true }
        thread?.start()
    }

    fun stop() {
        open = false
        val audio = record
        record = null
        try {
            audio?.stop()
        } catch (_: Exception) {
        }
        try {
            thread?.join(300)
        } catch (_: Exception) {
        }
        thread = null
        audio?.release()
    }

    private fun readLoop(audio: AudioRecord) {
        val buffer = ByteArray(1280)
        val speech = ByteArrayOutputStream()
        var heard = false
        var silence = 0
        var speechBytes = 0
        var failures = 0
        while (open && record === audio) {
            val read = audio.read(buffer, 0, buffer.size)
            if (read < 0) {
                failures += 1
                if (failures >= 5) break
                try {
                    Thread.sleep(200)
                } catch (_: Exception) {
                    break
                }
                continue
            }
            failures = 0
            if (read == 0) continue
            if (rms(buffer, read) >= 0.02) {
                if (!heard) {
                    speech.reset()
                    speechBytes = 0
                }
                heard = true
                silence = 0
                speech.write(buffer, 0, read)
                speechBytes += read
            } else if (heard) {
                speech.write(buffer, 0, read)
                silence += read
            }
            val ended = heard && speechBytes >= 11_200 && silence >= 16_000
            val capped = speechBytes >= 16000 * 2 * 6
            if (ended || capped) {
                val bytes = speech.toByteArray()
                speech.reset()
                heard = false
                silence = 0
                speechBytes = 0
                if (bytes.size >= 11_200) {
                    val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    handler.post { if (open) onUtterance(encoded) }
                }
            }
        }
        if (open && record === audio) {
            open = false
            record = null
            try {
                audio.stop()
            } catch (_: Exception) {
            }
            audio.release()
            handler.post { onEnded?.invoke() }
        }
    }

    private fun rms(buffer: ByteArray, length: Int): Double {
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < length) {
            val sample = (buffer[index].toInt() and 0xff) or (buffer[index + 1].toInt() shl 8)
            val signed = sample.toShort().toInt()
            sum += signed.toDouble() * signed.toDouble()
            count += 1
            index += 2
        }
        if (count == 0) return 0.0
        return sqrt(sum / count) / 32768.0
    }
}
