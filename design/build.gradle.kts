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

dependencies {
    implementation(project(":common"))
    implementation(project(":core"))
    implementation(project(":service"))

    // Тесты на чистые считалки слоя интерфейса (размер трафика, имя файла
    // логов, проверки полей ввода). Считают на JVM: ни телефона, ни эмулятора,
    // ни разметки им не нужно.
    testImplementation(libs.junit)

    implementation(libs.kotlin.coroutine)
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.coordinator)
    // RecyclerView убран: списков на нём не осталось, все переехали на LazyColumn.
    implementation(libs.androidx.fragment)
    implementation(libs.google.material)

    // Jetpack Compose. Разметки в модуле не осталось ни одной: экраны, диалоги
    // и списки живут только здесь, поэтому нет ни dataBinding, ни kapt.
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
}
