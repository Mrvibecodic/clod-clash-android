package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.Serializable

@Serializable
data class ProxyGroupNames(
    val direct: Boolean = false,
    val names: List<String> = emptyList(),
    val icons: Map<String, String> = emptyMap(),
    val main: String? = null,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<ProxyGroupNames> {
        override fun createFromParcel(parcel: Parcel): ProxyGroupNames {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ProxyGroupNames?> {
            return arrayOfNulls(size)
        }
    }
}
