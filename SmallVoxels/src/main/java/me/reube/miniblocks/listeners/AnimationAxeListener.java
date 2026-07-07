package me.reube.SmallVoxels.listeners;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.managers.AnimationAxeMode;
import me.reube.SmallVoxels.managers.VoxelManager;
import me.reube.SmallVoxels.ui.AnimationEditorGUI;
import me.reube.SmallVoxels.ui.AnimationSequenceGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

public class AnimationAxeListener implements Listener {
  private final SmallVoxels plugin;
  private final AnimationEditorGUI gui;
  private final Map<UUID, CapturePoint> captureFirstCorners = new HashMap<>();
  private final Map<UUID, CapturePoint> imageFirstCorners = new HashMap<>();
  private final Map<UUID, CapturePoint> windFirstCorners = new HashMap<>();
  private final Map<UUID, AnimationAxeMode> lastWindModes = new HashMap<>();
  private BukkitTask previewTask;

  public AnimationAxeListener(SmallVoxels plugin) {
    this.plugin = plugin;
    this.gui = new AnimationEditorGUI(plugin);
    startPreviewTask();
  }

  @EventHandler
  public void onDrop(PlayerDropItemEvent event) {
    Player player = event.getPlayer();
    ItemStack dropped = event.getItemDrop().getItemStack();
    if (!plugin.getAnimationAxeManager().isAnimationAxe(player.getInventory().getItemInMainHand())
        && !plugin.getAnimationAxeManager().isAnimationAxe(dropped)) {
      return;
    }
    event.setCancelled(true);
    if (!canUse(player)) {
      return;
    }
    plugin.getAnimationAxeManager().cycleMode(player);
  }

  @EventHandler
  public void onSwapHands(PlayerSwapHandItemsEvent event) {
    Player player = event.getPlayer();
    if (!plugin
        .getAnimationAxeManager()
        .isAnimationAxe(player.getInventory().getItemInMainHand())) {
      return;
    }
    event.setCancelled(true);
    if (!canUse(player)) {
      return;
    }
    if (!hasAnimationPermission(player, "smallvoxels.animation.settings")) {
      player.sendActionBar(Component.text("No animation GUI permission").color(NamedTextColor.RED));
      return;
    }
    gui.open(player);
  }

  @EventHandler
  public void onInteract(PlayerInteractEvent event) {
    if (event.getHand() != EquipmentSlot.HAND) {
      return;
    }
    Player player = event.getPlayer();
    if (!plugin
        .getAnimationAxeManager()
        .isAnimationAxe(player.getInventory().getItemInMainHand())) {
      return;
    }
    if (!canUse(player)) {
      event.setCancelled(true);
      return;
    }
    if (event.getAction() != Action.LEFT_CLICK_BLOCK
        && event.getAction() != Action.LEFT_CLICK_AIR
        && event.getAction() != Action.RIGHT_CLICK_BLOCK
        && event.getAction() != Action.RIGHT_CLICK_AIR) {
      return;
    }
    event.setCancelled(true);

    AnimationAxeMode mode = plugin.getAnimationAxeManager().mode(player);
    if ((mode == AnimationAxeMode.WIND_CONNECTOR || mode == AnimationAxeMode.WIND_NODE)
        && !hasAnimationPermission(player, "smallvoxels.animation.wind")) {
      player.sendActionBar(
          Component.text("No wind animation permission").color(NamedTextColor.RED));
      return;
    }
    clearWindSelectionOnModeChange(player, mode);
    if (mode == AnimationAxeMode.SELECT_ANIMATION) {
      if (event.getAction() == Action.RIGHT_CLICK_AIR
          || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
        String id = plugin.getAnimationAxeManager().cycleNearbyAnimation(player);
        if (id == null) {
          player.sendActionBar(
              Component.text("No animations within 10 chunks").color(NamedTextColor.YELLOW));
          return;
        }
        plugin.getAnimatedObjectManager().selectEditorObject(player, id);
        player.sendActionBar(
            Component.text("Selected animation: " + id).color(NamedTextColor.AQUA));
        return;
      }

      java.util.List<String> ids = plugin.getAnimationAxeManager().refreshNearbyAnimations(player);
      if (ids.isEmpty()) {
        player.sendActionBar(
            Component.text("No animations within 10 chunks").color(NamedTextColor.YELLOW));
        return;
      }
      String id = plugin.getAnimationAxeManager().currentNearbyAnimation(player);
      plugin.getAnimatedObjectManager().selectEditorObject(player, id);
      player.sendMessage(
          Component.text("Nearby animations: " + String.join(", ", ids))
              .color(NamedTextColor.AQUA));
      player.sendActionBar(Component.text("Selected animation: " + id).color(NamedTextColor.AQUA));
      return;
    }

    if (mode == AnimationAxeMode.EDIT_TOGGLE) {
      if (event.getAction() == Action.RIGHT_CLICK_AIR
          || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
        plugin.getAnimationAxeManager().toggleEditing(player);
      } else {
        player.sendActionBar(
            Component.text("Right-click to toggle animation editing")
                .color(
                    plugin.getAnimationAxeManager().isEditing(player)
                        ? NamedTextColor.GREEN
                        : NamedTextColor.RED));
      }
      return;
    }

    if (mode == AnimationAxeMode.WIND_CONNECTOR || mode == AnimationAxeMode.WIND_NODE) {
      CapturePoint point = windCapturePoint(player, event.getClickedBlock());
      if (point == null) {
        player.sendActionBar(
            Component.text("Aim at a voxel or block to mark it").color(NamedTextColor.YELLOW));
        return;
      }
      boolean connector = mode == AnimationAxeMode.WIND_CONNECTOR;
      if (event.getAction() == Action.LEFT_CLICK_BLOCK
          || event.getAction() == Action.LEFT_CLICK_AIR) {
        int marked =
            plugin
                .getAnimatedObjectManager()
                .markWindPoint(
                    player,
                    point.world,
                    point.worldX,
                    point.worldY,
                    point.worldZ,
                    point.size,
                    connector);
        plugin
            .getAnimatedObjectManager()
            .showEditorCaptureVoxelPreview(
                player,
                point.world,
                point.worldX,
                point.worldY,
                point.worldZ,
                point.size,
                point.worldX,
                point.worldY,
                point.worldZ,
                point.size);
        player.sendActionBar(
            Component.text(
                    marked > 0
                        ? "Marked " + (connector ? "connector" : "node") + " point"
                        : "Aim at a captured or real voxel/block")
                .color(marked > 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
        return;
      }
      CapturePoint first = windFirstCorners.get(player.getUniqueId());
      if (first == null || !first.sameWorld(point)) {
        windFirstCorners.put(player.getUniqueId(), point);
        plugin
            .getAnimatedObjectManager()
            .showEditorCaptureVoxelPreview(
                player,
                point.world,
                point.worldX,
                point.worldY,
                point.worldZ,
                point.size,
                point.worldX,
                point.worldY,
                point.worldZ,
                point.size);
        player.sendActionBar(
            Component.text(
                    (connector ? "Connector" : "Node") + " corner 1 set. Right-click corner 2.")
                .color(NamedTextColor.AQUA));
        return;
      }
      int marked =
          plugin
              .getAnimatedObjectManager()
              .markWindRegion(
                  player,
                  first.world,
                  first.worldX,
                  first.worldY,
                  first.worldZ,
                  first.size,
                  point.worldX,
                  point.worldY,
                  point.worldZ,
                  point.size,
                  connector);
      plugin.getVoxelPreviewManager().clearLive(player);
      windFirstCorners.put(player.getUniqueId(), point);
      player.sendActionBar(
          Component.text(
                  marked > 0
                      ? "Marked "
                          + marked
                          + (connector ? " connector" : " node")
                          + " part(s). Right-click next corner to keep selecting."
                      : "Select captured wind voxels/blocks first")
              .color(marked > 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
      return;
    }

    if (mode == AnimationAxeMode.CAPTURE_FRAME) {
      me.reube.SmallVoxels.managers.animation.VoxelAnimation animation =
          plugin.getAnimatedObjectManager().editorAnimation(player);
      String transition = animation == null ? "HARD" : animation.defaultTransition;
      String captureMode = animation == null ? "REPLACE" : animation.captureMode;
      CapturePoint clicked = capturePoint(player, event.getClickedBlock());
      if (clicked == null) {
        player.sendActionBar(
            Component.text("Click corner 1, then corner 2").color(NamedTextColor.YELLOW));
        return;
      }

      CapturePoint first = captureFirstCorners.remove(player.getUniqueId());
      if (first == null || !first.sameWorld(clicked)) {
        captureFirstCorners.put(player.getUniqueId(), clicked);
        plugin
            .getAnimatedObjectManager()
            .showEditorCaptureVoxelPreview(
                player,
                clicked.world,
                clicked.worldX,
                clicked.worldY,
                clicked.worldZ,
                clicked.size,
                clicked.worldX,
                clicked.worldY,
                clicked.worldZ,
                clicked.size);
        player.sendActionBar(
            Component.text("Capture corner 1 set. Click corner 2.").color(NamedTextColor.AQUA));
        return;
      }

      boolean captured =
          plugin
              .getAnimatedObjectManager()
              .captureEditorVoxelRegionState(
                  player,
                  first.world,
                  first.worldX,
                  first.worldY,
                  first.worldZ,
                  first.size,
                  clicked.worldX,
                  clicked.worldY,
                  clicked.worldZ,
                  clicked.size,
                  transition);
      plugin.getVoxelPreviewManager().clearLive(player);
      player.sendActionBar(
          Component.text(
                  captured
                      ? transition + " / " + captureMode + " region keyframe captured"
                      : "Select an animation and turn editing ON")
              .color(captured ? NamedTextColor.GREEN : NamedTextColor.RED));
      return;
    }

    if (mode == AnimationAxeMode.IMAGE_FRAME) {
      if (!plugin.getAnimatedObjectManager().hasPendingImage(player)) {
        player.sendActionBar(
            Component.text("Use /sv animation image <name> <url> first")
                .color(NamedTextColor.YELLOW));
        return;
      }
      CapturePoint clicked = capturePoint(player, event.getClickedBlock());
      if (clicked == null) {
        player.sendActionBar(
            Component.text("Drag the target surface: corner 1, then corner 2")
                .color(NamedTextColor.YELLOW));
        return;
      }
      CapturePoint first = imageFirstCorners.remove(player.getUniqueId());
      if (first == null || !first.sameWorld(clicked)) {
        imageFirstCorners.put(player.getUniqueId(), clicked);
        plugin
            .getAnimatedObjectManager()
            .showEditorCaptureVoxelPreview(
                player,
                clicked.world,
                clicked.worldX,
                clicked.worldY,
                clicked.worldZ,
                clicked.size,
                clicked.worldX,
                clicked.worldY,
                clicked.worldZ,
                clicked.size);
        String imageName = plugin.getAnimatedObjectManager().queuedImageName(player);
        player.sendActionBar(
            Component.text("Image " + imageName + " corner 1 set. Click corner 2.")
                .color(NamedTextColor.AQUA));
        return;
      }
      String result =
          plugin
              .getAnimatedObjectManager()
              .importPendingImageFrame(
                  player,
                  first.world.getName(),
                  first.worldX,
                  first.worldY,
                  first.worldZ,
                  first.size,
                  clicked.worldX,
                  clicked.worldY,
                  clicked.worldZ,
                  clicked.size);
      plugin.getVoxelPreviewManager().clearLive(player);
      player.sendActionBar(
          Component.text(
                  result == null ? "Could not paste image frame" : "Pasted image frame: " + result)
              .color(result == null ? NamedTextColor.RED : NamedTextColor.GREEN));
      return;
    }

    if (mode == AnimationAxeMode.SEQUENCE) {
      if (event.getAction() == Action.RIGHT_CLICK_AIR
          || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
        new AnimationSequenceGUI(plugin).open(player);
        return;
      }
      player.sendActionBar(
          Component.text("Right-click to open the sequence editor").color(NamedTextColor.AQUA));
      return;
    }

    if (mode == AnimationAxeMode.PLAY) {
      boolean playing = plugin.getAnimatedObjectManager().playEditor(player);
      player.sendActionBar(
          Component.text(playing ? "Animation playing" : "Select an animation first")
              .color(playing ? NamedTextColor.GREEN : NamedTextColor.RED));
    }
  }

  private boolean canUse(Player player) {
    if (hasAnimationPermission(player, "smallvoxels.animation.get")
        || hasAnimationPermission(player, "smallvoxels.animation.manage")
        || hasAnimationPermission(player, "smallvoxels.animation.settings")) {
      return true;
    }
    player.sendActionBar(Component.text("No animation permission").color(NamedTextColor.RED));
    return false;
  }

  private boolean hasAnimationPermission(Player player, String permission) {
    return player.hasPermission("smallvoxels.admin")
        || player.hasPermission("smallvoxels.animation.use")
        || player.hasPermission(permission);
  }

  private void clearWindSelectionOnModeChange(Player player, AnimationAxeMode mode) {
    AnimationAxeMode previous = lastWindModes.put(player.getUniqueId(), mode);
    if (previous != null && previous != mode) {
      windFirstCorners.remove(player.getUniqueId());
      if (previous == AnimationAxeMode.WIND_CONNECTOR || previous == AnimationAxeMode.WIND_NODE) {
        plugin.getVoxelPreviewManager().clearLive(player);
      }
    }
  }

  private void startPreviewTask() {
    previewTask =
        plugin
            .getServer()
            .getScheduler()
            .runTaskTimer(
                plugin,
                () -> {
                  for (Player player : plugin.getServer().getOnlinePlayers()) {
                    if (!plugin
                        .getAnimationAxeManager()
                        .isAnimationAxe(player.getInventory().getItemInMainHand())) {
                      captureFirstCorners.remove(player.getUniqueId());
                      imageFirstCorners.remove(player.getUniqueId());
                      windFirstCorners.remove(player.getUniqueId());
                      lastWindModes.remove(player.getUniqueId());
                      continue;
                    }
                    AnimationAxeMode mode = plugin.getAnimationAxeManager().mode(player);
                    boolean windSelect =
                        mode == AnimationAxeMode.WIND_CONNECTOR
                            || mode == AnimationAxeMode.WIND_NODE;
                    boolean imageSelect = mode == AnimationAxeMode.IMAGE_FRAME;
                    if (mode != AnimationAxeMode.CAPTURE_FRAME && !windSelect && !imageSelect) {
                      captureFirstCorners.remove(player.getUniqueId());
                      imageFirstCorners.remove(player.getUniqueId());
                      windFirstCorners.remove(player.getUniqueId());
                      continue;
                    }
                    CapturePoint first =
                        windSelect
                            ? windFirstCorners.get(player.getUniqueId())
                            : imageSelect
                                ? imageFirstCorners.get(player.getUniqueId())
                                : captureFirstCorners.get(player.getUniqueId());
                    CapturePoint target =
                        windSelect
                            ? windCapturePoint(player, player.getTargetBlockExact(6))
                            : capturePoint(player, player.getTargetBlockExact(6));
                    if (first == null) {
                      if (windSelect && target != null) {
                        plugin
                            .getAnimatedObjectManager()
                            .showEditorCaptureVoxelPreview(
                                player,
                                target.world,
                                target.worldX,
                                target.worldY,
                                target.worldZ,
                                target.size,
                                target.worldX,
                                target.worldY,
                                target.worldZ,
                                target.size);
                      }
                      continue;
                    }
                    if (target == null || !first.sameWorld(target)) {
                      continue;
                    }
                    plugin
                        .getAnimatedObjectManager()
                        .showEditorCaptureVoxelPreview(
                            player,
                            first.world,
                            first.worldX,
                            first.worldY,
                            first.worldZ,
                            first.size,
                            target.worldX,
                            target.worldY,
                            target.worldZ,
                            target.size);
                  }
                },
                5L,
                5L);
  }

  private CapturePoint capturePoint(Player player, Block fallbackBlock) {
    VoxelManager.RaycastResult ray =
        VoxelManager.raycastCarvedData(
            player.getEyeLocation(),
            player.getEyeLocation().getDirection().normalize(),
            plugin.getDataManager(),
            8.0);
    if (ray != null) {
      int x;
      int y;
      int z;
      int size;
      if (ray.piece != null) {
        x = ray.piece.x;
        y = ray.piece.y;
        z = ray.piece.z;
        size = ray.piece.size;
      } else {
        int[] coords = VoxelManager.getVoxelCoords(ray.voxelIndex);
        x = coords[0] * 4;
        y = coords[1] * 4;
        z = coords[2] * 4;
        size = 4;
      }
      return new CapturePoint(
          ray.block.getWorld(),
          ray.block.getX() * 16 + x,
          ray.block.getY() * 16 + y,
          ray.block.getZ() * 16 + z,
          size);
    }
    if (fallbackBlock == null) {
      return null;
    }
    return new CapturePoint(
        fallbackBlock.getWorld(),
        fallbackBlock.getX() * 16,
        fallbackBlock.getY() * 16,
        fallbackBlock.getZ() * 16,
        16);
  }

  private CapturePoint windCapturePoint(Player player, Block fallbackBlock) {
    CapturePoint voxelPoint = windVoxelCapturePoint(player);
    if (voxelPoint != null) {
      return voxelPoint;
    }
    int[] windPoint = plugin.getAnimatedObjectManager().windCapturePoint(player);
    if (windPoint != null) {
      return new CapturePoint(
          player.getWorld(), windPoint[0], windPoint[1], windPoint[2], windPoint[3]);
    }
    return capturePoint(player, fallbackBlock);
  }

  private CapturePoint windVoxelCapturePoint(Player player) {
    VoxelManager.RaycastResult ray =
        VoxelManager.raycastCarvedData(
            player.getEyeLocation(),
            player.getEyeLocation().getDirection().normalize(),
            plugin.getDataManager(),
            8.0);
    if (ray == null) {
      return null;
    }
    int x;
    int y;
    int z;
    int size;
    if (ray.piece != null) {
      x = ray.piece.x;
      y = ray.piece.y;
      z = ray.piece.z;
      size = ray.piece.size;
    } else {
      int[] coords = VoxelManager.getVoxelCoords(ray.voxelIndex);
      x = coords[0] * 4;
      y = coords[1] * 4;
      z = coords[2] * 4;
      size = 4;
    }
    return new CapturePoint(
        ray.block.getWorld(),
        ray.block.getX() * 16 + x,
        ray.block.getY() * 16 + y,
        ray.block.getZ() * 16 + z,
        size);
  }

  private record CapturePoint(World world, int worldX, int worldY, int worldZ, int size) {
    boolean sameWorld(CapturePoint other) {
      return other != null && world.equals(other.world);
    }
  }
}
