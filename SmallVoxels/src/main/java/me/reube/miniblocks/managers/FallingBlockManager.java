package me.reube.SmallVoxels.managers;

import com.google.gson.JsonArray;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.reube.SmallVoxels.SmallVoxels;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class FallingBlockManager {

  private static final String ITEM_DATA_PREFIX = "smallvoxels:item:";
  private final SmallVoxels plugin;
  private final DataManager dataManager;
  private final Map<HostKey, Set<Entity>> blockEntities = new HashMap<>();
  private final Map<HostKey, Integer> renderedSignatures = new HashMap<>();
  private final Map<String, BlockData> blockDataCache = new HashMap<>();
  private final Map<String, ItemStack> itemCache = new HashMap<>();
  private final NamespacedKey pieceXKey;
  private final NamespacedKey pieceYKey;
  private final NamespacedKey pieceZKey;
  private final NamespacedKey pieceSizeKey;
  private final NamespacedKey hostWorldKey;
  private final NamespacedKey hostXKey;
  private final NamespacedKey hostYKey;
  private final NamespacedKey hostZKey;

  public FallingBlockManager(SmallVoxels plugin) {
    this.plugin = plugin;
    this.dataManager = plugin.getDataManager();
    this.pieceXKey = new NamespacedKey(plugin, "piece_x");
    this.pieceYKey = new NamespacedKey(plugin, "piece_y");
    this.pieceZKey = new NamespacedKey(plugin, "piece_z");
    this.pieceSizeKey = new NamespacedKey(plugin, "piece_size");
    this.hostWorldKey = new NamespacedKey(plugin, "host_world");
    this.hostXKey = new NamespacedKey(plugin, "host_x");
    this.hostYKey = new NamespacedKey(plugin, "host_y");
    this.hostZKey = new NamespacedKey(plugin, "host_z");
  }

  public void updateBlockDisplay(Block block, JsonArray voxelData) {
    long started = System.nanoTime();
    try {
      updateBlockDisplayMeasured(block, voxelData);
    } finally {
      plugin
          .getPerformanceMonitor()
          .record(
              "voxel rendering",
              started,
              block.getWorld().getName()
                  + " "
                  + block.getX()
                  + ","
                  + block.getY()
                  + ","
                  + block.getZ());
    }
  }

  private void updateBlockDisplayMeasured(Block block, JsonArray voxelData) {
    List<VoxelPiece> pieces = dataManager.getVoxelPieces(block);
    HostKey hostKey = HostKey.of(block);
    int signature = renderSignature(pieces, voxelData);
    Set<Entity> existing = blockEntities.get(hostKey);
    if (renderedSignatures.getOrDefault(hostKey, Integer.MIN_VALUE) == signature
        && existing != null
        && existing.stream().allMatch(Entity::isValid)) {
      return;
    }
    removeBlockDisplay(block);

    if (pieces != null && !pieces.isEmpty()) {
      updateBlockDisplayFromPieces(block, pieces);
    } else if (voxelData != null && voxelData.size() > 0) {
      for (int i = 0; i < voxelData.size(); i++) {
        if (voxelData.get(i).isJsonNull()) {
          continue;
        }

        String materialText = voxelData.get(i).getAsString();
        if (!materialText.equals("air")) {
          Material material = VoxelManager.getMaterialFromString(materialText);
          int[] coords = VoxelManager.getVoxelCoords(i);
          spawnVoxelEntity(block, coords, material, 4, null);
        }
      }
    }
    renderedSignatures.put(hostKey, signature);
  }

  private int renderSignature(List<VoxelPiece> pieces, JsonArray legacyData) {
    int hash = plugin.getConfig().getBoolean("rendering.cull-hidden-voxels", true) ? 1 : 0;
    for (VoxelPiece piece : pieces) {
      hash = 31 * hash + piece.x;
      hash = 31 * hash + piece.y;
      hash = 31 * hash + piece.z;
      hash = 31 * hash + piece.size;
      hash = 31 * hash + java.util.Objects.hashCode(piece.material);
      hash = 31 * hash + java.util.Objects.hashCode(piece.blockData);
      hash =
          31 * hash
              + java.util.Objects.hash(
                  piece.offsetX,
                  piece.offsetY,
                  piece.offsetZ,
                  piece.rotationX,
                  piece.rotationY,
                  piece.rotationZ,
                  piece.visualScale);
      hash =
          31 * hash
              + java.util.Objects.hash(
                  piece.quaternionX, piece.quaternionY, piece.quaternionZ, piece.quaternionW);
      hash = 31 * hash + java.util.Objects.hash(piece.scaleX, piece.scaleY, piece.scaleZ);
    }
    if (pieces.isEmpty() && legacyData != null) hash = 31 * hash + legacyData.hashCode();
    return hash;
  }

  private void updateBlockDisplayFromPieces(Block block, List<VoxelPiece> pieces) {
    boolean cullHidden = plugin.getConfig().getBoolean("rendering.cull-hidden-voxels", true);
    boolean[][][] occluding = cullHidden ? buildOcclusionMap(pieces) : null;
    for (VoxelPiece piece : pieces) {
      if (!piece.isValid()) {
        plugin.getLogger().warning("Invalid piece: " + piece);
        continue;
      }
      // Rotated pieces are not axis-aligned, so the grid occlusion map cannot safely hide them.
      if (cullHidden && !piece.hasVisualTransform() && !hasExposedFace(piece, occluding)) {
        continue;
      }

      int[] coords = {piece.x, piece.y, piece.z};
      spawnVoxelEntity(block, coords, piece.getAsMaterial(), piece.size, piece.blockData, piece);
    }
  }

  private boolean[][][] buildOcclusionMap(List<VoxelPiece> pieces) {
    boolean[][][] occluding = new boolean[16][16][16];
    for (VoxelPiece piece : pieces) {
      if (!piece.isValid() || !piece.getAsMaterial().isOccluding()) {
        continue;
      }
      for (int x = piece.x; x < piece.x + piece.size; x++) {
        for (int y = piece.y; y < piece.y + piece.size; y++) {
          for (int z = piece.z; z < piece.z + piece.size; z++) {
            occluding[x][y][z] = true;
          }
        }
      }
    }
    return occluding;
  }

  private boolean hasExposedFace(VoxelPiece piece, boolean[][][] occluding) {
    int minX = piece.x;
    int minY = piece.y;
    int minZ = piece.z;
    int maxX = piece.x + piece.size - 1;
    int maxY = piece.y + piece.size - 1;
    int maxZ = piece.z + piece.size - 1;

    for (int y = minY; y <= maxY; y++) {
      for (int z = minZ; z <= maxZ; z++) {
        if (!isOccluding(occluding, minX - 1, y, z) || !isOccluding(occluding, maxX + 1, y, z))
          return true;
      }
    }
    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        if (!isOccluding(occluding, x, minY - 1, z) || !isOccluding(occluding, x, maxY + 1, z))
          return true;
      }
    }
    for (int x = minX; x <= maxX; x++) {
      for (int y = minY; y <= maxY; y++) {
        if (!isOccluding(occluding, x, y, minZ - 1) || !isOccluding(occluding, x, y, maxZ + 1))
          return true;
      }
    }
    return false;
  }

  private boolean isOccluding(boolean[][][] occluding, int x, int y, int z) {
    if (x < 0 || x >= 16 || y < 0 || y >= 16 || z < 0 || z >= 16) {
      return false;
    }
    return occluding[x][y][z];
  }

  private void spawnVoxelEntity(
      Block block, int[] coords, Material material, int voxelSize, String blockDataText) {
    spawnVoxelEntity(block, coords, material, voxelSize, blockDataText, null);
  }

  private void spawnVoxelEntity(
      Block block,
      int[] coords,
      Material material,
      int voxelSize,
      String blockDataText,
      VoxelPiece piece) {
    try {
      double voxelUnitSize = voxelSize / 16.0;
      Location voxelLoc =
          block.getLocation().clone().add(coords[0] / 16.0, coords[1] / 16.0, coords[2] / 16.0);
      if (piece != null && piece.hasVisualTransform()) {
        double half = voxelUnitSize / 2.0;
        voxelLoc.add(half + piece.offsetX, half + piece.offsetY, half + piece.offsetZ);
      }

      if (isItemVoxel(blockDataText)) {
        spawnItemVoxel(block, voxelLoc, voxelUnitSize, blockDataText);
        return;
      }

      BlockDisplay display = block.getWorld().spawn(voxelLoc, BlockDisplay.class);
      if (display == null) {
        plugin.getLogger().warning("Failed to spawn voxel display");
        return;
      }

      String cacheKey =
          blockDataText == null || blockDataText.isEmpty()
              ? "material:" + material.name()
              : "data:" + blockDataText;
      BlockData blockData =
          blockDataCache
              .computeIfAbsent(
                  cacheKey,
                  ignored -> {
                    if (blockDataText != null && !blockDataText.isEmpty())
                      return org.bukkit.Bukkit.createBlockData(blockDataText);
                    if (VoxelManager.isMultiPartBlock(material))
                      return VoxelManager.getDefaultBlockData(material);
                    return material.createBlockData();
                  })
              .clone();

      display.setBlock(blockData);
      tagPiece(display, block, coords, voxelSize);
      display.setPersistent(false);
      display.setNoPhysics(true);
      display.setViewRange((float) plugin.getConfig().getDouble("rendering.view-range", 1.0));
      display.setShadowRadius(0.0f);
      display.setShadowStrength(0.0f);

      float scale = (float) (voxelUnitSize * (piece == null ? 1.0 : piece.visualScale));
      float sx = (float) (scale * (piece == null ? 1.0 : piece.scaleX));
      float sy = (float) (scale * (piece == null ? 1.0 : piece.scaleY));
      float sz = (float) (scale * (piece == null ? 1.0 : piece.scaleZ));
      boolean transformed = piece != null && piece.hasVisualTransform();
      Quaternionf rotation = transformed ? piece.rotationQuaternion() : new Quaternionf();
      display.setTransformation(
          new Transformation(
              transformed ? new Vector3f(-sx / 2.0f, -sy / 2.0f, -sz / 2.0f) : new Vector3f(),
              rotation,
              new Vector3f(sx, sy, sz),
              new Quaternionf()));

      blockEntities.computeIfAbsent(HostKey.of(block), ignored -> new HashSet<>()).add(display);
    } catch (Exception e) {
      plugin.getLogger().warning("Failed to spawn voxel: " + e.getMessage());
    }
  }

  private void spawnItemVoxel(
      Block block, Location voxelLoc, double voxelUnitSize, String itemDataText) {
    ItemStack item = itemFromData(itemDataText);
    if (item == null || item.getType() == Material.AIR) {
      return;
    }

    ItemDisplay display = block.getWorld().spawn(voxelLoc, ItemDisplay.class);
    int[] coords = {
      (int) Math.round((voxelLoc.getX() - block.getX()) * 16.0),
      (int) Math.round((voxelLoc.getY() - block.getY()) * 16.0),
      (int) Math.round((voxelLoc.getZ() - block.getZ()) * 16.0)
    };
    tagPiece(display, block, coords, (int) Math.round(voxelUnitSize * 16.0));
    display.setPersistent(false);
    display.setViewRange((float) plugin.getConfig().getDouble("rendering.view-range", 1.0));
    display.setItemStack(item);
    display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
    display.setShadowRadius(0.0f);
    display.setShadowStrength(0.0f);

    float scale = (float) (voxelUnitSize * 2.0);
    display.setTransformation(
        new Transformation(
            new Vector3f(0, 0, 0),
            new Quaternionf(0, 0, 0, 1),
            new Vector3f(scale, scale, scale),
            new Quaternionf()));

    blockEntities.computeIfAbsent(HostKey.of(block), ignored -> new HashSet<>()).add(display);
  }

  private void tagPiece(Entity entity, Block block, int[] coords, int voxelSize) {
    entity.getPersistentDataContainer().set(pieceXKey, PersistentDataType.INTEGER, coords[0]);
    entity.getPersistentDataContainer().set(pieceYKey, PersistentDataType.INTEGER, coords[1]);
    entity.getPersistentDataContainer().set(pieceZKey, PersistentDataType.INTEGER, coords[2]);
    entity.getPersistentDataContainer().set(pieceSizeKey, PersistentDataType.INTEGER, voxelSize);
    entity
        .getPersistentDataContainer()
        .set(hostWorldKey, PersistentDataType.STRING, block.getWorld().getName());
    entity.getPersistentDataContainer().set(hostXKey, PersistentDataType.INTEGER, block.getX());
    entity.getPersistentDataContainer().set(hostYKey, PersistentDataType.INTEGER, block.getY());
    entity.getPersistentDataContainer().set(hostZKey, PersistentDataType.INTEGER, block.getZ());
  }

  private boolean isItemVoxel(String blockDataText) {
    return blockDataText != null && blockDataText.startsWith(ITEM_DATA_PREFIX);
  }

  private ItemStack itemFromData(String blockDataText) {
    ItemStack cached = itemCache.get(blockDataText);
    if (cached != null) return cached.clone();
    try {
      String encoded = blockDataText.substring(ITEM_DATA_PREFIX.length());
      String yaml = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
      YamlConfiguration config = new YamlConfiguration();
      config.loadFromString(yaml);
      ItemStack item = config.getItemStack("item");
      if (item != null) itemCache.put(blockDataText, item.clone());
      return item;
    } catch (Exception ex) {
      plugin.getLogger().warning("Failed to load custom voxel item: " + ex.getMessage());
      return null;
    }
  }

  public void removeBlockDisplay(Block block) {
    HostKey key = HostKey.of(block);
    Set<Entity> entities = blockEntities.get(key);
    if (entities != null) {
      for (Entity entity : entities) {
        if (entity.isValid()) {
          entity.remove();
        }
      }
      blockEntities.remove(key);
    }
    renderedSignatures.remove(key);
  }

  public void dropBlockItems(Block block, List<VoxelPiece> pieces) {
    World world = block.getWorld();
    Location dropLoc = block.getLocation().add(0.5, 0.5, 0.5);

    Set<String> blockTypes = new HashSet<>();
    Set<String> customItems = new HashSet<>();
    if (pieces != null) {
      for (VoxelPiece piece : pieces) {
        if (isItemVoxel(piece.blockData)) {
          customItems.add(piece.blockData);
        } else {
          blockTypes.add(piece.material);
        }
      }
    }

    for (String itemData : customItems) {
      ItemStack item = itemFromData(itemData);
      if (item != null && item.getType() != Material.AIR) {
        item.setAmount(1);
        world.dropItemNaturally(dropLoc, item);
      }
    }

    for (String materialText : blockTypes) {
      Material material = VoxelManager.getMaterialFromString(materialText);
      if (material != Material.AIR) {
        world.dropItemNaturally(dropLoc, new ItemStack(material, 1));
      }
    }
  }

  public void clearAllEntities() {
    for (Set<Entity> entities : blockEntities.values()) {
      for (Entity entity : entities) {
        if (entity.isValid()) {
          entity.remove();
        }
      }
    }
    blockEntities.clear();
    renderedSignatures.clear();
  }

  public int removePersistedVoxelEntities() {
    int removed = 0;
    for (World world : plugin.getServer().getWorlds()) {
      for (Chunk chunk : world.getLoadedChunks()) {
        removed += removePersistedVoxelEntities(chunk);
      }
    }
    blockEntities.clear();
    renderedSignatures.clear();
    return removed;
  }

  public int removePersistedVoxelEntities(Chunk chunk) {
    int removed = 0;
    for (Entity entity : chunk.getEntities()) {
      boolean voxelDisplay =
          entity instanceof Display
              && entity.getPersistentDataContainer().has(pieceXKey, PersistentDataType.INTEGER);
      if (voxelDisplay || CollisionStandManager.isCollisionStand(entity)) {
        entity.remove();
        removed++;
      }
    }
    blockEntities
        .entrySet()
        .removeIf(
            entry -> {
              entry.getValue().removeIf(entity -> !entity.isValid());
              return entry.getValue().isEmpty();
            });
    return removed;
  }

  private record HostKey(java.util.UUID world, int x, int y, int z) {
    private static HostKey of(Block block) {
      return new HostKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }
  }
}
