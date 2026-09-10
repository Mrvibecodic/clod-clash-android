package com.github.kr328.clash.log

object CrashLogClip {
    data class Clipped(val head: List<String>, val dropped: Int, val tail: List<String>)

    fun clip(
        lines: Sequence<String>,
        headLines: Int,
        headChars: Int,
        tailLines: Int,
        tailChars: Int,
    ): Clipped {
        val head = ArrayList<String>()
        val tail = ArrayDeque<String>()

        var headSize = 0
        var tailSize = 0
        var dropped = 0

        for (line in lines) {
            if (head.size < headLines && (headSize == 0 || headSize + line.length + 1 <= headChars)) {
                head.add(line)

                headSize += line.length + 1

                continue
            }

            tail.addLast(line)

            tailSize += line.length + 1

            while (tail.size > tailLines || tailSize > tailChars) {
                tailSize -= tail.removeFirst().length + 1

                dropped += 1
            }
        }

        return Clipped(head, dropped, tail.toList())
    }
}
