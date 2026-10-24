package com.izrecorder.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Хранилище записей: для каждой записи — аудиофайл и JSON с метаданными и расшифровкой
 * в папке filesDir/recordings/<раздел>/.
 */
object RecordingRepository {
    private lateinit var root: File
    private val lock = Any()
    private val _all = MutableStateFlow<List<Recording>>(emptyList())
    val all: StateFlow<List<Recording>> get() = _all

    fun init(context: Context) {
        root = File(context.filesDir, "recordings")
        Section.entries.forEach { dir(it).mkdirs() }
        reload()
    }

    fun dir(section: Section) = File(root, section.dir)

    fun audioFile(r: Recording) = File(dir(r.section), r.audioFile)

    private fun metaFile(section: Section, id: String) = File(dir(section), "$id.json")

    fun newId(): String {
        val base = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var id = base
        var n = 1
        while (Section.entries.any { s -> dir(s).list().orEmpty().any { it.startsWith("$id.") } }) {
            id = "${base}_${n++}"
        }
        return id
    }

    fun reload() {
        synchronized(lock) {
            val list = Section.entries.flatMap { section ->
                dir(section).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
                    runCatching { Recording.fromJson(JSONObject(f.readText())) }.getOrNull()
                }
            }.sortedByDescending { it.createdAt }
            _all.value = list
        }
    }

    fun get(id: String): Recording? = _all.value.firstOrNull { it.id == id }

    fun save(r: Recording) {
        synchronized(lock) {
            val f = metaFile(r.section, r.id)
            val tmp = File(f.parentFile, "${r.id}.json.tmp")
            tmp.writeText(r.toJson().toString())
            tmp.renameTo(f)
            _all.value = (_all.value.filterNot { it.id == r.id } + r).sortedByDescending { it.createdAt }
        }
    }

    fun update(id: String, block: (Recording) -> Recording): Recording? {
        synchronized(lock) {
            val r = get(id) ?: return null
            val updated = block(r)
            save(updated)
            return updated
        }
    }

    fun delete(r: Recording) {
        synchronized(lock) {
            audioFile(r).delete()
            metaFile(r.section, r.id).delete()
            _all.value = _all.value.filterNot { it.id == r.id }
        }
    }

    /** Переносит запись из «Входящих» в «Диктофон», чтобы она не удалялась. */
    fun moveTo(r: Recording, section: Section) {
        if (r.section == section) return
        synchronized(lock) {
            val src = audioFile(r)
            val dst = File(dir(section), r.audioFile)
            if (!src.renameTo(dst)) {
                src.copyTo(dst, overwrite = true)
                src.delete()
            }
            metaFile(r.section, r.id).delete()
            save(r.copy(section = section))
        }
    }

    /** Удаляет из «Входящих» всё старше [days] дней. */
    fun cleanupInbox(days: Int) {
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        _all.value.filter { it.section == Section.INBOX && it.createdAt < cutoff }.forEach { delete(it) }
    }
}

/**
 * Если приложение было убито посреди записи, аудиофайл (OGG) остаётся без метаданных.
 * Подхватываем такие файлы, чтобы запись не потерялась.
 */
fun RecordingRepository.recoverOrphans() {
    val known = all.value.map { it.audioFile to it.section }.toSet()
    for (section in Section.entries) {
        dir(section).listFiles().orEmpty()
            .filter { f -> f.isFile && !f.name.endsWith(".json") && !f.name.endsWith(".tmp") && f.length() > 0 }
            .filter { f -> (f.name to section) !in known }
            .forEach { f ->
                save(
                    Recording(
                        id = f.nameWithoutExtension,
                        section = section,
                        audioFile = f.name,
                        createdAt = f.lastModified(),
                        title = "Восстановленная запись",
                    )
                )
            }
    }
}
