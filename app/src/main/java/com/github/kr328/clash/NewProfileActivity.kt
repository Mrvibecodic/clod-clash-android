package com.github.kr328.clash

import android.app.Activity
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.design.NewProfileDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.ProfileProvider
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import java.util.*

class NewProfileActivity : BaseActivity<NewProfileDesign>() {
    override suspend fun main() {
        val design = NewProfileDesign(this)

        design.patchProviders(listOf(ProfileProvider.File(this), ProfileProvider.Url(this)))

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {

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
                        }
                    }
                }
            }
        }
    }

    private suspend fun launchProperties(uuid: UUID) {
        val r = startActivityForResult(
            ActivityResultContracts.StartActivityForResult(),
            PropertiesActivity::class.intent.setUUID(uuid)
        )

        if (r.resultCode == Activity.RESULT_OK) {
            setResult(Activity.RESULT_OK)

            finish()
        }
    }
}
