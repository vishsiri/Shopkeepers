package com.nisovin.shopkeepers.util.bukkit;

import org.bukkit.plugin.Plugin;

import com.nisovin.shopkeepers.api.internal.util.Unsafe;
import com.tcoded.folialib.wrapper.task.WrappedTask;

/**
 * A small scheduler task wrapper that hides the underlying server scheduler implementation.
 */
public final class ScheduledTask {

	private final WrappedTask task;

	ScheduledTask(WrappedTask task) {
		this.task = task;
	}

	public void cancel() {
		task.cancel();
	}

	public boolean isCancelled() {
		return task.isCancelled();
	}

	public Plugin getOwningPlugin() {
		return Unsafe.assertNonNull(task.getOwningPlugin());
	}

	public boolean isAsync() {
		return task.isAsync();
	}
}
