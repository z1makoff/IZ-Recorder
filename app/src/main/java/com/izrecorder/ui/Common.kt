package com.izrecorder.ui

import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings as SysSettings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.izrecorder.trigger.VolumeKeyService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun IZTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatDate(ts: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.forLanguageTag("ru")).format(Date(ts))

fun isAccessibilityServiceEnabled(context: Context, cls: Class<*>): Boolean {
    val enabled = SysSettings.Secure.getString(
        context.contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val name = ComponentName(context, cls)
    return enabled.split(':').any {
        ComponentName.unflattenFromString(it) == name
    }
}

fun isVolumeServiceEnabled(context: Context) = isAccessibilityServiceEnabled(context, VolumeKeyService::class.java)

fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
