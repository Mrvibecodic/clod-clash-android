package com.github.kr328.clash.design.model

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService

enum class DarkMode {
    Auto, ForceLight, ForceDark;

    // Стартовое окно рисует система из манифестной темы до запуска процесса,
    // поэтому выбор человека надо сообщить ей: с API 31 она хранит его сама
    // и подкладывает в стартовое окно. Ниже API 31 канала нет.
    fun applyToSystem(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val mode = when (this) {
            Auto -> UiModeManager.MODE_NIGHT_AUTO
            ForceLight -> UiModeManager.MODE_NIGHT_NO
            ForceDark -> UiModeManager.MODE_NIGHT_YES
        }

        context.getSystemService<UiModeManager>()?.setApplicationNightMode(mode)
    }
}
