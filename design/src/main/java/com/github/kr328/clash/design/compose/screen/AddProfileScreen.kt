package com.github.kr328.clash.design.compose.screen

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.theme.ClodTheme
import com.github.kr328.clash.service.model.Profile
import java.util.Date
import java.util.concurrent.TimeUnit

/** Шаги добавления подписки. Ровно два экрана: ввод и результат. */
enum class AddProfileStep {
    Input,
    Fetching,
    Done,
}

@Immutable
data class AddProfileState(
    val url: String = "",
    val step: AddProfileStep = AddProfileStep.Input,
    /** Что именно сейчас качается — приходит из наблюдателя загрузки ядра. */
    val progressText: String = "",
    val progress: Float = 0f,
    val error: String? = null,
    /**
     * clod:chan — подписка ходит только по защищённому каналу до прослойки.
     *
     * Провайдер пишет об этом в инструкции. Галочку можно только поставить:
     * снять её потом нельзя, профиль придётся удалить и завести заново.
     */
    val secure: Boolean = false,
    val result: Profile? = null,
    /** Название от панели. Профиль в базе всё ещё зовётся по умолчанию. */
    val resultTitle: String = "",
)

sealed interface AddProfileAction {
    data class UrlChanged(val url: String) : AddProfileAction
    data class SecureChanged(val secure: Boolean) : AddProfileAction
    data object Submit : AddProfileAction
    data object ScanQr : AddProfileAction
    data object OtherWays : AddProfileAction
    data object Finish : AddProfileAction
}

/**
 * Добавление подписки в два полноэкранных шага, а не диалогом.
 *
 * На десктопе это тоже два шага: сначала одно поле, потом — что нашлось по ссылке.
 * Имя, интервал обновления, срок и трафик приходят из ответа панели, спрашивать
 * их у человека незачем — старый экран CMFA требовал заполнить их руками.
 */
@Composable
fun AddProfileScreen(
    state: AddProfileState,
    onAction: (AddProfileAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // Без этого заголовок уезжает под системную панель: экран рисуется
            // во всё окно, а своей шапки с отступами у него нет.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(20.dp),
    ) {
        Text(
            text = stringResource(R.string.clod_sub_add),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(20.dp))

        when (state.step) {
            AddProfileStep.Input -> InputStep(state, onAction)
            AddProfileStep.Fetching -> FetchingStep(state)
            AddProfileStep.Done -> DoneStep(state, onAction)
        }
    }
}

@Composable
private fun InputStep(state: AddProfileState, onAction: (AddProfileAction) -> Unit) {
    OutlinedTextField(
        value = state.url,
        onValueChange = { onAction(AddProfileAction.UrlChanged(it)) },
        label = { Text(stringResource(R.string.clod_sub_url_label)) },
        singleLine = true,
        isError = state.error != null,
        supportingText = state.error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
        ),
        trailingIcon = {
            IconButton(onClick = { onAction(AddProfileAction.ScanQr) }) {
                Icon(
                    painter = painterResource(R.drawable.baseline_qr_code_scanner),
                    contentDescription = stringResource(R.string.import_from_qr),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.clod_sub_url_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onAction(AddProfileAction.SecureChanged(!state.secure)) },
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.clod_secure_channel),
                // Заголовок строки, а не обычный текст: без явного цвета он
                // брал LocalContentColor и на тёмной теме читался бледнее
                // собственного пояснения — строка выглядела выключенной.
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.clod_secure_channel_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = state.secure,
            onCheckedChange = { onAction(AddProfileAction.SecureChanged(it)) },
        )
    }
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = { onAction(AddProfileAction.Submit) },
        enabled = state.url.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.clod_sub_add))
    }
    Spacer(Modifier.height(8.dp))
    TextButton(
        onClick = { onAction(AddProfileAction.OtherWays) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.clod_sub_other_ways))
    }
}

@Composable
private fun FetchingStep(state: AddProfileState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                text = state.progressText.ifBlank { stringResource(R.string.clod_sub_fetching) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (state.progress > 0f) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DoneStep(state: AddProfileState, onAction: (AddProfileAction) -> Unit) {
    val context = LocalContext.current
    val profile = state.result

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                // Название от панели, а не то, под которым профиль лёг в базу:
                // человек его не задавал и видеть «New Profile» не должен.
                text = state.resultTitle.ifBlank { profile?.name.orEmpty() },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            if (profile != null && profile.interval > 0) {
                Fact(
                    stringResource(
                        R.string.clod_sub_interval,
                        TimeUnit.MILLISECONDS.toHours(profile.interval).toInt(),
                    ),
                )
            }
            if (profile != null && (profile.total > 0 || profile.expire > 0)) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (profile.total > 0) {
                        val used = profile.upload + profile.download
                        Fact(
                            Formatter.formatShortFileSize(context, used) + " / " +
                                Formatter.formatShortFileSize(context, profile.total),
                        )
                    }
                    if (profile.expire > 0) {
                        Fact(
                            stringResource(
                                R.string.clod_sub_until,
                                DateFormat.getDateFormat(context).format(Date(profile.expire)),
                            ),
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.ic_outline_check_circle),
            contentDescription = null,
            tint = ClodTheme.extraColors.statusConnected,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.clod_sub_added),
            style = MaterialTheme.typography.bodyMedium,
            color = ClodTheme.extraColors.statusConnected,
        )
    }

    Spacer(Modifier.height(24.dp))
    Button(
        onClick = { onAction(AddProfileAction.Finish) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.clod_sub_done))
    }
}

@Composable
private fun Fact(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
