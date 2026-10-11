package com.github.kr328.clash.log

// Строки, не вместившиеся в очередь записи, пока она стояла (ужатие файла, поток
// строк быстрее диска): их число едет вместе со следующей принятой строкой, а
// не отдельным элементом — иначе отметка занимала бы место строки
class OverflowGap {
    private var dropped = 0

    // send получает число потерянных перед этой строкой; true — принята.
    // Возвращает, началась ли этой строкой новая серия потерь
    @Synchronized
    fun offer(send: (dropped: Int) -> Boolean): Boolean {
        if (send(dropped)) {
            dropped = 0

            return false
        }

        return dropped++ == 0
    }
}
