package me.reube.SmallVoxels.managers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class VoxelPreviewManager {

  private final JavaPlugin plugin;
  private final Map<String, Map<String, BlockDisplay>> previews = new HashMap<>();

  public VoxelPreviewManager(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  public void showPaste(Player player, Block block, int[] anchor, List<VoxelPiece> copiedPieces) {
    int index = 0;
    for (VoxelPiece copied : copiedPieces) {
      int minX = copied.x + anchor[0];
      int minY = copied.y + anchor[1];
      int minZ = copied.z + anchor[2];
      int maxX = minX + copied.size;
      int maxY = minY + copied.size;
      int maxZ = minZ + copied.size;
      if (minX < 0 || minY < 0 || minZ < 0 || maxX > 16 || maxY > 16 || maxZ > 16) {
        continue;
      }
      showBox(player, "paste-" + index, block, minX, minY, minZ, maxX, maxY, maxZ, Color.ORANGE);
      index++;
    }
    clearGroupFrom(player, "paste-", index);
  }

  public void showPaste(Player player, Map<Block, List<VoxelPiece>> piecesByBlock) {
    showPieces(player, "paste-", piecesByBlock, Color.ORANGE);
  }

  public void showPlacement(Player player, Block block, int minX, int minY, int minZ, int size) {
    showBox(
        player,
        "place",
        block,
        minX,
        minY,
        minZ,
        minX + size,
        minY + size,
        minZ + size,
        Color.AQUA);
  }

  public void showPlacement(Player player, Map<Block, List<VoxelPiece>> piecesByBlock) {
    showPieces(player, "place-", piecesByBlock, Color.AQUA);
  }

  public void showRemove(Player player, Block block, VoxelPiece piece) {
    if (piece == null) {
      clearGroup(player, "remove");
      return;
    }
    showBox(
        player,
        "remove",
        block,
        piece.x,
        piece.y,
        piece.z,
        piece.x + piece.size,
        piece.y + piece.size,
        piece.z + piece.size,
        Color.RED);
  }

  public void showRemove(Player player, Block block, int minX, int minY, int minZ, int size) {
    showBox(
        player,
        "remove",
        block,
        minX,
        minY,
        minZ,
        minX + size,
        minY + size,
        minZ + size,
        Color.RED);
  }

  public void showRemove(Player player, Map<Block, List<VoxelPiece>> piecesByBlock) {
    showPieces(player, "remove-", piecesByBlock, Color.RED);
  }

  public void showSelection(
      Player player, Block block, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    showBox(player, "selection", block, minX, minY, minZ, maxX, maxY, maxZ, Color.FUCHSIA);
  }

  public void showReplace(
      Player player, Block block, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    showBox(player, "replace", block, minX, minY, minZ, maxX, maxY, maxZ, Color.YELLOW);
  }

  public void showBrush(
      Player player, Block block, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    showBox(player, "brush", block, minX, minY, minZ, maxX, maxY, maxZ, Color.LIME);
  }

  public void showSet(
      Player player, Block block, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    showBox(player, "set", block, minX, minY, minZ, maxX, maxY, maxZ, Color.YELLOW);
  }

  public void showBrush(Player player, Map<Block, List<VoxelPiece>> piecesByBlock) {
    showPieces(player, "brush-", piecesByBlock, Color.LIME);
  }

  public void showSet(Player player, Map<Block, List<VoxelPiece>> piecesByBlock) {
    showPieces(player, "set-", piecesByBlock, Color.YELLOW);
  }

  public void showSelection(Player player, Map<Block, List<VoxelPiece>> piecesByBlock) {
    showPieces(player, "selection-", piecesByBlock, Color.FUCHSIA);
  }

  public void showReplace(Player player, Map<Block, List<VoxelPiece>> piecesByBlock) {
    showPieces(player, "replace-", piecesByBlock, Color.YELLOW);
  }

  public void showMove(Player player, Map<Block, List<VoxelPiece>> piecesByBlock) {
    showPieces(player, "move-", piecesByBlock, Color.BLUE);
  }

  public void showLock(Player player, Block block, boolean locked) {
    showBox(player, "lock", block, 0, 0, 0, 16, 16, 16, locked ? Color.RED : Color.GRAY);
  }

  public void clear(Player player) {
    Map<String, BlockDisplay> playerPreviews = previews.remove(player.getUniqueId().toString());
    if (playerPreviews == null) {
      return;
    }
    for (BlockDisplay display : playerPreviews.values()) {
      display.remove();
    }
  }

  public void clearLive(Player player) {
    clearGroup(player, "place");
    clearGroup(player, "paste");
    clearGroup(player, "remove");
    clearGroup(player, "replace");
    clearGroup(player, "brush");
    clearGroup(player, "set");
    clearGroup(player, "lock");
    clearGroup(player, "selection");
    clearGroup(player, "move");
  }

  public void clearAll() {
    for (Map<String, BlockDisplay> playerPreviews : previews.values()) {
      for (BlockDisplay display : playerPreviews.values()) {
        display.remove();
      }
    }
    previews.clear();
  }

  private void clearGroup(Player player, String group) {
    Map<String, BlockDisplay> playerPreviews = previews.get(player.getUniqueId().toString());
    if (playerPreviews == null) {
      return;
    }
    playerPreviews
        .entrySet()
        .removeIf(
            entry -> {
              if (!entry.getKey().startsWith(group)) {
                return false;
              }
              entry.getValue().remove();
              return true;
            });
  }

  private void clearGroupFrom(Player player, String group, int firstUnused) {
    Map<String, BlockDisplay> playerPreviews = previews.get(player.getUniqueId().toString());
    if (playerPreviews == null) {
      return;
    }
    playerPreviews
        .entrySet()
        .removeIf(
            entry -> {
              if (!entry.getKey().startsWith(group)) {
                return false;
              }
              try {
                int index = Integer.parseInt(entry.getKey().substring(group.length()));
                if (index < firstUnused) {
                  return false;
                }
              } catch (NumberFormatException ignored) {
                return false;
              }
              entry.getValue().remove();
              return true;
            });
  }

  private void showPieces(
      Player player, String group, Map<Block, List<VoxelPiece>> piecesByBlock, Color color) {
    int index = 0;
    for (Map.Entry<Block, List<VoxelPiece>> entry : piecesByBlock.entrySet()) {
      for (VoxelPiece piece : entry.getValue()) {
        if (piece.hasVisualTransform())
          showTransformedPiece(player, group + index, entry.getKey(), piece, color);
        else
          showBox(
              player,
              group + index,
              entry.getKey(),
              piece.x,
              piece.y,
              piece.z,
              piece.x + piece.size,
              piece.y + piece.size,
              piece.z + piece.size,
              color);
        index++;
      }
    }
    clearGroupFrom(player, group, index);
  }

  private void showTransformedPiece(
      Player player, String name, Block block, VoxelPiece piece, Color color) {
    String id = player.getUniqueId().toString();
    Map<String, BlockDisplay> playerPreviews =
        previews.computeIfAbsent(id, ignored -> new HashMap<>());
    BlockDisplay display = playerPreviews.get(name);
    double side = piece.size / 16.0 * piece.visualScale;
    Location location =
        block
            .getLocation()
            .add(
                (piece.x + piece.size / 2.0) / 16.0 + piece.offsetX,
                (piece.y + piece.size / 2.0) / 16.0 + piece.offsetY,
                (piece.z + piece.size / 2.0) / 16.0 + piece.offsetZ);
    if (display == null || !display.isValid() || !display.getWorld().equals(block.getWorld())) {
      if (display != null) display.remove();
      display = block.getWorld().spawn(location, BlockDisplay.class);
      display.setBlock(Material.GLASS.createBlockData());
      display.setVisibleByDefault(false);
      display.setGlowing(true);
      display.setBrightness(new Display.Brightness(15, 15));
      display.setShadowRadius(0.0f);
      display.setShadowStrength(0.0f);
      player.showEntity((Plugin) plugin, display);
      playerPreviews.put(name, display);
    }
    display.teleport(location);
    display.setGlowColorOverride(color);
    float sx = (float) (side * piece.scaleX),
        sy = (float) (side * piece.scaleY),
        sz = (float) (side * piece.scaleZ);
    display.setTransformation(
        new Transformation(
            new Vector3f(-sx / 2, -sy / 2, -sz / 2),
            piece.rotationQuaternion(),
            new Vector3f(sx, sy, sz),
            new Quaternionf()));
  }

  private void showBox(
      Player player,
      String name,
      Block block,
      int minX,
      int minY,
      int minZ,
      int maxX,
      int maxY,
      int maxZ,
      Color color) {
    if (minX < 0 || minY < 0 || minZ < 0 || maxX > 16 || maxY > 16 || maxZ > 16) {
      clearGroup(player, name);
      return;
    }

    String id = player.getUniqueId().toString();
    Map<String, BlockDisplay> playerPreviews =
        previews.computeIfAbsent(id, ignored -> new HashMap<>());
    BlockDisplay display = playerPreviews.get(name);

    Location location = block.getLocation().add(minX / 16.0, minY / 16.0, minZ / 16.0);
    if (display == null || !display.isValid() || !display.getWorld().equals(block.getWorld())) {
      if (display != null) {
        display.remove();
      }
      display = block.getWorld().spawn(location, BlockDisplay.class);
      display.setBlock(Material.GLASS.createBlockData());
      display.setVisibleByDefault(false);
      display.setGlowing(true);
      display.setGlowColorOverride(color);
      display.setBrightness(new Display.Brightness(15, 15));
      display.setShadowRadius(0.0f);
      display.setShadowStrength(0.0f);
      player.showEntity((Plugin) plugin, display);
      playerPreviews.put(name, display);
    }

    display.teleport(location);
    display.setGlowColorOverride(color);
    display.setTransformation(
        new Transformation(
            new Vector3f(0, 0, 0),
            new Quaternionf(),
            new Vector3f((maxX - minX) / 16.0f, (maxY - minY) / 16.0f, (maxZ - minZ) / 16.0f),
            new Quaternionf()));
  }
}
