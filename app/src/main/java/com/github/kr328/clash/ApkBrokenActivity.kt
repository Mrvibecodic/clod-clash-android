package com.github.kr328.clash

import com.github.kr328.clash.design.ApkBrokenDesign
import kotlinx.coroutines.isActive

class ApkBrokenActivity : BaseActivity<ApkBrokenDesign>() {
    override suspend fun main() {
        val design = ApkBrokenDesign(this)

        setContentDesign(design)

        while (isActive) {
            when (val req = design.requests.receive()) {
                ApkBrokenDesign.Request.Back -> finish()
                is ApkBrokenDesign.Request.OpenUrl -> openExternalUrl(req.url)
            }
        }
    }
}
