package com.github.kr328.clash.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.UpdateSchedule
import com.github.kr328.clash.service.util.importedDir
import java.util.concurrent.TimeUnit

object ProfileUpdates {
    suspend fun scheduleAll(context: Context) {
        Log.i("Schedule all profiles update")

        ImportedDao().queryAllUUIDs()
            .mapNotNull { ImportedDao().queryByUUID(it) }
            .filter { it.type != Profile.Type.File }
            .forEach { schedule(context, it, ExistingPeriodicWorkPolicy.KEEP).await() }
    }

    fun schedule(context: Context, imported: Imported) {
        schedule(context, imported, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)
    }

    fun updateNow(context: Context, imported: Imported) {
        val request = OneTimeWorkRequestBuilder<ProfileUpdateWorker>()
            .setInputData(ProfileUpdateWorker.input(imported, periodic = false))
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(manualName(imported), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context, imported: Imported) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(periodicName(imported))
            cancelUniqueWork(manualName(imported))
        }
    }

    private fun schedule(context: Context, imported: Imported, policy: ExistingPeriodicWorkPolicy): Operation {
        val manager = WorkManager.getInstance(context)

        if (imported.interval < UpdateSchedule.MIN_INTERVAL)
            return manager.cancelUniqueWork(periodicName(imported))

        val updatedAt = context.importedDir
            .resolve(imported.uuid.toString())
            .resolve("config.yaml")
            .lastModified()
        val delay = UpdateSchedule.firstDelay(imported.interval, updatedAt, System.currentTimeMillis())

        val request = PeriodicWorkRequestBuilder<ProfileUpdateWorker>(imported.interval, TimeUnit.MILLISECONDS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, UpdateSchedule.MIN_INTERVAL, TimeUnit.MILLISECONDS)
            .setInputData(ProfileUpdateWorker.input(imported, periodic = true))
            .build()

        return manager.enqueueUniquePeriodicWork(periodicName(imported), policy, request)
    }

    private fun periodicName(imported: Imported) = "profile-update-${imported.uuid}"

    private fun manualName(imported: Imported) = "profile-update-now-${imported.uuid}"
}
