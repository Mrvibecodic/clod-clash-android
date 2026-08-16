package com.github.kr328.clash

import android.net.Uri
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.AddProfileDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.queryPanelInfo
import com.github.kr328.clash.util.withProfile
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.UUID

class AddProfileActivity : BaseActivity<AddProfileDesign>() {
    private val scanLauncher = registerForActivityResult(ScanQRCode(), ::onScanResult)

    override suspend fun main() {
        val design = AddProfileDesign(this)

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive { }
                design.requests.onReceive { request ->
                    when (request) {
                        is AddProfileDesign.Request.Submit -> design.addProfile(request.url, request.secure)
                        AddProfileDesign.Request.ScanQr -> scanLauncher.launch(null)
                        AddProfileDesign.Request.OtherWays ->
                            startActivity(NewProfileActivity::class.intent)

                        AddProfileDesign.Request.Finish -> finish()
                    }
                }
            }
        }
    }

    private suspend fun AddProfileDesign.addProfile(input: String, secure: Boolean) {
        val source = normalizeSource(input)

        if (source == null) {
            setError(getString(R.string.invalid_url))

            return
        }

        setFetching()

        val uuid: UUID = withProfile {
            create(Profile.Type.Url, getString(R.string.new_profile), source, secure = secure)
        }

        try {
            withProfile {
                coroutineScope {
                    commit(uuid) { status ->
                        launch { setProgress(status) }
                    }
                }
            }

            val profile = withProfile { queryByUUID(uuid) }

            if (profile == null) {
                withProfile { release(uuid) }

                setError(getString(R.string.invalid_url))

                return
            }

            val hasActive = withProfile { queryActive() } != null

            if (!hasActive) {
                withProfile { setActive(profile) }
            }

            setDone(profile, queryPanelInfo(uuid)?.title.orEmpty())
        } catch (e: Exception) {
            withProfile { release(uuid) }

            setError(e.message ?: getString(R.string.invalid_url))
        }
    }

    private fun normalizeSource(input: String): String? {
        val trimmed = input.trim()

        if (trimmed.isEmpty()) return null

        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null

        return when (uri.scheme?.lowercase()) {
            "http", "https" -> trimmed
            "clash", "clashmeta", "clodclash" ->
                uri.getQueryParameter("url")?.takeIf { it.isNotBlank() }

            else -> null
        }
    }

    private fun onScanResult(result: QRResult) {
        lifecycleScope.launch {
            when (result) {
                is QRResult.QRSuccess -> {
                    val url = result.content.rawValue
                        ?: result.content.rawBytes?.let { String(it) }.orEmpty()

                    design?.setUrl(url)
                }

                QRResult.QRUserCanceled -> Unit
                QRResult.QRMissingPermission ->
                    design?.showExceptionToast(getString(R.string.import_from_qr_no_permission))

                is QRResult.QRError ->
                    design?.showExceptionToast(getString(R.string.import_from_qr_exception))
            }
        }
    }
}
