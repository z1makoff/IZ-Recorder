package com.izrecorder

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.izrecorder.data.RecordingRepository
import com.izrecorder.data.Status
import com.izrecorder.data.recoverOrphans
import com.izrecorder.recorder.RecorderService
import com.izrecorder.transcribe.Transcriber
import com.izrecorder.ui.MainActivity
import java.util.concurrent.TimeUnit

class App : Application() {

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_PROBLEMS = "problems"
        private const val PROBLEM_ID = 2

        fun notifyProblem(context: Context, message: String) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val open = PendingIntent.getActivity(
                context, 1, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL_PROBLEMS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("IZ Recorder: запись не началась")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(PROBLEM_ID, n)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        RecordingRepository.init(this)
        if (!RecorderService.isRecording) RecordingRepository.recoverOrphans()
        createChannels()

        RecordingRepository.cleanupInbox(Settings.current.inboxRetentionDays)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "cleanup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS).build(),
        )
        if (RecordingRepository.all.value.any { it.status == Status.PENDING || it.status == Status.TRANSCRIBING }) {
            Transcriber.enqueue(this)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Идёт запись", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROBLEMS, "Проблемы с записью", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }
}

/** Раз в сутки удаляет из «Входящих» записи старше заданного срока. */
class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        RecordingRepository.cleanupInbox(Settings.current.inboxRetentionDays)
        return Result.success()
    }
}
