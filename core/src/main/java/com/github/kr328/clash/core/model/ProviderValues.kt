package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@Serializable
data class ProviderValues(
    val groups: List<Group> = emptyList(),
    val stack: String = "",
) : Parcelable {
    @Serializable
    data class Group(
        val id: String,
        val lines: List<Line> = emptyList(),
    )

    @Serializable
    data class Line(
        val key: String,
        val now: String,
        val becomes: String,
        val reset: List<String> = emptyList(),
    )

    sealed interface Value {
        data class Text(val text: String) : Value
        data class Flag(val on: Boolean) : Value
        data class Items(val items: List<String>) : Value
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<ProviderValues> {
        const val GROUP_MODE = "mode"
        const val GROUP_LOG = "log"
        const val GROUP_PORTS = "ports"
        const val GROUP_TUN = "tun"
        const val GROUP_DNS = "dns"
        const val GROUP_FEATURES = "features"

        const val MODE = "mode"
        const val MODE_CHOICE = "mode.choice"
        const val TUN_STACK = "tun.stack"

        fun value(json: String): Value = when (val element = Json.parseToJsonElement(json)) {
            is JsonNull -> Value.Items(emptyList())
            is JsonPrimitive -> element.booleanOrNull
                ?.takeUnless { element.isString }
                ?.let { Value.Flag(it) }
                ?: Value.Text(element.content)
            is JsonArray -> Value.Items(element.map(::item))
            is JsonObject -> Value.Items(element.map { (key, value) -> "$key = ${item(value)}" })
        }

        private fun item(element: JsonElement): String = when (element) {
            is JsonArray -> element.joinToString(", ", transform = ::item)
            is JsonPrimitive -> element.content
            is JsonObject -> element.toString()
        }

        override fun createFromParcel(parcel: Parcel): ProviderValues {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ProviderValues?> {
            return arrayOfNulls(size)
        }
    }
}
