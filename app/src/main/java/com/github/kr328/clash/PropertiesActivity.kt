package com.github.kr328.clash

import android.os.Bundle
import androidx.core.os.BundleCompat
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.design.PropertiesDesign
import com.github.kr328.clash.design.compose.screen.MIN_INTERVAL_MINUTES
import com.github.kr328.clash.design.compose.screen.isHttpUrl
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.displayProfileName
import com.github.kr328.clash.util.ProfileImports
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R

class PropertiesActivity : BaseActivity<PropertiesDesign>() {
    private var canceled: Boolean = false
    private var token: Long = 0
    private lateinit var original: Profile

    override suspend fun main() {
        token = restored?.getLong("token") ?: 0

        setResult(RESULT_CANCELED)

        val uuid = intent.uuid ?: return finish()
        val design = PropertiesDesign(this)

        val stored = withProfile { queryByUUID(uuid) } ?: return finish()

        val bundle = restored
        val draft = bundle?.let { BundleCompat.getParcelable(it, "draft", Profile::class.java) }

        original = bundle?.let { BundleCompat.getParcelable(it, "original", Profile::class.java) }
            ?: stored.copy(name = displayProfileName(uuid, stored.name))

        design.profile = draft ?: original

        setContentDesign(design)

        launch {
            ProfileImports.state.collect {
                if (it.token != token) return@collect

                when (it) {
                    is ProfileImports.State.Running -> design.setImporting(it.status)
                    is ProfileImports.State.Done -> {
                        ProfileImports.consume(token)
                        design.clearImporting()
                        setResult(RESULT_OK)
                        finish()
                    }
                    is ProfileImports.State.Failed -> {
                        ProfileImports.consume(token)
                        design.clearImporting()
                        design.showToast(it.message, ToastDuration.Long)
                    }
                    ProfileImports.State.Idle -> design.clearImporting()
                }
            }
        }

        defer {
            canceled = true

            withProfile { release(uuid) }
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStop -> {
                            val profile = design.profile

                            if (!canceled && profile != original && design.draftValid) {
                                withContext(NonCancellable) {
                                    withProfile {
                                        patch(profile.uuid, profile.name, profile.source, profile.interval, profile.ageSecretKey)
                                    }
                                }
                            }
                        }
                        Event.ServiceRecreated -> {
                            finish()
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        PropertiesDesign.Request.BrowseFiles -> {
                            startActivity(FilesActivity::class.intent.setUUID(uuid))
                        }
                        PropertiesDesign.Request.Commit -> {
                            design.verifyAndCommit()
                        }
                        PropertiesDesign.Request.Back -> onBackPressed()
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putLong("token", token)

        val design = design ?: return

        outState.putParcelable("draft", design.profile)
        outState.putParcelable("original", original)
    }

    override fun onBackPressed() {
        design?.apply {
            launch {
                if (!progressing) {
                    if (original == profile || requestExitWithoutSaving())
                        finish()
                }
            }
        } ?: return super.onBackPressed()
    }

    private suspend fun PropertiesDesign.verifyAndCommit() {
        when {
            profile.name.isBlank() -> {
                showToast(R.string.empty_name, ToastDuration.Long)
            }
            profile.type != Profile.Type.File && profile.source.isBlank() -> {
                showToast(R.string.invalid_url, ToastDuration.Long)
            }
            profile.type == Profile.Type.Url && !isHttpUrl(profile.source) -> {
                showToast(R.string.invalid_url, ToastDuration.Long)
            }
            profile.interval != 0L &&
                profile.interval < TimeUnit.MINUTES.toMillis(MIN_INTERVAL_MINUTES) -> {
                showToast(R.string.at_least_15_minutes, ToastDuration.Long)
            }
            else -> {
                val started = ProfileImports.commit(profile)

                if (started != 0L) token = started
            }
        }
    }
}
