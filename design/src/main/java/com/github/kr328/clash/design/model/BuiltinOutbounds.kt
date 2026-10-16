package com.github.kr328.clash.design.model

// Встроенные выходы ядра: в группах они стоят рядом с серверами, но серверами
// не считаются — ни в счёте, ни в замере, ни как выбор вместо скрытого
val BUILTIN_OUTBOUNDS = setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE")
