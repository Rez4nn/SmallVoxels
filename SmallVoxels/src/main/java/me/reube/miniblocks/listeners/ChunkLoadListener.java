package me.reube.SmallVoxels.listeners;

import java.util.Iterator;
import java.util.List;
import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.managers.CollisionStandManager;
import me.reube.SmallVoxels.managers.DataManager;
import me.reube.SmallVoxels.managers.FallingBlockManager;
import me.reube.SmallVoxels.managers.VoxelPiece;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

public class ChunkLoadListener implements Listener {

  private final SmallVoxels plugin;

  public ChunkLoadListener(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  @EventHandler
  public void onChunkLoad(ChunkLoadEvent event) {
    plugin.getServer().getScheduler().runTaskLater(plugin, () -> refreshChunk(event), 2L);
  }

  private void refreshChunk(ChunkLoadEvent event) {
    DataManager dataManager = plugin.getDataManager();
    FallingBlockManager displays = plugin.getFallingBlockManager();
    int chunkX = event.getChunk().getX();
    int chunkZ = event.getChunk().getZ();

    displays.removePersistedVoxelEntities(event.getChunk());
    Iterator<Block> blocks =
        dataManager.getSavedVoxelBlocks(event.getWorld(), chunkX, chunkZ).iterator();
    int blocksPerTick = Math.max(1, plugin.getConfig().getInt("loading.blocks-per-tick", 8));
    new org.bukkit.scheduler.BukkitRunnable() {
      @Override
      public void run() {
        long started = System.nanoTime();
        if (!event.getChunk().isLoaded()) {
          cancel();
          return;
        }
        int processed = 0;
        while (blocks.hasNext() && processed++ < blocksPerTick) {
          restoreBlock(blocks.next());
        }
        if (!blocks.hasNext()) {
          cancel();
        }
        plugin
            .getPerformanceMonitor()
            .record(
                "chunk voxel restoration",
                started,
                event.getWorld().getName() + " chunk " + chunkX + "," + chunkZ);
      }
    }.runTaskTimer(plugin, 0L, 1L);
  }

  private void restoreBlock(Block block) {
    DataManager dataManager = plugin.getDataManager();
    FallingBlockManager displays = plugin.getFallingBlockManager();
    if (!dataManager.hasCarvedData(block)) {
      return;
    }
    List<VoxelPiece> pieces = dataManager.getVoxelPieces(block);
    try {
      displays.updateBlockDisplay(block, null);
      if (block.getType() == Material.BARRIER && !pieces.isEmpty()) {
        block.setType(Material.BARRIER);
      }
      restoreCollisionStand(dataManager, block);
    } catch (Exception ex) {
      plugin
          .getLogger()
          .warning(
              "Failed to restore voxel block at "
                  + block.getX()
                  + " "
                  + block.getY()
                  + " "
                  + block.getZ()
                  + ": "
                  + ex.getMessage());
    }
  }

  private void restoreCollisionStand(DataManager dataManager, Block block) {
    if (!dataManager.isBlockLocked(block)) {
      dataManager.setCollisionStandUUID(block, null);
      return;
    }
    ArmorStand stand = CollisionStandManager.spawnCollisionStand(block);
    dataManager.setCollisionStandUUID(block, stand.getUniqueId().toString());
  }
}
