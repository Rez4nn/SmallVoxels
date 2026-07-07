package me.reube.SmallVoxels.managers;

import java.util.HashMap;
import java.util.Map;
import me.reube.SmallVoxels.SmallVoxels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class PerformanceMonitor {
  private final SmallVoxels plugin;
  private final Map<String, Long> lastWarning = new HashMap<>();

  public PerformanceMonitor(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  public void record(String subsystem, long startedNanos, String context) {
    if (!plugin.getConfig().getBoolean("lag-tracking.enabled", true)) return;
    double elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000.0;
    double threshold =
        Math.max(1.0, plugin.getConfig().getDouble("lag-tracking.operation-warning-ms", 25.0));
    if (elapsedMs < threshold) return;

    long now = System.currentTimeMillis();
    long cooldown =
        Math.max(5, plugin.getConfig().getLong("lag-tracking.cooldown-seconds", 60)) * 1000L;
    if (now - lastWarning.getOrDefault(subsystem, 0L) < cooldown) return;
    lastWarning.put(subsystem, now);

    String message =
        String.format(
            java.util.Locale.ROOT,
            "SmallVoxels performance warning: %s took %.1f ms%s",
            subsystem,
            elapsedMs,
            context == null || context.isBlank() ? "" : " (" + context + ")");
    plugin.getLogger().warning(message);
    if (plugin.getConfig().getBoolean("lag-tracking.warn-ops", true)) {
      Component component = Component.text(message).color(NamedTextColor.RED);
      for (org.bukkit.entity.Player player : plugin.getServer().getOnlinePlayers()) {
        if (player.isOp() || player.hasPermission("smallvoxels.admin"))
          player.sendMessage(component);
      }
    }
  }
}
