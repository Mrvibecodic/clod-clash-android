package com.github.kr328.clash.util

object SelectionOps {
    enum class Op { SelectAll, SelectNone, Invert }

    fun apply(
        op: Op,
        selected: Set<String>,
        visible: Set<String>,
    ): Set<String> = when (op) {
        Op.SelectAll -> selected + visible
        Op.SelectNone -> selected - visible
        Op.Invert -> (selected - visible) + (visible - selected)
    }
}
