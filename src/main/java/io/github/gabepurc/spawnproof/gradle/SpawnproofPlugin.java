package io.github.gabepurc.spawnproof.gradle;

import io.github.gabepurc.spawnproof.Dirs;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.initialization.Settings;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.plugins.PluginAware;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.JavaExec;
import org.gradle.build.event.BuildEventsListenerRegistry;

import javax.inject.Inject;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Applied in settings.gradle (every project), a build script (that project) or an init script (every build on the machine).
 * Every game task waits for a slot from the machine's Spawnproof daemon before its game starts, and test games draw on the
 * hidden display.
 */
public abstract class SpawnproofPlugin implements Plugin<PluginAware> {
	/** Set on a project once configured, so a second copy of the plugin (an init script's and the build's) does nothing. */
	private static final String CONFIGURED = "io.github.gabepurc.spawnproof.configured";
	/** Spawnproof for Fabric's settings, passed from the command line (-Pspawnproof.tests=hat) to every test game. */
	static final List<String> GAME_PROPERTIES = List.of("spawnproof.tests", "spawnproof.skip", "spawnproof.clock", "spawnproof.graphics");
	private static final Set<Gradle> LISTENING = Collections.newSetFromMap(new WeakHashMap<>());

	@Inject
	protected abstract BuildEventsListenerRegistry getListeners();

	@Override
	public void apply(PluginAware target) {
		if (target instanceof Settings settings) {
			Object existing = settings.getExtensions().findByName("spawnproof");
			if (existing != null && !(existing instanceof SpawnproofExtension)) return; // Another copy of the plugin has it.
			SpawnproofExtension shared = existing != null ? (SpawnproofExtension) existing
				: settings.getExtensions().create("spawnproof", SpawnproofExtension.class);
			settings.getGradle().allprojects(project -> configure(project, shared));
		} else if (target instanceof Gradle gradle) {
			gradle.beforeSettings(settings -> settings.getPluginManager().apply(SpawnproofPlugin.class));
		} else if (target instanceof Project project) {
			configure(project, null);
		}
	}

	private void configure(Project project, SpawnproofExtension shared) {
		ExtraPropertiesExtension extra = project.getExtensions().getExtraProperties();
		if (extra.has(CONFIGURED)) return;
		extra.set(CONFIGURED, true);
		SpawnproofExtension ext = project.getExtensions().findByName("spawnproof") instanceof SpawnproofExtension e ? e
			: project.getExtensions().create("spawnproof", SpawnproofExtension.class);

		Provider<LeaseService> service = project.getGradle().getSharedServices().registerIfAbsent("spawnproof", LeaseService.class, spec -> {});
		synchronized (LISTENING) {
			if (LISTENING.add(project.getGradle())) getListeners().onTaskCompletion(service);
		}

		ProviderFactory providers = project.getProviders();
		Provider<String> off = providers.gradleProperty("spawnproof.off");
		Provider<String> offEnv = providers.environmentVariable("SPAWNPROOF");
		Provider<String> ci = providers.environmentVariable("CI");
		Provider<String> visible = providers.gradleProperty("spawnproof.visible");
		Provider<String> alone = providers.gradleProperty("spawnproof.exclusive");
		Provider<String> games = providers.gradleProperty("spawnproof.games");
		Provider<String> onlyFailures = providers.gradleProperty("spawnproof.onlyFailures");
		Map<String, Provider<String>> passed = new LinkedHashMap<>();
		for (String name : GAME_PROPERTIES) passed.put(name, providers.gradleProperty(name));
		String projectName = project.getRootProject().getName();
		String rootDir = project.getRootDir().getAbsolutePath();
		Map<String, Provider<String>> found = new ConcurrentHashMap<>();
		List<Takeover> takeovers = new CopyOnWriteArrayList<>();
		// Read once the graph is known, after the build has configured its tasks, so these win over the build's own settings:
		// - the command line's settings for Spawnproof for Fabric go to every test game;
		// - a test game's Gradle timeout would start counting while it waits in line, so Spawnproof takes it over and counts
		//   it from when the game starts (TimeLimit);
		// - with -Pspawnproof.games or -Pspawnproof.onlyFailures, the game's java becomes a test run (TestRunLauncher).
		project.getGradle().getTaskGraph().whenReady(graph -> {
			boolean testRun = games.isPresent() || flag(onlyFailures);
			for (Takeover t : takeovers) {
				if (!graph.hasTask(t.task()) || !Rules.TEST.equals(t.kind().get())) continue;
				passSettings(t.task(), passed);
				if (Rules.TEST.equals(t.role().get())) {
					Duration limit = t.task().getTimeout().getOrNull();
					if (limit != null) {
						t.limit().set(limit);
						t.task().getTimeout().set((Duration) null);
					}
				}
				if (!testRun) continue;
				if (t.task() instanceof JavaExec) {
					t.testRun().set(true);
				} else {
					project.getLogger().warn("Spawnproof: {} runs its tests in one game: splitting them works with JavaExec game tasks so far",
						t.task().getPath());
				}
			}
		});

		if (games.isPresent() || flag(onlyFailures)) TestRunLauncher.installAfterJava(project);

		project.getTasks().configureEach(task -> {
			boolean productionRun = isProductionClientRun(task);
			if (!(task instanceof JavaExec) && !productionRun) return;
			if (task.getExtensions().findByName("spawnproof") != null) return;
			GameSettings settings = task.getExtensions().create("spawnproof", GameSettings.class);
			String name = task.getName();
			String path = task.getPath();
			// What the task is, by its name and settings; and how it queues, which is not at all with the queue turned off.
			Provider<String> kind = providers.provider(() -> Rules.role(name, path, productionRun, settings, ext, shared));
			Provider<String> role = providers.provider(() -> {
				if (flag(off) || isOff(offEnv.getOrNull())) return Rules.NONE;
				Boolean enabled = Rules.enabled(ext, shared);
				if (enabled == null) enabled = !flag(ci);
				return enabled ? kind.get() : Rules.NONE;
			});
			Provider<Boolean> exclusive = providers.provider(() -> Rules.exclusive(name, path, settings, ext, shared, flag(alone)));
			Provider<Boolean> hidden = providers.provider(() -> Rules.hidden(settings, ext, shared, flag(visible)));
			found.put(name, role.zip(exclusive, (r, x) -> r + (Rules.TEST.equals(r) && x ? ", alone" : ""))
				.zip(hidden, (r, h) -> r + (r.startsWith(Rules.TEST) ? h ? ", hidden" : ", visible" : "")));
			Property<Duration> limit = project.getObjects().property(Duration.class);
			Property<Boolean> testRun = project.getObjects().property(Boolean.class).convention(false);
			takeovers.add(new Takeover(task, kind, role, limit, testRun));
			task.usesService(service);
			Provider<Boolean> rerun = providers.provider(() -> flag(onlyFailures));
			task.doFirst(new AcquireAction(service, role, exclusive, hidden, limit, projectName, rootDir, testRun, games, rerun));
			task.doLast(new TimeLimit.Stop(service));
			if (productionRun && Dirs.isLinux()) {
				HiddenLauncher.install(task, project, role.zip(hidden, (r, h) -> h && Rules.TEST.equals(r)));
			}
		});

		if (!project.getTasks().getNames().contains("spawnproofTasks")) {
			project.getTasks().register("spawnproofTasks", SpawnproofTasksTask.class, t -> {
				// Every task, so the ones registered lazily have been seen above.
				project.getTasks().forEach(other -> {});
				found.forEach((name, description) -> t.getGames().put(name, description));
			});
		}

		if (project == project.getRootProject()) {
			for (List<String> command : List.of(List.of("status"), List.of("pause"), List.of("resume"), List.of("install"))) {
				String taskName = "spawnproof" + Character.toUpperCase(command.get(0).charAt(0)) + command.get(0).substring(1);
				if (project.getTasks().getNames().contains(taskName)) continue;
				project.getTasks().register(taskName, SpawnproofCommandTask.class, t -> {
					t.getArguments().set(command);
					t.setDescription(switch (command.get(0)) {
						case "status" -> "Shows the machine's game queue: what's running, waiting and playing.";
						case "pause" -> "Holds new games in the machine's queue; running ones finish.";
						case "resume" -> "Lets queued games start again.";
						default -> "Installs the spawnproof command in ~/.local/bin.";
					});
				});
			}
		}
	}

	private record Takeover(Task task, Provider<String> kind, Provider<String> role, Property<Duration> limit, Property<Boolean> testRun) {}

	/** -Pspawnproof.tests and the like, to the game as system properties: JavaExec's own, or a production run's JVM arguments. */
	@SuppressWarnings("unchecked")
	private static void passSettings(Task task, Map<String, Provider<String>> passed) {
		for (Map.Entry<String, Provider<String>> e : passed.entrySet()) {
			String value = e.getValue().getOrNull();
			if (value == null) continue;
			if (task instanceof JavaExec exec) {
				exec.systemProperty(e.getKey(), value);
				continue;
			}
			try {
				// (Loom's production runs: the last -D of a name wins, so this goes after the build's own.)
				((ListProperty<String>) task.getClass().getMethod("getJvmArgs").invoke(task)).add("-D" + e.getKey() + "=" + value);
			} catch (ReflectiveOperationException | ClassCastException ignored) {
				// A game task with no JVM arguments to give.
			} catch (RuntimeException fixed) {
				task.getLogger().warn("Spawnproof: couldn't pass -P{} on to {} ({})", e.getKey(), task.getPath(), fixed.getMessage());
			}
		}
	}

	/** -Pspawnproof.off, -Pspawnproof.visible: present and not "false". */
	private static boolean flag(Provider<String> property) {
		String v = property.getOrNull();
		return v != null && !v.equalsIgnoreCase("false");
	}

	private static boolean isOff(String v) {
		return v != null && (v.equalsIgnoreCase("off") || v.equalsIgnoreCase("false") || v.equals("0"));
	}

	/** Loom's production client run (net.fabricmc.loom.task.prod.ClientProductionRunTask), found without depending on Loom. */
	static boolean isProductionClientRun(Task task) {
		for (Class<?> c = task.getClass(); c != null; c = c.getSuperclass()) {
			if (c.getName().equals("net.fabricmc.loom.task.prod.ClientProductionRunTask")) return true;
		}
		return false;
	}
}
