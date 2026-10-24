@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.izrecorder.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.Settings as SysSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.izrecorder.Feedback
import com.izrecorder.Settings
import com.izrecorder.WhisperModel
import com.izrecorder.transcribe.ModelManager
import com.izrecorder.transcribe.ModelState
import com.izrecorder.transcribe.Transcriber
import com.izrecorder.trigger.ShortcutToggleService
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(tick: Int, requestPermissions: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val s by Settings.flow.collectAsState()
    val problems = remember(tick) { setupProblems(context) }
    val shortcutEnabled = remember(tick) { isAccessibilityServiceEnabled(context, ShortcutToggleService::class.java) }
    val batteryOk = remember(tick) { isIgnoringBatteryOptimizations(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header("Запуск кнопками громкости")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Включать/выключать запись нажатиями", Modifier.weight(1f))
                Switch(s.volumeTriggerEnabled, { v -> Settings.update { it.copy(volumeTriggerEnabled = v) } })
            }
            SliderSetting(
                "Количество нажатий: ${s.pressCount}",
                "Любая кнопка громкости — вверх или вниз, в любом сочетании.",
                s.pressCount.toFloat(), 3f..10f, steps = 6,
            ) { v -> Settings.update { it.copy(pressCount = v.roundToInt()) } }
            SliderSetting(
                "Макс. пауза между нажатиями: ${s.pressGapMs} мс",
                "Если нажатия не срабатывают — увеличьте; если срабатывает случайно — уменьшите.",
                s.pressGapMs.toFloat(), 300f..1200f, steps = 8,
            ) { v -> Settings.update { it.copy(pressGapMs = (v / 100).roundToInt() * 100) } }
            StatusLine(
                if (SetupProblem.VOLUME_SERVICE in problems) "Служба «кнопки громкости» выключена" else "Служба «кнопки громкости» включена",
                ok = SetupProblem.VOLUME_SERVICE !in problems,
            )

            HorizontalDivider()
            Header("Длительность записи")
            ChoiceChips(
                listOf(5, 10, 15, 30, 45, 60, 90, 120, 180, 0),
                s.maxDurationMin,
                label = { if (it == 0) "Без лимита" else if (it < 60 || it % 60 != 0) "$it мин" else "${it / 60} ч" },
            ) { v -> Settings.update { it.copy(maxDurationMin = v) } }

            HorizontalDivider()
            Header("Вибрация")
            SliderSetting(
                "Начало записи: ${"%.2f".format(s.startVibrationMs / 1000f)} с",
                "Одна непрерывная вибрация. 0 — без вибрации.",
                s.startVibrationMs.toFloat(), 0f..5000f, steps = 19,
            ) { v -> Settings.update { it.copy(startVibrationMs = (v / 250).roundToInt() * 250) } }
            OutlinedButton(onClick = { Feedback.started(context) }) { Text("Проверить «начало»") }
            SliderSetting(
                "Конец записи: 2 × ${"%.1f".format(s.stopVibrationMs / 1000f)} с",
                "Две вибрации подряд. 0 — без вибрации.",
                s.stopVibrationMs.toFloat(), 0f..3000f, steps = 29,
            ) { v -> Settings.update { it.copy(stopVibrationMs = (v / 100).roundToInt() * 100) } }
            OutlinedButton(onClick = { Feedback.stopped(context) }) { Text("Проверить «конец»") }
            Text(
                "Три короткие вибрации означают ошибку: запись не началась (подробности — в уведомлении).",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Header("Хранение «Входящих»")
            ChoiceChips(listOf(7, 14, 30, 60, 90), s.inboxRetentionDays, label = { "$it дн." }) { v ->
                Settings.update { it.copy(inboxRetentionDays = v) }
            }
            Text("Записи раздела «Диктофон» не удаляются никогда.", style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            Header("Расшифровка")
            Text("Язык")
            ChoiceChips(listOf("auto", "en", "ru"), s.language, label = {
                when (it) { "en" -> "Английский"; "ru" -> "Русский"; else -> "Авто (EN/RU)" }
            }) { v -> Settings.update { it.copy(language = v) } }
            Text("Модель (работает на телефоне, без интернета)")
            WhisperModel.entries.forEach { m ->
                Row(
                    Modifier.fillMaxWidth().selectable(s.model == m) {
                        Settings.update { it.copy(model = m) }
                        Transcriber.enqueue(context)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.model == m, onClick = null)
                    Text("${m.title}, ~${m.sizeMb} МБ", Modifier.padding(start = 8.dp))
                }
            }
            ModelStatus()

            HorizontalDivider()
            Header("Системные разрешения")
            StatusLine("Микрофон и уведомления", SetupProblem.PERMISSIONS !in problems)
            if (SetupProblem.PERMISSIONS in problems) Button(onClick = requestPermissions) { Text("Разрешить") }
            StatusLine("Без ограничений батареи", batteryOk)
            if (!batteryOk) Button(onClick = { requestIgnoreBattery(context) }) { Text("Снять ограничение") }
            StatusLine(
                if (shortcutEnabled) "Быстрая команда: служба сейчас включена (нормально во время срабатывания)"
                else "Быстрая команда «зажать обе кнопки громкости» — настраивается в спец. возможностях",
                ok = true,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { context.startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                    Text("Спец. возможности")
                }
                OutlinedButton(onClick = { openAppDetails(context) }) { Text("О приложении") }
            }

            HorizontalDivider()
            Header("Инструкция")
            Text(instructions(s.pressCount), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ModelStatus() {
    val context = LocalContext.current
    val model = Settings.flow.collectAsState().value.model
    var state by remember(model) { mutableStateOf(ModelManager.state(context, model)) }
    LaunchedEffect(model) {
        while (true) {
            state = ModelManager.state(context, model)
            if (state is ModelState.Ready) break
            delay(1000)
        }
    }
    when (val st = state) {
        ModelState.Ready -> StatusLine("Модель скачана", true)
        is ModelState.Downloading -> Text(
            "Скачивается: ${st.downloaded / 1_048_576} / ${if (st.total > 0) st.total / 1_048_576 else "?"} МБ"
        )
        is ModelState.Missing, is ModelState.Failed -> Column {
            if (st is ModelState.Failed) Text("Ошибка: ${st.reason}", color = MaterialTheme.colorScheme.error)
            Button(onClick = { ModelManager.ensureDownload(context, model); state = ModelManager.state(context, model) }) {
                Text("Скачать модель")
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun StatusLine(text: String, ok: Boolean) {
    Text(
        (if (ok) "✓ " else "✗ ") + text,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun SliderSetting(
    title: String, hint: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(title)
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
        Text(hint, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun <T> ChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
        }
    }
}

@SuppressLint("BatteryLife")
private fun requestIgnoreBattery(context: android.content.Context) {
    context.startActivity(
        Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    )
}

private fun instructions(pressCount: Int) = """
1. Разрешите микрофон и уведомления (кнопка выше).

2. Включите службу «IZ Recorder — кнопки громкости»:
Настройки телефона → Спец. возможности → Скачанные приложения → IZ Recorder — кнопки громкости → Вкл.
Если переключатель серый и пишет «Ограниченная настройка»: Настройки → Приложения → IZ Recorder → ⋮ (вверху справа) → «Разрешить ограниченные настройки», затем повторите.

3. (Необязательно) Быстрая команда «зажать обе кнопки громкости ~3 секунды»:
Спец. возможности → «IZ Recorder — удержание громкости» → включите «Быстрый запуск» и выберите «Удерживать кнопки громкости». Разрешите использование на заблокированном экране. Саму службу включать не нужно — команда включает её на мгновение и она сразу выключается.

4. Снимите ограничение батареи (кнопка выше), чтобы Android не выгружал приложение.

5. Скачайте модель распознавания (один раз, лучше по Wi‑Fi).

Как пользоваться: $pressCount быстрых нажатий любой кнопки громкости — длинная вибрация, запись пошла. Ещё столько же нажатий — две вибрации, запись остановлена и уходит на расшифровку. Три коротких вибрации — что-то пошло не так.

Пока идёт запись, Android всегда показывает уведомление и зелёную точку микрофона — это требование системы.

Голосовые из мессенджеров: долгое нажатие на сообщение → «Поделиться» → IZ Recorder. Они попадут во «Входящие».

Во время телефонного звонка Android не даёт другим приложениям слышать микрофон — запись будет пустой.
""".trimIndent()
