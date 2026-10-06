import org.gradle.api.file.DuplicatesStrategy
plugins {
    // hygradle comes onto the classpath via gg.ginco.hygradle.settings (0.5.0) in
    // settings.gradle.kts, so no version is declared here.
    id("gg.ginco.hygradle")
    kotlin("jvm") version "2.3.20"
    id("com.google.devtools.ksp") version "2.3.9"
    // Integration tests: `@HytaleTest` methods in src/hytaleTest/ run inside a
    // real Hytale server via the `hytaleTest` task.
    id("gg.ginco.hytest")
}

group = "gg.ginco"
version = "0.1.0"

dependencies {
    implementation("gg.ginco:hytale-codec-annotations:1.2.0")
    implementation("gg.ginco:hytale-codec-runtime:1.2.0")
    ksp("gg.ginco:hytale-codec-processor:1.2.0")
}

// bundleDependencies packs several jars together (kotlinx-serialization is
// multi-release): duplicate module-info/class entries are expected and must
// be excluded rather than failing the jar task.
tasks.named<org.gradle.api.tasks.bundling.Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
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
    // Server-run budget. The default 300s fits a handful of tests, but this
    // suite runs 68 tests on one server, and each per-test world teardown
    // costs ~30s on 2-core CI runners (server chunk-save pipeline during
    // world removal): 68 × ~32s ≈ 36 min — run 37448282111 expired the
    // previous 1800s budget right before the alphabetically-last test
    // class could run. 3600s ceilings the whole run well under GitHub's
    // job limit; locally the suite finishes in ~2-3 min, so the ceiling
    // only matters on CI. A cap, not a delay — local runs are unaffected.
    timeoutSeconds = 3600
    firstFrameGraceSeconds = 180
}
