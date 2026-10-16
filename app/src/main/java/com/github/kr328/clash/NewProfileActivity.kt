package com.github.kr328.clash

import android.app.Activity
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.design.NewProfileDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.ProfileProvider
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.store.AppStore
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import java.util.*

class NewProfileActivity : BaseActivity<NewProfileDesign>() {
    // Открыты свойства новой подписки: экран, пересозданный за это время
    // (поворот, смена темы), их ответа не получит — тогда по возвращении он
    // сам смотрит, добавилась ли подписка
    private var awaiting = false

    override suspend fun main() {
        val design = NewProfileDesign(this)
        val resumed = restored?.getBoolean(KEY_AWAITING) == true

        design.patchProviders(listOf(ProfileProvider.File(this), ProfileProvider.Url(this)))

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    if (it == Event.ActivityStart && resumed && AppStore(this@NewProfileActivity).addedProfilePending) {
                        setResult(Activity.RESULT_OK)

                        finish()
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        NewProfileDesign.Request.Back -> {
                            finish()
                        }

                        is NewProfileDesign.Request.Create -> {
                            withProfile(retry = false) {
                                val name = getString(R.string.new_profile)

                                val uuid = when (it.provider) {
                                    is ProfileProvider.File ->
                                        create(Profile.Type.File, name)

                                    is ProfileProvider.Url ->
                                        create(Profile.Type.Url, name)
                                }

                                launchProperties(uuid)
                            }

                            design.dropPendingRequests()
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putBoolean(KEY_AWAITING, awaiting)
    }

    private suspend fun launchProperties(uuid: UUID) {
        awaiting = true

        val r = try {
            startActivityForResult(
                ActivityResultContracts.StartActivityForResult(),
                PropertiesActivity::class.intent.setUUID(uuid)
            )
        } finally {
            awaiting = false
        }

        if (r.resultCode == Activity.RESULT_OK) {
            setResult(Activity.RESULT_OK)

            finish()
        }
    }

    private companion object {
        const val KEY_AWAITING = "awaiting"
    }
}
