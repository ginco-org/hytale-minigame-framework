import org.gradle.api.file.DuplicatesStrategy
import java.nio.file.Path
import org.gradle.api.Task
import com.vanniktech.maven.publish.MavenPublishBaseExtension
plugins {
    // hygradle comes onto the classpath via gg.ginco.hygradle.settings (0.5.0) in
    // settings.gradle.kts, so no version is declared here.
    id("gg.ginco.hygradle")
    kotlin("jvm") version "2.3.20"
    id("com.google.devtools.ksp") version "2.3.9"
    // Integration tests: `@HytaleTest` methods in src/hytaleTest/ run inside a
    // real Hytale server via the `hytaleTest` task. Published to Maven Central
    // (gg.ginco.hytest:runtime/:junit5/:gradle-plugin). The plugin pins those
    // runtime artifacts to its own release (0.1.2 fixed the convention the
    // 0.1.1 plugin hardcoded at apply() time, breaking consumer bumps —
    // ginco-org/hytest#2), so the plugin version is the whole knob.
    id("gg.ginco.hytest") version "0.2.0"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "gg.ginco"
version = "0.2.0"

dependencies {
    // api (not implementation) so the published POM exposes codec as a
    // compile-scope dependency: consumers of the framework's definition API
    // see codec types through it.
    api("gg.ginco:hytale-codec-annotations:1.2.1")
    api("gg.ginco:hytale-codec-runtime:1.2.1")
    ksp("gg.ginco:hytale-codec-processor:1.2.1")
}

// bundleDependencies packs several jars together, some of them
// multi-release: duplicate module-info/class entries are expected and must
// be excluded rather than failing the jar task.
tasks.named<org.gradle.api.tasks.bundling.Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

hytale {
    // kotlin-for-hytale is the server's single Kotlin runtime (see README
    // "Packaging"); our manifest demands it so the server refuses us when
    // the carrier is missing — and load order guarantees the runtime.
    depends("gg.ginco:KotlinForHytale", ">=0.1")
    group = "gg.ginco"
    bundleDependencies = true
    name = "MinigameFramework"
    description = "Minigame framework for Hytale"
    serverVersion = "0.6.8"
    mainClass = "gg.ginco.minigame.Plugin"
    // The carrier (kotlin-for-hytale) owns the Kotlin runtime server-wide;
    // bundling our own would fork the runtime identity per loader. Codec
    // MUST stay bundled: we cannot resolve our own codec classes from the
    // bridge — nobody else carries them.
    bundleExclude("org.jetbrains.kotlin:kotlin-stdlib")
}

// The suite's test server must carry the Kotlin carrier plugin (our manifest
// hard-depends on it). CI checks out the carrier repo at its pinned tag and
// builds it before the suite runs; locally we resolve the same checkout's
// built jar.
val carrierJar: Path? by lazy {
    val dirs = listOf(
        rootProject.file("../kotlin-for-hytale/build/libs"),   // CI sibling checkout
        rootProject.file("/home/agent/projects/kotlin-for-hytale/build/libs")) // the machine's checkout
    dirs.firstNotNullOfOrNull { dir ->
        dir.listFiles { f -> f.name.startsWith("kotlin-for-hytale-") && f.name.endsWith(".jar") }
            ?.maxByOrNull { it.name }?.toPath()
    }
}
if (carrierJar == null) {
    println("WARN: kotlin-for-hytale carrier jar not found — the hytaleTest server will refuse the framework (manifest depends on it). Build kotlin-for-hytale first.")
}

hytest {
    // Forward the server's own logs while debugging a failing test.
    showServerLogs = providers.gradleProperty("hytestLogs").isPresent
    // Dependency plugin jars deployed into the test server's mods/: the
    // Kotlin runtime carrier the framework hard-depends on (see the hytale
    // block above).
    carrierJar?.let { extraMods.set(listOf(it)) }
    // The suite outgrew hytest's default 4G server heap at ~75 tests: torn-down
    // per-test worlds are reclaimed too slowly/lazily, the heap fills near the
    // end of the run and the JVM drops into a GC death spiral (all cores in
    // GC threads, world creation for a late test never completes). 5G holds
    // the whole run with margin locally; it also fits GitHub's 7G
    // standard runners next to the Gradle daemon.
    jvmArgs = listOf("-Xmx5G", "-Xms1G")
    // Server-run budget. The default 300s fits a handful of tests; the full
    // suite runs many more on one server. Keep it generous: it is a CAP, not a
    // delay — a fresh full suite takes ~80s locally, and the fast subset is
    // well under that (see the hytaleTest filter below). 0.1.1's teardown fix
    // removed the per-world chunk-save/-load waits that pushed 2-core runs
    // past earlier ceilings (runs 37448282111/37456263862).
    timeoutSeconds = 3600
    firstFrameGraceSeconds = 180
}

// --- Fast integration subset -------------------------------------------------
// MinigameInstanceWorldTest spawns real instance worlds (one per behavior, two
// in the multi-create test): each pays create + join + settle + stop + teardown
// and dominates the wall clock even after hytest 0.1.1's teardown fix. CI
// passes -PhytestFast to skip the whole class in main-branch integration runs
// and keep the always-on signal quick; nightly and explicit full-suite runs
// (workflow_dispatch, or a plain `hytaleTest` invocation) run everything.
// Everything else (definitions, behaviors, lifecycle, teams/parties/spawn/
// stats/...) costs seconds in total, so the fast tier is the only tier a
// 2-core runner needs per commit.
//
// Two things get gated, and they must stay consistent, because hytest
// discovers tests TWICE and runs the SERVER's list:
//
// 1. The merged Hytest.jar (what the SERVER runs): inside the server, the
//    plugin's own ClasspathTestScanner scans the jar it was loaded from and
//    runs every @HytaleTest method it finds — nothing the Gradle side says
//    can stop that scan. So the fast tier excludes the class from the merged
//    jar's content (measured locally: "run starting: 69 discovered test(s)"
//    without the 3 instance tests, all green).
// 2. The Gradle/worker side (what the JUnit launcher reports): hytest's engine
//    ignores EngineDiscoveryRequest selectors, but Gradle applies the Test
//    task's name filter as a launcher post-discovery filter and prunes the
//    descriptor tree — verified on Gradle 9.5.1 against a same-shaped engine
//    (descriptors need ClassSource/MethodSource; HytestEngine's carry both).
//    Without this the worker would still discover tests the server does not
//    run, and the surplus expectation surfaces as engine-level
//    unknown-failures.

val hytestFastSuite = providers.gradleProperty("hytestFast").isPresent

if (hytestFastSuite) {
    tasks.named<org.gradle.api.tasks.bundling.Jar>("createHytaleTestPlugin") {
        exclude("**/MinigameInstanceWorldTest*.class")
    }
}

tasks.named<Test>("hytaleTest") {
    if (hytestFastSuite) {
        filter { excludeTestsMatching("*.MinigameInstanceWorldTest.*") }
    }
}

// --- Maven Central publication (thin API artifact) ---------------------------
// The default `jar` task is the SELF-CONTAINED plugin jar (hygradle bundles
// runtimeClasspath into it and stamps the plugin manifest) — that is the
// server-distribution artifact, not a dependency. What Central gets instead
// is `thinJar`: exactly this project's classes, with the real dependency
// graph in the POM (kotlin-stdlib, codec via api; the Hytale Server API
// stays compileOnly and is deliberately absent — consumers declare it
// themselves against maven.hytale.com, like every Hytale plugin build).
val thinJar = tasks.register<Jar>("thinJar") {
    // Unclassified: the publication maps component artifacts by classifier, so
    // a classified artifact lands as <artifact>-<version>-thin.jar and leaves
    // the MAIN artifact slot empty (0.1.0 shipped exactly that bug — only POM,
    // sources and javadoc are usable there). The unclassified thin jar collides
    // with the fat jar's filename locally, so it writes to its own directory.
    archiveClassifier.set("")
    destinationDirectory.set(layout.buildDirectory.dir("thinLibs"))
    from(sourceSets.main.get().output)
}

// Point the java component's outgoing variants at thinJar so both the
// publication (vanniktech publishes from(components["java"])) and consumer
// resolution metadata resolve the thin artifact under the main coordinates.
for (variant in listOf("apiElements", "runtimeElements")) {
    configurations.named(variant) {
        val jarTask = tasks.named("jar").get()
        outgoing.artifacts.removeIf { artifact ->
            artifact.buildDependencies.getDependencies(null as Task?).contains(jarTask)
        }
        outgoing.artifact(thinJar)
    }
}

extensions.configure<MavenPublishBaseExtension> {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    pom {
        name = "hytale-minigame-framework"
        description = "Minigame framework for Hytale: instances, phases, teams, regions, roles, stats, behaviors"
        url = "https://github.com/ginco-org/hytale-minigame-framework"
        licenses {
            license {
                name = "Apache-2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "ginco"
                name = "GINCo"
                url = "https://ginco.gg"
            }
        }
        scm {
            connection = "scm:git:git://github.com/ginco-org/hytale-minigame-framework.git"
            developerConnection = "scm:git:ssh://github.com/ginco-org/hytale-minigame-framework.git"
            url = "https://github.com/ginco-org/hytale-minigame-framework"
        }
    }
}
