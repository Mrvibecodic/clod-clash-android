package com.github.kr328.clash

import android.content.Intent
import android.net.Uri
import com.github.kr328.clash.design.HelpDesign
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.util.showNoAppForLink
import com.github.kr328.clash.util.startExternal
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
                        is HelpDesign.Request.OpenUrl -> openUrl(it.url)
                    }
                }
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            if (!startExternal(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
                launch { design?.showNoAppForLink(url) }
            }
        } catch (e: Exception) {
            launch { design?.showExceptionToast(e) }
        }
    }
}
