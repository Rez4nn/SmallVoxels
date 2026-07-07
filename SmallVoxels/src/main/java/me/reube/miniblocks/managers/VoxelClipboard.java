package me.reube.SmallVoxels.managers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;

public class VoxelClipboard {

  private final Map<String, List<VoxelPiece>> clips = new HashMap<>();

  public void copy(Player player, List<VoxelPiece> pieces) {
    set(player, pieces);
  }

  public void set(Player player, List<VoxelPiece> pieces) {
    List<VoxelPiece> stored = new ArrayList<>();
    if (!pieces.isEmpty()) {
      int minX = pieces.stream().mapToInt(piece -> piece.x).min().orElse(0);
      int minY = pieces.stream().mapToInt(piece -> piece.y).min().orElse(0);
      int minZ = pieces.stream().mapToInt(piece -> piece.z).min().orElse(0);

      for (VoxelPiece piece : pieces) {
        stored.add(
            new VoxelPiece(
                piece.x - minX,
                piece.y - minY,
                piece.z - minZ,
                piece.size,
                piece.material,
                piece.blockData));
      }
    }
    clips.put(key(player), stored);
  }

  public List<VoxelPiece> get(Player player) {
    List<VoxelPiece> stored = clips.getOrDefault(key(player), List.of());
    List<VoxelPiece> copy = new ArrayList<>();
    for (VoxelPiece piece : stored) {
      copy.add(piece.copy());
    }
    return copy;
  }

  public boolean hasCopy(Player player) {
    return !get(player).isEmpty();
  }

  public int count(Player player) {
    return clips.getOrDefault(key(player), List.of()).size();
  }

  public boolean rotateY(Player player) {
    List<VoxelPiece> pieces = get(player);
    if (pieces.isEmpty()) {
      return false;
    }

    int maxZ = pieces.stream().mapToInt(piece -> piece.z + piece.size).max().orElse(16);
    List<VoxelPiece> rotated = new ArrayList<>();
    for (VoxelPiece piece : pieces) {
      rotated.add(
          new VoxelPiece(
              maxZ - piece.z - piece.size,
              piece.y,
              piece.x,
              piece.size,
              piece.material,
              piece.blockData));
    }
    set(player, rotated);
    return true;
  }

  public boolean flip(Player player, String axis) {
    List<VoxelPiece> pieces = get(player);
    if (pieces.isEmpty()) {
      return false;
    }

    int maxX = pieces.stream().mapToInt(piece -> piece.x + piece.size).max().orElse(16);
    int maxY = pieces.stream().mapToInt(piece -> piece.y + piece.size).max().orElse(16);
    int maxZ = pieces.stream().mapToInt(piece -> piece.z + piece.size).max().orElse(16);
    List<VoxelPiece> flipped = new ArrayList<>();
    for (VoxelPiece piece : pieces) {
      int x = piece.x;
      int y = piece.y;
      int z = piece.z;
      if ("x".equalsIgnoreCase(axis)) {
        x = maxX - piece.x - piece.size;
      } else if ("y".equalsIgnoreCase(axis)) {
        y = maxY - piece.y - piece.size;
      } else if ("z".equalsIgnoreCase(axis)) {
        z = maxZ - piece.z - piece.size;
      } else {
        return false;
      }
      flipped.add(new VoxelPiece(x, y, z, piece.size, piece.material, piece.blockData));
    }
    set(player, flipped);
    return true;
  }

  private String key(Player player) {
    return player.getUniqueId().toString();
  }
}
