package com.izrecorder.trigger

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.izrecorder.Settings
import com.izrecorder.recorder.RecorderController

/**
 * Служба специальных возможностей, которая следит только за кнопками громкости:
 * N быстрых нажатий любой из них (вверх или вниз, в любом сочетании) включает или выключает запись.
 *
 * Экран включён: нажатия приходят в [onKeyEvent]. Они не перехватываются — громкость меняется
 * как обычно, а после срабатывания возвращается к прежнему уровню.
 *
 * Экран выключен: Android не передаёт кнопки службам специальных возможностей, а отдаёт их
 * активному медиасеансу. Поэтому на время выключенного экрана служба включает свой «пустой»
 * медиасеанс (без звука и без уведомления) и считает нажатия через него. Если в этот момент
 * играет музыка или идёт звонок, сеанс не включается, чтобы не мешать регулировать их громкость.
 */
class VolumeKeyService : AccessibilityService() {

    private companion object {
        const val TAG = "VolumeKeyService"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var count = 0
    private var lastPress = 0L
    private var savedVolumes: Map<Int, Int> = emptyMap()
    private var session: MediaSession? = null

    private val trackedStreams = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_VOICE_CALL,
        AudioManager.STREAM_RING,
    )

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> activateScreenOffListening()
                Intent.ACTION_SCREEN_ON -> deactivateScreenOffListening()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            RECEIVER_NOT_EXPORTED,
        )
        // Служба могла включиться, когда экран уже погас.
        if (!getSystemService(PowerManager::class.java).isInteractive) activateScreenOffListening()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        onPress(event.eventTime, restoreVolume = true)
        return false
    }

    /** Одно нажатие любой кнопки громкости. [now] — по SystemClock.uptimeMillis(). */
    private fun onPress(now: Long, restoreVolume: Boolean) {
        val settings = Settings.current
        if (!settings.volumeTriggerEnabled) return

        if (count == 0 || now - lastPress > settings.pressGapMs) {
            count = 0
            savedVolumes = if (restoreVolume) snapshotVolumes() else emptyMap()
        }
        lastPress = now
        count++

        if (count >= settings.pressCount) {
            count = 0
            val toRestore = savedVolumes
            // Последнее нажатие система применит после нас — восстанавливаем громкость чуть позже.
            if (toRestore.isNotEmpty()) handler.postDelayed({ restoreVolumes(toRestore) }, 400)
            RecorderController.toggle(this)
        }
    }

    private fun activateScreenOffListening() {
        if (!Settings.current.volumeTriggerEnabled) return
        val am = getSystemService(AudioManager::class.java) ?: return
        // Музыка или звонок: кнопки должны регулировать их громкость, а не считаться нами.
        if (am.isMusicActive || am.mode == AudioManager.MODE_IN_CALL ||
            am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_RINGTONE
        ) return
        try {
            val s = session ?: MediaSession(this, TAG).also { session = it }
            s.setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    // direction = 0 — запрос «показать громкость», это не нажатие.
                    if (direction != 0) onPress(SystemClock.uptimeMillis(), restoreVolume = false)
                }
            })
            // Android отдаёт кнопки громкости только «играющему» сеансу. Звука при этом нет.
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build()
            )
            s.isActive = true
        } catch (e: Exception) {
            Log.w(TAG, "screen-off listening failed", e)
        }
    }

    private fun deactivateScreenOffListening() {
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
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

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        deactivateScreenOffListening()
        super.onDestroy()
    }
}
