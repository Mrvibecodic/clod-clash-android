package com.github.kr328.clash.service

import android.content.Context
import android.content.SharedPreferences
import com.github.kr328.clash.common.constants.Authorities
import rikka.preference.MultiProcessPreference
import rikka.preference.PreferenceProvider

class DiagnosticsPreferenceProvider : PreferenceProvider() {
    override fun onCreatePreference(context: Context): SharedPreferences {
        return context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        const val FILE_NAME = "diagnostics_credentials"

        fun createSharedPreferencesFromContext(context: Context): SharedPreferences {
            return when (context) {
                is BaseService, is TunService ->
                    context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
                else -> MultiProcessPreference(context, Authorities.DIAGNOSTICS_SETTINGS_PROVIDER)
            }
        }
    }
}
