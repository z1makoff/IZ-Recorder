@file:OptIn(ExperimentalMaterial3Api::class)

package com.izrecorder.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings as SysSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.izrecorder.Settings
import com.izrecorder.data.Recording
import com.izrecorder.data.RecordingRepository
import com.izrecorder.data.Section
import com.izrecorder.data.Status
import com.izrecorder.recorder.RecState
import com.izrecorder.recorder.RecorderController
import com.izrecorder.recorder.RecorderService
import com.izrecorder.transcribe.ModelManager
import com.izrecorder.transcribe.ModelState
import com.izrecorder.transcribe.Transcriber
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    tick: Int,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    requestPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val all by RecordingRepository.all.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    val section = if (tab == 0) Section.DICTAPHONE else Section.INBOX
    val items = all.filter { it.section == section }
    val settings by Settings.flow.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) Thread {
            val n = uris.count { runCatching { ImportActivity.importAudio(context, it, null) }.getOrDefault(false) }
            if (n > 0) Transcriber.enqueue(context)
        }.start()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("IZ Recorder") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Настройки") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SetupCard(tick, requestPermissions, onSettings) }
            item { RecordCard() }
            item {
                TabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, onClick = { tab = 0 }, text = {
                        Text("Диктофон (${all.count { it.section == Section.DICTAPHONE }})")
                    })
                    Tab(tab == 1, onClick = { tab = 1 }, text = {
                        Text("Входящие (${all.count { it.section == Section.INBOX }})")
                    })
                }
            }
            if (section == Section.INBOX) item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Сюда попадает аудио, которым вы поделились с IZ Recorder (например, голосовые " +
                            "из мессенджеров: «Поделиться» → IZ Recorder). " +
                            "Удаляется через ${settings.inboxRetentionDays} дн.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { picker.launch("audio/*") }) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Добавить аудиофайл")
                    }
                }
            }
            if (items.isEmpty()) item {
                Text(
                    if (section == Section.DICTAPHONE) "Записей пока нет" else "Пусто",
                    Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(items, key = { it.id }) { r ->
                RecordingRow(r) { onOpen(r.id) }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun RecordCard() {
    val context = LocalContext.current
    val state by RecorderService.state.collectAsState()
    val settings by Settings.flow.collectAsState()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state) {
        while (state is RecState.Recording) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val rec = state as? RecState.Recording
            if (rec != null) {
                val elapsed = now - rec.startedAt
                Text("Идёт запись", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(formatDuration(elapsed), style = MaterialTheme.typography.displaySmall)
                if (rec.limitMs > 0) {
                    Text(
                        "Автостоп через ${formatDuration((rec.limitMs - elapsed).coerceAtLeast(0))}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { RecorderController.stop(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Stop, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Остановить")
                }
            } else {
                Button(onClick = { RecorderController.start(context) }) {
                    Icon(Icons.Default.Mic, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Начать запись")
                }
                Spacer(Modifier.height(8.dp))
                val limit = if (settings.maxDurationMin > 0) "автостоп через ${settings.maxDurationMin} мин" else "без ограничения"
                Text(
                    if (settings.volumeTriggerEnabled)
                        "Или ${settings.pressCount} раз быстро нажмите любую кнопку громкости · $limit"
                    else limit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SetupCard(tick: Int, requestPermissions: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val problems = remember(tick) { setupProblems(context) }
    var modelState by remember { mutableStateOf(ModelManager.state(context)) }
    LaunchedEffect(tick) {
        while (true) {
            modelState = ModelManager.state(context)
            if (modelState is ModelState.Ready) break
            delay(1000)
        }
    }
    if (problems.isEmpty() && modelState is ModelState.Ready) return

    Card(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null)
                Spacer(Modifier.width(8.dp))
                Text("Нужно донастроить", fontWeight = FontWeight.Bold)
            }
            if (SetupProblem.PERMISSIONS in problems) {
                SetupItem("Разрешите микрофон и уведомления", "Разрешить", requestPermissions)
            }
            if (SetupProblem.VOLUME_SERVICE in problems) {
                SetupItem(
                    "Включите службу «IZ Recorder — кнопки громкости» в специальных возможностях. " +
                        "Если переключатель серый — сначала «Разрешить ограниченные настройки» (см. Настройки → Инструкция).",
                    "Открыть",
                ) { context.startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            when (val m = modelState) {
                is ModelState.Missing, is ModelState.Failed -> SetupItem(
                    "Скачайте модель распознавания (${Settings.current.model.sizeMb} МБ, один раз)" +
                        if (m is ModelState.Failed) " — ${m.reason}" else "",
                    "Скачать",
                ) { ModelManager.ensureDownload(context); modelState = ModelManager.state(context) }
                is ModelState.Downloading -> Column {
                    Text("Скачивается модель распознавания…")
                    if (m.total > 0) LinearProgressIndicator(
                        progress = { m.downloaded.toFloat() / m.total },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                ModelState.Ready -> Unit
            }
            TextButton(onClick = onSettings) { Text("Все настройки и инструкция") }
        }
    }
}

@Composable
private fun SetupItem(text: String, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Button(onClick = onClick) { Text(action) }
    }
}

enum class SetupProblem { PERMISSIONS, VOLUME_SERVICE }

fun setupProblems(context: Context): Set<SetupProblem> = buildSet {
    val mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val notif = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    if (!mic || !notif) add(SetupProblem.PERMISSIONS)
    if (Settings.current.volumeTriggerEnabled && !isVolumeServiceEnabled(context)) add(SetupProblem.VOLUME_SERVICE)
}

@Composable
private fun RecordingRow(r: Recording, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                r.title.ifEmpty { formatDate(r.createdAt) },
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (r.durationMs > 0) Text(formatDuration(r.durationMs), style = MaterialTheme.typography.bodySmall)
        }
        if (r.title.isNotEmpty()) Text(formatDate(r.createdAt), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        when (r.status) {
            Status.PENDING -> StatusText("В очереди на расшифровку")
            Status.TRANSCRIBING -> {
                StatusText("Расшифровка… ${(r.progress * 100).toInt()}%")
                LinearProgressIndicator(
                    progress = { r.progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
                )
            }
            Status.ERROR -> StatusText("Ошибка: ${r.error}", error = true)
            Status.DONE -> Unit
        }
        val text = r.text
        if (text.isNotEmpty()) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (r.status == Status.DONE) {
            StatusText("Речь не распознана")
        }
    }
}

@Composable
private fun StatusText(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

fun openAppDetails(context: Context) {
    context.startActivity(
        Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    )
}
