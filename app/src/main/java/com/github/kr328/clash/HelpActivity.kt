package com.github.kr328.clash

import com.github.kr328.clash.design.HelpDesign
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class HelpActivity : BaseActivity<HelpDesign>() {
    override suspend fun main() {
        val design = HelpDesign(this)

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive { }
                design.requests.onReceive {
                    when (it) {
                        HelpDesign.Request.Back -> finish()
                        is HelpDesign.Request.OpenUrl -> openExternalUrl(it.url)
                    }
                }
            }
        }
    }
}
