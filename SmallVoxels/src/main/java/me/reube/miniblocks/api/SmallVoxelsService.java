package me.reube.SmallVoxels.api;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.managers.ToolMode;
import me.reube.SmallVoxels.managers.VoxelPiece;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public final class SmallVoxelsService implements SmallVoxelsAPI {
  private final SmallVoxels plugin;

  public SmallVoxelsService(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean isVoxelBlock(Block block) {
    return plugin.getDataManager().hasCarvedData(Objects.requireNonNull(block));
  }

  @Override
  public List<VoxelPiece> getVoxels(Block block) {
    List<VoxelPiece> copy = new ArrayList<>();
    for (VoxelPiece piece : plugin.getDataManager().getVoxelPieces(Objects.requireNonNull(block)))
      copy.add(piece.copy());
    return List.copyOf(copy);
  }

  @Override
  public void setVoxels(Block block, List<VoxelPiece> voxels) {
    Objects.requireNonNull(block);
    Objects.requireNonNull(voxels);
    List<VoxelPiece> copy = new ArrayList<>();
    for (VoxelPiece piece : voxels) {
      if (piece == null || !piece.isValid())
        throw new IllegalArgumentException("Invalid voxel piece");
      copy.add(piece.copy());
    }
    plugin.getDataManager().setVoxelPieces(block, copy);
    refresh(block);
  }

  @Override
  public void clearVoxels(Block block) {
    setVoxels(block, List.of());
  }

  @Override
  public void refresh(Block block) {
    plugin.getFallingBlockManager().updateBlockDisplay(Objects.requireNonNull(block), null);
  }

  @Override
  public boolean isLocked(Block block) {
    return plugin.getDataManager().isBlockLocked(Objects.requireNonNull(block));
  }

  @Override
  public void setLocked(Block block, boolean locked) {
    plugin.getDataManager().setBlockLocked(Objects.requireNonNull(block), locked);
  }

  @Override
  public ToolMode getToolMode(Player player) {
    return plugin.getModeToggleListener().getToolMode(Objects.requireNonNull(player));
  }

  @Override
  public void setToolMode(Player player, ToolMode mode) {
    plugin
        .getModeToggleListener()
        .setToolMode(Objects.requireNonNull(player), Objects.requireNonNull(mode));
  }

  @Override
  public String importImage(
      Player player,
      String objectId,
      String animationId,
      File image,
      int widthPixels,
      Integer heightPixels,
      int voxelSize,
      int tick,
      String plane) {
    return plugin
        .getAnimatedObjectManager()
        .importImageFrame(
            player,
            objectId,
            animationId,
            image,
            widthPixels,
            heightPixels,
            voxelSize,
            tick,
            plane);
  }
}
