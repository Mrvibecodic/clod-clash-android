package com.github.kr328.clash

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.GeoAssets
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.design.MetaFeatureSettingsDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.util.clashDir
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import com.github.kr328.clash.design.R

class MetaFeatureSettingsActivity : BaseActivity<MetaFeatureSettingsDesign>() {
    private var reload = false

    override suspend fun main() {
        val configuration = withClash { queryOverride(Clash.OverrideSlot.Persist) }

        var writeBack = true

        defer {
            if (writeBack) {
                withClash {
                    patchOverride(Clash.OverrideSlot.Persist, configuration)
                }
            }
        }

        val design = MetaFeatureSettingsDesign(
            this,
            configuration
        )

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    if (it == Event.ActivityStart && reload) {
                        reload = false

                        recreate()
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MetaFeatureSettingsDesign.Request.Back -> finish()
                        MetaFeatureSettingsDesign.Request.OpenOverride -> {
                            withClash {
                                patchOverride(Clash.OverrideSlot.Persist, configuration)
                            }

                            writeBack = false
                            reload = true

                            startActivity(OverrideSettingsActivity::class.intent)
                        }
                        MetaFeatureSettingsDesign.Request.ResetOverride -> {
                            if (design.requestResetConfirm()) {
                                defer {
                                    withClash {
                                        clearOverride(Clash.OverrideSlot.Persist)
                                    }
                                }
                                finish()
                            }
                        }
                        MetaFeatureSettingsDesign.Request.ImportGeoIp -> {
                            val uri = startActivityForResult(
                                ActivityResultContracts.GetContent(),
                                "*/*")
                            importGeoFile(uri, MetaFeatureSettingsDesign.Request.ImportGeoIp)
                        }
                        MetaFeatureSettingsDesign.Request.ImportGeoSite -> {
                            val uri = startActivityForResult(
                                ActivityResultContracts.GetContent(),
                                "*/*")
                            importGeoFile(uri, MetaFeatureSettingsDesign.Request.ImportGeoSite)
                        }
                        MetaFeatureSettingsDesign.Request.ImportCountry -> {
                            val uri = startActivityForResult(
                                ActivityResultContracts.GetContent(),
                                "*/*")
                            importGeoFile(uri, MetaFeatureSettingsDesign.Request.ImportCountry)
                        }
                        MetaFeatureSettingsDesign.Request.ImportASN -> {
                            val uri = startActivityForResult(
                                ActivityResultContracts.GetContent(),
                                "*/*")
                            importGeoFile(uri, MetaFeatureSettingsDesign.Request.ImportASN)
                        }
                    }
                }
            }
        }
    }

    private data class GeoImportTarget(
        val fileName: String,
        val extensions: List<String>,
        val obsolete: List<String>,
    )

    private fun geoImportTarget(
        importType: MetaFeatureSettingsDesign.Request,
    ): GeoImportTarget? {
        return when (importType) {
            MetaFeatureSettingsDesign.Request.ImportGeoIp,
            MetaFeatureSettingsDesign.Request.ImportCountry -> GeoImportTarget(
                fileName = "geoip.metadb",
                extensions = listOf(".metadb", ".db", ".mmdb"),
                obsolete = listOf("geoip.db", "country.mmdb"),
            )
            MetaFeatureSettingsDesign.Request.ImportGeoSite -> GeoImportTarget(
                fileName = "geosite.dat",
                extensions = listOf(".dat"),
                obsolete = emptyList(),
            )
            MetaFeatureSettingsDesign.Request.ImportASN -> GeoImportTarget(
                fileName = "ASN.mmdb",
                extensions = listOf(".mmdb"),
                obsolete = emptyList(),
            )
            else -> null
        }
    }

    private suspend fun importGeoFile(uri: Uri?, importType: MetaFeatureSettingsDesign.Request) {
        val target = geoImportTarget(importType) ?: return

        if (uri == null) {
            return
        }

        val displayName = withContext(Dispatchers.IO) {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) {
                        return@use null
                    }

                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

                    if (index >= 0) cursor.getString(index) else null
                }
            } catch (e: Exception) {
                Log.w("Import geo file: $e", e)

                null
            }
        }

        if (displayName.isNullOrBlank()) {
            design?.showToast(R.string.geofile_import_failed, ToastDuration.Long)

            return
        }

        val extension = "." + displayName.substringAfterLast('.', "")

        if (extension !in target.extensions) {
            design?.showToast(
                message = getString(R.string.geofile_unknown_db_format),
                duration = ToastDuration.Long,
                detail = getString(
                    R.string.geofile_unknown_db_format_message,
                    target.extensions.joinToString("/"),
                ),
            )

            return
        }

        val imported = GeoAssets.writeGuarded(this) {
            val destination = File(clashDir, target.fileName)
            val temp = File(clashDir, "${target.fileName}.importing")

            try {
                clashDir.mkdirs()

                val opened = contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temp).use { output ->
                        input.copyTo(output)
                    }

                    true
                } ?: false

                if (!opened || !temp.renameTo(destination)) {
                    return@writeGuarded false
                }

                target.obsolete.forEach { File(clashDir, it).delete() }

                true
            } catch (e: Exception) {
                Log.w("Import geo file: $e", e)

                false
            } finally {
                temp.delete()
            }
        }

        if (imported) {
            design?.showToast(
                getString(R.string.geofile_imported, displayName),
                ToastDuration.Long,
                detail = if (clashRunning) getString(R.string.geofile_after_reconnect) else null,
            )
        } else {
            design?.showToast(R.string.geofile_import_failed, ToastDuration.Long)
        }
    }
}
