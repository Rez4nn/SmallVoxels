package me.reube.SmallVoxels.listeners;

import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.managers.ChiselManager;
import me.reube.SmallVoxels.managers.CollisionBlockManager;
import me.reube.SmallVoxels.managers.CollisionStandManager;
import me.reube.SmallVoxels.managers.DataManager;
import me.reube.SmallVoxels.managers.FallingBlockManager;
import me.reube.SmallVoxels.managers.ToolMode;
import me.reube.SmallVoxels.managers.VoxelManager;
import me.reube.SmallVoxels.managers.VoxelPiece;
import me.reube.SmallVoxels.managers.VoxelPieceManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;

public class ChiselInteractionListener implements Listener {

  private final SmallVoxels plugin;
  private final ModeToggleListener modeToggleListener;
  private final Map<String, Long> chiselCooldown = new HashMap<>();
  private final Map<String, Long> scaleCooldown = new HashMap<>();
  private final Map<String, Long> previewCooldown = new HashMap<>();
  private final Map<String, PasteTarget> pendingPaste = new HashMap<>();
  private final Map<String, WorldVoxel> copySelections = new HashMap<>();
  private final Map<String, WorldVoxel> replaceSelections = new HashMap<>();
  private final Map<String, String> replaceSourceMaterials = new HashMap<>();
  private final Map<String, WorldVoxel> moveSelections = new HashMap<>();
  private final Map<String, WorldVoxel> rotateSelections = new HashMap<>();
  private final Map<String, Integer> transformSelectionRadius = new HashMap<>();
  private final Map<String, WorldVoxel> setSelections = new HashMap<>();
  private final Map<String, String> setSelectionSignatures = new HashMap<>();
  private final Map<String, WorldVoxel> removeSelections = new HashMap<>();
  private final Map<String, String> removeSelectionSignatures = new HashMap<>();
  private final Map<String, ToolMode> lastPreviewMode = new HashMap<>();
  private static final String ITEM_DATA_PREFIX = "smallvoxels:item:";
  private static final long CHISEL_COOLDOWN_MS = 200; // 0.2 seconds
  private static final int DEFAULT_MAX_SET_VOXELS = 512;
  private static final int DEFAULT_MAX_REMOVE_VOXELS = 768;
  private static final int DEFAULT_MAX_PREVIEW_VOXELS = 1024;

  public ChiselInteractionListener(SmallVoxels plugin, ModeToggleListener modeToggleListener) {
    this.plugin = plugin;
    this.modeToggleListener = modeToggleListener;
    startPreviewTask();
  }

  private void startPreviewTask() {
    Bukkit.getScheduler()
        .runTaskTimer(
            plugin,
            () -> {
              for (Player player : Bukkit.getOnlinePlayers()) {
                refreshPreview(player);
              }
            },
            2L,
            2L);
  }

  @EventHandler
  public void onBlockPlace(BlockPlaceEvent event) {
    Block block = event.getBlockPlaced();
    if (plugin.getDataManager().hasCarvedData(block)
        && !plugin.getDataManager().getVoxelPieces(block).isEmpty()) {
      modeToggleListener.setStatus(event.getPlayer(), "Voxel data kept");
    }
  }

  @EventHandler
  public void onPlayerInteract(PlayerInteractEvent event) {
    // Detail mode owns block-face clicks and writes to an adjacent surface
    // canvas. Do not also run the legacy carve/place action for that click.
    if (plugin.getDetailManager() != null
        && plugin.getModeToggleListener().getToolMode(event.getPlayer())
            == ToolMode.DETAIL) {
      return;
    }
    Player player = event.getPlayer();
    ItemStack item = player.getInventory().getItemInMainHand();

    ChiselManager chiselManager = plugin.getChiselManager();
    if (!chiselManager.isChiselAxe(item)) {
      return;
    }

    if (!player.hasPermission("smallvoxels.voxel.use")
        && !player.hasPermission("smallvoxels.chisel.use")) {
      event.setCancelled(true);
      modeToggleListener.setStatus(player, "No permission");
      return;
    }

    ToolMode currentMode = modeToggleListener.getToolMode(player);
    clearSelectionsIfModeChanged(player, currentMode);

    if (event.getAction() == Action.RIGHT_CLICK_AIR
        || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
      event.setCancelled(true);
      String key = player.getUniqueId().toString();
      long now = System.currentTimeMillis();
      if (scaleCooldown.containsKey(key) && now - scaleCooldown.get(key) < 200L) {
        return;
      }
      scaleCooldown.put(key, now);
      modeToggleListener.cyclePlayerVoxelScale(player);
      return;
    }

    if (event.getAction() == Action.LEFT_CLICK_AIR) {
      event.setCancelled(true);

      org.bukkit.Location eye = player.getEyeLocation();
      org.bukkit.util.Vector direction = eye.getDirection().normalize();
      DataManager dataManager = plugin.getDataManager();

      VoxelManager.RaycastResult rayResult =
          VoxelManager.raycastThroughBarriers(eye, direction, dataManager);

      if (rayResult == null) {
        ToolMode mode = modeToggleListener.getToolMode(player);
        if (mode == ToolMode.PLACE
            || mode == ToolMode.BRUSH
            || mode == ToolMode.SET
            || mode == ToolMode.MOVE
            || mode == ToolMode.PASTE
            || (mode == ToolMode.REMOVE && modeToggleListener.isRemoveMass(player))) {
          SolidHit hit = rayTraceSolidIgnoringBarriers(player, 6.0);
          if (hit != null && hit.face != null) {
            if (mode == ToolMode.PLACE) {
              placeVoxelOnRealBlockSurface(player, hit.block, hit.face);
            } else if (mode == ToolMode.BRUSH) {
              brushOnRealBlock(player, hit.block, hit.face);
            } else if (mode == ToolMode.SET) {
              selectSet(player, hit.block, hit.face);
            } else if (mode == ToolMode.MOVE) {
              selectMove(player, hit.block, hit.face);
            } else if (mode == ToolMode.REMOVE) {
              selectRemove(player, hit.block, hit.face);
            } else {
              pasteOnRealBlock(player, hit.block, hit.face);
            }
          }
        }
        return;
      }

      Block targetBlock = rayResult.block;
      restoreSavedVoxelHost(player, targetBlock);
      ToolMode mode = modeToggleListener.getToolMode(player);
      if (dataManager.isBlockLocked(targetBlock) && mode != ToolMode.LOCK) {
        return;
      }

      String playerName = player.getName();
      long now = System.currentTimeMillis();
      if (chiselCooldown.containsKey(playerName)) {
        long lastChisel = chiselCooldown.get(playerName);
        if (now - lastChisel < CHISEL_COOLDOWN_MS) {
          return;
        }
      }
      chiselCooldown.put(playerName, now);

      int playerScale = modeToggleListener.getPlayerVoxelScale(player);

      useModeOnRay(player, mode, rayResult, playerScale);
      return;
    }

    if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
      Block block = event.getClickedBlock();
      if (block == null) return;

      DataManager dataManager = plugin.getDataManager();

      if (block.getType() != org.bukkit.Material.BARRIER || !dataManager.hasCarvedData(block)) {
        event.setCancelled(true);

        ToolMode mode = modeToggleListener.getToolMode(player);
        if (block.isPassable()
            && block.getType() != Material.AIR
            && block.getType() != Material.BARRIER) {
          SolidHit hit = rayTraceSolidIgnoringBarriers(player, 6.0);
          if (hit != null && hit.face != null) {
            if (mode == ToolMode.PLACE) {
              placeVoxelOnRealBlockSurface(player, hit.block, hit.face);
            } else if (mode == ToolMode.BRUSH) {
              brushOnRealBlock(player, hit.block, hit.face);
            } else if (mode == ToolMode.SET) {
              selectSet(player, hit.block, hit.face);
            } else if (mode == ToolMode.MOVE) {
              selectMove(player, hit.block, hit.face);
            } else if (mode == ToolMode.REMOVE && modeToggleListener.isRemoveMass(player)) {
              selectRemove(player, hit.block, hit.face);
            } else if (mode == ToolMode.PASTE) {
              pasteOnRealBlock(player, hit.block, hit.face);
            }
          }
          return;
        }
        if (mode == ToolMode.PLACE) {
          if (block.getType() == Material.BARRIER && !dataManager.hasCarvedData(block)) {
            SolidHit hit = rayTraceSolidIgnoringBarriers(player, 6.0);
            if (hit != null && hit.face != null) {
              placeVoxelOnRealBlockSurface(player, hit.block, hit.face);
            }
          } else {
            placeVoxelOnRealBlockSurface(
                player, block, aimedFace(player, block, event.getBlockFace()));
          }
        } else if (mode == ToolMode.REMOVE) {
          VoxelManager.RaycastResult rayResult =
              VoxelManager.raycastThroughBarriers(
                  player.getEyeLocation(),
                  player.getEyeLocation().getDirection().normalize(),
                  dataManager);
          if (rayResult != null) {
            if (modeToggleListener.isRemoveMass(player)) {
              selectRemove(player, rayResult);
            } else {
              removeVoxelPiece(player, rayResult, modeToggleListener.getPlayerVoxelScale(player));
            }
          } else if (modeToggleListener.isRemoveMass(player)) {
            selectRemove(player, block, aimedFace(player, block, event.getBlockFace()));
          } else {
            chiselNormalBlock(player, block, aimedFace(player, block, event.getBlockFace()));
          }
        } else if (mode == ToolMode.UNDO) {
          plugin.getVoxelEditHistory().undo(player);
        } else if (mode == ToolMode.REDO) {
          plugin.getVoxelEditHistory().redo(player);
        } else if (mode == ToolMode.BRUSH) {
          if (block.getType() == Material.BARRIER && !dataManager.hasCarvedData(block)) {
            SolidHit hit = rayTraceSolidIgnoringBarriers(player, 6.0);
            if (hit != null && hit.face != null) {
              brushOnRealBlock(player, hit.block, hit.face);
            }
          } else {
            brushOnRealBlock(player, block, aimedFace(player, block, event.getBlockFace()));
          }
        } else if (mode == ToolMode.SET) {
          if (block.getType() == Material.BARRIER && !dataManager.hasCarvedData(block)) {
            SolidHit hit = rayTraceSolidIgnoringBarriers(player, 6.0);
            if (hit != null && hit.face != null) {
              selectSet(player, hit.block, hit.face);
            }
          } else {
            selectSet(player, block, aimedFace(player, block, event.getBlockFace()));
          }
        } else if (mode == ToolMode.MOVE) {
          if (block.getType() == Material.BARRIER && !dataManager.hasCarvedData(block)) {
            SolidHit hit = rayTraceSolidIgnoringBarriers(player, 6.0);
            if (hit != null && hit.face != null) {
              selectMove(player, hit.block, hit.face);
            }
          } else {
            selectMove(player, block, aimedFace(player, block, event.getBlockFace()));
          }
        } else if (mode == ToolMode.PASTE) {
          pasteOnRealBlock(player, block, aimedFace(player, block, event.getBlockFace()));
        }

        return;
      }

      ToolMode mode = modeToggleListener.getToolMode(player);
      if (dataManager.isBlockLocked(block) && mode != ToolMode.LOCK) {
        return;
      }

      event.setCancelled(true);

      String playerName = player.getName();
      long now = System.currentTimeMillis();
      if (chiselCooldown.containsKey(playerName)) {
        long lastChisel = chiselCooldown.get(playerName);
        if (now - lastChisel < CHISEL_COOLDOWN_MS) {
          return;
        }
      }
      chiselCooldown.put(playerName, now);

      int playerScale = modeToggleListener.getPlayerVoxelScale(player);

      org.bukkit.Location eye = player.getEyeLocation();
      org.bukkit.util.Vector direction = eye.getDirection().normalize();

      VoxelManager.RaycastResult rayResult =
          VoxelManager.raycastThroughBarriers(eye, direction, dataManager);

      if (rayResult == null) {
        if (mode == ToolMode.PLACE) {
          SolidHit hit = rayTraceSolidIgnoringBarriers(player, 6.0);
          if (hit != null && hit.face != null) {
            placeVoxelOnRealBlockSurface(player, hit.block, hit.face);
          } else {
            placeVoxelOnBarrierAgainstSolidSurface(player, block);
          }
        }
        return;
      }

      restoreSavedVoxelHost(player, rayResult.block);
      useModeOnRay(player, mode, rayResult, playerScale);
      return;
    }

    if ((currentMode == ToolMode.MOVE
            || currentMode == ToolMode.ROTATE
            || currentMode == ToolMode.SCALE)
        && player.isSneaking()
        && (event.getAction() == Action.RIGHT_CLICK_AIR
            || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
      event.setCancelled(true);
      boolean restored =
          plugin.getVoxelSelectionManager().restorePreviousTransformSelection(player);
      player.sendActionBar(
          Component.text(
                  restored
                      ? "Selection restored | Shift-click: replace | Shift-scroll: resize"
                      : "No previous selection")
              .color(restored ? NamedTextColor.AQUA : NamedTextColor.YELLOW));
      return;
    }
    if (!player.isSneaking()
        && (event.getAction() == Action.RIGHT_CLICK_AIR
            || event.getAction() == Action.RIGHT_CLICK_BLOCK)
        && (currentMode == ToolMode.MOVE
            || currentMode == ToolMode.ROTATE
            || currentMode == ToolMode.SCALE)) {
      event.setCancelled(true);
      if (currentMode == ToolMode.ROTATE) modeToggleListener.cycleRotateAxis(player, 1);
      else if (currentMode == ToolMode.SCALE) modeToggleListener.cycleScaleAxis(player, 1);
      else modeToggleListener.cycleToolAxis(player, 1);
      String axis =
          currentMode == ToolMode.ROTATE
              ? modeToggleListener.getRotateAxis(player)
              : currentMode == ToolMode.SCALE
                  ? modeToggleListener.getScaleAxis(player)
                  : modeToggleListener.getToolAxis(player);
      String precision =
          currentMode == ToolMode.MOVE
              ? " | Step: " + modeToggleListener.getMoveStep(player) + "/16"
              : "";
      player.sendActionBar(
          Component.text(
                  currentMode.label()
                      + " axis: "
                      + axis.toUpperCase()
                      + precision
                      + " | Right-click: next axis | Shift-scroll: - / +")
              .color(NamedTextColor.AQUA));
      return;
    }
  }

  private void restoreSavedVoxelHost(Player player, Block block) {
    DataManager dataManager = plugin.getDataManager();
    if (!dataManager.hasCarvedData(block)) {
      return;
    }

    List<VoxelPiece> pieces = dataManager.getVoxelPieces(block);
    if (pieces.isEmpty()) {
      return;
    }

    if (block.getType() != Material.AIR && block.getType() != Material.BARRIER) {
      modeToggleListener.setStatus(player, "Remove inside block to edit voxels");
      return;
    }

    if (block.getType() != Material.BARRIER) {
      block.setType(Material.BARRIER);
    }
    plugin.getFallingBlockManager().updateBlockDisplay(block, null);
    CollisionBlockManager.updateCollisionBlock(block, pieces, dataManager.isBlockLocked(block));
    modeToggleListener.setStatus(player, "Voxel host restored");
  }

  private void placeVoxel(Player player, Block block) {
    DataManager dataManager = plugin.getDataManager();
    FallingBlockManager fallingBlockManager = plugin.getFallingBlockManager();

    String selectedBlock = modeToggleListener.getPlayerSelectedBlock(player);
    if (selectedBlock == null || selectedBlock.isEmpty()) {
      modeToggleListener.setStatus(player, "Pick a block first");
      return;
    }

    if (block.getType() != org.bukkit.Material.BARRIER) {
      Block targetCarveBlock = block;

      if (targetCarveBlock.getType() != org.bukkit.Material.BARRIER) {
        targetCarveBlock.setType(org.bukkit.Material.BARRIER);
        JsonArray newCarvedData = new JsonArray();
        for (int i = 0; i < 64; i++) {
          newCarvedData.add(new JsonPrimitive("air"));
        }
        dataManager.setCarvedBlockData(targetCarveBlock, newCarvedData);
      }
      block = targetCarveBlock;
    }

    JsonArray carvedData = dataManager.getCarvedBlockData(block);

    int targetVoxelIndex = VoxelManager.getClickedVoxelIndex(block, player);

    String targetVoxel = carvedData.get(targetVoxelIndex).getAsString();
    if (!targetVoxel.equals("air")) {
      return;
    }

    carvedData.set(targetVoxelIndex, new JsonPrimitive(selectedBlock));

    dataManager.setCarvedBlockData(block, carvedData);

    fallingBlockManager.updateBlockDisplay(block, carvedData);

    boolean isLocked = dataManager.isBlockLocked(block);
    CollisionBlockManager.updateCollisionBlock(block, carvedData, isLocked);
  }

  private void chiselAwayVoxel(Player player, Block block, int voxelIndex) {
    DataManager dataManager = plugin.getDataManager();
    FallingBlockManager fallingBlockManager = plugin.getFallingBlockManager();

    JsonArray carvedData = dataManager.getCarvedBlockData(block);

    String targetVoxel = carvedData.get(voxelIndex).getAsString();

    if (targetVoxel.equals("air")) {
      return;
    }

    carvedData.set(voxelIndex, new JsonPrimitive("air"));

    dataManager.setCarvedBlockData(block, carvedData);

    boolean isLocked = dataManager.isBlockLocked(block);
    CollisionBlockManager.updateCollisionBlock(block, carvedData, isLocked);

    fallingBlockManager.updateBlockDisplay(block, carvedData);

    modeToggleListener.setStatus(player, "Removed voxel");
  }

  private void placeVoxelAdjacent(
      Player player, Block block, int clickedVoxelIndex, org.bukkit.block.BlockFace hitFace) {
    DataManager dataManager = plugin.getDataManager();
    FallingBlockManager fallingBlockManager = plugin.getFallingBlockManager();

    String selectedBlock = modeToggleListener.getPlayerSelectedBlock(player);
    if (selectedBlock == null || selectedBlock.isEmpty()) {
      modeToggleListener.setStatus(player, "Pick a block first");
      return;
    }

    if (hitFace == null) {
      return;
    }

    int[] clickedCoords = VoxelManager.getVoxelCoords(clickedVoxelIndex);
    VoxelManager.PlacementTarget target =
        VoxelManager.calculatePlacementTarget(hitFace, clickedCoords, block);

    if (target == null) {
      return;
    }

    Block targetBlock = target.block;
    int targetVoxelIndex = target.voxelIndex;

    if (target.isCrossBlock) {
      if (targetBlock.getType() == org.bukkit.Material.AIR) {
        targetBlock.setType(org.bukkit.Material.BARRIER);
        JsonArray newCarvedData = new JsonArray();
        for (int i = 0; i < 64; i++) {
          newCarvedData.add(new JsonPrimitive("air"));
        }
        dataManager.setCarvedBlockData(targetBlock, newCarvedData);
      }
    }

    JsonArray carvedData = dataManager.getCarvedBlockData(targetBlock);

    String targetVoxel = carvedData.get(targetVoxelIndex).getAsString();
    if (!targetVoxel.equals("air")) {
      return;
    }

    carvedData.set(targetVoxelIndex, new JsonPrimitive(selectedBlock));

    dataManager.setCarvedBlockData(targetBlock, carvedData);

    fallingBlockManager.updateBlockDisplay(targetBlock, carvedData);

    boolean isLocked = dataManager.isBlockLocked(targetBlock);
    CollisionBlockManager.updateCollisionBlock(targetBlock, carvedData, isLocked);

    modeToggleListener.setStatus(player, "Placed " + selectedBlock.replace("_", " "));
  }

  private BlockFace aimedFace(Player player, Block fallbackBlock, BlockFace fallbackFace) {
    RayTraceResult hit = player.rayTraceBlocks(6.0);
    if (hit != null
        && hit.getHitBlock() != null
        && hit.getHitBlock().equals(fallbackBlock)
        && hit.getHitBlockFace() != null) {
      return hit.getHitBlockFace();
    }
    return fallbackFace;
  }

  private void placeVoxelOnRealBlockSurface(
      Player player, Block realBlock, org.bukkit.block.BlockFace clickedFace) {
    DataManager dataManager = plugin.getDataManager();
    FallingBlockManager fallingBlockManager = plugin.getFallingBlockManager();

    String selectedBlock = modeToggleListener.getPlayerSelectedBlock(player);
    if (selectedBlock == null || selectedBlock.isEmpty()) {
      return;
    }

    if (clickedFace == null) {
      return;
    }

    Block adjacentBlock = realBlock.getRelative(clickedFace);
    Block targetBarrier;

    if (dataManager.hasCarvedData(realBlock)) {
      targetBarrier = realBlock;
      targetBarrier.setType(Material.BARRIER, false);
    } else if (dataManager.hasCarvedData(adjacentBlock)) {
      targetBarrier = adjacentBlock;
      targetBarrier.setType(Material.BARRIER, false);
    } else {
      targetBarrier = adjacentBlock;
      if (!isReplaceableVoxelHost(targetBarrier)
          && targetBarrier.getType() != org.bukkit.Material.BARRIER) {
        return;
      }
      targetBarrier.setType(org.bukkit.Material.BARRIER);
      dataManager.setVoxelPieces(targetBarrier, new java.util.ArrayList<>());
    }
    if (!canEditBlock(player, targetBarrier)) {
      return;
    }

    org.bukkit.Location eye = player.getEyeLocation();
    org.bukkit.util.Vector dir = eye.getDirection().normalize();

    double planeX = 0, planeY = 0, planeZ = 0;
    switch (clickedFace) {
      case UP -> planeY = realBlock.getY() + 1.0;
      case DOWN -> planeY = realBlock.getY();
      case EAST -> planeX = realBlock.getX() + 1.0;
      case WEST -> planeX = realBlock.getX();
      case SOUTH -> planeZ = realBlock.getZ() + 1.0;
      case NORTH -> planeZ = realBlock.getZ();
      default -> {
        return;
      }
    }

    double t;
    switch (clickedFace) {
      case UP, DOWN -> {
        if (Math.abs(dir.getY()) < 0.0001) return;
        t = (planeY - eye.getY()) / dir.getY();
      }
      case EAST, WEST -> {
        if (Math.abs(dir.getX()) < 0.0001) return;
        t = (planeX - eye.getX()) / dir.getX();
      }
      case SOUTH, NORTH -> {
        if (Math.abs(dir.getZ()) < 0.0001) return;
        t = (planeZ - eye.getZ()) / dir.getZ();
      }
      default -> {
        return;
      }
    }

    if (t < 0) return;

    org.bukkit.Location hit = eye.clone().add(dir.multiply(t));
    double relX = Math.max(0.0, Math.min(0.9999, hit.getX() - realBlock.getX()));
    double relY = Math.max(0.0, Math.min(0.9999, hit.getY() - realBlock.getY()));
    double relZ = Math.max(0.0, Math.min(0.9999, hit.getZ() - realBlock.getZ()));

    int voxelX = Math.min(15, Math.max(0, (int) Math.floor(relX * 16.0)));
    int voxelY = Math.min(15, Math.max(0, (int) Math.floor(relY * 16.0)));
    int voxelZ = Math.min(15, Math.max(0, (int) Math.floor(relZ * 16.0)));

    int playerScale = modeToggleListener.getPlayerVoxelScale(player);
    int pieceSize = VoxelPieceManager.getPieceSizeForScale(playerScale);

    int[] baseCoords = new int[] {voxelX, voxelY, voxelZ};

    if (targetBarrier.equals(realBlock)) {
      switch (clickedFace) {
        case UP -> baseCoords[1] = 16 - pieceSize;
        case DOWN -> baseCoords[1] = 0;
        case EAST -> baseCoords[0] = 16 - pieceSize;
        case WEST -> baseCoords[0] = 0;
        case SOUTH -> baseCoords[2] = 16 - pieceSize;
        case NORTH -> baseCoords[2] = 0;
        default -> {
          return;
        }
      }
    } else {
      switch (clickedFace) {
        case UP -> baseCoords[1] = 0;
        case DOWN -> baseCoords[1] = 16 - pieceSize;
        case EAST -> baseCoords[0] = 0;
        case WEST -> baseCoords[0] = 16 - pieceSize;
        case SOUTH -> baseCoords[2] = 0;
        case NORTH -> baseCoords[2] = 16 - pieceSize;
        default -> {
          return;
        }
      }
    }

    int[] gridAligned =
        VoxelManager.getGridAlignedCoords(baseCoords[0], baseCoords[1], baseCoords[2], playerScale);

    WorldVoxel anchor =
        worldVoxelAt(
            targetBarrier.getWorld(),
            targetBarrier.getX() * 16 + gridAligned[0],
            targetBarrier.getY() * 16 + gridAligned[1],
            targetBarrier.getZ() * 16 + gridAligned[2],
            pieceSize);
    int placed =
        applyPieces(
            player,
            directedStackPieces(
                player,
                anchor,
                selectedBlock,
                selectedBlockDataFor(player, selectedBlock, clickedFace)),
            true);
    modeToggleListener.setStatus(
        player,
        placed == 0 ? "Cannot place: overlap" : "Placed " + selectedBlock.replace("_", " "));
  }

  private boolean placeVoxelOnBarrierAgainstSolidSurface(Player player, Block barrierBlock) {
    if (!canEditBlock(player, barrierBlock)) {
      return false;
    }
    DataManager dataManager = plugin.getDataManager();
    FallingBlockManager fallingBlockManager = plugin.getFallingBlockManager();

    String selectedBlock = modeToggleListener.getPlayerSelectedBlock(player);
    if (selectedBlock == null || selectedBlock.isEmpty()) {
      return false;
    }

    org.bukkit.Location eye = player.getEyeLocation();
    org.bukkit.util.Vector dir = eye.getDirection().normalize();

    org.bukkit.block.BlockFace[] faces =
        new org.bukkit.block.BlockFace[] {
          org.bukkit.block.BlockFace.DOWN,
          org.bukkit.block.BlockFace.UP,
          org.bukkit.block.BlockFace.NORTH,
          org.bukkit.block.BlockFace.SOUTH,
          org.bukkit.block.BlockFace.WEST,
          org.bukkit.block.BlockFace.EAST
        };

    org.bukkit.block.BlockFace bestFace = null;
    org.bukkit.Location bestHit = null;
    double bestT = Double.MAX_VALUE;

    for (org.bukkit.block.BlockFace face : faces) {
      Block neighbour = barrierBlock.getRelative(face);

      if (neighbour.getType() == org.bukkit.Material.AIR) {
        continue;
      }

      if (neighbour.getType() == org.bukkit.Material.BARRIER) {
        continue;
      }

      double t;

      switch (face) {
        case DOWN -> {
          if (Math.abs(dir.getY()) < 0.0001) continue;
          t = (barrierBlock.getY() - eye.getY()) / dir.getY();
        }
        case UP -> {
          if (Math.abs(dir.getY()) < 0.0001) continue;
          t = (barrierBlock.getY() + 1.0 - eye.getY()) / dir.getY();
        }
        case WEST -> {
          if (Math.abs(dir.getX()) < 0.0001) continue;
          t = (barrierBlock.getX() - eye.getX()) / dir.getX();
        }
        case EAST -> {
          if (Math.abs(dir.getX()) < 0.0001) continue;
          t = (barrierBlock.getX() + 1.0 - eye.getX()) / dir.getX();
        }
        case NORTH -> {
          if (Math.abs(dir.getZ()) < 0.0001) continue;
          t = (barrierBlock.getZ() - eye.getZ()) / dir.getZ();
        }
        case SOUTH -> {
          if (Math.abs(dir.getZ()) < 0.0001) continue;
          t = (barrierBlock.getZ() + 1.0 - eye.getZ()) / dir.getZ();
        }
        default -> {
          continue;
        }
      }

      if (t < 0 || t >= bestT) {
        continue;
      }

      org.bukkit.Location hit = eye.clone().add(dir.clone().multiply(t));

      double relX = hit.getX() - barrierBlock.getX();
      double relY = hit.getY() - barrierBlock.getY();
      double relZ = hit.getZ() - barrierBlock.getZ();

      if (relX < -0.001 || relX > 1.001) continue;
      if (relY < -0.001 || relY > 1.001) continue;
      if (relZ < -0.001 || relZ > 1.001) continue;

      bestT = t;
      bestFace = face;
      bestHit = hit;
    }

    if (bestFace == null || bestHit == null) {
      return false;
    }

    double relX = bestHit.getX() - barrierBlock.getX();
    double relY = bestHit.getY() - barrierBlock.getY();
    double relZ = bestHit.getZ() - barrierBlock.getZ();

    relX = Math.max(0.0, Math.min(0.9999, relX));
    relY = Math.max(0.0, Math.min(0.9999, relY));
    relZ = Math.max(0.0, Math.min(0.9999, relZ));

    int voxelX = Math.min(15, Math.max(0, (int) Math.floor(relX * 16.0)));
    int voxelY = Math.min(15, Math.max(0, (int) Math.floor(relY * 16.0)));
    int voxelZ = Math.min(15, Math.max(0, (int) Math.floor(relZ * 16.0)));

    int playerScale = modeToggleListener.getPlayerVoxelScale(player);
    int pieceSize = VoxelPieceManager.getPieceSizeForScale(playerScale);

    switch (bestFace) {
      case DOWN -> voxelY = 0;
      case UP -> voxelY = 16 - pieceSize;
      case WEST -> voxelX = 0;
      case EAST -> voxelX = 16 - pieceSize;
      case NORTH -> voxelZ = 0;
      case SOUTH -> voxelZ = 16 - pieceSize;
      default -> {
        return false;
      }
    }

    int[] coords = VoxelManager.getGridAlignedCoords(voxelX, voxelY, voxelZ, playerScale);
    java.util.List<me.reube.SmallVoxels.managers.VoxelPiece> pieces =
        dataManager.getVoxelPieces(barrierBlock);
    List<VoxelPiece> before = plugin.getVoxelEditHistory().snapshot(barrierBlock);
    me.reube.SmallVoxels.managers.VoxelPiece newPiece =
        new me.reube.SmallVoxels.managers.VoxelPiece(
            coords[0],
            coords[1],
            coords[2],
            pieceSize,
            selectedBlock,
            selectedBlockDataFor(player, selectedBlock, bestFace));

    if (!VoxelPieceManager.place(pieces, newPiece)) {
      return false;
    }

    dataManager.setVoxelPieces(barrierBlock, pieces);
    fallingBlockManager.updateBlockDisplay(barrierBlock, null);

    boolean isLocked = dataManager.isBlockLocked(barrierBlock);
    CollisionBlockManager.updateCollisionBlock(barrierBlock, pieces, isLocked);
    plugin
        .getVoxelEditHistory()
        .remember(
            player, barrierBlock, before, plugin.getVoxelEditHistory().snapshot(barrierBlock));

    modeToggleListener.setStatus(player, "Placed " + selectedBlock.replace("_", " "));
    return true;
  }

  private void chiselAway(Player player, Block block) {
    org.bukkit.Location eye = player.getEyeLocation();
    org.bukkit.util.Vector direction = eye.getDirection().normalize();

    VoxelManager.RaycastResult rayResult =
        VoxelManager.raycastThroughBarriers(eye, direction, plugin.getDataManager());
    if (rayResult == null || !rayResult.block.equals(block)) {
      return;
    }

    removeVoxelPiece(player, rayResult, modeToggleListener.getPlayerVoxelScale(player));
  }

  private void chiselNormalBlock(Player player, Block block, BlockFace face) {
    if (!canEditBlock(player, block)) {
      return;
    }
    if (face == null || block.getType() == Material.AIR || block.getType() == Material.BARRIER) {
      return;
    }

    Material original = block.getType();
    String originalData = block.getBlockData().getAsString();
    int scale = modeToggleListener.getPlayerVoxelScale(player);
    int pieceSize = VoxelPieceManager.getPieceSizeForScale(scale);
    int[] removeAt = surfaceCoordsFromHit(player, block, block, face, pieceSize, scale);
    if (removeAt == null) {
      return;
    }

    java.util.ArrayList<VoxelPiece> pieces = new java.util.ArrayList<>();
    pieces.add(new VoxelPiece(0, 0, 0, 16, original.name(), originalData));
    VoxelPieceManager.chiselAt(pieces, removeAt[0], removeAt[1], removeAt[2], pieceSize);

    DataManager dataManager = plugin.getDataManager();
    List<VoxelPiece> before = plugin.getVoxelEditHistory().snapshot(block);
    block.setType(Material.BARRIER);
    dataManager.setVoxelPieces(block, pieces);
    plugin.getFallingBlockManager().updateBlockDisplay(block, null);
    CollisionBlockManager.updateCollisionBlock(block, pieces, dataManager.isBlockLocked(block));
    plugin
        .getVoxelEditHistory()
        .remember(player, block, before, plugin.getVoxelEditHistory().snapshot(block));
    modeToggleListener.setStatus(player, "Voxelized " + original.name().replace("_", " "));
  }

  private int[] rayResultToVoxelCoords(VoxelManager.RaycastResult rayResult) {
    if (rayResult.piece != null) {
      return new int[] {
        Math.min(15, Math.max(0, rayResult.hitX)),
        Math.min(15, Math.max(0, rayResult.hitY)),
        Math.min(15, Math.max(0, rayResult.hitZ))
      };
    }

    int[] slotCoords = VoxelManager.getVoxelCoords(rayResult.voxelIndex);
    return new int[] {
      Math.min(15, Math.max(0, slotCoords[0] * 4 + 2)),
      Math.min(15, Math.max(0, slotCoords[1] * 4 + 2)),
      Math.min(15, Math.max(0, slotCoords[2] * 4 + 2))
    };
  }

  @EventHandler
  public void onPlayerMove(PlayerMoveEvent event) {
    refreshPreview(event.getPlayer());
  }

  private void refreshPreview(Player player) {
    if (!plugin.getChiselManager().isChiselAxe(player.getInventory().getItemInMainHand())) {
      clearAllSelections(player);
      plugin.getVoxelPreviewManager().clearLive(player);
      return;
    }
    if (!modeToggleListener.isPlacementPreviewEnabled(player)) {
      plugin.getVoxelPreviewManager().clearLive(player);
      return;
    }

    ToolMode mode = modeToggleListener.getToolMode(player);
    String key = player.getUniqueId().toString();
    if ((mode == ToolMode.MOVE || mode == ToolMode.ROTATE || mode == ToolMode.SCALE)
        && moveSelections.containsKey(key)) {
      int radius = transformSelectionRadius.getOrDefault(key, 0);
      int width = radius * 2 + 1;
      player.sendActionBar(
          Component.text(
                  "Selecting "
                      + width
                      + "×"
                      + width
                      + "×"
                      + width
                      + " | Shift-scroll: resize | Shift-click: confirm | Shift-right-click:"
                      + " restore")
              .color(NamedTextColor.AQUA));
    }
    ToolMode previousMode = lastPreviewMode.put(key, mode);
    if (previousMode != null && previousMode != mode) {
      clearAllSelections(player);
    }
    clearSelectionsForMode(player, mode);
    if (mode != ToolMode.PLACE
        && mode != ToolMode.PASTE
        && mode != ToolMode.REMOVE
        && mode != ToolMode.BRUSH
        && mode != ToolMode.SET
        && mode != ToolMode.LOCK
        && mode != ToolMode.COPY
        && mode != ToolMode.REPLACE
        && mode != ToolMode.MOVE
        && mode != ToolMode.ROTATE
        && mode != ToolMode.SCALE) {
      plugin.getVoxelPreviewManager().clearLive(player);
      return;
    }

    org.bukkit.Location eye = player.getEyeLocation();
    org.bukkit.util.Vector direction = eye.getDirection().normalize();
    VoxelManager.RaycastResult rayResult =
        VoxelManager.raycastThroughBarriers(eye, direction, plugin.getDataManager());
    if (rayResult == null) {
      if (mode == ToolMode.PLACE) {
        if (!previewNormalPlacement(player)) {
          plugin.getVoxelPreviewManager().clearLive(player);
        }
      } else if (mode == ToolMode.REMOVE) {
        if (!previewNormalRemove(player)) {
          plugin.getVoxelPreviewManager().clearLive(player);
        }
      } else if (mode == ToolMode.BRUSH) {
        if (!previewNormalBrush(player)) {
          plugin.getVoxelPreviewManager().clearLive(player);
        }
      } else if (mode == ToolMode.SET) {
        if (!previewNormalSet(player)) {
          plugin.getVoxelPreviewManager().clearLive(player);
        }
      } else if (mode == ToolMode.MOVE || mode == ToolMode.ROTATE || mode == ToolMode.SCALE) {
        if (plugin.getVoxelSelectionManager().hasMoveSelection(player)) {
          plugin.getVoxelSelectionManager().showTransformSelection(player);
          return;
        }
        if (!previewNormalMove(player)) {
          plugin.getVoxelPreviewManager().clearLive(player);
        }
      } else if (mode == ToolMode.PASTE) {
        if (!previewNormalPaste(player)) {
          plugin.getVoxelPreviewManager().clearLive(player);
        }
      } else if (mode == ToolMode.COPY || mode == ToolMode.REPLACE) {
        plugin.getVoxelPreviewManager().clearLive(player);
      } else {
        plugin.getVoxelPreviewManager().clearLive(player);
      }
      return;
    }

    int scale = modeToggleListener.getPlayerVoxelScale(player);
    if (mode == ToolMode.PLACE) {
      if (isEmptyCarvedShell(rayResult)) {
        if (!previewNormalPlacement(player)) {
          plugin.getVoxelPreviewManager().clearLive(player);
        }
        return;
      }
      PlacementTarget target = placementTarget(rayResult, scale);
      if (target != null) {
        showPlacementPreview(
            player,
            worldVoxelAt(
                target.block.getWorld(),
                target.block.getX() * 16 + target.coords[0],
                target.block.getY() * 16 + target.coords[1],
                target.block.getZ() * 16 + target.coords[2],
                target.size));
      } else if (!previewNormalPlacement(player)) {
        plugin.getVoxelPreviewManager().clearLive(player);
      }
    } else if (mode == ToolMode.REMOVE) {
      if (modeToggleListener.isRemoveMass(player)) {
        showRemoveSelectionPreview(player, rayResult);
      } else {
        RemovePreview preview = removePreview(rayResult, scale);
        plugin
            .getVoxelPreviewManager()
            .showRemove(player, rayResult.block, preview.x, preview.y, preview.z, preview.size);
      }
    } else if (mode == ToolMode.BRUSH) {
      if (isEmptyCarvedShell(rayResult) && previewNormalBrush(player)) {
        return;
      }
      showBrushPreview(player, rayResult);
    } else if (mode == ToolMode.SET) {
      if (isEmptyCarvedShell(rayResult) && previewNormalSet(player)) {
        return;
      }
      showSetPreview(player, rayResult);
    } else if (mode == ToolMode.MOVE || mode == ToolMode.ROTATE || mode == ToolMode.SCALE) {
      if (plugin.getVoxelSelectionManager().hasMoveSelection(player)) {
        plugin.getVoxelSelectionManager().showTransformSelection(player);
        return;
      }
      if (isEmptyCarvedShell(rayResult) && previewNormalMove(player)) {
        return;
      }
      showMovePreview(player, rayResult);
    } else if (mode == ToolMode.COPY) {
      showCopyPreview(player, rayResult);
    } else if (mode == ToolMode.REPLACE) {
      showReplacePreview(player, rayResult);
    } else if (mode == ToolMode.LOCK) {
      boolean locked = plugin.getDataManager().isBlockLocked(rayResult.block);
      plugin.getVoxelPreviewManager().showLock(player, rayResult.block, locked);
      modeToggleListener.setStatus(player, locked ? "Locked block" : "Unlocked block");
    } else if (plugin.getVoxelClipboard().hasCopy(player)) {
      WorldVoxel anchor = pasteAnchorWorld(rayResult, scale);
      plugin.getVoxelPreviewManager().showPaste(player, pastePiecesAt(player, anchor));
    } else {
      plugin.getVoxelPreviewManager().clearLive(player);
    }
  }

  private void clearSelectionsForMode(Player player, ToolMode mode) {
    String key = player.getUniqueId().toString();
    if (mode != ToolMode.SET) {
      setSelections.remove(key);
      setSelectionSignatures.remove(key);
    }
    if (mode != ToolMode.COPY) {
      copySelections.remove(key);
    }
    if (mode != ToolMode.REPLACE) {
      replaceSelections.remove(key);
      replaceSourceMaterials.remove(key);
    }
    if (mode != ToolMode.MOVE && mode != ToolMode.ROTATE && mode != ToolMode.SCALE) {
      moveSelections.remove(key);
    }
    if (mode != ToolMode.ROTATE) {
      rotateSelections.remove(key);
    }
    if (mode != ToolMode.REMOVE) {
      removeSelections.remove(key);
      removeSelectionSignatures.remove(key);
    }
  }

  private void clearSelectionsIfModeChanged(Player player, ToolMode mode) {
    String key = player.getUniqueId().toString();
    ToolMode previous = lastPreviewMode.put(key, mode);
    if (previous != null && previous != mode) {
      clearAllSelections(player);
    }
  }

  private void clearAllSelections(Player player) {
    String key = player.getUniqueId().toString();
    setSelections.remove(key);
    setSelectionSignatures.remove(key);
    removeSelections.remove(key);
    removeSelectionSignatures.remove(key);
    copySelections.remove(key);
    replaceSelections.remove(key);
    replaceSourceMaterials.remove(key);
    moveSelections.remove(key);
    rotateSelections.remove(key);
  }

  private void useModeOnRay(
      Player player, ToolMode mode, VoxelManager.RaycastResult rayResult, int scale) {
    switch (mode) {
      case LOCK -> toggleLock(player, rayResult.block);
      case PLACE -> {
        if (isEmptyCarvedShell(rayResult)) {
          SolidHit hit = rayTraceSolidIgnoringBarriers(player, 6.0);
          if (hit != null && hit.face != null) {
            placeVoxelOnRealBlockSurface(player, hit.block, hit.face);
          }
        } else {
          placeVoxelAdjacentToHit(player, rayResult, scale);
        }
      }
      case REMOVE -> {
        if (modeToggleListener.isRemoveMass(player)) {
          if (isEmptyCarvedShell(rayResult)) {
            SolidHit hit = rayTraceSolidIgnoringBarriers(player, 6.0);
            if (hit != null && hit.face != null) {
              selectRemove(player, hit.block, hit.face);
            }
          } else {
            selectRemove(player, rayResult);
          }
        } else {
          removeVoxelPiece(player, rayResult, scale);
        }
      }
      case REPLACE -> replaceVoxelPiece(player, rayResult);
      case COPY -> copyVoxelBlock(player, rayResult);
      case PASTE -> pasteClipboard(player, rayResult, scale);
      case BRUSH -> {
        if (isEmptyCarvedShell(rayResult)) {
          SolidHit hit = rayTraceSolidIgnoringBarriers(player, 6.0);
          if (hit != null && hit.face != null) {
            brushOnRealBlock(player, hit.block, hit.face);
          }
        } else {
          brushVoxel(player, rayResult, scale);
        }
      }
      case SET -> {
        if (isEmptyCarvedShell(rayResult)) {
          SolidHit hit = rayTraceSolidIgnoringBarriers(player, 6.0);
          if (hit != null && hit.face != null) {
            selectSet(player, hit.block, hit.face);
          }
        } else {
          selectSet(player, rayResult);
        }
      }
      case MOVE -> {
        if (isEmptyCarvedShell(rayResult)) {
          SolidHit hit = rayTraceSolidIgnoringBarriers(player, 6.0);
          if (hit != null && hit.face != null) {
            selectMove(player, hit.block, hit.face);
          }
        } else {
          selectMove(player, rayResult);
        }
      }
      case ROTATE -> {
        if (player.isSneaking()) {
          selectMove(player, rayResult);
        } else {
          boolean rotated =
              plugin
                  .getVoxelSelectionManager()
                  .rotateSelection(
                      player,
                      modeToggleListener.getRotateAxis(player),
                      modeToggleListener.getRotateAngle(player));
          modeToggleListener.setStatus(
              player, rotated ? "Rotated selection" : "Shift-select first");
        }
      }
      case SCALE -> {
        if (player.isSneaking()) selectMove(player, rayResult);
        else modeToggleListener.openCurrentSettings(player);
      }
      case UNDO -> plugin.getVoxelEditHistory().undo(player);
      case REDO -> plugin.getVoxelEditHistory().redo(player);
    }
  }

  private boolean isEmptyCarvedShell(VoxelManager.RaycastResult rayResult) {
    return !rayResult.hitPiece
        && rayResult.block.getType() == Material.BARRIER
        && plugin.getDataManager().getVoxelPieces(rayResult.block).isEmpty();
  }

  private boolean previewNormalRemove(Player player) {
    SolidHit hitResult = rayTraceSolidIgnoringBarriers(player, 6.0);
    if (hitResult == null || hitResult.face == null) {
      return false;
    }

    Block block = hitResult.block;
    if (block.getType() == Material.AIR || block.getType() == Material.BARRIER) {
      return false;
    }

    if (modeToggleListener.isRemoveMass(player)) {
      WorldVoxel point =
          worldVoxelOnSurface(
              player, block, hitResult.face, modeToggleListener.getPlayerVoxelScale(player));
      if (point != null) {
        showRemoveSelectionPreview(player, point);
        return true;
      }
      return false;
    }

    int scale = modeToggleListener.getPlayerVoxelScale(player);
    int pieceSize = VoxelPieceManager.getPieceSizeForScale(scale);
    int[] coords = surfaceCoordsFromHit(player, block, block, hitResult.face, pieceSize, scale);
    if (coords != null) {
      plugin
          .getVoxelPreviewManager()
          .showRemove(player, block, coords[0], coords[1], coords[2], pieceSize);
      return true;
    }
    return false;
  }

  private boolean previewNormalPlacement(Player player) {
    SolidHit hitResult = rayTraceSolidIgnoringBarriers(player, 6.0);
    if (hitResult == null || hitResult.face == null) {
      return false;
    }

    Block realBlock = hitResult.block;
    BlockFace face = hitResult.face;
    DataManager dataManager = plugin.getDataManager();
    Block adjacentBlock = realBlock.getRelative(face);
    Block targetBlock;

    if (realBlock.getType() == Material.BARRIER && dataManager.hasCarvedData(realBlock)) {
      targetBlock = realBlock;
    } else if (adjacentBlock.getType() == Material.BARRIER
        && dataManager.hasCarvedData(adjacentBlock)) {
      targetBlock = adjacentBlock;
    } else if (adjacentBlock.getType() == Material.AIR
        || adjacentBlock.getType() == Material.BARRIER) {
      targetBlock = adjacentBlock;
    } else {
      return false;
    }

    int scale = modeToggleListener.getPlayerVoxelScale(player);
    int pieceSize = VoxelPieceManager.getPieceSizeForScale(scale);
    int[] coords = surfaceCoordsFromHit(player, realBlock, targetBlock, face, pieceSize, scale);
    if (coords != null) {
      showPlacementPreview(
          player,
          worldVoxelAt(
              targetBlock.getWorld(),
              targetBlock.getX() * 16 + coords[0],
              targetBlock.getY() * 16 + coords[1],
              targetBlock.getZ() * 16 + coords[2],
              pieceSize));
      return true;
    }
    return false;
  }

  private boolean previewNormalBrush(Player player) {
    SolidHit hit = rayTraceSolidIgnoringBarriers(player, 6.0);
    if (hit == null || hit.face == null) {
      return false;
    }
    WorldVoxel center =
        worldVoxelOnSurface(
            player, hit.block, hit.face, modeToggleListener.getPlayerVoxelScale(player));
    if (center == null) {
      return false;
    }
    showBrushPreview(player, center);
    return true;
  }

  private boolean previewNormalSet(Player player) {
    SolidHit hit = rayTraceSolidIgnoringBarriers(player, 6.0);
    if (hit == null || hit.face == null) {
      return false;
    }
    WorldVoxel point =
        worldVoxelAdjacentSurface(
            player, hit.block, hit.face, modeToggleListener.getPlayerVoxelScale(player));
    if (point == null) {
      return false;
    }
    showSetPreview(player, point);
    return true;
  }

  private boolean previewNormalPaste(Player player) {
    if (!plugin.getVoxelClipboard().hasCopy(player)) {
      return false;
    }
    SolidHit hit = rayTraceSolidIgnoringBarriers(player, 6.0);
    if (hit == null || hit.face == null) {
      return false;
    }
    WorldVoxel anchor =
        worldVoxelAdjacentSurface(
            player, hit.block, hit.face, modeToggleListener.getPlayerVoxelScale(player));
    if (anchor == null) {
      return false;
    }
    plugin.getVoxelPreviewManager().showPaste(player, pastePiecesAt(player, anchor));
    return true;
  }

  private SolidHit rayTraceSolidIgnoringBarriers(Player player, double maxDistance) {
    Location eye = player.getEyeLocation();
    org.bukkit.util.Vector direction = eye.getDirection().normalize();
    Block previous = eye.getBlock();

    for (double distance = 0.0; distance <= maxDistance; distance += 0.04) {
      Location point = eye.clone().add(direction.clone().multiply(distance));
      Block block = point.getBlock();
      if (block.equals(previous)) {
        continue;
      }

      if (block.getType() == Material.BARRIER && plugin.getDataManager().hasCarvedData(block)) {
        previous = block;
        continue;
      }

      if (block.getType() != Material.AIR && !block.isPassable()) {
        return new SolidHit(block, entryFace(eye, direction, block));
      }
      previous = block;
    }

    return null;
  }

  private BlockFace faceBetween(Block from, Block to) {
    int dx = to.getX() - from.getX();
    int dy = to.getY() - from.getY();
    int dz = to.getZ() - from.getZ();
    if (dx > 0) return BlockFace.WEST;
    if (dx < 0) return BlockFace.EAST;
    if (dy > 0) return BlockFace.DOWN;
    if (dy < 0) return BlockFace.UP;
    if (dz > 0) return BlockFace.NORTH;
    if (dz < 0) return BlockFace.SOUTH;
    return BlockFace.SELF;
  }

  private BlockFace entryFace(Location eye, org.bukkit.util.Vector direction, Block block) {
    double originX = eye.getX() - block.getX();
    double originY = eye.getY() - block.getY();
    double originZ = eye.getZ() - block.getZ();
    double[] origin = {originX, originY, originZ};
    double[] dir = {direction.getX(), direction.getY(), direction.getZ()};
    BlockFace[] minFaces = {BlockFace.WEST, BlockFace.DOWN, BlockFace.NORTH};
    BlockFace[] maxFaces = {BlockFace.EAST, BlockFace.UP, BlockFace.SOUTH};
    double bestEnter = -Double.MAX_VALUE;
    BlockFace face = BlockFace.SELF;

    for (int axis = 0; axis < 3; axis++) {
      if (Math.abs(dir[axis]) < 1e-9) {
        continue;
      }
      double t1 = (0.0 - origin[axis]) / dir[axis];
      double t2 = (1.0 - origin[axis]) / dir[axis];
      BlockFace enterFace = dir[axis] > 0 ? minFaces[axis] : maxFaces[axis];
      double enter = Math.min(t1, t2);
      if (enter > bestEnter) {
        bestEnter = enter;
        face = enterFace;
      }
    }
    return face;
  }

  private int[] surfaceCoordsFromHit(
      Player player, Block realBlock, Block targetBlock, BlockFace face, int pieceSize, int scale) {
    org.bukkit.Location eye = player.getEyeLocation();
    org.bukkit.util.Vector dir = eye.getDirection().normalize();

    double planeX = 0, planeY = 0, planeZ = 0;
    switch (face) {
      case UP -> planeY = realBlock.getY() + 1.0;
      case DOWN -> planeY = realBlock.getY();
      case EAST -> planeX = realBlock.getX() + 1.0;
      case WEST -> planeX = realBlock.getX();
      case SOUTH -> planeZ = realBlock.getZ() + 1.0;
      case NORTH -> planeZ = realBlock.getZ();
      default -> {
        return null;
      }
    }

    double t;
    switch (face) {
      case UP, DOWN -> {
        if (Math.abs(dir.getY()) < 0.0001) return null;
        t = (planeY - eye.getY()) / dir.getY();
      }
      case EAST, WEST -> {
        if (Math.abs(dir.getX()) < 0.0001) return null;
        t = (planeX - eye.getX()) / dir.getX();
      }
      case SOUTH, NORTH -> {
        if (Math.abs(dir.getZ()) < 0.0001) return null;
        t = (planeZ - eye.getZ()) / dir.getZ();
      }
      default -> {
        return null;
      }
    }

    if (t < 0) {
      return null;
    }

    org.bukkit.Location hit = eye.clone().add(dir.multiply(t));
    double relX = Math.max(0.0, Math.min(0.9999, hit.getX() - realBlock.getX()));
    double relY = Math.max(0.0, Math.min(0.9999, hit.getY() - realBlock.getY()));
    double relZ = Math.max(0.0, Math.min(0.9999, hit.getZ() - realBlock.getZ()));

    int[] baseCoords =
        new int[] {
          Math.min(15, Math.max(0, (int) Math.floor(relX * 16.0))),
          Math.min(15, Math.max(0, (int) Math.floor(relY * 16.0))),
          Math.min(15, Math.max(0, (int) Math.floor(relZ * 16.0)))
        };

    if (targetBlock.equals(realBlock)) {
      switch (face) {
        case UP -> baseCoords[1] = 16 - pieceSize;
        case DOWN -> baseCoords[1] = 0;
        case EAST -> baseCoords[0] = 16 - pieceSize;
        case WEST -> baseCoords[0] = 0;
        case SOUTH -> baseCoords[2] = 16 - pieceSize;
        case NORTH -> baseCoords[2] = 0;
        default -> {
          return null;
        }
      }
    } else {
      switch (face) {
        case UP -> baseCoords[1] = 0;
        case DOWN -> baseCoords[1] = 16 - pieceSize;
        case EAST -> baseCoords[0] = 0;
        case WEST -> baseCoords[0] = 16 - pieceSize;
        case SOUTH -> baseCoords[2] = 0;
        case NORTH -> baseCoords[2] = 16 - pieceSize;
        default -> {
          return null;
        }
      }
    }

    return VoxelManager.getGridAlignedCoords(baseCoords[0], baseCoords[1], baseCoords[2], scale);
  }

  private void toggleLock(Player player, Block block) {
    if (!canEditBlock(player, block)) {
      return;
    }
    DataManager dataManager = plugin.getDataManager();
    List<VoxelPiece> pieces = dataManager.getVoxelPieces(block);
    if (pieces.isEmpty()) {
      return;
    }

    boolean locked = !dataManager.isBlockLocked(block);
    dataManager.setBlockLocked(block, locked);

    if (locked) {
      org.bukkit.entity.ArmorStand stand = CollisionStandManager.spawnCollisionStand(block);
      dataManager.setCollisionStandUUID(block, stand.getUniqueId().toString());
    } else {
      String standUUID = dataManager.getCollisionStandUUID(block);
      if (standUUID != null) {
        CollisionStandManager.removeCollisionStand(block, standUUID);
        dataManager.setCollisionStandUUID(block, null);
      }
    }

    plugin.getFallingBlockManager().updateBlockDisplay(block, null);
    CollisionBlockManager.updateCollisionBlock(block, pieces, locked);
    modeToggleListener.setStatus(player, locked ? "Locked" : "Unlocked");
  }

  private void placeVoxelAdjacentToHit(
      Player player, VoxelManager.RaycastResult rayResult, int scale) {
    if (rayResult.piece != null
        && rayResult.piece.hasVisualTransform()
        && rayResult.hitFace != null) {
      placeOnTransformedFace(player, rayResult, scale);
      return;
    }
    PlacementTarget target = placementTarget(rayResult, scale);
    if (target == null) {
      return;
    }
    if (!canEditBlock(player, target.block)) {
      return;
    }

    DataManager dataManager = plugin.getDataManager();
    if (!ensureVoxelHost(target.block, dataManager)) {
      return;
    }

    placeVoxelPiece(player, target.block, target.coords, scale, rayResult.hitFace);
  }

  /** Places in the hit piece's local grid, then converts the centre back to world space. */
  private void placeOnTransformedFace(
      Player player, VoxelManager.RaycastResult rayResult, int scale) {
    VoxelPiece hit = rayResult.piece;
    int size = VoxelPieceManager.getPieceSizeForScale(scale);
    int tx = snapInsidePiece(rayResult.hitX, hit.x, hit.size, size);
    int ty = snapInsidePiece(rayResult.hitY, hit.y, hit.size, size);
    int tz = snapInsidePiece(rayResult.hitZ, hit.z, hit.size, size);
    switch (rayResult.hitFace) {
      case WEST -> tx = hit.x - size;
      case EAST -> tx = hit.x + hit.size;
      case DOWN -> ty = hit.y - size;
      case UP -> ty = hit.y + hit.size;
      case NORTH -> tz = hit.z - size;
      case SOUTH -> tz = hit.z + hit.size;
      default -> {
        return;
      }
    }

    double hitCx = (hit.x + hit.size / 2.0) / 16.0;
    double hitCy = (hit.y + hit.size / 2.0) / 16.0;
    double hitCz = (hit.z + hit.size / 2.0) / 16.0;
    org.joml.Vector3f delta =
        new org.joml.Vector3f(
            (float) ((tx + size / 2.0) / 16.0 - hitCx),
            (float) ((ty + size / 2.0) / 16.0 - hitCy),
            (float) ((tz + size / 2.0) / 16.0 - hitCz));
    delta.mul(
        (float) (hit.visualScale * hit.scaleX),
        (float) (hit.visualScale * hit.scaleY),
        (float) (hit.visualScale * hit.scaleZ));
    hit.rotationQuaternion().transform(delta);

    double worldCx = rayResult.block.getX() + hitCx + hit.offsetX + delta.x;
    double worldCy = rayResult.block.getY() + hitCy + hit.offsetY + delta.y;
    double worldCz = rayResult.block.getZ() + hitCz + hit.offsetZ + delta.z;
    Block host =
        rayResult
            .block
            .getWorld()
            .getBlockAt(
                (int) Math.floor(worldCx), (int) Math.floor(worldCy), (int) Math.floor(worldCz));
    if (!canEditBlock(player, host) || !ensureVoxelHost(host, plugin.getDataManager())) return;
    int px =
        Math.max(
            0,
            Math.min(16 - size, ((int) Math.floor((worldCx - host.getX()) * 16.0 / size)) * size));
    int py =
        Math.max(
            0,
            Math.min(16 - size, ((int) Math.floor((worldCy - host.getY()) * 16.0 / size)) * size));
    int pz =
        Math.max(
            0,
            Math.min(16 - size, ((int) Math.floor((worldCz - host.getZ()) * 16.0 / size)) * size));
    String material = modeToggleListener.getPlayerSelectedBlock(player);
    VoxelPiece placed =
        new VoxelPiece(
            px, py, pz, size, material, selectedBlockDataFor(player, material, rayResult.hitFace));
    placed.offsetX = worldCx - host.getX() - (px + size / 2.0) / 16.0;
    placed.offsetY = worldCy - host.getY() - (py + size / 2.0) / 16.0;
    placed.offsetZ = worldCz - host.getZ() - (pz + size / 2.0) / 16.0;
    placed.rotationX = hit.rotationX;
    placed.rotationY = hit.rotationY;
    placed.rotationZ = hit.rotationZ;
    placed.quaternionX = hit.quaternionX;
    placed.quaternionY = hit.quaternionY;
    placed.quaternionZ = hit.quaternionZ;
    placed.quaternionW = hit.quaternionW;
    placed.visualScale = hit.visualScale;
    placed.scaleX = hit.scaleX;
    placed.scaleY = hit.scaleY;
    placed.scaleZ = hit.scaleZ;
    placed.transformGroup =
        hit.transformGroup == null ? java.util.UUID.randomUUID().toString() : hit.transformGroup;

    List<VoxelPiece> pieces = plugin.getDataManager().getVoxelPieces(host);
    for (VoxelPiece existing : pieces) {
      if (existing.transformGroup != null
          && existing.transformGroup.equals(placed.transformGroup)
          && Math.abs(existing.offsetX - placed.offsetX) < 1.0e-5
          && Math.abs(existing.offsetY - placed.offsetY) < 1.0e-5
          && Math.abs(existing.offsetZ - placed.offsetZ) < 1.0e-5) {
        modeToggleListener.setStatus(player, "Cannot place: occupied local cell");
        return;
      }
    }
    List<VoxelPiece> before = plugin.getVoxelEditHistory().snapshot(host);
    pieces.add(placed);
    plugin.getDataManager().setVoxelPieces(host, pieces);
    plugin.getFallingBlockManager().updateBlockDisplay(host, null);
    CollisionBlockManager.updateCollisionBlock(
        host, pieces, plugin.getDataManager().isBlockLocked(host));
    plugin
        .getVoxelEditHistory()
        .remember(player, host, before, plugin.getVoxelEditHistory().snapshot(host));
    modeToggleListener.setStatus(player, "Placed on rotated grid");
  }

  private PlacementTarget placementTarget(VoxelManager.RaycastResult rayResult, int scale) {
    int pieceSize = VoxelPieceManager.getPieceSizeForScale(scale);
    Block targetBlock = rayResult.block;
    int[] coords;
    if (rayResult.piece == null || rayResult.hitFace == null) {
      coords =
          VoxelManager.getGridAlignedCoords(rayResult.hitX, rayResult.hitY, rayResult.hitZ, scale);
    } else {
      VoxelPiece hitPiece = rayResult.piece;
      int targetX = snapInsidePiece(rayResult.hitX, hitPiece.x, hitPiece.size, pieceSize);
      int targetY = snapInsidePiece(rayResult.hitY, hitPiece.y, hitPiece.size, pieceSize);
      int targetZ = snapInsidePiece(rayResult.hitZ, hitPiece.z, hitPiece.size, pieceSize);

      switch (rayResult.hitFace) {
        case WEST -> targetX = hitPiece.x - pieceSize;
        case EAST -> targetX = hitPiece.x + hitPiece.size;
        case DOWN -> targetY = hitPiece.y - pieceSize;
        case UP -> targetY = hitPiece.y + hitPiece.size;
        case NORTH -> targetZ = hitPiece.z - pieceSize;
        case SOUTH -> targetZ = hitPiece.z + hitPiece.size;
        default -> {
          return null;
        }
      }
      coords = new int[] {targetX, targetY, targetZ};
    }

    if (coords[0] < 0) {
      targetBlock = targetBlock.getRelative(BlockFace.WEST);
      coords[0] = 16 - pieceSize;
    } else if (coords[0] > 16 - pieceSize) {
      targetBlock = targetBlock.getRelative(BlockFace.EAST);
      coords[0] = 0;
    }

    if (coords[1] < 0) {
      targetBlock = targetBlock.getRelative(BlockFace.DOWN);
      coords[1] = 16 - pieceSize;
    } else if (coords[1] > 16 - pieceSize) {
      targetBlock = targetBlock.getRelative(BlockFace.UP);
      coords[1] = 0;
    }

    if (coords[2] < 0) {
      targetBlock = targetBlock.getRelative(BlockFace.NORTH);
      coords[2] = 16 - pieceSize;
    } else if (coords[2] > 16 - pieceSize) {
      targetBlock = targetBlock.getRelative(BlockFace.SOUTH);
      coords[2] = 0;
    }

    return new PlacementTarget(targetBlock, coords, pieceSize);
  }

  private int snapInsidePiece(int value, int start, int size, int pieceSize) {
    int offset = Math.max(0, Math.min(size - 1, value - start));
    return start + (offset / pieceSize) * pieceSize;
  }

  private void placeVoxelPiece(
      Player player, Block block, int[] coords, int scale, BlockFace face) {
    if (!canEditBlock(player, block)) {
      return;
    }
    DataManager dataManager = plugin.getDataManager();
    String selectedBlock = modeToggleListener.getPlayerSelectedBlock(player);
    if (selectedBlock == null || selectedBlock.isEmpty()) {
      return;
    }

    int pieceSize = VoxelPieceManager.getPieceSizeForScale(scale);
    WorldVoxel anchor =
        worldVoxelAt(
            block.getWorld(),
            block.getX() * 16 + coords[0],
            block.getY() * 16 + coords[1],
            block.getZ() * 16 + coords[2],
            pieceSize);
    int placed =
        applyPieces(
            player,
            directedStackPieces(
                player, anchor, selectedBlock, selectedBlockDataFor(player, selectedBlock, face)),
            false);
    modeToggleListener.setStatus(
        player,
        placed == 0 ? "Cannot place: overlap" : "Placed " + selectedBlock.replace("_", " "));
  }

  private void removeVoxelPiece(Player player, org.bukkit.block.Block block, int[] coords) {
    if (!canEditBlock(player, block)) {
      return;
    }
    DataManager dataManager = plugin.getDataManager();
    FallingBlockManager fallingBlockManager = plugin.getFallingBlockManager();

    java.util.List<me.reube.SmallVoxels.managers.VoxelPiece> pieces =
        dataManager.getVoxelPieces(block);
    List<VoxelPiece> before = plugin.getVoxelEditHistory().snapshot(block);

    int cutSize =
        VoxelPieceManager.getPieceSizeForScale(modeToggleListener.getPlayerVoxelScale(player));
    int removed = VoxelPieceManager.chiselAt(pieces, coords[0], coords[1], coords[2], cutSize);

    if (removed == 0) {
      return;
    }

    if (pieces.isEmpty()) {
      block.setType(org.bukkit.Material.AIR);
    }

    dataManager.setVoxelPieces(block, pieces);
    fallingBlockManager.updateBlockDisplay(block, null);

    boolean isLocked = dataManager.isBlockLocked(block);
    CollisionBlockManager.updateCollisionBlock(block, pieces, isLocked);
    plugin
        .getVoxelEditHistory()
        .remember(player, block, before, plugin.getVoxelEditHistory().snapshot(block));

    modeToggleListener.setStatus(player, "Removed " + removed + " voxel(s)");
  }

  private void replaceVoxelPiece(Player player, VoxelManager.RaycastResult rayResult) {
    if (!player.isSneaking()) {
      modeToggleListener.setStatus(player, "Shift select replace area");
      return;
    }

    selectReplace(player, rayResult, plugin.getDataManager().getVoxelPieces(rayResult.block));
  }

  private void copyVoxelBlock(Player player, VoxelManager.RaycastResult rayResult) {
    List<VoxelPiece> pieces = plugin.getDataManager().getVoxelPieces(rayResult.block);
    if (pieces.isEmpty()) {
      return;
    }

    if (!player.isSneaking()) {
      modeToggleListener.setStatus(player, "Shift select copy area");
      return;
    }

    copySelection(player, rayResult, pieces);
  }

  private void copySelection(
      Player player, VoxelManager.RaycastResult rayResult, List<VoxelPiece> pieces) {
    String key = player.getUniqueId().toString();
    WorldVoxel previous = copySelections.get(key);
    WorldVoxel current = centerTarget(rayResult, modeToggleListener.getPlayerVoxelScale(player));

    if (previous == null) {
      copySelections.put(key, current);
      plugin
          .getVoxelPreviewManager()
          .showSet(
              player,
              piecesForBox(
                  player,
                  current,
                  current,
                  modeToggleListener.getPlayerSelectedBlock(player),
                  null));
      modeToggleListener.setStatus(player, "Copy corner 1 set");
      return;
    }

    current = extendSelection(player, previous, current);
    plugin
        .getVoxelPreviewManager()
        .showSet(
            player,
            piecesForBox(
                player,
                previous,
                current,
                modeToggleListener.getPlayerSelectedBlock(player),
                null));
    java.util.ArrayList<VoxelPiece> selected = collectWorldSelection(previous, current);

    if (selected.isEmpty()) {
      replaceSourceMaterials.remove(key);
      modeToggleListener.setStatus(player, "Selection empty");
      return;
    }

    int copyMinX = selected.stream().mapToInt(piece -> piece.x).min().orElse(0);
    int copyMinY = selected.stream().mapToInt(piece -> piece.y).min().orElse(0);
    int copyMinZ = selected.stream().mapToInt(piece -> piece.z).min().orElse(0);
    int copyMaxX = selected.stream().mapToInt(piece -> piece.x + piece.size).max().orElse(copyMinX);
    int copyMaxY = selected.stream().mapToInt(piece -> piece.y + piece.size).max().orElse(copyMinY);
    int copyMaxZ = selected.stream().mapToInt(piece -> piece.z + piece.size).max().orElse(copyMinZ);
    java.util.ArrayList<VoxelPiece> normalized = new java.util.ArrayList<>();
    for (VoxelPiece piece : selected) {
      VoxelPiece copy =
          new VoxelPiece(
              piece.x - copyMinX,
              piece.y - copyMinY,
              piece.z - copyMinZ,
              piece.size,
              piece.material,
              piece.blockData);
      normalized.add(copy);
    }

    plugin.getVoxelClipboard().copy(player, normalized);
    plugin
        .getVoxelSelectionManager()
        .setLastCopyWorldSelection(
            player,
            previous.block.getWorld(),
            copyMinX,
            copyMinY,
            copyMinZ,
            copyMaxX,
            copyMaxY,
            copyMaxZ);
    copySelections.remove(key);
    pendingPaste.remove(key);
    modeToggleListener.setStatus(player, "Copied " + selected.size() + " selected");
  }

  private void selectReplace(
      Player player, VoxelManager.RaycastResult rayResult, List<VoxelPiece> pieces) {
    String key = player.getUniqueId().toString();
    WorldVoxel previous = replaceSelections.get(key);
    WorldVoxel current = centerTarget(rayResult, modeToggleListener.getPlayerVoxelScale(player));

    if (previous == null) {
      replaceSelections.put(key, current);
      replaceSourceMaterials.put(key, rayResult.piece == null ? null : rayResult.piece.material);
      plugin
          .getVoxelPreviewManager()
          .showReplace(
              player,
              current.block,
              current.localX,
              current.localY,
              current.localZ,
              current.localX + current.size,
              current.localY + current.size,
              current.localZ + current.size);
      modeToggleListener.setStatus(player, "Replace corner 1 set");
      return;
    }

    java.util.ArrayList<VoxelPiece> selected = collectWorldSelection(previous, current);
    plugin
        .getVoxelPreviewManager()
        .showReplace(player, previewOutline(piecesForSet(player, previous, current)));
    replaceSelections.remove(key);

    if (selected.isEmpty()) {
      modeToggleListener.setStatus(player, "Selection empty");
      return;
    }

    String sourceMaterial = null;
    sourceMaterial = modeToggleListener.getReplaceFromBlock(player);
    if (sourceMaterial == null || sourceMaterial.isBlank()) {
      sourceMaterial = null;
    }
    replaceSourceMaterials.remove(key);
    plugin
        .getVoxelSelectionManager()
        .setReplaceWorldSelection(
            player,
            previous.block.getWorld(),
            Math.min(previous.worldX, current.worldX),
            Math.min(previous.worldY, current.worldY),
            Math.min(previous.worldZ, current.worldZ),
            Math.max(previous.worldX, current.worldX) + current.size,
            Math.max(previous.worldY, current.worldY) + current.size,
            Math.max(previous.worldZ, current.worldZ) + current.size,
            sourceMaterial);
    modeToggleListener.setStatus(player, "Pick replacement block");
    modeToggleListener.blockGUI.openGUI(player);
  }

  private void pasteClipboard(Player player, VoxelManager.RaycastResult rayResult, int scale) {
    if (!plugin.getVoxelClipboard().hasCopy(player)) {
      modeToggleListener.setStatus(player, "Copy something first");
      return;
    }

    WorldVoxel anchor = pasteAnchorWorld(rayResult, scale);
    PasteTarget target = new PasteTarget(anchor.block, anchor.localX, anchor.localY, anchor.localZ);
    String key = player.getUniqueId().toString();
    PasteTarget pending = pendingPaste.get(key);
    if (pending == null || !pending.matches(target)) {
      pendingPaste.put(key, target);
      plugin.getVoxelPreviewManager().showPaste(player, pastePiecesAt(player, anchor));
      modeToggleListener.setStatus(player, "Paste preview: click again");
      return;
    }

    int placed =
        applyPastePieces(
            player, pastePiecesAt(player, anchor), modeToggleListener.isPasteMask(player));
    pendingPaste.remove(key);
    plugin.getVoxelPreviewManager().clear(player);
    modeToggleListener.setStatus(player, placed == 0 ? "Paste blocked" : "Paste complete");
  }

  private WorldVoxel pasteAnchorWorld(VoxelManager.RaycastResult rayResult, int scale) {
    PlacementTarget target = placementTarget(rayResult, scale);
    if (target == null) {
      return centerTarget(rayResult, scale);
    }
    return worldVoxelAt(
        target.block.getWorld(),
        target.block.getX() * 16 + target.coords[0],
        target.block.getY() * 16 + target.coords[1],
        target.block.getZ() * 16 + target.coords[2],
        target.size);
  }

  private Map<Block, List<VoxelPiece>> pastePiecesAt(Player player, WorldVoxel anchor) {
    Map<Block, List<VoxelPiece>> result = new java.util.LinkedHashMap<>();
    int repeats = Math.max(1, modeToggleListener.getToolHeight(player));
    String axis = modeToggleListener.getToolAxis(player);
    int step = Math.max(1, clipboardAxisSize(axis, plugin.getVoxelClipboard().get(player)));
    for (int i = 0; i < repeats; i++) {
      int ox = "x".equals(axis) ? i * step : 0;
      int oy = "y".equals(axis) ? i * step : 0;
      int oz = "z".equals(axis) ? i * step : 0;
      for (VoxelPiece copied : plugin.getVoxelClipboard().get(player)) {
        addWorldPiece(
            result,
            anchor.block.getWorld(),
            anchor.worldX + copied.x + ox,
            anchor.worldY + copied.y + oy,
            anchor.worldZ + copied.z + oz,
            copied.size,
            copied.material,
            copied.blockData);
      }
    }
    return result;
  }

  private Map<Block, List<VoxelPiece>> directedStackPieces(
      Player player, WorldVoxel anchor, String material, String blockData) {
    Map<Block, List<VoxelPiece>> result = new java.util.LinkedHashMap<>();
    int height = Math.max(1, modeToggleListener.getToolHeight(player));
    for (int i = 0; i < height; i++) {
      int[] offset = axisOffset(player, i * anchor.size);
      addWorldPiece(
          result,
          anchor.block.getWorld(),
          anchor.worldX + offset[0],
          anchor.worldY + offset[1],
          anchor.worldZ + offset[2],
          anchor.size,
          material,
          blockData);
    }
    return result;
  }

  private void showPlacementPreview(Player player, WorldVoxel anchor) {
    String selectedBlock = modeToggleListener.getPlayerSelectedBlock(player);
    Map<Block, List<VoxelPiece>> preview =
        directedStackPieces(
            player, anchor, selectedBlock, selectedBlockDataFor(player, selectedBlock, null));
    if (modeToggleListener.getToolHeight(player) <= 1) {
      plugin
          .getVoxelPreviewManager()
          .showPlacement(
              player, anchor.block, anchor.localX, anchor.localY, anchor.localZ, anchor.size);
      return;
    }
    plugin.getVoxelPreviewManager().showPlacement(player, previewOutline(preview));
  }

  private int[] axisOffset(Player player, int amount) {
    return switch (modeToggleListener.getToolAxis(player).toLowerCase()) {
      case "x" -> new int[] {amount, 0, 0};
      case "z" -> new int[] {0, 0, amount};
      default -> new int[] {0, amount, 0};
    };
  }

  private int clipboardAxisSize(String axis, List<VoxelPiece> pieces) {
    if (pieces == null || pieces.isEmpty()) {
      return 1;
    }
    int min = Integer.MAX_VALUE;
    int max = Integer.MIN_VALUE;
    for (VoxelPiece piece : pieces) {
      int start =
          switch (axis) {
            case "x" -> piece.x;
            case "z" -> piece.z;
            default -> piece.y;
          };
      min = Math.min(min, start);
      max = Math.max(max, start + piece.size);
    }
    return Math.max(1, max - min);
  }

  private int applyPastePieces(
      Player player, Map<Block, List<VoxelPiece>> piecesByBlock, boolean mask) {
    DataManager dataManager = plugin.getDataManager();
    int placed = 0;
    Map<Block, List<VoxelPiece>> beforeByBlock = new java.util.LinkedHashMap<>();
    Map<Block, List<VoxelPiece>> afterByBlock = new java.util.LinkedHashMap<>();
    for (Map.Entry<Block, List<VoxelPiece>> entry : piecesByBlock.entrySet()) {
      Block block = entry.getKey();
      if (!canEditBlock(player, block)) {
        continue;
      }
      List<VoxelPiece> before = plugin.getVoxelEditHistory().snapshot(block);
      if (!ensureVoxelHost(block, dataManager)) continue;

      List<VoxelPiece> pieces = dataManager.getVoxelPieces(block);
      int blockPlaced = 0;
      for (VoxelPiece piece : entry.getValue()) {
        boolean added;
        if (mask) {
          added = VoxelPieceManager.place(pieces, piece.copy());
        } else {
          added = piece.isValid();
          if (added) pieces.add(piece.copy());
        }
        if (added) {
          placed++;
          blockPlaced++;
        }
      }
      if (blockPlaced > 0) {
        VoxelPieceManager.compact(pieces);
        dataManager.setVoxelPieces(block, pieces);
        plugin.getFallingBlockManager().updateBlockDisplay(block, null);
        CollisionBlockManager.updateCollisionBlock(block, pieces, dataManager.isBlockLocked(block));
        beforeByBlock.put(block, before);
        afterByBlock.put(block, plugin.getVoxelEditHistory().snapshot(block));
      }
    }
    plugin.getVoxelEditHistory().remember(player, beforeByBlock, afterByBlock);
    return placed;
  }

  private void pasteOnRealBlock(Player player, Block block, BlockFace face) {
    if (!plugin.getVoxelClipboard().hasCopy(player)) {
      modeToggleListener.setStatus(player, "Copy something first");
      return;
    }
    WorldVoxel anchor =
        worldVoxelAdjacentSurface(
            player, block, face, modeToggleListener.getPlayerVoxelScale(player));
    if (anchor == null) {
      return;
    }
    int placed =
        applyPastePieces(
            player, pastePiecesAt(player, anchor), modeToggleListener.isPasteMask(player));
    modeToggleListener.setStatus(player, placed == 0 ? "Paste blocked" : "Paste complete");
  }

  private int[] pasteAnchor(Player player, VoxelManager.RaycastResult rayResult, int scale) {
    int[] anchor = rayResultToVoxelCoords(rayResult);
    int pieceSize = VoxelPieceManager.getPieceSizeForScale(scale);
    if (rayResult.piece != null) {
      anchor = new int[] {rayResult.piece.x, rayResult.piece.y, rayResult.piece.z};
    }

    if (rayResult.hitFace != null) {
      switch (rayResult.hitFace) {
        case WEST -> anchor[0] -= pieceSize;
        case EAST -> anchor[0] += rayResult.piece != null ? rayResult.piece.size : pieceSize;
        case DOWN -> anchor[1] -= pieceSize;
        case UP -> anchor[1] += rayResult.piece != null ? rayResult.piece.size : pieceSize;
        case NORTH -> anchor[2] -= pieceSize;
        case SOUTH -> anchor[2] += rayResult.piece != null ? rayResult.piece.size : pieceSize;
        default -> {}
      }
    }

    int[] aligned =
        VoxelManager.getGridAlignedCoords(
            Math.max(0, Math.min(15, anchor[0])),
            Math.max(0, Math.min(15, anchor[1])),
            Math.max(0, Math.min(15, anchor[2])),
            scale);
    return fitPasteAnchor(aligned, plugin.getVoxelClipboard().get(player));
  }

  private int[] fitPasteAnchor(int[] anchor, List<VoxelPiece> copiedPieces) {
    if (copiedPieces == null || copiedPieces.isEmpty()) {
      return anchor;
    }

    int maxX = 0;
    int maxY = 0;
    int maxZ = 0;
    for (VoxelPiece piece : copiedPieces) {
      maxX = Math.max(maxX, piece.x + piece.size);
      maxY = Math.max(maxY, piece.y + piece.size);
      maxZ = Math.max(maxZ, piece.z + piece.size);
    }

    return new int[] {
      Math.max(0, Math.min(anchor[0], 16 - maxX)),
      Math.max(0, Math.min(anchor[1], 16 - maxY)),
      Math.max(0, Math.min(anchor[2], 16 - maxZ))
    };
  }

  private String blockDataFor(String materialName, BlockFace face) {
    Material material = Material.getMaterial(materialName);
    if (material == null) {
      return null;
    }

    try {
      if (face != null && face != BlockFace.UP && face != BlockFace.DOWN) {
        if (material == Material.TORCH) {
          return wallFacingData(Material.WALL_TORCH, face);
        }
        if (material == Material.REDSTONE_TORCH) {
          return wallFacingData(Material.REDSTONE_WALL_TORCH, face);
        }
        if (material == Material.SOUL_TORCH) {
          return wallFacingData(Material.SOUL_WALL_TORCH, face);
        }
      }

      org.bukkit.block.data.BlockData data = material.createBlockData();
      if (data instanceof org.bukkit.block.data.type.Stairs stairs && face != null) {
        BlockFace facing = supportFace(face);
        if (stairs.getFaces().contains(facing)) {
          stairs.setFacing(facing);
        }
        if (face == BlockFace.DOWN) {
          stairs.setHalf(org.bukkit.block.data.type.Stairs.Half.TOP);
        } else if (face == BlockFace.UP) {
          stairs.setHalf(org.bukkit.block.data.type.Stairs.Half.BOTTOM);
        }
      }
      if (data instanceof org.bukkit.block.data.type.Lantern lantern && face == BlockFace.DOWN) {
        lantern.setHanging(true);
      }
      if (data instanceof org.bukkit.block.data.type.RedstoneWire wire) {
        for (BlockFace side :
            List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
          wire.setFace(side, org.bukkit.block.data.type.RedstoneWire.Connection.SIDE);
        }
      }
      if (!(data instanceof org.bukkit.block.data.type.Stairs)
          && data instanceof org.bukkit.block.data.Directional directional
          && face != null) {
        BlockFace facing = supportFace(face);
        if (directional.getFaces().contains(facing)) {
          directional.setFacing(facing);
        }
      }
      if (data instanceof org.bukkit.block.data.MultipleFacing facingData && face != null) {
        BlockFace support = supportFace(face);
        for (BlockFace allowed : facingData.getAllowedFaces()) {
          facingData.setFace(allowed, allowed == support);
        }
      }
      return data.getAsString();
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private String selectedBlockDataFor(Player player, String materialName, BlockFace face) {
    String customItem = selectedItemData(player);
    return customItem == null ? blockDataFor(materialName, face) : customItem;
  }

  private String selectedItemData(Player player) {
    ItemStack item = modeToggleListener.getPlayerBlockMetadata(player);
    if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
      return null;
    }

    try {
      YamlConfiguration config = new YamlConfiguration();
      config.set("item", item);
      String yaml = config.saveToString();
      return ITEM_DATA_PREFIX
          + Base64.getEncoder().encodeToString(yaml.getBytes(StandardCharsets.UTF_8));
    } catch (Exception ignored) {
      return null;
    }
  }

  private String wallFacingData(Material material, BlockFace face) {
    org.bukkit.block.data.BlockData data = material.createBlockData();
    if (data instanceof org.bukkit.block.data.Directional directional) {
      BlockFace facing = supportFace(face);
      if (directional.getFaces().contains(facing)) {
        directional.setFacing(facing);
      }
    }
    return data.getAsString();
  }

  private BlockFace supportFace(BlockFace face) {
    return switch (face) {
      case NORTH -> BlockFace.SOUTH;
      case SOUTH -> BlockFace.NORTH;
      case EAST -> BlockFace.WEST;
      case WEST -> BlockFace.EAST;
      default -> face;
    };
  }

  private void removeVoxelPiece(Player player, VoxelManager.RaycastResult rayResult, int scale) {
    if (!rayResult.hitPiece) {
      modeToggleListener.setStatus(player, "No voxel there");
      return;
    }
    RemovePreview preview = removePreview(rayResult, scale);
    removeVoxelPiece(player, rayResult.block, new int[] {preview.x, preview.y, preview.z});
  }

  private int[] removePreviewCoords(VoxelManager.RaycastResult rayResult, int scale) {
    RemovePreview preview = removePreview(rayResult, scale);
    return new int[] {preview.x, preview.y, preview.z};
  }

  private RemovePreview removePreview(VoxelManager.RaycastResult rayResult, int scale) {
    int[] coords = rayResultToVoxelCoords(rayResult);
    VoxelPiece piece = rayResult.piece;
    int cutSize = VoxelPieceManager.getPieceSizeForScale(scale);
    if (piece == null) {
      int[] aligned = VoxelManager.getGridAlignedCoords(coords[0], coords[1], coords[2], scale);
      return new RemovePreview(aligned[0], aligned[1], aligned[2], cutSize);
    }
    if (cutSize >= piece.size) {
      return new RemovePreview(piece.x, piece.y, piece.z, piece.size);
    }
    return new RemovePreview(
        ((coords[0] - piece.x) / cutSize) * cutSize + piece.x,
        ((coords[1] - piece.y) / cutSize) * cutSize + piece.y,
        ((coords[2] - piece.z) / cutSize) * cutSize + piece.z,
        cutSize);
  }

  private void brushVoxel(Player player, VoxelManager.RaycastResult rayResult, int scale) {
    BrushSettings settings =
        new BrushSettings(
            modeToggleListener.getBrushRadius(player), modeToggleListener.isBrushRound(player));
    WorldVoxel center = buildTarget(rayResult, scale);
    if (center == null) {
      return;
    }

    if (modeToggleListener.isBrushSmooth(player)) {
      int changed = smoothBrush(player, center, settings);
      modeToggleListener.setStatus(
          player, changed == 0 ? "Smooth unchanged" : "Smoothed " + changed);
      return;
    }

    String selectedBlock = modeToggleListener.getPlayerSelectedBlock(player);
    Map<Block, List<VoxelPiece>> pending =
        brushPieces(
            center,
            settings,
            selectedBlock,
            selectedBlockDataFor(player, selectedBlock, rayResult.hitFace));
    int placed = applyPieces(player, pending, modeToggleListener.isBrushMask(player));
    modeToggleListener.setStatus(player, placed == 0 ? "Brush blocked" : "Brush placed " + placed);
  }

  private void brushOnRealBlock(Player player, Block block, BlockFace face) {
    WorldVoxel center =
        worldVoxelOnSurface(player, block, face, modeToggleListener.getPlayerVoxelScale(player));
    if (center == null) {
      return;
    }
    BrushSettings settings =
        new BrushSettings(
            modeToggleListener.getBrushRadius(player), modeToggleListener.isBrushRound(player));
    if (modeToggleListener.isBrushSmooth(player)) {
      int changed = smoothBrush(player, center, settings);
      modeToggleListener.setStatus(
          player, changed == 0 ? "Smooth unchanged" : "Smoothed " + changed);
      return;
    }
    String selectedBlock = modeToggleListener.getPlayerSelectedBlock(player);
    Map<Block, List<VoxelPiece>> pending =
        brushPieces(
            center, settings, selectedBlock, selectedBlockDataFor(player, selectedBlock, face));
    int placed = applyPieces(player, pending, modeToggleListener.isBrushMask(player));
    modeToggleListener.setStatus(player, placed == 0 ? "Brush blocked" : "Brush placed " + placed);
  }

  private int applyPieces(Player player, Map<Block, List<VoxelPiece>> piecesByBlock) {
    return applyPieces(player, piecesByBlock, true);
  }

  private int applyPieces(Player player, Map<Block, List<VoxelPiece>> piecesByBlock, boolean mask) {
    DataManager dataManager = plugin.getDataManager();
    int placed = 0;
    Map<Block, List<VoxelPiece>> beforeByBlock = new java.util.LinkedHashMap<>();
    Map<Block, List<VoxelPiece>> afterByBlock = new java.util.LinkedHashMap<>();
    for (Map.Entry<Block, List<VoxelPiece>> entry : piecesByBlock.entrySet()) {
      Block block = entry.getKey();
      if (!canEditBlock(player, block)) {
        continue;
      }
      List<VoxelPiece> before = plugin.getVoxelEditHistory().snapshot(block);
      if (!ensureVoxelHost(block, dataManager)) continue;

      List<VoxelPiece> pieces = dataManager.getVoxelPieces(block);
      int blockPlaced = 0;
      for (VoxelPiece piece : entry.getValue()) {
        boolean added;
        if (mask) {
          added = VoxelPieceManager.place(pieces, piece.copy());
        } else {
          added = piece.isValid();
          if (added) pieces.add(piece.copy());
        }
        if (added) {
          placed++;
          blockPlaced++;
        }
      }

      if (blockPlaced > 0) {
        VoxelPieceManager.compact(pieces);
        dataManager.setVoxelPieces(block, pieces);
        plugin.getFallingBlockManager().updateBlockDisplay(block, null);
        CollisionBlockManager.updateCollisionBlock(block, pieces, dataManager.isBlockLocked(block));
        beforeByBlock.put(block, before);
        afterByBlock.put(block, plugin.getVoxelEditHistory().snapshot(block));
      }
    }
    plugin.getVoxelEditHistory().remember(player, beforeByBlock, afterByBlock);
    return placed;
  }

  private void showBrushPreview(Player player, VoxelManager.RaycastResult rayResult) {
    int scale = modeToggleListener.getPlayerVoxelScale(player);
    WorldVoxel center = buildTarget(rayResult, scale);
    if (center == null) {
      plugin.getVoxelPreviewManager().clearLive(player);
      return;
    }
    showBrushPreview(player, center);
  }

  private void showBrushPreview(Player player, WorldVoxel center) {
    BrushSettings settings =
        new BrushSettings(
            modeToggleListener.getBrushRadius(player), modeToggleListener.isBrushRound(player));
    String selectedBlock = modeToggleListener.getPlayerSelectedBlock(player);
    Map<Block, List<VoxelPiece>> preview =
        brushPieces(
            center, settings, selectedBlock, selectedBlockDataFor(player, selectedBlock, null));
    plugin.getVoxelPreviewManager().showBrush(player, previewOutline(preview));
  }

  private Map<Block, List<VoxelPiece>> brushPieces(
      WorldVoxel center, BrushSettings settings, String material, String blockData) {
    Map<Block, List<VoxelPiece>> piecesByBlock = new java.util.LinkedHashMap<>();
    for (int dx = -settings.radius; dx <= settings.radius; dx++) {
      for (int dy = -settings.radius; dy <= settings.radius; dy++) {
        for (int dz = -settings.radius; dz <= settings.radius; dz++) {
          if (settings.round && dx * dx + dy * dy + dz * dz > settings.radius * settings.radius) {
            continue;
          }
          WorldVoxel voxel =
              worldVoxelAt(
                  center.block.getWorld(),
                  center.worldX + dx * center.size,
                  center.worldY + dy * center.size,
                  center.worldZ + dz * center.size,
                  center.size);
          VoxelPiece piece =
              new VoxelPiece(
                  voxel.localX, voxel.localY, voxel.localZ, center.size, material, blockData);
          if (piece.isValid()) {
            piecesByBlock
                .computeIfAbsent(voxel.block, ignored -> new java.util.ArrayList<>())
                .add(piece);
          }
        }
      }
    }
    return piecesByBlock;
  }

  private int smoothBrush(Player player, WorldVoxel center, BrushSettings settings) {
    Map<Block, List<VoxelPiece>> beforeByBlock = new java.util.LinkedHashMap<>();
    Map<Block, List<VoxelPiece>> afterByBlock = new java.util.LinkedHashMap<>();
    int size = center.size;
    org.bukkit.World world = center.block.getWorld();
    String material = modeToggleListener.getPlayerSelectedBlock(player);
    String blockData = selectedBlockDataFor(player, material, null);
    java.util.Set<Block> touched = new java.util.LinkedHashSet<>();
    java.util.Map<String, Boolean> occupied = new java.util.HashMap<>();
    java.util.Map<String, WorldVoxel> candidates = new java.util.LinkedHashMap<>();

    for (int dx = -settings.radius - 1; dx <= settings.radius + 1; dx++) {
      for (int dy = -settings.radius - 1; dy <= settings.radius + 1; dy++) {
        for (int dz = -settings.radius - 1; dz <= settings.radius + 1; dz++) {
          boolean active =
              !settings.round || dx * dx + dy * dy + dz * dz <= settings.radius * settings.radius;
          WorldVoxel voxel =
              worldVoxelAt(
                  world,
                  center.worldX + dx * size,
                  center.worldY + dy * size,
                  center.worldZ + dz * size,
                  size);
          touched.add(voxel.block);
          occupied.put(worldKey(voxel.worldX, voxel.worldY, voxel.worldZ, size), hasVoxelAt(voxel));
          if (active) {
            candidates.put(worldKey(voxel.worldX, voxel.worldY, voxel.worldZ, size), voxel);
          }
        }
      }
    }

    java.util.Set<String> remove = new java.util.HashSet<>();
    java.util.List<WorldVoxel> add = new java.util.ArrayList<>();
    int[][] directions = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    for (Map.Entry<String, WorldVoxel> entry : candidates.entrySet()) {
      WorldVoxel voxel = entry.getValue();
      int neighbours = 0;
      for (int[] direction : directions) {
        String neighbourKey =
            worldKey(
                voxel.worldX + direction[0] * size,
                voxel.worldY + direction[1] * size,
                voxel.worldZ + direction[2] * size,
                size);
        if (occupied.getOrDefault(neighbourKey, false)) {
          neighbours++;
        }
      }
      boolean filled = occupied.getOrDefault(entry.getKey(), false);
      if (filled && neighbours <= 1) {
        remove.add(entry.getKey());
      } else if (!filled && neighbours >= 5) {
        add.add(voxel);
      }
    }

    int changed = 0;
    DataManager dataManager = plugin.getDataManager();
    for (Block block : touched) {
      if (!canEditBlock(player, block)) {
        continue;
      }
      List<VoxelPiece> pieces = dataManager.getVoxelPieces(block);
      boolean needsHost = add.stream().anyMatch(voxel -> voxel.block.equals(block));
      if (pieces.isEmpty() && !needsHost) {
        continue;
      }
      List<VoxelPiece> before = plugin.getVoxelEditHistory().snapshot(block);
      int beforeSize = pieces.size();
      int blockChanged = 0;
      for (String removeKey : remove) {
        String[] parts = removeKey.split(":");
        if (parts.length != 4) {
          continue;
        }
        int wx = Integer.parseInt(parts[0]);
        int wy = Integer.parseInt(parts[1]);
        int wz = Integer.parseInt(parts[2]);
        if (Math.floorDiv(wx, 16) != block.getX()
            || Math.floorDiv(wy, 16) != block.getY()
            || Math.floorDiv(wz, 16) != block.getZ()) {
          continue;
        }
        blockChanged +=
            VoxelPieceManager.chiselAt(
                pieces, Math.floorMod(wx, 16), Math.floorMod(wy, 16), Math.floorMod(wz, 16), size);
      }
      for (WorldVoxel voxel : add) {
        if (!voxel.block.equals(block)) {
          continue;
        }
        if (dataManager.hasCarvedData(block)) {
          block.setType(Material.BARRIER, false);
        } else if (isReplaceableVoxelHost(block)) {
          block.setType(Material.BARRIER, false);
          dataManager.setVoxelPieces(block, new java.util.ArrayList<>());
          pieces = dataManager.getVoxelPieces(block);
        } else {
          continue;
        }
        VoxelPiece piece =
            new VoxelPiece(voxel.localX, voxel.localY, voxel.localZ, size, material, blockData);
        if (VoxelPieceManager.place(pieces, piece)) {
          blockChanged++;
        }
      }
      VoxelPieceManager.compact(pieces);
      if (pieces.size() != beforeSize) {
        changed += Math.max(1, blockChanged);
        dataManager.setVoxelPieces(block, pieces);
        plugin.getFallingBlockManager().updateBlockDisplay(block, null);
        CollisionBlockManager.updateCollisionBlock(block, pieces, dataManager.isBlockLocked(block));
        beforeByBlock.put(block, before);
        afterByBlock.put(block, plugin.getVoxelEditHistory().snapshot(block));
      }
    }
    plugin.getVoxelEditHistory().remember(player, beforeByBlock, afterByBlock);
    return changed;
  }

  private boolean hasVoxelAt(WorldVoxel voxel) {
    List<VoxelPiece> pieces = plugin.getDataManager().getVoxelPieces(voxel.block);
    return VoxelPieceManager.getPieceAt(pieces, voxel.localX, voxel.localY, voxel.localZ) != null;
  }

  private String worldKey(int x, int y, int z, int size) {
    return x + ":" + y + ":" + z + ":" + size;
  }

  private int exposedSides(Block block, VoxelPiece piece, List<VoxelPiece> pieces) {
    int exposed = 0;
    int[][] directions = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    for (int[] direction : directions) {
      int x = piece.x + direction[0] * piece.size;
      int y = piece.y + direction[1] * piece.size;
      int z = piece.z + direction[2] * piece.size;
      if (x < 0 || y < 0 || z < 0 || x > 15 || y > 15 || z > 15) {
        exposed++;
        continue;
      }
      if (VoxelPieceManager.getPieceAt(pieces, x, y, z) == null) {
        exposed++;
      }
    }
    return exposed;
  }

  private void selectSet(Player player, VoxelManager.RaycastResult rayResult) {
    WorldVoxel point = centerTarget(rayResult, modeToggleListener.getPlayerVoxelScale(player));
    if (point != null) {
      selectSet(player, point);
    }
  }

  private void selectSet(Player player, Block block, BlockFace face) {
    WorldVoxel point =
        worldVoxelAdjacentSurface(
            player, block, face, modeToggleListener.getPlayerVoxelScale(player));
    if (point == null) {
      return;
    }
    selectSet(player, point);
  }

  private void selectSet(Player player, WorldVoxel current) {
    String key = player.getUniqueId().toString();
    String signature = setSignature(player);
    String previousSignature = setSelectionSignatures.get(key);
    if (previousSignature != null && !previousSignature.equals(signature)) {
      setSelections.remove(key);
      setSelectionSignatures.remove(key);
    }

    WorldVoxel previous = setSelections.get(key);
    if (previous == null) {
      setSelections.put(key, current);
      setSelectionSignatures.put(key, signature);
      plugin
          .getVoxelPreviewManager()
          .showSet(player, previewOutline(piecesForSet(player, current, current)));
      modeToggleListener.setStatus(player, "Set corner 1");
      return;
    }

    current = extendSelection(player, previous, current);
    applySet(player, previous, current);
    if (modeToggleListener.isSetKeepCorner(player)) {
      setSelections.put(key, current);
      setSelectionSignatures.put(key, signature);
    } else {
      setSelections.remove(key);
      setSelectionSignatures.remove(key);
    }
  }

  private void showSetPreview(Player player, VoxelManager.RaycastResult rayResult) {
    WorldVoxel current = centerTarget(rayResult, modeToggleListener.getPlayerVoxelScale(player));
    if (current != null) {
      showSetPreview(player, current);
    }
  }

  private void showSetPreview(Player player, WorldVoxel current) {
    String key = player.getUniqueId().toString();
    WorldVoxel previous = setSelections.get(key);
    String previousSignature = setSelectionSignatures.get(key);
    if (previousSignature != null && !previousSignature.equals(setSignature(player))) {
      setSelections.remove(key);
      setSelectionSignatures.remove(key);
      previous = null;
    }
    if (previous == null) {
      plugin
          .getVoxelPreviewManager()
          .showSet(player, previewOutline(piecesForSet(player, current, current)));
      return;
    }
    current = extendSelection(player, previous, current);
    int count =
        modeToggleListener.isSetLine(player)
            ? lineVoxelCount(previous, current)
            : selectionVoxelCount(previous, current);
    if (count > maxSetVoxels()) {
      plugin
          .getVoxelPreviewManager()
          .showSet(
              player,
              previewOutline(cappedPiecesForSet(player, previous, current, maxPreviewVoxels())));
      modeToggleListener.setStatus(player, "Set too large: " + count + "/" + maxSetVoxels());
      return;
    }
    plugin
        .getVoxelPreviewManager()
        .showSet(player, previewOutline(piecesForSet(player, previous, current)));
  }

  private void showCopyPreview(Player player, VoxelManager.RaycastResult rayResult) {
    WorldVoxel current = centerTarget(rayResult, modeToggleListener.getPlayerVoxelScale(player));
    WorldVoxel previous = copySelections.get(player.getUniqueId().toString());
    if (previous == null) {
      plugin
          .getVoxelPreviewManager()
          .showSelection(
              player,
              previewOutline(
                  piecesForBox(
                      player,
                      current,
                      current,
                      modeToggleListener.getPlayerSelectedBlock(player),
                      null)));
      return;
    }
    current = extendSelection(player, previous, current);
    plugin
        .getVoxelPreviewManager()
        .showSelection(
            player,
            previewOutline(
                piecesForBox(
                    player,
                    previous,
                    current,
                    modeToggleListener.getPlayerSelectedBlock(player),
                    null)));
  }

  private void showReplacePreview(Player player, VoxelManager.RaycastResult rayResult) {
    WorldVoxel current = centerTarget(rayResult, modeToggleListener.getPlayerVoxelScale(player));
    WorldVoxel previous = replaceSelections.get(player.getUniqueId().toString());
    if (previous == null) {
      plugin
          .getVoxelPreviewManager()
          .showReplace(player, previewOutline(piecesForSet(player, current, current)));
      return;
    }
    plugin
        .getVoxelPreviewManager()
        .showReplace(player, previewOutline(piecesForSet(player, previous, current)));
  }

  private boolean previewNormalMove(Player player) {
    SolidHit hit = rayTraceSolidIgnoringBarriers(player, 6.0);
    if (hit == null || hit.face == null) {
      return false;
    }
    WorldVoxel point =
        worldVoxelOnSurface(
            player, hit.block, hit.face, modeToggleListener.getPlayerVoxelScale(player));
    if (point == null) {
      return false;
    }
    showMovePreview(player, point);
    return true;
  }

  private void selectMove(Player player, VoxelManager.RaycastResult rayResult) {
    if (rayResult.piece != null
        && rayResult.piece.transformGroup != null
        && plugin
            .getVoxelSelectionManager()
            .selectTransformGroup(
                player, rayResult.block.getWorld(), rayResult.piece.transformGroup)) {
      moveSelections.remove(player.getUniqueId().toString());
      modeToggleListener.setStatus(
          player, modeToggleListener.getToolMode(player).label() + " group selected");
      modeToggleListener.blockGUI.openGUI(player);
      return;
    }
    WorldVoxel current = centerTarget(rayResult, modeToggleListener.getPlayerVoxelScale(player));
    if (current != null) {
      selectMove(player, current);
    }
  }

  private void selectMove(Player player, Block block, BlockFace face) {
    WorldVoxel current =
        worldVoxelOnSurface(player, block, face, modeToggleListener.getPlayerVoxelScale(player));
    if (current != null) {
      selectMove(player, current);
    }
  }

  private void selectMove(Player player, WorldVoxel current) {
    String key = player.getUniqueId().toString();
    WorldVoxel previous = moveSelections.get(key);
    if (previous == null) {
      plugin.getVoxelSelectionManager().beginTransformReselection(player);
      moveSelections.put(key, current);
      transformSelectionRadius.put(key, 0);
      plugin
          .getVoxelPreviewManager()
          .showMove(
              player,
              previewOutline(piecesForBox(player, current, current, "BLUE_STAINED_GLASS", null)));
      modeToggleListener.setStatus(
          player, modeToggleListener.getToolMode(player).label() + " selection: drag to corner 2");
      return;
    }

    if (transformSelectionRadius.getOrDefault(key, 0) > 0) {
      moveSelections.remove(key);
      transformSelectionRadius.remove(key);
      plugin.getVoxelSelectionManager().showTransformSelection(player);
      modeToggleListener.setStatus(
          player, modeToggleListener.getToolMode(player).label() + " selection confirmed");
      modeToggleListener.blockGUI.openGUI(player);
      return;
    }

    current = extendSelection(player, previous, current);
    java.util.ArrayList<VoxelPiece> selected = collectWorldSelection(previous, current);
    if (selected.isEmpty()) {
      moveSelections.remove(key);
      modeToggleListener.setStatus(player, "Selection empty");
      return;
    }

    plugin
        .getVoxelSelectionManager()
        .setMoveWorldSelection(
            player,
            previous.block.getWorld(),
            Math.min(previous.worldX, current.worldX),
            Math.min(previous.worldY, current.worldY),
            Math.min(previous.worldZ, current.worldZ),
            Math.max(previous.worldX, current.worldX) + current.size,
            Math.max(previous.worldY, current.worldY) + current.size,
            Math.max(previous.worldZ, current.worldZ) + current.size);
    moveSelections.remove(key);
    transformSelectionRadius.remove(key);
    plugin.getVoxelSelectionManager().showTransformSelection(player);
    modeToggleListener.setStatus(
        player, modeToggleListener.getToolMode(player).label() + " selection ready");
    modeToggleListener.blockGUI.openGUI(player);
  }

  public boolean adjustTransformSelection(Player player, int direction) {
    ToolMode mode = modeToggleListener.getToolMode(player);
    if (mode != ToolMode.MOVE && mode != ToolMode.ROTATE && mode != ToolMode.SCALE) return false;
    String key = player.getUniqueId().toString();
    WorldVoxel anchor = moveSelections.get(key);
    if (anchor == null) return false;
    int radius =
        Math.max(0, Math.min(64, transformSelectionRadius.getOrDefault(key, 0) + direction));
    transformSelectionRadius.put(key, radius);
    int extent = radius * anchor.size;
    plugin
        .getVoxelSelectionManager()
        .updateTransformSelection(
            player,
            anchor.block.getWorld(),
            anchor.worldX - extent,
            anchor.worldY - extent,
            anchor.worldZ - extent,
            anchor.worldX + anchor.size + extent,
            anchor.worldY + anchor.size + extent,
            anchor.worldZ + anchor.size + extent);
    int width = radius * 2 + 1;
    player.sendActionBar(
        Component.text(
                "Selecting "
                    + width
                    + "×"
                    + width
                    + "×"
                    + width
                    + " cells | Shift-scroll: resize | Shift-click: confirm | Shift-right-click:"
                    + " restore")
            .color(NamedTextColor.AQUA));
    return true;
  }

  private void showMovePreview(Player player, VoxelManager.RaycastResult rayResult) {
    WorldVoxel current = centerTarget(rayResult, modeToggleListener.getPlayerVoxelScale(player));
    if (current != null) {
      showMovePreview(player, current);
    }
  }

  private void showMovePreview(Player player, WorldVoxel current) {
    WorldVoxel previous = moveSelections.get(player.getUniqueId().toString());
    if (previous == null) {
      plugin
          .getVoxelPreviewManager()
          .showMove(
              player,
              previewOutline(piecesForBox(player, current, current, "BLUE_STAINED_GLASS", null)));
      return;
    }
    plugin
        .getVoxelPreviewManager()
        .showMove(
            player,
            previewOutline(piecesForBox(player, previous, current, "BLUE_STAINED_GLASS", null)));
  }

  private void selectRotate(Player player, VoxelManager.RaycastResult rayResult) {
    WorldVoxel current = centerTarget(rayResult, modeToggleListener.getPlayerVoxelScale(player));
    String key = player.getUniqueId().toString();
    WorldVoxel previous = rotateSelections.get(key);
    if (previous == null) {
      rotateSelections.put(key, current);
      plugin
          .getVoxelPreviewManager()
          .showSelection(
              player,
              previewOutline(piecesForBox(player, current, current, "YELLOW_STAINED_GLASS", null)));
      modeToggleListener.setStatus(player, "Rotate corner 1");
      return;
    }

    current = extendSelection(player, previous, current);
    int changed = rotateSelection(player, previous, current);
    rotateSelections.remove(key);
    plugin.getVoxelPreviewManager().clearLive(player);
    modeToggleListener.setStatus(
        player, changed == 0 ? "Selection empty" : "Rotated " + changed + " voxel(s)");
  }

  private int rotateSelection(Player player, WorldVoxel first, WorldVoxel second) {
    int minX = Math.min(first.worldX, second.worldX);
    int minY = Math.min(first.worldY, second.worldY);
    int minZ = Math.min(first.worldZ, second.worldZ);
    int maxX = Math.max(first.worldX, second.worldX) + second.size;
    int maxY = Math.max(first.worldY, second.worldY) + second.size;
    int maxZ = Math.max(first.worldZ, second.worldZ) + second.size;
    Map<Block, List<VoxelPiece>> beforeByBlock = new java.util.LinkedHashMap<>();
    Map<Block, List<VoxelPiece>> afterByBlock = new java.util.LinkedHashMap<>();
    DataManager dataManager = plugin.getDataManager();
    int changed = 0;

    org.bukkit.World world = first.block.getWorld();
    double pivotX = (minX + maxX) / 32.0;
    double pivotY = (minY + maxY) / 32.0;
    double pivotZ = (minZ + maxZ) / 32.0;
    double radians = Math.toRadians(modeToggleListener.getRotateAngle(player));
    double sin = Math.sin(radians), cos = Math.cos(radians);
    double visualScale = modeToggleListener.getRotateScale(player);
    String axis = modeToggleListener.getRotateAxis(player);
    String transformGroup = java.util.UUID.randomUUID().toString();
    for (int bx = Math.floorDiv(minX, 16); bx <= Math.floorDiv(maxX - 1, 16); bx++) {
      for (int by = Math.floorDiv(minY, 16); by <= Math.floorDiv(maxY - 1, 16); by++) {
        for (int bz = Math.floorDiv(minZ, 16); bz <= Math.floorDiv(maxZ - 1, 16); bz++) {
          Block block = world.getBlockAt(bx, by, bz);
          if (!canEditBlock(player, block)) {
            continue;
          }
          List<VoxelPiece> pieces = dataManager.getVoxelPieces(block);
          if (pieces.isEmpty()) {
            continue;
          }
          List<VoxelPiece> before = plugin.getVoxelEditHistory().snapshot(block);
          int blockChanged = 0;
          for (VoxelPiece piece : pieces) {
            int wx = block.getX() * 16 + piece.x;
            int wy = block.getY() * 16 + piece.y;
            int wz = block.getZ() * 16 + piece.z;
            if (worldPieceIntersects(wx, wy, wz, piece.size, minX, minY, minZ, maxX, maxY, maxZ)) {
              double cx = block.getX() + (piece.x + piece.size / 2.0) / 16.0 + piece.offsetX;
              double cy = block.getY() + (piece.y + piece.size / 2.0) / 16.0 + piece.offsetY;
              double cz = block.getZ() + (piece.z + piece.size / 2.0) / 16.0 + piece.offsetZ;
              double dx = cx - pivotX, dy = cy - pivotY, dz = cz - pivotZ;
              double rx = dx, ry = dy, rz = dz;
              if (axis.equals("x")) {
                ry = dy * cos - dz * sin;
                rz = dy * sin + dz * cos;
                piece.rotationX += Math.toDegrees(radians);
              } else if (axis.equals("z")) {
                rx = dx * cos - dy * sin;
                ry = dx * sin + dy * cos;
                piece.rotationZ += Math.toDegrees(radians);
              } else {
                rx = dx * cos - dz * sin;
                rz = dx * sin + dz * cos;
                piece.rotationY += Math.toDegrees(radians);
              }
              double anchorX = block.getX() + (piece.x + piece.size / 2.0) / 16.0;
              double anchorY = block.getY() + (piece.y + piece.size / 2.0) / 16.0;
              double anchorZ = block.getZ() + (piece.z + piece.size / 2.0) / 16.0;
              piece.offsetX = pivotX + rx * visualScale - anchorX;
              piece.offsetY = pivotY + ry * visualScale - anchorY;
              piece.offsetZ = pivotZ + rz * visualScale - anchorZ;
              piece.visualScale *= visualScale;
              piece.transformGroup = transformGroup;
              blockChanged++;
            }
          }
          if (blockChanged > 0) {
            changed += blockChanged;
            dataManager.setVoxelPieces(block, pieces);
            plugin.getFallingBlockManager().updateBlockDisplay(block, null);
            CollisionBlockManager.updateCollisionBlock(
                block, pieces, dataManager.isBlockLocked(block));
            beforeByBlock.put(block, before);
            afterByBlock.put(block, plugin.getVoxelEditHistory().snapshot(block));
          }
        }
      }
    }
    plugin.getVoxelEditHistory().remember(player, beforeByBlock, afterByBlock);
    return changed;
  }

  private void selectRemove(Player player, VoxelManager.RaycastResult rayResult) {
    WorldVoxel current = centerTarget(rayResult, modeToggleListener.getPlayerVoxelScale(player));
    if (current == null) {
      return;
    }
    selectRemove(player, current);
  }

  private void selectRemove(Player player, Block block, BlockFace face) {
    WorldVoxel current =
        worldVoxelOnSurface(player, block, face, modeToggleListener.getPlayerVoxelScale(player));
    if (current == null) {
      return;
    }
    selectRemove(player, current);
  }

  private void selectRemove(Player player, WorldVoxel current) {
    String key = player.getUniqueId().toString();
    String signature = removeSignature(player);
    String previousSignature = removeSelectionSignatures.get(key);
    if (previousSignature != null && !previousSignature.equals(signature)) {
      removeSelections.remove(key);
      removeSelectionSignatures.remove(key);
    }

    WorldVoxel previous = removeSelections.get(key);
    if (previous == null) {
      removeSelections.put(key, current);
      removeSelectionSignatures.put(key, signature);
      plugin
          .getVoxelPreviewManager()
          .showRemove(
              player, current.block, current.localX, current.localY, current.localZ, current.size);
      modeToggleListener.setStatus(player, "Remove corner 1");
      return;
    }

    current = extendSelection(player, previous, current);
    int removed = removeSelection(player, previous, current);
    if (modeToggleListener.isRemoveKeepCorner(player)) {
      removeSelections.put(key, current);
      removeSelectionSignatures.put(key, signature);
    } else {
      removeSelections.remove(key);
      removeSelectionSignatures.remove(key);
    }
    plugin.getVoxelPreviewManager().clearLive(player);
    if (removed >= 0) {
      modeToggleListener.setStatus(player, "Removed " + removed + " voxel(s)");
    }
  }

  private void showRemoveSelectionPreview(Player player, VoxelManager.RaycastResult rayResult) {
    WorldVoxel current = centerTarget(rayResult, modeToggleListener.getPlayerVoxelScale(player));
    if (current == null) {
      return;
    }
    showRemoveSelectionPreview(player, current);
  }

  private void showRemoveSelectionPreview(Player player, WorldVoxel current) {
    String key = player.getUniqueId().toString();
    WorldVoxel previous = removeSelections.get(key);
    String previousSignature = removeSelectionSignatures.get(key);
    if (previousSignature != null && !previousSignature.equals(removeSignature(player))) {
      removeSelections.remove(key);
      removeSelectionSignatures.remove(key);
      previous = null;
    }
    if (previous == null) {
      plugin
          .getVoxelPreviewManager()
          .showRemove(
              player, current.block, current.localX, current.localY, current.localZ, current.size);
      return;
    }
    current = extendSelection(player, previous, current);
    int count = selectionVoxelCount(previous, current);
    if (count > maxRemoveVoxels()) {
      plugin
          .getVoxelPreviewManager()
          .showRemove(
              player,
              previewOutline(
                  cappedPiecesForBox(
                      player, previous, current, "RED_STAINED_GLASS", null, maxPreviewVoxels())));
      modeToggleListener.setStatus(player, "Remove too large: " + count + "/" + maxRemoveVoxels());
      return;
    }
    plugin
        .getVoxelPreviewManager()
        .showRemove(
            player,
            previewOutline(piecesForBox(player, previous, current, "RED_STAINED_GLASS", null)));
  }

  private int removeSelection(Player player, WorldVoxel first, WorldVoxel second) {
    int requested = selectionVoxelCount(first, second);
    if (requested > maxRemoveVoxels()) {
      modeToggleListener.setStatus(
          player, "Remove too large: " + requested + "/" + maxRemoveVoxels());
      return -1;
    }

    DataManager dataManager = plugin.getDataManager();
    Map<Block, List<VoxelPiece>> beforeByBlock = new java.util.LinkedHashMap<>();
    Map<Block, List<VoxelPiece>> afterByBlock = new java.util.LinkedHashMap<>();
    int removed = 0;
    int minX = Math.min(first.worldX, second.worldX);
    int minY = Math.min(first.worldY, second.worldY);
    int minZ = Math.min(first.worldZ, second.worldZ);
    int maxX = Math.max(first.worldX, second.worldX) + second.size;
    int maxY = Math.max(first.worldY, second.worldY) + second.size;
    int maxZ = Math.max(first.worldZ, second.worldZ) + second.size;

    org.bukkit.World world = first.block.getWorld();
    for (int bx = Math.floorDiv(minX, 16); bx <= Math.floorDiv(maxX - 1, 16); bx++) {
      for (int by = Math.floorDiv(minY, 16); by <= Math.floorDiv(maxY - 1, 16); by++) {
        for (int bz = Math.floorDiv(minZ, 16); bz <= Math.floorDiv(maxZ - 1, 16); bz++) {
          final int blockX = bx;
          final int blockY = by;
          final int blockZ = bz;
          Block block = world.getBlockAt(bx, by, bz);
          if (!canEditBlock(player, block)) {
            continue;
          }
          List<VoxelPiece> pieces = dataManager.getVoxelPieces(block);
          if (pieces.isEmpty()) {
            continue;
          }
          List<VoxelPiece> before = plugin.getVoxelEditHistory().snapshot(block);
          int beforeCount = pieces.size();
          for (int wx = Math.max(minX, blockX * 16);
              wx < Math.min(maxX, blockX * 16 + 16);
              wx += first.size) {
            for (int wy = Math.max(minY, blockY * 16);
                wy < Math.min(maxY, blockY * 16 + 16);
                wy += first.size) {
              for (int wz = Math.max(minZ, blockZ * 16);
                  wz < Math.min(maxZ, blockZ * 16 + 16);
                  wz += first.size) {
                removed +=
                    VoxelPieceManager.chiselAt(
                        pieces,
                        Math.floorMod(wx, 16),
                        Math.floorMod(wy, 16),
                        Math.floorMod(wz, 16),
                        first.size);
              }
            }
          }
          if (pieces.size() != beforeCount) {
            VoxelPieceManager.compact(pieces);
            dataManager.setVoxelPieces(block, pieces);
            plugin.getFallingBlockManager().updateBlockDisplay(block, null);
            CollisionBlockManager.updateCollisionBlock(
                block, pieces, dataManager.isBlockLocked(block));
            beforeByBlock.put(block, before);
            afterByBlock.put(block, plugin.getVoxelEditHistory().snapshot(block));
          }
        }
      }
    }
    plugin.getVoxelEditHistory().remember(player, beforeByBlock, afterByBlock);
    return removed;
  }

  private void applySet(Player player, WorldVoxel first, WorldVoxel second) {
    int requested =
        modeToggleListener.isSetLine(player)
            ? lineVoxelCount(first, second)
            : selectionVoxelCount(first, second);
    if (requested > maxSetVoxels()) {
      modeToggleListener.setStatus(player, "Set too large: " + requested + "/" + maxSetVoxels());
      return;
    }
    Map<Block, List<VoxelPiece>> pending = piecesForSet(player, first, second);
    int placed = applyPieces(player, pending, modeToggleListener.isSetMask(player));
    modeToggleListener.setStatus(player, "Set placed " + placed);
  }

  private int selectionVoxelCount(WorldVoxel first, WorldVoxel second) {
    int size = Math.max(1, first.size);
    int xCount = Math.abs(second.worldX - first.worldX) / size + 1;
    int yCount = Math.abs(second.worldY - first.worldY) / size + 1;
    int zCount = Math.abs(second.worldZ - first.worldZ) / size + 1;
    long count = (long) xCount * yCount * zCount;
    return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
  }

  private int lineVoxelCount(WorldVoxel first, WorldVoxel second) {
    int size = Math.max(1, first.size);
    return Math.max(
                Math.max(
                    Math.abs(second.worldX - first.worldX), Math.abs(second.worldY - first.worldY)),
                Math.abs(second.worldZ - first.worldZ))
            / size
        + 1;
  }

  private WorldVoxel extendSelection(Player player, WorldVoxel first, WorldVoxel second) {
    int height = Math.max(1, modeToggleListener.getToolHeight(player));
    if (height <= 1) {
      return second;
    }
    int x = second.worldX;
    int y = second.worldY;
    int z = second.worldZ;
    int heightExtra = (height - 1) * second.size;
    switch (modeToggleListener.getToolAxis(player).toLowerCase()) {
      case "x" -> x += second.worldX >= first.worldX ? heightExtra : -heightExtra;
      case "z" -> z += second.worldZ >= first.worldZ ? heightExtra : -heightExtra;
      default -> y += second.worldY >= first.worldY ? heightExtra : -heightExtra;
    }
    return worldVoxelAt(second.block.getWorld(), x, y, z, second.size);
  }

  private int maxSetVoxels() {
    return Math.max(1, plugin.getConfig().getInt("limits.set-voxels", DEFAULT_MAX_SET_VOXELS));
  }

  private int maxRemoveVoxels() {
    return Math.max(
        1, plugin.getConfig().getInt("limits.remove-voxels", DEFAULT_MAX_REMOVE_VOXELS));
  }

  private int maxPreviewVoxels() {
    return Math.max(
        1, plugin.getConfig().getInt("limits.preview-voxels", DEFAULT_MAX_PREVIEW_VOXELS));
  }

  private String setSignature(Player player) {
    return modeToggleListener.getPlayerVoxelScale(player)
        + ":"
        + modeToggleListener.getPlayerSelectedBlock(player)
        + ":"
        + modeToggleListener.isSetLine(player)
        + ":"
        + modeToggleListener.isSetMask(player)
        + ":"
        + modeToggleListener.isSetKeepCorner(player);
  }

  private String removeSignature(Player player) {
    return modeToggleListener.getPlayerVoxelScale(player)
        + ":"
        + modeToggleListener.isRemoveMass(player)
        + ":"
        + modeToggleListener.isRemoveKeepCorner(player);
  }

  private Map<Block, List<VoxelPiece>> piecesForBox(
      Player player, WorldVoxel first, WorldVoxel second, String material, String blockData) {
    Map<Block, List<VoxelPiece>> result = new java.util.LinkedHashMap<>();
    int size = first.size;
    int minX = Math.min(first.worldX, second.worldX);
    int minY = Math.min(first.worldY, second.worldY);
    int minZ = Math.min(first.worldZ, second.worldZ);
    int maxX = Math.max(first.worldX, second.worldX);
    int maxY = Math.max(first.worldY, second.worldY);
    int maxZ = Math.max(first.worldZ, second.worldZ);

    addCompactedWorldBox(
        result,
        first.block.getWorld(),
        minX,
        minY,
        minZ,
        maxX + size,
        maxY + size,
        maxZ + size,
        size,
        material,
        blockData);
    return result;
  }

  private void addCompactedWorldBox(
      Map<Block, List<VoxelPiece>> result,
      org.bukkit.World world,
      int minX,
      int minY,
      int minZ,
      int maxX,
      int maxY,
      int maxZ,
      int baseSize,
      String material,
      String blockData) {
    int minBlockX = Math.floorDiv(minX, 16);
    int minBlockY = Math.floorDiv(minY, 16);
    int minBlockZ = Math.floorDiv(minZ, 16);
    int maxBlockX = Math.floorDiv(maxX - 1, 16);
    int maxBlockY = Math.floorDiv(maxY - 1, 16);
    int maxBlockZ = Math.floorDiv(maxZ - 1, 16);

    for (int bx = minBlockX; bx <= maxBlockX; bx++) {
      for (int by = minBlockY; by <= maxBlockY; by++) {
        for (int bz = minBlockZ; bz <= maxBlockZ; bz++) {
          int localMinX = Math.max(0, minX - bx * 16);
          int localMinY = Math.max(0, minY - by * 16);
          int localMinZ = Math.max(0, minZ - bz * 16);
          int localMaxX = Math.min(16, maxX - bx * 16);
          int localMaxY = Math.min(16, maxY - by * 16);
          int localMaxZ = Math.min(16, maxZ - bz * 16);
          addCompactedLocalBox(
              result,
              world.getBlockAt(bx, by, bz),
              localMinX,
              localMinY,
              localMinZ,
              localMaxX,
              localMaxY,
              localMaxZ,
              baseSize,
              material,
              blockData);
        }
      }
    }
  }

  private void addCompactedLocalBox(
      Map<Block, List<VoxelPiece>> result,
      Block block,
      int minX,
      int minY,
      int minZ,
      int maxX,
      int maxY,
      int maxZ,
      int baseSize,
      String material,
      String blockData) {
    java.util.ArrayList<VoxelPiece> pieces = new java.util.ArrayList<>();
    for (int x = minX; x < maxX; x += baseSize) {
      for (int y = minY; y < maxY; y += baseSize) {
        for (int z = minZ; z < maxZ; z += baseSize) {
          VoxelPiece piece = new VoxelPiece(x, y, z, baseSize, material, blockData);
          if (piece.isValid()) {
            pieces.add(piece);
          }
        }
      }
    }
    VoxelPieceManager.compact(pieces);
    if (!pieces.isEmpty()) {
      result.computeIfAbsent(block, ignored -> new java.util.ArrayList<>()).addAll(pieces);
    }
  }

  private Map<Block, List<VoxelPiece>> cappedPiecesForBox(
      Player player,
      WorldVoxel first,
      WorldVoxel second,
      String material,
      String blockData,
      int limit) {
    Map<Block, List<VoxelPiece>> result = new java.util.LinkedHashMap<>();
    int size = first.size;
    int minX = Math.min(first.worldX, second.worldX);
    int minY = Math.min(first.worldY, second.worldY);
    int minZ = Math.min(first.worldZ, second.worldZ);
    int maxX = Math.max(first.worldX, second.worldX);
    int maxY = Math.max(first.worldY, second.worldY);
    int maxZ = Math.max(first.worldZ, second.worldZ);
    int count = 0;

    for (int x = minX; x <= maxX && count < limit; x += size) {
      for (int y = minY; y <= maxY && count < limit; y += size) {
        for (int z = minZ; z <= maxZ && count < limit; z += size) {
          addWorldPiece(result, first.block.getWorld(), x, y, z, size, material, blockData);
          count++;
        }
      }
    }
    return result;
  }

  private Map<Block, List<VoxelPiece>> piecesForSet(
      Player player, WorldVoxel first, WorldVoxel second) {
    Map<Block, List<VoxelPiece>> result = new java.util.LinkedHashMap<>();
    int size = first.size;
    String material = modeToggleListener.getPlayerSelectedBlock(player);
    String blockData = selectedBlockDataFor(player, material, null);
    boolean line = modeToggleListener.isSetLine(player);

    int minX = Math.min(first.worldX, second.worldX);
    int minY = Math.min(first.worldY, second.worldY);
    int minZ = Math.min(first.worldZ, second.worldZ);
    int maxX = Math.max(first.worldX, second.worldX);
    int maxY = Math.max(first.worldY, second.worldY);
    int maxZ = Math.max(first.worldZ, second.worldZ);

    if (line) {
      int steps =
          Math.max(
                  Math.max(
                      Math.abs(second.worldX - first.worldX),
                      Math.abs(second.worldY - first.worldY)),
                  Math.abs(second.worldZ - first.worldZ))
              / size;
      steps = Math.max(0, steps);
      for (int i = 0; i <= steps; i++) {
        double t = steps == 0 ? 0.0 : (double) i / steps;
        int x =
            alignWorld((int) Math.round(first.worldX + (second.worldX - first.worldX) * t), size);
        int y =
            alignWorld((int) Math.round(first.worldY + (second.worldY - first.worldY) * t), size);
        int z =
            alignWorld((int) Math.round(first.worldZ + (second.worldZ - first.worldZ) * t), size);
        addWorldPiece(result, first.block.getWorld(), x, y, z, size, material, blockData);
      }
      return result;
    }

    for (int x = minX; x <= maxX; x += size) {
      for (int y = minY; y <= maxY; y += size) {
        for (int z = minZ; z <= maxZ; z += size) {
          addWorldPiece(result, first.block.getWorld(), x, y, z, size, material, blockData);
        }
      }
    }
    return result;
  }

  private Map<Block, List<VoxelPiece>> cappedPiecesForSet(
      Player player, WorldVoxel first, WorldVoxel second, int limit) {
    Map<Block, List<VoxelPiece>> result = new java.util.LinkedHashMap<>();
    int size = first.size;
    String material = modeToggleListener.getPlayerSelectedBlock(player);
    String blockData = selectedBlockDataFor(player, material, null);
    boolean line = modeToggleListener.isSetLine(player);

    int minX = Math.min(first.worldX, second.worldX);
    int minY = Math.min(first.worldY, second.worldY);
    int minZ = Math.min(first.worldZ, second.worldZ);
    int maxX = Math.max(first.worldX, second.worldX);
    int maxY = Math.max(first.worldY, second.worldY);
    int maxZ = Math.max(first.worldZ, second.worldZ);
    int count = 0;

    if (line) {
      int steps = lineVoxelCount(first, second) - 1;
      for (int i = 0; i <= steps && count < limit; i++) {
        double t = steps == 0 ? 0.0 : (double) i / steps;
        int x =
            alignWorld((int) Math.round(first.worldX + (second.worldX - first.worldX) * t), size);
        int y =
            alignWorld((int) Math.round(first.worldY + (second.worldY - first.worldY) * t), size);
        int z =
            alignWorld((int) Math.round(first.worldZ + (second.worldZ - first.worldZ) * t), size);
        addWorldPiece(result, first.block.getWorld(), x, y, z, size, material, blockData);
        count++;
      }
      return result;
    }

    for (int x = minX; x <= maxX && count < limit; x += size) {
      for (int y = minY; y <= maxY && count < limit; y += size) {
        for (int z = minZ; z <= maxZ && count < limit; z += size) {
          addWorldPiece(result, first.block.getWorld(), x, y, z, size, material, blockData);
          count++;
        }
      }
    }
    return result;
  }

  private Map<Block, List<VoxelPiece>> outlinePieces(Map<Block, List<VoxelPiece>> piecesByBlock) {
    int count = piecesByBlock.values().stream().mapToInt(List::size).sum();
    if (count < 48) {
      return piecesByBlock;
    }

    java.util.Set<String> occupied = new java.util.HashSet<>();
    for (Map.Entry<Block, List<VoxelPiece>> entry : piecesByBlock.entrySet()) {
      Block block = entry.getKey();
      for (VoxelPiece piece : entry.getValue()) {
        occupied.add(worldPieceKey(block, piece.x, piece.y, piece.z, piece.size));
      }
    }

    Map<Block, List<VoxelPiece>> outline = new java.util.LinkedHashMap<>();
    int[][] directions = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    for (Map.Entry<Block, List<VoxelPiece>> entry : piecesByBlock.entrySet()) {
      Block block = entry.getKey();
      for (VoxelPiece piece : entry.getValue()) {
        boolean exposed = false;
        for (int[] direction : directions) {
          int wx = block.getX() * 16 + piece.x + direction[0] * piece.size;
          int wy = block.getY() * 16 + piece.y + direction[1] * piece.size;
          int wz = block.getZ() * 16 + piece.z + direction[2] * piece.size;
          if (!occupied.contains(wx + ":" + wy + ":" + wz + ":" + piece.size)) {
            exposed = true;
            break;
          }
        }
        if (exposed) {
          outline.computeIfAbsent(block, ignored -> new java.util.ArrayList<>()).add(piece);
        }
      }
    }
    return outline;
  }

  private Map<Block, List<VoxelPiece>> previewOutline(Map<Block, List<VoxelPiece>> piecesByBlock) {
    int count = piecesByBlock.values().stream().mapToInt(List::size).sum();
    if (count < 32) {
      return piecesByBlock;
    }

    java.util.Set<String> occupied = new java.util.HashSet<>();
    for (Map.Entry<Block, List<VoxelPiece>> entry : piecesByBlock.entrySet()) {
      Block block = entry.getKey();
      for (VoxelPiece piece : entry.getValue()) {
        occupied.add(worldPieceKey(block, piece.x, piece.y, piece.z, piece.size));
      }
    }

    Map<Block, List<VoxelPiece>> outline = new java.util.LinkedHashMap<>();
    int[][] directions = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    for (Map.Entry<Block, List<VoxelPiece>> entry : piecesByBlock.entrySet()) {
      Block block = entry.getKey();
      for (VoxelPiece piece : entry.getValue()) {
        boolean exposedX = false;
        boolean exposedY = false;
        boolean exposedZ = false;
        for (int[] direction : directions) {
          int wx = block.getX() * 16 + piece.x + direction[0] * piece.size;
          int wy = block.getY() * 16 + piece.y + direction[1] * piece.size;
          int wz = block.getZ() * 16 + piece.z + direction[2] * piece.size;
          if (!occupied.contains(wx + ":" + wy + ":" + wz + ":" + piece.size)) {
            if (direction[0] != 0) {
              exposedX = true;
            } else if (direction[1] != 0) {
              exposedY = true;
            } else {
              exposedZ = true;
            }
          }
        }
        int exposedAxes = (exposedX ? 1 : 0) + (exposedY ? 1 : 0) + (exposedZ ? 1 : 0);
        if (exposedAxes >= 2) {
          outline.computeIfAbsent(block, ignored -> new java.util.ArrayList<>()).add(piece);
        }
      }
    }
    return outline.isEmpty() ? outlinePieces(piecesByBlock) : outline;
  }

  private String worldPieceKey(Block block, int x, int y, int z, int size) {
    return (block.getX() * 16 + x)
        + ":"
        + (block.getY() * 16 + y)
        + ":"
        + (block.getZ() * 16 + z)
        + ":"
        + size;
  }

  private void addWorldPiece(
      Map<Block, List<VoxelPiece>> result,
      org.bukkit.World world,
      int worldX,
      int worldY,
      int worldZ,
      int size,
      String material,
      String blockData) {
    WorldVoxel voxel = worldVoxelAt(world, worldX, worldY, worldZ, size);
    VoxelPiece piece =
        new VoxelPiece(voxel.localX, voxel.localY, voxel.localZ, size, material, blockData);
    if (piece.isValid()) {
      result.computeIfAbsent(voxel.block, ignored -> new java.util.ArrayList<>()).add(piece);
    }
  }

  private void applySetOld(
      Player player, Block block, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    DataManager dataManager = plugin.getDataManager();
    if (block.getType() == Material.AIR) {
      block.setType(Material.BARRIER);
    }
    if (!dataManager.hasCarvedData(block)) {
      dataManager.setVoxelPieces(block, new java.util.ArrayList<>());
    }

    int size =
        VoxelPieceManager.getPieceSizeForScale(modeToggleListener.getPlayerVoxelScale(player));
    String material = modeToggleListener.getPlayerSelectedBlock(player);
    List<VoxelPiece> pieces = dataManager.getVoxelPieces(block);
    List<VoxelPiece> before = plugin.getVoxelEditHistory().snapshot(block);
    boolean line = modeToggleListener.isSetLine(player);
    String blockData = selectedBlockDataFor(player, material, null);
    int placed = 0;
    for (int x = minX; x < maxX; x += size) {
      for (int y = minY; y < maxY; y += size) {
        for (int z = minZ; z < maxZ; z += size) {
          if (line && x != minX && y != minY && z != minZ) {
            continue;
          }
          VoxelPiece piece = new VoxelPiece(x, y, z, size, material, blockData);
          if (VoxelPieceManager.place(pieces, piece)) {
            placed++;
          }
        }
      }
    }
    VoxelPieceManager.compact(pieces);
    dataManager.setVoxelPieces(block, pieces);
    plugin.getFallingBlockManager().updateBlockDisplay(block, null);
    CollisionBlockManager.updateCollisionBlock(block, pieces, dataManager.isBlockLocked(block));
    plugin
        .getVoxelEditHistory()
        .remember(player, block, before, plugin.getVoxelEditHistory().snapshot(block));
    modeToggleListener.setStatus(player, "Set placed " + placed);
  }

  private void rotateVoxelSpace(Player player, Block block) {
    if (!canEditBlock(player, block)) {
      return;
    }
    DataManager dataManager = plugin.getDataManager();
    List<VoxelPiece> pieces = dataManager.getVoxelPieces(block);
    if (pieces.isEmpty()) {
      return;
    }

    List<VoxelPiece> before = plugin.getVoxelEditHistory().snapshot(block);
    String axis = modeToggleListener.getRotateAxis(player);
    double radians = Math.toRadians(modeToggleListener.getRotateAngle(player));
    double sin = Math.sin(radians), cos = Math.cos(radians);
    double scale = modeToggleListener.getRotateScale(player);
    String transformGroup = java.util.UUID.randomUUID().toString();
    for (VoxelPiece piece : pieces) {
      double anchorX = (piece.x + piece.size / 2.0) / 16.0;
      double anchorY = (piece.y + piece.size / 2.0) / 16.0;
      double anchorZ = (piece.z + piece.size / 2.0) / 16.0;
      double dx = anchorX + piece.offsetX - 0.5,
          dy = anchorY + piece.offsetY - 0.5,
          dz = anchorZ + piece.offsetZ - 0.5;
      double rx = dx, ry = dy, rz = dz;
      if (axis.equals("x")) {
        ry = dy * cos - dz * sin;
        rz = dy * sin + dz * cos;
        piece.rotationX += Math.toDegrees(radians);
      } else if (axis.equals("z")) {
        rx = dx * cos - dy * sin;
        ry = dx * sin + dy * cos;
        piece.rotationZ += Math.toDegrees(radians);
      } else {
        rx = dx * cos - dz * sin;
        rz = dx * sin + dz * cos;
        piece.rotationY += Math.toDegrees(radians);
      }
      piece.offsetX = 0.5 + rx * scale - anchorX;
      piece.offsetY = 0.5 + ry * scale - anchorY;
      piece.offsetZ = 0.5 + rz * scale - anchorZ;
      piece.visualScale *= scale;
      piece.transformGroup = transformGroup;
    }
    dataManager.setVoxelPieces(block, pieces);
    plugin.getFallingBlockManager().updateBlockDisplay(block, null);
    CollisionBlockManager.updateCollisionBlock(block, pieces, dataManager.isBlockLocked(block));
    plugin
        .getVoxelEditHistory()
        .remember(player, block, before, plugin.getVoxelEditHistory().snapshot(block));
    modeToggleListener.setStatus(
        player, "Rotated " + modeToggleListener.getRotateAngle(player) + "° " + axis.toUpperCase());
  }

  private WorldVoxel centerTarget(VoxelManager.RaycastResult rayResult, int scale) {
    int size = VoxelPieceManager.getPieceSizeForScale(scale);
    int[] coords =
        VoxelManager.getGridAlignedCoords(rayResult.hitX, rayResult.hitY, rayResult.hitZ, scale);
    int worldX = rayResult.block.getX() * 16 + coords[0];
    int worldY = rayResult.block.getY() * 16 + coords[1];
    int worldZ = rayResult.block.getZ() * 16 + coords[2];
    return worldVoxelAt(rayResult.block.getWorld(), worldX, worldY, worldZ, size);
  }

  private WorldVoxel buildTarget(VoxelManager.RaycastResult rayResult, int scale) {
    PlacementTarget target = placementTarget(rayResult, scale);
    if (target == null) {
      return centerTarget(rayResult, scale);
    }
    return worldVoxelAt(
        target.block.getWorld(),
        target.block.getX() * 16 + target.coords[0],
        target.block.getY() * 16 + target.coords[1],
        target.block.getZ() * 16 + target.coords[2],
        target.size);
  }

  private WorldVoxel worldVoxelOnSurface(Player player, Block block, BlockFace face, int scale) {
    int size = VoxelPieceManager.getPieceSizeForScale(scale);
    int[] coords = surfaceCoordsFromHit(player, block, block, face, size, scale);
    if (coords == null) {
      return null;
    }
    int worldX = block.getX() * 16 + coords[0];
    int worldY = block.getY() * 16 + coords[1];
    int worldZ = block.getZ() * 16 + coords[2];
    return worldVoxelAt(block.getWorld(), worldX, worldY, worldZ, size);
  }

  private WorldVoxel worldVoxelAdjacentSurface(
      Player player, Block block, BlockFace face, int scale) {
    if (face == null || face == BlockFace.SELF) {
      return null;
    }
    int size = VoxelPieceManager.getPieceSizeForScale(scale);
    Block targetBlock = block.getRelative(face);
    int[] coords = surfaceCoordsFromHit(player, block, targetBlock, face, size, scale);
    if (coords == null) {
      return null;
    }
    int worldX = targetBlock.getX() * 16 + coords[0];
    int worldY = targetBlock.getY() * 16 + coords[1];
    int worldZ = targetBlock.getZ() * 16 + coords[2];
    return worldVoxelAt(targetBlock.getWorld(), worldX, worldY, worldZ, size);
  }

  private WorldVoxel worldVoxelAt(
      org.bukkit.World world, int worldX, int worldY, int worldZ, int size) {
    int blockX = Math.floorDiv(worldX, 16);
    int blockY = Math.floorDiv(worldY, 16);
    int blockZ = Math.floorDiv(worldZ, 16);
    int localX = Math.floorMod(worldX, 16);
    int localY = Math.floorMod(worldY, 16);
    int localZ = Math.floorMod(worldZ, 16);
    return new WorldVoxel(
        world.getBlockAt(blockX, blockY, blockZ),
        localX,
        localY,
        localZ,
        worldX,
        worldY,
        worldZ,
        size);
  }

  private int alignWorld(int value, int size) {
    return Math.floorDiv(value, size) * size;
  }

  private boolean canEditBlock(Player player, Block block) {
    if (plugin.getVoxelProtectionManager().canEdit(player, block)) {
      plugin.getVoxelProtectionManager().claimIfNeeded(player, block);
      return true;
    }
    modeToggleListener.setStatus(player, "Protected voxel");
    return false;
  }

  private boolean isReplaceableVoxelHost(Block block) {
    if (block.getType() == Material.AIR) {
      return true;
    }
    if (block.getType() == Material.BARRIER) {
      return false;
    }
    return block.isPassable() && !plugin.getDataManager().hasCarvedData(block);
  }

  private boolean ensureVoxelHost(Block block, DataManager dataManager) {
    if (dataManager.hasCarvedData(block)) {
      if (block.getType() != Material.BARRIER) block.setType(Material.BARRIER, false);
      return true;
    }
    Material original = block.getType();
    List<VoxelPiece> base = new java.util.ArrayList<>();
    if (original != Material.AIR && original != Material.BARRIER) {
      if (!original.isBlock()) return false;
      base.add(new VoxelPiece(0, 0, 0, 16, original.name(), block.getBlockData().getAsString()));
    }
    block.setType(Material.BARRIER, false);
    dataManager.setVoxelPieces(block, base);
    return true;
  }

  private java.util.ArrayList<VoxelPiece> collectWorldSelection(
      WorldVoxel first, WorldVoxel second) {
    java.util.ArrayList<VoxelPiece> selected = new java.util.ArrayList<>();
    int minX = Math.min(first.worldX, second.worldX);
    int minY = Math.min(first.worldY, second.worldY);
    int minZ = Math.min(first.worldZ, second.worldZ);
    int maxX = Math.max(first.worldX, second.worldX) + second.size;
    int maxY = Math.max(first.worldY, second.worldY) + second.size;
    int maxZ = Math.max(first.worldZ, second.worldZ) + second.size;

    org.bukkit.World world = first.block.getWorld();
    int minBlockX = Math.floorDiv(minX, 16);
    int minBlockY = Math.floorDiv(minY, 16);
    int minBlockZ = Math.floorDiv(minZ, 16);
    int maxBlockX = Math.floorDiv(maxX - 1, 16);
    int maxBlockY = Math.floorDiv(maxY - 1, 16);
    int maxBlockZ = Math.floorDiv(maxZ - 1, 16);

    for (int bx = minBlockX; bx <= maxBlockX; bx++) {
      for (int by = minBlockY; by <= maxBlockY; by++) {
        for (int bz = minBlockZ; bz <= maxBlockZ; bz++) {
          Block block = world.getBlockAt(bx, by, bz);
          for (VoxelPiece piece : plugin.getDataManager().getVoxelPieces(block)) {
            int wx = bx * 16 + piece.x;
            int wy = by * 16 + piece.y;
            int wz = bz * 16 + piece.z;
            if (worldPieceIntersects(wx, wy, wz, piece.size, minX, minY, minZ, maxX, maxY, maxZ)) {
              VoxelPiece selectedPiece =
                  new VoxelPiece(wx, wy, wz, piece.size, piece.material, piece.blockData);
              selected.add(selectedPiece);
            }
          }
        }
      }
    }
    return selected;
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

  private SelectionPoint selectionPoint(VoxelManager.RaycastResult rayResult) {
    if (rayResult.piece != null) {
      VoxelPiece piece = rayResult.piece;
      return new SelectionPoint(
          rayResult.block,
          piece.x,
          piece.y,
          piece.z,
          piece.x + piece.size,
          piece.y + piece.size,
          piece.z + piece.size);
    }

    int[] point = rayResultToVoxelCoords(rayResult);
    return new SelectionPoint(
        rayResult.block, point[0], point[1], point[2], point[0] + 1, point[1] + 1, point[2] + 1);
  }

  private static class SolidHit {
    private final Block block;
    private final BlockFace face;

    SolidHit(Block block, BlockFace face) {
      this.block = block;
      this.face = face;
    }
  }

  private static class WorldVoxel {
    private final Block block;
    private final int localX;
    private final int localY;
    private final int localZ;
    private final int worldX;
    private final int worldY;
    private final int worldZ;
    private final int size;

    WorldVoxel(
        Block block,
        int localX,
        int localY,
        int localZ,
        int worldX,
        int worldY,
        int worldZ,
        int size) {
      this.block = block;
      this.localX = localX;
      this.localY = localY;
      this.localZ = localZ;
      this.worldX = worldX;
      this.worldY = worldY;
      this.worldZ = worldZ;
      this.size = size;
    }
  }

  private static class RemovePreview {
    private final int x;
    private final int y;
    private final int z;
    private final int size;

    RemovePreview(int x, int y, int z, int size) {
      this.x = x;
      this.y = y;
      this.z = z;
      this.size = size;
    }
  }

  private static class PasteTarget {
    private final String worldName;
    private final int blockX;
    private final int blockY;
    private final int blockZ;
    private final int x;
    private final int y;
    private final int z;

    PasteTarget(Block block, int x, int y, int z) {
      this.worldName = block.getWorld().getName();
      this.blockX = block.getX();
      this.blockY = block.getY();
      this.blockZ = block.getZ();
      this.x = x;
      this.y = y;
      this.z = z;
    }

    boolean matches(PasteTarget other) {
      return worldName.equals(other.worldName)
          && blockX == other.blockX
          && blockY == other.blockY
          && blockZ == other.blockZ
          && x == other.x
          && y == other.y
          && z == other.z;
    }
  }

  private static class PlacementTarget {
    private final Block block;
    private final int[] coords;
    private final int size;

    PlacementTarget(Block block, int[] coords, int size) {
      this.block = block;
      this.coords = coords;
      this.size = size;
    }
  }

  private static class SelectionPoint {
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

    SelectionPoint(Block block, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
      this.worldName = block.getWorld().getName();
      this.blockX = block.getX();
      this.blockY = block.getY();
      this.blockZ = block.getZ();
      this.minX = minX;
      this.minY = minY;
      this.minZ = minZ;
      this.maxX = maxX;
      this.maxY = maxY;
      this.maxZ = maxZ;
    }

    boolean sameBlock(SelectionPoint other) {
      return worldName.equals(other.worldName)
          && blockX == other.blockX
          && blockY == other.blockY
          && blockZ == other.blockZ;
    }

    Block block() {
      org.bukkit.World world = Bukkit.getWorld(worldName);
      return world == null ? null : world.getBlockAt(blockX, blockY, blockZ);
    }
  }

  private static class BrushSettings {
    private final int radius;
    private final boolean round;

    BrushSettings(int radius, boolean round) {
      this.radius = radius;
      this.round = round;
    }
  }
}
