import org.gradle.api.file.DuplicatesStrategy
plugins {
    // hygradle comes onto the classpath via gg.ginco.hygradle.settings (0.5.0) in
    // settings.gradle.kts, so no version is declared here.
    id("gg.ginco.hygradle")
    kotlin("jvm") version "2.3.20"
    id("com.google.devtools.ksp") version "2.3.9"
    // Integration tests: `@HytaleTest` methods in src/hytaleTest/ run inside a
    // real Hytale server via the `hytaleTest` task. Published to Maven Central
    // (gg.ginco.hytest:runtime/:junit5/:gradle-plugin) — the plugin adds the
    // runtime artifacts itself, pinned to its own version.
    id("gg.ginco.hytest") version "0.1.0"
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
    // The suite outgrew hytest's default 4G server heap at ~75 tests: torn-down
    // per-test worlds are reclaimed too slowly/lazily, the heap fills near the
    // end of the run and the JVM drops into a GC death spiral (all cores in
    // GC threads, world creation for a late test never completes). 5G holds
    // the whole 76-test run with margin locally; it also fits GitHub's 7G
    // standard runners next to the Gradle daemon.
    jvmArgs = listOf("-Xmx5G", "-Xms1G")
    // Server-run budget. The default 300s fits a handful of tests; this
    // suite runs 68 on one server. Per-test lobby teardowns are fast since
    // hytest@5af8992 (canSaveChunks=false), but instance-world tests still
    // pay a ~30s removal save each on 2-core CI runners, so the whole run
    // needs well over 10 min there (runs 37448282111/37456263862 expired
    // earlier ceilings). A cap, not a delay — local fresh runs take ~80s.
    timeoutSeconds = 3600
    firstFrameGraceSeconds = 180
}
