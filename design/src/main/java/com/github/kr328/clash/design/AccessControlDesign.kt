package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.kr328.clash.design.compose.screen.AccessControlAction
import com.github.kr328.clash.design.compose.screen.AccessControlScreen
import com.github.kr328.clash.design.compose.screen.AccessControlState
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AccessControlDesign(
    context: Context,
    private val uiStore: UiStore,
    private val srvStore: ServiceStore,
    private val selected: MutableSet<String>,
    includeFromProfile: Set<String> = emptySet(),
    excludeFromProfile: Set<String> = emptySet(),
) : Design<AccessControlDesign.Request>(context) {
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
            includeFromProfile = includeFromProfile,
            excludeFromProfile = excludeFromProfile,
        ),
    )

    val apps: List<AppInfo>
        get() = state.apps

    override val root: View = composeRoot {
        AccessControlScreen(state = state, onAction = ::onAction)
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
