package com.izrecorder.data

import org.json.JSONArray
import org.json.JSONObject

enum class Section(val dir: String) {
    /** Записи с диктофона — хранятся всегда. */
    DICTAPHONE("dictaphone"),

    /** Импортированное аудио (голосовые и т.п.) — удаляется через N дней. */
    INBOX("inbox"),
}

enum class Status { PENDING, TRANSCRIBING, DONE, ERROR }

data class Segment(val startMs: Long, val endMs: Long, val text: String)

data class Recording(
    val id: String,
    val section: Section,
    val audioFile: String,
    val createdAt: Long,
    val durationMs: Long = 0,
    val title: String = "",
    val status: Status = Status.PENDING,
    val progress: Float = 0f,
    val language: String = "",
    val error: String = "",
    val segments: List<Segment> = emptyList(),
    /** До какого места аудио уже расшифровано (расшифровка идёт кусками). */
    val transcribedUntilMs: Long = 0,
) {
    val text: String get() = segments.joinToString(" ") { it.text.trim() }.trim()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("section", section.name)
        put("audioFile", audioFile)
        put("createdAt", createdAt)
        put("durationMs", durationMs)
        put("title", title)
        put("status", status.name)
        put("progress", progress.toDouble())
        put("language", language)
        put("error", error)
        put("transcribedUntilMs", transcribedUntilMs)
        put("segments", JSONArray().apply {
            segments.forEach {
                put(JSONObject().put("s", it.startMs).put("e", it.endMs).put("t", it.text))
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Recording {
            val segs = o.optJSONArray("segments") ?: JSONArray()
            return Recording(
                id = o.getString("id"),
                section = Section.valueOf(o.getString("section")),
                audioFile = o.getString("audioFile"),
                createdAt = o.getLong("createdAt"),
                durationMs = o.optLong("durationMs"),
                title = o.optString("title"),
                status = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.PENDING),
                progress = o.optDouble("progress", 0.0).toFloat(),
                language = o.optString("language"),
                error = o.optString("error"),
                transcribedUntilMs = o.optLong("transcribedUntilMs"),
                segments = (0 until segs.length()).map {
                    val s = segs.getJSONObject(it)
                    Segment(s.getLong("s"), s.getLong("e"), s.getString("t"))
                },
            )
        }
    }
}
