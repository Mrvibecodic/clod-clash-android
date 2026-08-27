package com.github.kr328.clash.util

import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IFetchObserver
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

object ProfileImports {
    sealed interface State {
        val token: Long

        data object Idle : State {
            override val token: Long = 0
        }

        data class Running(override val token: Long, val status: FetchStatus?) : State
        data class Done(override val token: Long, val uuid: UUID, val name: String) : State
        data class Failed(override val token: Long, val message: String) : State
    }

    sealed interface BatchState {
        data object Idle : BatchState
        data class Running(val restored: Int, val total: Int) : BatchState
        data class Done(val restored: Int, val total: Int) : BatchState
    }

    data class Item(
        val name: String,
        val source: String,
        val interval: Long,
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

    @Synchronized
    fun start(source: String, secure: Boolean): Long {
        if (job?.isActive == true) return 0

        val token = ++lastToken

        state_.value = State.Running(token, null)

        job = Global.launch {
            val context = Global.application.withAppLocale()

            try {
                val uuid = withProfile(retry = false) {
                    create(Profile.Type.Url, context.getString(R.string.new_profile), source, secure = secure)
                }

                val profile = import(uuid, true) { status ->
                    state_.value = State.Running(token, status)
                }

                val title = context.queryPanelInfo(uuid)?.title?.takeIf { it.isNotBlank() } ?: profile.name

                AppStore(context).apply {
                    addedProfileName = title
                    addedProfilePending = true
                }

                state_.value = State.Done(token, uuid, title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state_.value = State.Failed(token, e.message ?: context.getString(R.string.invalid_url))
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

            for (item in items) {
                try {
                    val uuid = withProfile(retry = false) {
                        create(Profile.Type.Url, item.name, item.source, secure = item.secure)
                    }

                    if (item.interval > 0) {
                        withProfile(retry = false) { patch(uuid, item.name, item.source, item.interval, null) }
                    }

                    import(uuid, item.active, null)

                    restored += 1

                    batch_.value = BatchState.Running(restored, total)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Restore subscription: $e", e)
                }
            }

            batch_.value = BatchState.Done(restored, total)
        }

        return true
    }

    @Synchronized
    fun commit(profile: Profile): Long {
        if (job?.isActive == true) return 0

        val token = ++lastToken

        state_.value = State.Running(token, null)

        job = Global.launch {
            val context = Global.application.withAppLocale()

            try {
                withProfile(retry = false) {
                    patch(profile.uuid, profile.name, profile.source, profile.interval, profile.ageSecretKey)
                }

                withProfile(retry = false) {
                    commit(profile.uuid) { status ->
                        state_.value = State.Running(token, status)
                    }
                }

                if (withProfile { queryActive() } == null) {
                    withProfile { setActive(profile) }
                }

                state_.value = State.Done(token, profile.uuid, profile.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state_.value = State.Failed(token, e.message ?: context.getString(R.string.invalid_url))
            }
        }

        return token
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
