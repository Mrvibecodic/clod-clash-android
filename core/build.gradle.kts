import android.databinding.tool.ext.capitalizeUS
import com.github.kr328.golang.GolangBuildTask
import com.github.kr328.golang.GolangPlugin

plugins {
    kotlin("android")
    id("com.android.library")
    id("kotlinx-serialization")
    id("golang-android")
}

val golangSource = file("src/main/golang/native")

golang {
    sourceSets {
        create("standard") {
            tags.set(listOf("foss","with_gvisor","cmfa") + listOfNotNull("pprof".takeIf { project.findProperty("clod.pprof") == "true" }))
            srcDir.set(file("src/main/golang"))
        }
        all {
            fileName.set("libclash.so")
            packageName.set("cfa/native")
        }
    }
}

android {
    productFlavors {
        all {
            externalNativeBuild {
                cmake {
                    arguments("-DGO_SOURCE:STRING=${golangSource}")
                    arguments("-DGO_OUTPUT:STRING=${GolangPlugin.outputDirOf(project, null, null)}")
                    arguments("-DFLAVOR_NAME:STRING=$name")
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation(project(":common"))

    implementation(libs.androidx.annotation)
    implementation(libs.kotlin.coroutine)
    implementation(libs.kotlin.serialization.json)

    testImplementation(libs.junit)
}

afterEvaluate {
    tasks.withType(GolangBuildTask::class.java).forEach {
        // Ядро лежит вне srcDir: без этого правка ядра не пересоберёт libclash.so.
        it.inputs.dir("src/foss/golang/clash")
        // Модуль cfa собирается главным: без флага Go вписывает в .so коммит клиента и зовёт git на каждой сборке.
        it.environment("GOFLAGS", listOfNotNull(System.getenv("GOFLAGS"), "-buildvcs=false").joinToString(" "))
    }
}

val abis = listOf("arm64-v8a" to "Arm64V8a", "armeabi-v7a" to "ArmeabiV7a", "x86_64" to "X8664")

androidComponents.onVariants { variant ->
    val cmakeName = if (variant.buildType == "debug") "Debug" else "RelWithDebInfo"

    abis.forEach { (abi, goAbi) ->
        tasks.configureEach {
            if (name.startsWith("buildCMake$cmakeName[$abi]")) {
                dependsOn("externalGolangBuild${variant.name.capitalizeUS()}$goAbi")
            }
        }
    }
}
