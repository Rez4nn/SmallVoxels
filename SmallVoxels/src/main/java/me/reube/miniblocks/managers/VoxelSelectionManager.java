package me.reube.SmallVoxels.managers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.reube.SmallVoxels.SmallVoxels;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public class VoxelSelectionManager {

  private final SmallVoxels plugin;
  private final Map<String, Selection> replaceSelections = new HashMap<>();
  private final Map<String, Selection> moveSelections = new HashMap<>();
  private final Map<String, Selection> previousTransformSelections = new HashMap<>();
  private final Map<String, Selection> lastCopySelections = new HashMap<>();

  public VoxelSelectionManager(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  public void setReplaceSelection(
      Player player, Block block, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    replaceSelections.put(key(player), new Selection(block, minX, minY, minZ, maxX, maxY, maxZ));
  }

  public void setReplaceWorldSelection(
      Player player,
      World world,
      int minX,
      int minY,
      int minZ,
      int maxX,
      int maxY,
      int maxZ,
      String sourceMaterial) {
    replaceSelections.put(
        key(player), new Selection(world, minX, minY, minZ, maxX, maxY, maxZ, sourceMaterial));
  }

  public boolean hasReplaceSelection(Player player) {
    return replaceSelections.containsKey(key(player));
  }

  public void setMoveWorldSelection(
      Player player, World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    rememberCurrentSelection(player);
    moveSelections.put(key(player), new Selection(world, minX, minY, minZ, maxX, maxY, maxZ, null));
  }

  public void updateTransformSelection(
      Player player, World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    moveSelections.put(key(player), new Selection(world, minX, minY, minZ, maxX, maxY, maxZ, null));
    showTransformSelection(player);
  }

  public boolean selectTransformGroup(Player player, World world, String group) {
    if (group == null || group.isBlank()) return false;
    Selection selection = new Selection(world, group);
    if (selection.blocks().isEmpty()) return false;
    rememberCurrentSelection(player);
    moveSelections.put(key(player), selection);
    showTransformSelection(player);
    return true;
  }

  public boolean hasMoveSelection(Player player) {
    return moveSelections.containsKey(key(player));
  }

  public void beginTransformReselection(Player player) {
    Selection current = moveSelections.remove(key(player));
    if (current != null) previousTransformSelections.put(key(player), current);
    plugin.getVoxelPreviewManager().clearLive(player);
  }

  public boolean restorePreviousTransformSelection(Player player) {
    String id = key(player);
    Selection previous = previousTransformSelections.get(id);
    if (previous == null) return false;
    Selection current = moveSelections.put(id, previous);
    if (current != null) previousTransformSelections.put(id, current);
    showTransformSelection(player);
    return true;
  }

  private void rememberCurrentSelection(Player player) {
    Selection current = moveSelections.get(key(player));
    if (current != null) previousTransformSelections.put(key(player), current);
  }

  public void showTransformSelection(Player player) {
    Selection selection = moveSelections.get(key(player));
    if (selection != null)
      plugin.getVoxelPreviewManager().showMove(player, previewPieces(selection, player));
  }

  public void setLastCopyWorldSelection(
      Player player, World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    lastCopySelections.put(
        key(player), new Selection(world, minX, minY, minZ, maxX, maxY, maxZ, null));
  }

  public WorldSelection lastCopySelection(Player player) {
    Selection selection = lastCopySelections.get(key(player));
    if (selection == null) {
      return null;
    }
    return new WorldSelection(
        selection.worldName,
        selection.minX,
        selection.minY,
        selection.minZ,
        selection.maxX,
        selection.maxY,
        selection.maxZ);
  }

  public boolean hasLastCopySelection(Player player) {
    return lastCopySelections.containsKey(key(player));
  }

  public int bindClickCommandToLastCopy(Player player, String command) {
    Selection selection = lastCopySelections.get(key(player));
    if (selection == null) {
      return 0;
    }
    int changed = 0;
    for (Block block : selection.blocks()) {
      if (!plugin.getVoxelProtectionManager().canEdit(player, block)) {
        continue;
      }
      boolean selected = false;
      for (VoxelPiece piece : plugin.getDataManager().getVoxelPieces(block)) {
        if (selection.contains(block, piece)) {
          selected = true;
          break;
        }
      }
      if (selected) {
        plugin.getDataManager().setClickCommand(block, command);
        changed++;
      }
    }
    plugin.getDataManager().saveAllData();
    showLastCopyOutline(player);
    return changed;
  }

  public int clearClickCommandFromLastCopy(Player player) {
    Selection selection = lastCopySelections.get(key(player));
    if (selection == null) {
      return 0;
    }
    int changed = 0;
    for (Block block : selection.blocks()) {
      if (plugin.getDataManager().getClickCommand(block) != null) {
        plugin.getDataManager().clearClickCommand(block);
        changed++;
      }
    }
    plugin.getDataManager().saveAllData();
    showLastCopyOutline(player);
    return changed;
  }

  public void showLastCopyOutline(Player player) {
    Selection selection = lastCopySelections.get(key(player));
    if (selection != null) {
      plugin.getVoxelPreviewManager().showSelection(player, previewPieces(selection, player));
    }
  }

  public boolean moveSelection(Player player, int dx, int dy, int dz) {
    Selection selection = moveSelections.get(key(player));
    if (selection == null) {
      return false;
    }

    List<SelectedPiece> visualSelection = selectedPieces(player, selection);
    if (!visualSelection.isEmpty()
        && visualSelection.stream().allMatch(item -> item.piece.hasVisualTransform())) {
      return moveSelectionByOffset(
          player, selection, visualSelection, dx / 16.0, dy / 16.0, dz / 16.0);
    }

    World world = Bukkit.getWorld(selection.worldName);
    if (world == null) {
      return false;
    }

    DataManager dataManager = plugin.getDataManager();
    List<WorldPiece> selected = new ArrayList<>();
    Set<Block> touched = new LinkedHashSet<>();
    Map<Block, List<VoxelPiece>> working = new LinkedHashMap<>();
    Map<Block, List<VoxelPiece>> beforeByBlock = new LinkedHashMap<>();

    for (Block block : selection.blocks()) {
      if (!plugin.getVoxelProtectionManager().canEdit(player, block)) {
        continue;
      }
      List<VoxelPiece> pieces = dataManager.getVoxelPieces(block);
      if (pieces.isEmpty()) {
        continue;
      }
      beforeByBlock.put(block, plugin.getVoxelEditHistory().snapshot(block));
      working.put(block, clonePieces(pieces));
      touched.add(block);
      for (VoxelPiece piece : pieces) {
        if (selection.contains(block, piece)) {
          selected.add(
              new WorldPiece(
                  block.getX() * 16 + piece.x,
                  block.getY() * 16 + piece.y,
                  block.getZ() * 16 + piece.z,
                  piece.copy()));
        }
      }
    }

    if (selected.isEmpty()) {
      return false;
    }

    for (Map.Entry<Block, List<VoxelPiece>> entry : working.entrySet()) {
      Block block = entry.getKey();
      entry.getValue().removeIf(piece -> selection.contains(block, piece));
    }

    for (WorldPiece worldPiece : selected) {
      int nextX = worldPiece.worldX + dx;
      int nextY = worldPiece.worldY + dy;
      int nextZ = worldPiece.worldZ + dz;
      Block target =
          world.getBlockAt(
              Math.floorDiv(nextX, 16), Math.floorDiv(nextY, 16), Math.floorDiv(nextZ, 16));
      if (!plugin.getVoxelProtectionManager().canEdit(player, target) || !canMoveInto(target)) {
        return false;
      }
      touched.add(target);
      working.computeIfAbsent(target, block -> clonePieces(dataManager.getVoxelPieces(block)));
      beforeByBlock.computeIfAbsent(target, plugin.getVoxelEditHistory()::snapshot);

      VoxelPiece shifted = worldPiece.piece.copy();
      shifted.x = Math.floorMod(nextX, 16);
      shifted.y = Math.floorMod(nextY, 16);
      shifted.z = Math.floorMod(nextZ, 16);
      if (!VoxelPieceManager.place(working.get(target), shifted)) {
        return false;
      }
    }

    Map<Block, List<VoxelPiece>> afterByBlock = new LinkedHashMap<>();
    for (Block block : touched) {
      plugin.getVoxelProtectionManager().claimIfNeeded(player, block);
      List<VoxelPiece> pieces = working.getOrDefault(block, new ArrayList<>());
      VoxelPieceManager.compact(pieces);
      if (!pieces.isEmpty() && canTurnIntoVoxelHost(block)) {
        block.setType(Material.BARRIER);
      } else if (pieces.isEmpty() && block.getType() == Material.BARRIER) {
        block.setType(Material.AIR);
      }
      dataManager.setVoxelPieces(block, pieces);
      plugin.getFallingBlockManager().updateBlockDisplay(block, null);
      CollisionBlockManager.updateCollisionBlock(block, pieces, dataManager.isBlockLocked(block));
      afterByBlock.put(block, plugin.getVoxelEditHistory().snapshot(block));
    }

    plugin.getVoxelEditHistory().remember(player, beforeByBlock, afterByBlock);
    moveSelections.put(key(player), selection.shift(dx, dy, dz));
    plugin
        .getVoxelPreviewManager()
        .showMove(player, previewPieces(selection.shift(dx, dy, dz), player));
    return true;
  }

  private boolean moveSelectionByOffset(
      Player player,
      Selection selection,
      List<SelectedPiece> selected,
      double dx,
      double dy,
      double dz) {
    Map<Block, List<VoxelPiece>> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
    Set<Block> touched = new LinkedHashSet<>();
    for (SelectedPiece item : selected) {
      before.computeIfAbsent(item.block, plugin.getVoxelEditHistory()::snapshot);
      item.piece.offsetX += dx;
      item.piece.offsetY += dy;
      item.piece.offsetZ += dz;
      touched.add(item.block);
    }
    for (Block block : touched) {
      List<VoxelPiece> pieces =
          selected.stream()
              .filter(item -> item.block.equals(block))
              .findFirst()
              .orElseThrow()
              .pieces;
      plugin.getDataManager().setVoxelPieces(block, pieces);
      plugin.getFallingBlockManager().updateBlockDisplay(block, null);
      CollisionBlockManager.updateCollisionBlock(
          block, pieces, plugin.getDataManager().isBlockLocked(block));
      after.put(block, plugin.getVoxelEditHistory().snapshot(block));
    }
    plugin.getVoxelEditHistory().remember(player, before, after);
    plugin.getVoxelPreviewManager().showMove(player, previewPieces(selection, player));
    return !touched.isEmpty();
  }

  public boolean moveSelectionLocal(Player player, int dx, int dy, int dz) {
    Selection selection = moveSelections.get(key(player));
    if (selection == null) return false;
    VoxelPiece reference = null;
    for (Block block : selection.blocks()) {
      for (VoxelPiece piece : plugin.getDataManager().getVoxelPieces(block)) {
        if (selection.contains(block, piece) && piece.hasVisualTransform()) {
          reference = piece;
          break;
        }
      }
      if (reference != null) break;
    }
    if (reference == null) return moveSelection(player, dx, dy, dz);
    org.joml.Vector3f delta = new org.joml.Vector3f(dx / 16.0f, dy / 16.0f, dz / 16.0f);
    reference.rotationQuaternion().transform(delta);
    Map<Block, List<VoxelPiece>> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
    boolean changed = false;
    for (Block block : selection.blocks()) {
      if (!plugin.getVoxelProtectionManager().canEdit(player, block)) continue;
      List<VoxelPiece> pieces = plugin.getDataManager().getVoxelPieces(block);
      boolean blockChanged = false;
      for (VoxelPiece piece : pieces) {
        if (selection.contains(block, piece)) {
          if (!blockChanged) before.put(block, plugin.getVoxelEditHistory().snapshot(block));
          piece.offsetX += delta.x;
          piece.offsetY += delta.y;
          piece.offsetZ += delta.z;
          blockChanged = changed = true;
        }
      }
      if (blockChanged) {
        plugin.getDataManager().setVoxelPieces(block, pieces);
        plugin.getFallingBlockManager().updateBlockDisplay(block, null);
        CollisionBlockManager.updateCollisionBlock(
            block, pieces, plugin.getDataManager().isBlockLocked(block));
        after.put(block, plugin.getVoxelEditHistory().snapshot(block));
      }
    }
    if (changed) plugin.getVoxelEditHistory().remember(player, before, after);
    plugin.getVoxelPreviewManager().showMove(player, previewPieces(selection, player));
    return changed;
  }

  public boolean scaleSelection(Player player, String axis, double factor) {
    Selection selection = moveSelections.get(key(player));
    if (selection == null || factor <= 0.0) return false;
    List<SelectedPiece> selected = selectedPieces(player, selection);
    if (selected.isEmpty()) return false;
    double pivotX = selected.stream().mapToDouble(SelectedPiece::centerX).average().orElse(0);
    double pivotY = selected.stream().mapToDouble(SelectedPiece::centerY).average().orElse(0);
    double pivotZ = selected.stream().mapToDouble(SelectedPiece::centerZ).average().orElse(0);
    VoxelPiece reference = selected.get(0).piece;
    org.joml.Quaternionf rotation = reference.rotationQuaternion();
    org.joml.Quaternionf inverse = new org.joml.Quaternionf(rotation).invert();
    boolean x = axis.equalsIgnoreCase("x") || axis.equalsIgnoreCase("uniform");
    boolean y = axis.equalsIgnoreCase("y") || axis.equalsIgnoreCase("uniform");
    boolean z = axis.equalsIgnoreCase("z") || axis.equalsIgnoreCase("uniform");
    Map<Block, List<VoxelPiece>> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
    Set<Block> touched = new LinkedHashSet<>();
    String group =
        selected.stream()
            .map(item -> item.piece.transformGroup)
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElseGet(() -> java.util.UUID.randomUUID().toString());
    for (SelectedPiece item : selected) {
      before.computeIfAbsent(item.block, plugin.getVoxelEditHistory()::snapshot);
      org.joml.Vector3f relative =
          inverse.transform(
              new org.joml.Vector3f(
                  (float) (item.centerX() - pivotX),
                  (float) (item.centerY() - pivotY),
                  (float) (item.centerZ() - pivotZ)));
      if (x) relative.x *= factor;
      if (y) relative.y *= factor;
      if (z) relative.z *= factor;
      rotation.transform(relative);
      double anchorX = item.block.getX() + (item.piece.x + item.piece.size / 2.0) / 16.0;
      double anchorY = item.block.getY() + (item.piece.y + item.piece.size / 2.0) / 16.0;
      double anchorZ = item.block.getZ() + (item.piece.z + item.piece.size / 2.0) / 16.0;
      item.piece.offsetX = pivotX + relative.x - anchorX;
      item.piece.offsetY = pivotY + relative.y - anchorY;
      item.piece.offsetZ = pivotZ + relative.z - anchorZ;
      if (x) item.piece.scaleX = clampScale(item.piece.scaleX * factor);
      if (y) item.piece.scaleY = clampScale(item.piece.scaleY * factor);
      if (z) item.piece.scaleZ = clampScale(item.piece.scaleZ * factor);
      item.piece.transformGroup = group;
      touched.add(item.block);
    }
    for (Block block : touched) {
      List<VoxelPiece> pieces =
          selected.stream()
              .filter(item -> item.block.equals(block))
              .findFirst()
              .orElseThrow()
              .pieces;
      plugin.getDataManager().setVoxelPieces(block, pieces);
      plugin.getFallingBlockManager().updateBlockDisplay(block, null);
      CollisionBlockManager.updateCollisionBlock(
          block, pieces, plugin.getDataManager().isBlockLocked(block));
      after.put(block, plugin.getVoxelEditHistory().snapshot(block));
    }
    plugin.getVoxelEditHistory().remember(player, before, after);
    plugin.getVoxelPreviewManager().showMove(player, previewPieces(selection, player));
    return true;
  }

  public boolean rotateSelection(Player player, String axis, double degrees) {
    Selection selection = moveSelections.get(key(player));
    if (selection == null) return false;
    List<SelectedPiece> selected = selectedPieces(player, selection);
    if (selected.isEmpty()) return false;
    double pivotX = selected.stream().mapToDouble(SelectedPiece::centerX).average().orElse(0);
    double pivotY = selected.stream().mapToDouble(SelectedPiece::centerY).average().orElse(0);
    double pivotZ = selected.stream().mapToDouble(SelectedPiece::centerZ).average().orElse(0);
    double radians = Math.toRadians(degrees), sin = Math.sin(radians), cos = Math.cos(radians);
    String group =
        selected.stream()
            .map(item -> item.piece.transformGroup)
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElseGet(() -> java.util.UUID.randomUUID().toString());
    Map<Block, List<VoxelPiece>> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
    Set<Block> touched = new LinkedHashSet<>();
    for (SelectedPiece item : selected) {
      before.computeIfAbsent(item.block, plugin.getVoxelEditHistory()::snapshot);
      double dx = item.centerX() - pivotX,
          dy = item.centerY() - pivotY,
          dz = item.centerZ() - pivotZ;
      double rx = dx, ry = dy, rz = dz;
      org.joml.Quaternionf deltaRotation;
      if (axis.equalsIgnoreCase("x")) {
        ry = dy * cos - dz * sin;
        rz = dy * sin + dz * cos;
        deltaRotation = new org.joml.Quaternionf().rotateX((float) radians);
      } else if (axis.equalsIgnoreCase("z")) {
        rx = dx * cos - dy * sin;
        ry = dx * sin + dy * cos;
        deltaRotation = new org.joml.Quaternionf().rotateZ((float) radians);
      } else {
        rx = dx * cos - dz * sin;
        rz = dx * sin + dz * cos;
        deltaRotation = new org.joml.Quaternionf().rotateY((float) radians);
      }
      item.piece.setRotationQuaternion(deltaRotation.mul(item.piece.rotationQuaternion()));
      double anchorX = item.block.getX() + (item.piece.x + item.piece.size / 2.0) / 16.0;
      double anchorY = item.block.getY() + (item.piece.y + item.piece.size / 2.0) / 16.0;
      double anchorZ = item.block.getZ() + (item.piece.z + item.piece.size / 2.0) / 16.0;
      item.piece.offsetX = pivotX + rx - anchorX;
      item.piece.offsetY = pivotY + ry - anchorY;
      item.piece.offsetZ = pivotZ + rz - anchorZ;
      item.piece.transformGroup = group;
      touched.add(item.block);
    }
    for (Block block : touched) {
      List<VoxelPiece> pieces =
          selected.stream()
              .filter(item -> item.block.equals(block))
              .findFirst()
              .orElseThrow()
              .pieces;
      plugin.getDataManager().setVoxelPieces(block, pieces);
      plugin.getFallingBlockManager().updateBlockDisplay(block, null);
      CollisionBlockManager.updateCollisionBlock(
          block, pieces, plugin.getDataManager().isBlockLocked(block));
      after.put(block, plugin.getVoxelEditHistory().snapshot(block));
    }
    plugin.getVoxelEditHistory().remember(player, before, after);
    plugin.getVoxelPreviewManager().showMove(player, previewPieces(selection, player));
    return true;
  }

  private double clampScale(double value) {
    return Math.max(0.05, Math.min(32.0, value));
  }

  private List<SelectedPiece> selectedPieces(Player player, Selection selection) {
    List<SelectedPiece> result = new ArrayList<>();
    for (Block block : selection.blocks()) {
      if (!plugin.getVoxelProtectionManager().canEdit(player, block)) continue;
      List<VoxelPiece> pieces = plugin.getDataManager().getVoxelPieces(block);
      for (VoxelPiece piece : pieces) {
        if (selection.contains(block, piece)) result.add(new SelectedPiece(block, pieces, piece));
      }
    }
    return result;
  }

  private record SelectedPiece(Block block, List<VoxelPiece> pieces, VoxelPiece piece) {
    double centerX() {
      return block.getX() + (piece.x + piece.size / 2.0) / 16.0 + piece.offsetX;
    }

    double centerY() {
      return block.getY() + (piece.y + piece.size / 2.0) / 16.0 + piece.offsetY;
    }

    double centerZ() {
      return block.getZ() + (piece.z + piece.size / 2.0) / 16.0 + piece.offsetZ;
    }
  }

  public boolean applyReplaceSelection(Player player, String material) {
    Selection selection = replaceSelections.remove(key(player));
    if (selection == null) {
      return false;
    }

    DataManager dataManager = plugin.getDataManager();
    int changed = 0;
    Map<Block, List<VoxelPiece>> beforeByBlock = new HashMap<>();
    Map<Block, List<VoxelPiece>> afterByBlock = new HashMap<>();
    for (Block block : selection.blocks()) {
      if (!plugin.getVoxelProtectionManager().canEdit(player, block)) {
        continue;
      }
      plugin.getVoxelProtectionManager().claimIfNeeded(player, block);
      List<VoxelPiece> pieces = dataManager.getVoxelPieces(block);
      if (pieces.isEmpty()) {
        continue;
      }
      List<VoxelPiece> before = plugin.getVoxelEditHistory().snapshot(block);
      int blockChanged = 0;

      for (VoxelPiece piece : pieces) {
        if (selection.contains(block, piece) && selection.matches(piece)) {
          piece.material = material;
          piece.blockData = null;
          changed++;
          blockChanged++;
        }
      }

      if (blockChanged > 0) {
        dataManager.setVoxelPieces(block, pieces);
        plugin.getFallingBlockManager().updateBlockDisplay(block, null);
        CollisionBlockManager.updateCollisionBlock(block, pieces, dataManager.isBlockLocked(block));
        beforeByBlock.put(block, before);
        afterByBlock.put(block, plugin.getVoxelEditHistory().snapshot(block));
      }
    }
    plugin.getVoxelEditHistory().remember(player, beforeByBlock, afterByBlock);

    return changed > 0;
  }

  private String key(Player player) {
    return player.getUniqueId().toString();
  }

  private boolean canMoveInto(Block block) {
    return plugin.getDataManager().hasCarvedData(block)
        || block.getType() == Material.AIR
        || (block.isPassable() && block.getType() != Material.BARRIER);
  }

  private boolean canTurnIntoVoxelHost(Block block) {
    return block.getType() == Material.AIR
        || (block.isPassable() && block.getType() != Material.BARRIER);
  }

  private List<VoxelPiece> clonePieces(List<VoxelPiece> pieces) {
    List<VoxelPiece> copy = new ArrayList<>();
    for (VoxelPiece piece : pieces) {
      copy.add(piece.copy());
    }
    return copy;
  }

  private Map<Block, List<VoxelPiece>> previewPieces(Selection selection, Player player) {
    Map<Block, List<VoxelPiece>> result = new LinkedHashMap<>();
    for (Block block : selection.blocks()) {
      for (VoxelPiece piece : plugin.getDataManager().getVoxelPieces(block)) {
        if (selection.contains(block, piece)) {
          result.computeIfAbsent(block, ignored -> new ArrayList<>()).add(piece.copy());
        }
      }
    }
    return result;
  }

  private class Selection {
    private final String worldName;
    private final int blockX;
    private final int blockY;
    private final int blockZ;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private final String sourceMaterial;
    private final String transformGroup;

    Selection(Block block, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
      this.worldName = block.getWorld().getName();
      this.blockX = Integer.MIN_VALUE;
      this.blockY = Integer.MIN_VALUE;
      this.blockZ = Integer.MIN_VALUE;
      this.minX = block.getX() * 16 + minX;
      this.minY = block.getY() * 16 + minY;
      this.minZ = block.getZ() * 16 + minZ;
      this.maxX = block.getX() * 16 + maxX;
      this.maxY = block.getY() * 16 + maxY;
      this.maxZ = block.getZ() * 16 + maxZ;
      this.sourceMaterial = null;
      this.transformGroup = null;
    }

    Selection(
        World world,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ,
        String sourceMaterial) {
      this.worldName = world.getName();
      this.blockX = Integer.MIN_VALUE;
      this.blockY = Integer.MIN_VALUE;
      this.blockZ = Integer.MIN_VALUE;
      this.minX = minX;
      this.minY = minY;
      this.minZ = minZ;
      this.maxX = maxX;
      this.maxY = maxY;
      this.maxZ = maxZ;
      this.sourceMaterial = sourceMaterial;
      this.transformGroup = null;
    }

    Selection(World world, String transformGroup) {
      this.worldName = world.getName();
      this.blockX = this.blockY = this.blockZ = Integer.MIN_VALUE;
      this.minX = this.minY = this.minZ = 0;
      this.maxX = this.maxY = this.maxZ = 0;
      this.sourceMaterial = null;
      this.transformGroup = transformGroup;
    }

    Selection shift(int dx, int dy, int dz) {
      World world = Bukkit.getWorld(worldName);
      if (world == null) {
        return this;
      }
      if (transformGroup != null) return this;
      return new Selection(
          world, minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz, sourceMaterial);
    }

    Block block() {
      World world = Bukkit.getWorld(worldName);
      return world == null ? null : world.getBlockAt(blockX, blockY, blockZ);
    }

    List<Block> blocks() {
      World world = Bukkit.getWorld(worldName);
      if (world == null) {
        return List.of();
      }
      if (transformGroup != null) {
        List<Block> grouped = new ArrayList<>();
        for (Block block : plugin.getDataManager().getSavedVoxelBlocks(world)) {
          if (plugin.getDataManager().getVoxelPieces(block).stream()
              .anyMatch(piece -> transformGroup.equals(piece.transformGroup))) grouped.add(block);
        }
        return grouped;
      }
      java.util.ArrayList<Block> blocks = new java.util.ArrayList<>();
      int minBlockX = Math.floorDiv(minX, 16);
      int minBlockY = Math.floorDiv(minY, 16);
      int minBlockZ = Math.floorDiv(minZ, 16);
      int maxBlockX = Math.floorDiv(maxX - 1, 16);
      int maxBlockY = Math.floorDiv(maxY - 1, 16);
      int maxBlockZ = Math.floorDiv(maxZ - 1, 16);
      for (int x = minBlockX; x <= maxBlockX; x++) {
        for (int y = minBlockY; y <= maxBlockY; y++) {
          for (int z = minBlockZ; z <= maxBlockZ; z++) {
            blocks.add(world.getBlockAt(x, y, z));
          }
        }
      }
      return blocks;
    }

    boolean contains(Block block, VoxelPiece piece) {
      if (transformGroup != null) return transformGroup.equals(piece.transformGroup);
      int x = block.getX() * 16 + piece.x;
      int y = block.getY() * 16 + piece.y;
      int z = block.getZ() * 16 + piece.z;
      return x < maxX
          && x + piece.size > minX
          && y < maxY
          && y + piece.size > minY
          && z < maxZ
          && z + piece.size > minZ;
    }

    boolean matches(VoxelPiece piece) {
      return sourceMaterial == null || sourceMaterial.equalsIgnoreCase(piece.material);
    }
  }

  private static class WorldPiece {
    private final int worldX;
    private final int worldY;
    private final int worldZ;
    private final VoxelPiece piece;

    WorldPiece(int worldX, int worldY, int worldZ, VoxelPiece piece) {
      this.worldX = worldX;
      this.worldY = worldY;
      this.worldZ = worldZ;
      this.piece = piece;
    }
  }

  public record WorldSelection(
      String worldName, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}
}
