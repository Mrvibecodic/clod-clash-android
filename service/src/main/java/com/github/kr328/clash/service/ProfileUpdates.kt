package com.github.kr328.clash.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.UpdateSchedule
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.readPanelInfo
import java.util.concurrent.TimeUnit

object ProfileUpdates {
    suspend fun scheduleAll(context: Context) {
        Log.i("Schedule all profiles update")

        ImportedDao().queryAllUUIDs()
            .mapNotNull { ImportedDao().queryByUUID(it) }
            .filter { it.type != Profile.Type.File }
            .forEach {
                schedule(context, it, ExistingPeriodicWorkPolicy.KEEP).await()
                scheduleExpiry(context, it)?.await()
            }
    }

    fun schedule(context: Context, imported: Imported, caller: Caller = Caller.Change) {
        schedule(context, imported, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)
        scheduleExpiry(context, imported, caller)
    }

    /** Кто переоценивает задачу загрузки после истечения — от этого зависит, что делать с прежней. */
    enum class Caller {
        /** Что угодно, кроме самой задачи: открытие приложения, сохранение свойств, другая загрузка. */
        Change,

        /** Сама задача после истечения, только что отработавшая. */
        ExpiryRun,
    }

    fun updateNow(context: Context, imported: Imported) {
        val request = OneTimeWorkRequestBuilder<ProfileUpdateWorker>()
            .setInputData(ProfileUpdateWorker.input(imported, ProfileUpdateWorker.Kind.Manual))
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(manualName(imported), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context, imported: Imported) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(periodicName(imported))
            cancelUniqueWork(manualName(imported))
            cancelUniqueWork(expiryName(imported))
        }
    }

    /**
     * clod: одноразовая загрузка после истечения срока подписки — чтобы перемена
     * (заглушки панели вместо узлов или, наоборот, продление) дошла до экрана
     * сразу, а не с очередным периодом. Ставится и при выключенном
     * автообновлении: это не опрос, а один запрос в названный панелью момент.
     *
     * Без constraints — как и периодическая: под нашим VPN с мёртвой подпиской
     * сеть может быть не validated, и задача с требованием сети не стартовала бы
     * никогда. WorkManager хранит задачу на диске, перезагрузку она переживает;
     * Doze может сдвинуть запуск до окна обслуживания.
     *
     * Провал mtime не двигает: задача повторяется с бэкоффом WorkManager без
     * сдачи, а раз она ждёт повтора — новая с нулевой задержкой её не сбивает
     * (KEEP), так что серия одна, сколько ни открывай приложение. Ждущую тот же
     * момент — не трогает (иначе каждое открытие приложения пересоздавало бы её
     * в базе WorkManager). Бегущую саму себя ([Caller.ExpiryRun]) — не
     * отменяет: снятая цель просто ничего не ставит, а новый срок (продление)
     * ставится следом за ней.
     *
     * null — делать нечего.
     */
    fun scheduleExpiry(context: Context, imported: Imported, caller: Caller = Caller.Change): Operation? {
        val manager = WorkManager.getInstance(context)
        val name = expiryName(imported)

        val skew = context.readPanelInfo(imported.uuid)?.clockSkewMillis() ?: 0
        val fetchAt = UpdateSchedule.expiryFetchAt(imported.expire, skew, configUpdatedAt(context, imported))
            ?: return if (caller == Caller.ExpiryRun) null else manager.cancelUniqueWork(name)

        val delay = (fetchAt - System.currentTimeMillis()).coerceAtLeast(0)

        if (manager.getWorkInfosForUniqueWork(name).get().any { it.waitsFor(fetchAt) })
            return null

        val request = OneTimeWorkRequestBuilder<ProfileUpdateWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, UpdateSchedule.MIN_INTERVAL, TimeUnit.MILLISECONDS)
            .setInputData(ProfileUpdateWorker.input(imported, ProfileUpdateWorker.Kind.Expiry))
            .build()

        val policy = when {
            // Срок уже наступил: что стоит в очереди или бежит (в том числе повтор
            // после провала), ничем не хуже нового запроса — не сбивать.
            delay == 0L -> ExistingWorkPolicy.KEEP
            // Новый срок из ответа самой задачи: встаёт за ней, а не вместо неё.
            caller == Caller.ExpiryRun -> ExistingWorkPolicy.APPEND_OR_REPLACE
            // Срок впереди и сдвинулся (продление): прежняя задача заменяется.
            else -> ExistingWorkPolicy.REPLACE
        }

        return manager.enqueueUniqueWork(name, policy, request)
    }

    /** Задача уже ждёт этот же момент (с точностью до дрожания поправки часов). */
    private fun WorkInfo.waitsFor(fetchAt: Long): Boolean =
        state == WorkInfo.State.ENQUEUED && runAttemptCount == 0 &&
            kotlin.math.abs(nextScheduleTimeMillis - fetchAt) <= UpdateSchedule.EXPIRY_SLACK

    private fun schedule(context: Context, imported: Imported, policy: ExistingPeriodicWorkPolicy): Operation {
        val manager = WorkManager.getInstance(context)

        if (imported.interval < UpdateSchedule.MIN_INTERVAL)
            return manager.cancelUniqueWork(periodicName(imported))

        val delay = UpdateSchedule.firstDelay(imported.interval, configUpdatedAt(context, imported), System.currentTimeMillis())

        val request = PeriodicWorkRequestBuilder<ProfileUpdateWorker>(imported.interval, TimeUnit.MILLISECONDS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, UpdateSchedule.MIN_INTERVAL, TimeUnit.MILLISECONDS)
            .setInputData(ProfileUpdateWorker.input(imported, ProfileUpdateWorker.Kind.Periodic))
            .build()

        return manager.enqueueUniquePeriodicWork(periodicName(imported), policy, request)
    }

    /** Когда подписку загружали в последний раз — mtime её `config.yaml`; 0, если ни разу. */
    private fun configUpdatedAt(context: Context, imported: Imported): Long =
        context.importedDir
            .resolve(imported.uuid.toString())
            .resolve("config.yaml")
            .lastModified()

    private fun periodicName(imported: Imported) = "profile-update-${imported.uuid}"

    private fun manualName(imported: Imported) = "profile-update-now-${imported.uuid}"

    private fun expiryName(imported: Imported) = "profile-update-expiry-${imported.uuid}"
}
