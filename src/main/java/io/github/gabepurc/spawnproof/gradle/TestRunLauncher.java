package io.github.gabepurc.spawnproof.gradle;

import io.github.gabepurc.spawnproof.Dirs;
import io.github.gabepurc.spawnproof.client.DaemonStarter;
import org.gradle.api.Project;
import org.gradle.api.Transformer;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.Nested;
import org.gradle.jvm.toolchain.JavaInstallationMetadata;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Path;

/**
 * A test game's java, when its tests are split across games (-Pspawnproof.games, or spawnproof test). It's a small script that
 * starts Spawnproof's test run with the game's whole command line, just as the build made it: the classpath and run folder
 * Loom works out as the game starts, the build's own properties and arguments. The run then starts that command once for
 * each game ({@code io.github.gabepurc.spawnproof.run.TestRun}).
 *
 * <p>The run's settings come in SPAWNPROOF_RUN, which AcquireAction sets as the task starts; without them the script runs the
 * real java, so the game runs on its own as it always did.
 */
final class TestRunLauncher implements JavaLauncher {
	private final JavaLauncher delegate;
	private final String runner;
	private final String classpath;

	private TestRunLauncher(JavaLauncher delegate, String runner, String classpath) {
		this.delegate = delegate;
		this.runner = runner;
		this.classpath = classpath;
	}

	/**
	 * Puts the launcher on a build's JavaExec tasks, in place of the one Gradle gives them by default: the project's toolchain,
	 * which Loom's game tasks run on. It's set as the Java plugin sets its own, as each task is made (just after it), since a
	 * task's launcher can't change once the build is configured. Only on builds that ask for a test run; and on a task
	 * that isn't a test game, the script runs the real java.
	 *
	 * <p>A launcher the build sets itself still wins. AcquireAction finds this isn't in place then, and the task runs one game.
	 */
	static void installAfterJava(Project project) {
		if (Dirs.isWindows()) return;
		String runner = DaemonStarter.java();
		String classpath;
		try {
			classpath = DaemonStarter.classpath().toString();
		} catch (IOException e) {
			project.getLogger().warn("Spawnproof: can't split tests across games: {}", e.getMessage());
			return;
		}
		project.getPluginManager().withPlugin("java-base", applied -> {
			JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
			JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
			Provider<JavaLauncher> original = toolchains.launcherFor(java.getToolchain());
			project.getTasks().withType(JavaExec.class).configureEach(task -> task.getJavaLauncher().convention(original.map(new Wrap(runner, classpath))));
		});
	}

	/** Why a test game that asked to be split runs in one game after all. */
	static String whyNot() {
		return Dirs.isWindows() ? "splitting tests across games works on Linux and macOS so far"
			: "the build sets the game's java launcher itself, so Spawnproof can't start the game several times over";
	}

	/** A named class, not a lambda, so the configuration cache can store it. */
	private record Wrap(String runner, String classpath) implements Transformer<JavaLauncher, JavaLauncher>, Serializable {
		@Override
		public JavaLauncher transform(JavaLauncher launcher) {
			return launcher instanceof TestRunLauncher ? launcher : new TestRunLauncher(launcher, runner, classpath);
		}
	}

	@Override
	@Nested
	public JavaInstallationMetadata getMetadata() {
		return delegate.getMetadata();
	}

	@Override
	@Internal
	public RegularFile getExecutablePath() {
		String real = delegate.getExecutablePath().getAsFile().getAbsolutePath();
		String text = "#!/bin/sh\n"
			+ "# Written by Spawnproof: a test game's java. With SPAWNPROOF_RUN set, Spawnproof's test run takes the command and starts\n"
			+ "# the game several times over, splitting its tests between them. Without it, the real java.\n"
			+ "if [ -z \"$SPAWNPROOF_RUN\" ]; then exec " + HiddenLauncher.quote(real) + " \"$@\"; fi\n"
			+ "exec " + HiddenLauncher.quote(runner) + " -Xmx128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -cp " + HiddenLauncher.quote(classpath)
			+ " io.github.gabepurc.spawnproof.run.TestRun " + HiddenLauncher.quote(real) + " \"$@\"\n";
		Path script = HiddenLauncher.script("test run\n" + real + "\n" + runner + "\n" + classpath, text);
		return new HiddenLauncher.WrapperFile(script.toFile());
	}
}
