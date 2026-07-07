package me.reube.SmallVoxels.listeners;

import me.reube.SmallVoxels.SmallVoxels;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

public class TrustSelectionListener implements Listener {

  private final SmallVoxels plugin;

  public TrustSelectionListener(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onSelect(PlayerInteractEvent event) {
    Player player = event.getPlayer();
    if (!plugin.getVoxelProtectionManager().hasPendingRegion(player)) {
      return;
    }
    if (event.getAction() != Action.LEFT_CLICK_BLOCK
        && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
      return;
    }

    Block block = event.getClickedBlock();
    if (block == null) {
      return;
    }

    event.setCancelled(true);
    plugin.getVoxelProtectionManager().handleRegionClick(player, block);
  }
}
