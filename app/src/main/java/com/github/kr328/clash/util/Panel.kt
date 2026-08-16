package com.github.kr328.clash.util

import android.content.Context
import com.github.kr328.clash.service.model.PanelInfo
import com.github.kr328.clash.service.util.readPanelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

suspend fun Context.queryPanelInfo(uuid: UUID): PanelInfo? = withContext(Dispatchers.IO) {
    readPanelInfo(uuid)
}
