package com.izrecorder.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.izrecorder.data.Recording
import com.izrecorder.data.RecordingRepository
import com.izrecorder.data.Section
import com.izrecorder.transcribe.Transcriber
import java.io.File

/**
 * Принимает аудио через «Поделиться» / «Открыть с помощью» (например, голосовые из мессенджеров)
 * и кладёт его во «Входящие» на расшифровку. Своего окна не показывает.
 */
class ImportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = extractUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, "Нет аудио для импорта", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val source = referrer?.host?.let { appLabel(it) }
        val app = applicationContext
        Thread {
            val n = uris.count { runCatching { importAudio(app, it, source) }.getOrDefault(false) }
            if (n > 0) Transcriber.enqueue(app)
            runOnUiThread {
                val msg = if (n > 0) "Добавлено во «Входящие»: $n. Расшифровка начнётся автоматически."
                else "Не удалось импортировать аудио"
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
        finish()
    }

    private fun appLabel(pkg: String): String? = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun extractUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        )
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        else -> emptyList()
    }

    companion object {
        fun importAudio(context: android.content.Context, uri: Uri, source: String?): Boolean {
            val cr = context.contentResolver
            var name: String? = null
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
            val mime = cr.getType(uri)
            val ext = name?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 5 }
                ?: mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it.substringBefore(';')) }
                ?: "audio"
            val id = RecordingRepository.newId()
            val fileName = "$id.$ext"
            val dst = File(RecordingRepository.dir(Section.INBOX), fileName)
            val tmp = File(dst.path + ".tmp")
            cr.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: return false
            if (tmp.length() == 0L) {
                tmp.delete()
                return false
            }
            tmp.renameTo(dst)
            val title = listOfNotNull(source, name).joinToString(" · ").ifEmpty { "Аудио" }
            RecordingRepository.save(
                Recording(
                    id = id,
                    section = Section.INBOX,
                    audioFile = fileName,
                    createdAt = System.currentTimeMillis(),
                    title = title,
                )
            )
            return true
        }
    }
}
