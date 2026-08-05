package com.github.kr328.clash.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Манифест обновлений — тот самый latest.json, который выкладывает релизный workflow.
 *
 * Схема повторяет десктопную, но с двумя добавками, обязательными для Android:
 *  - [versionCode]: система сравнивает версии монотонным числом, а не строкой, и откат
 *    на меньший versionCode запрещён на уровне установщика;
 *  - sha256 у каждого файла: ключ подписи APK гарантирует, что чужую сборку поверх нашей
 *    не поставят, но подмену при загрузке ловит именно хеш.
 */
@Serializable
data class UpdateManifest(
    val version: String,
    val versionCode: Long,
    val notes: String = "",
    @SerialName("pub_date") val pubDate: String = "",
    val channel: String = "release",
    val platforms: Map<String, Platform> = emptyMap(),
) {
    @Serializable
    data class Platform(
        val url: String,
        val sha256: String,
    )

    /**
     * Файл под конкретное устройство. Сначала ищем APK ровно под ABI устройства
     * (он в несколько раз меньше), при отсутствии — универсальный.
     *
     * Порядок [abis] важен: Build.SUPPORTED_ABIS отсортирован по предпочтительности,
     * и на 64-битном устройстве первым идёт arm64-v8a, хотя armeabi-v7a тоже подойдёт.
     */
    fun platformFor(abis: List<String>): Platform? =
        abis.firstNotNullOfOrNull { platforms[it] } ?: platforms["universal"]
}
