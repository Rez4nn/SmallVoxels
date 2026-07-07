package me.reube.SmallVoxels.managers;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import me.reube.SmallVoxels.managers.animation.AnimatedVoxelObject;
import me.reube.SmallVoxels.managers.animation.AnimatedVoxelPart;
import me.reube.SmallVoxels.managers.animation.VoxelFrameChange;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class AnimatedObjectRenderer {
  private static final String ITEM_DATA_PREFIX = "smallvoxels:item:";
  private static final String ANIMATION_TAG = "smallvoxels_animation_display";

  private final JavaPlugin plugin;
  private final Map<String, Map<String, Entity>> displaysByObject = new HashMap<>();
  private final Map<String, Map<String, String>> displaySignaturesByObject = new HashMap<>();
  private final Map<String, java.util.List<AnimatedVoxelPart>> reconciledPartsByObject =
      new HashMap<>();
  private final NamespacedKey animationObjectKey;
  private final NamespacedKey animationPartKey;
  private final NamespacedKey normalVoxelPieceXKey;

  public AnimatedObjectRenderer(JavaPlugin plugin) {
    this.plugin = plugin;
    this.animationObjectKey = new NamespacedKey(plugin, "animation_object");
    this.animationPartKey = new NamespacedKey(plugin, "animation_part");
    this.normalVoxelPieceXKey = new NamespacedKey(plugin, "piece_x");
  }

  public void removePersistedAnimationDisplays() {
    for (World world : Bukkit.getWorlds()) {
      for (Entity entity : world.getEntities()) {
        if (!(entity instanceof BlockDisplay) && !(entity instanceof ItemDisplay)) {
          continue;
        }
        if (isAnimationDisplay(entity)) {
          entity.remove();
        }
      }
    }
    displaysByObject.clear();
    displaySignaturesByObject.clear();
    reconciledPartsByObject.clear();
  }

  public void removeUntaggedDisplaysInside(AnimatedVoxelObject object) {
    World world = Bukkit.getWorld(object.world);
    if (world == null) {
      return;
    }
    Bounds bounds = boundsFor(object);
    for (Entity entity : world.getEntities()) {
      if (!(entity instanceof BlockDisplay) && !(entity instanceof ItemDisplay)) {
        continue;
      }
      if (isNormalVoxelDisplay(entity) || isAnimationDisplay(entity)) {
        continue;
      }
      Location location = entity.getLocation();
      if (bounds.contains(location.getX(), location.getY(), location.getZ())) {
        entity.remove();
      }
    }
  }

  public void spawn(AnimatedVoxelObject object) {
    remove(object.id);
    World world = Bukkit.getWorld(object.world);
    if (world == null) {
      return;
    }

    Map<String, Entity> displays = new LinkedHashMap<>();
    Map<String, String> signatures = new LinkedHashMap<>();
    for (AnimatedVoxelPart part : object.parts) {
      Entity entity = spawnPart(world, object, part);
      if (entity != null) {
        displays.put(part.id, entity);
        signatures.put(part.id, partSignature(part));
      }
    }
    String objectKey = object.id.toLowerCase();
    displaysByObject.put(objectKey, displays);
    displaySignaturesByObject.put(objectKey, signatures);
    reconciledPartsByObject.put(objectKey, object.parts);
  }

  public void replaceParts(AnimatedVoxelObject object, java.util.List<AnimatedVoxelPart> parts) {
    remove(object.id);
    renderParts(object, parts);
  }

  public void remove(String objectId) {
    String objectKey = objectId.toLowerCase();
    Map<String, Entity> displays = displaysByObject.remove(objectKey);
    displaySignaturesByObject.remove(objectKey);
    reconciledPartsByObject.remove(objectKey);
    if (displays == null) {
      return;
    }
    for (Entity entity : displays.values()) {
      if (entity.isValid()) {
        entity.remove();
      }
    }
  }

  public void removeAll() {
    for (String id : new java.util.ArrayList<>(displaysByObject.keySet())) {
      remove(id);
    }
  }

  public void render(
      AnimatedVoxelObject object, double offsetX, double offsetY, double offsetZ, float yaw) {
    Map<String, Entity> displays = displaysByObject.get(object.id.toLowerCase());
    if (displays == null || displays.isEmpty()) {
      spawn(object);
      displays = displaysByObject.get(object.id.toLowerCase());
    }
    if (displays == null) {
      return;
    }

    World world = Bukkit.getWorld(object.world);
    if (world == null) {
      return;
    }

    ensureReconciled(object, object.parts, displays);
    renderLocations(object, object.parts, displays, null, offsetX, offsetY, offsetZ, yaw);
  }

  public void renderParts(AnimatedVoxelObject object, java.util.List<AnimatedVoxelPart> parts) {
    String objectKey = object.id.toLowerCase();
    Map<String, Entity> displays = displaysByObject.get(objectKey);
    if (displays == null) {
      displays = new LinkedHashMap<>();
      displaysByObject.put(objectKey, displays);
    }
    ensureReconciled(object, parts, displays);
    renderLocations(object, parts, displays, null, 0, 0, 0, object.yaw);
  }

  public void renderPartsWithOffsets(
      AnimatedVoxelObject object,
      java.util.List<AnimatedVoxelPart> parts,
      Map<String, double[]> voxelOffsets) {
    String objectKey = object.id.toLowerCase();
    Map<String, Entity> displays = displaysByObject.get(objectKey);
    if (displays == null) {
      displays = new LinkedHashMap<>();
      displaysByObject.put(objectKey, displays);
    }
    ensureReconciled(object, parts, displays);
    renderLocations(object, parts, displays, voxelOffsets, 0, 0, 0, object.yaw);
  }

  private void ensureReconciled(
      AnimatedVoxelObject object,
      java.util.List<AnimatedVoxelPart> parts,
      Map<String, Entity> displays) {
    String objectKey = object.id.toLowerCase();
    boolean needsReconcile =
        reconciledPartsByObject.get(objectKey) != parts || displays.size() != parts.size();
    if (!needsReconcile) {
      for (AnimatedVoxelPart part : parts) {
        Entity entity = displays.get(part.id);
        if (entity == null || !entity.isValid()) {
          needsReconcile = true;
          break;
        }
      }
    }
    if (needsReconcile) {
      reconcileParts(object, parts, displays);
      reconciledPartsByObject.put(objectKey, parts);
    }
  }

  private void renderLocations(
      AnimatedVoxelObject object,
      java.util.List<AnimatedVoxelPart> parts,
      Map<String, Entity> displays,
      Map<String, double[]> voxelOffsets,
      double offsetX,
      double offsetY,
      double offsetZ,
      float yaw) {
    World world = Bukkit.getWorld(object.world);
    if (world == null) {
      return;
    }
    double radians = Math.toRadians(yaw);
    double sin = Math.sin(radians);
    double cos = Math.cos(radians);
    for (AnimatedVoxelPart part : parts) {
      Entity entity = displays.get(part.id);
      if (entity == null || !entity.isValid()) {
        continue;
      }
      double[] wind = voxelOffsets == null ? null : voxelOffsets.get(part.id);
      double windX = wind == null ? 0.0 : wind[0];
      double windY = wind == null ? 0.0 : wind[1];
      double windZ = wind == null ? 0.0 : wind[2];
      double localX = (part.localX + windX) / 16.0 + part.offsetX;
      double localY = (part.localY + windY) / 16.0 + part.offsetY;
      double localZ = (part.localZ + windZ) / 16.0 + part.offsetZ;
      boolean transformed =
          part.rotationX != 0
              || part.rotationY != 0
              || part.rotationZ != 0
              || part.quaternionX != 0
              || part.quaternionY != 0
              || part.quaternionZ != 0
              || part.quaternionW != 1.0f
              || part.visualScale != 1.0
              || part.scaleX != 1.0
              || part.scaleY != 1.0
              || part.scaleZ != 1.0
              || part.offsetX != 0
              || part.offsetY != 0
              || part.offsetZ != 0;
      if (transformed) {
        double half = part.size / 32.0;
        localX += half;
        localY += half;
        localZ += half;
      }
      double rotatedX = localX * cos - localZ * sin;
      double rotatedZ = localX * sin + localZ * cos;
      entity.teleport(
          new Location(
              world,
              object.originX + offsetX + rotatedX,
              object.originY + offsetY + localY,
              object.originZ + offsetZ + rotatedZ,
              yaw,
              0.0f));
    }
  }

  private void reconcileParts(
      AnimatedVoxelObject object,
      java.util.List<AnimatedVoxelPart> parts,
      Map<String, Entity> displays) {
    java.util.Set<String> wanted = new java.util.HashSet<>();
    World world = Bukkit.getWorld(object.world);
    if (world == null) {
      return;
    }
    Map<String, String> signatures =
        displaySignaturesByObject.computeIfAbsent(
            object.id.toLowerCase(), ignored -> new LinkedHashMap<>());
    for (AnimatedVoxelPart part : parts) {
      wanted.add(part.id);
      Entity entity = displays.get(part.id);
      if (entity == null || !entity.isValid() || !sameDisplayType(entity, part)) {
        if (entity != null) {
          entity.remove();
        }
        entity = spawnPart(world, object, part);
        if (entity != null) {
          displays.put(part.id, entity);
          signatures.put(part.id, partSignature(part));
        }
      } else {
        String signature = partSignature(part);
        if (!signature.equals(signatures.get(part.id))) {
          updateDisplayAppearance(entity, part);
          signatures.put(part.id, signature);
        }
      }
    }
    displays
        .entrySet()
        .removeIf(
            entry -> {
              if (wanted.contains(entry.getKey())) {
                return false;
              }
              if (entry.getValue().isValid()) {
                entry.getValue().remove();
              }
              signatures.remove(entry.getKey());
              return true;
            });
  }

  private String partSignature(AnimatedVoxelPart part) {
    return part.size
        + "|"
        + part.material
        + "|"
        + part.blockData
        + "|"
        + part.rotationX
        + "|"
        + part.rotationY
        + "|"
        + part.rotationZ
        + "|"
        + part.visualScale
        + "|"
        + part.scaleX
        + "|"
        + part.scaleY
        + "|"
        + part.scaleZ
        + "|"
        + part.quaternionX
        + "|"
        + part.quaternionY
        + "|"
        + part.quaternionZ
        + "|"
        + part.quaternionW;
  }

  private boolean sameDisplayType(Entity entity, AnimatedVoxelPart part) {
    boolean itemPart = part.blockData != null && part.blockData.startsWith(ITEM_DATA_PREFIX);
    return itemPart ? entity instanceof ItemDisplay : entity instanceof BlockDisplay;
  }

  private void updateDisplayAppearance(Entity entity, AnimatedVoxelPart part) {
    float scale = (float) (part.size / 16.0 * part.visualScale);
    Quaternionf rotation = part.rotationQuaternion();
    boolean transformed =
        part.rotationX != 0
            || part.rotationY != 0
            || part.rotationZ != 0
            || part.visualScale != 1.0
            || part.quaternionX != 0
            || part.quaternionY != 0
            || part.quaternionZ != 0
            || part.quaternionW != 1.0f
            || part.scaleX != 1.0
            || part.scaleY != 1.0
            || part.scaleZ != 1.0;
    float sx = (float) (scale * part.scaleX),
        sy = (float) (scale * part.scaleY),
        sz = (float) (scale * part.scaleZ);
    Vector3f translation = transformed ? new Vector3f(-sx / 2, -sy / 2, -sz / 2) : new Vector3f();
    if (entity instanceof BlockDisplay blockDisplay) {
      blockDisplay.setBlock(blockDataFor(part));
      blockDisplay.setTransformation(
          new Transformation(translation, rotation, new Vector3f(sx, sy, sz), new Quaternionf()));
    } else if (entity instanceof ItemDisplay itemDisplay) {
      ItemStack item = itemFromData(part.blockData);
      if (item != null && item.getType() != Material.AIR) {
        itemDisplay.setItemStack(item);
      }
      itemDisplay.setTransformation(
          new Transformation(
              translation, rotation, new Vector3f(sx * 2, sy * 2, sz * 2), new Quaternionf()));
    }
  }

  public void applyChange(AnimatedVoxelObject object, VoxelFrameChange change) {
    Map<String, Entity> displays = displaysByObject.get(object.id.toLowerCase());
    if (displays == null) {
      return;
    }
    Entity entity = displays.get(change.partId);
    AnimatedVoxelPart part = findPart(object, change.partId);
    if (part == null) {
      return;
    }
    if (change.material != null && !change.material.isBlank()) {
      part.material = change.material;
      part.blockData = change.blockData;
      if (entity instanceof BlockDisplay blockDisplay) {
        blockDisplay.setBlock(blockDataFor(part));
      }
    }
    if (change.visible != null && entity != null) {
      float scale = change.visible ? (float) part.size / 16.0f : 0.0f;
      if (entity instanceof Display display) {
        display.setTransformation(
            new Transformation(
                new Vector3f(0, 0, 0),
                new Quaternionf(0, 0, 0, 1),
                new Vector3f(scale, scale, scale),
                new Quaternionf()));
      }
    }
    if (change.localX != null) {
      part.localX = change.localX;
    }
    if (change.localY != null) {
      part.localY = change.localY;
    }
    if (change.localZ != null) {
      part.localZ = change.localZ;
    }
  }

  private Entity spawnPart(World world, AnimatedVoxelObject object, AnimatedVoxelPart part) {
    Location location =
        new Location(
            world,
            object.originX + part.localX / 16.0,
            object.originY + part.localY / 16.0,
            object.originZ + part.localZ / 16.0);
    float scale = (float) part.size / 16.0f;

    if (part.blockData != null && part.blockData.startsWith(ITEM_DATA_PREFIX)) {
      ItemStack item = itemFromData(part.blockData);
      if (item == null || item.getType() == Material.AIR) {
        return null;
      }
      ItemDisplay display = world.spawn(location, ItemDisplay.class);
      tagAnimationDisplay(display, object, part);
      display.setItemStack(item);
      display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
      display.setShadowRadius(0.0f);
      display.setShadowStrength(0.0f);
      display.setTransformation(
          new Transformation(
              new Vector3f(), new Quaternionf(), new Vector3f(scale * 2.0f), new Quaternionf()));
      updateDisplayAppearance(display, part);
      return display;
    }

    BlockDisplay display = world.spawn(location, BlockDisplay.class);
    tagAnimationDisplay(display, object, part);
    display.setBlock(blockDataFor(part));
    display.setNoPhysics(true);
    display.setShadowRadius(0.0f);
    display.setShadowStrength(0.0f);
    display.setBrightness(new Display.Brightness(15, 15));
    display.setTransformation(
        new Transformation(
            new Vector3f(), new Quaternionf(), new Vector3f(scale), new Quaternionf()));
    updateDisplayAppearance(display, part);
    return display;
  }

  private void tagAnimationDisplay(
      Entity entity, AnimatedVoxelObject object, AnimatedVoxelPart part) {
    entity.addScoreboardTag(ANIMATION_TAG);
    PersistentDataContainer data = entity.getPersistentDataContainer();
    data.set(animationObjectKey, PersistentDataType.STRING, object.id);
    data.set(animationPartKey, PersistentDataType.STRING, part.id == null ? "" : part.id);
  }

  private boolean isAnimationDisplay(Entity entity) {
    return entity.getScoreboardTags().contains(ANIMATION_TAG)
        || entity.getPersistentDataContainer().has(animationObjectKey, PersistentDataType.STRING);
  }

  private boolean isNormalVoxelDisplay(Entity entity) {
    return entity
        .getPersistentDataContainer()
        .has(normalVoxelPieceXKey, PersistentDataType.INTEGER);
  }

  private Bounds boundsFor(AnimatedVoxelObject object) {
    double minX = object.originX - 1.0;
    double minY = object.originY - 1.0;
    double minZ = object.originZ - 1.0;
    double maxX = object.originX + 1.0;
    double maxY = object.originY + 1.0;
    double maxZ = object.originZ + 1.0;
    java.util.List<AnimatedVoxelPart> allParts = new java.util.ArrayList<>();
    if (object.parts != null) {
      allParts.addAll(object.parts);
    }
    if (object.animations != null) {
      for (me.reube.SmallVoxels.managers.animation.VoxelAnimation animation : object.animations) {
        if (animation.stateKeyframes == null) {
          continue;
        }
        for (me.reube.SmallVoxels.managers.animation.VoxelStateKeyframe frame :
            animation.stateKeyframes) {
          if (frame.parts != null) {
            allParts.addAll(frame.parts);
          }
        }
      }
    }
    for (AnimatedVoxelPart part : allParts) {
      double x = object.originX + part.localX / 16.0;
      double y = object.originY + part.localY / 16.0;
      double z = object.originZ + part.localZ / 16.0;
      double size = Math.max(1, part.size) / 16.0;
      minX = Math.min(minX, x);
      minY = Math.min(minY, y);
      minZ = Math.min(minZ, z);
      maxX = Math.max(maxX, x + size);
      maxY = Math.max(maxY, y + size);
      maxZ = Math.max(maxZ, z + size);
    }
    return new Bounds(minX - 0.25, minY - 0.25, minZ - 0.25, maxX + 0.25, maxY + 0.25, maxZ + 0.25);
  }

  private record Bounds(
      double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    boolean contains(double x, double y, double z) {
      return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
  }

  private BlockData blockDataFor(AnimatedVoxelPart part) {
    if (part.blockData != null && !part.blockData.isBlank()) {
      return Bukkit.createBlockData(part.blockData);
    }
    return part.getAsMaterial().createBlockData();
  }

  private ItemStack itemFromData(String blockDataText) {
    try {
      String encoded = blockDataText.substring(ITEM_DATA_PREFIX.length());
      String yaml = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
      YamlConfiguration config = new YamlConfiguration();
      config.loadFromString(yaml);
      return config.getItemStack("item");
    } catch (Exception ex) {
      plugin.getLogger().warning("Failed to load animated item voxel: " + ex.getMessage());
      return null;
    }
  }

  private AnimatedVoxelPart findPart(AnimatedVoxelObject object, String partId) {
    for (AnimatedVoxelPart part : object.parts) {
      if (part.id.equalsIgnoreCase(partId)) {
        return part;
      }
    }
    return null;
  }
}
