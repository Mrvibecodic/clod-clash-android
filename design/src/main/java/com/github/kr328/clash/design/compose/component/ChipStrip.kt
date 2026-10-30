package com.github.kr328.clash.design.compose.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll

private object KeepHorizontalDrag : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput) Offset(available.x, 0f) else Offset.Zero
}

// Свайп по полосе крутит только её и не листает вкладки
fun Modifier.chipStripScroll(state: ScrollState): Modifier =
    nestedScroll(KeepHorizontalDrag).horizontalScroll(state)
