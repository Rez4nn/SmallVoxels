package me.reube.SmallVoxels.listeners;

import com.google.gson.JsonArray;
import java.util.HashMap;
import java.util.Map;
import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.managers.CollisionStandManager;
import me.reube.SmallVoxels.managers.DataManager;
import me.reube.SmallVoxels.managers.FallingBlockManager;
import me.reube.SmallVoxels.managers.VoxelManager;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;

/** Handles interaction with the invisible target attached to a locked voxel block. */
public class CollisionStandListener implements Listener {

  private final SmallVoxels plugin;
  private final Map<String, Long> standCooldown = new HashMap<>();
  private static final long STAND_COOLDOWN_MS = 300;

  public CollisionStandListener(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  @EventHandler
  public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
    Entity entity = event.getRightClicked();

    if (!CollisionStandManager.isCollisionStand(entity)) {
      return;
    }

    event.setCancelled(true);

    Player player = event.getPlayer();
    ArmorStand stand = (ArmorStand) entity;

    Block block = stand.getLocation().getBlock();
    DataManager dataManager = plugin.getDataManager();

    if (!dataManager.hasCarvedData(block)) {
      return;
    }

    String playerName = player.getName();
    long now = System.currentTimeMillis();
    if (standCooldown.containsKey(playerName)) {
      long lastInteract = standCooldown.get(playerName);
      if (now - lastInteract < STAND_COOLDOWN_MS) {
        return;
      }
    }
    standCooldown.put(playerName, now);

    boolean isNowLocked = !dataManager.isBlockLocked(block);

    JsonArray carvedData = dataManager.getCarvedBlockData(block);
    int filledVoxels = VoxelManager.countFilledVoxels(carvedData);

    if (filledVoxels == 0) {
      return;
    }

    if (isNowLocked) {
      dataManager.setBlockLocked(block, true);
      plugin.getModeToggleListener().setStatus(player, "Locked");
    } else {
      dataManager.setBlockLocked(block, false);

      String standUUID = dataManager.getCollisionStandUUID(block);
      if (standUUID != null) {
        CollisionStandManager.removeCollisionStand(block, standUUID);
        dataManager.setCollisionStandUUID(block, null);
      }

      plugin.getModeToggleListener().setStatus(player, "Unlocked");
    }

    FallingBlockManager fallingBlockManager = plugin.getFallingBlockManager();
    fallingBlockManager.updateBlockDisplay(block, carvedData);
  }
}
