package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.github.kr328.clash.design.compose.screen.AccessControlAction
import com.github.kr328.clash.design.compose.screen.AccessControlScreen
import com.github.kr328.clash.design.compose.screen.AccessControlState
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Экран «Приложения» — какие приложения пускать в туннель.
 *
 * @param selected НАСТОЯЩИЙ набор выбранного: активити сохраняет его в
 *   настройки при уходе с экрана и по изменению перезапускает ядро. Экран
 *   правит его на месте, а Compose перерисовывает по снимку в состоянии —
 *   на самом изменяемом множестве галочки не двигались бы вовсе.
 */
class AccessControlDesign(
    context: Context,
    private val uiStore: UiStore,
    private val srvStore: ServiceStore,
    private val selected: MutableSet<String>,
) : Design<AccessControlDesign.Request>(context) {
    /** Порядок важен: экран отдаёт индекс, в хранилище лежит значение. */
    private val modes = AccessControlMode.entries

    enum class Request {
        Back,
        ReloadApps,
        SelectAll,
        SelectNone,
        SelectInvert,
        Import,
        Export,
    }

    private var state by mutableStateOf(
        AccessControlState(
            selected = selected.toSet(),
            sort = uiStore.accessControlSort,
            reverse = uiStore.accessControlReverse,
            systemApps = uiStore.accessControlSystemApp,
            mode = modes.indexOf(srvStore.accessControlMode).coerceAtLeast(0),
        ),
    )

    val apps: List<AppInfo>
        get() = state.apps

    override val root: View = ComposeView(context).apply {
        setContent {
            ClodClashTheme {
                AccessControlScreen(state = state, onAction = ::onAction)
            }
        }
    }

    private fun onAction(action: AccessControlAction) {
        when (action) {
            AccessControlAction.Back -> request(Request.Back)
            AccessControlAction.SelectAll -> request(Request.SelectAll)
            AccessControlAction.SelectNone -> request(Request.SelectNone)
            AccessControlAction.SelectInvert -> request(Request.SelectInvert)
            AccessControlAction.Import -> request(Request.Import)
            AccessControlAction.Export -> request(Request.Export)
            is AccessControlAction.Toggle -> {
                if (!selected.remove(action.packageName)) {
                    selected.add(action.packageName)
                }

                syncSelected()
            }
            is AccessControlAction.Search -> {
                // Уход из поиска стирает слово: вернуться к отфильтрованному
                // списку через закрытый поиск было бы нечем.
                state = if (action.enabled) {
                    state.copy(searching = true)
                } else {
                    state.copy(searching = false, query = "")
                }
            }
            is AccessControlAction.Query -> state = state.copy(query = action.value)
            is AccessControlAction.Mode -> {
                val mode = modes.getOrNull(action.index) ?: return

                srvStore.accessControlMode = mode

                state = state.copy(mode = action.index)
            }
            is AccessControlAction.Sort -> {
                uiStore.accessControlSort = action.value
                state = state.copy(sort = action.value, loaded = false)

                request(Request.ReloadApps)
            }
            is AccessControlAction.Reverse -> {
                uiStore.accessControlReverse = action.value
                state = state.copy(reverse = action.value, loaded = false)

                request(Request.ReloadApps)
            }
            is AccessControlAction.SystemApps -> {
                uiStore.accessControlSystemApp = action.value
                state = state.copy(systemApps = action.value, loaded = false)

                request(Request.ReloadApps)
            }
        }
    }

    suspend fun patchApps(apps: List<AppInfo>) {
        withContext(Dispatchers.Main) {
            state = state.copy(apps = apps, loaded = true)
        }
    }

    /**
     * Показать то, что сейчас в наборе.
     *
     * Зовётся после того, как набор поменяла активити (выделить всё, снять всё,
     * инвертировать, вставить из буфера) — она правит `selected` на месте,
     * и без снимка экран об этом не узнает.
     */
    suspend fun rebindAll() {
        withContext(Dispatchers.Main) {
            syncSelected()
        }
    }

    private fun syncSelected() {
        state = state.copy(selected = selected.toSet())
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}
