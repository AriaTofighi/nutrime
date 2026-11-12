// Maven Central is fetched through Google's mirror first: Maven Central itself often rate-limits (HTTP 429)
// CI and cloud machines. Maven Central and the plugin portal stay listed as fallbacks.
pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
    }
}

rootProject.name = "nutrime-server"
