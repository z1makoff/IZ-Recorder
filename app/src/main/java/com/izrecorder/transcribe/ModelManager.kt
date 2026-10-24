package com.izrecorder.transcribe

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.izrecorder.Settings
import com.izrecorder.WhisperModel
import java.io.File

sealed interface ModelState {
    data object Ready : ModelState
    data object Missing : ModelState
    data class Downloading(val downloaded: Long, val total: Long) : ModelState
    data class Failed(val reason: String) : ModelState
}

/** Скачивание модели распознавания (один раз) через системный DownloadManager. */
object ModelManager {
    private const val PREFS = "model"

    private fun dir(context: Context) =
        (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }

    fun file(context: Context, model: WhisperModel) = File(dir(context), model.fileName)

    private fun partFile(context: Context, model: WhisperModel) = File(dir(context), model.fileName + ".part")

    fun isReady(context: Context, model: WhisperModel = Settings.current.model): Boolean {
        val f = file(context, model)
        return f.exists() && f.length() > model.sizeMb * 1024L * 1024 * 7 / 10
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun state(context: Context, model: WhisperModel = Settings.current.model): ModelState {
        if (isReady(context, model)) return ModelState.Ready
        val id = prefs(context).getLong("id_${model.name}", -1)
        if (id < 0) {
            val err = prefs(context).getString("err_${model.name}", null)
            return if (err != null) ModelState.Failed(err) else ModelState.Missing
        }
        val dm = context.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return ModelState.Missing
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return when (status) {
                DownloadManager.STATUS_FAILED -> ModelState.Failed("ошибка загрузки")
                DownloadManager.STATUS_SUCCESSFUL -> {
                    finish(context, model, id)
                    if (isReady(context, model)) ModelState.Ready else ModelState.Failed("файл повреждён")
                }
                else -> ModelState.Downloading(done, total)
            }
        }
    }

    /** Запускает загрузку, если модели ещё нет и она не качается. */
    fun ensureDownload(context: Context, model: WhisperModel = Settings.current.model) {
        val s = state(context, model)
        if (s is ModelState.Ready || s is ModelState.Downloading) return
        val part = partFile(context, model)
        part.delete()
        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("IZ Recorder: модель распознавания")
            .setDescription(model.title)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(part))
            .setAllowedOverMetered(true)
        val id = context.getSystemService(DownloadManager::class.java).enqueue(request)
        prefs(context).edit().putLong("id_${model.name}", id).remove("err_${model.name}").apply()
    }

    fun delete(context: Context, model: WhisperModel) {
        file(context, model).delete()
        partFile(context, model).delete()
        prefs(context).edit().remove("id_${model.name}").remove("err_${model.name}").apply()
    }

    /** Вызывается по окончании загрузки: переименовывает .part в готовый файл. */
    internal fun onDownloadComplete(context: Context, downloadId: Long) {
        val model = WhisperModel.entries.firstOrNull {
            prefs(context).getLong("id_${it.name}", -1) == downloadId
        } ?: return
        val dm = context.getSystemService(DownloadManager::class.java)
        val ok = dm.query(DownloadManager.Query().setFilterById(downloadId)).use { c ->
            c.moveToFirst() &&
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
        }
        if (ok) {
            finish(context, model, downloadId)
            Transcriber.enqueue(context)
        } else {
            prefs(context).edit().remove("id_${model.name}")
                .putString("err_${model.name}", "ошибка загрузки, попробуйте ещё раз").apply()
        }
    }

    private fun finish(context: Context, model: WhisperModel, downloadId: Long) {
        val part = partFile(context, model)
        if (part.exists()) part.renameTo(file(context, model))
        if (prefs(context).getLong("id_${model.name}", -1) == downloadId) {
            prefs(context).edit().remove("id_${model.name}").apply()
        }
    }
}

class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id >= 0) ModelManager.onDownloadComplete(context, id)
    }
}
