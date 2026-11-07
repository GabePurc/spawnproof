package io.github.gabepurc.spawnproof.gradle;

import io.github.gabepurc.spawnproof.cli.Main;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** spawnproofStatus and friends: the spawnproof command, from any build that applies the plugin. */
@DisableCachingByDefault(because = "It talks to the daemon")
public abstract class SpawnproofCommandTask extends DefaultTask {
	@Input
	public abstract ListProperty<String> getArguments();

	public SpawnproofCommandTask() {
		setGroup("spawnproof");
		getOutputs().upToDateWhen(t -> false);
	}

	@TaskAction
	public void run() {
		List<String> args = getArguments().get();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
		int code = Main.run(args.toArray(new String[0]), print, print);
		for (String line : out.toString(StandardCharsets.UTF_8).stripTrailing().split("\n")) getLogger().quiet(line);
		if (code != 0) throw new GradleException("spawnproof " + String.join(" ", args) + " failed");
	}
}
