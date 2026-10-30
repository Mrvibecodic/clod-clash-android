package com.github.kr328.clash.service

import java.util.UUID
import java.util.concurrent.TimeUnit

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

    fun ids(uuid: UUID): List<Int> = Kind.entries.map { id(uuid, it) }.distinct()

    fun replaced(uuid: UUID, kind: Kind): List<Int> = ids(uuid) - id(uuid, kind)

    // Ручное обновление человек ждёт на экране — без пауз и повторов.
    fun waitsForNetwork(kind: ProfileUpdateWorker.Kind): Boolean = kind != ProfileUpdateWorker.Kind.Manual

    // Ещё одна попытка в той же задаче — только после быстрого сбоя сети: так
    // кончается попытка в миг пробуждения, а долгая уже съела свой бюджет и с
    // повтором вышла бы за предел задачи WorkManager. [others] — обновления других
    // подписок сейчас: загрузки идут по одной, и повтор встал бы в очередь за
    // чужой загрузкой неизвестной длины.
    fun retriesAtOnce(kind: ProfileUpdateWorker.Kind, temporary: Boolean, spent: Long, others: Int): Boolean =
        waitsForNetwork(kind) && temporary && spent < QUICK_FAILURE && others == 0

    val QUICK_FAILURE: Long = TimeUnit.SECONDS.toMillis(60)

    // Сбой сети на первой попытке периода, за которой будет повтор, не объявляем.
    // Повторы после истечения срока идут без сдачи, и шуметь о каждом — значит
    // слать «не удалось обновить» вечно раз в 5 ч: объявляется первая попытка серии.
    fun notifiesFailure(kind: ProfileUpdateWorker.Kind, attempt: Int, temporary: Boolean, retrying: Boolean): Boolean =
        when (kind) {
            ProfileUpdateWorker.Kind.Manual -> true
            ProfileUpdateWorker.Kind.Periodic -> !(temporary && retrying && attempt == 0)
            ProfileUpdateWorker.Kind.Expiry -> attempt == 0
        }
}
