pluginManagement {
	repositories {
		maven("https://maven.fabricmc.net/") { name = "Fabric" }
		gradlePluginPortal()
		mavenCentral()
	}
}

// The Fabric runtime builds for Minecraft 26.x, which needs JDK 25: an installed one is found, or this downloads one.
plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "spawnproof"

// The in-game half: the test API and runner that test mods build on (fabric/README.md).
include("fabric")
