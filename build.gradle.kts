import org.gradle.api.file.DuplicatesStrategy
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
    // (gg.ginco.hytest:runtime/:junit5/:gradle-plugin) — the plugin adds the
    // runtime artifacts itself, pinned to its own version.
    id("gg.ginco.hytest") version "0.1.0"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "gg.ginco"
version = "0.1.1"

dependencies {
    // api (not implementation) so the published POM exposes codec as a
    // compile-scope dependency: consumers of the framework's definition API
    // see codec types through it.
    api("gg.ginco:hytale-codec-annotations:1.2.1")
    api("gg.ginco:hytale-codec-runtime:1.2.1")
    ksp("gg.ginco:hytale-codec-processor:1.2.1")
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
