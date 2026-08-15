import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.application")
    id("kotlinx-serialization")
}

android {
    buildFeatures {
        compose = true
    }
}

dependencies {
    compileOnly(project(":hideapi"))

    implementation(project(":core"))
    implementation(project(":service"))
    implementation(project(":design"))
    implementation(project(":common"))

    implementation(libs.kotlin.coroutine)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.coordinator)
    implementation(libs.google.material)
    implementation(libs.quickie.bundled)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.kotlin.serialization.json)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}

tasks.getByName("clean", type = Delete::class) {
    delete(file("release"))
}

val geoFilesDownloadDir = "src/main/assets"

data class GeoAsset(
    val url: String,
    val outputFileName: String,
    val sha256: String,
)

val geoAssets = listOf(
    GeoAsset(
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb",
        "geoip.metadb",
        "71def3ab45393093baf9d39b69787b3e0d0685607c13dd135bfdbb603bd475dd",
    ),
    GeoAsset(
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat",
        "geosite.dat",
        "8c9e9ec13807174ffb3582d95655e00559af3fb30253b5e30c0385e46366d9dc",
    ),
    GeoAsset(
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb",
        "ASN.mmdb",
        "82abcabdf4d0ecb34da45e4f0f9bc30bf933cfbfec446b89a2215fae5b1fdbdc",
    ),
    GeoAsset(
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/BundleMRS.7z",
        "BundleMRS.7z",
        "965d2536dcc918a04c3b72be97c87b0727db005d423de8334f9c1171c7e24cf9",
    ),
)

task("downloadGeoFiles") {
    doLast {
        geoAssets.forEach { asset ->
            val outputPath = file("$geoFilesDownloadDir/${asset.outputFileName}")
            outputPath.parentFile.mkdirs()
            val temporaryPath = Files.createTempFile(
                outputPath.parentFile.toPath(),
                asset.outputFileName,
                ".download",
            )
            try {
                URL(asset.url).openStream().use { input ->
                    Files.copy(input, temporaryPath, StandardCopyOption.REPLACE_EXISTING)
                }
                val digest = MessageDigest.getInstance("SHA-256")
                val actual = temporaryPath.toFile().inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                }
                check(actual == asset.sha256) {
                    "Checksum mismatch for ${asset.outputFileName}"
                }
                Files.move(
                    temporaryPath,
                    outputPath.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                println("${asset.outputFileName} verified and stored in $outputPath")
            } finally {
                Files.deleteIfExists(temporaryPath)
            }
        }
    }
}

afterEvaluate {
    val downloadGeoFilesTask = tasks["downloadGeoFiles"]

    tasks.forEach {
        if (it.name.startsWith("assemble")) {
            it.dependsOn(downloadGeoFilesTask)
        }
    }
}

tasks.getByName("clean", type = Delete::class) {
    delete(file(geoFilesDownloadDir))
}
