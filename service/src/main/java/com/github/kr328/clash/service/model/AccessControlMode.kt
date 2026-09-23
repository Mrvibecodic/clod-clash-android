package com.github.kr328.clash.service.model

enum class AccessControlMode {
    AcceptAll, AcceptSelected, DenySelected
}

fun accessControlFingerprint(mode: AccessControlMode, packages: Set<String>): String =
    if (mode == AccessControlMode.AcceptAll) {
        mode.name
    } else {
        mode.name + ":" + packages.sorted().joinToString(",")
    }
