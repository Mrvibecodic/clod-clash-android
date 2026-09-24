plugins {
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.library")
}

android {
    buildFeatures {
        compose = true
    }
}

tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.layout.projectDirectory.files(
            "design/src/main/res/values/strings.xml",
            "design/src/main/res/values-ru/strings.xml",
            "service/src/main/res/values/strings.xml",
            "service/src/main/res/values-ru/strings.xml",
            "common/src/main/res/values/strings.xml",
            "common/src/main/res/values-ru/strings.xml",
        ),
    )
        .withPropertyName("localeParityStrings")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(project(":common"))
    implementation(project(":core"))
    implementation(project(":service"))

    testImplementation(libs.junit)

    implementation(libs.kotlin.coroutine)
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
}
