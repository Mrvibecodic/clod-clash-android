package com.github.kr328.clash.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun Uri.displayName(resolver: ContentResolver): String? {
    val named = withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(this@displayName, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null

                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

                if (index >= 0) cursor.getString(index) else null
            }
        }.getOrNull()
    }

    return (named ?: schemeSpecificPart.split("/").lastOrNull())?.takeIf { it.isNotBlank() }
}
