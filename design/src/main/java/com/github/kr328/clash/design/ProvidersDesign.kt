package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.github.kr328.clash.core.model.Provider
import com.github.kr328.clash.design.compose.screen.ProviderRow
import com.github.kr328.clash.design.compose.screen.ProvidersAction
import com.github.kr328.clash.design.compose.screen.ProvidersScreen
import com.github.kr328.clash.design.compose.screen.ProvidersState
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProvidersDesign(
    context: Context,
    providers: List<Provider>,
) : Design<ProvidersDesign.Request>(context) {
    sealed interface Request {
        data object Back : Request
        data class Update(val index: Int, val provider: Provider) : Request
    }

    private var state by mutableStateOf(
        ProvidersState(
            providers = providers.map { ProviderRow(provider = it, updatedAt = it.updatedAt) },
            currentTime = System.currentTimeMillis(),
        ),
    )

    override val root: View = ComposeView(context).apply {
        setContent {
            ClodClashTheme {
                ProvidersScreen(state = state, onAction = ::onAction)
            }
        }
    }

    private fun onAction(action: ProvidersAction) {
        when (action) {
            ProvidersAction.Back -> requests.trySend(Request.Back)
            ProvidersAction.UpdateAll -> requestUpdateAll()
            is ProvidersAction.Update -> requestUpdate(action.index)
        }
    }

    /** Пересчитать строки «N минут назад»: активити зовёт раз в минуту. */
    fun updateElapsed() {
        state = state.copy(currentTime = System.currentTimeMillis())
    }

    /** Обновление не удалось: кружок гаснет, время остаётся прежним. */
    suspend fun notifyUpdated(index: Int) {
        withContext(Dispatchers.Main) {
            patch(index) { it.copy(updating = false) }
        }
    }

    /** Обновление прошло: гасим кружок и переставляем «N минут назад». */
    suspend fun notifyChanged(index: Int) {
        withContext(Dispatchers.Main) {
            patch(index) { it.copy(updating = false, updatedAt = System.currentTimeMillis()) }
        }
    }

    private fun requestUpdate(index: Int) {
        val row = state.providers.getOrNull(index) ?: return

        if (row.updating || row.inline) return

        patch(index) { it.copy(updating = true) }

        requests.trySend(Request.Update(index, row.provider))
    }

    private fun requestUpdateAll() {
        // Индексы берутся из ИСХОДНОГО списка. Раньше здесь стояло
        // `filter { !it.updating }.forEachIndexed`, и номер приходил из
        // отфильтрованного списка: стоило одному провайдеру уже обновляться,
        // как ответ прилетал не на ту строку. Вторая половина той же ошибки:
        // встроенные провайдеры помечались обновляющимися, но запроса
        // не получали — видно этого не было (у них скрыта вся правая часть
        // строки), зато они навсегда выпадали из фильтра и сдвигали номера
        // всем следующим нажатиям «обновить всё».
        val targets = state.providers.withIndex()
            .filter { (_, row) -> !row.updating && !row.inline }
            .map { (index, _) -> index }

        if (targets.isEmpty()) return

        val marked = targets.toSet()

        state = state.copy(
            providers = state.providers.mapIndexed { index, row ->
                if (index in marked) row.copy(updating = true) else row
            },
        )

        for (index in targets) {
            requests.trySend(Request.Update(index, state.providers[index].provider))
        }
    }

    private fun patch(index: Int, block: (ProviderRow) -> ProviderRow) {
        if (index !in state.providers.indices) return

        state = state.copy(
            providers = state.providers.mapIndexed { i, row ->
                if (i == index) block(row) else row
            },
        )
    }
}
