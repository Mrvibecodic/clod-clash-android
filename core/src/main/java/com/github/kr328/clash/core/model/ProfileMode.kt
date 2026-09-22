package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProfileMode(
    val mode: TunnelState.Mode? = null,
    val source: Source = Source.Template,
) : Parcelable {
    @Serializable
    enum class Source {
        @SerialName("template")
        Template,

        @SerialName("override")
        Override,

        @SerialName("choice")
        Choice,

        @SerialName("locked")
        Locked,
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<ProfileMode> {
        override fun createFromParcel(parcel: Parcel): ProfileMode {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ProfileMode?> {
            return arrayOfNulls(size)
        }
    }
}
