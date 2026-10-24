package com.izrecorder.trigger

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.izrecorder.Settings
import com.izrecorder.recorder.RecorderController

/**
 * Служба специальных возможностей, которая следит только за кнопками громкости:
 * N быстрых нажатий любой из них (вверх или вниз, в любом сочетании) включает или выключает запись.
 * Сами нажатия не перехватываются — громкость меняется как обычно, а после срабатывания
 * возвращается к прежнему уровню.
 */
class VolumeKeyService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var count = 0
    private var lastPress = 0L
    private var savedVolumes: Map<Int, Int> = emptyMap()

    private val trackedStreams = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_VOICE_CALL,
        AudioManager.STREAM_RING,
    )

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val settings = Settings.current
        if (!settings.volumeTriggerEnabled) return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false

        val now = event.eventTime
        if (count == 0 || now - lastPress > settings.pressGapMs) {
            count = 0
            savedVolumes = snapshotVolumes()
        }
        lastPress = now
        count++

        if (count >= settings.pressCount) {
            count = 0
            val toRestore = savedVolumes
            // Последнее нажатие система применит после нас — восстанавливаем громкость чуть позже.
            handler.postDelayed({ restoreVolumes(toRestore) }, 400)
            RecorderController.toggle(this)
        }
        return false
    }

    private fun snapshotVolumes(): Map<Int, Int> {
        val am = getSystemService(AudioManager::class.java) ?: return emptyMap()
        return trackedStreams.associateWith { am.getStreamVolume(it) }
    }

    private fun restoreVolumes(volumes: Map<Int, Int>) {
        val am = getSystemService(AudioManager::class.java) ?: return
        for ((stream, volume) in volumes) {
            if (am.getStreamVolume(stream) != volume) {
                // Может не сработать, например, при «Не беспокоить» — это не критично.
                runCatching { am.setStreamVolume(stream, volume, 0) }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
