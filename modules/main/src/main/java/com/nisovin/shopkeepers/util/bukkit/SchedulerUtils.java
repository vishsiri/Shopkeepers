package com.nisovin.shopkeepers.util.bukkit;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitWorker;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.api.internal.util.Unsafe;
import com.nisovin.shopkeepers.util.java.Validate;
import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.wrapper.task.WrappedTask;

/**
 * Scheduler related utilities.
 */
public final class SchedulerUtils {

	private static final Map<Plugin, FoliaLib> FOLIA_LIB_INSTANCES = new WeakHashMap<>();

	private static synchronized FoliaLib getFoliaLib(Plugin plugin) {
		return FOLIA_LIB_INSTANCES.computeIfAbsent(plugin, FoliaLib::new);
	}

	/**
	 * Creates an {@link Executor} that executes tasks on the server's main thread using
	 * {@link #runOnMainThreadOrOmit(Plugin, Runnable)}.
	 * <p>
	 * If the thread registering the task is already the server's main thread, the task is run
	 * immediately. Otherwise, it is scheduled using the {@link BukkitScheduler}. If the plugin is
	 * not enabled at the time of task registration, the task is omitted.
	 * 
	 * @param plugin
	 *            the plugin
	 * @return the executor
	 */
	public static Executor createSyncExecutor(Plugin plugin) {
		return (runnable) -> runOnMainThreadOrOmit(plugin, runnable);
	}

	/**
	 * Creates an {@link Executor} that executes tasks using
	 * {@link #runAsyncTaskOrOmit(Plugin, Runnable)}.
	 * 
	 * @param plugin
	 *            the plugin
	 * @return the executor
	 */
	public static Executor createAsyncExecutor(Plugin plugin) {
		return (runnable) -> runAsyncTaskOrOmit(plugin, runnable);
	}

	public static int getActiveAsyncTasks(Plugin plugin) {
		Validate.notNull(plugin, "plugin is null");
		int workers = 0;
		for (BukkitWorker worker : Bukkit.getScheduler().getActiveWorkers()) {
			if (worker.getOwner().equals(plugin)) {
				workers++;
			}
		}
		return workers;
	}

	private static void validatePluginTask(Plugin plugin, Runnable task) {
		Validate.notNull(plugin, "plugin is null");
		Validate.notNull(task, "task is null");
	}

	/**
	 * Checks if the current thread is the server's main thread.
	 * 
	 * @return <code>true</code> if currently running on the main thread
	 */
	public static boolean isMainThread() {
		if (Bukkit.isPrimaryThread()) {
			return true;
		}
		FoliaLib foliaLib = getFoliaLib(com.nisovin.shopkeepers.SKShopkeepersPlugin.getInstance());
		return foliaLib.isFolia() && foliaLib.getScheduler().isGlobalTickThread();
	}

	/**
	 * Schedules the given task to be run on the primary thread if required.
	 * <p>
	 * If the current thread is already the primary thread, the task will be run immediately.
	 * Otherwise, it attempts to schedule the task to run on the server's primary thread. However,
	 * if the plugin is disabled, the task won't be scheduled.
	 * 
	 * @param plugin
	 *            the plugin to use for scheduling, not <code>null</code>
	 * @param task
	 *            the task, not <code>null</code>
	 * @return <code>true</code> if the task was run or successfully scheduled to be run,
	 *         <code>false</code> otherwise
	 */
	public static boolean runOnMainThreadOrOmit(Plugin plugin, Runnable task) {
		validatePluginTask(plugin, task);
		if (isMainThread()) {
			task.run();
			return true;
		} else {
			return (runTaskOrOmit(plugin, task) != null);
		}
	}

	public static @Nullable ScheduledTask runTaskOrOmit(Plugin plugin, Runnable task) {
		return runTaskLaterOrOmit(plugin, task, 0L);
	}

	public static @Nullable ScheduledTask runTaskLaterOrOmit(
			Plugin plugin,
			Runnable task,
			long delay
	) {
		validatePluginTask(plugin, task);
		// Tasks can only be registered while enabled:
		if (plugin.isEnabled()) {
			try {
				WrappedTask wrappedTask = getFoliaLib(plugin).getScheduler().runLater(
						task,
						Math.max(1L, delay)
				);
				return new ScheduledTask(Unsafe.assertNonNull(wrappedTask));
			} catch (IllegalPluginAccessException e) {
				// Couldn't register task: The plugin got disabled just now.
			}
		}
		return null;
	}

	public static @Nullable ScheduledTask runAsyncTaskOrOmit(Plugin plugin, Runnable task) {
		return runAsyncTaskLaterOrOmit(plugin, task, 0L);
	}

	public static @Nullable ScheduledTask runAsyncTaskLaterOrOmit(
			Plugin plugin,
			Runnable task,
			long delay
	) {
		validatePluginTask(plugin, task);
		// Tasks can only be registered while enabled:
		if (plugin.isEnabled()) {
			try {
				WrappedTask wrappedTask = getFoliaLib(plugin).getScheduler().runLaterAsync(
						task,
						Math.max(1L, delay)
				);
				return new ScheduledTask(Unsafe.assertNonNull(wrappedTask));
			} catch (IllegalPluginAccessException e) {
				// Couldn't register task: The plugin got disabled just now.
			}
		}
		return null;
	}

	/**
	 * Awaits the completion of async tasks of the specified plugin.
	 * <p>
	 * If a logger is specified, it will be used to print informational messages suited to the
	 * context of this method being called during disabling of the plugin.
	 * 
	 * @param plugin
	 *            the plugin
	 * @param asyncTasksTimeoutSeconds
	 *            the duration to wait for async tasks to finish in seconds (can be <code>0</code>)
	 * @param logger
	 *            the logger used for printing informational messages, can be <code>null</code>
	 * @return the number of remaining async tasks that are still running after waiting for the
	 *         specified duration
	 */
	public static int awaitAsyncTasksCompletion(
			Plugin plugin,
			int asyncTasksTimeoutSeconds,
			@Nullable Logger logger
	) {
		Validate.notNull(plugin, "plugin is null");
		Validate.isTrue(asyncTasksTimeoutSeconds >= 0, "asyncTasksTimeoutSeconds cannot be negative");

		int activeAsyncTasks = getActiveAsyncTasks(plugin);
		if (activeAsyncTasks > 0 && asyncTasksTimeoutSeconds > 0) {
			if (logger != null) {
				logger.info("Waiting up to " + asyncTasksTimeoutSeconds + " seconds for "
						+ activeAsyncTasks + " remaining async tasks to finish ...");
			}

			final long asyncTasksTimeoutMillis = TimeUnit.SECONDS.toMillis(asyncTasksTimeoutSeconds);
			final long waitStartNanos = System.nanoTime();
			long waitDurationMillis = 0L;
			do {
				// Periodically check again:
				try {
					Thread.sleep(25L);
				} catch (InterruptedException e) {
					// Ignore, but reset interrupt flag:
					Thread.currentThread().interrupt();
				}
				// Update the number of active async task before breaking from loop:
				activeAsyncTasks = getActiveAsyncTasks(plugin);

				// Update waiting duration and compare to timeout:
				waitDurationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStartNanos);
				if (waitDurationMillis > asyncTasksTimeoutMillis) {
					// Timeout reached, abort waiting..
					break;
				}
			} while (activeAsyncTasks > 0);

			if (waitDurationMillis > 1 && logger != null) {
				logger.info("Waited " + waitDurationMillis + " ms for async tasks to finish.");
			}
		}

		if (activeAsyncTasks > 0 && logger != null) {
			// Severe, since this can potentially result in data loss, depending on what the tasks
			// are doing:
			logger.severe("There are still " + activeAsyncTasks
					+ " remaining async tasks active! Disabling anyway now.");
		}
		return activeAsyncTasks;
	}

	public static @Nullable ScheduledTask runTaskTimerOrOmit(
			Plugin plugin,
			Runnable task,
			long delay,
			long period
	) {
		validatePluginTask(plugin, task);
		if (plugin.isEnabled()) {
			try {
				WrappedTask wrappedTask = getFoliaLib(plugin).getScheduler().runTimer(
						task,
						Math.max(1L, delay),
						Math.max(1L, period)
				);
				return new ScheduledTask(Unsafe.assertNonNull(wrappedTask));
			} catch (IllegalPluginAccessException e) {
				// Couldn't register task: The plugin got disabled just now.
			}
		}
		return null;
	}

	public static @Nullable ScheduledTask runAtLocationOrOmit(
			Plugin plugin,
			Location location,
			Runnable task
	) {
		validatePluginTask(plugin, task);
		Validate.notNull(location, "location is null");
		if (plugin.isEnabled()) {
			try {
				WrappedTask wrappedTask = getFoliaLib(plugin).getScheduler().runAtLocationLater(
						location,
						task,
						1L
				);
				return new ScheduledTask(Unsafe.assertNonNull(wrappedTask));
			} catch (IllegalPluginAccessException e) {
				// Couldn't register task: The plugin got disabled just now.
			}
		}
		return null;
	}

	public static @Nullable ScheduledTask runAtEntityOrOmit(
			Plugin plugin,
			Entity entity,
			Runnable task
	) {
		validatePluginTask(plugin, task);
		Validate.notNull(entity, "entity is null");
		if (plugin.isEnabled()) {
			try {
				WrappedTask wrappedTask = getFoliaLib(plugin).getScheduler().runAtEntityLater(
						entity,
						task,
						1L
				);
				return new ScheduledTask(Unsafe.assertNonNull(wrappedTask));
			} catch (IllegalPluginAccessException e) {
				// Couldn't register task: The plugin got disabled just now.
			}
		}
		return null;
	}

	public static @Nullable ScheduledTask runAtEntityTimerOrOmit(
			Plugin plugin,
			Entity entity,
			Runnable task,
			long delay,
			long period
	) {
		validatePluginTask(plugin, task);
		Validate.notNull(entity, "entity is null");
		if (plugin.isEnabled()) {
			try {
				WrappedTask wrappedTask = getFoliaLib(plugin).getScheduler().runAtEntityTimer(
						entity,
						task,
						Math.max(1L, delay),
						Math.max(1L, period)
				);
				return new ScheduledTask(Unsafe.assertNonNull(wrappedTask));
			} catch (IllegalPluginAccessException e) {
				// Couldn't register task: The plugin got disabled just now.
			}
		}
		return null;
	}

	public static void cancelTasks(Plugin plugin) {
		getFoliaLib(plugin).getScheduler().cancelAllTasks();
	}

	private SchedulerUtils() {
	}
}
