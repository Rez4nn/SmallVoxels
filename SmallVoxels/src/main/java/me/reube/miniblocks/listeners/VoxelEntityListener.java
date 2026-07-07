package me.reube.SmallVoxels.listeners;

import com.google.gson.JsonArray;
import java.util.HashMap;
import java.util.Map;
import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.managers.CollisionStandManager;
import me.reube.SmallVoxels.managers.DataManager;
import me.reube.SmallVoxels.managers.FallingBlockManager;
import me.reube.SmallVoxels.managers.ToolMode;
import me.reube.SmallVoxels.managers.VoxelManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.persistence.PersistentDataType;

/** Handles commands and lock toggles attached to rendered voxel entities. */
public class VoxelEntityListener implements Listener {

  private final SmallVoxels plugin;
  private final NamespacedKey pieceXKey;
  private final NamespacedKey pieceYKey;
  private final NamespacedKey pieceZKey;
  private final NamespacedKey pieceSizeKey;
  private final NamespacedKey hostWorldKey;
  private final NamespacedKey hostXKey;
  private final NamespacedKey hostYKey;
  private final NamespacedKey hostZKey;
  private final Map<String, Long> entityClickCooldown = new HashMap<>();
  private static final long CLICK_COOLDOWN_MS = 300;

  public VoxelEntityListener(SmallVoxels plugin) {
    this.plugin = plugin;
    this.pieceXKey = new NamespacedKey(plugin, "piece_x");
    this.pieceYKey = new NamespacedKey(plugin, "piece_y");
    this.pieceZKey = new NamespacedKey(plugin, "piece_z");
    this.pieceSizeKey = new NamespacedKey(plugin, "piece_size");
    this.hostWorldKey = new NamespacedKey(plugin, "host_world");
    this.hostXKey = new NamespacedKey(plugin, "host_x");
    this.hostYKey = new NamespacedKey(plugin, "host_y");
    this.hostZKey = new NamespacedKey(plugin, "host_z");
  }

  @EventHandler
  public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
    Entity entity = event.getRightClicked();

    if (!(entity instanceof Display)) {
      return;
    }

    event.setCancelled(true);

    Player player = event.getPlayer();
    Display display = (Display) entity;

    // Host coordinates remain authoritative when a player temporarily removes the barrier.
    Block block = hostBlock(display);
    DataManager dataManager = plugin.getDataManager();

    if (plugin.getModeToggleListener().getToolMode(player) == ToolMode.DETAIL) {
      VoxelManager.RaycastResult hit =
          VoxelManager.raycastThroughBarriers(
              player.getEyeLocation(), player.getEyeLocation().getDirection(), dataManager);
      org.bukkit.block.BlockFace face =
          hit != null && hit.hitFace != null ? hit.hitFace : player.getFacing().getOppositeFace();
      org.bukkit.util.Vector position =
          hit == null
              ? new org.bukkit.util.Vector(.5, .5, .5)
              : new org.bukkit.util.Vector(
                  (hit.hitX + .5) / 16.0, (hit.hitY + .5) / 16.0, (hit.hitZ + .5) / 16.0);
      boolean applied = plugin.getDetailManager().apply(player, block, face, position);
      player.sendActionBar(
          Component.text(applied ? "Detail applied" : "That surface could not be edited")
              .color(applied ? NamedTextColor.GREEN : NamedTextColor.RED));
      return;
    }

    String playerName = player.getName();
    long now = System.currentTimeMillis();
    if (entityClickCooldown.containsKey(playerName)) {
      long lastClick = entityClickCooldown.get(playerName);
      if (now - lastClick < CLICK_COOLDOWN_MS) {
        return;
      }
    }
    entityClickCooldown.put(playerName, now);

    if (runClickCommand(player, block, entity)) {
      return;
    }

    if (!dataManager.hasCarvedData(block)) {
      return;
    }

    boolean isNowLocked = !dataManager.isBlockLocked(block);

    JsonArray carvedData = dataManager.getCarvedBlockData(block);
    int filledVoxels = VoxelManager.countFilledVoxels(carvedData);

    if (filledVoxels == 0) {
      return;
    }

    if (isNowLocked) {
      dataManager.setBlockLocked(block, true);
      org.bukkit.entity.ArmorStand stand = CollisionStandManager.spawnCollisionStand(block);
      dataManager.setCollisionStandUUID(block, stand.getUniqueId().toString());
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

  private Block hostBlock(Display display) {
    String worldName =
        display.getPersistentDataContainer().get(hostWorldKey, PersistentDataType.STRING);
    Integer x = display.getPersistentDataContainer().get(hostXKey, PersistentDataType.INTEGER);
    Integer y = display.getPersistentDataContainer().get(hostYKey, PersistentDataType.INTEGER);
    Integer z = display.getPersistentDataContainer().get(hostZKey, PersistentDataType.INTEGER);
    if (worldName != null && x != null && y != null && z != null) {
      World world = org.bukkit.Bukkit.getWorld(worldName);
      if (world != null) {
        return world.getBlockAt(x, y, z);
      }
    }
    return display.getLocation().getBlock();
  }

  private boolean runClickCommand(Player player, Block block, Entity entity) {
    Integer pieceX = entity.getPersistentDataContainer().get(pieceXKey, PersistentDataType.INTEGER);
    Integer pieceY = entity.getPersistentDataContainer().get(pieceYKey, PersistentDataType.INTEGER);
    Integer pieceZ = entity.getPersistentDataContainer().get(pieceZKey, PersistentDataType.INTEGER);
    Integer pieceSize =
        entity.getPersistentDataContainer().get(pieceSizeKey, PersistentDataType.INTEGER);
    String command = null;
    if (pieceX != null && pieceY != null && pieceZ != null && pieceSize != null) {
      command =
          plugin.getDataManager().getPieceClickCommand(block, pieceX, pieceY, pieceZ, pieceSize);
    }
    if (command == null || command.isBlank()) {
      command = plugin.getDataManager().getClickCommand(block);
    }
    if (command == null || command.isBlank()) {
      return false;
    }

    String resolved =
        command
            .replace("{player}", player.getName())
            .replace("{world}", block.getWorld().getName())
            .replace("{x}", String.valueOf(block.getX()))
            .replace("{y}", String.valueOf(block.getY()))
            .replace("{z}", String.valueOf(block.getZ()));
    if (resolved.regionMatches(true, 0, "console:", 0, "console:".length())) {
      org.bukkit.Bukkit.dispatchCommand(
          org.bukkit.Bukkit.getConsoleSender(), resolved.substring("console:".length()).trim());
    } else {
      player.performCommand(resolved.startsWith("/") ? resolved.substring(1) : resolved);
    }
    plugin.getModeToggleListener().setStatus(player, "Command triggered");
    return true;
  }
}
