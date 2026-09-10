package com.github.kr328.clash.common.util

object TempArtifacts {
    const val MARK = ".extracting"

    fun pidOf(name: String): Int? {
        val at = name.indexOf(MARK)

        if (at < 0) return null

        val tail = name.drop(at + MARK.length)

        if (!tail.startsWith('.')) return null

        return tail.drop(1).toIntOrNull()?.takeIf { it > 0 }
    }

    fun deletable(
        name: String,
        ownPid: Int,
        writerAlive: Boolean?,
        lastModified: Long,
        now: Long,
        maxAge: Long,
    ): Boolean {
        if (!name.contains(MARK)) return false

        val pid = pidOf(name)

        if (pid == ownPid) return true

        if (pid == null || writerAlive == null) return now - lastModified > maxAge

        return !writerAlive
    }
}
