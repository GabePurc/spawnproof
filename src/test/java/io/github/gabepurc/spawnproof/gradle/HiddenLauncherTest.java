package io.github.gabepurc.spawnproof.gradle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs({OS.LINUX, OS.MAC})
class HiddenLauncherTest {
	@AfterEach
	void tearDown() {
		System.clearProperty("spawnproof.home");
	}

	@Test
	void theWrapperRunsTheRealJava(@TempDir Path home) throws IOException {
		System.setProperty("spawnproof.home", home.toString());
		Path script = HiddenLauncher.wrapperFor(Path.of("/opt/a jdk/bin/java"));
		assertTrue(Files.isExecutable(script));
		String text = Files.readString(script);
		assertTrue(text.contains("exec '/opt/a jdk/bin/java' \"$@\""), text);
		assertTrue(text.contains(home.resolve("run/display").toString()), text);
		assertEquals(script, HiddenLauncher.wrapperFor(Path.of("/opt/a jdk/bin/java")), "the same java gets the same script");
	}

	@Test
	void theExecutableReadsAsItsPath() {
		// Loom passes it to Gradle's commandLine, which turns it into text.
		Path file = Path.of("/tmp/somewhere/java");
		assertEquals(file.toString(), new HiddenLauncher.WrapperFile(file.toFile()).toString());
	}
}
