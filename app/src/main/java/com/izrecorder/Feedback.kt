package com.izrecorder

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager

/** Вибро-сигналы, по которым понятно, что произошло, не доставая телефон. */
object Feedback {
    // Категория «будильник»: вибрация срабатывает и в беззвучном режиме.
    private val attrs = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)

    private fun vibrate(context: Context, effect: VibrationEffect) {
        val vm = context.getSystemService(VibratorManager::class.java) ?: return
        vm.defaultVibrator.vibrate(effect, attrs)
    }

    /** Запись началась: одна длинная вибрация. */
    fun started(context: Context) {
        val ms = Settings.current.startVibrationMs
        if (ms > 0) vibrate(context, VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** Запись остановлена: две вибрации. */
    fun stopped(context: Context) {
        val ms = Settings.current.stopVibrationMs.toLong()
        if (ms > 0) vibrate(context, VibrationEffect.createWaveform(longArrayOf(0, ms, 300, ms), -1))
    }

    /** Ошибка: три коротких. */
    fun error(context: Context) {
        vibrate(context, VibrationEffect.createWaveform(longArrayOf(0, 150, 150, 150, 150, 150), -1))
    }
}
