pluginManagement {
    // hytest is not published yet — consume it as a local composite build.
    // Resolution order: a sibling checkout (../hytest — what CI uses, checking
    // out both repos side by side) then the supervisor machine's checkout.
    val hytestDir = listOf(file("hytest"), file("../hytest"), file("/home/agent/projects/hytest"))
        .firstOrNull { it.resolve("settings.gradle.kts").exists() }
        ?: throw GradleException(
            "hytest not found: check out https://github.com/ginco-org/hytest " +
                "next to this repository (as ../hytest) to build.")
    includeBuild(hytestDir)
    println("Using hytest from: $hytestDir")

    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Puts hygradle (versionless, via the settings plugin) on the build
    // classpath; hytest is consumed from the composite build above.
    id("gg.ginco.hygradle.settings") version "0.5.0"
}

dependencyResolutionManagement {
    // Substitutes gg.ginco.hytest:runtime / :junit5 with the local build.
    val hytestDir = listOf(file("hytest"), file("../hytest"), file("/home/agent/projects/hytest"))
        .firstOrNull { it.resolve("settings.gradle.kts").exists() }
        ?: throw GradleException(
            "hytest not found: check out https://github.com/ginco-org/hytest " +
                "next to this repository (as ../hytest) to build.")
    includeBuild(hytestDir)

    // hytale-codec composite (same candidates as pluginManagement above).
    val codecDir = listOf(file("hytale-codec"), file("../hytale-codec"), file("/home/agent/projects/hytale-codec"))
        .firstOrNull { it.resolve("settings.gradle.kts").exists() }
    if (codecDir != null) {
        includeBuild(codecDir) {
            dependencySubstitution {
                substitute(module("gg.ginco:hytale-codec-annotations")).using(project(":hytale-codec-annotations"))
                substitute(module("gg.ginco:hytale-codec-processor")).using(project(":hytale-codec-processor"))
                substitute(module("gg.ginco:hytale-codec-runtime")).using(project(":hytale-codec-runtime"))
            }
        }
    }
}

rootProject.name = "hytale-minigame-framework"
