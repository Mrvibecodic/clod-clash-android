package com.github.kr328.clash

import android.app.Activity
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.AddProfileDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.util.ProfileFields
import com.github.kr328.clash.store.AppStore
import com.github.kr328.clash.util.ProfileImports
import com.github.kr328.clash.util.subscriptionSourceOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

class AddProfileActivity : BaseActivity<AddProfileDesign>() {
    private var token: Long = 0
    private var awaiting = false

    override suspend fun main() {
        token = restored?.getLong(KEY_TOKEN) ?: ProfileImports.runningAddToken()
        val resumed = restored?.getBoolean(KEY_AWAITING) == true

        val design = AddProfileDesign(
            this,
            restored?.getString(KEY_URL).orEmpty(),
            restored?.getString(KEY_ERROR),
            restored?.getString(KEY_ERROR_DETAIL),
        )

        setContentDesign(design)

        launch {
            ProfileImports.state.collect { state ->
                // Закрывающийся экран итог не забирает: его покажет Главная
                if (state.token != token || isFinishing) return@collect

                when (state) {
                    ProfileImports.State.Idle -> Unit
                    is ProfileImports.State.Running -> {
                        design.setStage(state.stage)

                        state.status?.let { design.setProgress(it) } ?: design.setFetching()
                    }

                    is ProfileImports.State.Done -> {
                        ProfileImports.consume(token)

                        setResult(Activity.RESULT_OK)

                        finish()
                    }

                    is ProfileImports.State.Failed -> {
                        ProfileImports.consume(token)

                        AppStore(this@AddProfileActivity).apply {
                            addFailedMessage = ""
                            addFailedDetail = ""
                        }

                        design.setError(state.message, state.detail)
                    }
                }
            }
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    // Пересоздан, пока шли «Другие способы»: их ответа он не
                    // получит — подписка добавилась, значит, экран своё сделал
                    if (it == Event.ActivityStart && resumed && AppStore(this@AddProfileActivity).addedProfilePending) {
                        setResult(Activity.RESULT_OK)

                        finish()
                    }
                }
                design.requests.onReceive { request ->
                    when (request) {
                        is AddProfileDesign.Request.Submit -> design.addProfile(request.url)
                        AddProfileDesign.Request.OtherWays -> {
                            awaiting = true

                            val result = try {
                                startActivityForResult(
                                    ActivityResultContracts.StartActivityForResult(),
                                    NewProfileActivity::class.intent,
                                )
                            } finally {
                                awaiting = false
                            }

                            design.dropPendingRequests()

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
        outState.putBoolean(KEY_AWAITING, awaiting)

        design?.let {
            outState.putString(KEY_URL, it.url)
            outState.putString(KEY_ERROR, it.error)
            outState.putString(KEY_ERROR_DETAIL, it.errorDetail)
        }
    }

    private suspend fun AddProfileDesign.addProfile(input: String) {
        val source = subscriptionSourceOf(input)

        if (source == null) {
            setError(getString(R.string.accept_http_content))

            return
        }

        if (source.length > ProfileFields.SOURCE_MAX) {
            setError(getString(R.string.clod_url_too_long, ProfileFields.SOURCE_MAX))

            return
        }

        val started = ProfileImports.start(source)

        if (started != 0L) {
            token = started
        } else if (ProfileImports.state.value.token != token) {
            setError(getString(R.string.clod_sub_import_busy))
        }
    }

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_TOKEN = "token"
        private const val KEY_AWAITING = "awaiting"
        private const val KEY_ERROR = "error"
        private const val KEY_ERROR_DETAIL = "error_detail"
    }
}
