package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.compose.component.NoticeHost
import com.github.kr328.clash.design.compose.component.NoticeState
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.ui.ToastDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

abstract class Design<R>(val context: Context) :
    CoroutineScope by CoroutineScope(Dispatchers.Unconfined) {
    abstract val root: View

    val requests: Channel<R> = Channel(Channel.UNLIMITED)

    val notices: NoticeState = NoticeState()

    private val designUiStore by lazy { UiStore(context) }

    protected fun composeRoot(
        noticeInset: Dp = 0.dp,
        content: @Composable () -> Unit,
    ): View = ComposeView(context).apply {
        setContent {
            val darkTheme = when (designUiStore.darkMode) {
                DarkMode.Auto -> isSystemInDarkTheme()
                DarkMode.ForceLight -> false
                DarkMode.ForceDark -> true
            }

            ClodClashTheme(darkTheme = darkTheme) {
                Box(modifier = Modifier.fillMaxSize()) {
                    content()

                    NoticeHost(
                        state = notices,
                        bottomInset = noticeInset,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .imePadding(),
                    )
                }
            }
        }
    }

    suspend fun showToast(
        resId: Int,
        duration: ToastDuration,
        detail: String? = null,
        actionLabel: Int? = null,
        onAction: (() -> Unit)? = null,
    ) {
        return showToast(
            message = context.getString(resId),
            duration = duration,
            detail = detail,
            actionLabel = actionLabel?.let { context.getString(it) },
            onAction = onAction,
        )
    }

    suspend fun showToast(
        message: CharSequence,
        duration: ToastDuration,
        detail: String? = null,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        withContext(Dispatchers.Main) {
            notices.show(
                text = message.toString(),
                longDuration = duration != ToastDuration.Short,
                detail = detail,
                actionLabel = actionLabel,
                onAction = onAction,
            )
        }
    }
}
