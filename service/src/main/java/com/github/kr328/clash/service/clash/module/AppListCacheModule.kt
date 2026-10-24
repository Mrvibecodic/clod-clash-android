package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import android.content.pm.PackageInfo
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

// notifyChanges: после каждой установки или удаления приложений — событие с
// картой пакет → UID, по которой туннельная служба сверяет состав приложений VPN;
// null — список прочитать не удалось, карта службе неизвестна.
class AppListCacheModule(service: Service, private val notifyChanges: Boolean = false) : Module<Map<String, Int>?>(service) {
    private fun PackageInfo.uniqueUidName(): String =
        if (sharedUserId?.isNotBlank() == true) sharedUserId!! else packageName

    private fun reload(): Map<String, Int> {
        val all = service.packageManager.getInstalledPackages(0)
            .filter { it.applicationInfo != null }

        val packages = all
            .groupBy { it.uniqueUidName() }
            .map { (_, v) ->
                val info = v[0]

                if (v.size == 1) {

                    info.applicationInfo!!.uid to info.packageName
                } else {
                    info.applicationInfo!!.uid to info.uniqueUidName()
                }
            }

        Clash.notifyInstalledAppsChanged(packages)

        Log.d("Installed ${packages.size} packages cached")

        return all.associate { it.packageName to it.applicationInfo!!.uid }
    }

    override suspend fun run() {
        val packageChanged = receiveBroadcast(false, Channel.CONFLATED) {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }

        reload()

        while (true) {
            packageChanged.receive()

            delay(TimeUnit.SECONDS.toMillis(10))

            // Диспетчер пакетов отказывает на больших списках и при своём
            // перезапуске: ядро остаётся с прежним списком
            val installed = try {
                reload()
            } catch (e: Exception) {
                Log.w("Query installed packages: $e", e)

                null
            }

            if (notifyChanges) enqueueEvent(installed)
        }
    }
}
