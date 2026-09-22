pluginManagement {
    // hytest is not published to the Gradle Plugin Portal — consume it as a
    // local composite build (same pattern as hytest's own example/).
    includeBuild("/home/agent/projects/hytest")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // Substitutes gg.ginco.hytest:runtime / :junit5 with the local build.
    includeBuild("/home/agent/projects/hytest")
}

plugins {
    id("gg.ginco.hygradle.settings") version "0.5.0"
}

rootProject.name = "hytale-minigame-framework"