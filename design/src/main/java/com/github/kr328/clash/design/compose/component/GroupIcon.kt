package com.github.kr328.clash.design.compose.component

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.util.GroupIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun rememberGroupIcon(url: String?): ImageBitmap? {
    val context = LocalContext.current
    val target = url?.takeIf { it.isNotBlank() }

    return produceState<ImageBitmap?>(initialValue = null, target) {
        value = target?.let {
            withContext(Dispatchers.IO) { GroupIcons.load(context, it) }
        }
    }.value
}

@Composable
fun GroupIcon(url: String?, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val bitmap = rememberGroupIcon(url) ?: return

    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size),
    )
}
