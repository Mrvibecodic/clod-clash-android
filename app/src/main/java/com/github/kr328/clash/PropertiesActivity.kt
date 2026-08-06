package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.design.PropertiesDesign
import com.github.kr328.clash.design.compose.screen.MIN_INTERVAL_MINUTES
import com.github.kr328.clash.design.compose.screen.isHttpUrl
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R

class PropertiesActivity : BaseActivity<PropertiesDesign>() {
    private var canceled: Boolean = false
    private lateinit var original: Profile

    override suspend fun main() {
        setResult(RESULT_CANCELED)

        val uuid = intent.uuid ?: return finish()
        val design = PropertiesDesign(this)

        original = withProfile { queryByUUID(uuid) } ?: return finish()

        design.profile = original

        setContentDesign(design)

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

                            // Правки сохраняются молча при уходе с экрана,
                            // поэтому недописанное туда попадать не должно:
                            // поля правятся напрямую, и на полпути между
                            // «http» и полной ссылкой человек может просто
                            // свернуть приложение.
                            if (!canceled && profile != original &&
                                design.draftValid &&
                                verifyAgeSecretKey(profile.ageSecretKey)
                            ) {
                                withProfile {
                                    patch(profile.uuid, profile.name, profile.source, profile.interval, profile.ageSecretKey)
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
                        // Стрелка в шапке ведёт туда же, куда системная кнопка:
                        // спросить о несохранённых правках надо в обоих случаях.
                        PropertiesDesign.Request.Back -> onBackPressed()
                    }
                }
            }
        }
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

    private suspend fun verifyAgeSecretKey(key: String?): Boolean {
        if (key.isNullOrBlank()) return true

        return withContext(Dispatchers.IO) { Clash.veritySecretKeys(key) }
    }

    private suspend fun PropertiesDesign.verifyAndCommit() {
        when {
            profile.name.isBlank() -> {
                showToast(R.string.empty_name, ToastDuration.Long)
            }
            profile.type != Profile.Type.File && profile.source.isBlank() -> {
                showToast(R.string.invalid_url, ToastDuration.Long)
            }
            // Только для подписки по ссылке: у внешнего профиля в source лежит
            // адрес от приложения-источника, и он не обязан быть http.
            profile.type == Profile.Type.Url && !isHttpUrl(profile.source) -> {
                showToast(R.string.invalid_url, ToastDuration.Long)
            }
            profile.interval != 0L &&
                profile.interval < TimeUnit.MINUTES.toMillis(MIN_INTERVAL_MINUTES) -> {
                // Иначе будильник обновления просто не встанет, и человек будет
                // думать, что автообновление работает.
                showToast(R.string.at_least_15_minutes, ToastDuration.Long)
            }
            // Ключ проверяется здесь, а не по мере ввода: проверку делает ядро
            // через JNI, и дёргать его на каждую нажатую клавишу — значит
            // держать главный поток занятым ради подсветки поля.
            !verifyAgeSecretKey(profile.ageSecretKey) -> {
                showToast(R.string.age_secret_key_error, ToastDuration.Long)
            }
            else -> {
                try {
                    withProcessing { updateStatus ->
                        withProfile {
                            patch(profile.uuid, profile.name, profile.source, profile.interval, profile.ageSecretKey)

                            coroutineScope {
                                commit(profile.uuid) {
                                    launch {
                                        updateStatus(it)
                                    }
                                }
                            }
                        }
                    }

                    setResult(RESULT_OK)

                    finish()
                } catch (e: Exception) {
                    showExceptionToast(e)
                }
            }
        }
    }
}