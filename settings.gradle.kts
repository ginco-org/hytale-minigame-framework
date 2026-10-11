pluginManagement {
    repositories {
        gradlePluginPortal()
        // hytest's Gradle plugin publishes its marker here (gg.ginco.hytest).
        mavenCentral()
    }
}

plugins {
    // Puts hygradle (versionless, via the settings plugin) on the build
    // classpath.
    id("gg.ginco.hygradle.settings") version "0.5.0"
}

rootProject.name = "hytale-minigame-framework"
