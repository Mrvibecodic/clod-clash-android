package com.github.kr328.clash

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.design.R

class WidgetToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        if (StatusClient(this).currentProfile() != null) {
            return finish()
        }

        start()
    }

    private fun start() {
        val vpnRequest = startClashService()

        if (vpnRequest == null) {
            ToggleWidgetProvider.notifyWait(this)
            Toast.makeText(this, R.string.external_control_started, Toast.LENGTH_SHORT).show()

            return finish()
        }

        try {
            @Suppress("DEPRECATION")
            startActivityForResult(vpnRequest, REQUEST_VPN)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.unable_to_start_vpn, Toast.LENGTH_SHORT).show()

            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) {
            if (startClashService() == null) {
                ToggleWidgetProvider.notifyWait(this)
                Toast.makeText(this, R.string.external_control_started, Toast.LENGTH_SHORT).show()
            }
        }

        finish()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val REQUEST_VPN = 1
    }
}
