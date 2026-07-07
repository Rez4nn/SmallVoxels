package me.reube.SmallVoxels.managers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.reube.SmallVoxels.SmallVoxels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class SurvivalVoxelManager {
  private final SmallVoxels plugin;
  private final NamespacedKey materialKey;
  private final NamespacedKey sizeKey;
  private final NamespacedKey blockDataKey;

  public SurvivalVoxelManager(SmallVoxels plugin) {
    this.plugin = plugin;
    this.materialKey = new NamespacedKey(plugin, "survival_voxel_material");
    this.sizeKey = new NamespacedKey(plugin, "survival_voxel_size");
    this.blockDataKey = new NamespacedKey(plugin, "survival_voxel_block_data");
  }

  public boolean isMiningIndividualEnabled(World world) {
    return worldSetting(world, "mining-individual");
  }

  public boolean isCraftingEnabled(World world) {
    return worldSetting(world, "crafting");
  }

  public boolean isCraftingTableEnabled(World world, InventoryView view) {
    return stationEnabled(world, view, "crafting-table", isCraftingEnabled(world));
  }

  public boolean isStonecutterEnabled(World world, InventoryView view) {
    return stationEnabled(world, view, "stonecutter", isCraftingEnabled(world));
  }

  private boolean worldSetting(World world, String key) {
    String worldPath = "worlds_name." + world.getName() + ".survival." + key;
    String legacyWorldPath = "worlds." + world.getName() + ".survival." + key;
    String defaultPath = "defaults.survival." + key;
    return plugin
        .getConfig()
        .getBoolean(
            worldPath,
            plugin
                .getConfig()
                .getBoolean(legacyWorldPath, plugin.getConfig().getBoolean(defaultPath, false)));
  }

  private boolean stationEnabled(
      World world, InventoryView view, String station, boolean legacyFallback) {
    String worldBase = "worlds_name." + world.getName() + ".survival." + station;
    String legacyWorldBase = "worlds." + world.getName() + ".survival." + station;
    String defaultBase = "defaults.survival." + station;
    boolean normalEnabled =
        plugin
            .getConfig()
            .getBoolean(
                worldBase + ".enabled",
                plugin
                    .getConfig()
                    .getBoolean(
                        legacyWorldBase + ".enabled",
                        plugin.getConfig().getBoolean(defaultBase + ".enabled", legacyFallback)));
    boolean componentEnabled =
        plugin
            .getConfig()
            .getBoolean(
                worldBase + ".component.enabled",
                plugin
                    .getConfig()
                    .getBoolean(
                        legacyWorldBase + ".component.enabled",
                        plugin.getConfig().getBoolean(defaultBase + ".component.enabled", false)));
    if (normalEnabled) {
      return true;
    }
    return componentEnabled && matchesComponentName(view, worldBase, legacyWorldBase, defaultBase);
  }

  private boolean matchesComponentName(
      InventoryView view, String worldBase, String legacyWorldBase, String defaultBase) {
    String name =
        plugin
            .getConfig()
            .getString(
                worldBase + ".component.name",
                plugin
                    .getConfig()
                    .getString(
                        legacyWorldBase + ".component.name",
                        plugin.getConfig().getString(defaultBase + ".component.name", "")));
    if (name == null || name.isBlank()) {
      return false;
    }
    String title = PlainTextComponentSerializer.plainText().serialize(view.title());
    return title.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT));
  }

  public ItemStack createVoxelItem(Material material, int size, String blockData, int amount) {
    if (size == 16 && (blockData == null || blockData.isEmpty()) && material.isItem()) {
      return new ItemStack(material, Math.max(1, Math.min(64, amount)));
    }
    Material itemMaterial = material.isItem() ? material : Material.STONE;
    ItemStack item = new ItemStack(itemMaterial, Math.max(1, Math.min(64, amount)));
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.displayName(
          Component.text(pretty(material) + " " + scaleName(size) + " Voxel")
              .color(NamedTextColor.AQUA)
              .decoration(TextDecoration.ITALIC, false));
      meta.lore(
          List.of(
              Component.text("SmallVoxels survival bit")
                  .color(NamedTextColor.GRAY)
                  .decoration(TextDecoration.ITALIC, false),
              Component.text("Scale: " + scaleName(size))
                  .color(NamedTextColor.DARK_GRAY)
                  .decoration(TextDecoration.ITALIC, false),
              Component.text("Block: " + pretty(material))
                  .color(NamedTextColor.DARK_GRAY)
                  .decoration(TextDecoration.ITALIC, false)));
      PersistentDataContainer data = meta.getPersistentDataContainer();
      data.set(materialKey, PersistentDataType.STRING, material.name());
      data.set(sizeKey, PersistentDataType.INTEGER, size);
      if (blockData != null && !blockData.isEmpty()) {
        data.set(blockDataKey, PersistentDataType.STRING, blockData);
      }
      item.setItemMeta(meta);
    }
    return item;
  }

  public boolean isVoxelItem(ItemStack item) {
    if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
      return false;
    }
    ItemMeta meta = item.getItemMeta();
    return meta != null
        && meta.getPersistentDataContainer().has(materialKey, PersistentDataType.STRING)
        && meta.getPersistentDataContainer().has(sizeKey, PersistentDataType.INTEGER);
  }

  public VoxelItemData itemData(ItemStack item) {
    if (isVoxelItem(item)) {
      PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
      Material material =
          Material.matchMaterial(data.getOrDefault(materialKey, PersistentDataType.STRING, "AIR"));
      Integer size = data.get(sizeKey, PersistentDataType.INTEGER);
      String blockData = data.get(blockDataKey, PersistentDataType.STRING);
      if (material == null || size == null || !validSize(size)) {
        return null;
      }
      return new VoxelItemData(material, size, blockData, true);
    }
    if (item != null && item.getType().isBlock() && !item.getType().isAir()) {
      return new VoxelItemData(item.getType(), 16, null, false);
    }
    return null;
  }

  public ItemStack craftResult(ItemStack[] matrix) {
    List<Integer> occupiedSlots = occupiedCraftSlots(matrix);
    if (!isTwoByTwoShape(matrix.length, occupiedSlots)) {
      return null;
    }
    List<VoxelItemData> ingredients = new ArrayList<>();
    for (Integer slot : occupiedSlots) {
      ItemStack item = matrix[slot];
      VoxelItemData data = itemData(item);
      if (data == null) {
        return null;
      }
      ingredients.add(data);
    }
    if (ingredients.size() != 4) {
      return null;
    }
    VoxelItemData first = ingredients.get(0);
    if (first.size >= 16) {
      return null;
    }
    for (VoxelItemData data : ingredients) {
      if (data.size != first.size
          || data.material != first.material
          || !java.util.Objects.equals(data.blockData, first.blockData)) {
        return null;
      }
    }
    return createVoxelItem(first.material, first.size * 2, first.blockData, 1);
  }

  private List<Integer> occupiedCraftSlots(ItemStack[] matrix) {
    List<Integer> slots = new ArrayList<>();
    for (int i = 0; i < matrix.length; i++) {
      ItemStack item = matrix[i];
      if (item != null && !item.getType().isAir()) {
        slots.add(i);
      }
    }
    return slots;
  }

  private boolean isTwoByTwoShape(int matrixLength, List<Integer> occupiedSlots) {
    if (occupiedSlots.size() != 4) {
      return false;
    }
    if (matrixLength == 4) {
      return occupiedSlots.containsAll(List.of(0, 1, 2, 3));
    }
    if (matrixLength != 9) {
      return false;
    }
    return occupiedSlots.containsAll(List.of(0, 1, 3, 4))
        || occupiedSlots.containsAll(List.of(1, 2, 4, 5))
        || occupiedSlots.containsAll(List.of(3, 4, 6, 7))
        || occupiedSlots.containsAll(List.of(4, 5, 7, 8));
  }

  public ItemStack stonecutterResult(ItemStack input) {
    VoxelItemData data = itemData(input);
    if (data == null || data.size <= 1) {
      return null;
    }
    return createVoxelItem(data.material, data.size / 2, data.blockData, 4);
  }

  public boolean placeVoxel(Player player, ItemStack item, Block clickedBlock, BlockFace face) {
    VoxelItemData data = itemData(item);
    if (data == null || !data.custom || clickedBlock == null || face == null) {
      return false;
    }

    Placement placement = placementFor(player, data.size, clickedBlock, face);
    if (placement == null) {
      return false;
    }
    if (!plugin.getVoxelProtectionManager().canEdit(player, placement.block)) {
      return false;
    }

    DataManager dataManager = plugin.getDataManager();
    if (dataManager.hasCarvedData(placement.block)) {
      placement.block.setType(Material.BARRIER, false);
    } else if (isReplaceableVoxelHost(placement.block)) {
      placement.block.setType(Material.BARRIER);
      dataManager.setVoxelPieces(placement.block, new ArrayList<>());
    } else {
      return false;
    }

    List<VoxelPiece> pieces = dataManager.getVoxelPieces(placement.block);
    VoxelPiece piece =
        new VoxelPiece(
            placement.x, placement.y, placement.z, data.size, data.material.name(), data.blockData);
    if (!VoxelPieceManager.place(pieces, piece)) {
      return false;
    }
    VoxelPieceManager.compact(pieces);
    dataManager.setVoxelPieces(placement.block, pieces);
    plugin.getFallingBlockManager().updateBlockDisplay(placement.block, null);
    CollisionBlockManager.updateCollisionBlock(
        placement.block, pieces, dataManager.isBlockLocked(placement.block));
    plugin.getVoxelProtectionManager().claimIfNeeded(player, placement.block);

    if (player.getGameMode() != GameMode.CREATIVE) {
      item.setAmount(item.getAmount() - 1);
    }
    return true;
  }

  private Placement placementFor(Player player, int size, Block clickedBlock, BlockFace face) {
    DataManager dataManager = plugin.getDataManager();
    VoxelManager.RaycastResult ray =
        VoxelManager.raycastThroughBarriers(
            player.getEyeLocation(),
            player.getEyeLocation().getDirection().normalize(),
            dataManager);

    if (ray != null && ray.hitFace != null) {
      return placementAdjacentToVoxel(ray, size);
    }
    return placementOnBlockSurface(player, clickedBlock, face, size);
  }

  private Placement placementAdjacentToVoxel(VoxelManager.RaycastResult ray, int size) {
    Block target = ray.block;
    int x = ray.piece == null ? ray.hitX : snapInside(ray.hitX, ray.piece.x, ray.piece.size, size);
    int y = ray.piece == null ? ray.hitY : snapInside(ray.hitY, ray.piece.y, ray.piece.size, size);
    int z = ray.piece == null ? ray.hitZ : snapInside(ray.hitZ, ray.piece.z, ray.piece.size, size);

    if (ray.piece != null) {
      switch (ray.hitFace) {
        case WEST -> x = ray.piece.x - size;
        case EAST -> x = ray.piece.x + ray.piece.size;
        case DOWN -> y = ray.piece.y - size;
        case UP -> y = ray.piece.y + ray.piece.size;
        case NORTH -> z = ray.piece.z - size;
        case SOUTH -> z = ray.piece.z + ray.piece.size;
        default -> {
          return null;
        }
      }
    }
    return normalizePlacement(target, x, y, z, size);
  }

  private Placement placementOnBlockSurface(Player player, Block block, BlockFace face, int size) {
    Block target = block.getRelative(face);
    double relX = 0.5;
    double relY = 0.5;
    double relZ = 0.5;
    org.bukkit.util.RayTraceResult hit = player.rayTraceBlocks(6.0);
    if (hit != null && hit.getHitPosition() != null) {
      relX = Math.max(0.0, Math.min(0.9999, hit.getHitPosition().getX() - block.getX()));
      relY = Math.max(0.0, Math.min(0.9999, hit.getHitPosition().getY() - block.getY()));
      relZ = Math.max(0.0, Math.min(0.9999, hit.getHitPosition().getZ() - block.getZ()));
    }
    int x = (int) Math.floor(relX * 16.0);
    int y = (int) Math.floor(relY * 16.0);
    int z = (int) Math.floor(relZ * 16.0);
    switch (face) {
      case UP -> y = 0;
      case DOWN -> y = 16 - size;
      case EAST -> x = 0;
      case WEST -> x = 16 - size;
      case SOUTH -> z = 0;
      case NORTH -> z = 16 - size;
      default -> {
        return null;
      }
    }
    int[] aligned = align(x, y, z, size);
    return normalizePlacement(target, aligned[0], aligned[1], aligned[2], size);
  }

  private Placement normalizePlacement(Block block, int x, int y, int z, int size) {
    Block target = block;
    while (x < 0) {
      target = target.getRelative(BlockFace.WEST);
      x += 16;
    }
    while (x > 16 - size) {
      target = target.getRelative(BlockFace.EAST);
      x -= 16;
    }
    while (y < 0) {
      target = target.getRelative(BlockFace.DOWN);
      y += 16;
    }
    while (y > 16 - size) {
      target = target.getRelative(BlockFace.UP);
      y -= 16;
    }
    while (z < 0) {
      target = target.getRelative(BlockFace.NORTH);
      z += 16;
    }
    while (z > 16 - size) {
      target = target.getRelative(BlockFace.SOUTH);
      z -= 16;
    }
    int[] aligned = align(x, y, z, size);
    return new Placement(target, aligned[0], aligned[1], aligned[2]);
  }

  private int[] align(int x, int y, int z, int size) {
    return new int[] {
      Math.max(0, Math.min(16 - size, (x / size) * size)),
      Math.max(0, Math.min(16 - size, (y / size) * size)),
      Math.max(0, Math.min(16 - size, (z / size) * size))
    };
  }

  private int snapInside(int value, int start, int span, int size) {
    int offset = Math.max(0, Math.min(span - 1, value - start));
    return start + (offset / size) * size;
  }

  private boolean isReplaceableVoxelHost(Block block) {
    return block.getType().isAir() || block.isPassable() || block.getType() == Material.BARRIER;
  }

  public boolean canHarvestAll(Player player, List<VoxelPiece> pieces) {
    ItemStack tool = player.getInventory().getItemInMainHand();
    for (VoxelPiece piece : pieces) {
      if (!canHarvest(piece.getAsMaterial(), tool)) {
        return false;
      }
    }
    return true;
  }

  private boolean canHarvest(Material material, ItemStack tool) {
    if (tool == null || tool.getType().isAir()) {
      return !requiresTool(material);
    }
    if (Tag.INCORRECT_FOR_WOODEN_TOOL.isTagged(material) && isWoodenTool(tool.getType()))
      return false;
    if (Tag.INCORRECT_FOR_GOLD_TOOL.isTagged(material) && isGoldTool(tool.getType())) return false;
    if (Tag.INCORRECT_FOR_STONE_TOOL.isTagged(material) && isStoneTool(tool.getType()))
      return false;
    if (Tag.INCORRECT_FOR_IRON_TOOL.isTagged(material) && isIronTool(tool.getType())) return false;
    if (Tag.INCORRECT_FOR_DIAMOND_TOOL.isTagged(material) && isDiamondTool(tool.getType()))
      return false;
    if (Tag.INCORRECT_FOR_NETHERITE_TOOL.isTagged(material) && isNetheriteTool(tool.getType()))
      return false;
    if (Tag.MINEABLE_PICKAXE.isTagged(material)) return isPickaxe(tool.getType());
    if (Tag.MINEABLE_AXE.isTagged(material)) return isAxe(tool.getType());
    if (Tag.MINEABLE_SHOVEL.isTagged(material)) return isShovel(tool.getType());
    if (Tag.MINEABLE_HOE.isTagged(material)) return isHoe(tool.getType());
    return true;
  }

  private boolean requiresTool(Material material) {
    return Tag.MINEABLE_PICKAXE.isTagged(material)
        || Tag.MINEABLE_AXE.isTagged(material)
        || Tag.MINEABLE_SHOVEL.isTagged(material)
        || Tag.MINEABLE_HOE.isTagged(material)
        || Tag.NEEDS_STONE_TOOL.isTagged(material)
        || Tag.NEEDS_IRON_TOOL.isTagged(material)
        || Tag.NEEDS_DIAMOND_TOOL.isTagged(material);
  }

  public void dropVoxelItems(Block block, List<VoxelPiece> pieces) {
    Map<String, DropStack> stacks = new HashMap<>();
    for (VoxelPiece piece : pieces) {
      Material material = piece.getAsMaterial();
      if (material == Material.AIR) {
        continue;
      }
      String key =
          material.name()
              + "|"
              + piece.size
              + "|"
              + (piece.blockData == null ? "" : piece.blockData);
      DropStack stack =
          stacks.computeIfAbsent(
              key, ignored -> new DropStack(material, piece.size, piece.blockData, 0));
      stack.amount++;
    }
    stacks.values().stream()
        .sorted(Comparator.comparing(stack -> stack.material.name()))
        .forEach(
            stack -> {
              int remaining = stack.amount;
              while (remaining > 0) {
                int amount = Math.min(64, remaining);
                block
                    .getWorld()
                    .dropItemNaturally(
                        block.getLocation().add(0.5, 0.5, 0.5),
                        createVoxelItem(stack.material, stack.size, stack.blockData, amount));
                remaining -= amount;
              }
            });
  }

  private boolean validSize(int size) {
    return size == 1 || size == 2 || size == 4 || size == 8 || size == 16;
  }

  private String scaleName(int size) {
    return switch (size) {
      case 8 -> "2x";
      case 4 -> "4x";
      case 2 -> "8x";
      case 1 -> "16x";
      default -> "Full";
    };
  }

  private String pretty(Material material) {
    String lower = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    StringBuilder result = new StringBuilder();
    for (String word : lower.split(" ")) {
      if (!word.isEmpty()) {
        result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
      }
    }
    return result.toString().trim();
  }

  private boolean isPickaxe(Material material) {
    return material.name().endsWith("_PICKAXE");
  }

  private boolean isAxe(Material material) {
    return material.name().endsWith("_AXE");
  }

  private boolean isShovel(Material material) {
    return material.name().endsWith("_SHOVEL");
  }

  private boolean isHoe(Material material) {
    return material.name().endsWith("_HOE");
  }

  private boolean isWoodenTool(Material material) {
    return material.name().startsWith("WOODEN_");
  }

  private boolean isGoldTool(Material material) {
    return material.name().startsWith("GOLDEN_");
  }

  private boolean isStoneTool(Material material) {
    return material.name().startsWith("STONE_");
  }

  private boolean isIronTool(Material material) {
    return material.name().startsWith("IRON_");
  }

  private boolean isDiamondTool(Material material) {
    return material.name().startsWith("DIAMOND_");
  }

  private boolean isNetheriteTool(Material material) {
    return material.name().startsWith("NETHERITE_");
  }

  public record VoxelItemData(Material material, int size, String blockData, boolean custom) {}

  private record Placement(Block block, int x, int y, int z) {}

  private static class DropStack {
    final Material material;
    final int size;
    final String blockData;
    int amount;

    DropStack(Material material, int size, String blockData, int amount) {
      this.material = material;
      this.size = size;
      this.blockData = blockData;
      this.amount = amount;
    }
  }
}
