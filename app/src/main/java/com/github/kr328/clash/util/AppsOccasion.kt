package com.github.kr328.clash.util

// Повод показать лист о приложениях (Главная стала видна, служба запустилась)
// ждёт расчёта, дошедшего до экрана. Расчёт забирает повод до начала: пришедший,
// пока он шёл, остаётся следующему; не дошёл до экрана — повод возвращается.
// null — расчёта никто не ждёт; false — ждёт, но лист остаётся каким был.
internal class AppsOccasion {
    private var owed: Boolean? = null

    fun owe(opportunity: Boolean) {
        owed = owed == true || opportunity
    }

    fun take(): Boolean? = owed.also { owed = null }

    fun giveBack(taken: Boolean?) {
        if (taken != null) owe(taken)
    }
}
