package com.github.kr328.clash

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.GeoAssets
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.design.MetaFeatureSettingsDesign
import com.github.kr328.clash.design.compose.component.NoticeKind
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.util.UpdateFailures
import com.github.kr328.clash.common.util.clashDir
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import java.io.File
import java.io.FileOutputStream
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.R as ServiceR
import com.github.kr328.clash.util.displayName

class MetaFeatureSettingsActivity : BaseActivity<MetaFeatureSettingsDesign>() {
    private var reload = false
    private var rereading = false
    private var draft: PendingOverride.Draft? = null

    override suspend fun main() {
        val opened = PendingOverride.take(PendingOverride.SLOT_META, restored)
        val draft = opened.draft

        this.draft = draft

        reload = restored?.getBoolean("reload") ?: false

        val design = MetaFeatureSettingsDesign(
            this,
            draft?.value ?: ConfigurationOverride(),
            unreadable = opened.unreadable,
        )

        if (draft != null) saveOverrideOnFinish(PendingOverride.SLOT_META, draft, design)

        setContentDesign(design)

        design.warnLostDraft(opened)

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
                            if (draft == null || draft.saveReporting(design) { discardOverride(PendingOverride.SLOT_META) }) {
                                reload = true

                                startActivity(OverrideSettingsActivity::class.intent)
                            }
                        }
                        MetaFeatureSettingsDesign.Request.ResetOverride -> {
                            if (design.requestResetConfirm()) resetOverrideAndClose(design)
                        }
                        MetaFeatureSettingsDesign.Request.ImportGeoIp,
                        MetaFeatureSettingsDesign.Request.ImportGeoSite,
                        MetaFeatureSettingsDesign.Request.ImportASN -> {
                            val uri = startActivityForResult(ActivityResultContracts.GetContent(), "*/*")

                            importGeoFile(uri, GeoAssets.entry(GEO_IMPORTS.getValue(it)))
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
            PendingOverride.put(PendingOverride.SLOT_META, draft, outState)
        }
    }

    private suspend fun importGeoFile(uri: Uri?, target: GeoAssets.Entry) {
        if (uri == null) {
            return
        }

        val displayName = uri.displayName(contentResolver)

        if (displayName.isNullOrBlank()) {
            design?.showToast(R.string.geofile_import_failed, ToastDuration.Long)

            return
        }

        val extension = "." + displayName.substringAfterLast('.', "")

        if (extension !in target.importExtensions) {
            design?.showToast(
                message = getString(R.string.geofile_unknown_db_format),
                duration = ToastDuration.Long,
                detail = getString(
                    R.string.geofile_unknown_db_format_message,
                    target.importExtensions.joinToString("/"),
                ),
                kind = NoticeKind.Error,
            )

            return
        }

        var noSpace = false

        val imported = GeoAssets.writeGuarded(this) {
            val destination = File(clashDir, target.name)
            val temp = File(clashDir, "${target.name}.importing")

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

                noSpace = UpdateFailures.noSpace(e)

                false
            } finally {
                temp.delete()
            }
        }

        if (imported) {
            design?.showToast(
                getString(R.string.geofile_imported, displayName),
                ToastDuration.Long,
                detail = if (clashActive) getString(R.string.geofile_after_reconnect) else null,
            )
        } else if (noSpace) {
            design?.showToast(getString(ServiceR.string.clod_update_cause_storage), ToastDuration.Long, kind = NoticeKind.Error)
        } else {
            design?.showToast(R.string.geofile_import_failed, ToastDuration.Long)
        }
    }

    private companion object {
        val GEO_IMPORTS = mapOf(
            MetaFeatureSettingsDesign.Request.ImportGeoIp to "geoip.metadb",
            MetaFeatureSettingsDesign.Request.ImportGeoSite to "geosite.dat",
            MetaFeatureSettingsDesign.Request.ImportASN to "ASN.mmdb",
        )
    }
}
