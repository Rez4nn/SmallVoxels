package me.reube.SmallVoxels.api;

import java.io.File;
import java.util.List;
import me.reube.SmallVoxels.managers.ToolMode;
import me.reube.SmallVoxels.managers.VoxelPiece;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** Public, implementation-independent API for SmallVoxels integrations. */
public interface SmallVoxelsAPI {
  boolean isVoxelBlock(Block block);

  List<VoxelPiece> getVoxels(Block block);

  void setVoxels(Block block, List<VoxelPiece> voxels);

  void clearVoxels(Block block);

  void refresh(Block block);

  boolean isLocked(Block block);

  void setLocked(Block block, boolean locked);

  ToolMode getToolMode(Player player);

  void setToolMode(Player player, ToolMode mode);

  String importImage(
      Player player,
      String objectId,
      String animationId,
      File image,
      int widthPixels,
      Integer heightPixels,
      int voxelSize,
      int tick,
      String plane);
}
