package com.github.kr328.clash

import android.content.Intent
import android.net.Uri
import com.github.kr328.clash.design.ApkBrokenDesign
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.util.showNoAppForLink
import com.github.kr328.clash.util.startExternal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive

class ApkBrokenActivity : BaseActivity<ApkBrokenDesign>() {
    override suspend fun main() {
        val design = ApkBrokenDesign(this)

        setContentDesign(design)

        while (isActive) {
            when (val req = design.requests.receive()) {
                ApkBrokenDesign.Request.Back -> finish()
                is ApkBrokenDesign.Request.OpenUrl -> try {
                    if (!startExternal(Intent(Intent.ACTION_VIEW).setData(Uri.parse(req.url)))) {
                        design.showNoAppForLink(req.url)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    design.showExceptionToast(e)
                }
            }
        }
    }
}
