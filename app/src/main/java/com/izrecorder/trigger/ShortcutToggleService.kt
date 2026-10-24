package com.izrecorder.trigger

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.izrecorder.recorder.RecorderController

/**
 * Цель системной быстрой команды «зажать обе кнопки громкости».
 * Команда включает эту службу → мы переключаем запись и сразу выключаемся,
 * чтобы следующее удержание снова сработало.
 */
class ShortcutToggleService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        RecorderController.toggle(this)
        disableSelf()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
