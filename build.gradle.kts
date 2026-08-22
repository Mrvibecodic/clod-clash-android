@file:Suppress("UNUSED_VARIABLE")

import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import groovy.json.JsonOutput
import java.net.URL
import java.util.*

buildscript {
    repositories {
        mavenCentral()
        google()
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases")
    }
    dependencies {
        classpath(libs.build.android)
        classpath(libs.build.kotlin.common)
        classpath(libs.build.kotlin.serialization)
        classpath(libs.build.kotlin.compose)
        classpath(libs.build.ksp)
        classpath(libs.build.golang)
    }
}

subprojects {
    repositories {
        mavenCentral()
        google()
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases")
    }

    val isApp = name == "app"

    val abiList: List<String> = (project.findProperty("clod.abi") as String?)
        ?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
        ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")

    apply(plugin = if (isApp) "com.android.application" else "com.android.library")

    fun queryConfigProperty(key: String): Any? {
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(localPropertiesFile.inputStream())
        } else {
            return null
        }
        return localProperties.getProperty(key)
    }

    extensions.configure<BaseExtension> {
        buildFeatures.buildConfig = true
        defaultConfig {
            if (isApp) {
                val customApplicationId = queryConfigProperty("custom.application.id") as? String?
                applicationId = customApplicationId.takeIf { it?.isNotBlank() == true } ?: "io.clodclash.app"
            }

            project.name.let { name ->
                namespace = if (name == "app") "com.github.kr328.clash"
                else "com.github.kr328.clash.$name"
            }

            minSdk = 23
            targetSdk = 35

            versionName = "0.1.15"
            versionCode = 10015

            resValue("string", "release_name", "v$versionName")
            resValue("integer", "release_code", "$versionCode")

            ndk {
                abiFilters += abiList
            }

            externalNativeBuild {
                cmake {
                    abiFilters(*abiList.toTypedArray())
                }
            }

            if (!isApp) {
                consumerProguardFiles("consumer-rules.pro")
            } else {
                setProperty("archivesBaseName", "clodclash-$versionName")
            }
        }

        ndkVersion = "29.0.14206865"

        compileSdkVersion(defaultConfig.targetSdk!!)

        if (isApp) {
            packagingOptions {
                resources {
                    excludes.add("DebugProbesKt.bin")
                }
            }
        }

        productFlavors {
            flavorDimensions("feature")

            create("standard") {
                isDefault = true
                dimension = flavorDimensionList[0]

                buildConfigField("boolean", "PREMIUM", "Boolean.parseBoolean(\"false\")")
                buildConfigField(
                    "boolean",
                    "DIAGNOSTICS_AVAILABLE",
                    rootProject.file("core/src/main/golang/native/diagnostics_credentials_generated.go").exists().toString(),
                )
                buildConfigField(
                    "String",
                    "DIAGNOSTICS_ENDPOINT",
                    JsonOutput.toJson(System.getenv("DIAGNOSTICS_ENDPOINT").orEmpty()),
                )

                resValue("string", "launch_name", "Clod Clash")
                resValue("string", "application_name", "Clod Clash")
            }
        }

        sourceSets {
            getByName("standard") {
                java.srcDirs("src/foss/java")
            }
        }

        signingConfigs {
            val keystore = rootProject.file("signing.properties")
            if (isApp && keystore.exists()) {
                create("release") {
                    val prop = Properties().apply {
                        keystore.inputStream().use(this::load)
                    }

                    fun requiredProperty(name: String): String =
                        prop.getProperty(name)?.takeIf(String::isNotBlank)
                            ?: throw GradleException("Missing release signing property: $name")

                    val releaseKeystore = rootProject.file(requiredProperty("keystore.path"))
                    if (!releaseKeystore.isFile) {
                        throw GradleException("Release keystore does not exist")
                    }

                    storeFile = releaseKeystore
                    storePassword = requiredProperty("keystore.password")
                    keyAlias = requiredProperty("key.alias")
                    keyPassword = requiredProperty("key.password")
                }
            }
        }

        buildTypes {
            named("release") {
                isMinifyEnabled = isApp
                isShrinkResources = isApp
                if (isApp) signingConfig = signingConfigs.findByName("release")
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
            named("debug") {
                versionNameSuffix = ".debug"
            }
        }

        if (isApp) {
            this as AppExtension

            // A release variant without protected signing input would produce an
            // unsigned artifact. Do not create that variant at all.
            variantFilter {
                if (buildType.name == "release" && !rootProject.file("signing.properties").isFile) {
                    ignore = true
                }
            }

            splits {
                abi {
                    isEnable = true
                    isUniversalApk = true
                    reset()
                    include(*abiList.toTypedArray())
                }
            }
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }
}

task("clean", type = Delete::class) {
    delete(rootProject.buildDir)
}

tasks.wrapper {
    distributionType = Wrapper.DistributionType.ALL

    doLast {
        val sha256 = URL("$distributionUrl.sha256").openStream()
            .use { it.reader().readText().trim() }

        file("gradle/wrapper/gradle-wrapper.properties")
            .appendText("distributionSha256Sum=$sha256")
    }
}
