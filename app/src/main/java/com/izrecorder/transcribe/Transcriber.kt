package com.izrecorder.transcribe

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
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
        enqueue(context)
    }
}

/**
 * Расшифровывает записи кусками примерно по минуте. Фоновая задача Android живёт не дольше
 * 10 минут, поэтому прогресс сохраняется после каждого куска, а при нехватке времени
 * задача ставит в очередь своё продолжение.
 */
class TranscribeWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val TAG = "TranscribeWorker"
        private const val CHUNK_MS = 60_000L
        private const val SEARCH_MS = 8_000L
        private const val BUDGET_MS = 8 * 60_000L
        private const val SR = AudioDecoder.SAMPLE_RATE
    }

    private val startedAt = SystemClock.elapsedRealtime()
    private var ctx = 0L
    /** Сколько миллисекунд обработки уходит на миллисекунду аудио (оценка). */
    private var speedRatio = 1.0

    override fun doWork(): Result {
        val context = applicationContext
        val model = Settings.current.model
        if (!ModelManager.isReady(context, model)) {
            ModelManager.ensureDownload(context, model)
            return Result.success() // продолжим, когда модель скачается
        }
        try {
            while (!isStopped) {
                val next = RecordingRepository.all.value
                    .filter { it.status == Status.PENDING || it.status == Status.TRANSCRIBING }
                    .maxByOrNull { it.createdAt } ?: break
                if (ctx == 0L) {
                    ctx = WhisperLib.initContext(ModelManager.file(context, model).absolutePath)
                    if (ctx == 0L) {
                        Log.e(TAG, "model load failed")
                        ModelManager.delete(context, model)
                        ModelManager.ensureDownload(context, model)
                        return Result.success()
                    }
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

    private fun pcmFile(r: Recording) = File(applicationContext.cacheDir, "pcm/${r.id}.pcm")

    /** @return true — запись обработана полностью, false — не хватило времени. */
    private fun process(start: Recording): Boolean {
        val audio = RecordingRepository.audioFile(start)
        if (!audio.exists()) {
            RecordingRepository.update(start.id) { it.copy(status = Status.ERROR, error = "Аудиофайл не найден") }
            return true
        }
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
            if (elapsed > 30_000 && elapsed + CHUNK_MS * speedRatio * 1.5 > BUDGET_MS) return false

            val fromSample = doneMs * SR / 1000
            val endSample = chooseChunkEnd(pcm, fromSample, totalSamples)
            val count = (endSample - fromSample).toInt()
            if (count < SR / 2) { // меньше полсекунды — нечего распознавать
                doneMs = totalMs
                break
            }
            val samples = AudioDecoder.readSamples(pcm, fromSample, count)
            val chunkMs = count * 1000L / SR
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
                if (text.isEmpty() || isNoise(text)) null
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
        }
        RecordingRepository.update(start.id) {
            it.copy(status = Status.DONE, progress = 1f, transcribedUntilMs = totalMs, error = "")
        }
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

    /** Отбрасываем служебные пометки whisper вроде «[Music]» или «(шум)». */
    private fun isNoise(text: String): Boolean {
        val t = text.trim()
        return (t.startsWith("[") && t.endsWith("]")) || (t.startsWith("(") && t.endsWith(")"))
    }
}
