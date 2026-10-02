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

    // Весь ход восстановления — от вопроса «восстановить?» до итога — хранится здесь,
    // а не в экране настроек: экран пересоздаётся (поворот, тема, старт и остановка
    // VPN) и только показывает это состояние. shown — открыт ли диалог хода: закрытый
    // человеком не возвращается и после пересоздания.
    sealed interface BatchState {
        data object Idle : BatchState
        // present — сколько подписок из копии уже есть, rejected — «запись: причина»
        // для тех, что отброшены до загрузки; итог называет и то, и другое.
        data class Confirm(
            val items: List<Item>,
            val entries: List<String>,
            val activeName: String?,
            val present: Int,
            val rejected: List<String>,
        ) : BatchState
        data class Running(val processed: Int, val total: Int, val shown: Boolean = true) : BatchState
        data class Done(
            val restored: Int,
            val total: Int,
            val present: Int,
            val failures: List<String>,
            val failedProviders: List<String> = emptyList(),
        ) : BatchState
    }

    data class Item(
        val name: String,
        val nameManual: Boolean,
        val source: String,
        val interval: Long,
        val intervalManual: Boolean,
        val secure: Boolean,
        val active: Boolean,
    )

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

        job = launchHoldingService {
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

                val title = profileDisplayName(context.queryPanelInfo(uuid), profile.name, profile.nameManual)

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
    fun offerBatch(items: List<Item>, entries: List<String>, activeName: String?, present: Int, rejected: List<String>) {
        if (batch_.value is BatchState.Idle) {
            batch_.value = BatchState.Confirm(items, entries, activeName, present, rejected)
        }
    }

    @Synchronized
    fun confirmBatch() {
        val confirm = batch_.value as? BatchState.Confirm ?: return

        if (batchJob?.isActive == true) return

        val items = confirm.items
        val total = items.size

        batch_.value = BatchState.Running(0, total)

        batchJob = launchHoldingService {
            val context = Global.application.withAppLocale()
            var restored = 0
            val failures = confirm.rejected.toMutableList()
            val failed = AtomicReference(emptyList<String>())

            for ((index, item) in items.withIndex()) {
                try {
                    val uuid = withProfile(retry = false) {
                        create(Profile.Type.Url, item.name, item.source, secure = item.secure)
                    }

                    if (item.intervalManual || item.nameManual) {
                        val interval = if (item.intervalManual) item.interval else 0L

                        withProfile(retry = false) {
                            patch(uuid, item.name, item.nameManual, item.source, interval, item.intervalManual)
                        }
                    }

                    import(uuid, item.active) { status ->
                        FailedProviders.accumulate(failed, status)
                    }

                    restored += 1
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Restore subscription: $e", e)

                    failures += "${confirm.entries.getOrElse(index) { item.name }}: ${context.failureMessage(e)}"
                }

                batch_.update { (it as? BatchState.Running)?.copy(processed = index + 1) ?: it }
            }

            batch_.value = BatchState.Done(restored, total + confirm.rejected.size, confirm.present, failures, failed.get())
        }
    }

    // Отказ от предпросмотра — конец восстановления; закрытие хода — только диалога.
    @Synchronized
    fun dismissBatch() {
        batch_.update {
            when (it) {
                is BatchState.Confirm -> BatchState.Idle
                is BatchState.Running -> it.copy(shown = false)
                else -> it
            }
        }
    }

    @Synchronized
    fun commit(profile: Profile): Long {
        if (job?.isActive == true) return 0

        val token = ++lastToken

        state_.value = State.Running(token, null)
        committing = profile.uuid

        job = launchHoldingService {
            val context = Global.application.withAppLocale()
            val failed = AtomicReference(emptyList<String>())

            try {
                withProfile(retry = false) {
                    patch(profile.uuid, profile.name, profile.nameManual, profile.source, profile.interval, profile.intervalManual)
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
                        addedProfileName = profileDisplayName(
                            context.queryPanelInfo(profile.uuid),
                            profile.name,
                            profile.nameManual,
                        )
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

    private fun Context.humanFailure(e: Exception): String? {
        val raw = e.message.orEmpty()

        return humanizeUpdateFailure(raw) ?: raw.takeIf { e is HumanMessage && it.isNotBlank() }
    }

    private fun Context.failureMessage(e: Exception): String =
        humanFailure(e) ?: getString(R.string.clod_sub_fetch_failed)

    private fun Context.failed(token: Long, e: Exception): State.Failed {
        val raw = e.message.orEmpty()
        val human = humanFailure(e)

        return State.Failed(
            token,
            human ?: getString(R.string.clod_sub_fetch_failed),
            if (human == null) Redact.text(raw.ifBlank { e.javaClass.name }) else UpdateFailures.detail(raw),
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
