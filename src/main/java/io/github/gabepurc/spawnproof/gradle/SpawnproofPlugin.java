package io.github.gabepurc.spawnproof.gradle;

import io.github.gabepurc.spawnproof.Dirs;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.initialization.Settings;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.plugins.PluginAware;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.JavaExec;
import org.gradle.build.event.BuildEventsListenerRegistry;

import javax.inject.Inject;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applied in settings.gradle (every project), a build script (that project) or an init script (every build on the machine).
 * Every game task waits for a slot from the machine's Spawnproof daemon before its game starts, and test games draw on the
 * hidden display.
 */
public abstract class SpawnproofPlugin implements Plugin<PluginAware> {
	/** Set on a project once configured, so a second copy of the plugin (an init script's and the build's) does nothing. */
	private static final String CONFIGURED = "io.github.gabepurc.spawnproof.configured";
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
		String projectName = project.getRootProject().getName();
		String rootDir = project.getRootDir().getAbsolutePath();
		Map<String, Provider<String>> found = new ConcurrentHashMap<>();

		project.getTasks().configureEach(task -> {
			boolean productionRun = isProductionClientRun(task);
			if (!(task instanceof JavaExec) && !productionRun) return;
			if (task.getExtensions().findByName("spawnproof") != null) return;
			GameSettings settings = task.getExtensions().create("spawnproof", GameSettings.class);
			String name = task.getName();
			String path = task.getPath();
			Provider<String> role = providers.provider(() -> {
				if (flag(off) || isOff(offEnv.getOrNull())) return Rules.NONE;
				Boolean enabled = Rules.enabled(ext, shared);
				if (enabled == null) enabled = !flag(ci);
				return enabled ? Rules.role(name, path, productionRun, settings, ext, shared) : Rules.NONE;
			});
			Provider<Boolean> exclusive = providers.provider(() -> Rules.exclusive(name, path, settings, ext, shared, flag(alone)));
			Provider<Boolean> hidden = providers.provider(() -> Rules.hidden(settings, ext, shared, flag(visible)));
			found.put(name, role.zip(exclusive, (r, x) -> r + (Rules.TEST.equals(r) && x ? ", alone" : ""))
				.zip(hidden, (r, h) -> r + (r.startsWith(Rules.TEST) ? h ? ", hidden" : ", visible" : "")));
			task.usesService(service);
			task.doFirst(new AcquireAction(service, role, exclusive, hidden, projectName, rootDir));
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
