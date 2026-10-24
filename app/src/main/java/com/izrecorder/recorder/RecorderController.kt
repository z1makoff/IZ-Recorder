package com.izrecorder.recorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.izrecorder.App
import com.izrecorder.Feedback

/** Единая точка запуска/остановки записи — из UI, кнопок громкости или быстрой команды. */
object RecorderController {
    private var lastToggle = 0L

    fun toggle(context: Context) {
        val now = SystemClock.elapsedRealtime()
        // Защита от двойного срабатывания (например, сразу два способа запуска).
        if (now - lastToggle < 1500) return
        lastToggle = now
        if (RecorderService.isRecording) stop(context) else start(context)
    }

    fun start(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Feedback.error(context)
            App.notifyProblem(context, "Нет разрешения на микрофон. Откройте IZ Recorder и разрешите доступ.")
            return
        }
        try {
            context.startForegroundService(
                Intent(context, RecorderService::class.java).setAction(RecorderService.ACTION_START)
            )
        } catch (e: Exception) {
            Log.e("RecorderController", "start failed", e)
            Feedback.error(context)
            App.notifyProblem(context, "Android не дал начать запись в фоне: ${e.message}")
        }
    }

    fun stop(context: Context) {
        try {
            context.startService(
                Intent(context, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)
            )
        } catch (e: Exception) {
            Log.e("RecorderController", "stop failed", e)
        }
    }
}
