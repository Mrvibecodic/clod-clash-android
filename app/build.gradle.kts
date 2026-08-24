import java.net.HttpURLConnection
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
    implementation(libs.androidx.appcompat)
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

val geoFilesConnectTimeout = 30_000

val geoFilesReadTimeout = 120_000

val geoFilesMaxAge = 24L * 60 * 60 * 1000

val geoFilesUrls = mapOf(
    "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb" to "geoip.metadb",
    "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat" to "geosite.dat",
    "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb" to "ASN.mmdb",
    "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/BundleMRS.7z" to "BundleMRS.7z",
)

val geoFilesChecksums = layout.buildDirectory.file("geo/geo-sha256.txt")

task("downloadGeoFiles") {
    mustRunAfter("clean")

    inputs.property("sources", geoFilesUrls.keys.sorted())

    outputs.files(geoFilesUrls.values.map { file("$geoFilesDownloadDir/$it") })
    outputs.file(geoFilesChecksums)
    outputs.upToDateWhen {
        geoFilesUrls.values.all {
            val downloaded = file("$geoFilesDownloadDir/$it")

            downloaded.isFile &&
                downloaded.length() > 0 &&
                System.currentTimeMillis() - downloaded.lastModified() < geoFilesMaxAge
        }
    }

    doLast {
        val checksums = StringBuilder()

        geoFilesUrls.forEach { (downloadUrl, outputFileName) ->
            val outputPath = file("$geoFilesDownloadDir/$outputFileName")

            outputPath.parentFile.mkdirs()

            val connection = URL(downloadUrl).openConnection() as HttpURLConnection

            connection.connectTimeout = geoFilesConnectTimeout
            connection.readTimeout = geoFilesReadTimeout
            connection.instanceFollowRedirects = true

            try {
                if (connection.responseCode !in 200..299) {
                    throw GradleException("$outputFileName: HTTP ${connection.responseCode}")
                }

                connection.inputStream.use { input ->
                    Files.copy(input, outputPath.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                connection.disconnect()
            }

            val checksum = MessageDigest.getInstance("SHA-256")
                .digest(outputPath.readBytes())
                .joinToString("") { "%02x".format(it) }

            checksums.appendLine("$checksum  $outputFileName")

            println("$outputFileName downloaded to $outputPath")
        }

        val report = geoFilesChecksums.get().asFile

        report.parentFile.mkdirs()
        report.writeText(checksums.toString())

        println(checksums.toString().trimEnd())
    }
}

tasks.matching {
    it.name.startsWith("assemble") ||
        it.name.startsWith("bundle") ||
        it.name.contains("Assets")
}.configureEach {
    dependsOn("downloadGeoFiles")
}

tasks.getByName("clean", type = Delete::class) {
    delete(file(geoFilesDownloadDir))
}
