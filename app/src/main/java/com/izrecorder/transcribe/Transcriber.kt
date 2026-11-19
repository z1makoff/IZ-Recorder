package com.izrecorder.transcribe

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.izrecorder.App
import com.izrecorder.R
import com.izrecorder.Settings
import com.izrecorder.data.Recording
import com.izrecorder.data.RecordingRepository
import com.izrecorder.data.Segment
import com.izrecorder.data.Status
import java.io.File

object Transcriber {
    private const val WORK_NAME = "transcribe"

    /** Ставит в очередь обработку всех нерасшифрованных записей. */
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<TranscribeWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun retranscribe(context: Context, r: Recording) {
        RecordingRepository.save(
            r.copy(status = Status.PENDING, segments = emptyList(), transcribedUntilMs = 0, progress = 0f, error = "")
        )
        File(context.cacheDir, "pcm/${r.id}.pcm").delete()
        stallPrefs(context).edit().remove(r.id).apply()
        enqueue(context)
    }

    /** Сколько раз подряд расшифровка одной записи начиналась с одного и того же места. */
    internal fun stallPrefs(context: Context) =
        context.getSharedPreferences("transcribe_stalls", Context.MODE_PRIVATE)
}

/**
 * Расшифровывает записи кусками по ~30 секунд, сохраняя прогресс после каждого куска.
 *
 * Работает как foreground-задача с уведомлением: так Android даёт ей быстрые ядра и не
 * обрывает через несколько минут. Без этого (обычная фоновая задача) расшифровка шла на
 * медленных ядрах, система обрывала её посреди куска, перезапускала с того же места — и так
 * по кругу весь день, сажая батарею. Если foreground не разрешён, задача укладывается
 * в 8 минут и ставит в очередь продолжение. Если запись несколько раз подряд не продвигается,
 * её расшифровка останавливается с ошибкой, а не крутится бесконечно.
 */
class TranscribeWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val TAG = "TranscribeWorker"
        private const val CHUNK_MS = 30_000L
        private const val SEARCH_MS = 8_000L
        private const val BUDGET_MS = 8 * 60_000L
        private const val SR = AudioDecoder.SAMPLE_RATE
        private const val NOTIFICATION_ID = 3
        /** Сколько раз подряд можно начать с одного места, прежде чем сдаться. */
        private const val MAX_STALLS = 3
        private const val MODEL_FAIL_KEY = "model_load_failures"
    }

    private val startedAt = SystemClock.elapsedRealtime()
    private var ctx = 0L
    /** Сколько миллисекунд обработки уходит на миллисекунду аудио (оценка). */
    private var speedRatio = 1.0
    /** true — задача работает как foreground и не ограничена 10 минутами. */
    private var foreground = false

    override fun doWork(): Result {
        val context = applicationContext
        val model = Settings.current.model
        if (!ModelManager.isReady(context, model)) {
            ModelManager.ensureDownload(context, model)
            return Result.success() // продолжим, когда модель скачается
        }
        WhisperLib.resetAbort()
        try {
            while (!isStopped) {
                val next = RecordingRepository.all.value
                    .filter { it.status == Status.PENDING || it.status == Status.TRANSCRIBING }
                    .maxByOrNull { it.createdAt } ?: break
                if (ctx == 0L) {
                    // Уведомление появляется, только когда действительно есть что расшифровывать.
                    foreground = tryStartForeground("Подготовка…")
                    ctx = WhisperLib.initContext(ModelManager.file(context, model).absolutePath)
                    val stalls = Transcriber.stallPrefs(context)
                    if (ctx == 0L) {
                        Log.e(TAG, "model load failed")
                        val failures = stalls.getInt(MODEL_FAIL_KEY, 0) + 1
                        stalls.edit().putInt(MODEL_FAIL_KEY, failures).apply()
                        if (failures <= 1) {
                            // Возможно, файл повреждён — скачиваем заново, но только один раз.
                            ModelManager.delete(context, model)
                            ModelManager.ensureDownload(context, model)
                        } else {
                            // Иначе получился бы бесконечный круг «скачать 190 МБ → ошибка».
                            failPending("модель распознавания не загружается. Выберите другую модель в настройках.")
                        }
                        return Result.success()
                    }
                    stalls.edit().remove(MODEL_FAIL_KEY).apply()
                }
                val finished = try {
                    process(next)
                } catch (e: Exception) {
                    Log.e(TAG, "transcription failed", e)
                    RecordingRepository.update(next.id) {
                        it.copy(status = Status.ERROR, error = e.message ?: e.toString())
                    }
                    pcmFile(next).delete()
                    true
                }
                if (!finished) {
                    if (!isStopped) Transcriber.enqueue(context)
                    break
                }
            }
        } finally {
            if (ctx != 0L) WhisperLib.freeContext(ctx)
            ctx = 0L
        }
        return Result.success()
    }

    override fun onStopped() {
        WhisperLib.abort()
    }

    private fun failPending(message: String) {
        RecordingRepository.all.value
            .filter { it.status == Status.PENDING || it.status == Status.TRANSCRIBING }
            .forEach { r -> RecordingRepository.update(r.id) { it.copy(status = Status.ERROR, error = message) } }
    }

    private fun notification(text: String) =
        NotificationCompat.Builder(applicationContext, App.CHANNEL_TRANSCRIBING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Расшифровка записи")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun foregroundInfo(text: String): ForegroundInfo {
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return ForegroundInfo(NOTIFICATION_ID, notification(text), type)
    }

    /** @return true, если Android разрешил работать как foreground-задача. */
    private fun tryStartForeground(text: String): Boolean = try {
        setForegroundAsync(foregroundInfo(text)).get()
        true
    } catch (e: Exception) {
        Log.w(TAG, "foreground not allowed, running as regular background work", e)
        false
    }

    private fun updateNotification(text: String) {
        if (!foreground) return
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    private fun pcmFile(r: Recording) = File(applicationContext.cacheDir, "pcm/${r.id}.pcm")

    /** @return true — запись обработана полностью, false — не хватило времени. */
    private fun process(start: Recording): Boolean {
        val audio = RecordingRepository.audioFile(start)
        if (!audio.exists()) {
            RecordingRepository.update(start.id) { it.copy(status = Status.ERROR, error = "Аудиофайл не найден") }
            return true
        }
        // Защита от бесконечного круга: запись снова и снова начинается с одного места.
        val stalls = Transcriber.stallPrefs(applicationContext)
        val prev = stalls.getString(start.id, null)?.split(':')
        val count = if (prev?.getOrNull(0)?.toLongOrNull() == start.transcribedUntilMs) {
            (prev?.getOrNull(1)?.toIntOrNull() ?: 0) + 1
        } else 1
        if (count > MAX_STALLS) {
            stalls.edit().remove(start.id).apply()
            RecordingRepository.update(start.id) {
                it.copy(
                    status = Status.ERROR,
                    error = "расшифровка не продвигается и остановлена, чтобы не сажать батарею. " +
                        "Можно запустить заново, лучше на зарядке.",
                )
            }
            pcmFile(start).delete()
            return true
        }
        stalls.edit().putString(start.id, "${start.transcribedUntilMs}:$count").apply()

        RecordingRepository.update(start.id) { it.copy(status = Status.TRANSCRIBING) }

        val pcm = pcmFile(start)
        pcm.parentFile?.mkdirs()
        if (!pcm.exists() || pcm.length() == 0L) {
            val tmp = File(pcm.path + ".tmp")
            AudioDecoder.decodeToPcm(audio, tmp)
            tmp.renameTo(pcm)
        }
        val totalSamples = pcm.length() / 2
        val totalMs = totalSamples * 1000 / SR
        if (start.durationMs <= 0) RecordingRepository.update(start.id) { it.copy(durationMs = totalMs) }

        val settings = Settings.current
        val threads = WhisperLib.recommendedThreads()
        var doneMs = start.transcribedUntilMs

        while (doneMs < totalMs) {
            if (isStopped) return false
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (!foreground && elapsed > 30_000 && elapsed + CHUNK_MS * speedRatio * 1.5 > BUDGET_MS) return false
            updateNotification("Готово ${doneMs * 100 / totalMs.coerceAtLeast(1)}%")

            val fromSample = doneMs * SR / 1000
            val endSample = chooseChunkEnd(pcm, fromSample, totalSamples)
            val count = (endSample - fromSample).toInt()
            if (count < SR / 2) { // меньше полсекунды — нечего распознавать
                doneMs = totalMs
                break
            }
            val samples = AudioDecoder.readSamples(pcm, fromSample, count)
            val chunkMs = count * 1000L / SR
            if (isSilent(samples)) {
                // В тишине whisper склонен «придумывать» фразы — такие куски пропускаем.
                doneMs = endSample * 1000 / SR
                val until = doneMs
                RecordingRepository.update(start.id) {
                    it.copy(transcribedUntilMs = until, progress = (until.toFloat() / totalMs).coerceIn(0f, 1f))
                }
                continue
            }
            val chunkStarted = SystemClock.elapsedRealtime()
            var lastSaved = -10
            val listener = WhisperLib.ProgressListener { percent ->
                if (percent - lastSaved >= 10) {
                    lastSaved = percent
                    val p = (doneMs + chunkMs * percent / 100f) / totalMs
                    RecordingRepository.update(start.id) { it.copy(progress = p.coerceIn(0f, 1f)) }
                }
            }
            val n = WhisperLib.transcribe(ctx, samples, settings.language, threads, listener)
            if (n < 0) {
                if (isStopped) return false
                throw IllegalStateException("Ошибка распознавания")
            }
            speedRatio = (SystemClock.elapsedRealtime() - chunkStarted).toDouble() / chunkMs
            val offset = doneMs
            val newSegments = (0 until n).mapNotNull { i ->
                val text = String(WhisperLib.segmentText(ctx, i), Charsets.UTF_8).trim()
                if (text.isEmpty() || isNoise(text) || isHallucination(text)) null
                else Segment(offset + WhisperLib.segmentT0(ctx, i), offset + WhisperLib.segmentT1(ctx, i), text)
            }
            val lang = WhisperLib.detectedLanguage(ctx)
            doneMs = endSample * 1000 / SR
            val until = doneMs
            RecordingRepository.update(start.id) {
                it.copy(
                    segments = it.segments + newSegments,
                    transcribedUntilMs = until,
                    progress = (until.toFloat() / totalMs).coerceIn(0f, 1f),
                    language = it.language.ifEmpty { lang },
                )
            }
            // Кусок обработан — счётчик «застреваний» начинается заново с нового места.
            stalls.edit().putString(start.id, "$until:1").apply()
        }
        RecordingRepository.update(start.id) {
            it.copy(status = Status.DONE, progress = 1f, transcribedUntilMs = totalMs, error = "")
        }
        stalls.edit().remove(start.id).apply()
        pcm.delete()
        return true
    }

    /**
     * Конец куска: около [CHUNK_MS] от начала, но в самом тихом месте последних секунд,
     * чтобы не резать слово пополам.
     */
    private fun chooseChunkEnd(pcm: File, from: Long, total: Long): Long {
        val target = from + CHUNK_MS * SR / 1000
        if (target + SEARCH_MS * SR / 1000 >= total) return total
        val searchFrom = target - SEARCH_MS * SR / 1000
        val window = AudioDecoder.readSamples(pcm, searchFrom, (target - searchFrom).toInt())
        val frame = SR / 10 // 100 мс
        var best = window.size
        var bestEnergy = Double.MAX_VALUE
        var i = 0
        while (i + frame <= window.size) {
            var e = 0.0
            for (j in i until i + frame) e += window[j] * window[j]
            if (e < bestEnergy) {
                bestEnergy = e
                best = i + frame / 2
            }
            i += frame
        }
        return searchFrom + best
    }

    /** Почти тишина: самые громкие 100-мс отрезки куска тише примерно −45 дБ. */
    private fun isSilent(samples: FloatArray): Boolean {
        val frame = SR / 10
        var maxRms = 0.0
        var i = 0
        while (i + frame <= samples.size) {
            var e = 0.0
            for (j in i until i + frame) e += samples[j] * samples[j]
            maxRms = maxOf(maxRms, Math.sqrt(e / frame))
            i += frame
        }
        return maxRms < 0.0056
    }

    /** Типичные фразы, которые whisper выдумывает на шуме и тишине. */
    private val hallucinations = listOf(
        "продолжение следует", "субтитры", "редактор субтитров", "спасибо за просмотр",
        "thanks for watching", "thank you for watching", "subtitles by", "please subscribe",
    )

    private fun isHallucination(text: String): Boolean {
        val t = text.lowercase().trim(' ', '.', '!', '…')
        return hallucinations.any { t.startsWith(it) }
    }

    /** Отбрасываем служебные пометки whisper вроде «[Music]» или «(шум)». */
    private fun isNoise(text: String): Boolean {
        val t = text.trim()
        return (t.startsWith("[") && t.endsWith("]")) || (t.startsWith("(") && t.endsWith(")"))
    }
}
