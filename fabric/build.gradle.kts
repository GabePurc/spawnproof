// The in-game half of Spawnproof for Fabric: the test API that test mods write their tests with, the runner that runs them
// and writes their results, and what makes test games fast (a clock that runs on drawn frames, light settings). A mod puts
// it on its client game test classpath; nothing here goes in the jar players install.
plugins {
	id("net.fabricmc.fabric-loom") version "1.18.4"
	`maven-publish`
}

group = "io.github.gabepurc"
version = rootProject.version
base.archivesName = "spawnproof-fabric"

val minecraftVersion = "26.3"
val loaderVersion = "0.19.5"
val fabricApiVersion = "0.162.0+26.3"

dependencies {
	minecraft("com.mojang:minecraft:$minecraftVersion")
	implementation("net.fabricmc:fabric-loader:$loaderVersion")
	// What the runner builds on. A test mod brings its own Fabric API, so it isn't a dependency of the published jar.
	compileOnly(fabricApi.module("fabric-client-gametest-api-v1", fabricApiVersion))
	localRuntime("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
	compileOnly("org.lwjgl:lwjgl-sdl:3.4.3") // (The game brings it at run time.)

	testImplementation(platform("org.junit:junit-bom:5.11.4"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
	useJUnitPlatform()
	testLogging {
		events("failed")
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
	}
}

java {
	toolchain.languageVersion = JavaLanguageVersion.of(25)
	withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 25
	options.encoding = "UTF-8"
}

tasks.processResources {
	val props = mapOf("version" to project.version, "minecraft" to minecraftVersion)
	inputs.properties(props)
	filesMatching("fabric.mod.json") { expand(props) }
}

// Spawnproof's own tests of the runner: a test mod (src/gametest) with tests that pass, fail and throw on purpose, run in a
// real game by ./gradlew :fabric:runClientGameTest. Its results land in build/run/clientGameTest/spawnproof/.
fabricApi {
	configureTests {
		createSourceSet = true
		modId = "spawnproof-selftest"
		enableGameTests = false
		enableClientGameTests = true
		eula = true
	}
}
loom.runs.matching { it.name == "clientGameTest" }.configureEach { runDir("build/run/clientGameTest") }
// -Pspawnproof.tests=selftest-failing and the like reach the game.
val gameProperties = listOf("spawnproof.tests", "spawnproof.skip", "spawnproof.clock", "spawnproof.graphics")
	.mapNotNull { name -> providers.gradleProperty(name).orNull?.let { name to it } }
tasks.matching { it.name == "runClientGameTest" }.configureEach {
	val exec = this as JavaExec
	gameProperties.forEach { (name, value) -> exec.systemProperty(name, value) }
}

publishing {
	publications {
		create<MavenPublication>("fabric") {
			artifactId = "spawnproof-fabric"
			from(components["java"])
		}
	}
}
