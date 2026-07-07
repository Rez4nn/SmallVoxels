package me.reube.SmallVoxels.managers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.managers.animation.AnimatedImageFramePart;
import me.reube.SmallVoxels.managers.animation.AnimatedVoxelObject;
import me.reube.SmallVoxels.managers.animation.AnimatedVoxelPart;
import me.reube.SmallVoxels.managers.animation.AnimationKeyframe;
import me.reube.SmallVoxels.managers.animation.AnimationSequence;
import me.reube.SmallVoxels.managers.animation.AnimationSequenceStep;
import me.reube.SmallVoxels.managers.animation.AnimationTriggerCause;
import me.reube.SmallVoxels.managers.animation.VoxelAnimation;
import me.reube.SmallVoxels.managers.animation.VoxelFrame;
import me.reube.SmallVoxels.managers.animation.VoxelFrameChange;
import me.reube.SmallVoxels.managers.animation.VoxelStateKeyframe;
import me.reube.SmallVoxels.managers.animation.WindLink;
import me.reube.SmallVoxels.managers.animation.WindSourceBlock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.w3c.dom.Node;

public class AnimatedObjectManager {
  private final SmallVoxels plugin;
  private final AnimationStorageManager storage;
  private final AnimatedObjectRenderer renderer;
  private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
  private final Map<String, Long> triggerCooldownUntil = new HashMap<>();
  private final Map<String, RunningAnimation> running = new HashMap<>();
  private final Map<String, RunningSequence> runningSequences = new HashMap<>();
  private final Map<String, EditorState> editors = new HashMap<>();
  private final Map<String, PendingImage> pendingImages = new HashMap<>();
  private final Map<String, Map<String, ItemFrame>> imageFramesByObject = new HashMap<>();
  private final Map<String, WindTopologyCache> windTopologyByObject = new HashMap<>();
  private final NamespacedKey imageFrameObjectKey;
  private final NamespacedKey imageFramePartKey;
  private BukkitTask task;
  private BukkitTask triggerTask;

  public AnimatedObjectManager(SmallVoxels plugin) {
    this.plugin = plugin;
    this.storage = new AnimationStorageManager(plugin);
    this.renderer = new AnimatedObjectRenderer(plugin);
    this.imageFrameObjectKey = new NamespacedKey(plugin, "animation_image_object");
    this.imageFramePartKey = new NamespacedKey(plugin, "animation_image_part");
  }

  public void loadAll() {
    storage.loadAll();
    syncLoadedObjects();
    ensureTriggerTask();
  }

  public void saveAll() {
    storage.saveAll();
  }

  public void shutdown() {
    if (task != null) {
      task.cancel();
      task = null;
    }
    if (triggerTask != null) {
      triggerTask.cancel();
      triggerTask = null;
    }
    running.clear();
    runningSequences.clear();
    removeAllRenderedImageFrames();
    renderer.removeAll();
    saveAll();
  }

  public AnimationStorageManager storage() {
    return storage;
  }

  public void syncLoadedObjects() {
    removePersistedImageFrames();
    renderer.removePersistedAnimationDisplays();
    for (AnimatedVoxelObject object : storage.all()) {
      renderer.removeUntaggedDisplaysInside(object);
      hideClaimedSourceBlocks(object);
      renderDefaultState(object);
    }
  }

  public AnimatedVoxelObject createFromClipboard(Player player, String id) {
    if (!storage.isValidId(id)
        || storage.get(id) != null
        || !plugin.getVoxelClipboard().hasCopy(player)) {
      return null;
    }

    Location origin = player.getLocation();
    AnimatedVoxelObject object =
        new AnimatedVoxelObject(
            id,
            player.getWorld().getName(),
            origin.getBlockX() + 0.5,
            origin.getBlockY(),
            origin.getBlockZ() + 0.5);
    List<VoxelPiece> pieces = plugin.getVoxelClipboard().get(player);
    int index = 1;
    for (VoxelPiece piece : pieces) {
      object.parts.add(animatedFromPiece("part_" + index, piece.x, piece.y, piece.z, piece));
      index++;
    }

    VoxelAnimation main = new VoxelAnimation("main");
    main.displayName = "idle";
    main.lengthTicks = 100;
    main.loop = false;
    main.transformKeyframes.add(new AnimationKeyframe(0, 0, 0, 0, 0));
    main.transformKeyframes.add(new AnimationKeyframe(100, 0, 0, 0, 0));
    object.animations.add(main);

    storage.put(object);
    storage.save(object);
    renderer.spawn(object);
    return object;
  }

  public AnimatedVoxelObject createEmpty(Player player, String id) {
    if (!storage.isValidId(id) || storage.get(id) != null) {
      return null;
    }
    Location origin = player.getLocation();
    AnimatedVoxelObject object =
        new AnimatedVoxelObject(
            id,
            player.getWorld().getName(),
            origin.getBlockX() + 0.5,
            origin.getBlockY(),
            origin.getBlockZ() + 0.5);

    VoxelAnimation main = new VoxelAnimation("main");
    main.displayName = "idle";
    main.lengthTicks = 40;
    main.loop = true;
    object.animations.add(main);

    storage.put(object);
    storage.save(object);
    editors.put(playerKey(player), new EditorState(object.id, main.id, 0));
    return object;
  }

  public AnimatedVoxelObject createWind(Player player, String id) {
    AnimatedVoxelObject object = createEmpty(player, id);
    if (object == null) {
      return null;
    }
    object.objectType = "WIND";
    VoxelAnimation animation = object.animation("main");
    if (animation != null) {
      animation.displayName = "wind";
      animation.loop = true;
    }
    storage.save(object);
    ensureTriggerTask();
    return object;
  }

  public AnimatedVoxelObject copyObject(Player player, String sourceId, String newId) {
    AnimatedVoxelObject source = storage.get(sourceId);
    if (source == null || !storage.isValidId(newId) || storage.get(newId) != null) {
      return null;
    }
    AnimatedVoxelObject copy = gson.fromJson(gson.toJson(source), AnimatedVoxelObject.class);
    copy.id = newId;
    copy.originX = player.getLocation().getBlockX() + 0.5;
    copy.originY = player.getLocation().getBlockY();
    copy.originZ = player.getLocation().getBlockZ() + 0.5;
    copy.world = player.getWorld().getName();
    storage.put(copy);
    storage.save(copy);
    renderer.spawn(copy);
    return copy;
  }

  public boolean saveSchematic(String objectId, File file) {
    AnimatedVoxelObject object = storage.get(objectId);
    if (object == null) {
      return false;
    }
    if (file.getParentFile() != null) {
      try {
        java.nio.file.Files.createDirectories(file.getParentFile().toPath());
      } catch (IOException exception) {
        plugin.getLogger().warning("Could not create schematic folder: " + exception.getMessage());
        return false;
      }
    }
    try (var writer =
        java.nio.file.Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
      gson.toJson(object, writer);
      return true;
    } catch (IOException ex) {
      plugin
          .getLogger()
          .warning("Could not save animation schematic " + file + ": " + ex.getMessage());
      return false;
    }
  }

  public AnimatedVoxelObject loadSchematic(Player player, File file, String newId) {
    if (!file.exists() || !storage.isValidId(newId) || storage.get(newId) != null) {
      return null;
    }
    try (var reader =
        java.nio.file.Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
      AnimatedVoxelObject object = gson.fromJson(reader, AnimatedVoxelObject.class);
      if (object == null) {
        return null;
      }
      object.id = newId;
      object.world = player.getWorld().getName();
      object.originX = player.getLocation().getBlockX() + 0.5;
      object.originY = player.getLocation().getBlockY();
      object.originZ = player.getLocation().getBlockZ() + 0.5;
      storage.put(object);
      storage.save(object);
      renderer.spawn(object);
      return object;
    } catch (IOException ex) {
      plugin
          .getLogger()
          .warning("Could not load animation schematic " + file + ": " + ex.getMessage());
      return null;
    }
  }

  public AnimatedVoxelObject createFromEnvironment(Player player, String id, int radiusBlocks) {
    if (!storage.isValidId(id) || storage.get(id) != null) {
      return null;
    }

    Location origin = player.getLocation();
    AnimatedVoxelObject object =
        new AnimatedVoxelObject(
            id,
            player.getWorld().getName(),
            origin.getBlockX() + 0.5,
            origin.getBlockY(),
            origin.getBlockZ() + 0.5);
    object.parts =
        captureEnvironment(player.getWorld(), object, Math.max(1, Math.min(64, radiusBlocks)));
    if (object.parts.isEmpty()) {
      return null;
    }
    removeClaimedWindSourceBlocks(player.getWorld(), object, object.parts);

    VoxelAnimation main = new VoxelAnimation("main");
    main.displayName = "idle";
    main.lengthTicks = 40;
    main.loop = false;
    main.stateKeyframes.add(new VoxelStateKeyframe(0, "HARD", cloneParts(object.parts)));
    object.animations.add(main);

    storage.put(object);
    storage.save(object);
    renderer.spawn(object);
    return object;
  }

  public AnimatedVoxelObject createFromRegion(Player player, String id, Block first, Block second) {
    if (!storage.isValidId(id)
        || storage.get(id) != null
        || first == null
        || second == null
        || !first.getWorld().equals(second.getWorld())) {
      return null;
    }
    int minX = Math.min(first.getX(), second.getX());
    int minY = Math.min(first.getY(), second.getY());
    int minZ = Math.min(first.getZ(), second.getZ());
    int maxX = Math.max(first.getX(), second.getX());
    int maxY = Math.max(first.getY(), second.getY());
    int maxZ = Math.max(first.getZ(), second.getZ());
    AnimatedVoxelObject object =
        new AnimatedVoxelObject(id, first.getWorld().getName(), minX + 0.5, minY, minZ + 0.5);
    object.parts = captureRegion(first.getWorld(), object, minX, minY, minZ, maxX, maxY, maxZ);
    if (object.parts.isEmpty()) {
      return null;
    }
    removeClaimedWindSourceBlocks(first.getWorld(), object, object.parts);
    VoxelAnimation main = new VoxelAnimation("main");
    main.displayName = "idle";
    main.lengthTicks = 40;
    main.stateKeyframes.add(new VoxelStateKeyframe(0, "HARD", cloneParts(object.parts)));
    object.animations.add(main);
    storage.put(object);
    storage.save(object);
    renderer.spawn(object);
    return object;
  }

  public boolean addKeyframe(String objectId, String animationId, AnimationKeyframe keyframe) {
    AnimatedVoxelObject object = storage.get(objectId);
    if (object == null) {
      return false;
    }
    VoxelAnimation animation = ensureAnimation(object, animationId);
    animation.transformKeyframes.removeIf(existing -> existing.tick == keyframe.tick);
    animation.transformKeyframes.add(keyframe);
    animation.lengthTicks = Math.max(animation.lengthTicks, keyframe.tick);
    animation.sort();
    storage.save(object);
    return true;
  }

  public boolean addMaterialFrame(
      String objectId, String animationId, int tick, String partId, String material) {
    AnimatedVoxelObject object = storage.get(objectId);
    if (object == null || findPart(object, partId) == null) {
      return false;
    }
    VoxelAnimation animation = ensureAnimation(object, animationId);
    VoxelFrame frame = frameAt(animation, tick);
    VoxelFrameChange change = new VoxelFrameChange(partId);
    change.material = material;
    frame.changes.add(change);
    animation.lengthTicks = Math.max(animation.lengthTicks, tick);
    animation.sort();
    storage.save(object);
    return true;
  }

  public boolean captureEditorState(Player player, String transition) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    EditorState state = editor(player);
    if (object == null || animation == null || state == null) {
      return false;
    }

    if (state.editBlockKeys.isEmpty()) {
      return false;
    }
    List<AnimatedVoxelPart> captured =
        captureBlocks(player.getWorld(), object, state.editBlockKeys);
    if (captured.isEmpty()) {
      return false;
    }

    putStateKeyframe(animation, state.tick, transition, captured);
    animation.lengthTicks = Math.max(animation.lengthTicks, state.tick);
    animation.sort();
    object.parts = cloneParts(captured);
    storage.save(object);
    if (!plugin.getAnimationAxeManager().isEditing(player)) {
      renderer.renderParts(object, captured);
    }
    return true;
  }

  public boolean captureEditorBlockState(Player player, Block block, String transition) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    EditorState state = editor(player);
    if (object == null
        || animation == null
        || state == null
        || block == null
        || !block.getWorld().getName().equals(object.world)) {
      return false;
    }
    java.util.Set<String> keys = java.util.Set.of(blockKey(block));
    List<AnimatedVoxelPart> captured = captureBlocks(block.getWorld(), object, keys);
    if (captured.isEmpty()) {
      return false;
    }
    putStateKeyframe(animation, state.tick, transition, captured);
    animation.lengthTicks = Math.max(animation.lengthTicks, state.tick);
    animation.sort();
    object.parts = cloneParts(captured);
    state.editBlockKeys.clear();
    state.editBlockKeys.addAll(keys);
    storage.save(object);
    return true;
  }

  public boolean captureEditorRegionState(
      Player player, Block first, Block second, String transition) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    EditorState state = editor(player);
    if (object == null
        || animation == null
        || state == null
        || first == null
        || second == null
        || !first.getWorld().equals(second.getWorld())) {
      return false;
    }
    int minX = Math.min(first.getX(), second.getX());
    int minY = Math.min(first.getY(), second.getY());
    int minZ = Math.min(first.getZ(), second.getZ());
    int maxX = Math.max(first.getX(), second.getX());
    int maxY = Math.max(first.getY(), second.getY());
    int maxZ = Math.max(first.getZ(), second.getZ());
    if (regionBlockCount(minX, minY, minZ, maxX, maxY, maxZ) > maxCaptureBlocks()) {
      player.sendMessage(
          Component.text(
                  "Capture selection is too large. Narrow the two corners or increase"
                      + " limits.animation-capture-blocks.")
              .color(NamedTextColor.RED));
      return false;
    }
    List<AnimatedVoxelPart> captured =
        captureRegion(first.getWorld(), object, minX, minY, minZ, maxX, maxY, maxZ);
    if (captured.isEmpty()) {
      return false;
    }
    putStateKeyframe(animation, state.tick, transition, captured);
    animation.lengthTicks = Math.max(animation.lengthTicks, state.tick);
    animation.sort();
    object.parts = cloneParts(captured);
    storage.save(object);
    if (!plugin.getAnimationAxeManager().isEditing(player)) {
      renderer.renderParts(object, captured);
    }
    return true;
  }

  public boolean captureEditorVoxelRegionState(
      Player player,
      World world,
      int firstX,
      int firstY,
      int firstZ,
      int firstSize,
      int secondX,
      int secondY,
      int secondZ,
      int secondSize,
      String transition) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    EditorState state = editor(player);
    if (object == null
        || animation == null
        || state == null
        || world == null
        || !world.getName().equals(object.world)) {
      return false;
    }
    int minX = Math.min(firstX, secondX);
    int minY = Math.min(firstY, secondY);
    int minZ = Math.min(firstZ, secondZ);
    int maxX = Math.max(firstX + firstSize, secondX + secondSize);
    int maxY = Math.max(firstY + firstSize, secondY + secondSize);
    int maxZ = Math.max(firstZ + firstSize, secondZ + secondSize);
    if (regionBlockCount(
            Math.floorDiv(minX, 16),
            Math.floorDiv(minY, 16),
            Math.floorDiv(minZ, 16),
            Math.floorDiv(maxX - 1, 16),
            Math.floorDiv(maxY - 1, 16),
            Math.floorDiv(maxZ - 1, 16))
        > maxCaptureBlocks()) {
      player.sendMessage(
          Component.text(
                  "Capture selection is too large. Narrow the two corners or increase"
                      + " limits.animation-capture-blocks.")
              .color(NamedTextColor.RED));
      return false;
    }
    List<AnimatedVoxelPart> captured =
        captureVoxelRegion(world, object, minX, minY, minZ, maxX, maxY, maxZ);
    if (captured.isEmpty()) {
      return false;
    }
    putStateKeyframe(animation, state.tick, transition, captured);
    animation.lengthTicks = Math.max(animation.lengthTicks, state.tick);
    animation.sort();
    object.parts = cloneParts(captured);
    storage.save(object);
    if (!plugin.getAnimationAxeManager().isEditing(player)) {
      renderer.renderParts(object, captured);
    }
    return true;
  }

  public void showEditorCapturePreview(Player player, Block first, Block second) {
    if (first == null || second == null || !first.getWorld().equals(second.getWorld())) {
      plugin.getVoxelPreviewManager().clearLive(player);
      return;
    }
    int minX = Math.min(first.getX(), second.getX());
    int minY = Math.min(first.getY(), second.getY());
    int minZ = Math.min(first.getZ(), second.getZ());
    int maxX = Math.max(first.getX(), second.getX());
    int maxY = Math.max(first.getY(), second.getY());
    int maxZ = Math.max(first.getZ(), second.getZ());
    int count = regionBlockCount(minX, minY, minZ, maxX, maxY, maxZ);
    if (count > maxPreviewBlocks()) {
      plugin
          .getVoxelPreviewManager()
          .showSelection(
              player,
              previewRegion(
                  first.getWorld(), minX, minY, minZ, maxX, maxY, maxZ, maxPreviewBlocks()));
      return;
    }
    plugin
        .getVoxelPreviewManager()
        .showSelection(
            player,
            previewRegion(first.getWorld(), minX, minY, minZ, maxX, maxY, maxZ, Integer.MAX_VALUE));
  }

  public void showEditorCaptureVoxelPreview(
      Player player,
      World world,
      int firstX,
      int firstY,
      int firstZ,
      int firstSize,
      int secondX,
      int secondY,
      int secondZ,
      int secondSize) {
    if (world == null) {
      plugin.getVoxelPreviewManager().clearLive(player);
      return;
    }
    int minX = Math.min(firstX, secondX);
    int minY = Math.min(firstY, secondY);
    int minZ = Math.min(firstZ, secondZ);
    int maxX = Math.max(firstX + firstSize, secondX + secondSize);
    int maxY = Math.max(firstY + firstSize, secondY + secondSize);
    int maxZ = Math.max(firstZ + firstSize, secondZ + secondSize);
    int baseSize = Math.max(1, Math.min(firstSize, secondSize));
    int estimate = voxelRegionCount(minX, minY, minZ, maxX, maxY, maxZ, baseSize);
    int limit = estimate > maxPreviewBlocks() ? maxPreviewBlocks() : Integer.MAX_VALUE;
    plugin
        .getVoxelPreviewManager()
        .showSelection(
            player, previewVoxelRegion(world, minX, minY, minZ, maxX, maxY, maxZ, baseSize, limit));
  }

  public boolean duplicatePreviousEditorStateKeyframe(Player player) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    EditorState state = editor(player);
    if (object == null || animation == null || state == null) {
      return false;
    }
    animation.sort();
    int targetTick = state.tick;
    if (stateKeyframeAt(animation, targetTick) != null) {
      targetTick = nextFreeStateTick(animation, targetTick + 1);
    }
    VoxelStateKeyframe source = null;
    for (VoxelStateKeyframe frame : animation.stateKeyframes) {
      if (frame.tick < targetTick) {
        source = frame;
      }
      if (frame.tick >= targetTick) {
        break;
      }
    }
    if (source == null && !animation.stateKeyframes.isEmpty()) {
      source = animation.stateKeyframes.get(animation.stateKeyframes.size() - 1);
    }
    String transition = source == null ? animation.defaultTransition : source.transition;
    List<AnimatedVoxelPart> parts = source == null ? new ArrayList<>() : cloneParts(source.parts);
    animation.stateKeyframes.add(new VoxelStateKeyframe(targetTick, transition, parts));
    animation.lengthTicks = Math.max(animation.lengthTicks, targetTick);
    animation.sort();
    state.tick = targetTick;
    object.parts = cloneParts(parts);
    storage.save(object);
    if (plugin.getAnimationAxeManager().isEditing(player)) {
      state.editBlockKeys.clear();
      state.editBlockKeys.addAll(writeFrameToWorld(object, animation, parts));
    }
    return true;
  }

  public boolean deleteEditorStateKeyframe(Player player, int tick) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    if (object == null || animation == null) {
      return false;
    }
    boolean removed = animation.stateKeyframes.removeIf(frame -> frame.tick == tick);
    if (removed) {
      storage.save(object);
    }
    return removed;
  }

  public boolean moveEditorStateKeyframe(Player player, int tick, int amount) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    EditorState state = editor(player);
    if (object == null || animation == null || state == null) {
      return false;
    }
    VoxelStateKeyframe frame = stateKeyframeAt(animation, tick);
    if (frame == null) {
      return false;
    }
    int targetTick = Math.max(0, tick + amount);
    if (targetTick != tick && stateKeyframeAt(animation, targetTick) != null) {
      return false;
    }
    frame.tick = targetTick;
    animation.lengthTicks = Math.max(animation.lengthTicks, targetTick);
    animation.sort();
    state.tick = targetTick;
    storage.save(object);
    renderEditorFrame(player);
    return true;
  }

  public boolean markWindPart(
      Player player, int worldX, int worldY, int worldZ, int size, boolean connector) {
    AnimatedVoxelObject object = editorObject(player);
    if (object == null || !"WIND".equalsIgnoreCase(object.objectType)) {
      return false;
    }
    List<AnimatedVoxelPart> windParts = currentWindEditorParts(player, object);
    String id = windPartIdAt(object, windParts, worldX, worldY, worldZ, size);
    if (id == null) {
      return false;
    }
    List<String> target = connector ? object.windConnectorPartIds : object.windNodePartIds;
    List<String> other = connector ? object.windNodePartIds : object.windConnectorPartIds;
    if (!target.contains(id)) {
      target.add(id);
    }
    other.remove(id);
    storage.save(object);
    return true;
  }

  public int markWindPoint(
      Player player, World world, int worldX, int worldY, int worldZ, int size, boolean connector) {
    AnimatedVoxelObject object = editorObject(player);
    if (object == null
        || !"WIND".equalsIgnoreCase(object.objectType)
        || world == null
        || !world.getName().equals(object.world)) {
      return 0;
    }
    List<AnimatedVoxelPart> windParts = currentWindEditorParts(player, object);
    String existingId = windPartIdAt(object, windParts, worldX, worldY, worldZ, size);
    if (existingId != null) {
      markWindPartId(object, existingId, connector);
      if (!connector) {
        autoLinkWindFabric(object, currentWindEditorParts(player, object), object.windNodePartIds);
      }
      storage.save(object);
      return 1;
    }

    List<AnimatedVoxelPart> captured =
        captureVoxelRegion(
            world, object, worldX, worldY, worldZ, worldX + size, worldY + size, worldZ + size);
    if (captured.isEmpty()) {
      return 0;
    }
    mergeWindCapturedParts(player, object, captured);
    removeClaimedWindSourceBlocks(world, object, captured);
    for (AnimatedVoxelPart part : captured) {
      markWindPartId(object, part.id, connector);
    }
    if (!connector) {
      autoLinkWindFabric(object, currentWindEditorParts(player, object), object.windNodePartIds);
    }
    storage.save(object);
    renderer.renderParts(object, object.parts);
    return captured.size();
  }

  private void markWindPartId(AnimatedVoxelObject object, String id, boolean connector) {
    List<String> target = connector ? object.windConnectorPartIds : object.windNodePartIds;
    List<String> other = connector ? object.windNodePartIds : object.windConnectorPartIds;
    if (!target.contains(id)) {
      target.add(id);
    }
    other.remove(id);
  }

  public int[] windCapturePoint(Player player) {
    AnimatedVoxelObject object = editorObject(player);
    if (object == null || !"WIND".equalsIgnoreCase(object.objectType)) {
      return null;
    }
    World world = Bukkit.getWorld(object.world);
    if (world == null || !world.equals(player.getWorld())) {
      return null;
    }
    List<AnimatedVoxelPart> parts = currentWindEditorParts(player, object);
    if (parts.isEmpty()) {
      return null;
    }
    Vector origin = player.getEyeLocation().toVector();
    Vector direction = player.getEyeLocation().getDirection().normalize();
    double best = Double.MAX_VALUE;
    AnimatedVoxelPart bestPart = null;
    for (AnimatedVoxelPart part : parts) {
      double minX = object.originX + part.localX / 16.0;
      double minY = object.originY + part.localY / 16.0;
      double minZ = object.originZ + part.localZ / 16.0;
      double size = part.size / 16.0;
      double hit =
          rayBoxDistance(
              origin, direction, minX, minY, minZ, minX + size, minY + size, minZ + size);
      if (hit >= 0.0 && hit < best && hit <= 8.0) {
        best = hit;
        bestPart = part;
      }
    }
    if (bestPart == null) {
      return null;
    }
    return new int[] {
      (int) Math.round(object.originX * 16.0) + bestPart.localX,
      (int) Math.round(object.originY * 16.0) + bestPart.localY,
      (int) Math.round(object.originZ * 16.0) + bestPart.localZ,
      bestPart.size
    };
  }

  public int markWindRegion(
      Player player,
      World world,
      int firstX,
      int firstY,
      int firstZ,
      int firstSize,
      int secondX,
      int secondY,
      int secondZ,
      int secondSize,
      boolean connector) {
    AnimatedVoxelObject object = editorObject(player);
    if (object == null
        || !"WIND".equalsIgnoreCase(object.objectType)
        || world == null
        || !world.getName().equals(object.world)) {
      return 0;
    }
    int minX = Math.min(firstX, secondX);
    int minY = Math.min(firstY, secondY);
    int minZ = Math.min(firstZ, secondZ);
    int maxX = Math.max(firstX + firstSize, secondX + secondSize);
    int maxY = Math.max(firstY + firstSize, secondY + secondSize);
    int maxZ = Math.max(firstZ + firstSize, secondZ + secondSize);
    List<String> target = connector ? object.windConnectorPartIds : object.windNodePartIds;
    List<String> other = connector ? object.windNodePartIds : object.windConnectorPartIds;
    int marked = 0;
    List<AnimatedVoxelPart> parts = currentWindEditorParts(player, object);
    for (AnimatedVoxelPart part : parts) {
      int worldPartX = (int) Math.round(object.originX * 16.0) + part.localX;
      int worldPartY = (int) Math.round(object.originY * 16.0) + part.localY;
      int worldPartZ = (int) Math.round(object.originZ * 16.0) + part.localZ;
      if (!worldPieceIntersects(
          worldPartX, worldPartY, worldPartZ, part.size, minX, minY, minZ, maxX, maxY, maxZ)) {
        continue;
      }
      if (!target.contains(part.id)) {
        target.add(part.id);
        marked++;
      }
      other.remove(part.id);
    }
    if (marked == 0) {
      List<AnimatedVoxelPart> captured =
          captureVoxelRegion(world, object, minX, minY, minZ, maxX, maxY, maxZ);
      if (!captured.isEmpty()) {
        mergeWindCapturedParts(player, object, captured);
        removeClaimedWindSourceBlocks(world, object, captured);
        for (AnimatedVoxelPart part : captured) {
          if (!target.contains(part.id)) {
            target.add(part.id);
            marked++;
          }
          other.remove(part.id);
        }
      }
    }
    if (!connector) {
      autoLinkWindFabric(object, currentWindEditorParts(player, object), target);
    }
    storage.save(object);
    return marked;
  }

  private void mergeWindCapturedParts(
      Player player, AnimatedVoxelObject object, List<AnimatedVoxelPart> captured) {
    Map<String, AnimatedVoxelPart> objectParts = partsById(object.parts);
    for (AnimatedVoxelPart part : captured) {
      objectParts.put(part.id, copyPart(part));
    }
    object.parts = new ArrayList<>(objectParts.values());

    VoxelAnimation animation = editorAnimation(player);
    EditorState state = editor(player);
    if (animation == null || state == null) {
      return;
    }
    VoxelStateKeyframe frame = stateKeyframeAt(animation, state.tick);
    if (frame == null) {
      frame =
          new VoxelStateKeyframe(state.tick, animation.defaultTransition, cloneParts(object.parts));
      animation.stateKeyframes.add(frame);
      animation.lengthTicks = Math.max(animation.lengthTicks, state.tick);
    } else {
      Map<String, AnimatedVoxelPart> frameParts = partsById(frame.parts);
      for (AnimatedVoxelPart part : captured) {
        frameParts.put(part.id, copyPart(part));
      }
      frame.parts = new ArrayList<>(frameParts.values());
    }
    animation.sort();
  }

  private void removeClaimedWindSourceBlocks(
      World world, AnimatedVoxelObject object, List<AnimatedVoxelPart> captured) {
    Map<Block, List<AnimatedVoxelPart>> byBlock = new LinkedHashMap<>();
    for (AnimatedVoxelPart part : captured) {
      Block block = blockForPart(world, object, part);
      if (block != null) {
        byBlock.computeIfAbsent(block, ignored -> new ArrayList<>()).add(part);
      }
    }
    for (Map.Entry<Block, List<AnimatedVoxelPart>> entry : byBlock.entrySet()) {
      Block block = entry.getKey();
      rememberWindSourceBlock(object, block);
      plugin.getFallingBlockManager().removeBlockDisplay(block);
      if (!plugin.getDataManager().hasCarvedData(block)) {
        block.setType(Material.AIR, false);
        continue;
      }
      List<VoxelPiece> remaining = new ArrayList<>(plugin.getDataManager().getVoxelPieces(block));
      for (AnimatedVoxelPart part : entry.getValue()) {
        int worldVoxelX = (int) Math.round(object.originX * 16.0) + part.localX;
        int worldVoxelY = (int) Math.round(object.originY * 16.0) + part.localY;
        int worldVoxelZ = (int) Math.round(object.originZ * 16.0) + part.localZ;
        int innerX = Math.floorMod(worldVoxelX, 16);
        int innerY = Math.floorMod(worldVoxelY, 16);
        int innerZ = Math.floorMod(worldVoxelZ, 16);
        remaining.removeIf(
            piece ->
                piece.x == innerX
                    && piece.y == innerY
                    && piece.z == innerZ
                    && piece.size == part.size);
      }
      if (remaining.isEmpty()) {
        plugin.getDataManager().removeCarvedBlockAndMetadata(block);
        block.setType(Material.AIR, false);
      } else {
        plugin.getDataManager().setVoxelPieces(block, remaining);
        plugin.getFallingBlockManager().updateBlockDisplay(block, null);
        CollisionBlockManager.updateCollisionBlock(
            block, remaining, plugin.getDataManager().isBlockLocked(block));
      }
    }
  }

  private void rememberWindSourceBlock(AnimatedVoxelObject object, Block block) {
    if (object.windSourceBlocks == null) {
      object.windSourceBlocks = new ArrayList<>();
    }
    String key =
        block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    for (WindSourceBlock existing : object.windSourceBlocks) {
      if (key.equals(existing.key())) {
        return;
      }
    }
    List<AnimatedVoxelPart> pieces = new ArrayList<>();
    boolean carved = plugin.getDataManager().hasCarvedData(block);
    if (carved) {
      for (VoxelPiece piece : plugin.getDataManager().getVoxelPieces(block)) {
        pieces.add(
            new AnimatedVoxelPart(
                partId(piece.x, piece.y, piece.z, piece.size),
                piece.x,
                piece.y,
                piece.z,
                piece.size,
                piece.material,
                piece.blockData));
      }
    }
    object.windSourceBlocks.add(
        new WindSourceBlock(
            block.getWorld().getName(),
            block.getX(),
            block.getY(),
            block.getZ(),
            block.getBlockData().getAsString(),
            carved,
            pieces));
  }

  private void restoreWindSourceBlocks(AnimatedVoxelObject object) {
    World world = Bukkit.getWorld(object.world);
    if (world == null) {
      return;
    }
    if (object.windSourceBlocks != null && !object.windSourceBlocks.isEmpty()) {
      restoreWindSourceBackups(object);
      return;
    }
    Map<Block, List<VoxelPiece>> voxelPiecesByBlock = new LinkedHashMap<>();
    for (AnimatedVoxelPart part : object.parts) {
      Block block = blockForPart(world, object, part);
      if (block == null) {
        continue;
      }
      if (isFullBlockPart(part)) {
        restoreFullBlock(block, part);
        continue;
      }
      int worldVoxelX = (int) Math.round(object.originX * 16.0) + part.localX;
      int worldVoxelY = (int) Math.round(object.originY * 16.0) + part.localY;
      int worldVoxelZ = (int) Math.round(object.originZ * 16.0) + part.localZ;
      VoxelPiece piece =
          new VoxelPiece(
              Math.floorMod(worldVoxelX, 16),
              Math.floorMod(worldVoxelY, 16),
              Math.floorMod(worldVoxelZ, 16),
              part.size,
              part.material,
              part.blockData);
      copyTransform(part, piece);
      if (piece.isValid()) {
        voxelPiecesByBlock.computeIfAbsent(block, ignored -> new ArrayList<>()).add(piece);
      }
    }
    for (Map.Entry<Block, List<VoxelPiece>> entry : voxelPiecesByBlock.entrySet()) {
      Block block = entry.getKey();
      Map<String, VoxelPiece> merged = new LinkedHashMap<>();
      if (plugin.getDataManager().hasCarvedData(block)) {
        for (VoxelPiece piece : plugin.getDataManager().getVoxelPieces(block)) {
          merged.put(pieceKey(piece), piece);
        }
      }
      for (VoxelPiece piece : entry.getValue()) {
        merged.put(pieceKey(piece), piece);
      }
      block.setType(Material.BARRIER, false);
      List<VoxelPiece> pieces = new ArrayList<>(merged.values());
      plugin.getDataManager().setVoxelPieces(block, pieces);
      plugin.getFallingBlockManager().updateBlockDisplay(block, null);
      CollisionBlockManager.updateCollisionBlock(
          block, pieces, plugin.getDataManager().isBlockLocked(block));
    }
  }

  private void restoreWindSourceBackups(AnimatedVoxelObject object) {
    for (WindSourceBlock source : object.windSourceBlocks) {
      World sourceWorld = Bukkit.getWorld(source.world == null ? object.world : source.world);
      if (sourceWorld == null) {
        continue;
      }
      if (source.y < sourceWorld.getMinHeight() || source.y >= sourceWorld.getMaxHeight()) {
        continue;
      }
      Block block = sourceWorld.getBlockAt(source.x, source.y, source.z);
      plugin.getFallingBlockManager().removeBlockDisplay(block);
      plugin.getDataManager().removeCarvedBlockAndMetadata(block);
      if (source.carved) {
        List<VoxelPiece> pieces = new ArrayList<>();
        if (source.pieces != null) {
          for (AnimatedVoxelPart part : source.pieces) {
            VoxelPiece piece =
                new VoxelPiece(
                    part.localX,
                    part.localY,
                    part.localZ,
                    part.size,
                    part.material,
                    part.blockData);
            copyTransform(part, piece);
            if (piece.isValid()) {
              pieces.add(piece);
            }
          }
        }
        block.setType(Material.BARRIER, false);
        plugin.getDataManager().setVoxelPieces(block, pieces);
        plugin.getFallingBlockManager().updateBlockDisplay(block, null);
        CollisionBlockManager.updateCollisionBlock(
            block, pieces, plugin.getDataManager().isBlockLocked(block));
        continue;
      }
      try {
        if (source.blockData != null && !source.blockData.isBlank()) {
          block.setBlockData(Bukkit.createBlockData(source.blockData), false);
          continue;
        }
      } catch (IllegalArgumentException ignored) {
      }
      block.setType(Material.AIR, false);
    }
  }

  private void hideClaimedSourceBlocks(AnimatedVoxelObject object) {
    if (object.windSourceBlocks == null || object.windSourceBlocks.isEmpty()) {
      return;
    }
    for (WindSourceBlock source : object.windSourceBlocks) {
      World sourceWorld = Bukkit.getWorld(source.world == null ? object.world : source.world);
      if (sourceWorld == null
          || source.y < sourceWorld.getMinHeight()
          || source.y >= sourceWorld.getMaxHeight()) {
        continue;
      }
      Block block = sourceWorld.getBlockAt(source.x, source.y, source.z);
      plugin.getFallingBlockManager().removeBlockDisplay(block);
      plugin.getDataManager().removeCarvedBlockAndMetadata(block);
      if (block.getType() == Material.BARRIER || !block.getType().isAir()) {
        block.setType(Material.AIR, false);
      }
    }
  }

  private boolean isFullBlockPart(AnimatedVoxelPart part) {
    return part.size == 16 && part.id != null && part.id.startsWith("full_");
  }

  private void restoreFullBlock(Block block, AnimatedVoxelPart part) {
    plugin.getFallingBlockManager().removeBlockDisplay(block);
    plugin.getDataManager().removeCarvedBlockAndMetadata(block);
    try {
      if (part.blockData != null && !part.blockData.isBlank()) {
        block.setBlockData(Bukkit.createBlockData(part.blockData), false);
        return;
      }
    } catch (IllegalArgumentException ignored) {
    }
    block.setType(part.getAsMaterial(), false);
  }

  private String pieceKey(VoxelPiece piece) {
    return piece.x + ":" + piece.y + ":" + piece.z + ":" + piece.size;
  }

  private void autoLinkWindFabric(
      AnimatedVoxelObject object, List<AnimatedVoxelPart> parts, List<String> selectedNodeIds) {
    Map<String, AnimatedVoxelPart> byId = partsById(parts);
    java.util.Set<String> selected = new java.util.HashSet<>(selectedNodeIds);
    for (String nodeId : selected) {
      AnimatedVoxelPart node = byId.get(nodeId);
      if (node == null) {
        continue;
      }
      for (String connectorId : object.windConnectorPartIds) {
        AnimatedVoxelPart connector = byId.get(connectorId);
        if (connector != null && windPartsTouchOrNear(connector, node)) {
          addWindLinkIfMissing(object, connector, node);
        }
      }
      for (String otherId : selected) {
        if (nodeId.equals(otherId)) {
          continue;
        }
        AnimatedVoxelPart other = byId.get(otherId);
        if (other != null && windPartsTouchOrNear(node, other)) {
          addWindLinkIfMissing(object, node, other);
        }
      }
    }
  }

  private void addWindLinkIfMissing(
      AnimatedVoxelObject object, AnimatedVoxelPart from, AnimatedVoxelPart to) {
    for (WindLink link : object.windLinks) {
      if (from.id.equals(link.fromPartId) && to.id.equals(link.toPartId)) {
        return;
      }
      if (to.id.equals(link.fromPartId) && from.id.equals(link.toPartId)) {
        return;
      }
    }
    object.windLinks.add(new WindLink(from.id, to.id, partCenterDistance(from, to)));
  }

  private boolean windPartsTouchOrNear(AnimatedVoxelPart first, AnimatedVoxelPart second) {
    double distance = partCenterDistance(first, second);
    double max = (first.size + second.size) * 0.65 + 1.0;
    return distance <= max;
  }

  private List<AnimatedVoxelPart> currentWindEditorParts(
      Player player, AnimatedVoxelObject object) {
    VoxelAnimation animation = editorAnimation(player);
    EditorState state = editor(player);
    if (animation != null && state != null) {
      List<AnimatedVoxelPart> stateParts = statePartsAt(animation, state.tick);
      if (stateParts != null && !stateParts.isEmpty()) {
        return stateParts;
      }
    }
    return object.parts;
  }

  private String windPartIdAt(
      AnimatedVoxelObject object,
      List<AnimatedVoxelPart> parts,
      int worldX,
      int worldY,
      int worldZ,
      int size) {
    String exact =
        partId(
            (int) Math.round(worldX - object.originX * 16.0),
            (int) Math.round(worldY - object.originY * 16.0),
            (int) Math.round(worldZ - object.originZ * 16.0),
            size);
    for (AnimatedVoxelPart part : parts) {
      if (part.id.equals(exact)) {
        return part.id;
      }
    }
    for (AnimatedVoxelPart part : parts) {
      int partX = (int) Math.round(object.originX * 16.0) + part.localX;
      int partY = (int) Math.round(object.originY * 16.0) + part.localY;
      int partZ = (int) Math.round(object.originZ * 16.0) + part.localZ;
      if (worldPieceIntersects(
          partX,
          partY,
          partZ,
          part.size,
          worldX,
          worldY,
          worldZ,
          worldX + size,
          worldY + size,
          worldZ + size)) {
        return part.id;
      }
    }
    return null;
  }

  public void adjustWindSetting(Player player, String setting, double amount) {
    AnimatedVoxelObject object = editorObject(player);
    if (object == null || !"WIND".equalsIgnoreCase(object.objectType)) {
      return;
    }
    switch (setting.toLowerCase(Locale.ROOT)) {
      case "displacement" ->
          object.windDisplacement = clamp(object.windDisplacement + amount, 0.05, 4.0);
      case "idle" -> object.windIdleIntensity = clamp(object.windIdleIntensity + amount, 0.0, 3.0);
      case "sun" -> object.windSunIntensity = clamp(object.windSunIntensity + amount, 0.0, 3.0);
      case "rain" -> object.windRainIntensity = clamp(object.windRainIntensity + amount, 0.0, 3.0);
      case "thunder" ->
          object.windThunderIntensity = clamp(object.windThunderIntensity + amount, 0.0, 3.0);
      case "stiffness" -> object.windStiffness = clamp(object.windStiffness + amount, 0.05, 1.0);
      case "damping" -> object.windDamping = clamp(object.windDamping + amount, 0.0, 0.95);
      case "coherence" -> object.windCoherence = clamp(object.windCoherence + amount, 0.0, 1.0);
      case "environment" ->
          object.windEnvironmentStrength = clamp(object.windEnvironmentStrength + amount, 0.0, 2.0);
      case "direction" ->
          object.windDirectionStrength = clamp(object.windDirectionStrength + amount, 0.0, 2.0);
      case "yaw" -> object.windDirectionYaw = wrapDegrees(object.windDirectionYaw + amount);
      case "players" ->
          object.windPlayerReactionStrength =
              clamp(object.windPlayerReactionStrength + amount, 0.0, 3.0);
      case "projectiles" ->
          object.windProjectileReactionStrength =
              clamp(object.windProjectileReactionStrength + amount, 0.0, 3.0);
      case "velocity" ->
          object.windVelocityReactionStrength =
              clamp(object.windVelocityReactionStrength + amount, 0.0, 3.0);
      default -> {
        return;
      }
    }
    storage.save(object);
  }

  public void toggleWindEnvironmentReactive(Player player) {
    AnimatedVoxelObject object = editorObject(player);
    if (object == null || !"WIND".equalsIgnoreCase(object.objectType)) {
      return;
    }
    object.windEnvironmentReactive = !object.windEnvironmentReactive;
    storage.save(object);
  }

  private double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }

  private double wrapDegrees(double value) {
    double wrapped = value % 360.0;
    return wrapped < 0.0 ? wrapped + 360.0 : wrapped;
  }

  public boolean extendEditorStateKeyframe(Player player, int tick, int amount) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    if (object == null || animation == null || stateKeyframeAt(animation, tick) == null) {
      return false;
    }
    animation.sort();
    boolean shifted = false;
    for (VoxelStateKeyframe frame : animation.stateKeyframes) {
      if (frame.tick > tick) {
        frame.tick += Math.max(1, amount);
        shifted = true;
      }
    }
    animation.lengthTicks =
        Math.max(playbackEndTick(animation), animation.lengthTicks + Math.max(1, amount));
    animation.sort();
    storage.save(object);
    return shifted;
  }

  public void setEditorTick(Player player, int tick) {
    EditorState state = editor(player);
    VoxelAnimation animation = editorAnimation(player);
    if (state == null || animation == null) {
      return;
    }
    state.tick = Math.max(0, Math.min(animation.lengthTicks, tick));
    renderEditorFrame(player);
  }

  public boolean loadEditorStateKeyframeForEditing(Player player, int tick) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    EditorState state = editor(player);
    if (object == null || animation == null || state == null) {
      return false;
    }
    VoxelStateKeyframe keyframe = null;
    for (VoxelStateKeyframe frame : animation.stateKeyframes) {
      if (frame.tick == tick) {
        keyframe = frame;
        break;
      }
    }
    if (keyframe == null) {
      return false;
    }
    state.tick = Math.max(0, Math.min(animation.lengthTicks, tick));
    renderer.remove(object.id);
    state.editBlockKeys.clear();
    state.editBlockKeys.addAll(writeFrameToWorld(object, animation, keyframe.parts));
    return true;
  }

  public boolean selectEditorObject(Player player, String objectId) {
    AnimatedVoxelObject object = storage.get(objectId);
    if (object == null) {
      return false;
    }
    editors.put(playerKey(player), new EditorState(object.id, "main", 0));
    return true;
  }

  public boolean cycleEditorState(Player player) {
    return cycleEditorState(player, 1);
  }

  public boolean cycleEditorState(Player player, int direction) {
    AnimatedVoxelObject object = editorObject(player);
    EditorState state = editor(player);
    if (object == null || state == null || object.animations.isEmpty()) {
      return false;
    }
    int index = 0;
    for (int i = 0; i < object.animations.size(); i++) {
      if (object.animations.get(i).id.equalsIgnoreCase(state.animationId)) {
        index = i;
        break;
      }
    }
    state.animationId =
        object.animations.get(Math.floorMod(index + direction, object.animations.size())).id;
    state.tick = 0;
    renderEditorFrame(player);
    return true;
  }

  public boolean createEditorState(Player player) {
    return createEditorState(player, null);
  }

  public boolean createEditorState(Player player, String requestedId) {
    AnimatedVoxelObject object = editorObject(player);
    if (object == null) {
      return false;
    }
    String id = sanitizeId(requestedId);
    if (id == null) {
      int next = object.animations.size() + 1;
      id = "state_" + next;
      while (object.animation(id) != null) {
        next++;
        id = "state_" + next;
      }
    } else if (object.animation(id) != null) {
      return false;
    }
    VoxelAnimation animation = new VoxelAnimation(id);
    animation.displayName = id;
    animation.lengthTicks =
        Math.max(40, editorAnimation(player) == null ? 40 : editorAnimation(player).lengthTicks);
    animation.loop = true;
    animation.stateKeyframes.add(new VoxelStateKeyframe(0, "HARD", cloneParts(object.parts)));
    object.animations.add(animation);
    EditorState state = editor(player);
    if (state != null) {
      state.animationId = id;
      state.tick = 0;
    }
    storage.save(object);
    return true;
  }

  public boolean deleteEditorState(Player player) {
    AnimatedVoxelObject object = editorObject(player);
    EditorState state = editor(player);
    if (object == null || state == null || object.animations.size() <= 1) {
      return false;
    }
    boolean removed =
        object.animations.removeIf(animation -> animation.id.equalsIgnoreCase(state.animationId));
    if (removed) {
      state.animationId = object.animations.get(0).id;
      state.tick = 0;
      storage.save(object);
    }
    return removed;
  }

  public void cycleEditorTrigger(Player player) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    if (object == null || animation == null) {
      return;
    }
    animation.trigger =
        switch (animation.trigger.toUpperCase(Locale.ROOT)) {
          case "PLAYER_DISTANCE", "PLAYER_ENTER" -> "REDSTONE";
          case "REDSTONE" -> "MANUAL";
          default -> "PLAYER_DISTANCE";
        };
    storage.save(object);
  }

  public boolean configureTrigger(
      String objectId,
      String animationId,
      String trigger,
      Integer radius,
      Integer playerCount,
      Block redstoneBlock) {
    AnimatedVoxelObject object = storage.get(objectId);
    if (object == null) {
      return false;
    }
    VoxelAnimation animation =
        ensureAnimation(
            object, animationId == null || animationId.isBlank() ? "main" : animationId);
    animation.trigger = trigger == null ? "MANUAL" : trigger.toUpperCase(Locale.ROOT);
    if (radius != null) {
      animation.triggerRadius = Math.max(1, Math.min(128, radius));
    }
    if (playerCount != null) {
      animation.triggerPlayerCount = Math.max(1, Math.min(100, playerCount));
    }
    if (redstoneBlock != null) {
      animation.redstoneWorld = redstoneBlock.getWorld().getName();
      animation.redstoneX = redstoneBlock.getX();
      animation.redstoneY = redstoneBlock.getY();
      animation.redstoneZ = redstoneBlock.getZ();
    }
    storage.save(object);
    ensureTriggerTask();
    return true;
  }

  public boolean configureProbabilityTrigger(
      String objectId,
      String animationId,
      double chancePercent,
      int intervalTicks,
      Integer cooldownTicks) {
    AnimatedVoxelObject object = storage.get(objectId);
    if (object == null) {
      return false;
    }
    VoxelAnimation animation =
        ensureAnimation(
            object, animationId == null || animationId.isBlank() ? "main" : animationId);
    animation.trigger = "PROBABILITY";
    AnimationTriggerCause cause = ensureTriggerCause(animation, "PROBABILITY");
    cause.chance = clamp(chancePercent / 100.0, 0.0, 1.0);
    cause.intervalTicks = Math.max(1, Math.min(20 * 60 * 60, intervalTicks));
    if (cooldownTicks != null) {
      cause.cooldownTicks = Math.max(0, Math.min(20 * 60 * 60, cooldownTicks));
    }
    storage.save(object);
    ensureTriggerTask();
    return true;
  }

  public boolean configureEntityTrigger(
      String objectId,
      String animationId,
      int radius,
      boolean includeInvisibleEntities,
      Integer cooldownTicks) {
    AnimatedVoxelObject object = storage.get(objectId);
    if (object == null) {
      return false;
    }
    VoxelAnimation animation =
        ensureAnimation(
            object, animationId == null || animationId.isBlank() ? "main" : animationId);
    animation.trigger = "ENTITY_NEARBY";
    AnimationTriggerCause cause = ensureTriggerCause(animation, "ENTITY_NEARBY");
    cause.radius = Math.max(1, Math.min(128, radius));
    cause.includeInvisibleEntities = includeInvisibleEntities;
    if (cooldownTicks != null) {
      cause.cooldownTicks = Math.max(0, Math.min(20 * 60 * 60, cooldownTicks));
    }
    storage.save(object);
    ensureTriggerTask();
    return true;
  }

  public boolean configureAnimationSound(
      String objectId, String animationId, String soundName, float volume, float pitch) {
    AnimatedVoxelObject object = storage.get(objectId);
    if (object == null) {
      return false;
    }
    VoxelAnimation animation =
        ensureAnimation(
            object, animationId == null || animationId.isBlank() ? "main" : animationId);
    animation.sound =
        soundName == null || soundName.isBlank() ? null : soundName.toUpperCase(Locale.ROOT);
    animation.soundVolume = (float) clamp(volume, 0.0, 16.0);
    animation.soundPitch = (float) clamp(pitch, 0.1, 2.0);
    storage.save(object);
    return true;
  }

  public boolean configureSequence(
      String objectId, String animationId, String nextAnimationId, String afterAction) {
    AnimatedVoxelObject object = storage.get(objectId);
    if (object == null) {
      return false;
    }
    VoxelAnimation animation =
        ensureAnimation(
            object, animationId == null || animationId.isBlank() ? "main" : animationId);
    AnimationTriggerCause cause = ensureTriggerCause(animation, selectedTriggerType(animation));
    String action =
        afterAction == null || afterAction.isBlank()
            ? "NEXT"
            : afterAction.toUpperCase(Locale.ROOT);
    if (!List.of("DEFAULT", "REVERSE", "NEXT", "NOTHING").contains(action)) {
      return false;
    }
    if ("NEXT".equals(action)
        && (nextAnimationId == null || object.animation(nextAnimationId) == null)) {
      return false;
    }
    cause.afterAction = action;
    cause.nextAnimationId = "NEXT".equals(action) ? nextAnimationId : null;
    storage.save(object);
    return true;
  }

  public void setEditorTrigger(Player player, String trigger) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    if (object == null || animation == null) {
      return;
    }
    animation.trigger = trigger.toUpperCase(Locale.ROOT);
    ensureTriggerCause(animation, animation.trigger);
    storage.save(object);
    ensureTriggerTask();
  }

  public void adjustEditorTriggerRadius(Player player, int amount) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    if (object == null || animation == null) {
      return;
    }
    animation.triggerRadius = Math.max(1, Math.min(128, animation.triggerRadius + amount));
    ensureTriggerCause(animation, "PLAYER_DISTANCE").radius = animation.triggerRadius;
    ensureTriggerCause(animation, "ENTITY_NEARBY").radius = animation.triggerRadius;
    storage.save(object);
  }

  public void toggleEditorTriggerInvisibleEntities(Player player) {
    AnimatedVoxelObject object = editorObject(player);
    AnimationTriggerCause cause = editorTriggerCause(player);
    if (object == null || cause == null) {
      return;
    }
    cause.includeInvisibleEntities = !cause.includeInvisibleEntities;
    storage.save(object);
  }

  public void adjustEditorTriggerPlayers(Player player, int amount) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    if (object == null || animation == null) {
      return;
    }
    animation.triggerPlayerCount =
        Math.max(1, Math.min(100, animation.triggerPlayerCount + amount));
    ensureTriggerCause(animation, "PLAYER_DISTANCE").playerCount = animation.triggerPlayerCount;
    storage.save(object);
  }

  public AnimationTriggerCause editorTriggerCause(Player player) {
    VoxelAnimation animation = editorAnimation(player);
    if (animation == null) {
      return null;
    }
    return ensureTriggerCause(animation, selectedTriggerType(animation));
  }

  public void adjustEditorTriggerCooldown(Player player, int amount) {
    AnimatedVoxelObject object = editorObject(player);
    AnimationTriggerCause cause = editorTriggerCause(player);
    if (object == null || cause == null) {
      return;
    }
    cause.cooldownTicks = Math.max(0, Math.min(20 * 60 * 10, cause.cooldownTicks + amount));
    storage.save(object);
  }

  public void cycleEditorTriggerAfterAction(Player player) {
    cycleEditorTriggerAfterAction(player, 1);
  }

  public void cycleEditorTriggerAfterAction(Player player, int direction) {
    AnimatedVoxelObject object = editorObject(player);
    AnimationTriggerCause cause = editorTriggerCause(player);
    if (object == null || cause == null) {
      return;
    }
    List<String> values = List.of("DEFAULT", "REVERSE", "NEXT", "NOTHING");
    String current =
        (cause.afterAction == null ? "DEFAULT" : cause.afterAction).toUpperCase(Locale.ROOT);
    cause.afterAction =
        values.get(Math.floorMod(values.indexOf(current) + direction, values.size()));
    storage.save(object);
  }

  public void cycleEditorTriggerNextAnimation(Player player) {
    cycleEditorTriggerNextAnimation(player, 1);
  }

  public void cycleEditorTriggerNextAnimation(Player player, int direction) {
    AnimatedVoxelObject object = editorObject(player);
    AnimationTriggerCause cause = editorTriggerCause(player);
    if (object == null || cause == null || object.animations.isEmpty()) {
      return;
    }
    int current = -1;
    for (int i = 0; i < object.animations.size(); i++) {
      if (object
          .animations
          .get(i)
          .id
          .equalsIgnoreCase(cause.nextAnimationId == null ? "" : cause.nextAnimationId)) {
        current = i;
        break;
      }
    }
    cause.nextAnimationId =
        object.animations.get(Math.floorMod(current + direction, object.animations.size())).id;
    storage.save(object);
  }

  public boolean linkEditorRedstoneTrigger(Player player, Block block) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    if (object == null || animation == null || block == null) {
      return false;
    }
    animation.trigger = "REDSTONE";
    animation.redstoneWorld = block.getWorld().getName();
    animation.redstoneX = block.getX();
    animation.redstoneY = block.getY();
    animation.redstoneZ = block.getZ();
    if (animation.triggerCauses == null) {
      animation.triggerCauses = new ArrayList<>();
    }
    AnimationTriggerCause cause = new AnimationTriggerCause("REDSTONE");
    cause.world = block.getWorld().getName();
    cause.x = block.getX();
    cause.y = block.getY();
    cause.z = block.getZ();
    AnimationTriggerCause template = ensureTriggerCause(animation, "REDSTONE");
    cause.cooldownTicks = template.cooldownTicks;
    cause.afterAction = template.afterAction;
    cause.nextAnimationId = template.nextAnimationId;
    animation.triggerCauses.add(cause);
    storage.save(object);
    ensureTriggerTask();
    return true;
  }

  public void clearEditorTriggerLinks(Player player) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    if (object == null || animation == null) {
      return;
    }
    if (animation.triggerCauses != null) {
      animation.triggerCauses.clear();
    }
    animation.redstoneWorld = null;
    animation.redstoneX = null;
    animation.redstoneY = null;
    animation.redstoneZ = null;
    storage.save(object);
  }

  private AnimationTriggerCause ensureTriggerCause(VoxelAnimation animation, String type) {
    if (animation.triggerCauses == null) {
      animation.triggerCauses = new ArrayList<>();
    }
    for (AnimationTriggerCause cause : animation.triggerCauses) {
      if (type.equalsIgnoreCase(cause.type)) {
        return cause;
      }
    }
    AnimationTriggerCause cause = new AnimationTriggerCause(type);
    cause.radius = animation.triggerRadius;
    cause.playerCount = animation.triggerPlayerCount;
    animation.triggerCauses.add(cause);
    return cause;
  }

  private String selectedTriggerType(VoxelAnimation animation) {
    String trigger =
        animation.trigger == null ? "MANUAL" : animation.trigger.toUpperCase(Locale.ROOT);
    return "PLAYER_ENTER".equals(trigger) ? "PLAYER_DISTANCE" : trigger;
  }

  public void cycleEditorDefaultTransition(Player player) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    if (object == null || animation == null) {
      return;
    }
    animation.defaultTransition =
        "SOFT".equalsIgnoreCase(animation.defaultTransition) ? "HARD" : "SOFT";
    storage.save(object);
  }

  public void cycleEditorCaptureMode(Player player) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    if (object == null || animation == null) {
      return;
    }
    animation.captureMode = "ADD".equalsIgnoreCase(animation.captureMode) ? "REPLACE" : "ADD";
    storage.save(object);
  }

  public void adjustEditorPriority(Player player, int amount) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    if (object == null || animation == null) {
      return;
    }
    animation.priority = Math.max(0, Math.min(100, animation.priority + amount));
    storage.save(object);
  }

  public EditorState editor(Player player) {
    EditorState state = editors.get(playerKey(player));
    if (state != null && storage.get(state.objectId) != null) {
      return state;
    }
    AnimatedVoxelObject first = storage.all().stream().findFirst().orElse(null);
    if (first == null) {
      return null;
    }
    state = new EditorState(first.id, "main", 0);
    editors.put(playerKey(player), state);
    return state;
  }

  public AnimatedVoxelObject editorObject(Player player) {
    EditorState state = editor(player);
    return state == null ? null : storage.get(state.objectId);
  }

  public VoxelAnimation editorAnimation(Player player) {
    AnimatedVoxelObject object = editorObject(player);
    EditorState state = editor(player);
    if (object == null || state == null) {
      return null;
    }
    return ensureAnimation(object, state.animationId);
  }

  public void adjustEditorTick(Player player, int amount) {
    EditorState state = editor(player);
    VoxelAnimation animation = editorAnimation(player);
    if (state == null || animation == null) {
      return;
    }
    state.tick = Math.max(0, Math.min(animation.lengthTicks, state.tick + amount));
    renderEditorFrame(player);
  }

  public void adjustEditorLength(Player player, int amount) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    EditorState state = editor(player);
    if (object == null || animation == null || state == null) {
      return;
    }
    animation.lengthTicks = Math.max(1, animation.lengthTicks + amount);
    state.tick = Math.min(state.tick, animation.lengthTicks);
    storage.save(object);
  }

  public void toggleEditorLoop(Player player) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    if (object == null || animation == null) {
      return;
    }
    animation.loop = !animation.loop;
    storage.save(object);
  }

  public void toggleEditorStateWind(Player player) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    if (object == null || animation == null) {
      return;
    }
    animation.windEnabled = !animation.windEnabled;
    storage.save(object);
    renderEditorFrame(player);
  }

  public void cycleEditorSpeed(Player player) {
    cycleEditorSpeed(player, 1);
  }

  public void cycleEditorSpeed(Player player, int direction) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    if (object == null || animation == null) {
      return;
    }
    List<Double> values = List.of(0.5, 1.0, 2.0, 4.0);
    int index = 0;
    double distance = Double.MAX_VALUE;
    for (int i = 0; i < values.size(); i++) {
      double candidate = Math.abs(values.get(i) - animation.playbackSpeed);
      if (candidate < distance) {
        distance = candidate;
        index = i;
      }
    }
    animation.playbackSpeed = values.get(Math.floorMod(index + direction, values.size()));
    storage.save(object);
  }

  public boolean addEditorKeyframeHere(Player player) {
    AnimatedVoxelObject object = editorObject(player);
    EditorState state = editor(player);
    if (object == null || state == null) {
      return false;
    }
    Location here = player.getLocation();
    return addKeyframe(
        object.id,
        state.animationId,
        new AnimationKeyframe(
            state.tick,
            here.getX() - object.originX,
            here.getY() - object.originY,
            here.getZ() - object.originZ,
            here.getYaw()));
  }

  public boolean playEditor(Player player) {
    EditorState state = editor(player);
    return state != null && play(state.objectId, state.animationId);
  }

  public boolean addEditorAnimationToDefaultSequence(Player player) {
    EditorState state = editor(player);
    AnimatedVoxelObject object = editorObject(player);
    if (state == null || object == null || object.animation(state.animationId) == null) {
      return false;
    }
    AnimationSequence sequence = ensureSequence(object, "main");
    sequence.steps.add(new AnimationSequenceStep(state.animationId, 0, 0));
    storage.save(object);
    return true;
  }

  public boolean playEditorSequence(Player player) {
    EditorState state = editor(player);
    return state != null && playSequence(state.objectId, "main");
  }

  public String editorSequenceStatus(Player player) {
    AnimatedVoxelObject object = editorObject(player);
    if (object == null) {
      return "Select an animation object first";
    }
    AnimationSequence sequence = ensureSequence(object, "main");
    return sequence.steps.size() + " step(s) in sequence";
  }

  public AnimationSequence editorSequence(Player player) {
    AnimatedVoxelObject object = editorObject(player);
    if (object == null) {
      return new AnimationSequence("main");
    }
    return ensureSequence(object, "main");
  }

  public boolean addEditorSequenceStep(Player player, String animationId) {
    AnimatedVoxelObject object = editorObject(player);
    if (object == null || object.animation(animationId) == null) {
      return false;
    }
    ensureSequence(object, "main").steps.add(new AnimationSequenceStep(animationId, 0, 0));
    storage.save(object);
    return true;
  }

  public boolean removeEditorSequenceStep(Player player, int index) {
    AnimatedVoxelObject object = editorObject(player);
    if (object == null) {
      return false;
    }
    AnimationSequence sequence = ensureSequence(object, "main");
    if (index < 0 || index >= sequence.steps.size()) {
      return false;
    }
    sequence.steps.remove(index);
    storage.save(object);
    return true;
  }

  public boolean moveEditorSequenceStep(Player player, int index, int amount) {
    AnimatedVoxelObject object = editorObject(player);
    if (object == null) {
      return false;
    }
    AnimationSequence sequence = ensureSequence(object, "main");
    int target = index + amount;
    if (index < 0
        || index >= sequence.steps.size()
        || target < 0
        || target >= sequence.steps.size()) {
      return false;
    }
    AnimationSequenceStep step = sequence.steps.remove(index);
    sequence.steps.add(target, step);
    storage.save(object);
    return true;
  }

  public boolean cycleEditorSequenceStepTrigger(Player player, int index) {
    AnimationSequenceStep step = editorSequenceStep(player, index);
    if (step == null) {
      return false;
    }
    String current = normalizeSequenceTrigger(step.triggerType);
    step.triggerType =
        switch (current == null ? "AUTO" : current) {
          case "AUTO" -> "PLAYER_NEAR";
          case "PLAYER_NEAR" -> "PLAYER_AWAY";
          case "PLAYER_AWAY" -> "ENTITY_NEAR";
          case "ENTITY_NEAR" -> "ENTITY_AWAY";
          default -> "AUTO";
        };
    storage.save(editorObject(player));
    return true;
  }

  public boolean setEditorSequenceStepTrigger(Player player, int index, String triggerType) {
    AnimationSequenceStep step = editorSequenceStep(player, index);
    AnimatedVoxelObject object = editorObject(player);
    String type = normalizeSequenceTrigger(triggerType);
    if (step == null || object == null || type == null) {
      return false;
    }
    step.triggerType = type;
    storage.save(object);
    return true;
  }

  public boolean toggleEditorSequenceStepWait(Player player, int index) {
    AnimationSequenceStep step = editorSequenceStep(player, index);
    AnimatedVoxelObject object = editorObject(player);
    if (step == null || object == null) {
      return false;
    }
    step.waitForCompletion = !step.waitForCompletion;
    storage.save(object);
    return true;
  }

  public boolean toggleEditorSequenceStepInvisibleEntities(Player player, int index) {
    AnimationSequenceStep step = editorSequenceStep(player, index);
    AnimatedVoxelObject object = editorObject(player);
    if (step == null || object == null) {
      return false;
    }
    step.includeInvisibleEntities = !step.includeInvisibleEntities;
    storage.save(object);
    return true;
  }

  public boolean adjustEditorSequenceStepDelay(
      Player player, int index, boolean before, int amount) {
    AnimationSequenceStep step = editorSequenceStep(player, index);
    AnimatedVoxelObject object = editorObject(player);
    if (step == null || object == null) {
      return false;
    }
    if (before) {
      step.delayBeforeTicks = Math.max(0, Math.min(20 * 60 * 10, step.delayBeforeTicks + amount));
    } else {
      step.delayAfterTicks = Math.max(0, Math.min(20 * 60 * 10, step.delayAfterTicks + amount));
    }
    storage.save(object);
    return true;
  }

  public boolean adjustEditorSequenceStepRadius(Player player, int index, int amount) {
    AnimationSequenceStep step = editorSequenceStep(player, index);
    AnimatedVoxelObject object = editorObject(player);
    if (step == null || object == null) {
      return false;
    }
    step.triggerRadius = Math.max(1, Math.min(128, step.triggerRadius + amount));
    storage.save(object);
    return true;
  }

  public boolean adjustEditorSequenceStepPlayerCount(Player player, int index, int amount) {
    AnimationSequenceStep step = editorSequenceStep(player, index);
    AnimatedVoxelObject object = editorObject(player);
    if (step == null || object == null) {
      return false;
    }
    step.triggerPlayerCount = Math.max(1, Math.min(100, step.triggerPlayerCount + amount));
    storage.save(object);
    return true;
  }

  private AnimationSequenceStep editorSequenceStep(Player player, int index) {
    AnimatedVoxelObject object = editorObject(player);
    if (object == null) {
      return null;
    }
    AnimationSequence sequence = ensureSequence(object, "main");
    if (index < 0 || index >= sequence.steps.size()) {
      return null;
    }
    return sequence.steps.get(index);
  }

  public boolean toggleEditorSequenceLoop(Player player) {
    AnimatedVoxelObject object = editorObject(player);
    if (object == null) {
      return false;
    }
    AnimationSequence sequence = ensureSequence(object, "main");
    sequence.loop = !sequence.loop;
    storage.save(object);
    return true;
  }

  public int animationVoxelCount(AnimatedVoxelObject object, VoxelAnimation animation) {
    int count = object.parts == null ? 0 : object.parts.size();
    if (animation != null && animation.stateKeyframes != null) {
      for (VoxelStateKeyframe frame : animation.stateKeyframes) {
        count = Math.max(count, frame.parts == null ? 0 : frame.parts.size());
      }
    }
    return count;
  }

  public int animationBlockCount(AnimatedVoxelObject object, VoxelAnimation animation) {
    int count = 0;
    if (animation != null && animation.stateKeyframes != null) {
      for (VoxelStateKeyframe frame : animation.stateKeyframes) {
        count = Math.max(count, frame.blocks == null ? 0 : frame.blocks.size());
      }
    }
    return count;
  }

  public boolean configureSequenceStep(
      String objectId, String sequenceId, int index, int beforeTicks, int afterTicks) {
    AnimatedVoxelObject object = storage.get(objectId);
    if (object == null) {
      return false;
    }
    AnimationSequence sequence =
        ensureSequence(object, sequenceId == null || sequenceId.isBlank() ? "main" : sequenceId);
    if (index < 1 || index > sequence.steps.size()) {
      return false;
    }
    AnimationSequenceStep step = sequence.steps.get(index - 1);
    step.delayBeforeTicks = Math.max(0, Math.min(20 * 60 * 60, beforeTicks));
    step.delayAfterTicks = Math.max(0, Math.min(20 * 60 * 60, afterTicks));
    storage.save(object);
    return true;
  }

  public boolean configureSequenceStepTrigger(
      String objectId,
      String sequenceId,
      int index,
      String triggerType,
      Integer radius,
      Integer playerCount,
      Boolean includeInvisibleEntities) {
    AnimatedVoxelObject object = storage.get(objectId);
    if (object == null) {
      return false;
    }
    AnimationSequence sequence =
        ensureSequence(object, sequenceId == null || sequenceId.isBlank() ? "main" : sequenceId);
    if (index < 1 || index > sequence.steps.size()) {
      return false;
    }
    String type = normalizeSequenceTrigger(triggerType);
    if (type == null) {
      return false;
    }
    AnimationSequenceStep step = sequence.steps.get(index - 1);
    step.triggerType = type;
    if (radius != null) {
      step.triggerRadius = Math.max(1, Math.min(128, radius));
    }
    if (playerCount != null) {
      step.triggerPlayerCount = Math.max(1, Math.min(100, playerCount));
    }
    if (includeInvisibleEntities != null) {
      step.includeInvisibleEntities = includeInvisibleEntities;
    }
    storage.save(object);
    ensureTriggerTask();
    return true;
  }

  public boolean configureSequenceStepWait(
      String objectId, String sequenceId, int index, boolean waitForCompletion) {
    AnimatedVoxelObject object = storage.get(objectId);
    if (object == null) {
      return false;
    }
    AnimationSequence sequence =
        ensureSequence(object, sequenceId == null || sequenceId.isBlank() ? "main" : sequenceId);
    if (index < 1 || index > sequence.steps.size()) {
      return false;
    }
    sequence.steps.get(index - 1).waitForCompletion = waitForCompletion;
    storage.save(object);
    return true;
  }

  public boolean setSequenceLoop(String objectId, String sequenceId, boolean loop) {
    AnimatedVoxelObject object = storage.get(objectId);
    if (object == null) {
      return false;
    }
    ensureSequence(object, sequenceId == null || sequenceId.isBlank() ? "main" : sequenceId).loop =
        loop;
    storage.save(object);
    return true;
  }

  public String importImageFrame(
      Player player,
      String objectId,
      String animationId,
      File file,
      int widthPixels,
      Integer heightPixels,
      int pixelSize,
      int tick,
      String plane) {
    if (!file.exists() || widthPixels < 1 || pixelSize < 1) {
      return null;
    }
    try {
      BufferedImage image = ImageIO.read(file);
      if (image == null) {
        return null;
      }
      int targetWidth = Math.max(1, Math.min(256, widthPixels));
      int targetHeight =
          heightPixels == null
              ? Math.max(
                  1,
                  (int) Math.round(targetWidth * (image.getHeight() / (double) image.getWidth())))
              : Math.max(1, Math.min(256, heightPixels));
      int size = Math.max(1, Math.min(16, pixelSize));

      AnimatedVoxelObject object = storage.get(objectId);
      if (object == null) {
        if (!storage.isValidId(objectId)) {
          return null;
        }
        Location origin = player.getLocation();
        object =
            new AnimatedVoxelObject(
                objectId,
                player.getWorld().getName(),
                origin.getBlockX() + 0.5,
                origin.getBlockY(),
                origin.getBlockZ() + 0.5);
        storage.put(object);
      }
      VoxelAnimation animation =
          ensureAnimation(
              object, animationId == null || animationId.isBlank() ? "main" : animationId);
      List<AnimatedVoxelPart> parts = new ArrayList<>();
      String axis = plane == null ? "XY" : plane.toUpperCase(Locale.ROOT);
      int index = 0;
      for (int y = 0; y < targetHeight; y++) {
        for (int x = 0; x < targetWidth; x++) {
          int sourceX =
              Math.min(
                  image.getWidth() - 1,
                  (int) Math.floor(x * image.getWidth() / (double) targetWidth));
          int sourceY =
              Math.min(
                  image.getHeight() - 1,
                  (int) Math.floor(y * image.getHeight() / (double) targetHeight));
          int argb = image.getRGB(sourceX, sourceY);
          int alpha = (argb >>> 24) & 0xff;
          if (alpha < 128) {
            continue;
          }
          int localX;
          int localY;
          int localZ;
          if ("XZ".equals(axis)) {
            localX = x * size;
            localY = 0;
            localZ = y * size;
          } else if ("ZY".equals(axis) || "YZ".equals(axis)) {
            localX = 0;
            localY = (targetHeight - 1 - y) * size;
            localZ = x * size;
          } else {
            localX = x * size;
            localY = (targetHeight - 1 - y) * size;
            localZ = 0;
          }
          parts.add(
              new AnimatedVoxelPart(
                  "img_" + tick + "_" + index++,
                  localX,
                  localY,
                  localZ,
                  size,
                  nearestImageMaterial(argb),
                  null));
        }
      }
      animation.stateKeyframes.removeIf(frame -> frame.tick == tick);
      animation.stateKeyframes.add(new VoxelStateKeyframe(Math.max(0, tick), "HARD", parts));
      animation.lengthTicks = Math.max(animation.lengthTicks, Math.max(1, tick));
      animation.sort();
      storage.save(object);
      renderer.renderParts(object, parts);
      return targetWidth + ":" + targetHeight;
    } catch (IOException ex) {
      plugin.getLogger().warning("Could not import image frame " + file + ": " + ex.getMessage());
      return null;
    }
  }

  public String queueEditorImage(
      Player player, String name, String url, Integer startTick, Integer endTick) {
    EditorState state = editor(player);
    if (state == null || editorObject(player) == null || editorAnimation(player) == null) {
      return null;
    }
    try {
      List<ImageFrame> frames = optimizeImageFrames(readImageFrames(url));
      if (frames.isEmpty()) {
        return "unsupported";
      }
      String cleanName = cleanImageName(name);
      pendingImages.put(
          playerKey(player), new PendingImage(cleanName, url, frames, startTick, endTick));
      BufferedImage first = frames.get(0).image;
      String recommended = recommendedImageSurface(first);
      return first.getWidth()
          + ":"
          + first.getHeight()
          + ":"
          + frames.size()
          + ":"
          + cleanName
          + ":"
          + recommended;
    } catch (Exception ex) {
      plugin
          .getLogger()
          .warning("Could not queue image import from " + url + ": " + ex.getMessage());
      return null;
    }
  }

  public String queuedImageName(Player player) {
    PendingImage pending = pendingImages.get(playerKey(player));
    return pending == null ? null : pending.name;
  }

  public boolean hasPendingImage(Player player) {
    return pendingImages.containsKey(playerKey(player));
  }

  public String importPendingImageFrame(
      Player player,
      String worldName,
      int firstX,
      int firstY,
      int firstZ,
      int firstSize,
      int secondX,
      int secondY,
      int secondZ,
      int secondSize) {
    PendingImage pending = pendingImages.get(playerKey(player));
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    EditorState state = editor(player);
    if (pending == null || object == null || animation == null || state == null) {
      return null;
    }
    int minX = Math.min(firstX, secondX);
    int minY = Math.min(firstY, secondY);
    int minZ = Math.min(firstZ, secondZ);
    int maxX = Math.max(firstX + firstSize, secondX + secondSize) - 1;
    int maxY = Math.max(firstY + firstSize, secondY + secondSize) - 1;
    int maxZ = Math.max(firstZ + firstSize, secondZ + secondSize) - 1;
    int spanX = Math.max(1, maxX - minX + 1);
    int spanY = Math.max(1, maxY - minY + 1);
    int spanZ = Math.max(1, maxZ - minZ + 1);
    int gridSize = Math.max(1, Math.min(16, Math.min(firstSize, secondSize)));

    String plane;
    int widthUnits;
    int heightUnits;
    if (spanZ <= spanX && spanZ <= spanY) {
      plane = "XY";
      widthUnits = spanX;
      heightUnits = spanY;
    } else if (spanY <= spanX && spanY <= spanZ) {
      plane = "XZ";
      widthUnits = spanX;
      heightUnits = spanZ;
    } else {
      plane = "ZY";
      widthUnits = spanZ;
      heightUnits = spanY;
    }

    BufferedImage firstImage = pending.frames.get(0).image;
    double ratio = firstImage.getWidth() / (double) Math.max(1, firstImage.getHeight());
    int fittedWidth = widthUnits;
    int fittedHeight = Math.max(1, (int) Math.round(fittedWidth / ratio));
    if (fittedHeight > heightUnits) {
      fittedHeight = heightUnits;
      fittedWidth = Math.max(1, (int) Math.round(fittedHeight * ratio));
    }
    int columns = Math.max(1, Math.min(64, (int) Math.ceil(fittedWidth / 16.0)));
    int rows = Math.max(1, Math.min(64, (int) Math.ceil(fittedHeight / 16.0)));
    int targetWidth = columns * 16;
    int targetHeight = rows * 16;
    int startX = minX + Math.max(0, (widthUnits - targetWidth) / 2);
    int startY = minY + Math.max(0, (heightUnits - targetHeight) / 2);
    int startZ = minZ;
    if ("XZ".equals(plane)) {
      startY = minY;
      startZ = minZ + Math.max(0, (heightUnits - targetHeight) / 2);
    } else if ("ZY".equals(plane)) {
      startX = minX;
      startZ = minZ + Math.max(0, (widthUnits - targetWidth) / 2);
      startY = minY + Math.max(0, (heightUnits - targetHeight) / 2);
    }

    int firstTick = pending.startTick == null ? state.tick : Math.max(0, pending.startTick);
    int naturalDuration = naturalImageDurationTicks(pending.frames);
    int lastTick =
        pending.endTick == null
            ? firstTick + naturalDuration
            : Math.max(firstTick, pending.endTick);
    int frames = pending.frames.size();
    if (frames > 1 && lastTick - firstTick < frames - 1) {
      lastTick = firstTick + frames - 1;
    }
    removeImageLayer(animation, pending.name);
    removeImageFrameLayer(animation, pending.name);
    int naturalCursor = 0;
    for (int frameIndex = 0; frameIndex < frames; frameIndex++) {
      int tick;
      if (frames == 1) {
        tick = firstTick;
      } else if (pending.endTick == null && naturalDuration > 0) {
        tick = firstTick + naturalCursor;
        naturalCursor += Math.max(1, pending.frames.get(frameIndex).delayTicks);
      } else {
        tick =
            firstTick
                + (int)
                    Math.round(
                        (lastTick - firstTick) * (frameIndex / (double) Math.max(1, frames - 1)));
      }
      // Each selected block face is a true 16x16 voxel canvas. A pixel
      // becomes one 1/16-block voxel; no map or ItemFrame entities are
      // created. Larger selections simply tile these 16x16 canvases.
      List<AnimatedVoxelPart> parts =
          imagePartsFor(
              pending,
              pending.frames.get(frameIndex).image,
              object,
              plane,
              startX,
              startY,
              startZ,
              targetWidth,
              targetHeight,
              1,
              frameIndex);
      putImageLayer(animation, tick, pending.name, parts);
    }
    animation.lengthTicks = Math.max(animation.lengthTicks, Math.max(1, lastTick));
    animation.sort();
    storage.save(object);
    List<AnimatedVoxelPart> preview = statePartsAt(animation, firstTick);
    if (preview != null) {
      renderer.renderParts(object, preview);
    }
    removeRenderedImageFrames(object.id);
    pendingImages.remove(playerKey(player));
    return pending.name
        + " "
        + plane
        + " "
        + targetWidth
        + "x"
        + targetHeight
        + " voxels"
        + (frames > 1
            ? " frames " + frames + " ticks " + firstTick + "-" + lastTick
            : " tick " + firstTick);
  }

  private List<AnimatedImageFramePart> imageFramePartsFor(
      PendingImage pending,
      BufferedImage image,
      AnimatedVoxelObject object,
      String plane,
      int startX,
      int startY,
      int startZ,
      int columns,
      int rows,
      int frameIndex) {
    BufferedImage scaled =
        new BufferedImage(columns * 128, rows * 128, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = scaled.createGraphics();
    graphics.drawImage(image, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
    graphics.dispose();

    List<AnimatedImageFramePart> parts = new ArrayList<>();
    for (int tileY = 0; tileY < rows; tileY++) {
      for (int tileX = 0; tileX < columns; tileX++) {
        BufferedImage tile = scaled.getSubimage(tileX * 128, tileY * 128, 128, 128);
        int worldX;
        int worldY;
        int worldZ;
        if ("XZ".equals(plane)) {
          worldX = startX + tileX * 16;
          worldY = startY;
          worldZ = startZ + tileY * 16;
        } else if ("ZY".equals(plane) || "YZ".equals(plane)) {
          worldX = startX;
          worldY = startY + (rows - 1 - tileY) * 16;
          worldZ = startZ + tileX * 16;
        } else {
          worldX = startX + tileX * 16;
          worldY = startY + (rows - 1 - tileY) * 16;
          worldZ = startZ;
        }
        parts.add(
            new AnimatedImageFramePart(
                imagePartPrefix(pending.name) + frameIndex + "_" + tileX + "_" + tileY,
                worldX - (int) Math.floor(object.originX * 16.0),
                worldY - (int) Math.floor(object.originY * 16.0),
                worldZ - (int) Math.floor(object.originZ * 16.0),
                plane,
                tileX,
                tileY,
                columns,
                rows,
                encodeImage(tile)));
      }
    }
    return parts;
  }

  private List<AnimatedVoxelPart> imagePartsFor(
      PendingImage pending,
      BufferedImage image,
      AnimatedVoxelObject object,
      String plane,
      int startX,
      int startY,
      int startZ,
      int targetWidth,
      int targetHeight,
      int pixelSize,
      int frameIndex) {
    List<AnimatedVoxelPart> parts = new ArrayList<>();
    int index = 0;
    for (int y = 0; y < targetHeight; y++) {
      for (int x = 0; x < targetWidth; x++) {
        int sourceX =
            Math.min(
                image.getWidth() - 1,
                (int) Math.floor(x * image.getWidth() / (double) targetWidth));
        int sourceY =
            Math.min(
                image.getHeight() - 1,
                (int) Math.floor(y * image.getHeight() / (double) targetHeight));
        int argb = image.getRGB(sourceX, sourceY);
        if (((argb >>> 24) & 0xff) < 128) {
          continue;
        }
        int worldX;
        int worldY;
        int worldZ;
        if ("XZ".equals(plane)) {
          worldX = startX + x * pixelSize;
          worldY = startY;
          worldZ = startZ + y * pixelSize;
        } else if ("ZY".equals(plane)) {
          worldX = startX;
          worldY = startY + (targetHeight - 1 - y) * pixelSize;
          worldZ = startZ + x * pixelSize;
        } else {
          worldX = startX + x * pixelSize;
          worldY = startY + (targetHeight - 1 - y) * pixelSize;
          worldZ = startZ;
        }
        parts.add(
            new AnimatedVoxelPart(
                imagePartPrefix(pending.name) + frameIndex + "_" + index++,
                worldX - (int) Math.floor(object.originX * 16.0),
                worldY - (int) Math.floor(object.originY * 16.0),
                worldZ - (int) Math.floor(object.originZ * 16.0),
                pixelSize,
                nearestImageMaterial(argb),
                null));
      }
    }
    return parts;
  }

  private void putImageLayer(
      VoxelAnimation animation, int tick, String imageName, List<AnimatedVoxelPart> imageParts) {
    VoxelStateKeyframe existing = null;
    for (VoxelStateKeyframe frame : animation.stateKeyframes) {
      if (frame.tick == tick) {
        existing = frame;
        break;
      }
    }
    List<AnimatedVoxelPart> parts =
        existing == null ? statePartsAt(animation, tick) : cloneParts(existing.parts);
    if (parts == null) {
      parts = new ArrayList<>();
    }
    String prefix = imagePartPrefix(imageName);
    parts.removeIf(part -> part.id != null && part.id.startsWith(prefix));
    parts.addAll(imageParts);
    if (existing == null) {
      animation.stateKeyframes.add(new VoxelStateKeyframe(tick, "HARD", parts));
    } else {
      existing.transition = "HARD";
      existing.parts = parts;
    }
  }

  private void removeImageLayer(VoxelAnimation animation, String imageName) {
    String prefix = imagePartPrefix(imageName);
    animation.stateKeyframes.removeIf(
        frame -> {
          if (frame.parts != null) {
            frame.parts.removeIf(part -> part.id != null && part.id.startsWith(prefix));
          }
          return (frame.parts == null || frame.parts.isEmpty())
              && (frame.blocks == null || frame.blocks.isEmpty())
              && frame.tick != 0;
        });
  }

  private void putImageFrameLayer(
      VoxelAnimation animation,
      int tick,
      String imageName,
      List<AnimatedImageFramePart> imageParts) {
    VoxelStateKeyframe existing = stateKeyframeAt(animation, tick);
    if (existing == null) {
      existing =
          new VoxelStateKeyframe(
              tick,
              "HARD",
              statePartsAt(animation, tick) == null
                  ? new ArrayList<>()
                  : statePartsAt(animation, tick));
      animation.stateKeyframes.add(existing);
    }
    if (existing.imageFrames == null) {
      existing.imageFrames = new ArrayList<>();
    }
    String prefix = imagePartPrefix(imageName);
    existing.imageFrames.removeIf(part -> part.id != null && part.id.startsWith(prefix));
    existing.imageFrames.addAll(imageParts);
    existing.transition = "HARD";
  }

  private void removeImageFrameLayer(VoxelAnimation animation, String imageName) {
    String prefix = imagePartPrefix(imageName);
    for (VoxelStateKeyframe frame : animation.stateKeyframes) {
      if (frame.imageFrames != null) {
        frame.imageFrames.removeIf(part -> part.id != null && part.id.startsWith(prefix));
      }
    }
  }

  private List<AnimatedImageFramePart> stateImageFramesAt(VoxelAnimation animation, int tick) {
    if (animation.stateKeyframes.isEmpty()) {
      return List.of();
    }
    animation.sort();
    VoxelStateKeyframe previous = animation.stateKeyframes.get(0);
    for (VoxelStateKeyframe frame : animation.stateKeyframes) {
      if (frame.tick <= tick) {
        previous = frame;
      } else {
        break;
      }
    }
    return previous.imageFrames == null ? List.of() : previous.imageFrames;
  }

  private void renderImageFramesAt(AnimatedVoxelObject object, VoxelAnimation animation, int tick) {
    renderImageFrames(object, stateImageFramesAt(animation, tick));
  }

  private void renderImageFrames(AnimatedVoxelObject object, List<AnimatedImageFramePart> parts) {
    World world = Bukkit.getWorld(object.world);
    if (world == null) {
      return;
    }
    String objectKey = key(object.id);
    Map<String, ItemFrame> rendered =
        imageFramesByObject.computeIfAbsent(objectKey, ignored -> new LinkedHashMap<>());
    java.util.Set<String> wanted = new java.util.HashSet<>();
    for (AnimatedImageFramePart part : parts) {
      if (part.id == null || part.imagePngBase64 == null || part.imagePngBase64.isBlank()) {
        continue;
      }
      wanted.add(part.id);
      ItemFrame frame = rendered.get(part.id);
      if (frame == null || !frame.isValid()) {
        frame = spawnImageItemFrame(world, object, part);
        if (frame != null) {
          rendered.put(part.id, frame);
        }
      } else {
        frame.setItem(imageMapItem(world, part));
      }
    }
    rendered
        .entrySet()
        .removeIf(
            entry -> {
              if (wanted.contains(entry.getKey())) {
                return false;
              }
              if (entry.getValue().isValid()) {
                entry.getValue().remove();
              }
              return true;
            });
  }

  private ItemFrame spawnImageItemFrame(
      World world, AnimatedVoxelObject object, AnimatedImageFramePart part) {
    Location location =
        new Location(
            world,
            object.originX + part.localX / 16.0,
            object.originY + part.localY / 16.0,
            object.originZ + part.localZ / 16.0);
    try {
      ItemFrame frame = world.spawn(location, ItemFrame.class);
      frame.setFacingDirection(faceForImagePlane(part.plane), true);
      frame.setFixed(true);
      frame.setVisible(false);
      frame
          .getPersistentDataContainer()
          .set(imageFrameObjectKey, PersistentDataType.STRING, object.id);
      frame.getPersistentDataContainer().set(imageFramePartKey, PersistentDataType.STRING, part.id);
      frame.setItem(imageMapItem(world, part));
      return frame;
    } catch (Exception ex) {
      plugin
          .getLogger()
          .warning("Could not spawn animation image frame " + part.id + ": " + ex.getMessage());
      return null;
    }
  }

  private BlockFace faceForImagePlane(String plane) {
    String value = plane == null ? "XY" : plane.toUpperCase(Locale.ROOT);
    if ("XZ".equals(value)) {
      return BlockFace.UP;
    }
    if ("ZY".equals(value) || "YZ".equals(value)) {
      return BlockFace.EAST;
    }
    return BlockFace.SOUTH;
  }

  private ItemStack imageMapItem(World world, AnimatedImageFramePart part) {
    ItemStack item = new ItemStack(Material.FILLED_MAP);
    MapMeta meta = (MapMeta) item.getItemMeta();
    if (meta == null) {
      return item;
    }
    MapView map = Bukkit.createMap(world);
    for (MapRenderer renderer : new ArrayList<>(map.getRenderers())) {
      map.removeRenderer(renderer);
    }
    map.setScale(MapView.Scale.CLOSEST);
    map.addRenderer(new StaticImageMapRenderer(decodeImage(part.imagePngBase64)));
    meta.setMapView(map);
    item.setItemMeta(meta);
    return item;
  }

  private BufferedImage decodeImage(String base64) {
    try {
      BufferedImage image =
          ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
      return image == null ? new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB) : image;
    } catch (IOException | IllegalArgumentException ex) {
      return new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
    }
  }

  private String encodeImage(BufferedImage image) {
    try {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      ImageIO.write(image, "png", output);
      return Base64.getEncoder().encodeToString(output.toByteArray());
    } catch (IOException ex) {
      return "";
    }
  }

  private void removeRenderedImageFrames(String objectId) {
    Map<String, ItemFrame> frames = imageFramesByObject.remove(key(objectId));
    if (frames == null) {
      return;
    }
    for (ItemFrame frame : frames.values()) {
      if (frame.isValid()) {
        frame.remove();
      }
    }
  }

  private void removeAllRenderedImageFrames() {
    for (String objectId : new ArrayList<>(imageFramesByObject.keySet())) {
      removeRenderedImageFrames(objectId);
    }
  }

  private void removePersistedImageFrames() {
    for (World world : Bukkit.getWorlds()) {
      for (Entity entity : world.getEntities()) {
        if (entity instanceof ItemFrame
            && entity
                .getPersistentDataContainer()
                .has(imageFrameObjectKey, PersistentDataType.STRING)) {
          entity.remove();
        }
      }
    }
    imageFramesByObject.clear();
  }

  private static class StaticImageMapRenderer extends MapRenderer {
    private final BufferedImage image;
    private boolean rendered;

    StaticImageMapRenderer(BufferedImage image) {
      super(false);
      this.image = image;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
      if (rendered) {
        return;
      }
      canvas.drawImage(0, 0, image);
      rendered = true;
    }
  }

  private int chooseImagePixelSize(
      int fittedWidth, int fittedHeight, int frameCount, int clickedGridSize) {
    int[] sizes = {1, 2, 4, 8, 16};
    int best = 16;
    int maxParts = frameCount > 1 ? 1800 : 3200;
    for (int size : sizes) {
      int targetWidth = Math.max(1, fittedWidth / size);
      int targetHeight = Math.max(1, fittedHeight / size);
      int parts = targetWidth * targetHeight;
      if (parts <= maxParts) {
        best = size;
        break;
      }
    }
    if (frameCount <= 1 && clickedGridSize <= 2) {
      best = Math.min(best, clickedGridSize);
    }
    return Math.max(1, Math.min(16, best));
  }

  private List<ImageFrame> optimizeImageFrames(List<ImageFrame> source) {
    if (source.size() <= 1) {
      return source;
    }
    List<ImageFrame> deduped = new ArrayList<>();
    int previousHash = 0;
    boolean hasPrevious = false;
    for (ImageFrame frame : source) {
      int hash = imageSampleHash(frame.image);
      if (hasPrevious && hash == previousHash) {
        ImageFrame last = deduped.remove(deduped.size() - 1);
        deduped.add(new ImageFrame(last.image, Math.min(40, last.delayTicks + frame.delayTicks)));
        continue;
      }
      deduped.add(frame);
      previousHash = hash;
      hasPrevious = true;
    }
    return enforceMinimumGifDelay(deduped);
  }

  private List<ImageFrame> enforceMinimumGifDelay(List<ImageFrame> frames) {
    if (frames.size() <= 1) {
      return frames;
    }
    List<ImageFrame> adjusted = new ArrayList<>();
    for (ImageFrame frame : frames) {
      adjusted.add(new ImageFrame(frame.image, Math.max(2, frame.delayTicks)));
    }
    return adjusted;
  }

  private int imageSampleHash(BufferedImage image) {
    int hash = 17;
    int stepX = Math.max(1, image.getWidth() / 12);
    int stepY = Math.max(1, image.getHeight() / 12);
    for (int y = 0; y < image.getHeight(); y += stepY) {
      for (int x = 0; x < image.getWidth(); x += stepX) {
        hash = 31 * hash + image.getRGB(x, y);
      }
    }
    return hash;
  }

  private String recommendedImageSurface(BufferedImage image) {
    int width = Math.max(1, image.getWidth());
    int height = Math.max(1, image.getHeight());
    double ratio = width / (double) height;
    int recommendedHeight = 16;
    int recommendedWidth = Math.max(1, (int) Math.round(recommendedHeight * ratio));
    int gcd = gcd(recommendedWidth, recommendedHeight);
    return (recommendedWidth / gcd)
        + "x"
        + (recommendedHeight / gcd)
        + "-ratio, try "
        + recommendedWidth
        + "x"
        + recommendedHeight
        + " voxels";
  }

  private int naturalImageDurationTicks(List<ImageFrame> frames) {
    if (frames.size() <= 1) {
      return 0;
    }
    int ticks = 0;
    for (ImageFrame frame : frames) {
      ticks += Math.max(1, frame.delayTicks);
    }
    return Math.max(frames.size() - 1, ticks);
  }

  private int gcd(int a, int b) {
    while (b != 0) {
      int next = a % b;
      a = b;
      b = next;
    }
    return Math.max(1, Math.abs(a));
  }

  private List<ImageFrame> readImageFrames(String url) throws IOException {
    List<ImageFrame> frames = new ArrayList<>();
    try (InputStream stream = URI.create(url).toURL().openStream();
        ImageInputStream imageInput = ImageIO.createImageInputStream(stream)) {
      if (imageInput == null) {
        return frames;
      }
      Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
      if (!readers.hasNext()) {
        return frames;
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(imageInput, false, false);
        boolean gif = "gif".equalsIgnoreCase(reader.getFormatName());
        int count;
        try {
          count = reader.getNumImages(true);
        } catch (IOException ignored) {
          count = 1;
        }
        BufferedImage gifCanvas = null;
        for (int i = 0; i < count; i++) {
          try {
            BufferedImage raw = toArgb(reader.read(i));
            BufferedImage image;
            if (gif) {
              GifFramePlacement placement = gifFramePlacement(reader, i, raw);
              if (gifCanvas == null) {
                gifCanvas =
                    new BufferedImage(
                        placement.canvasWidth, placement.canvasHeight, BufferedImage.TYPE_INT_ARGB);
              }
              Graphics2D graphics = gifCanvas.createGraphics();
              graphics.drawImage(raw, placement.left, placement.top, null);
              graphics.dispose();
              image = copyImage(gifCanvas);
              if ("restoreToBackgroundColor".equalsIgnoreCase(placement.disposal)) {
                Graphics2D clear = gifCanvas.createGraphics();
                clear.setComposite(AlphaComposite.Clear);
                clear.fillRect(placement.left, placement.top, raw.getWidth(), raw.getHeight());
                clear.dispose();
              }
            } else {
              image = raw;
            }
            int delayTicks = gifDelayTicks(reader, i);
            frames.add(new ImageFrame(image, delayTicks));
          } catch (IndexOutOfBoundsException ignored) {
            break;
          }
        }
      } finally {
        reader.dispose();
      }
    }
    return frames;
  }

  private BufferedImage copyImage(BufferedImage source) {
    BufferedImage copy =
        new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = copy.createGraphics();
    graphics.drawImage(source, 0, 0, null);
    graphics.dispose();
    return copy;
  }

  private BufferedImage toArgb(BufferedImage source) {
    if (source.getType() == BufferedImage.TYPE_INT_ARGB) {
      return source;
    }
    BufferedImage converted =
        new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = converted.createGraphics();
    graphics.drawImage(source, 0, 0, null);
    graphics.dispose();
    return converted;
  }

  private int gifDelayTicks(ImageReader reader, int frameIndex) {
    try {
      IIOMetadata metadata = reader.getImageMetadata(frameIndex);
      String format = metadata.getNativeMetadataFormatName();
      if (format == null) {
        return 2;
      }
      Node root = metadata.getAsTree(format);
      Node node = findNode(root, "GraphicControlExtension");
      if (node == null
          || node.getAttributes() == null
          || node.getAttributes().getNamedItem("delayTime") == null) {
        return 2;
      }
      int hundredths =
          Integer.parseInt(node.getAttributes().getNamedItem("delayTime").getNodeValue());
      return Math.max(1, (int) Math.round(hundredths / 5.0));
    } catch (Exception ignored) {
      return 2;
    }
  }

  private GifFramePlacement gifFramePlacement(
      ImageReader reader, int frameIndex, BufferedImage fallback) {
    int canvasWidth = fallback.getWidth();
    int canvasHeight = fallback.getHeight();
    int left = 0;
    int top = 0;
    String disposal = "none";
    try {
      IIOMetadata streamMetadata = reader.getStreamMetadata();
      if (streamMetadata != null && streamMetadata.getNativeMetadataFormatName() != null) {
        Node root = streamMetadata.getAsTree(streamMetadata.getNativeMetadataFormatName());
        Node descriptor = findNode(root, "LogicalScreenDescriptor");
        if (descriptor != null && descriptor.getAttributes() != null) {
          canvasWidth = intAttribute(descriptor, "logicalScreenWidth", canvasWidth);
          canvasHeight = intAttribute(descriptor, "logicalScreenHeight", canvasHeight);
        }
      }
      IIOMetadata imageMetadata = reader.getImageMetadata(frameIndex);
      String format = imageMetadata.getNativeMetadataFormatName();
      if (format != null) {
        Node root = imageMetadata.getAsTree(format);
        Node descriptor = findNode(root, "ImageDescriptor");
        if (descriptor != null && descriptor.getAttributes() != null) {
          left = intAttribute(descriptor, "imageLeftPosition", 0);
          top = intAttribute(descriptor, "imageTopPosition", 0);
          canvasWidth = Math.max(canvasWidth, left + fallback.getWidth());
          canvasHeight = Math.max(canvasHeight, top + fallback.getHeight());
        }
        Node graphics = findNode(root, "GraphicControlExtension");
        if (graphics != null
            && graphics.getAttributes() != null
            && graphics.getAttributes().getNamedItem("disposalMethod") != null) {
          disposal = graphics.getAttributes().getNamedItem("disposalMethod").getNodeValue();
        }
      }
    } catch (Exception ignored) {
    }
    return new GifFramePlacement(canvasWidth, canvasHeight, left, top, disposal);
  }

  private int intAttribute(Node node, String attribute, int fallback) {
    try {
      Node value = node.getAttributes().getNamedItem(attribute);
      return value == null ? fallback : Integer.parseInt(value.getNodeValue());
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private Node findNode(Node node, String name) {
    if (node == null) {
      return null;
    }
    if (name.equals(node.getNodeName())) {
      return node;
    }
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      Node found = findNode(child, name);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private record GifFramePlacement(
      int canvasWidth, int canvasHeight, int left, int top, String disposal) {}

  private String cleanImageName(String name) {
    String clean =
        name == null ? "image" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    if (clean.isBlank()) {
      clean = "image";
    }
    return clean.length() > 32 ? clean.substring(0, 32) : clean;
  }

  private String imagePartPrefix(String imageName) {
    return "img_" + cleanImageName(imageName) + "_";
  }

  private String nearestImageMaterial(int argb) {
    int r = (argb >> 16) & 0xff;
    int g = (argb >> 8) & 0xff;
    int b = argb & 0xff;
    MaterialChoice best = IMAGE_MATERIALS[0];
    int bestDistance = Integer.MAX_VALUE;
    for (MaterialChoice choice : IMAGE_MATERIALS) {
      int dr = r - choice.r;
      int dg = g - choice.g;
      int db = b - choice.b;
      int distance = dr * dr + dg * dg + db * db;
      if (distance < bestDistance) {
        bestDistance = distance;
        best = choice;
      }
    }
    return best.material;
  }

  public boolean playSequence(String objectId, String sequenceId) {
    AnimatedVoxelObject object = storage.get(objectId);
    if (object == null) {
      return false;
    }
    AnimationSequence sequence =
        object.sequence(sequenceId == null || sequenceId.isBlank() ? "main" : sequenceId);
    if (sequence == null || sequence.steps.isEmpty()) {
      return false;
    }
    RunningSequence run = new RunningSequence(object.id, sequence.id);
    runningSequences.put(sequenceKey(object.id, sequence.id), run);
    startSequenceStep(object, sequence, run);
    ensureTask();
    return true;
  }

  public boolean stopEditor(Player player) {
    EditorState state = editor(player);
    return state != null && stop(state.objectId);
  }

  public void renderEditorFrame(Player player) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    EditorState state = editor(player);
    if (object == null || animation == null || state == null) {
      return;
    }
    animation.sort();
    List<AnimatedVoxelPart> stateParts = statePartsAt(animation, state.tick);
    if (stateParts != null) {
      if (windActive(object, animation)) {
        renderer.renderPartsWithOffsets(
            object, stateParts, windOffsets(object, stateParts, Bukkit.getCurrentTick()));
        renderImageFramesAt(object, animation, state.tick);
        return;
      }
      renderer.renderParts(object, stateParts);
      renderImageFramesAt(object, animation, state.tick);
      return;
    }
    renderImageFramesAt(object, animation, state.tick);
    Transform transform = transformAt(animation, state.tick);
    renderer.render(object, transform.x, transform.y, transform.z, transform.yaw);
  }

  public boolean show(String objectId) {
    AnimatedVoxelObject object = storage.get(objectId);
    if (object == null) {
      return false;
    }
    renderDefaultState(object);
    return true;
  }

  public boolean hide(String objectId) {
    AnimatedVoxelObject object = storage.get(objectId);
    if (object == null) {
      return false;
    }
    stop(objectId);
    removeRenderedImageFrames(object.id);
    renderer.remove(object.id);
    return true;
  }

  public boolean delete(String objectId) {
    AnimatedVoxelObject object = storage.get(objectId);
    hide(objectId);
    if (object != null) {
      restoreWindSourceBlocks(object);
    }
    return storage.delete(objectId);
  }

  public boolean play(String objectId, String animationId) {
    AnimatedVoxelObject object = storage.get(objectId);
    if (object == null) {
      return false;
    }
    VoxelAnimation animation = object.animation(animationId);
    if (animation == null) {
      return false;
    }
    if (blockedByHigherPriority(object, animation)) {
      return false;
    }
    animation.sort();
    RunningAnimation run = new RunningAnimation(object.id, animation.id);
    List<AnimatedVoxelPart> firstState = playablePartsAt(object, animation, 0);
    if (firstState != null) {
      if (windActive(object, animation)) {
        renderer.renderPartsWithOffsets(
            object,
            firstState,
            windOffsets(object, firstState, Bukkit.getCurrentTick(), previewWindIntensity(object)));
      } else {
        renderer.renderParts(object, firstState);
      }
      renderImageFramesAt(object, animation, 0);
      run.lastStateRenderKey = stateRenderKey(animation, 0);
    } else {
      renderImageFramesAt(object, animation, 0);
      renderer.spawn(object);
    }
    playAnimationSound(object, animation);
    running.put(key(objectId), run);
    ensureTask();
    return true;
  }

  private boolean playTriggered(
      AnimatedVoxelObject object,
      VoxelAnimation animation,
      AnimationTriggerCause cause,
      boolean reverse) {
    if (blockedByHigherPriority(object, animation)) {
      return false;
    }
    animation.sort();
    RunningAnimation run = new RunningAnimation(object.id, animation.id);
    run.reverse = reverse;
    run.allowLoop = false;
    run.afterAction =
        cause == null || cause.afterAction == null
            ? "DEFAULT"
            : cause.afterAction.toUpperCase(Locale.ROOT);
    run.nextAnimationId = cause == null ? null : cause.nextAnimationId;
    run.tick = reverse ? playbackEndTick(animation) : 0;
    List<AnimatedVoxelPart> firstState =
        playablePartsAt(object, animation, (int) Math.floor(run.tick));
    if (firstState != null) {
      if (windActive(object, animation)) {
        renderer.renderPartsWithOffsets(
            object,
            firstState,
            windOffsets(object, firstState, Bukkit.getCurrentTick(), previewWindIntensity(object)));
      } else {
        renderer.renderParts(object, firstState);
      }
      renderImageFramesAt(object, animation, (int) Math.floor(run.tick));
      run.lastStateRenderKey = stateRenderKey(animation, (int) Math.floor(run.tick));
    } else {
      renderImageFramesAt(object, animation, (int) Math.floor(run.tick));
      renderer.spawn(object);
    }
    playAnimationSound(object, animation);
    running.put(key(object.id), run);
    ensureTask();
    return true;
  }

  public boolean stop(String objectId) {
    boolean removed = running.remove(key(objectId)) != null;
    stopTaskIfIdle();
    return removed;
  }

  private void tick() {
    tickSequences();
    if (running.isEmpty()) {
      return;
    }
    for (RunningAnimation run : new java.util.ArrayList<>(running.values())) {
      AnimatedVoxelObject object = storage.get(run.objectId);
      if (object == null || Bukkit.getWorld(object.world) == null) {
        running.remove(key(run.objectId));
        stopTaskIfIdle();
        continue;
      }
      VoxelAnimation animation = object.animation(run.animationId);
      if (animation == null
          || (animation.transformKeyframes.isEmpty()
              && animation.stateKeyframes.isEmpty()
              && (!windActive(object, animation) || object.parts.isEmpty()))) {
        running.remove(key(run.objectId));
        stopTaskIfIdle();
        continue;
      }

      int wholeTick = (int) Math.floor(run.tick);
      boolean stateDriven =
          !animation.stateKeyframes.isEmpty()
              || (windActive(object, animation) && !object.parts.isEmpty());
      if (stateDriven) {
        String renderKey =
            stateRenderKey(animation, wholeTick)
                + (windActive(object, animation) ? ":wind:" + Bukkit.getCurrentTick() : "");
        if (!renderKey.equals(run.lastStateRenderKey)) {
          List<AnimatedVoxelPart> stateParts = playablePartsAt(object, animation, wholeTick);
          if (stateParts != null) {
            if (windActive(object, animation)) {
              renderer.renderPartsWithOffsets(
                  object,
                  stateParts,
                  windOffsets(
                      object, stateParts, Bukkit.getCurrentTick(), previewWindIntensity(object)));
            } else {
              renderer.renderParts(object, stateParts);
            }
            renderImageFramesAt(object, animation, wholeTick);
            run.lastStateRenderKey = renderKey;
          }
        }
      } else {
        renderImageFramesAt(object, animation, wholeTick);
        Transform transform = transformAt(animation, wholeTick);
        renderer.render(object, transform.x, transform.y, transform.z, transform.yaw);
      }
      applyFrames(object, animation, run.lastTick, wholeTick);

      run.lastTick = wholeTick;
      double step = Math.max(0.1, animation.playbackSpeed);
      run.tick += run.reverse ? -step : step;
      int endTick = playbackEndTick(animation);
      boolean finished = run.reverse ? run.tick < 0 : run.tick > endTick;
      if (finished) {
        if (animation.loop && run.allowLoop) {
          run.tick = run.reverse ? endTick : 0;
          run.lastTick = -1;
          run.lastStateRenderKey = "";
        } else {
          running.remove(key(run.objectId));
          finishTriggeredRun(object, run);
          stopTaskIfIdle();
        }
      }
    }
  }

  private void tickSequences() {
    if (runningSequences.isEmpty()) {
      return;
    }
    for (RunningSequence run : new ArrayList<>(runningSequences.values())) {
      AnimatedVoxelObject object = storage.get(run.objectId);
      if (object == null) {
        runningSequences.remove(sequenceKey(run.objectId, run.sequenceId));
        continue;
      }
      AnimationSequence sequence = object.sequence(run.sequenceId);
      if (sequence == null || sequence.steps.isEmpty()) {
        runningSequences.remove(sequenceKey(run.objectId, run.sequenceId));
        continue;
      }
      if (Bukkit.getCurrentTick() < run.nextActionTick) {
        continue;
      }
      if (run.waitingForAnimation) {
        if (running.containsKey(key(run.objectId))) {
          continue;
        }
        AnimationSequenceStep completed =
            sequence.steps.get(Math.max(0, Math.min(run.stepIndex, sequence.steps.size() - 1)));
        run.waitingForAnimation = false;
        run.stepIndex++;
        run.nextActionTick = Bukkit.getCurrentTick() + Math.max(0, completed.delayAfterTicks);
        continue;
      }
      if (run.stepIndex >= sequence.steps.size()) {
        if (sequence.loop) {
          run.stepIndex = 0;
        } else {
          runningSequences.remove(sequenceKey(run.objectId, run.sequenceId));
          continue;
        }
      }
      startSequenceStep(object, sequence, run);
    }
  }

  private void startSequenceStep(
      AnimatedVoxelObject object, AnimationSequence sequence, RunningSequence run) {
    if (sequence.steps.isEmpty() || run.stepIndex >= sequence.steps.size()) {
      return;
    }
    AnimationSequenceStep step = sequence.steps.get(run.stepIndex);
    if (run.delayStartedTick < 0) {
      run.delayStartedTick = Bukkit.getCurrentTick();
      run.nextActionTick = Bukkit.getCurrentTick() + Math.max(0, step.delayBeforeTicks);
      return;
    }
    if (!sequenceStepTriggered(object, step)) {
      run.nextActionTick = Bukkit.getCurrentTick() + 10L;
      return;
    }
    if (play(object.id, step.animationId)) {
      run.delayStartedTick = -1;
      if (step.waitForCompletion) {
        run.waitingForAnimation = true;
      } else {
        run.waitingForAnimation = false;
        run.stepIndex++;
        run.nextActionTick = Bukkit.getCurrentTick() + Math.max(0, step.delayAfterTicks);
      }
    } else {
      run.stepIndex++;
      run.delayStartedTick = -1;
      run.nextActionTick = Bukkit.getCurrentTick();
    }
  }

  private AnimationSequence ensureSequence(AnimatedVoxelObject object, String sequenceId) {
    if (object.sequences == null) {
      object.sequences = new ArrayList<>();
    }
    AnimationSequence sequence = object.sequence(sequenceId);
    if (sequence != null) {
      return sequence;
    }
    sequence = new AnimationSequence(sequenceId);
    object.sequences.add(sequence);
    return sequence;
  }

  private String sequenceKey(String objectId, String sequenceId) {
    return key(objectId) + ":" + sequenceId.toLowerCase(Locale.ROOT);
  }

  private boolean sequenceStepTriggered(AnimatedVoxelObject object, AnimationSequenceStep step) {
    String type = normalizeSequenceTrigger(step.triggerType);
    if (type == null || "AUTO".equals(type)) {
      return true;
    }
    World world = Bukkit.getWorld(object.world);
    if (world == null) {
      return false;
    }
    int radius = Math.max(1, step.triggerRadius);
    int players = Math.max(1, step.triggerPlayerCount);
    return switch (type) {
      case "PLAYER_NEAR" -> playerDistanceTriggered(world, object, radius, players);
      case "PLAYER_AWAY" -> !playerDistanceTriggered(world, object, radius, players);
      case "ENTITY_NEAR" -> entityNearbyTriggered(world, object, sequenceTriggerCause(step));
      case "ENTITY_AWAY" -> !entityNearbyTriggered(world, object, sequenceTriggerCause(step));
      default -> true;
    };
  }

  private AnimationTriggerCause sequenceTriggerCause(AnimationSequenceStep step) {
    AnimationTriggerCause cause = new AnimationTriggerCause("ENTITY_NEARBY");
    cause.radius = Math.max(1, step.triggerRadius);
    cause.playerCount = Math.max(1, step.triggerPlayerCount);
    cause.includeInvisibleEntities = step.includeInvisibleEntities;
    return cause;
  }

  private String normalizeSequenceTrigger(String triggerType) {
    String value =
        triggerType == null || triggerType.isBlank()
            ? "AUTO"
            : triggerType.toUpperCase(Locale.ROOT).replace("-", "_");
    return switch (value) {
      case "AUTO", "MANUAL", "NONE" -> "AUTO";
      case "PLAYER", "PLAYER_DISTANCE", "PLAYER_NEAR", "NEAR" -> "PLAYER_NEAR";
      case "PLAYER_AWAY", "AWAY", "LEAVE", "PLAYER_LEAVE" -> "PLAYER_AWAY";
      case "ENTITY", "ENTITY_NEARBY", "ENTITY_NEAR" -> "ENTITY_NEAR";
      case "ENTITY_AWAY", "ENTITY_LEAVE" -> "ENTITY_AWAY";
      default -> null;
    };
  }

  private void finishTriggeredRun(AnimatedVoxelObject object, RunningAnimation run) {
    String afterAction =
        run.afterAction == null ? "DEFAULT" : run.afterAction.toUpperCase(Locale.ROOT);
    if ("REVERSE".equals(afterAction) && !run.reverse) {
      VoxelAnimation animation = object.animation(run.animationId);
      if (animation != null) {
        AnimationTriggerCause cause = new AnimationTriggerCause("MANUAL");
        cause.afterAction = "DEFAULT";
        playTriggered(object, animation, cause, true);
      }
      return;
    }
    if ("NEXT".equals(afterAction) && run.nextAnimationId != null) {
      play(object.id, run.nextAnimationId);
      return;
    }
    if ("NOTHING".equals(afterAction)) {
      return;
    }
    renderDefaultState(object);
  }

  private void renderDefaultState(AnimatedVoxelObject object) {
    VoxelAnimation animation = object.animation("main");
    if (animation == null && !object.animations.isEmpty()) {
      animation = object.animations.get(0);
    }
    List<AnimatedVoxelPart> parts = animation == null ? null : statePartsAt(animation, 0);
    if (parts != null) {
      if (windActive(object, animation)) {
        renderer.renderPartsWithOffsets(
            object,
            parts,
            windOffsets(object, parts, Bukkit.getCurrentTick(), previewWindIntensity(object)));
      } else {
        renderer.renderParts(object, parts);
      }
      renderImageFramesAt(object, animation, 0);
    } else {
      renderImageFramesAt(object, animation, 0);
      renderer.spawn(object);
    }
  }

  private int playbackEndTick(VoxelAnimation animation) {
    int end = 0;
    for (VoxelStateKeyframe frame : animation.stateKeyframes) {
      end = Math.max(end, frame.tick);
    }
    for (AnimationKeyframe frame : animation.transformKeyframes) {
      end = Math.max(end, frame.tick);
    }
    for (VoxelFrame frame : animation.voxelFrames) {
      end = Math.max(end, frame.tick);
    }
    return Math.max(1, end);
  }

  private List<AnimatedVoxelPart> playablePartsAt(
      AnimatedVoxelObject object, VoxelAnimation animation, int tick) {
    List<AnimatedVoxelPart> stateParts = statePartsAt(animation, tick);
    if (stateParts != null) {
      return stateParts;
    }
    if (windActive(object, animation) && !object.parts.isEmpty()) {
      return object.parts;
    }
    return null;
  }

  private double previewWindIntensity(AnimatedVoxelObject object) {
    World world = Bukkit.getWorld(object.world);
    double intensity =
        world == null
            ? object.windIdleIntensity
            : weatherWindIntensity(world, object) + localGustIntensity(world, object);
    return Math.max(intensity, 1.0);
  }

  public void renderWindPreview(Player player) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    EditorState state = editor(player);
    if (object == null || animation == null || state == null || !windActive(object, animation)) {
      return;
    }
    List<AnimatedVoxelPart> parts = playablePartsAt(object, animation, state.tick);
    if (parts == null || parts.isEmpty()) {
      return;
    }
    renderer.renderPartsWithOffsets(
        object, parts, windOffsets(object, parts, Bukkit.getCurrentTick(), 1.5));
  }

  public String windStatus(Player player) {
    AnimatedVoxelObject object = editorObject(player);
    VoxelAnimation animation = editorAnimation(player);
    if (object == null || !windActive(object, animation)) {
      return "Wind disabled for this state";
    }
    return object.parts.size()
        + " parts, "
        + object.windConnectorPartIds.size()
        + " connectors, "
        + object.windNodePartIds.size()
        + " nodes, "
        + object.windLinks.size()
        + " links";
  }

  private void applyFrames(
      AnimatedVoxelObject object, VoxelAnimation animation, int lastTick, int tick) {
    for (VoxelFrame frame : animation.voxelFrames) {
      if (frame.tick <= lastTick || frame.tick > tick) {
        continue;
      }
      for (VoxelFrameChange change : frame.changes) {
        renderer.applyChange(object, change);
      }
    }
  }

  private boolean blockedByHigherPriority(AnimatedVoxelObject object, VoxelAnimation animation) {
    int priority = object.priority + animation.priority;
    Bounds bounds = boundsFor(object);
    for (RunningAnimation run : running.values()) {
      AnimatedVoxelObject other = storage.get(run.objectId);
      if (other == null
          || other.id.equalsIgnoreCase(object.id)
          || !other.world.equals(object.world)) {
        continue;
      }
      VoxelAnimation otherAnimation = other.animation(run.animationId);
      if (otherAnimation == null) {
        continue;
      }
      int otherPriority = other.priority + otherAnimation.priority;
      if (otherPriority >= priority && bounds.overlaps(boundsFor(other))) {
        return true;
      }
    }
    return false;
  }

  private Bounds boundsFor(AnimatedVoxelObject object) {
    double minX = object.originX;
    double minY = object.originY;
    double minZ = object.originZ;
    double maxX = object.originX;
    double maxY = object.originY;
    double maxZ = object.originZ;
    for (AnimatedVoxelPart part : object.parts) {
      double x = object.originX + part.localX / 16.0;
      double y = object.originY + part.localY / 16.0;
      double z = object.originZ + part.localZ / 16.0;
      double size = part.size / 16.0;
      minX = Math.min(minX, x);
      minY = Math.min(minY, y);
      minZ = Math.min(minZ, z);
      maxX = Math.max(maxX, x + size);
      maxY = Math.max(maxY, y + size);
      maxZ = Math.max(maxZ, z + size);
    }
    return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
  }

  private Transform transformAt(VoxelAnimation animation, int tick) {
    if (animation.transformKeyframes.isEmpty()) {
      return new Transform(0, 0, 0, 0);
    }
    AnimationKeyframe previous = animation.transformKeyframes.get(0);
    AnimationKeyframe next = previous;
    for (AnimationKeyframe keyframe : animation.transformKeyframes) {
      if (keyframe.tick <= tick) {
        previous = keyframe;
      }
      if (keyframe.tick >= tick) {
        next = keyframe;
        break;
      }
    }

    if (previous == next || next.tick == previous.tick) {
      return new Transform(previous.x, previous.y, previous.z, previous.yaw);
    }
    double amount = (double) (tick - previous.tick) / (double) (next.tick - previous.tick);
    return new Transform(
        lerp(previous.x, next.x, amount),
        lerp(previous.y, next.y, amount),
        lerp(previous.z, next.z, amount),
        (float) lerp(previous.yaw, next.yaw, amount));
  }

  private double lerp(double start, double end, double amount) {
    return start + (end - start) * amount;
  }

  private List<AnimatedVoxelPart> statePartsAt(VoxelAnimation animation, int tick) {
    if (animation.stateKeyframes.isEmpty()) {
      return null;
    }
    VoxelStateKeyframe previous = animation.stateKeyframes.get(0);
    VoxelStateKeyframe next = null;
    for (VoxelStateKeyframe frame : animation.stateKeyframes) {
      if (frame.tick <= tick) {
        previous = frame;
      }
      if (frame.tick > tick) {
        next = frame;
        break;
      }
    }
    if (next == null || !next.soft() || next.tick == previous.tick) {
      return previous.parts;
    }

    double amount = (double) (tick - previous.tick) / (double) (next.tick - previous.tick);
    Map<String, AnimatedVoxelPart> previousById = partsById(previous.parts);
    List<AnimatedVoxelPart> blended = new ArrayList<>();
    for (int i = 0; i < next.parts.size(); i++) {
      AnimatedVoxelPart nextPart = next.parts.get(i);
      AnimatedVoxelPart previousPart = previousById.get(nextPart.id);
      if (previousPart == null
          && i < previous.parts.size()
          && sameAppearance(previous.parts.get(i), nextPart)) {
        previousPart = previous.parts.get(i);
      }
      if (previousPart == null || !sameAppearance(previousPart, nextPart)) {
        if (amount >= 0.5) {
          blended.add(copyPart(nextPart));
        }
        continue;
      }
      blended.add(
          new AnimatedVoxelPart(
              previousPart.id.equals(nextPart.id) ? nextPart.id : "blend_" + i,
              (int) Math.round(lerp(previousPart.localX, nextPart.localX, amount)),
              (int) Math.round(lerp(previousPart.localY, nextPart.localY, amount)),
              (int) Math.round(lerp(previousPart.localZ, nextPart.localZ, amount)),
              Math.max(1, (int) Math.round(lerp(previousPart.size, nextPart.size, amount))),
              nextPart.material,
              nextPart.blockData));
    }
    return blended;
  }

  private String stateRenderKey(VoxelAnimation animation, int tick) {
    if (animation.stateKeyframes.isEmpty()) {
      return "none";
    }
    VoxelStateKeyframe previous = animation.stateKeyframes.get(0);
    VoxelStateKeyframe next = null;
    for (VoxelStateKeyframe frame : animation.stateKeyframes) {
      if (frame.tick <= tick) {
        previous = frame;
      }
      if (frame.tick > tick) {
        next = frame;
        break;
      }
    }
    if (next == null || !next.soft() || next.tick == previous.tick) {
      return "hard:" + previous.tick;
    }
    return "soft:" + previous.tick + ":" + next.tick + ":" + (tick / 4);
  }

  private void putStateKeyframe(
      VoxelAnimation animation, int tick, String transition, List<AnimatedVoxelPart> captured) {
    if ("ADD".equalsIgnoreCase(animation.captureMode)) {
      VoxelStateKeyframe existing = null;
      for (VoxelStateKeyframe frame : animation.stateKeyframes) {
        if (frame.tick == tick) {
          existing = frame;
          break;
        }
      }
      if (existing != null) {
        Map<String, AnimatedVoxelPart> merged = partsById(existing.parts);
        for (AnimatedVoxelPart part : captured) {
          merged.put(part.id, copyPart(part));
        }
        existing.parts = new ArrayList<>(merged.values());
        existing.transition = transition;
        return;
      }
    }
    animation.stateKeyframes.removeIf(frame -> frame.tick == tick);
    animation.stateKeyframes.add(new VoxelStateKeyframe(tick, transition, captured));
  }

  private VoxelStateKeyframe stateKeyframeAt(VoxelAnimation animation, int tick) {
    for (VoxelStateKeyframe frame : animation.stateKeyframes) {
      if (frame.tick == tick) {
        return frame;
      }
    }
    return null;
  }

  private int nextFreeStateTick(VoxelAnimation animation, int startTick) {
    int tick = Math.max(0, startTick);
    while (stateKeyframeAt(animation, tick) != null) {
      tick++;
    }
    return tick;
  }

  private java.util.Set<String> writeFrameToWorld(
      AnimatedVoxelObject object, VoxelAnimation animation, List<AnimatedVoxelPart> parts) {
    World world = Bukkit.getWorld(object.world);
    java.util.Set<String> affected = new java.util.HashSet<>();
    if (world == null) {
      return affected;
    }
    for (AnimatedVoxelPart part : object.parts) {
      affected.add(blockKeyForPart(object, part));
    }
    for (VoxelStateKeyframe frame : animation.stateKeyframes) {
      for (AnimatedVoxelPart part : frame.parts) {
        affected.add(blockKeyForPart(object, part));
      }
    }
    for (String key : affected) {
      Block block = blockFromKey(world, key);
      if (block == null) {
        continue;
      }
      plugin.getFallingBlockManager().removeBlockDisplay(block);
      if (plugin.getDataManager().hasCarvedData(block)) {
        plugin.getDataManager().removeCarvedBlockAndMetadata(block);
      }
      if (block.getType() == Material.BARRIER || !block.getType().isAir()) {
        block.setType(Material.AIR, false);
      }
    }

    Map<String, List<VoxelPiece>> voxelPiecesByBlock = new LinkedHashMap<>();
    for (AnimatedVoxelPart part : parts) {
      Block block = blockForPart(world, object, part);
      if (block == null) {
        continue;
      }
      if (part.id != null && part.id.startsWith("full_") && part.size == 16) {
        Material material = Material.matchMaterial(part.material == null ? "" : part.material);
        if (material != null && material.isBlock() && !material.isAir()) {
          if (part.blockData != null && !part.blockData.isBlank()) {
            try {
              block.setBlockData(Bukkit.createBlockData(part.blockData), false);
            } catch (IllegalArgumentException ignored) {
              block.setType(material, false);
            }
          } else {
            block.setType(material, false);
          }
        }
        continue;
      }
      int worldVoxelX = (int) Math.round(object.originX * 16.0) + part.localX;
      int worldVoxelY = (int) Math.round(object.originY * 16.0) + part.localY;
      int worldVoxelZ = (int) Math.round(object.originZ * 16.0) + part.localZ;
      int innerX = Math.floorMod(worldVoxelX, 16);
      int innerY = Math.floorMod(worldVoxelY, 16);
      int innerZ = Math.floorMod(worldVoxelZ, 16);
      voxelPiecesByBlock
          .computeIfAbsent(blockKey(block), ignored -> new ArrayList<>())
          .add(new VoxelPiece(innerX, innerY, innerZ, part.size, part.material, part.blockData));
    }

    for (Map.Entry<String, List<VoxelPiece>> entry : voxelPiecesByBlock.entrySet()) {
      Block block = blockFromKey(world, entry.getKey());
      if (block == null) {
        continue;
      }
      block.setType(Material.BARRIER, false);
      plugin.getDataManager().setVoxelPieces(block, entry.getValue());
      plugin.getFallingBlockManager().updateBlockDisplay(block, null);
      CollisionBlockManager.updateCollisionBlock(
          block, entry.getValue(), plugin.getDataManager().isBlockLocked(block));
    }
    return affected;
  }

  private List<AnimatedVoxelPart> captureEnvironment(
      World world, AnimatedVoxelObject object, int radiusBlocks) {
    List<AnimatedVoxelPart> captured = new ArrayList<>();
    int minX = (int) Math.floor(object.originX) - radiusBlocks;
    int minY = (int) Math.floor(object.originY) - radiusBlocks;
    int minZ = (int) Math.floor(object.originZ) - radiusBlocks;
    int maxX = (int) Math.floor(object.originX) + radiusBlocks;
    int maxY = (int) Math.floor(object.originY) + radiusBlocks;
    int maxZ = (int) Math.floor(object.originZ) + radiusBlocks;

    for (Block block : plugin.getDataManager().getSavedVoxelBlocks(world)) {
      if (block.getX() < minX
          || block.getX() > maxX
          || block.getY() < minY
          || block.getY() > maxY
          || block.getZ() < minZ
          || block.getZ() > maxZ) {
        continue;
      }
      for (VoxelPiece piece : plugin.getDataManager().getVoxelPieces(block)) {
        int worldVoxelX = block.getX() * 16 + piece.x;
        int worldVoxelY = block.getY() * 16 + piece.y;
        int worldVoxelZ = block.getZ() * 16 + piece.z;
        int localX = (int) Math.round(worldVoxelX - object.originX * 16.0);
        int localY = (int) Math.round(worldVoxelY - object.originY * 16.0);
        int localZ = (int) Math.round(worldVoxelZ - object.originZ * 16.0);
        captured.add(
            animatedFromPiece(
                partId(localX, localY, localZ, piece.size), localX, localY, localZ, piece));
      }
    }
    captureFullBlocks(world, object, minX, minY, minZ, maxX, maxY, maxZ, captured);
    captured.sort(java.util.Comparator.comparing(part -> part.id));
    return captured;
  }

  private List<AnimatedVoxelPart> captureBlocks(
      World world, AnimatedVoxelObject object, java.util.Set<String> blockKeys) {
    List<AnimatedVoxelPart> captured = new ArrayList<>();
    for (String key : blockKeys) {
      Block block = blockFromKey(world, key);
      if (block == null) {
        continue;
      }
      if (plugin.getDataManager().hasCarvedData(block)) {
        for (VoxelPiece piece : plugin.getDataManager().getVoxelPieces(block)) {
          int worldVoxelX = block.getX() * 16 + piece.x;
          int worldVoxelY = block.getY() * 16 + piece.y;
          int worldVoxelZ = block.getZ() * 16 + piece.z;
          int localX = (int) Math.round(worldVoxelX - object.originX * 16.0);
          int localY = (int) Math.round(worldVoxelY - object.originY * 16.0);
          int localZ = (int) Math.round(worldVoxelZ - object.originZ * 16.0);
          captured.add(
              animatedFromPiece(
                  partId(localX, localY, localZ, piece.size), localX, localY, localZ, piece));
        }
        continue;
      }
      if (block.getType() == Material.AIR
          || block.getType() == Material.BARRIER
          || !block.getType().isBlock()) {
        continue;
      }
      int localX = (int) Math.round(block.getX() * 16.0 - object.originX * 16.0);
      int localY = (int) Math.round(block.getY() * 16.0 - object.originY * 16.0);
      int localZ = (int) Math.round(block.getZ() * 16.0 - object.originZ * 16.0);
      captured.add(
          new AnimatedVoxelPart(
              "full_" + localX + "_" + localY + "_" + localZ,
              localX,
              localY,
              localZ,
              16,
              block.getType().name(),
              block.getBlockData().getAsString()));
    }
    captured.sort(java.util.Comparator.comparing(part -> part.id));
    return captured;
  }

  private List<AnimatedVoxelPart> captureRegion(
      World world,
      AnimatedVoxelObject object,
      int minX,
      int minY,
      int minZ,
      int maxX,
      int maxY,
      int maxZ) {
    List<AnimatedVoxelPart> captured = new ArrayList<>();
    for (Block block : plugin.getDataManager().getSavedVoxelBlocks(world)) {
      if (block.getX() < minX
          || block.getX() > maxX
          || block.getY() < minY
          || block.getY() > maxY
          || block.getZ() < minZ
          || block.getZ() > maxZ) {
        continue;
      }
      for (VoxelPiece piece : plugin.getDataManager().getVoxelPieces(block)) {
        int worldVoxelX = block.getX() * 16 + piece.x;
        int worldVoxelY = block.getY() * 16 + piece.y;
        int worldVoxelZ = block.getZ() * 16 + piece.z;
        int localX = (int) Math.round(worldVoxelX - object.originX * 16.0);
        int localY = (int) Math.round(worldVoxelY - object.originY * 16.0);
        int localZ = (int) Math.round(worldVoxelZ - object.originZ * 16.0);
        captured.add(
            new AnimatedVoxelPart(
                partId(localX, localY, localZ, piece.size),
                localX,
                localY,
                localZ,
                piece.size,
                piece.material,
                piece.blockData));
      }
    }
    captureFullBlocks(world, object, minX, minY, minZ, maxX, maxY, maxZ, captured);
    captured.sort(java.util.Comparator.comparing(part -> part.id));
    return captured;
  }

  private List<AnimatedVoxelPart> captureVoxelRegion(
      World world,
      AnimatedVoxelObject object,
      int minX,
      int minY,
      int minZ,
      int maxX,
      int maxY,
      int maxZ) {
    List<AnimatedVoxelPart> captured = new ArrayList<>();
    int minBlockX = Math.floorDiv(minX, 16);
    int minBlockY = Math.floorDiv(minY, 16);
    int minBlockZ = Math.floorDiv(minZ, 16);
    int maxBlockX = Math.floorDiv(maxX - 1, 16);
    int maxBlockY = Math.floorDiv(maxY - 1, 16);
    int maxBlockZ = Math.floorDiv(maxZ - 1, 16);

    for (int x = minBlockX; x <= maxBlockX; x++) {
      for (int y = minBlockY; y <= maxBlockY; y++) {
        for (int z = minBlockZ; z <= maxBlockZ; z++) {
          if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            continue;
          }
          Block block = world.getBlockAt(x, y, z);
          if (!plugin.getDataManager().hasCarvedData(block)) {
            continue;
          }
          for (VoxelPiece piece : plugin.getDataManager().getVoxelPieces(block)) {
            int worldVoxelX = block.getX() * 16 + piece.x;
            int worldVoxelY = block.getY() * 16 + piece.y;
            int worldVoxelZ = block.getZ() * 16 + piece.z;
            if (!worldPieceIntersects(
                worldVoxelX,
                worldVoxelY,
                worldVoxelZ,
                piece.size,
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ)) {
              continue;
            }
            int localX = (int) Math.round(worldVoxelX - object.originX * 16.0);
            int localY = (int) Math.round(worldVoxelY - object.originY * 16.0);
            int localZ = (int) Math.round(worldVoxelZ - object.originZ * 16.0);
            captured.add(
                new AnimatedVoxelPart(
                    partId(localX, localY, localZ, piece.size),
                    localX,
                    localY,
                    localZ,
                    piece.size,
                    piece.material,
                    piece.blockData));
          }
        }
      }
    }

    for (int x = minBlockX; x <= maxBlockX; x++) {
      for (int y = minBlockY; y <= maxBlockY; y++) {
        for (int z = minBlockZ; z <= maxBlockZ; z++) {
          Block block = world.getBlockAt(x, y, z);
          if (block.getType() == Material.AIR
              || block.getType() == Material.BARRIER
              || plugin.getDataManager().hasCarvedData(block)
              || !block.getType().isBlock()) {
            continue;
          }
          int worldVoxelX = x * 16;
          int worldVoxelY = y * 16;
          int worldVoxelZ = z * 16;
          if (!worldPieceIntersects(
              worldVoxelX, worldVoxelY, worldVoxelZ, 16, minX, minY, minZ, maxX, maxY, maxZ)) {
            continue;
          }
          int localX = (int) Math.round(worldVoxelX - object.originX * 16.0);
          int localY = (int) Math.round(worldVoxelY - object.originY * 16.0);
          int localZ = (int) Math.round(worldVoxelZ - object.originZ * 16.0);
          captured.add(
              new AnimatedVoxelPart(
                  "full_" + localX + "_" + localY + "_" + localZ,
                  localX,
                  localY,
                  localZ,
                  16,
                  block.getType().name(),
                  block.getBlockData().getAsString()));
        }
      }
    }
    captured.sort(java.util.Comparator.comparing(part -> part.id));
    return captured;
  }

  private void captureFullBlocks(
      World world,
      AnimatedVoxelObject object,
      int minX,
      int minY,
      int minZ,
      int maxX,
      int maxY,
      int maxZ,
      List<AnimatedVoxelPart> captured) {
    for (int x = minX; x <= maxX; x++) {
      for (int y = minY; y <= maxY; y++) {
        for (int z = minZ; z <= maxZ; z++) {
          Block block = world.getBlockAt(x, y, z);
          if (block.getType() == Material.AIR
              || block.getType() == Material.BARRIER
              || plugin.getDataManager().hasCarvedData(block)) {
            continue;
          }
          if (!block.getType().isBlock()) {
            continue;
          }
          int localX = (int) Math.round(x * 16.0 - object.originX * 16.0);
          int localY = (int) Math.round(y * 16.0 - object.originY * 16.0);
          int localZ = (int) Math.round(z * 16.0 - object.originZ * 16.0);
          captured.add(
              new AnimatedVoxelPart(
                  "full_" + localX + "_" + localY + "_" + localZ,
                  localX,
                  localY,
                  localZ,
                  16,
                  block.getType().name(),
                  block.getBlockData().getAsString()));
        }
      }
    }
  }

  private Map<Block, List<VoxelPiece>> previewRegion(
      World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int limit) {
    Map<Block, List<VoxelPiece>> preview = new LinkedHashMap<>();
    int shown = 0;
    for (int x = minX; x <= maxX && shown < limit; x++) {
      for (int y = minY; y <= maxY && shown < limit; y++) {
        for (int z = minZ; z <= maxZ && shown < limit; z++) {
          preview
              .computeIfAbsent(world.getBlockAt(x, y, z), ignored -> new ArrayList<>())
              .add(new VoxelPiece(0, 0, 0, 16, "MAGENTA_STAINED_GLASS", null));
          shown++;
        }
      }
    }
    return outlinePreview(preview);
  }

  private Map<Block, List<VoxelPiece>> previewVoxelRegion(
      World world,
      int minX,
      int minY,
      int minZ,
      int maxX,
      int maxY,
      int maxZ,
      int baseSize,
      int limit) {
    Map<Block, List<VoxelPiece>> preview = new LinkedHashMap<>();
    int shown = 0;
    for (int x = minX; x < maxX && shown < limit; x += baseSize) {
      for (int y = minY; y < maxY && shown < limit; y += baseSize) {
        for (int z = minZ; z < maxZ && shown < limit; z += baseSize) {
          int size = Math.min(baseSize, Math.min(maxX - x, Math.min(maxY - y, maxZ - z)));
          size =
              Math.min(
                  size,
                  Math.min(
                      16 - Math.floorMod(x, 16),
                      Math.min(16 - Math.floorMod(y, 16), 16 - Math.floorMod(z, 16))));
          Block block =
              world.getBlockAt(Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16));
          VoxelPiece piece =
              new VoxelPiece(
                  Math.floorMod(x, 16),
                  Math.floorMod(y, 16),
                  Math.floorMod(z, 16),
                  size,
                  "MAGENTA_STAINED_GLASS",
                  null);
          if (piece.isValid()) {
            preview.computeIfAbsent(block, ignored -> new ArrayList<>()).add(piece);
            shown++;
          }
        }
      }
    }
    return outlinePreview(preview);
  }

  private Map<Block, List<VoxelPiece>> outlinePreview(Map<Block, List<VoxelPiece>> piecesByBlock) {
    if (piecesByBlock.size() < 32) {
      return piecesByBlock;
    }
    Map<Block, List<VoxelPiece>> outline = new LinkedHashMap<>();
    for (Map.Entry<Block, List<VoxelPiece>> entry : piecesByBlock.entrySet()) {
      Block block = entry.getKey();
      if (!piecesByBlock.containsKey(block.getRelative(1, 0, 0))
          || !piecesByBlock.containsKey(block.getRelative(-1, 0, 0))
          || !piecesByBlock.containsKey(block.getRelative(0, 1, 0))
          || !piecesByBlock.containsKey(block.getRelative(0, -1, 0))
          || !piecesByBlock.containsKey(block.getRelative(0, 0, 1))
          || !piecesByBlock.containsKey(block.getRelative(0, 0, -1))) {
        outline.put(block, entry.getValue());
      }
    }
    return outline.isEmpty() ? piecesByBlock : outline;
  }

  private int regionBlockCount(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    long x = (long) maxX - minX + 1;
    long y = (long) maxY - minY + 1;
    long z = (long) maxZ - minZ + 1;
    long count = x * y * z;
    return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
  }

  private int voxelRegionCount(
      int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int baseSize) {
    long x = Math.max(1, Math.ceilDiv(maxX - minX, baseSize));
    long y = Math.max(1, Math.ceilDiv(maxY - minY, baseSize));
    long z = Math.max(1, Math.ceilDiv(maxZ - minZ, baseSize));
    long count = x * y * z;
    return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
  }

  private boolean worldPieceIntersects(
      int x, int y, int z, int size, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    return x < maxX
        && x + size > minX
        && y < maxY
        && y + size > minY
        && z < maxZ
        && z + size > minZ;
  }

  private double rayBoxDistance(
      Vector origin,
      Vector direction,
      double minX,
      double minY,
      double minZ,
      double maxX,
      double maxY,
      double maxZ) {
    double tMin = 0.0;
    double tMax = Double.MAX_VALUE;
    double[] o = {origin.getX(), origin.getY(), origin.getZ()};
    double[] d = {direction.getX(), direction.getY(), direction.getZ()};
    double[] min = {minX, minY, minZ};
    double[] max = {maxX, maxY, maxZ};
    for (int i = 0; i < 3; i++) {
      if (Math.abs(d[i]) < 0.000001) {
        if (o[i] < min[i] || o[i] > max[i]) {
          return -1.0;
        }
        continue;
      }
      double inv = 1.0 / d[i];
      double near = (min[i] - o[i]) * inv;
      double far = (max[i] - o[i]) * inv;
      if (near > far) {
        double tmp = near;
        near = far;
        far = tmp;
      }
      tMin = Math.max(tMin, near);
      tMax = Math.min(tMax, far);
      if (tMin > tMax) {
        return -1.0;
      }
    }
    return tMin;
  }

  private int maxCaptureBlocks() {
    return Math.max(1, plugin.getConfig().getInt("limits.animation-capture-blocks", 4096));
  }

  private int maxPreviewBlocks() {
    return Math.max(1, plugin.getConfig().getInt("limits.preview-voxels", 1024));
  }

  private int objectCaptureRadius(AnimatedVoxelObject object) {
    if (object.parts.isEmpty()) {
      return 8;
    }
    int max = 1;
    for (AnimatedVoxelPart part : object.parts) {
      max = Math.max(max, Math.abs(part.localX / 16) + 2);
      max = Math.max(max, Math.abs(part.localY / 16) + 2);
      max = Math.max(max, Math.abs(part.localZ / 16) + 2);
    }
    return Math.min(64, max);
  }

  private String partId(int localX, int localY, int localZ, int size) {
    return "p_" + localX + "_" + localY + "_" + localZ + "_" + size;
  }

  private Block blockForPart(World world, AnimatedVoxelObject object, AnimatedVoxelPart part) {
    int worldVoxelX = (int) Math.round(object.originX * 16.0) + part.localX;
    int worldVoxelY = (int) Math.round(object.originY * 16.0) + part.localY;
    int worldVoxelZ = (int) Math.round(object.originZ * 16.0) + part.localZ;
    int blockX = Math.floorDiv(worldVoxelX, 16);
    int blockY = Math.floorDiv(worldVoxelY, 16);
    int blockZ = Math.floorDiv(worldVoxelZ, 16);
    if (blockY < world.getMinHeight() || blockY >= world.getMaxHeight()) {
      return null;
    }
    return world.getBlockAt(blockX, blockY, blockZ);
  }

  private String blockKeyForPart(AnimatedVoxelObject object, AnimatedVoxelPart part) {
    int worldVoxelX = (int) Math.round(object.originX * 16.0) + part.localX;
    int worldVoxelY = (int) Math.round(object.originY * 16.0) + part.localY;
    int worldVoxelZ = (int) Math.round(object.originZ * 16.0) + part.localZ;
    return Math.floorDiv(worldVoxelX, 16)
        + ","
        + Math.floorDiv(worldVoxelY, 16)
        + ","
        + Math.floorDiv(worldVoxelZ, 16);
  }

  private String blockKey(Block block) {
    return block.getX() + "," + block.getY() + "," + block.getZ();
  }

  private Block blockFromKey(World world, String key) {
    String[] parts = key.split(",");
    if (parts.length != 3) {
      return null;
    }
    try {
      int x = Integer.parseInt(parts[0]);
      int y = Integer.parseInt(parts[1]);
      int z = Integer.parseInt(parts[2]);
      if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
        return null;
      }
      return world.getBlockAt(x, y, z);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private String sanitizeId(String raw) {
    if (raw == null) {
      return null;
    }
    String id = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    return id.matches("[a-z0-9_-]{1,48}") ? id : null;
  }

  private Map<String, AnimatedVoxelPart> partsById(List<AnimatedVoxelPart> parts) {
    Map<String, AnimatedVoxelPart> byId = new LinkedHashMap<>();
    for (AnimatedVoxelPart part : parts) {
      byId.put(part.id, part);
    }
    return byId;
  }

  private boolean sameAppearance(AnimatedVoxelPart a, AnimatedVoxelPart b) {
    return a.material.equalsIgnoreCase(b.material)
        && java.util.Objects.equals(a.blockData, b.blockData);
  }

  private List<AnimatedVoxelPart> cloneParts(List<AnimatedVoxelPart> parts) {
    List<AnimatedVoxelPart> copy = new ArrayList<>();
    for (AnimatedVoxelPart part : parts) {
      copy.add(copyPart(part));
    }
    return copy;
  }

  private AnimatedVoxelPart copyPart(AnimatedVoxelPart part) {
    return part.copy();
  }

  private AnimatedVoxelPart animatedFromPiece(
      String id, int localX, int localY, int localZ, VoxelPiece piece) {
    AnimatedVoxelPart part =
        new AnimatedVoxelPart(
            id, localX, localY, localZ, piece.size, piece.material, piece.blockData);
    part.offsetX = piece.offsetX;
    part.offsetY = piece.offsetY;
    part.offsetZ = piece.offsetZ;
    part.rotationX = piece.rotationX;
    part.rotationY = piece.rotationY;
    part.rotationZ = piece.rotationZ;
    part.quaternionX = piece.quaternionX;
    part.quaternionY = piece.quaternionY;
    part.quaternionZ = piece.quaternionZ;
    part.quaternionW = piece.quaternionW;
    part.visualScale = piece.visualScale;
    part.scaleX = piece.scaleX;
    part.scaleY = piece.scaleY;
    part.scaleZ = piece.scaleZ;
    part.transformGroup = piece.transformGroup;
    return part;
  }

  private void copyTransform(AnimatedVoxelPart part, VoxelPiece piece) {
    piece.offsetX = part.offsetX;
    piece.offsetY = part.offsetY;
    piece.offsetZ = part.offsetZ;
    piece.rotationX = part.rotationX;
    piece.rotationY = part.rotationY;
    piece.rotationZ = part.rotationZ;
    piece.quaternionX = part.quaternionX;
    piece.quaternionY = part.quaternionY;
    piece.quaternionZ = part.quaternionZ;
    piece.quaternionW = part.quaternionW;
    piece.visualScale = part.visualScale;
    piece.scaleX = part.scaleX;
    piece.scaleY = part.scaleY;
    piece.scaleZ = part.scaleZ;
    piece.transformGroup = part.transformGroup;
  }

  private void ensureTask() {
    if (task != null) {
      return;
    }
    task =
        Bukkit.getScheduler()
            .runTaskTimer(
                plugin,
                () -> {
                  long started = System.nanoTime();
                  tick();
                  plugin
                      .getPerformanceMonitor()
                      .record("animation tick", started, running.size() + " active animations");
                },
                1L,
                1L);
  }

  private void ensureTriggerTask() {
    if (triggerTask != null) {
      return;
    }
    triggerTask =
        Bukkit.getScheduler()
            .runTaskTimer(
                plugin,
                () -> {
                  long started = System.nanoTime();
                  tickTriggers();
                  plugin
                      .getPerformanceMonitor()
                      .record(
                          "animation trigger scan",
                          started,
                          storage.all().size() + " animation objects");
                },
                1L,
                1L);
  }

  private void tickTriggers() {
    renderWindObjects();
    if (Bukkit.getCurrentTick() % 10 != 0) {
      return;
    }
    for (AnimatedVoxelObject object : storage.all()) {
      if (running.containsKey(key(object.id))) {
        continue;
      }
      World world = Bukkit.getWorld(object.world);
      if (world == null) {
        continue;
      }
      for (VoxelAnimation animation : object.animations) {
        AnimationTriggerCause cause = triggeredCause(world, object, animation);
        if (cause != null && triggerReady(object, animation, cause)) {
          if (playTriggered(object, animation, cause, false)) {
            markTriggerCooldown(object, animation, cause);
          }
          break;
        }
        String trigger =
            animation.trigger == null ? "MANUAL" : animation.trigger.toUpperCase(Locale.ROOT);
        if ("PLAYER_ENTER".equals(trigger) || "PLAYER_DISTANCE".equals(trigger)) {
          if (playerDistanceTriggered(world, object, animation)) {
            AnimationTriggerCause legacy = legacyTriggerCause(animation, "PLAYER_DISTANCE");
            if (triggerReady(object, animation, legacy)
                && playTriggered(object, animation, legacy, false)) {
              markTriggerCooldown(object, animation, legacy);
            }
            break;
          }
        } else if ("REDSTONE".equals(trigger) && redstoneTriggered(world, object, animation)) {
          AnimationTriggerCause legacy = legacyTriggerCause(animation, "REDSTONE");
          if (triggerReady(object, animation, legacy)
              && playTriggered(object, animation, legacy, false)) {
            markTriggerCooldown(object, animation, legacy);
          }
          break;
        }
      }
    }
  }

  private AnimationTriggerCause triggeredCause(
      World fallbackWorld, AnimatedVoxelObject object, VoxelAnimation animation) {
    if (animation.triggerCauses == null || animation.triggerCauses.isEmpty()) {
      return null;
    }
    for (AnimationTriggerCause cause : animation.triggerCauses) {
      String type = cause.type == null ? "MANUAL" : cause.type.toUpperCase(Locale.ROOT);
      if ("PLAYER_DISTANCE".equals(type) || "PLAYER_ENTER".equals(type)) {
        if (playerDistanceTriggered(fallbackWorld, object, cause.radius, cause.playerCount)) {
          return cause;
        }
      } else if ("REDSTONE".equals(type) && redstoneCauseTriggered(fallbackWorld, cause)) {
        return cause;
      } else if (("PROBABILITY".equals(type) || "TIMER".equals(type))
          && probabilityTriggered(cause)) {
        return cause;
      } else if ("ENTITY_NEARBY".equals(type)
          && entityNearbyTriggered(fallbackWorld, object, cause)) {
        return cause;
      }
    }
    return null;
  }

  private AnimationTriggerCause legacyTriggerCause(VoxelAnimation animation, String type) {
    AnimationTriggerCause cause = new AnimationTriggerCause(type);
    cause.radius = animation.triggerRadius;
    cause.playerCount = animation.triggerPlayerCount;
    return cause;
  }

  private boolean triggerReady(
      AnimatedVoxelObject object, VoxelAnimation animation, AnimationTriggerCause cause) {
    return Bukkit.getCurrentTick()
        >= triggerCooldownUntil.getOrDefault(triggerCooldownKey(object, animation, cause), 0L);
  }

  private void markTriggerCooldown(
      AnimatedVoxelObject object, VoxelAnimation animation, AnimationTriggerCause cause) {
    int cooldown = cause == null ? 40 : Math.max(0, cause.cooldownTicks);
    triggerCooldownUntil.put(
        triggerCooldownKey(object, animation, cause), (long) Bukkit.getCurrentTick() + cooldown);
  }

  private String triggerCooldownKey(
      AnimatedVoxelObject object, VoxelAnimation animation, AnimationTriggerCause cause) {
    String type =
        cause == null || cause.type == null ? "MANUAL" : cause.type.toUpperCase(Locale.ROOT);
    String linked =
        cause == null || cause.x == null
            ? ""
            : ":" + cause.world + ":" + cause.x + ":" + cause.y + ":" + cause.z;
    return key(object.id) + ":" + animation.id.toLowerCase(Locale.ROOT) + ":" + type + linked;
  }

  private boolean probabilityTriggered(AnimationTriggerCause cause) {
    int interval = Math.max(1, cause.intervalTicks);
    if (Bukkit.getCurrentTick() % interval != 0) {
      return false;
    }
    return Math.random() <= clamp(cause.chance, 0.0, 1.0);
  }

  private boolean entityNearbyTriggered(
      World fallbackWorld, AnimatedVoxelObject object, AnimationTriggerCause cause) {
    World world = fallbackWorld;
    if (cause.world != null && !cause.world.isBlank()) {
      World linkedWorld = Bukkit.getWorld(cause.world);
      if (linkedWorld != null) {
        world = linkedWorld;
      }
    }
    if (world == null) {
      return false;
    }
    double radius = Math.max(1, cause.radius);
    Location center = new Location(world, object.originX, object.originY, object.originZ);
    for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
      if (entity instanceof org.bukkit.entity.Display) {
        continue;
      }
      if (!cause.includeInvisibleEntities
          && entity instanceof LivingEntity living
          && living.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY)) {
        continue;
      }
      return true;
    }
    return false;
  }

  private void renderWindObjects() {
    long tick = Bukkit.getCurrentTick();
    for (AnimatedVoxelObject object : storage.all()) {
      if (!hasIdleWind(object) || running.containsKey(key(object.id))) {
        continue;
      }
      World world = Bukkit.getWorld(object.world);
      if (world == null || object.parts.isEmpty()) {
        continue;
      }
      double intensity = weatherWindIntensity(world, object) + localGustIntensity(world, object);
      renderer.renderPartsWithOffsets(
          object, object.parts, windOffsets(object, object.parts, tick, intensity));
    }
  }

  private boolean windActive(AnimatedVoxelObject object, VoxelAnimation animation) {
    return object != null
        && ("WIND".equalsIgnoreCase(object.objectType)
            || (animation != null && animation.windEnabled));
  }

  private boolean hasIdleWind(AnimatedVoxelObject object) {
    if (object == null) {
      return false;
    }
    if ("WIND".equalsIgnoreCase(object.objectType)) {
      return true;
    }
    VoxelAnimation animation = object.animation("main");
    if (animation == null && !object.animations.isEmpty()) {
      animation = object.animations.get(0);
    }
    return animation != null && animation.windEnabled;
  }

  private void playAnimationSound(AnimatedVoxelObject object, VoxelAnimation animation) {
    if (object == null
        || animation == null
        || animation.sound == null
        || animation.sound.isBlank()) {
      return;
    }
    World world = Bukkit.getWorld(object.world);
    if (world == null) {
      return;
    }
    try {
      Sound sound = Sound.valueOf(animation.sound.toUpperCase(Locale.ROOT));
      world.playSound(
          new Location(world, object.originX, object.originY, object.originZ),
          sound,
          animation.soundVolume,
          animation.soundPitch);
    } catch (IllegalArgumentException ignored) {
      plugin
          .getLogger()
          .warning(
              "Unknown animation sound '"
                  + animation.sound
                  + "' on "
                  + object.id
                  + "/"
                  + animation.id);
    }
  }

  private Map<String, double[]> windOffsets(
      AnimatedVoxelObject object, List<AnimatedVoxelPart> source, long tick) {
    World world = Bukkit.getWorld(object.world);
    double intensity =
        world == null
            ? object.windIdleIntensity
            : weatherWindIntensity(world, object) + localGustIntensity(world, object);
    return windOffsets(object, source, tick, intensity);
  }

  private Map<String, double[]> windOffsets(
      AnimatedVoxelObject object, List<AnimatedVoxelPart> source, long tick, double intensity) {
    WindTopologyCache topology = windTopology(object, source, tick);
    Map<String, AnimatedVoxelPart> sourceById = topology.sourceById;
    Map<String, Integer> depths = topology.depths;
    World world = Bukkit.getWorld(object.world);
    double[] windVector = windDirectionVector(world, object);
    Map<String, double[]> offsets = new HashMap<>();
    for (AnimatedVoxelPart part : source) {
      offsets.put(
          part.id,
          baseWindOffset(
              object, part, tick, intensity, depths.getOrDefault(part.id, 1), windVector));
    }
    smoothWindTargets(object, sourceById, offsets);
    applyWindConstraints(object, sourceById, offsets);
    return offsets;
  }

  private WindTopologyCache windTopology(
      AnimatedVoxelObject object, List<AnimatedVoxelPart> source, long tick) {
    String objectKey = key(object.id);
    int linkCount = object.windLinks == null ? 0 : object.windLinks.size();
    int connectorCount =
        object.windConnectorPartIds == null ? 0 : object.windConnectorPartIds.size();
    WindTopologyCache cached = windTopologyByObject.get(objectKey);
    if (cached != null
        && cached.source == source
        && cached.partCount == source.size()
        && cached.linkCount == linkCount
        && cached.connectorCount == connectorCount
        && tick - cached.createdTick < 20L) {
      return cached;
    }
    WindTopologyCache rebuilt =
        new WindTopologyCache(
            source,
            source.size(),
            linkCount,
            connectorCount,
            tick,
            partsById(source),
            windDepths(object));
    windTopologyByObject.put(objectKey, rebuilt);
    return rebuilt;
  }

  private double[] baseWindOffset(
      AnimatedVoxelObject object,
      AnimatedVoxelPart part,
      long tick,
      double intensity,
      int depth,
      double[] windVector) {
    if (object.windConnectorPartIds.contains(part.id)) {
      return new double[] {0.0, 0.0, 0.0};
    }
    boolean node = object.windNodePartIds.isEmpty() || object.windNodePartIds.contains(part.id);
    if (!node) {
      return new double[] {0.0, 0.0, 0.0};
    }
    double phase =
        tick * 0.075
            - depth * 0.32
            + part.localX * 0.006
            + part.localY * 0.004
            + part.localZ * 0.008;
    double damped = 1.0 - clamp(object.windDamping, 0.0, 0.95);
    double displacement = Math.max(object.windDisplacement, 1.5);
    double looseness = 0.75 + Math.min(depth, 8) * 0.10;
    double dirX = windVector[0];
    double dirZ = windVector[1];
    double sideX = -dirZ;
    double sideZ = dirX;
    double push = Math.sin(phase) * displacement * intensity * damped * looseness;
    double roll = Math.cos(phase * 0.82) * displacement * intensity * 0.55 * damped * looseness;
    return new double[] {
      dirX * push + sideX * roll,
      Math.sin(phase - 0.55) * displacement * intensity * 0.22 * damped * looseness,
      dirZ * push + sideZ * roll
    };
  }

  private void smoothWindTargets(
      AnimatedVoxelObject object,
      Map<String, AnimatedVoxelPart> sourceById,
      Map<String, double[]> offsets) {
    if (object.windLinks == null || object.windLinks.isEmpty()) {
      return;
    }
    double coherence = clamp(object.windCoherence <= 0 ? 0.85 : object.windCoherence, 0.0, 1.0);
    for (int iteration = 0; iteration < 3; iteration++) {
      Map<String, double[]> next = new HashMap<>();
      Map<String, Integer> counts = new HashMap<>();
      for (WindLink link : object.windLinks) {
        AnimatedVoxelPart from = sourceById.get(link.fromPartId);
        AnimatedVoxelPart to = sourceById.get(link.toPartId);
        if (from == null || to == null) {
          continue;
        }
        addOffset(next, counts, from.id, offsets.getOrDefault(to.id, new double[] {0.0, 0.0, 0.0}));
        addOffset(next, counts, to.id, offsets.getOrDefault(from.id, new double[] {0.0, 0.0, 0.0}));
      }
      for (Map.Entry<String, double[]> entry : next.entrySet()) {
        String id = entry.getKey();
        if (object.windConnectorPartIds.contains(id)) {
          continue;
        }
        double count = Math.max(1, counts.getOrDefault(id, 1));
        double[] current = offsets.getOrDefault(id, new double[] {0.0, 0.0, 0.0});
        double[] average = entry.getValue();
        current[0] = current[0] * (1.0 - coherence) + (average[0] / count) * coherence;
        current[1] = current[1] * (1.0 - coherence) + (average[1] / count) * coherence;
        current[2] = current[2] * (1.0 - coherence) + (average[2] / count) * coherence;
      }
    }
  }

  private void addOffset(
      Map<String, double[]> totals, Map<String, Integer> counts, String id, double[] offset) {
    double[] total = totals.computeIfAbsent(id, ignored -> new double[] {0.0, 0.0, 0.0});
    total[0] += offset[0];
    total[1] += offset[1];
    total[2] += offset[2];
    counts.put(id, counts.getOrDefault(id, 0) + 1);
  }

  private Map<String, Integer> windDepths(AnimatedVoxelObject object) {
    Map<String, Integer> depths = new HashMap<>();
    java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
    for (String connectorId : object.windConnectorPartIds) {
      depths.put(connectorId, 0);
      queue.add(connectorId);
    }
    while (!queue.isEmpty()) {
      String current = queue.removeFirst();
      int nextDepth = depths.getOrDefault(current, 0) + 1;
      for (WindLink link : object.windLinks) {
        String next = null;
        if (current.equals(link.fromPartId)) {
          next = link.toPartId;
        } else if (current.equals(link.toPartId)) {
          next = link.fromPartId;
        }
        if (next != null && !depths.containsKey(next)) {
          depths.put(next, nextDepth);
          queue.add(next);
        }
      }
    }
    return depths;
  }

  private void applyWindConstraints(
      AnimatedVoxelObject object,
      Map<String, AnimatedVoxelPart> sourceById,
      Map<String, double[]> offsets) {
    if (object.windLinks == null || object.windLinks.isEmpty()) {
      return;
    }
    double stiffness = clamp(object.windStiffness <= 0 ? 0.9 : object.windStiffness, 0.05, 1.0);
    for (int iteration = 0; iteration < 14; iteration++) {
      for (WindLink link : object.windLinks) {
        AnimatedVoxelPart from = sourceById.get(link.fromPartId);
        AnimatedVoxelPart to = sourceById.get(link.toPartId);
        if (from == null || to == null) {
          continue;
        }
        double rest = link.restDistance > 0.0 ? link.restDistance : partCenterDistance(from, to);
        if (rest <= 0.0) {
          continue;
        }
        double[] fromOffset =
            offsets.computeIfAbsent(from.id, ignored -> new double[] {0.0, 0.0, 0.0});
        double[] toOffset = offsets.computeIfAbsent(to.id, ignored -> new double[] {0.0, 0.0, 0.0});
        double fromX = partCenterX(from) + fromOffset[0];
        double fromY = partCenterY(from) + fromOffset[1];
        double fromZ = partCenterZ(from) + fromOffset[2];
        double toX = partCenterX(to) + toOffset[0];
        double toY = partCenterY(to) + toOffset[1];
        double toZ = partCenterZ(to) + toOffset[2];
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 0.0001) {
          continue;
        }
        double correction = (distance - rest) / distance * stiffness;
        boolean fromMovable = windConstraintMovable(object, from.id);
        boolean toMovable = windConstraintMovable(object, to.id);
        double fromShare = fromMovable && toMovable ? 0.5 : fromMovable ? 1.0 : 0.0;
        double toShare = fromMovable && toMovable ? 0.5 : toMovable ? 1.0 : 0.0;
        if (fromShare > 0.0) {
          fromOffset[0] += dx * correction * fromShare;
          fromOffset[1] += dy * correction * fromShare;
          fromOffset[2] += dz * correction * fromShare;
        }
        if (toShare > 0.0) {
          toOffset[0] -= dx * correction * toShare;
          toOffset[1] -= dy * correction * toShare;
          toOffset[2] -= dz * correction * toShare;
        }
      }
    }
  }

  private boolean windConstraintMovable(AnimatedVoxelObject object, String partId) {
    return !object.windConnectorPartIds.contains(partId)
        && (object.windNodePartIds.isEmpty() || object.windNodePartIds.contains(partId));
  }

  private double partCenterDistance(AnimatedVoxelPart first, AnimatedVoxelPart second) {
    double dx = partCenterX(first) - partCenterX(second);
    double dy = partCenterY(first) - partCenterY(second);
    double dz = partCenterZ(first) - partCenterZ(second);
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  private double partCenterX(AnimatedVoxelPart part) {
    return part.localX + part.size / 2.0;
  }

  private double partCenterY(AnimatedVoxelPart part) {
    return part.localY + part.size / 2.0;
  }

  private double partCenterZ(AnimatedVoxelPart part) {
    return part.localZ + part.size / 2.0;
  }

  private double weatherWindIntensity(World world, AnimatedVoxelObject object) {
    if (world.isThundering()) {
      return object.windThunderIntensity;
    }
    if (world.hasStorm()) {
      return object.windRainIntensity;
    }
    return object.windSunIntensity + object.windIdleIntensity;
  }

  private double[] windDirectionVector(World world, AnimatedVoxelObject object) {
    double radians = Math.toRadians(object.windDirectionYaw);
    double x = -Math.sin(radians) * object.windDirectionStrength;
    double z = Math.cos(radians) * object.windDirectionStrength;
    if (world != null) {
      if (object.windEnvironmentReactive) {
        double[] environment = environmentWindVector(world, object);
        x += environment[0] * object.windEnvironmentStrength;
        z += environment[1] * object.windEnvironmentStrength;
      }
      double[] local = localWindVector(world, object);
      x += local[0];
      z += local[1];
    }
    double length = Math.sqrt(x * x + z * z);
    if (length < 0.0001) {
      return new double[] {0.0, 1.0};
    }
    return new double[] {x / length, z / length};
  }

  private double[] environmentWindVector(World world, AnimatedVoxelObject object) {
    int originX = (int) Math.floor(object.originX);
    int originY = (int) Math.floor(object.originY);
    int originZ = (int) Math.floor(object.originZ);
    double x =
        openness(world, originX + 1, originY, originZ)
            - openness(world, originX - 1, originY, originZ);
    double z =
        openness(world, originX, originY, originZ + 1)
            - openness(world, originX, originY, originZ - 1);
    double length = Math.sqrt(x * x + z * z);
    if (length < 0.0001) {
      return new double[] {0.0, 0.0};
    }
    return new double[] {x / length, z / length};
  }

  private double openness(World world, int x, int y, int z) {
    int open = 0;
    for (int dy = -1; dy <= 2; dy++) {
      if (y + dy < world.getMinHeight() || y + dy >= world.getMaxHeight()) {
        continue;
      }
      Block block = world.getBlockAt(x, y + dy, z);
      if (block.getType().isAir()
          || block.getType() == Material.BARRIER
          || !block.getType().isSolid()) {
        open++;
      }
    }
    return open / 4.0;
  }

  private double[] localWindVector(World world, AnimatedVoxelObject object) {
    Location origin = new Location(world, object.originX, object.originY, object.originZ);
    double x = 0.0;
    double z = 0.0;
    for (Player player : world.getPlayers()) {
      double distance = player.getLocation().distance(origin);
      if (distance > 6.0) {
        continue;
      }
      Vector velocity = player.getVelocity();
      double speed = velocity.length();
      double strength =
          (player.isSprinting() ? 1.2 : player.isSneaking() ? 0.35 : 0.7)
              * object.windPlayerReactionStrength
              * (1.0 - distance / 6.0);
      if (speed > 0.025) {
        x += velocity.getX() / speed * strength;
        z += velocity.getZ() / speed * strength;
      } else {
        Vector away = origin.toVector().subtract(player.getLocation().toVector());
        double awayLength = Math.sqrt(away.getX() * away.getX() + away.getZ() * away.getZ());
        if (awayLength > 0.0001) {
          x += away.getX() / awayLength * strength * 0.45;
          z += away.getZ() / awayLength * strength * 0.45;
        }
      }
    }
    for (Entity entity : world.getNearbyEntities(origin, 6, 6, 6)) {
      if (entity instanceof Projectile) {
        Vector velocity = entity.getVelocity();
        double speed = velocity.length();
        if (speed > 0.025) {
          double distance = entity.getLocation().distance(origin);
          double strength =
              Math.min(1.5, speed)
                  * object.windProjectileReactionStrength
                  * Math.max(0.0, 1.0 - distance / 6.0);
          x += velocity.getX() / speed * strength;
          z += velocity.getZ() / speed * strength;
        }
      } else {
        Vector velocity = entity.getVelocity();
        double speed = velocity.length();
        if (speed > 0.08) {
          double distance = entity.getLocation().distance(origin);
          double strength =
              Math.min(1.0, speed)
                  * object.windVelocityReactionStrength
                  * Math.max(0.0, 1.0 - distance / 6.0);
          x += velocity.getX() / speed * strength;
          z += velocity.getZ() / speed * strength;
        }
      }
    }
    return new double[] {x, z};
  }

  private double localGustIntensity(World world, AnimatedVoxelObject object) {
    Location origin = new Location(world, object.originX, object.originY, object.originZ);
    double gust = 0.0;
    for (Player player : world.getPlayers()) {
      double distance = player.getLocation().distance(origin);
      if (distance > 5.0) {
        continue;
      }
      double movement =
          (player.isSprinting() ? 0.8 : player.isSneaking() ? 0.2 : 0.45)
              * object.windPlayerReactionStrength;
      gust = Math.max(gust, movement * (1.0 - distance / 5.0));
    }
    for (Entity entity : world.getNearbyEntities(origin, 5, 5, 5)) {
      if (entity instanceof Projectile) {
        double speed = entity.getVelocity().length();
        gust = Math.max(gust, Math.min(1.2, speed) * object.windProjectileReactionStrength);
      } else {
        double speed = entity.getVelocity().length();
        if (speed > 0.08) {
          gust = Math.max(gust, Math.min(0.8, speed) * object.windVelocityReactionStrength);
        }
      }
    }
    return gust;
  }

  private boolean playerDistanceTriggered(
      World world, AnimatedVoxelObject object, VoxelAnimation animation) {
    return playerDistanceTriggered(
        world, object, animation.triggerRadius, animation.triggerPlayerCount);
  }

  private boolean playerDistanceTriggered(
      World world, AnimatedVoxelObject object, int radius, int playerCount) {
    double radiusSquared = Math.max(1, radius) * Math.max(1, radius);
    int count = 0;
    Location origin = new Location(world, object.originX, object.originY, object.originZ);
    for (Player player : world.getPlayers()) {
      if (player.getLocation().distanceSquared(origin) <= radiusSquared) {
        count++;
        if (count >= Math.max(1, playerCount)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean redstoneCauseTriggered(World fallbackWorld, AnimationTriggerCause cause) {
    World world = cause.world == null ? fallbackWorld : Bukkit.getWorld(cause.world);
    if (world == null || cause.x == null || cause.y == null || cause.z == null) {
      return false;
    }
    Block block = world.getBlockAt(cause.x, cause.y, cause.z);
    return block.isBlockPowered() || block.isBlockIndirectlyPowered();
  }

  private boolean redstoneTriggered(
      World fallbackWorld, AnimatedVoxelObject object, VoxelAnimation animation) {
    World world =
        animation.redstoneWorld == null ? fallbackWorld : Bukkit.getWorld(animation.redstoneWorld);
    if (world == null) {
      return false;
    }
    int x = animation.redstoneX == null ? (int) Math.floor(object.originX) : animation.redstoneX;
    int y = animation.redstoneY == null ? (int) Math.floor(object.originY) : animation.redstoneY;
    int z = animation.redstoneZ == null ? (int) Math.floor(object.originZ) : animation.redstoneZ;
    Block block = world.getBlockAt(x, y, z);
    return block.isBlockPowered() || block.isBlockIndirectlyPowered();
  }

  private void stopTaskIfIdle() {
    if (!running.isEmpty() || !runningSequences.isEmpty() || task == null) {
      return;
    }
    task.cancel();
    task = null;
  }

  private VoxelAnimation ensureAnimation(AnimatedVoxelObject object, String animationId) {
    VoxelAnimation animation = object.animation(animationId);
    if (animation == null) {
      animation = new VoxelAnimation(animationId);
      object.animations.add(animation);
    }
    return animation;
  }

  private VoxelFrame frameAt(VoxelAnimation animation, int tick) {
    for (VoxelFrame frame : animation.voxelFrames) {
      if (frame.tick == tick) {
        return frame;
      }
    }
    VoxelFrame frame = new VoxelFrame(tick);
    animation.voxelFrames.add(frame);
    return frame;
  }

  private AnimatedVoxelPart findPart(AnimatedVoxelObject object, String partId) {
    return findPart(object.parts, partId);
  }

  private AnimatedVoxelPart findPart(List<AnimatedVoxelPart> parts, String partId) {
    for (AnimatedVoxelPart part : parts) {
      if (part.id.equalsIgnoreCase(partId)) {
        return part;
      }
    }
    return null;
  }

  private String key(String id) {
    return id.toLowerCase(Locale.ROOT);
  }

  private String playerKey(Player player) {
    return player.getUniqueId().toString();
  }

  private record WindTopologyCache(
      List<AnimatedVoxelPart> source,
      int partCount,
      int linkCount,
      int connectorCount,
      long createdTick,
      Map<String, AnimatedVoxelPart> sourceById,
      Map<String, Integer> depths) {}

  private static class RunningAnimation {
    private final String objectId;
    private final String animationId;
    private double tick;
    private int lastTick = -1;
    private String lastStateRenderKey = "";
    private boolean reverse = false;
    private boolean allowLoop = true;
    private String afterAction = "DEFAULT";
    private String nextAnimationId = null;

    RunningAnimation(String objectId, String animationId) {
      this.objectId = objectId;
      this.animationId = animationId;
    }
  }

  private static class RunningSequence {
    private final String objectId;
    private final String sequenceId;
    private int stepIndex;
    private long nextActionTick;
    private int delayStartedTick = -1;
    private boolean waitingForAnimation;

    RunningSequence(String objectId, String sequenceId) {
      this.objectId = objectId;
      this.sequenceId = sequenceId;
    }
  }

  private record MaterialChoice(String material, int r, int g, int b) {}

  private record PendingImage(
      String name, String url, List<ImageFrame> frames, Integer startTick, Integer endTick) {}

  private record ImageFrame(BufferedImage image, int delayTicks) {}

  private static final MaterialChoice[] IMAGE_MATERIALS = {
    new MaterialChoice("WHITE_CONCRETE", 207, 213, 214),
    new MaterialChoice("WHITE_WOOL", 234, 236, 237),
    new MaterialChoice("SNOW_BLOCK", 249, 254, 254),
    new MaterialChoice("QUARTZ_BLOCK", 236, 229, 221),
    new MaterialChoice("LIGHT_GRAY_CONCRETE", 125, 125, 115),
    new MaterialChoice("LIGHT_GRAY_WOOL", 142, 142, 134),
    new MaterialChoice("CALCITE", 224, 226, 220),
    new MaterialChoice("GRAY_CONCRETE", 55, 58, 62),
    new MaterialChoice("GRAY_WOOL", 62, 68, 71),
    new MaterialChoice("TUFF", 108, 109, 102),
    new MaterialChoice("BLACK_CONCRETE", 8, 10, 15),
    new MaterialChoice("BLACK_WOOL", 21, 21, 26),
    new MaterialChoice("DEEPSLATE", 72, 72, 75),
    new MaterialChoice("RED_CONCRETE", 142, 33, 33),
    new MaterialChoice("RED_WOOL", 160, 39, 34),
    new MaterialChoice("RED_TERRACOTTA", 143, 61, 46),
    new MaterialChoice("ORANGE_CONCRETE", 224, 97, 0),
    new MaterialChoice("ORANGE_WOOL", 240, 118, 19),
    new MaterialChoice("ORANGE_TERRACOTTA", 161, 83, 37),
    new MaterialChoice("YELLOW_CONCRETE", 241, 175, 21),
    new MaterialChoice("YELLOW_WOOL", 249, 198, 40),
    new MaterialChoice("HAY_BLOCK", 166, 136, 38),
    new MaterialChoice("LIME_CONCRETE", 94, 169, 24),
    new MaterialChoice("LIME_WOOL", 112, 185, 25),
    new MaterialChoice("MOSS_BLOCK", 89, 109, 45),
    new MaterialChoice("GREEN_CONCRETE", 73, 91, 36),
    new MaterialChoice("GREEN_WOOL", 85, 110, 27),
    new MaterialChoice("GREEN_TERRACOTTA", 76, 83, 42),
    new MaterialChoice("CYAN_CONCRETE", 21, 119, 136),
    new MaterialChoice("CYAN_WOOL", 21, 137, 145),
    new MaterialChoice("WARPED_WART_BLOCK", 22, 126, 134),
    new MaterialChoice("LIGHT_BLUE_CONCRETE", 36, 137, 199),
    new MaterialChoice("LIGHT_BLUE_WOOL", 58, 175, 217),
    new MaterialChoice("PRISMARINE", 99, 156, 151),
    new MaterialChoice("BLUE_CONCRETE", 44, 46, 143),
    new MaterialChoice("BLUE_WOOL", 53, 57, 157),
    new MaterialChoice("LAPIS_BLOCK", 30, 67, 140),
    new MaterialChoice("PURPLE_CONCRETE", 100, 32, 156),
    new MaterialChoice("PURPLE_WOOL", 121, 42, 172),
    new MaterialChoice("PURPLE_TERRACOTTA", 118, 70, 86),
    new MaterialChoice("MAGENTA_CONCRETE", 169, 48, 159),
    new MaterialChoice("MAGENTA_WOOL", 190, 69, 180),
    new MaterialChoice("MAGENTA_TERRACOTTA", 149, 88, 108),
    new MaterialChoice("PINK_CONCRETE", 214, 101, 143),
    new MaterialChoice("PINK_WOOL", 237, 141, 172),
    new MaterialChoice("PINK_TERRACOTTA", 161, 78, 78),
    new MaterialChoice("BROWN_CONCRETE", 96, 59, 31),
    new MaterialChoice("BROWN_WOOL", 114, 71, 40),
    new MaterialChoice("BROWN_TERRACOTTA", 77, 51, 36),
    new MaterialChoice("OAK_PLANKS", 162, 130, 78),
    new MaterialChoice("SPRUCE_PLANKS", 114, 84, 48),
    new MaterialChoice("SAND", 219, 207, 163),
    new MaterialChoice("SANDSTONE", 217, 203, 158),
    new MaterialChoice("NETHERRACK", 111, 54, 52)
  };

  private record Transform(double x, double y, double z, float yaw) {}

  private record Bounds(
      double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    boolean overlaps(Bounds other) {
      return minX < other.maxX
          && maxX > other.minX
          && minY < other.maxY
          && maxY > other.minY
          && minZ < other.maxZ
          && maxZ > other.minZ;
    }
  }

  public static class EditorState {
    public final String objectId;
    public String animationId;
    public int tick;
    public final java.util.Set<String> editBlockKeys = new java.util.HashSet<>();

    EditorState(String objectId, String animationId, int tick) {
      this.objectId = objectId;
      this.animationId = animationId;
      this.tick = tick;
    }
  }
}
