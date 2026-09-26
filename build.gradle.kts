plugins {
    // hygradle comes onto the classpath via gg.ginco.hygradle.settings (0.5.0) in
    // settings.gradle.kts, so no version is declared here.
    id("gg.ginco.hygradle")
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    id("com.google.devtools.ksp") version "2.3.9"
    // Integration tests: `@HytaleTest` methods in src/hytaleTest/ run inside a
    // real Hytale server via the `hytaleTest` task.
    id("gg.ginco.hytest")
}

group = "gg.ginco"
version = "0.1.0"

dependencies {
    implementation("gg.ginco:hytale-codec-annotations:1.1.0")
    implementation("gg.ginco:hytale-codec-runtime:1.1.0")
    ksp("gg.ginco:hytale-codec-processor:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

hytale {
    group = "gg.ginco"
    bundleDependencies = true
    name = "MinigameFramework"
    description = "Minigame framework for Hytale"
    serverVersion = "0.6.8"
    mainClass = "gg.ginco.minigame.Plugin"
}

hytest {
    // Forward the server's own logs while debugging a failing test.
    showServerLogs = providers.gradleProperty("hytestLogs").isPresent
    // CI runners are 2-core: boot + 41 fresh worlds measured ~25s/test on
    // ubuntu-latest (~15x local) — the whole suite needs >900s there.
    // A cap, not a delay — local runs are unaffected.
    timeoutSeconds = 1800
    firstFrameGraceSeconds = 180
}
