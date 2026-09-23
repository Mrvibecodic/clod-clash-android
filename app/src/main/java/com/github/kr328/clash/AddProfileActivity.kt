package com.github.kr328.clash

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.AddProfileDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.service.util.ProfileFields
import com.github.kr328.clash.util.ProfileImports
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

class AddProfileActivity : BaseActivity<AddProfileDesign>() {
    private val scanLauncher = registerForActivityResult(ScanQRCode(), ::onScanResult)

    private var token: Long = 0

    override suspend fun main() {
        token = restored?.getLong(KEY_TOKEN) ?: ProfileImports.runningAddToken()

        val design = AddProfileDesign(
            this,
            restored?.getString(KEY_URL).orEmpty(),
            restored?.getBoolean(KEY_SECURE) ?: false,
            restored?.getString(KEY_ERROR),
        )

        setContentDesign(design)

        launch {
            ProfileImports.state.collect { state ->
                if (state.token != token) return@collect

                when (state) {
                    ProfileImports.State.Idle -> Unit
                    is ProfileImports.State.Running ->
                        state.status?.let { design.setProgress(it) } ?: design.setFetching()

                    is ProfileImports.State.Done -> {
                        ProfileImports.consume(token)

                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(Intents.EXTRA_NAME, state.name),
                        )

                        finish()
                    }

                    is ProfileImports.State.Failed -> {
                        ProfileImports.consume(token)

                        design.setError(listOfNotNull(state.message, state.detail).joinToString("\n"))
                    }
                }
            }
        }

        while (isActive) {
            select<Unit> {
                events.onReceive { }
                design.requests.onReceive { request ->
                    when (request) {
                        is AddProfileDesign.Request.Submit -> design.addProfile(request.url, request.secure)
                        AddProfileDesign.Request.ScanQr -> scanLauncher.launch(null)
                        AddProfileDesign.Request.OtherWays -> {
                            val result = startActivityForResult(
                                ActivityResultContracts.StartActivityForResult(),
                                NewProfileActivity::class.intent,
                            )

                            if (result.resultCode == Activity.RESULT_OK) {
                                setResult(Activity.RESULT_OK)

                                finish()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putLong(KEY_TOKEN, token)

        design?.let {
            outState.putString(KEY_URL, it.url)
            outState.putBoolean(KEY_SECURE, it.secure)
            outState.putString(KEY_ERROR, it.error)
        }
    }

    private suspend fun AddProfileDesign.addProfile(input: String, secure: Boolean) {
        val source = normalizeSource(input)

        if (source == null) {
            setError(getString(R.string.invalid_url))

            return
        }

        if (source.length > ProfileFields.SOURCE_MAX) {
            setError(getString(R.string.clod_url_too_long, ProfileFields.SOURCE_MAX))

            return
        }

        val started = ProfileImports.start(source, secure)

        if (started != 0L) {
            token = started
        } else if (ProfileImports.state.value.token != token) {
            setError(getString(R.string.clod_sub_import_busy))
        }
    }

    private fun normalizeSource(input: String, unwrap: Boolean = true): String? {
        val trimmed = input.trim()

        if (trimmed.isEmpty()) return null

        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null

        return when (uri.scheme?.lowercase()) {
            "https" -> trimmed
            "clash", "clashmeta", "clodclash" -> if (unwrap) {
                uri.getQueryParameter("url")?.let { normalizeSource(it, unwrap = false) }
            } else {
                null
            }

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

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_SECURE = "secure"
        private const val KEY_TOKEN = "token"
        private const val KEY_ERROR = "error"
    }
}
