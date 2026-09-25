package com.github.kr328.clash

import android.net.Uri
import android.os.Bundle
import androidx.core.os.BundleCompat
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.design.PropertiesDesign
import com.github.kr328.clash.design.compose.component.NoticeKind
import com.github.kr328.clash.design.compose.screen.isValidSource
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.ProfileFields
import com.github.kr328.clash.util.DraftGate
import com.github.kr328.clash.util.ProfileImports
import com.github.kr328.clash.util.queryPanelInfo
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.Locale
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
            ?: stored

        design.panelName = queryPanelInfo(uuid)?.title
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
                        design.showToast(it.message, ToastDuration.Long, detail = it.detail, kind = NoticeKind.Error)
                    }
                    ProfileImports.State.Idle -> design.clearImporting()
                }
            }
        }

        defer {
            canceled = true

            withProfile(retry = false) { release(uuid) }
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStop -> {
                            val profile = design.profile

                            val saves = DraftGate.savesOnStop(
                                canceled = canceled,
                                changed = profile != original,
                                valid = design.draftValid,
                                committing = ProfileImports.isCommitting(profile.uuid),
                            )

                            if (saves) {
                                withContext(NonCancellable) {
                                    withProfile(retry = false) {
                                        patch(profile.uuid, profile.name, profile.nameManual, profile.source, profile.interval, profile.intervalManual)
                                    }
                                }
                            }
                        }
                        Event.ServiceRecreated -> {
                            if (DraftGate.closesOnServiceRecreated(ProfileImports.isCommitting(uuid))) {
                                finish()
                            }
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
                    val stored = try {
                        withProfile(retry = false) { queryByUUID(profile.uuid) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    val silent = DraftGate.exitsSilently(
                        changed = original != profile,
                        imported = stored?.imported == true,
                        draft = stored?.pending == true,
                    )

                    if (silent || requestExitWithoutSaving())
                        finish()
                }
            }
        } ?: return super.onBackPressed()
    }

    private suspend fun PropertiesDesign.verifyAndCommit() {
        val violation = ProfileFields.violation(
            profile.name,
            profile.source,
            Uri.parse(profile.source)?.scheme?.lowercase(Locale.ROOT),
            profile.interval,
            profile.type != Profile.Type.File,
        )

        when {
            violation != null -> showToast(violationText(violation), ToastDuration.Long)
            !isValidSource(profile.type, profile.source) -> showToast(R.string.invalid_url, ToastDuration.Long)
            else -> {
                val started = ProfileImports.commit(profile)

                if (started != 0L) {
                    token = started
                } else if (ProfileImports.state.value.token != token) {
                    showToast(R.string.clod_sub_import_busy, ToastDuration.Long)
                }
            }
        }
    }

    private fun violationText(violation: ProfileFields.Violation): String = when (violation) {
        ProfileFields.Violation.EmptyName -> getString(R.string.empty_name)
        ProfileFields.Violation.NameTooLong -> getString(R.string.clod_name_too_long, ProfileFields.NAME_MAX)
        ProfileFields.Violation.EmptySource,
        ProfileFields.Violation.UnsupportedScheme,
        -> getString(R.string.invalid_url)
        ProfileFields.Violation.SourceTooLong -> getString(R.string.clod_url_too_long, ProfileFields.SOURCE_MAX)
        ProfileFields.Violation.ShortInterval -> getString(R.string.at_least_15_minutes)
    }
}
