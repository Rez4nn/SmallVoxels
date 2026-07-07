package me.reube.SmallVoxels.listeners;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.ui.AnimationTriggerSettingsGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

public class AnimationTriggerLinkListener implements Listener {
  private static final Set<UUID> LINKING = ConcurrentHashMap.newKeySet();
  private final SmallVoxels plugin;

  public AnimationTriggerLinkListener(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  public static void begin(Player player) {
    LINKING.add(player.getUniqueId());
  }

  @EventHandler
  public void onInteract(PlayerInteractEvent event) {
    Player player = event.getPlayer();
    if (!LINKING.contains(player.getUniqueId())) {
      return;
    }
    if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
      return;
    }
    event.setCancelled(true);
    Block block = event.getClickedBlock();
    if (!isRedstoneCause(block.getType())) {
      player.sendActionBar(
          Component.text("Right-click a button, pressure plate, lever, or redstone dust")
              .color(NamedTextColor.YELLOW));
      return;
    }
    LINKING.remove(player.getUniqueId());
    boolean linked = plugin.getAnimatedObjectManager().linkEditorRedstoneTrigger(player, block);
    player.sendMessage(
        Component.text(linked ? "Linked redstone trigger." : "Select an animation state first.")
            .color(linked ? NamedTextColor.GREEN : NamedTextColor.RED));
    new AnimationTriggerSettingsGUI(plugin).open(player);
  }

  private boolean isRedstoneCause(Material material) {
    String name = material.name();
    return name.endsWith("_BUTTON")
        || name.endsWith("_PRESSURE_PLATE")
        || material == Material.LEVER
        || material == Material.REDSTONE_WIRE
        || material == Material.REDSTONE_TORCH
        || material == Material.REDSTONE_WALL_TORCH
        || material == Material.TRIPWIRE_HOOK
        || material == Material.LIGHTNING_ROD;
  }
}
