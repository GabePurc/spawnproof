package io.github.gabepurc.spawnproof.gradle;

import io.github.gabepurc.spawnproof.Dirs;
import org.gradle.api.Task;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.jvm.toolchain.JavaInstallationMetadata;
import org.gradle.jvm.toolchain.JavaLauncher;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
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
 * file, then runs the real java: the one the task was going to use, whether that's Loom's default (the project's toolchain)
 * or one the build chose itself, as a mod does to run each Minecraft version on the Java its players use.
 */
final class HiddenLauncher implements JavaLauncher {
	private final JavaLauncher delegate;

	HiddenLauncher(JavaLauncher delegate) {
		this.delegate = delegate;
	}

	/**
	 * Puts the script in place of the task's java, and says whether it's there. Called once the build has configured its tasks
	 * (a launcher the build sets would win over one set any earlier) and before they run (it can't change after that).
	 */
	@SuppressWarnings("unchecked")
	static boolean wrap(Task task) {
		try {
			Property<JavaLauncher> launcher = (Property<JavaLauncher>) task.getClass().getMethod("getJavaLauncher").invoke(task);
			JavaLauncher chosen = launcher.get();
			if (!(chosen instanceof HiddenLauncher)) launcher.set(new HiddenLauncher(chosen));
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
			task.getLogger().warn("Spawnproof: couldn't put {} on the hidden display, so its window opens on your desktop ({}). "
				+ "`spawnproof run -- <your gradle command>` hides everything the command starts.", task.getPath(), cause.toString());
			return false;
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
