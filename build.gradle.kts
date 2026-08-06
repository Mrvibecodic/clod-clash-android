@file:Suppress("UNUSED_VARIABLE")

import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
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

    // -Pclod.abi=arm64-v8a — собрать только под одну архитектуру.
    // ABI перечислены в четырёх независимых местах (ndk, cmake, splits, golang),
    // поэтому сужение вынесено сюда, чтобы они не разъехались.
    val abiList: List<String> = (project.findProperty("clod.abi") as String?)
        ?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
        // x86 (32-битный) выброшен: живых устройств нет, нужен только древним
        // эмуляторам, а стоит ~25 с сборки Go на каждый прогон.
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

            // clod: пакет исходников намеренно оставлен апстримным (com.github.kr328.clash).
            // Смена пакета тянет переименование всех JNI-символов Java_com_github_kr328_clash_*
            // в core/src/main/cpp/main.c и ничего не даёт: пользователю виден applicationId, не пакет.
            project.name.let { name ->
                namespace = if (name == "app") "com.github.kr328.clash"
                else "com.github.kr328.clash.$name"
            }

            // clod: minSdk 23 — при 21 AGP включает legacy packaging (extractNativeLibs=true),
            // что несовместимо с требованием 16 KB page size для Google Play.
            minSdk = 23
            targetSdk = 35

            // clod: нумерация как на десктопе — начинаем с 0.0.1-alpha.
            // versionCode = major * 1_000_000 + minor * 10_000 + patch,
            // суффикс -alpha на versionCode не влияет (система сравнивает только число).
            versionName = "0.0.1-alpha"
            versionCode = 1

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

        // clod: у апстрима два flavor'а (alpha/meta) с одинаковым PREMIUM=false — они различались
        // только суффиксом имени и applicationId. Нам нужна одна сборка, поэтому meta удалён,
        // а суффиксы сняты: applicationId должен совпадать с установленным, иначе самообновление
        // из GitHub система посчитает установкой другого приложения.
        productFlavors {
            flavorDimensions("feature")

            create("standard") {
                isDefault = true
                dimension = flavorDimensionList[0]

                buildConfigField("boolean", "PREMIUM", "Boolean.parseBoolean(\"false\")")

                // resValue, а не строка в strings.xml: блок subprojects применяется ко ВСЕМ
                // модулям, поэтому launch_name/application_name попадают в R каждого из них.
                // Объявление в одном модуле не видно остальным: TileService берёт R из service,
                // а вёрстка design — из design.
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
            if (keystore.exists()) {
                create("release") {
                    val prop = Properties().apply {
                        keystore.inputStream().use(this::load)
                    }

                    // clod: апстрим держал release.keystore прямо в репозитории.
                    // Теперь путь задаётся в signing.properties (CI пишет его из секрета).
                    storeFile = rootProject.file(prop.getProperty("keystore.path") ?: "release.keystore")
                    storePassword = prop.getProperty("keystore.password")!!
                    keyAlias = prop.getProperty("key.alias")!!
                    keyPassword = prop.getProperty("key.password")!!
                }
            }
        }

        buildTypes {
            named("release") {
                isMinifyEnabled = isApp
                isShrinkResources = isApp
                signingConfig = signingConfigs.findByName("release") ?: signingConfigs["debug"]
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
            named("debug") {
                versionNameSuffix = ".debug"
            }
        }

        buildFeatures.apply {
            // Вёрстка (42 layout-файла) есть только в design, но app тоже обязан
            // держать dataBinding включённым: класс design.BR генерируется на этапе
            // сборки приложения, и без него R8 падает на minify.
            // В core/service/common его нет — там задачи dataBinding отрабатывали
            // вхолостую (~40 с на прогон).
            dataBinding {
                isEnabled = name == "design" || name == "app"
            }
        }

        if (isApp) {
            this as AppExtension

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