package com.izrecorder.recorder

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.izrecorder.App
import com.izrecorder.Feedback
import com.izrecorder.R
import com.izrecorder.Settings
import com.izrecorder.data.Recording
import com.izrecorder.data.RecordingRepository
import com.izrecorder.data.Section
import com.izrecorder.transcribe.Transcriber
import com.izrecorder.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

sealed interface RecState {
    data object Idle : RecState

    /** [startedAt] — по SystemClock.elapsedRealtime(), [limitMs] = 0 — без ограничения. */
    data class Recording(val id: String, val startedAt: Long, val limitMs: Long) : RecState
}

/**
 * Запись с микрофона в foreground-сервисе. Пока идёт запись, Android показывает уведомление
 * и индикатор микрофона — это обязательное требование системы.
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.izrecorder.action.START"
        const val ACTION_STOP = "com.izrecorder.action.STOP"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "RecorderService"

        private val _state = MutableStateFlow<RecState>(RecState.Idle)
        val state: StateFlow<RecState> get() = _state
        val isRecording: Boolean get() = _state.value is RecState.Recording
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var current: RecState.Recording? = null
    private var wallStart = 0L
    private val autoStop = Runnable { stopRecording() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
            else -> if (current == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (current != null) return
        val settings = Settings.current
        val limitMs = settings.maxDurationMin * 60_000L
        val startedAt = SystemClock.elapsedRealtime()
        try {
            startForeground(
                NOTIFICATION_ID, buildNotification(limitMs),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            fail("Android не разрешил начать запись: ${e.message}")
            return
        }

        val id = RecordingRepository.newId()
        val rec = createRecorder(id)
        if (rec == null) {
            fail("Не удалось включить микрофон. Возможно, он занят другим приложением.")
            return
        }
        recorder = rec
        wallStart = System.currentTimeMillis()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IZRecorder:recording")
            .apply { acquire(if (limitMs > 0) limitMs + 60_000 else 24 * 60 * 60_000L) }

        current = RecState.Recording(id, startedAt, limitMs).also { _state.value = it }
        if (limitMs > 0) handler.postDelayed(autoStop, limitMs)
        Feedback.started(this)
    }

    /** Пробует Opus в OGG (файл остаётся читаемым даже при внезапной остановке), иначе AAC. */
    private fun createRecorder(id: String): MediaRecorder? {
        val dir = RecordingRepository.dir(Section.DICTAPHONE)
        val variants = listOf(
            Triple("ogg", MediaRecorder.OutputFormat.OGG, MediaRecorder.AudioEncoder.OPUS),
            Triple("m4a", MediaRecorder.OutputFormat.MPEG_4, MediaRecorder.AudioEncoder.AAC),
        )
        for ((ext, format, encoder) in variants) {
            val file = File(dir, "$id.$ext")
            val r = MediaRecorder(this)
            try {
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(format)
                r.setAudioEncoder(encoder)
                r.setAudioChannels(1)
                r.setAudioSamplingRate(16_000)
                r.setAudioEncodingBitRate(if (encoder == MediaRecorder.AudioEncoder.OPUS) 24_000 else 32_000)
                r.setOutputFile(file)
                r.prepare()
                r.start()
                outputFile = file
                return r
            } catch (e: Exception) {
                Log.w(TAG, "recorder $ext failed", e)
                r.release()
                file.delete()
            }
        }
        return null
    }

    private fun stopRecording() {
        val rec = current ?: run { stopSelf(); return }
        handler.removeCallbacks(autoStop)
        val file = outputFile
        var ok = true
        try {
            recorder?.stop()
        } catch (e: RuntimeException) {
            // Слишком короткая запись — данных нет.
            ok = false
        }
        recorder?.release()
        recorder = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        current = null
        _state.value = RecState.Idle

        if (ok && file != null && file.exists() && file.length() > 0) {
            val elapsed = SystemClock.elapsedRealtime() - rec.startedAt
            RecordingRepository.save(
                Recording(
                    id = rec.id,
                    section = Section.DICTAPHONE,
                    audioFile = file.name,
                    createdAt = wallStart,
                    durationMs = probeDuration(file) ?: elapsed,
                )
            )
            Feedback.stopped(this)
            Transcriber.enqueue(this)
        } else {
            file?.delete()
            Feedback.error(this)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun probeDuration(file: File): Long? = runCatching {
        MediaMetadataRetriever().use { mmr ->
            mmr.setDataSource(file.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
        }
    }.getOrNull()?.takeIf { it > 0 }

    private fun fail(message: String) {
        Feedback.error(this)
        App.notifyProblem(this, message)
        recorder?.release()
        recorder = null
        outputFile?.delete()
        current = null
        _state.value = RecState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(limitMs: Long): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (limitMs > 0) "Автостоп через ${limitMs / 60_000} мин" else "Без ограничения по времени"
        return NotificationCompat.Builder(this, App.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Идёт запись")
            .setContentText(text)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Остановить", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        // Если сервис останавливают посреди записи — сохраняем то, что успели записать.
        if (current != null) stopRecording()
        super.onDestroy()
    }
}
