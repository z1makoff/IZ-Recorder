@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.izrecorder.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.izrecorder.data.Recording
import com.izrecorder.data.RecordingRepository
import com.izrecorder.data.Section
import com.izrecorder.data.Status
import com.izrecorder.transcribe.Transcriber
import kotlinx.coroutines.delay

@Composable
fun DetailScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val all by RecordingRepository.all.collectAsState()
    val r = all.firstOrNull { it.id == id }
    if (r == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val player = remember(r.audioFile, r.section) {
        runCatching {
            MediaPlayer().apply {
                setDataSource(RecordingRepository.audioFile(r).absolutePath)
                prepare()
            }
        }.getOrNull()
    }
    DisposableEffect(player) { onDispose { player?.release() } }

    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }
    val duration = player?.duration?.takeIf { it > 0 } ?: r.durationMs.toInt()

    LaunchedEffect(player, playing) {
        while (playing && player != null) {
            position = player.currentPosition
            if (!player.isPlaying) playing = false
            delay(200)
        }
    }
    fun play() {
        player ?: return
        runCatching { player.playbackParams = player.playbackParams.setSpeed(speed) }
        player.start()
        playing = true
    }
    fun pause() {
        player?.pause()
        playing = false
    }
    fun seek(ms: Long) {
        player ?: return
        player.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
        position = ms.toInt()
    }

    var confirmDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(r.title.ifEmpty { formatDate(r.createdAt) }, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                actions = {
                    IconButton(onClick = { copyText(context, r) }) { Icon(Icons.Default.ContentCopy, "Копировать текст") }
                    IconButton(onClick = { shareText(context, r) }) { Icon(Icons.Default.Share, "Поделиться текстом") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Удалить") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Плеер
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    formatDate(r.createdAt) + if (r.language.isNotEmpty()) " · язык: ${r.language}" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (player == null) {
                    Text("Не удалось открыть аудио", color = MaterialTheme.colorScheme.error)
                } else {
                    Slider(
                        value = position.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                        onValueChange = { seek(it.toLong()) },
                        valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${formatDuration(position.toLong())} / ${formatDuration(duration.toLong())}")
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { seek((position - 10_000L).coerceAtLeast(0)) }) {
                            Icon(Icons.Default.Replay10, "Назад на 10 секунд")
                        }
                        FilledIconButton(onClick = { if (playing) pause() else play() }) {
                            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Воспроизвести")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.5f, 0.75f, 1f, 1.25f).forEach { s ->
                            FilterChip(
                                selected = speed == s,
                                onClick = {
                                    speed = s
                                    if (playing) runCatching { player.playbackParams = player.playbackParams.setSpeed(s) }
                                },
                                label = { Text("${s}x") },
                            )
                        }
                    }
                }
                when (r.status) {
                    Status.PENDING -> Text("В очереди на расшифровку", color = MaterialTheme.colorScheme.primary)
                    Status.TRANSCRIBING -> {
                        Text("Расшифровка… ${(r.progress * 100).toInt()}%", color = MaterialTheme.colorScheme.primary)
                        LinearProgressIndicator(progress = { r.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    Status.ERROR -> Text("Ошибка расшифровки: ${r.error}", color = MaterialTheme.colorScheme.error)
                    Status.DONE -> if (r.segments.isEmpty()) Text("Речь не распознана")
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r.section == Section.INBOX) {
                        OutlinedButton(onClick = {
                            RecordingRepository.moveTo(r, Section.DICTAPHONE)
                            Toast.makeText(context, "Перенесено в «Диктофон» — не будет удаляться", Toast.LENGTH_SHORT).show()
                        }) { Text("Хранить всегда") }
                    }
                    if (r.status == Status.DONE || r.status == Status.ERROR) {
                        OutlinedButton(onClick = { Transcriber.retranscribe(context, r) }) { Text("Расшифровать заново") }
                    }
                }
            }

            // Текст: нажатие на фразу — перемотка к ней
            val current = r.segments.indexOfLast { it.startMs <= position }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                itemsIndexed(r.segments) { index, seg ->
                    val active = playing && index == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                            .clickable { seek(seg.startMs); play() }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            formatDuration(seg.startMs),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(seg.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            LaunchedEffect(current, playing) {
                if (playing && current >= 0) listState.animateScrollToItem((current - 1).coerceAtLeast(0))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить запись?") },
            text = { Text("Аудио и расшифровка будут удалены без возможности восстановления.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    player?.let { if (playing) it.pause() }
                    RecordingRepository.delete(r)
                    onBack()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

private fun transcriptText(r: Recording) =
    r.segments.joinToString("\n") { "[${formatDuration(it.startMs)}] ${it.text}" }

private fun copyText(context: android.content.Context, r: Recording) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("Расшифровка", r.text))
    Toast.makeText(context, "Текст скопирован", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: android.content.Context, r: Recording) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, transcriptText(r))
    context.startActivity(Intent.createChooser(send, "Поделиться расшифровкой"))
}
