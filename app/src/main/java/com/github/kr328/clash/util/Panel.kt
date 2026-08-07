package com.github.kr328.clash.util

import android.content.Context
import com.github.kr328.clash.service.model.PanelInfo
import com.github.kr328.clash.service.util.readPanelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * `panel.json` профиля с фонового потока.
 *
 * Сам разбор живёт в модуле службы: то же самое нужно ей самой — заголовок
 * шторки и уведомления об обновлении подписки берут название оттуда же.
 */
suspend fun Context.queryPanelInfo(uuid: UUID): PanelInfo? = withContext(Dispatchers.IO) {
    readPanelInfo(uuid)
}
