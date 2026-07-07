package me.reube.SmallVoxels.listeners;

import java.util.List;
import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.managers.ChiselManager;
import me.reube.SmallVoxels.managers.CollisionStandManager;
import me.reube.SmallVoxels.managers.DataManager;
import me.reube.SmallVoxels.managers.FallingBlockManager;
import me.reube.SmallVoxels.managers.VoxelPiece;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

public class BlockBreakListener implements Listener {

  private final SmallVoxels plugin;

  public BlockBreakListener(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  @EventHandler
  public void onBlockBreak(BlockBreakEvent event) {
    Block block = event.getBlock();
    Player player = event.getPlayer();

    if (block.getType() == org.bukkit.Material.AIR) {
      return;
    }

    DataManager dataManager = plugin.getDataManager();

    if (block.getType() != org.bukkit.Material.BARRIER || !dataManager.hasCarvedData(block)) {
      return;
    }

    if (!plugin.getVoxelProtectionManager().canEdit(player, block)) {
      event.setCancelled(true);
      plugin.getModeToggleListener().setStatus(player, "Protected voxel");
      return;
    }
    plugin.getVoxelProtectionManager().claimIfNeeded(player, block);

    boolean isSneaking = player.isSneaking();
    ChiselManager chiselManager = plugin.getChiselManager();
    boolean holdingAxe = chiselManager.isChiselAxe(player.getInventory().getItemInMainHand());
    boolean isCreative = player.getGameMode() == org.bukkit.GameMode.CREATIVE;

    if (!holdingAxe
        && plugin.getSurvivalVoxelManager().isMiningIndividualEnabled(block.getWorld())) {
      event.setCancelled(true);
      if (!isCreative
          && !plugin
              .getSurvivalVoxelManager()
              .canHarvestAll(player, dataManager.getVoxelPieces(block))) {
        plugin.getModeToggleListener().setStatus(player, "Wrong tool for voxel block");
        return;
      }

      String standUUID = dataManager.getCollisionStandUUID(block);
      if (standUUID != null) {
        CollisionStandManager.removeCollisionStand(block, standUUID);
      }

      FallingBlockManager fallingBlockManager = plugin.getFallingBlockManager();
      List<VoxelPiece> pieces = dataManager.getVoxelPieces(block);
      fallingBlockManager.removeBlockDisplay(block);
      if (!isCreative) {
        plugin.getSurvivalVoxelManager().dropVoxelItems(block, pieces);
      }
      dataManager.removeCarvedBlockAndMetadata(block);
      block.setType(org.bukkit.Material.AIR);
      plugin.getModeToggleListener().setStatus(player, "Voxel bits mined");
      return;
    }

    if (isSneaking && !holdingAxe) {
      event.setCancelled(true);
      String standUUID = dataManager.getCollisionStandUUID(block);
      if (standUUID != null) {
        CollisionStandManager.removeCollisionStand(block, standUUID);
      }

      FallingBlockManager fallingBlockManager = plugin.getFallingBlockManager();

      List<VoxelPiece> pieces = dataManager.getVoxelPieces(block);
      fallingBlockManager.removeBlockDisplay(block);
      fallingBlockManager.dropBlockItems(block, pieces);
      dataManager.removeCarvedBlockAndMetadata(block);
      block.setType(org.bukkit.Material.AIR);

      plugin.getModeToggleListener().setStatus(player, "Carved block removed");
    } else if (!isSneaking && !holdingAxe && isCreative) {
      event.setCancelled(true);
      block.setType(org.bukkit.Material.AIR);
      plugin.getModeToggleListener().setStatus(player, "Barrier removed");
    } else {
      event.setCancelled(true);
      if (!isSneaking && !isCreative) {
        plugin.getModeToggleListener().setStatus(player, "Shift break without axe");
      }
    }
  }
}
