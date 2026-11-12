// Maven Central is fetched through Google's mirror first: Maven Central itself often rate-limits (HTTP 429)
// CI and cloud machines. Maven Central stays listed as a fallback.
pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        google()
        mavenCentral()
    }
}
rootProject.name = "NutriMe"
include(":app")
