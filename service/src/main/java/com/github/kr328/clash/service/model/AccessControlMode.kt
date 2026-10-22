package com.github.kr328.clash.service.model

enum class AccessControlMode {
    AcceptAll, AcceptSelected, DenySelected
}

// Режим провайдера (clod-app-mode: all) действует только вместо нашего
// умолчания — «Разрешить выбранные» без единой отметки.
fun AccessControlMode.effective(nothingSelected: Boolean, providerAllApps: Boolean): AccessControlMode =
    if (this == AccessControlMode.AcceptSelected && nothingSelected && providerAllApps) AccessControlMode.AcceptAll else this

fun accessControlFingerprint(mode: AccessControlMode, packages: Set<String>): String =
    if (mode == AccessControlMode.AcceptAll) {
        mode.name
    } else {
        mode.name + ":" + packages.sorted().joinToString(",")
    }
