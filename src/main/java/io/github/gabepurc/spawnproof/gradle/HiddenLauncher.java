package io.github.gabepurc.spawnproof.gradle;

import io.github.gabepurc.spawnproof.Dirs;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.jvm.toolchain.JavaInstallationMetadata;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Loom's production runs (ClientProductionRunTask) start java with Gradle's own environment and take no environment of
 * their own, so DISPLAY can't be set on them. Instead their java is a small script that sets it from the hidden display's
 * file, then runs the real java. It takes the place of Loom's default launcher, which is the project's toolchain.
 */
final class HiddenLauncher implements JavaLauncher {
	private final JavaLauncher delegate;

	HiddenLauncher(JavaLauncher delegate) {
		this.delegate = delegate;
	}

	@SuppressWarnings("unchecked")
	static void install(Task task, Project project, Provider<Boolean> wanted) {
		JavaPluginExtension java = project.getExtensions().findByType(JavaPluginExtension.class);
		JavaToolchainService toolchains = project.getExtensions().findByType(JavaToolchainService.class);
		if (java == null || toolchains == null) return;
		Property<JavaLauncher> launcher;
		try {
			launcher = (Property<JavaLauncher>) task.getClass().getMethod("getJavaLauncher").invoke(task);
		} catch (ReflectiveOperationException | ClassCastException e) {
			return;
		}
		Provider<JavaLauncher> original = toolchains.launcherFor(java.getToolchain());
		// A convention, so a launcher the build sets itself still wins.
		launcher.convention(original.zip(wanted, (l, w) -> w ? new HiddenLauncher(l) : l));
	}

	@Override
	@Nested
	public JavaInstallationMetadata getMetadata() {
		return delegate.getMetadata();
	}

	@Override
	@Internal
	public RegularFile getExecutablePath() {
		return new WrapperFile(wrapperFor(delegate.getExecutablePath().getAsFile().toPath()).toFile());
	}

	/** Loom hands this to Gradle's commandLine, which reads it with toString: so that must be the path. */
	record WrapperFile(File file) implements RegularFile {
		@Override
		public File getAsFile() {
			return file;
		}

		@Override
		public String toString() {
			return file.getAbsolutePath();
		}
	}

	static Path wrapperFor(Path realJava) {
		String text = "#!/bin/sh\n"
			+ "# Written by Spawnproof: runs a test game on the hidden display while it's up, then the real java.\n"
			+ "f=" + quote(Dirs.displayFile().toString()) + "\n"
			+ "if [ -r \"$f\" ]; then\n"
			+ "\td=$(cat \"$f\")\n"
			+ "\tif [ -S \"/tmp/.X11-unix/X${d#:}\" ]; then\n"
			+ "\t\tDISPLAY=\"$d\"\n"
			+ "\t\tXDG_SESSION_TYPE=x11\n"
			+ "\t\texport DISPLAY XDG_SESSION_TYPE\n"
			+ "\t\tunset WAYLAND_DISPLAY\n"
			+ "\tfi\n"
			+ "fi\n"
			+ "exec " + quote(realJava.toString()) + " \"$@\"\n";
		return script(realJava.toString(), text);
	}

	/**
	 * A launcher script in Spawnproof's state folder, named by a hash of what it wraps, written again only when its text
	 * changes (a running game may be reading it).
	 */
	static Path script(String key, String text) {
		try {
			String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
			Path script = Dirs.state().resolve("launchers").resolve(hash.substring(0, 16)).resolve("java");
			if (!Files.isRegularFile(script) || !Files.readString(script).equals(text)) {
				Files.createDirectories(script.getParent());
				Path temp = Files.createTempFile(script.getParent(), "java", ".tmp");
				Files.writeString(temp, text);
				Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rwxr-xr-x"));
				Files.move(temp, script, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			}
			return script;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	static String quote(String s) {
		return "'" + s.replace("'", "'\\''") + "'";
	}
}
