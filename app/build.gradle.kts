import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

// Version shown to users; bump by hand in gradle.properties. The build number (versionCode) must
// always increase between releases; the release workflow sets NUTRIME_BUILD_NUMBER.
val appVersion = providers.gradleProperty("appVersion").get()
val buildNumber = env("NUTRIME_BUILD_NUMBER")?.toIntOrNull() ?: 1
// The one sync server the app talks to. The default reaches a server on the host machine from the
// Android emulator; set NUTRIME_SERVER_URL to your own server's HTTPS URL for real builds.
val syncServerUrl = env("NUTRIME_SERVER_URL") ?: "http://10.0.2.2:8080"
// Release signing key, from the environment (see README "Release builds"). Without it, release
// builds are signed with the local debug key.
val releaseKeystore = env("NUTRIME_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

// Robolectric's Android runtimes (~150-200 MB each). Robolectric would download them at test
// time from Maven Central, which rate-limits CI (HTTP 429). Fetch them up front instead: Google's
// Maven Central mirror first, retrying with backoff, into a folder CI caches between runs.
val robolectricDepsDir: File = rootProject.file(".robolectric")
val robolectricRuntimes = listOf("14-robolectric-10818077-i7", "15-robolectric-12650502-i7")
val downloadRobolectricRuntimes by tasks.registering {
    outputs.upToDateWhen {
        robolectricRuntimes.all { File(robolectricDepsDir, "android-all-instrumented-$it.jar").length() > 0 }
    }
    doLast {
        robolectricDepsDir.mkdirs()
        val mirrors = listOf(
            "https://maven-central.storage-download.googleapis.com/maven2/",
            "https://repo1.maven.org/maven2/",
        )
        for (version in robolectricRuntimes) {
            val name = "android-all-instrumented-$version.jar"
            val target = File(robolectricDepsDir, name)
            if (target.length() > 0) continue
            val path = "org/robolectric/android-all-instrumented/$version/$name"
            var done = false
            for (attempt in 1..5) {
                for (mirror in mirrors) {
                    try {
                        val tmp = File(robolectricDepsDir, "$name.part")
                        URI(mirror + path).toURL().openStream().use { input ->
                            tmp.outputStream().use { input.copyTo(it) }
                        }
                        check(tmp.renameTo(target)) { "could not move $tmp" }
                        logger.lifecycle("Downloaded $name (${target.length()} bytes) from $mirror")
                        done = true
                        break
                    } catch (e: Exception) {
                        logger.warn("Attempt $attempt: $mirror failed for $name: ${e.message}")
                    }
                }
                if (done) break
                Thread.sleep(15_000L * attempt)
            }
            if (!done) throw GradleException("Could not download $name")
        }
    }
}

android {
    namespace = "com.nutrime.app"

    // Nutrient definitions, DRI targets and profile math, shared with the server's AI assistant.
    sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
    // Room's exported schemas, for MigrationTest.
    sourceSets["test"].assets.srcDir("$projectDir/schemas")
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nutrime.app"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = appVersion
        buildConfigField("String", "SYNC_SERVER_URL", "\"$syncServerUrl\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = env("NUTRIME_KEYSTORE_PASSWORD")
                keyAlias = env("NUTRIME_KEY_ALIAS") ?: "nutrime"
                keyPassword = env("NUTRIME_KEY_PASSWORD") ?: env("NUTRIME_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        noCompress += "db"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            it.systemProperty("screenshotDir", rootProject.layout.buildDirectory.dir("screenshots").get().asFile.path)
            System.getenv("SYNC_TEST_URL")?.let { url -> it.environment("SYNC_TEST_URL", url) }
            listOf("SYNC_TEST_EMAIL", "SYNC_TEST_INVITATION_CODE").forEach { name ->
                System.getenv(name)?.let { value -> it.environment(name, value) }
            }
            it.maxHeapSize = "3g"
            it.dependsOn(downloadRobolectricRuntimes)
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.dependency.dir", robolectricDepsDir.path)
            it.testLogging {
                events("failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showStandardStreams = false
            }
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)
    implementation(libs.play.code.scanner)
    implementation(libs.health.connect)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
