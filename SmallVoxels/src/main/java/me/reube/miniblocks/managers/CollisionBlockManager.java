package me.reube.SmallVoxels.managers;

import com.google.gson.JsonArray;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.block.Block;

public class CollisionBlockManager {

  public static void updateCollisionBlock(Block block, JsonArray voxelData, boolean isLocked) {
    if (voxelData == null || voxelData.size() == 0) {
      clearEditableHost(block);
      return;
    }

    int filledVoxels = 0;
    for (int i = 0; i < voxelData.size(); i++) {
      if (!voxelData.get(i).isJsonNull()) {
        String voxel = voxelData.get(i).getAsString();
        if (!voxel.equals("air")) {
          filledVoxels++;
        }
      }
    }

    if (filledVoxels > 0) {
      ensureEditableHost(block);
    } else {
      clearEditableHost(block);
    }
  }

  public static void removeCollisionBlock(Block block, String originalBlockType) {
    try {
      Material original = Material.valueOf(originalBlockType);
      block.setType(original);
    } catch (Exception e) {
      block.setType(Material.STONE);
    }
  }

  public static void updateCollisionBlock(Block block, List<VoxelPiece> pieces, boolean isLocked) {
    if (pieces == null || pieces.isEmpty()) {
      clearEditableHost(block);
    } else {
      ensureEditableHost(block);
    }
  }

  private static void ensureEditableHost(Block block) {
    if (block.getType() == Material.AIR || block.getType() == Material.BARRIER) {
      block.setType(Material.BARRIER);
    }
  }

  private static void clearEditableHost(Block block) {
    if (block.getType() == Material.BARRIER) {
      block.setType(Material.AIR);
    }
  }
}
