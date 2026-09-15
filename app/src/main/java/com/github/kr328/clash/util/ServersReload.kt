package com.github.kr328.clash.util

internal enum class ServersReload {
    Panel,
    SelectedGroup,
    Nothing,
}

internal fun serversReload(panelCurrent: Boolean, liveGroups: Boolean): ServersReload = when {
    !panelCurrent -> ServersReload.Panel
    liveGroups -> ServersReload.SelectedGroup
    else -> ServersReload.Nothing
}
