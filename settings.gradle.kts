rootProject.name = "ClodClashAndroid"

include(":app")
include(":core")
include(":service")
include(":design")
include(":common")
include(":hideapi")
include(":screenshots")

pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }
}
