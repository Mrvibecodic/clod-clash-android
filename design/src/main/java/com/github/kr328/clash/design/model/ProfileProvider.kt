package com.github.kr328.clash.design.model

import android.content.Context
import android.graphics.drawable.Drawable
import com.github.kr328.clash.common.compat.getDrawableCompat
import com.github.kr328.clash.design.R

sealed class ProfileProvider {
    class File(private val context: Context) : ProfileProvider() {
        override val name: String
            get() = context.getString(R.string.file)
        override val summary: String
            get() = context.getString(R.string.import_from_file)
        override val icon: Drawable?
            get() = context.getDrawableCompat(R.drawable.ic_baseline_attach_file)

    }

    class Url(private val context: Context) : ProfileProvider() {
        override val name: String
            get() = context.getString(R.string.url)
        override val summary: String
            get() = context.getString(R.string.import_from_url)
        override val icon: Drawable?
            get() = context.getDrawableCompat(R.drawable.ic_baseline_cloud_download)
    }

    abstract val name: String
    abstract val summary: String
    abstract val icon: Drawable?
}
