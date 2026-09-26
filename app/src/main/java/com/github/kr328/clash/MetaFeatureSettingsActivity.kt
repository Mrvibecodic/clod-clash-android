package com.github.kr328.clash

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.GeoAssets
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.design.MetaFeatureSettingsDesign
import com.github.kr328.clash.design.compose.component.NoticeKind
import com.github.kr328.clash.design.model.PendingRestore
import com.github.kr328.clash.design.model.pendingRestore
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.util.clashDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import com.github.kr328.clash.design.R

class MetaFeatureSettingsActivity : BaseActivity<MetaFeatureSettingsDesign>() {
    private var reload = false
    private var rereading = false
    private var draft: PendingOverride.Draft? = null

    override suspend fun main() {
        val pending = PendingOverride.take(PendingOverride.SLOT_META)
        val restore = pendingRestore(
            flagSet = restored?.getBoolean(PendingOverride.KEY) == true,
            valuePresent = pending != null,
        )

        val stored = if (pending == null) readStoredOverride() else null

        val draft = pending
            ?: (stored as? StoredOverride.Readable)?.let { PendingOverride.Draft(it.value) }

        this.draft = draft

        reload = restored?.getBoolean("reload") ?: false

        val design = MetaFeatureSettingsDesign(
            this,
            draft?.value ?: ConfigurationOverride(),
            unreadable = (stored as? StoredOverride.Unreadable)?.reason,
        )

        val discard = {
            defer { PendingOverride.clear(PendingOverride.SLOT_META) }

            finish()
        }

        if (draft != null) {
            defer {
                if (!draft.saveReporting(design, discard)) throw FinishCancelled()

                PendingOverride.clear(PendingOverride.SLOT_META)
            }
        }

        setContentDesign(design)

        if (restore == PendingRestore.UseStoredAndWarn) {
            design.showToast(R.string.clod_override_pending_lost, ToastDuration.Long)
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    if (it == Event.ActivityStart && reload) {
                        rereading = true

                        recreate()
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MetaFeatureSettingsDesign.Request.Back -> finish()
                        MetaFeatureSettingsDesign.Request.OpenOverride -> {
                            if (draft == null || draft.saveReporting(design, discard)) {
                                reload = true

                                startActivity(OverrideSettingsActivity::class.intent)
                            }
                        }
                        MetaFeatureSettingsDesign.Request.ResetOverride -> {
                            if (design.requestResetConfirm() && resetStoredOverride(design)) {
                                defer { }
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putBoolean("reload", reload && !rereading)

        if (!reload) {
            PendingOverride.put(PendingOverride.SLOT_META, draft)

            outState.putBoolean(PendingOverride.KEY, draft?.dirty() == true)
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
            MetaFeatureSettingsDesign.Request.ImportGeoIp -> GeoImportTarget(
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
                kind = NoticeKind.Error,
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
