plugins {
    id("gg.ginco.hygradle")
    kotlin("jvm") version "2.3.20"
    id("com.google.devtools.ksp") version "2.3.9"
}

group = "gg.ginco"
version = "0.1.0"

dependencies {
    implementation("gg.ginco:hytale-codec-annotations:1.1.0")
    implementation("gg.ginco:hytale-codec-runtime:1.1.0")
    ksp("gg.ginco:hytale-codec-processor:1.1.0")
}

hytale {
    group = "gg.ginco"
    bundleDependencies = true
    name = "MinigameFramework"
    description = "Minigame framework for Hytale"
    serverVersion = "0.6.8"
    mainClass = "gg.ginco.minigame.Plugin"
}
