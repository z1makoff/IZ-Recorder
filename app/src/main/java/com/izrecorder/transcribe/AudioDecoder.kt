package com.izrecorder.transcribe

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Декодирует любое поддерживаемое Android аудио (ogg/opus, m4a, mp3, amr, wav…)
 * в сырой PCM: 16 кГц, моно, 16 бит — именно это нужно whisper.
 */
object AudioDecoder {
    const val SAMPLE_RATE = 16_000

    /** Возвращает количество сэмплов в [output]. */
    fun decodeToPcm(input: File, output: File): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("В файле нет аудиодорожки")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        var resampler = Resampler(sampleRate, SAMPLE_RATE)
        val writer = PcmWriter(output)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var mono = FloatArray(0)

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (rate != sampleRate) {
                        sampleRate = rate
                        resampler = Resampler(sampleRate, SAMPLE_RATE)
                    }
                } else if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.order(ByteOrder.nativeOrder())
                    val ch = channels.coerceAtLeast(1)
                    val frames = if (floatPcm) info.size / 4 / ch else info.size / 2 / ch
                    if (mono.size < frames) mono = FloatArray(frames)
                    if (floatPcm) {
                        val fb = buf.asFloatBuffer()
                        for (i in 0 until frames) {
                            var s = 0f
                            for (c in 0 until ch) s += fb.get(i * ch + c)
                            mono[i] = s / ch
                        }
                    } else {
                        val sb = buf.asShortBuffer()
                        for (i in 0 until frames) {
                            var s = 0f
                            for (c in 0 until ch) s += sb.get(i * ch + c)
                            mono[i] = s / ch / 32768f
                        }
                    }
                    resampler.process(mono, frames) { writer.write(it) }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            writer.close()
            codec.stop()
            codec.release()
            extractor.release()
        }
        return writer.samples
    }

    /** Читает [count] сэмплов начиная с [from] из PCM-файла как float [-1, 1]. */
    fun readSamples(pcm: File, from: Long, count: Int): FloatArray {
        val bytes = ByteArray(count * 2)
        RandomAccessFile(pcm, "r").use { raf ->
            raf.seek(from * 2)
            raf.readFully(bytes)
        }
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(count) { sb.get(it) / 32768f }
    }

    /** Линейная интерполяция с сохранением фазы между буферами. */
    private class Resampler(inRate: Int, outRate: Int) {
        private val step = inRate.toDouble() / outRate
        private var pos = 0.0
        private var last = 0f

        fun process(input: FloatArray, n: Int, emit: (Float) -> Unit) {
            if (n <= 0) return
            while (true) {
                val i = Math.floor(pos).toInt()
                if (i + 1 >= n) break
                val a = if (i < 0) last else input[i]
                val b = input[i + 1]
                emit((a + (b - a) * (pos - i)).toFloat())
                pos += step
            }
            pos -= n
            last = input[n - 1]
        }
    }

    private class PcmWriter(file: File) {
        private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
        var samples = 0L
            private set

        fun write(v: Float) {
            val s = (v.coerceIn(-1f, 1f) * 32767f).toInt()
            out.write(s and 0xff)
            out.write((s shr 8) and 0xff)
            samples++
        }

        fun close() = out.close()
    }
}
