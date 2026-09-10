package com.github.kr328.clash.service

import java.util.UUID

object UpdateOutcome {
    enum class Kind {
        Success,
        Partial,
        Failure,
    }

    data class Plan(val kind: Kind, val error: Boolean)

    fun plan(partial: Boolean, notifyErrors: Boolean, notifyUpdates: Boolean): Plan? = when {
        partial && notifyErrors -> Plan(Kind.Partial, error = true)
        notifyUpdates -> Plan(if (partial) Kind.Partial else Kind.Success, error = false)
        else -> null
    }

    fun id(uuid: UUID, kind: Kind): Int = when (kind) {
        Kind.Partial -> uuid.hashCode() xor 1
        else -> uuid.hashCode()
    }

    fun replaced(uuid: UUID, kind: Kind): List<Int> =
        Kind.entries.filter { it != kind }.map { id(uuid, it) }.distinct() - id(uuid, kind)
}
