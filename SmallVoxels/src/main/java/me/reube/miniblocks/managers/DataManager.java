package me.reube.SmallVoxels.managers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

public class DataManager {

  private final JavaPlugin plugin;
  private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
  private final Map<String, JsonObject> worldData = new HashMap<>();
  private final Map<String, Map<Long, Set<String>>> blocksByChunk = new HashMap<>();
  private final Map<String, List<VoxelPiece>> pieceCache = new HashMap<>();
  private final File dataFolder;

  public DataManager(JavaPlugin plugin) {
    this.plugin = plugin;
    this.dataFolder = new File(plugin.getDataFolder(), "chisel-data");

    try {
      Files.createDirectories(dataFolder.toPath());
    } catch (IOException exception) {
      plugin.getLogger().warning("Could not create voxel data folder: " + exception.getMessage());
    }
  }

  public void loadAllData() {
    worldData.clear();
    blocksByChunk.clear();
    pieceCache.clear();
    File[] worldFiles = dataFolder.listFiles();
    if (worldFiles != null) {
      for (File file : worldFiles) {
        if (file.isFile() && file.getName().endsWith(".json")) {
          try (var reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String worldName = file.getName().replace(".json", "");
            JsonObject loaded = gson.fromJson(reader, JsonObject.class);
            worldData.put(worldName, loaded == null ? new JsonObject() : loaded);
            indexWorld(worldName, worldData.get(worldName));
          } catch (IOException | RuntimeException e) {
            String worldName = file.getName().replace(".json", "");
            worldData.put(worldName, new JsonObject());
            plugin
                .getLogger()
                .warning("Failed to load voxel data for " + file.getName() + ": " + e.getMessage());
          }
        }
      }
    }
  }

  public void saveAllData() {
    for (Map.Entry<String, JsonObject> entry : worldData.entrySet()) {
      File worldFile = new File(dataFolder, entry.getKey() + ".json");
      try (var writer = Files.newBufferedWriter(worldFile.toPath(), StandardCharsets.UTF_8)) {
        gson.toJson(entry.getValue(), writer);
      } catch (IOException e) {
        plugin
            .getLogger()
            .warning("Failed to save voxel data for " + entry.getKey() + ": " + e.getMessage());
      }
    }
  }

  public void logHeavyVoxelAreas() {
    if (!plugin.getConfig().getBoolean("heavy-voxel-tracking.enabled", false)) {
      return;
    }

    int chunkThreshold =
        Math.max(1, plugin.getConfig().getInt("heavy-voxel-tracking.chunk-threshold", 400));
    int blockThreshold =
        Math.max(1, plugin.getConfig().getInt("heavy-voxel-tracking.block-threshold", 80));

    for (Map.Entry<String, JsonObject> entry : worldData.entrySet()) {
      String worldName = entry.getKey();
      JsonObject world = entry.getValue();
      Map<String, Integer> chunkCounts = new HashMap<>();

      for (String key : world.keySet()) {
        if (!key.contains(",")
            || key.endsWith("_locked")
            || key.endsWith("_stand_uuid")
            || key.endsWith("_host_type")) {
          continue;
        }

        JsonElement element = world.get(key);
        int count = countPiecesInElement(element);
        if (count <= 0) {
          continue;
        }

        if (count >= blockThreshold) {
          plugin
              .getLogger()
              .warning("Heavy voxel block " + worldName + " " + key + " has " + count + " pieces.");
        }

        String[] parts = key.split(",");
        if (parts.length != 3) {
          continue;
        }
        try {
          int chunkX = Math.floorDiv(Integer.parseInt(parts[0]), 16);
          int chunkZ = Math.floorDiv(Integer.parseInt(parts[2]), 16);
          String chunkKey = chunkX + "," + chunkZ;
          chunkCounts.put(chunkKey, chunkCounts.getOrDefault(chunkKey, 0) + count);
        } catch (NumberFormatException ignored) {
        }
      }

      for (Map.Entry<String, Integer> chunk : chunkCounts.entrySet()) {
        if (chunk.getValue() >= chunkThreshold) {
          plugin
              .getLogger()
              .warning(
                  "Heavy voxel chunk "
                      + worldName
                      + " chunk "
                      + chunk.getKey()
                      + " has "
                      + chunk.getValue()
                      + " pieces.");
        }
      }
    }
  }

  public List<Block> getSavedVoxelBlocks() {
    List<Block> blocks = new ArrayList<>();
    for (Map.Entry<String, JsonObject> entry : worldData.entrySet()) {
      World world = Bukkit.getWorld(entry.getKey());
      if (world == null) {
        continue;
      }

      for (String key : entry.getValue().keySet()) {
        if (!key.contains(",")
            || key.endsWith("_locked")
            || key.endsWith("_stand_uuid")
            || key.endsWith("_host_type")) {
          continue;
        }

        String[] parts = key.split(",");
        if (parts.length != 3) {
          continue;
        }

        try {
          int x = Integer.parseInt(parts[0]);
          int y = Integer.parseInt(parts[1]);
          int z = Integer.parseInt(parts[2]);
          if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            continue;
          }
          if (!world.isChunkLoaded(Math.floorDiv(x, 16), Math.floorDiv(z, 16))) {
            continue;
          }
          blocks.add(world.getBlockAt(x, y, z));
        } catch (NumberFormatException ignored) {
        }
      }
    }
    return blocks;
  }

  public List<Block> getSavedVoxelBlocks(World world) {
    List<Block> blocks = new ArrayList<>();
    if (world == null) {
      return blocks;
    }

    JsonObject data = worldData.get(world.getName());
    if (data == null) {
      return blocks;
    }

    for (String key : data.keySet()) {
      if (!key.contains(",")
          || key.endsWith("_locked")
          || key.endsWith("_stand_uuid")
          || key.endsWith("_host_type")) {
        continue;
      }

      String[] parts = key.split(",");
      if (parts.length != 3) {
        continue;
      }

      try {
        int x = Integer.parseInt(parts[0]);
        int y = Integer.parseInt(parts[1]);
        int z = Integer.parseInt(parts[2]);
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
          continue;
        }
        blocks.add(world.getBlockAt(x, y, z));
      } catch (NumberFormatException ignored) {
      }
    }
    return blocks;
  }

  public List<Block> getSavedVoxelBlocks(World world, int chunkX, int chunkZ) {
    List<Block> blocks = new ArrayList<>();
    if (world == null) {
      return blocks;
    }
    Set<String> keys =
        blocksByChunk.getOrDefault(world.getName(), Map.of()).get(chunkKey(chunkX, chunkZ));
    if (keys == null || keys.isEmpty()) {
      return blocks;
    }
    for (String key : keys) {
      String[] parts = key.split(",");
      if (parts.length != 3) {
        continue;
      }
      try {
        int x = Integer.parseInt(parts[0]);
        int y = Integer.parseInt(parts[1]);
        int z = Integer.parseInt(parts[2]);
        if (Math.floorDiv(x, 16) != chunkX
            || Math.floorDiv(z, 16) != chunkZ
            || y < world.getMinHeight()
            || y >= world.getMaxHeight()) {
          continue;
        }
        blocks.add(world.getBlockAt(x, y, z));
      } catch (NumberFormatException ignored) {
      }
    }
    return blocks;
  }

  private int countPiecesInElement(JsonElement element) {
    if (element == null || element.isJsonNull()) {
      return 0;
    }
    if (element.isJsonObject()) {
      JsonObject obj = element.getAsJsonObject();
      if (obj.has("pieces") && obj.get("pieces").isJsonArray()) {
        return obj.getAsJsonArray("pieces").size();
      }
    }
    if (element.isJsonArray()) {
      int count = 0;
      for (JsonElement value : element.getAsJsonArray()) {
        if (!value.isJsonNull() && !"air".equalsIgnoreCase(value.getAsString())) {
          count++;
        }
      }
      return count;
    }
    return 0;
  }

  public String getBlockKey(Block block) {
    return block.getX() + "," + block.getY() + "," + block.getZ();
  }

  public JsonArray getCarvedBlockData(Block block) {
    String worldName = block.getWorld().getName();
    String blockKey = getBlockKey(block);

    worldData.putIfAbsent(worldName, new JsonObject());
    JsonObject world = worldData.get(worldName);

    if (!world.has(blockKey)) {
      JsonArray blocks = new JsonArray();
      for (int i = 0; i < 64; i++) {
        blocks.add("air");
      }
      world.add(blockKey, blocks);
    }

    JsonElement blockData = world.get(blockKey);

    if (blockData.isJsonArray()) {
      return blockData.getAsJsonArray();
    }

    if (blockData.isJsonObject()) {
      JsonObject obj = blockData.getAsJsonObject();

      if (obj.has("pieces")) {
        JsonArray fakeOldArray = new JsonArray();

        for (int i = 0; i < 64; i++) {
          fakeOldArray.add("air");
        }

        JsonArray pieces = obj.getAsJsonArray("pieces");

        for (JsonElement element : pieces) {
          JsonObject piece = element.getAsJsonObject();

          int x = piece.get("x").getAsInt();
          int y = piece.get("y").getAsInt();
          int z = piece.get("z").getAsInt();
          String material = piece.get("material").getAsString();

          int slotX = Math.min(3, Math.max(0, x / 4));
          int slotY = Math.min(3, Math.max(0, y / 4));
          int slotZ = Math.min(3, Math.max(0, z / 4));

          int index = slotX + (slotY * 4) + (slotZ * 16);
          fakeOldArray.set(index, new com.google.gson.JsonPrimitive(material));
        }

        return fakeOldArray;
      }
    }

    JsonArray fallback = new JsonArray();
    for (int i = 0; i < 64; i++) {
      fallback.add("air");
    }
    return fallback;
  }

  public void setCarvedBlockData(Block block, JsonArray data) {
    String worldName = block.getWorld().getName();
    String blockKey = getBlockKey(block);

    worldData.putIfAbsent(worldName, new JsonObject());
    worldData.get(worldName).add(blockKey, data);
    pieceCache.remove(cacheKey(worldName, blockKey));
    indexBlock(worldName, blockKey, block.getX(), block.getZ());
  }

  public void removeCarvedBlockData(Block block) {
    String worldName = block.getWorld().getName();
    String blockKey = getBlockKey(block);

    if (worldData.containsKey(worldName)) {
      worldData.get(worldName).remove(blockKey);
      unindexBlock(worldName, blockKey, block.getX(), block.getZ());
      pieceCache.remove(cacheKey(worldName, blockKey));
    }
  }

  public void removeCarvedBlockAndMetadata(Block block) {
    String worldName = block.getWorld().getName();
    String blockKey = getBlockKey(block);

    if (worldData.containsKey(worldName)) {
      JsonObject world = worldData.get(worldName);
      world.remove(blockKey);
      world.remove(blockKey + "_locked");
      world.remove(blockKey + "_stand_uuid");
      world.remove(blockKey + "_host_type");
      world.remove(blockKey + "_click_command");
      unindexBlock(worldName, blockKey, block.getX(), block.getZ());
      pieceCache.remove(cacheKey(worldName, blockKey));
    }
  }

  public String getClickCommand(Block block) {
    return getMetadataString(block, "_click_command");
  }

  public void setClickCommand(Block block, String command) {
    setMetadataString(block, "_click_command", command);
  }

  public void clearClickCommand(Block block) {
    setMetadataString(block, "_click_command", null);
  }

  public String getPieceClickCommand(Block block, int x, int y, int z, int size) {
    return getMetadataString(block, pieceClickSuffix(x, y, z, size));
  }

  public void setPieceClickCommand(Block block, int x, int y, int z, int size, String command) {
    setMetadataString(block, pieceClickSuffix(x, y, z, size), command);
  }

  public void clearPieceClickCommand(Block block, int x, int y, int z, int size) {
    setMetadataString(block, pieceClickSuffix(x, y, z, size), null);
  }

  private String getMetadataString(Block block, String suffix) {
    String worldName = block.getWorld().getName();
    String key = getBlockKey(block) + suffix;
    if (!worldData.containsKey(worldName)) {
      return null;
    }
    JsonObject world = worldData.get(worldName);
    return world.has(key) ? world.get(key).getAsString() : null;
  }

  private void setMetadataString(Block block, String suffix, String value) {
    String worldName = block.getWorld().getName();
    String key = getBlockKey(block) + suffix;
    worldData.putIfAbsent(worldName, new JsonObject());
    JsonObject world = worldData.get(worldName);
    if (value == null || value.isBlank()) {
      world.remove(key);
    } else {
      world.addProperty(key, value);
    }
  }

  private String pieceClickSuffix(int x, int y, int z, int size) {
    return "_click_" + x + "_" + y + "_" + z + "_" + size;
  }

  public String getOriginalBlockType(JsonArray carvedData) {
    // Converted blocks initially use the same material in every slot.
    if (carvedData != null && carvedData.size() > 0) {
      String firstVoxel = carvedData.get(0).getAsString();
      if (!firstVoxel.equals("air") && isSolidMaterial(firstVoxel)) {
        return firstVoxel;
      }
    }
    return "STONE";
  }

  private boolean isSolidMaterial(String materialName) {
    try {
      org.bukkit.Material mat = org.bukkit.Material.valueOf(materialName);
      return mat.isBlock() && !mat.isAir() && mat.isSolid();
    } catch (Exception e) {
      return false;
    }
  }

  public boolean isBlockLocked(Block block) {
    String worldName = block.getWorld().getName();
    String blockKey = getBlockKey(block);
    String lockKey = blockKey + "_locked";

    if (!worldData.containsKey(worldName)) return false;

    JsonObject world = worldData.get(worldName);
    if (!world.has(lockKey)) return false;

    return world.get(lockKey).getAsBoolean();
  }

  public void setBlockLocked(Block block, boolean locked) {
    String worldName = block.getWorld().getName();
    String blockKey = getBlockKey(block);
    String lockKey = blockKey + "_locked";

    worldData.putIfAbsent(worldName, new JsonObject());
    JsonObject world = worldData.get(worldName);
    world.addProperty(lockKey, locked);
  }

  public void toggleBlockLocked(Block block) {
    boolean currentState = isBlockLocked(block);
    setBlockLocked(block, !currentState);
  }

  public boolean hasCarvedData(Block block) {
    String worldName = block.getWorld().getName();
    String blockKey = getBlockKey(block);

    if (!worldData.containsKey(worldName)) return false;

    JsonObject world = worldData.get(worldName);
    return world.has(blockKey);
  }

  public String getCollisionStandUUID(Block block) {
    String worldName = block.getWorld().getName();
    String blockKey = getBlockKey(block);
    String standKey = blockKey + "_stand_uuid";

    if (!worldData.containsKey(worldName)) return null;

    JsonObject world = worldData.get(worldName);
    if (!world.has(standKey)) return null;

    return world.get(standKey).getAsString();
  }

  public void setCollisionStandUUID(Block block, String standUUID) {
    String worldName = block.getWorld().getName();
    String blockKey = getBlockKey(block);
    String standKey = blockKey + "_stand_uuid";

    worldData.putIfAbsent(worldName, new JsonObject());
    JsonObject world = worldData.get(worldName);

    if (standUUID == null || standUUID.isEmpty()) {
      world.remove(standKey);
    } else {
      world.addProperty(standKey, standUUID);
    }
  }

  public boolean isGravityAffected(org.bukkit.Material material) {
    return material == org.bukkit.Material.SAND
        || material == org.bukkit.Material.RED_SAND
        || material == org.bukkit.Material.GRAVEL
        || material == org.bukkit.Material.SUSPICIOUS_SAND
        || material == org.bukkit.Material.SUSPICIOUS_GRAVEL;
  }

  public List<VoxelPiece> getVoxelPieces(Block block) {
    String worldName = block.getWorld().getName();
    String blockKey = getBlockKey(block);

    worldData.putIfAbsent(worldName, new JsonObject());
    JsonObject world = worldData.get(worldName);

    List<VoxelPiece> cached = pieceCache.get(cacheKey(worldName, blockKey));
    if (cached != null) {
      return copyPieces(cached);
    }

    if (!world.has(blockKey)) {
      return new ArrayList<>();
    }

    JsonElement blockData = world.get(blockKey);

    if (blockData.isJsonObject()) {
      JsonObject obj = blockData.getAsJsonObject();
      if (obj.has("version") && obj.get("version").getAsInt() >= 2 && obj.has("pieces")) {
        List<VoxelPiece> pieces = deserializePieces(obj.getAsJsonArray("pieces"));
        pieceCache.put(cacheKey(worldName, blockKey), copyPieces(pieces));
        return pieces;
      }
    }

    // Older 4x4 slot data is migrated the first time it is read.
    if (blockData.isJsonArray()) {
      JsonArray slotArray = blockData.getAsJsonArray();
      String[] slots = new String[slotArray.size()];
      for (int i = 0; i < slotArray.size(); i++) {
        slots[i] = slotArray.get(i).getAsString();
      }
      List<VoxelPiece> pieces = VoxelPieceManager.convertFromSlotArray(slots);

      if (!pieces.isEmpty()) {
        setVoxelPieces(block, pieces);
      }

      return pieces;
    }

    return new ArrayList<>();
  }

  public void setVoxelPieces(Block block, List<VoxelPiece> pieces) {
    String worldName = block.getWorld().getName();
    String blockKey = getBlockKey(block);

    worldData.putIfAbsent(worldName, new JsonObject());
    JsonObject world = worldData.get(worldName);

    JsonObject blockData = new JsonObject();
    blockData.addProperty("version", 2);
    blockData.add("pieces", serializePieces(pieces));

    world.add(blockKey, blockData);
    pieceCache.put(cacheKey(worldName, blockKey), copyPieces(pieces));
    indexBlock(worldName, blockKey, block.getX(), block.getZ());
  }

  private void indexWorld(String worldName, JsonObject data) {
    for (String key : data.keySet()) {
      int[] position = parseBlockKey(key);
      if (position != null) {
        indexBlock(worldName, key, position[0], position[2]);
      }
    }
  }

  private void indexBlock(String worldName, String blockKey, int x, int z) {
    blocksByChunk
        .computeIfAbsent(worldName, ignored -> new HashMap<>())
        .computeIfAbsent(
            chunkKey(Math.floorDiv(x, 16), Math.floorDiv(z, 16)), ignored -> new HashSet<>())
        .add(blockKey);
  }

  private void unindexBlock(String worldName, String blockKey, int x, int z) {
    Map<Long, Set<String>> worldIndex = blocksByChunk.get(worldName);
    if (worldIndex == null) return;
    long chunk = chunkKey(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
    Set<String> keys = worldIndex.get(chunk);
    if (keys == null) return;
    keys.remove(blockKey);
    if (keys.isEmpty()) worldIndex.remove(chunk);
  }

  private long chunkKey(int x, int z) {
    return ((long) x << 32) ^ (z & 0xffffffffL);
  }

  private int[] parseBlockKey(String key) {
    if (key.indexOf('_') >= 0) return null;
    String[] parts = key.split(",", -1);
    if (parts.length != 3) return null;
    try {
      return new int[] {
        Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])
      };
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private String cacheKey(String worldName, String blockKey) {
    return worldName + '\0' + blockKey;
  }

  private List<VoxelPiece> copyPieces(List<VoxelPiece> pieces) {
    List<VoxelPiece> copy = new ArrayList<>(pieces.size());
    for (VoxelPiece piece : pieces) copy.add(piece.copy());
    return copy;
  }

  private JsonArray serializePieces(List<VoxelPiece> pieces) {
    JsonArray array = new JsonArray();
    for (VoxelPiece piece : pieces) {
      JsonObject obj = new JsonObject();
      obj.addProperty("x", piece.x);
      obj.addProperty("y", piece.y);
      obj.addProperty("z", piece.z);
      obj.addProperty("size", piece.size);
      obj.addProperty("material", piece.material);
      if (piece.blockData != null && !piece.blockData.isEmpty()) {
        obj.addProperty("blockData", piece.blockData);
      }
      if (piece.hasVisualTransform()) {
        obj.addProperty("offsetX", piece.offsetX);
        obj.addProperty("offsetY", piece.offsetY);
        obj.addProperty("offsetZ", piece.offsetZ);
        obj.addProperty("rotationX", piece.rotationX);
        obj.addProperty("rotationY", piece.rotationY);
        obj.addProperty("rotationZ", piece.rotationZ);
        obj.addProperty("quaternionX", piece.quaternionX);
        obj.addProperty("quaternionY", piece.quaternionY);
        obj.addProperty("quaternionZ", piece.quaternionZ);
        obj.addProperty("quaternionW", piece.quaternionW);
        obj.addProperty("visualScale", piece.visualScale);
        obj.addProperty("scaleX", piece.scaleX);
        obj.addProperty("scaleY", piece.scaleY);
        obj.addProperty("scaleZ", piece.scaleZ);
        if (piece.transformGroup != null) obj.addProperty("transformGroup", piece.transformGroup);
      }
      array.add(obj);
    }
    return array;
  }

  private List<VoxelPiece> deserializePieces(JsonArray array) {
    List<VoxelPiece> pieces = new ArrayList<>();
    for (JsonElement element : array) {
      if (element.isJsonObject()) {
        JsonObject obj = element.getAsJsonObject();
        int x = obj.get("x").getAsInt();
        int y = obj.get("y").getAsInt();
        int z = obj.get("z").getAsInt();
        int size = obj.get("size").getAsInt();
        String material = obj.get("material").getAsString();
        String blockData = obj.has("blockData") ? obj.get("blockData").getAsString() : null;
        VoxelPiece piece = new VoxelPiece(x, y, z, size, material, blockData);
        piece.offsetX = obj.has("offsetX") ? obj.get("offsetX").getAsDouble() : 0.0;
        piece.offsetY = obj.has("offsetY") ? obj.get("offsetY").getAsDouble() : 0.0;
        piece.offsetZ = obj.has("offsetZ") ? obj.get("offsetZ").getAsDouble() : 0.0;
        piece.rotationX = obj.has("rotationX") ? obj.get("rotationX").getAsFloat() : 0.0f;
        piece.rotationY = obj.has("rotationY") ? obj.get("rotationY").getAsFloat() : 0.0f;
        piece.rotationZ = obj.has("rotationZ") ? obj.get("rotationZ").getAsFloat() : 0.0f;
        piece.quaternionX = obj.has("quaternionX") ? obj.get("quaternionX").getAsFloat() : 0.0f;
        piece.quaternionY = obj.has("quaternionY") ? obj.get("quaternionY").getAsFloat() : 0.0f;
        piece.quaternionZ = obj.has("quaternionZ") ? obj.get("quaternionZ").getAsFloat() : 0.0f;
        piece.quaternionW = obj.has("quaternionW") ? obj.get("quaternionW").getAsFloat() : 1.0f;
        piece.visualScale = obj.has("visualScale") ? obj.get("visualScale").getAsDouble() : 1.0;
        piece.scaleX = obj.has("scaleX") ? obj.get("scaleX").getAsDouble() : 1.0;
        piece.scaleY = obj.has("scaleY") ? obj.get("scaleY").getAsDouble() : 1.0;
        piece.scaleZ = obj.has("scaleZ") ? obj.get("scaleZ").getAsDouble() : 1.0;
        piece.transformGroup =
            obj.has("transformGroup") ? obj.get("transformGroup").getAsString() : null;
        pieces.add(piece);
      }
    }
    return pieces;
  }

  public boolean hasVoxelPieces(Block block) {
    return !getVoxelPieces(block).isEmpty();
  }

  public int countVoxelPieces(Block block) {
    return getVoxelPieces(block).size();
  }
}
