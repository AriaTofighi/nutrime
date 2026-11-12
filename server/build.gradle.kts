plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    application
}

group = "com.nutrime"
version = "1.0.0"

repositories {
    // Google's Maven Central mirror first (see settings.gradle.kts), Maven Central as a fallback.
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
}

val ktor = "3.1.3"
val exposed = "0.61.0"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-server-auth:$ktor")
    implementation("io.ktor:ktor-server-auth-jwt:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("io.ktor:ktor-server-call-logging:$ktor")
    implementation("org.jetbrains.exposed:exposed-core:$exposed")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("com.h2database:h2:2.3.232")
    implementation("at.favre.lib:bcrypt:0.10.2")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    // Reads the bundled USDA food database (the same foods.db the Android app ships) for the AI assistant.
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktor")
    testImplementation(kotlin("test-junit"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// Nutrient definitions, DRI targets and profile math, shared with the Android app.
sourceSets { main { kotlin.srcDir("../shared/src/main/kotlin") } }

tasks.test {
    // Tests read the bundled food database straight from the Android app's assets.
    systemProperty("nutrime.foodsDb", rootProject.file("../app/src/main/assets/foods.db").absolutePath)
}

application { mainClass.set("com.nutrime.server.ApplicationKt") }

// Trusted server-side bootstrap/recovery; prints one code to the operator, never to server logs.
tasks.register<JavaExec>("createInvite") {
    group = "application"
    description = "Creates a single-use invitation for -PinviteEmail=... (optional -PinviteDays=7)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.nutrime.server.ApplicationKt")
    args("invite", providers.gradleProperty("inviteEmail").get(), providers.gradleProperty("inviteDays").getOrElse("7"))
}

// Runs the assistant with the real model on evals/cases.json (see AssistantEval.kt). It calls the Gemini API,
// so it is never part of `test`; run it only when asked: GEMINI_API_KEY=... ./gradlew evalAssistant
tasks.register<JavaExec>("evalAssistant") {
    group = "verification"
    description = "Checks the assistant's proposals on fixed cases with the real model."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.nutrime.server.AssistantEvalKt")
    systemProperty("nutrime.foodsDb", rootProject.file("../app/src/main/assets/foods.db").absolutePath)
    workingDir = projectDir
}
