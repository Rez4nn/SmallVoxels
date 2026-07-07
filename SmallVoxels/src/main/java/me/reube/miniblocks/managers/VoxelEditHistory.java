package me.reube.SmallVoxels.managers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.reube.SmallVoxels.SmallVoxels;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public class VoxelEditHistory {

  private static final int MAX_HISTORY = 30;

  private final SmallVoxels plugin;
  private final Map<String, Deque<EditRecord>> undoStacks = new HashMap<>();
  private final Map<String, Deque<EditRecord>> redoStacks = new HashMap<>();
  private final Map<String, Material> snapshotHostTypes = new HashMap<>();

  public VoxelEditHistory(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  public List<VoxelPiece> snapshot(Block block) {
    snapshotHostTypes.put(blockKey(block), block.getType());
    List<VoxelPiece> pieces = plugin.getDataManager().getVoxelPieces(block);
    List<VoxelPiece> copy = new ArrayList<>();
    for (VoxelPiece piece : pieces) {
      copy.add(piece.copy());
    }
    return copy;
  }

  public void remember(
      Player player, Block block, List<VoxelPiece> before, List<VoxelPiece> after) {
    Map<Block, List<VoxelPiece>> beforeByBlock = new HashMap<>();
    Map<Block, List<VoxelPiece>> afterByBlock = new HashMap<>();
    beforeByBlock.put(block, before);
    afterByBlock.put(block, after);
    remember(player, beforeByBlock, afterByBlock);
  }

  public void remember(
      Player player,
      Map<Block, List<VoxelPiece>> beforeByBlock,
      Map<Block, List<VoxelPiece>> afterByBlock) {
    List<EditChange> changes = new ArrayList<>();
    for (Block block : afterByBlock.keySet()) {
      List<VoxelPiece> before = beforeByBlock.getOrDefault(block, List.of());
      List<VoxelPiece> after = afterByBlock.getOrDefault(block, List.of());
      if (!samePieces(before, after)) {
        changes.add(
            new EditChange(
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ(),
                before,
                after,
                snapshotHostTypes.remove(blockKey(block)),
                block.getType()));
      }
    }
    for (Block block : beforeByBlock.keySet()) {
      if (afterByBlock.containsKey(block)) {
        continue;
      }
      List<VoxelPiece> before = beforeByBlock.getOrDefault(block, List.of());
      if (!before.isEmpty()) {
        changes.add(
            new EditChange(
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ(),
                before,
                List.of(),
                snapshotHostTypes.remove(blockKey(block)),
                block.getType()));
      }
    }

    if (changes.isEmpty()) {
      return;
    }

    EditRecord record = new EditRecord(changes);
    Deque<EditRecord> undo =
        undoStacks.computeIfAbsent(playerKey(player), ignored -> new ArrayDeque<>());
    undo.push(record);
    while (undo.size() > MAX_HISTORY) {
      undo.removeLast();
    }
    redoStacks.computeIfAbsent(playerKey(player), ignored -> new ArrayDeque<>()).clear();
  }

  public void undo(Player player) {
    move(player, undoStacks, redoStacks, true);
  }

  public void redo(Player player) {
    move(player, redoStacks, undoStacks, false);
  }

  private void move(
      Player player,
      Map<String, Deque<EditRecord>> fromMap,
      Map<String, Deque<EditRecord>> toMap,
      boolean undo) {
    Deque<EditRecord> from =
        fromMap.computeIfAbsent(playerKey(player), ignored -> new ArrayDeque<>());
    if (from.isEmpty()) {
      plugin
          .getModeToggleListener()
          .setStatus(player, undo ? "Nothing to undo" : "Nothing to redo");
      return;
    }

    EditRecord record = from.pop();
    for (EditChange change : record.changes) {
      Block block = change.block();
      if (block == null) {
        plugin.getModeToggleListener().setStatus(player, "World not loaded");
        return;
      }

      apply(
          block, undo ? change.before : change.after, undo ? change.beforeType : change.afterType);
    }
    toMap.computeIfAbsent(playerKey(player), ignored -> new ArrayDeque<>()).push(record);
    plugin.getModeToggleListener().setStatus(player, undo ? "Undo complete" : "Redo complete");
  }

  private void apply(Block block, List<VoxelPiece> pieces, Material hostType) {
    if (pieces.isEmpty()) {
      plugin.getFallingBlockManager().removeBlockDisplay(block);
      plugin.getDataManager().setVoxelPieces(block, new ArrayList<>());
      CollisionBlockManager.updateCollisionBlock(
          block, pieces, plugin.getDataManager().isBlockLocked(block));
      return;
    }

    if (hostType != null && hostType != Material.AIR && hostType != Material.BARRIER) {
      block.setType(hostType);
    } else if (hostType == Material.BARRIER) {
      block.setType(Material.BARRIER);
    } else if (block.getType() == Material.BARRIER) {
      block.setType(Material.AIR);
    }

    plugin.getDataManager().setVoxelPieces(block, clonePieces(pieces));
    plugin.getFallingBlockManager().updateBlockDisplay(block, null);
    if (block.getType() == Material.BARRIER) {
      CollisionBlockManager.updateCollisionBlock(
          block, pieces, plugin.getDataManager().isBlockLocked(block));
    }
  }

  private boolean samePieces(List<VoxelPiece> a, List<VoxelPiece> b) {
    if (a.size() != b.size()) {
      return false;
    }
    for (int i = 0; i < a.size(); i++) {
      if (!pieceKey(a.get(i)).equals(pieceKey(b.get(i)))) {
        return false;
      }
    }
    return true;
  }

  private List<VoxelPiece> clonePieces(List<VoxelPiece> pieces) {
    List<VoxelPiece> copy = new ArrayList<>();
    for (VoxelPiece piece : pieces) {
      copy.add(piece.copy());
    }
    return copy;
  }

  private String pieceKey(VoxelPiece piece) {
    return piece.x
        + ":"
        + piece.y
        + ":"
        + piece.z
        + ":"
        + piece.size
        + ":"
        + piece.material
        + ":"
        + piece.blockData;
  }

  private String playerKey(Player player) {
    return player.getUniqueId().toString();
  }

  private String blockKey(Block block) {
    return block.getWorld().getName()
        + ":"
        + block.getX()
        + ":"
        + block.getY()
        + ":"
        + block.getZ();
  }

  private class EditRecord {
    private final List<EditChange> changes;

    EditRecord(List<EditChange> changes) {
      this.changes = changes;
    }
  }

  public class EditChange {
    private final String worldName;
    private final int x;
    private final int y;
    private final int z;
    private final List<VoxelPiece> before;
    private final List<VoxelPiece> after;
    private final Material beforeType;
    private final Material afterType;

    public EditChange(
        String worldName,
        int x,
        int y,
        int z,
        List<VoxelPiece> before,
        List<VoxelPiece> after,
        Material beforeType,
        Material afterType) {
      this.worldName = worldName;
      this.x = x;
      this.y = y;
      this.z = z;
      this.before = clonePieces(before);
      this.after = clonePieces(after);
      this.beforeType = beforeType == null ? Material.AIR : beforeType;
      this.afterType = afterType == null ? Material.AIR : afterType;
    }

    Block block() {
      World world = Bukkit.getWorld(worldName);
      return world == null ? null : world.getBlockAt(x, y, z);
    }
  }
}
