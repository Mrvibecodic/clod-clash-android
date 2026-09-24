package com.github.kr328.clash.util

import android.content.Context
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.HumanMessage
import com.github.kr328.clash.common.util.Redact
import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IFetchObserver
import com.github.kr328.clash.service.util.UpdateFailures
import com.github.kr328.clash.service.util.humanizeUpdateFailure
import com.github.kr328.clash.service.util.profileDisplayName
import com.github.kr328.clash.store.AppStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

object ProfileImports {
    sealed interface State {
        val token: Long

        data object Idle : State {
            override val token: Long = 0
        }

        data class Running(override val token: Long, val status: FetchStatus?) : State
        data class Done(
            override val token: Long,
            val uuid: UUID,
            val name: String,
            val failedProviders: List<String>,
        ) : State
        data class Failed(override val token: Long, val message: String, val detail: String?) : State
    }

    sealed interface BatchState {
        data object Idle : BatchState
        data class Running(val restored: Int, val total: Int) : BatchState
        data class Done(
            val restored: Int,
            val total: Int,
            val failedProviders: List<String> = emptyList(),
        ) : BatchState
    }

    data class Item(
        val name: String,
        val source: String,
        val interval: Long,
        val intervalManual: Boolean,
        val secure: Boolean,
        val active: Boolean,
    )

    private const val CONFIG_REJECTED_MARK = "clod-config-rejected: "

    private val DETAILED_CAUSES = setOf(UpdateFailures.Cause.Rejected, UpdateFailures.Cause.Tls)

    private val state_ = MutableStateFlow<State>(State.Idle)
    private val batch_ = MutableStateFlow<BatchState>(BatchState.Idle)

    val state: StateFlow<State> = state_
    val batch: StateFlow<BatchState> = batch_

    private var job: Job? = null
    private var batchJob: Job? = null
    private var lastToken: Long = 0
    private var committing: UUID? = null

    @Synchronized
    fun isCommitting(uuid: UUID): Boolean = committing == uuid && job?.isActive == true

    @Synchronized
    fun runningAddToken(): Long =
        (state_.value as? State.Running)?.token?.takeIf { committing == null } ?: 0

    @Synchronized
    fun start(source: String, secure: Boolean): Long {
        if (job?.isActive == true) return 0

        val token = ++lastToken

        committing = null

        state_.value = State.Running(token, null)

        job = Global.launch {
            val context = Global.application.withAppLocale()
            val failed = AtomicReference(emptyList<String>())

            try {
                val uuid = withProfile(retry = false) {
                    create(Profile.Type.Url, context.getString(R.string.new_profile), source, secure = secure)
                }

                val profile = import(uuid, true) { status ->
                    runCatching {
                        FailedProviders.accumulate(failed, status)

                        state_.value = State.Running(token, status)
                    }.onFailure {
                        Log.w("Report import status: $it", it)
                    }
                }

                val title = profileDisplayName(context.queryPanelInfo(uuid), profile.name)

                AppStore(context).apply {
                    addedProfileName = title
                    addedProfilePending = true
                    profileProvidersFailed = FailedProviders.merge(profileProvidersFailed, failed.get())
                }

                state_.value = State.Done(token, uuid, title, failed.get())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state_.value = context.failed(token, e)
            }
        }

        return token
    }

    @Synchronized
    fun startBatch(items: List<Item>, total: Int): Boolean {
        if (batchJob?.isActive == true) return false

        batch_.value = BatchState.Running(0, total)

        batchJob = Global.launch {
            var restored = 0
            val failed = AtomicReference(emptyList<String>())

            for (item in items) {
                try {
                    val uuid = withProfile(retry = false) {
                        create(Profile.Type.Url, item.name, item.source, secure = item.secure)
                    }

                    if (item.intervalManual) {
                        withProfile(retry = false) { patch(uuid, item.name, item.source, item.interval, true) }
                    }

                    import(uuid, item.active) { status ->
                        FailedProviders.accumulate(failed, status)
                    }

                    restored += 1

                    batch_.value = BatchState.Running(restored, total)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Restore subscription: $e", e)
                }
            }

            batch_.value = BatchState.Done(restored, total, failed.get())
        }

        return true
    }

    @Synchronized
    fun commit(profile: Profile): Long {
        if (job?.isActive == true) return 0

        val token = ++lastToken

        state_.value = State.Running(token, null)
        committing = profile.uuid

        job = Global.launch {
            val context = Global.application.withAppLocale()
            val failed = AtomicReference(emptyList<String>())

            try {
                withProfile(retry = false) {
                    patch(profile.uuid, profile.name, profile.source, profile.interval, profile.intervalManual)
                }

                withProfile(retry = false) {
                    commit(profile.uuid) { status ->
                        FailedProviders.accumulate(failed, status)

                        state_.value = State.Running(token, status)
                    }
                }

                if (withProfile { queryActive() } == null) {
                    withProfile { setActive(profile) }
                }

                AppStore(context).apply {
                    if (!profile.imported) {
                        addedProfileName = profileDisplayName(context.queryPanelInfo(profile.uuid), profile.name)
                        addedProfilePending = true
                    }

                    profileProvidersFailed = FailedProviders.merge(profileProvidersFailed, failed.get())
                }

                state_.value = State.Done(token, profile.uuid, profile.name, failed.get())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state_.value = context.failed(token, e)
            }
        }

        return token
    }

    private fun Context.failed(token: Long, e: Exception): State.Failed {
        val raw = e.message.orEmpty()
        val human = humanizeUpdateFailure(raw) ?: raw.takeIf { e is HumanMessage && it.isNotBlank() }
        val detailed = UpdateFailures.classify(raw)?.cause in DETAILED_CAUSES

        return State.Failed(
            token,
            human ?: getString(R.string.clod_sub_fetch_failed),
            if (human == null || detailed) {
                Redact.text(raw.replace(CONFIG_REJECTED_MARK, "").ifBlank { e.javaClass.name })
            } else {
                null
            },
        )
    }

    fun consume(token: Long) {
        state_.update { if (it.token == token) State.Idle else it }
    }

    fun resetBatch() {
        batch_.value = BatchState.Idle
    }

    private suspend fun import(
        uuid: UUID,
        activate: Boolean,
        observer: IFetchObserver?,
    ): Profile {
        try {
            withProfile(retry = false) {
                commit(uuid, observer)
            }

            val profile = withProfile { queryByUUID(uuid) }
                ?: throw IllegalStateException(Global.application.withAppLocale().getString(R.string.invalid_url))

            if (activate && withProfile { queryActive() } == null) {
                withProfile { setActive(profile) }
            }

            return profile
        } catch (e: Exception) {
            withContext(NonCancellable) {
                withProfile(retry = false) { release(uuid) }
            }

            throw e
        }
    }
}
