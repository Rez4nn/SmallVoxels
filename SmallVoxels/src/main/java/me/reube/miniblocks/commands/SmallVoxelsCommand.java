package me.reube.SmallVoxels.commands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.detail.BrushType;
import me.reube.SmallVoxels.detail.DetailPreset;
import me.reube.SmallVoxels.detail.DetailToolMode;
import me.reube.SmallVoxels.managers.CollisionBlockManager;
import me.reube.SmallVoxels.managers.CollisionStandManager;
import me.reube.SmallVoxels.managers.ToolMode;
import me.reube.SmallVoxels.managers.VoxelPiece;
import me.reube.SmallVoxels.managers.VoxelPieceManager;
import me.reube.SmallVoxels.managers.VoxelSelectionManager;
import me.reube.SmallVoxels.managers.animation.AnimatedVoxelObject;
import me.reube.SmallVoxels.managers.animation.AnimationKeyframe;
import me.reube.SmallVoxels.ui.AnimationEditorGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class SmallVoxelsCommand implements CommandExecutor, TabCompleter {

  private final SmallVoxels plugin;
  private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
  private final Map<UUID, PendingDelete> pendingDeletes = new HashMap<>();
  private final Map<UUID, PendingAnimationDelete> pendingAnimationDeletes = new HashMap<>();

  public SmallVoxelsCommand(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(
          Component.text("Only players can use this command.").color(NamedTextColor.RED));
      return true;
    }

    if (args.length == 0) {
      plugin.getDetailToolListener().open(player);
      return true;
    }

    return switch (args[0].toLowerCase(Locale.ROOT)) {
      case "refresh" -> refresh(player);
      case "save" -> save(player, args);
      case "load" -> load(player, args);
      case "list" -> list(player);
      case "rotate" -> rotate(player);
      case "flip" -> flip(player, args);
      case "delete" -> delete(player, args);
      case "playertrust" -> playerTrust(player, args);
      case "playertrustregion" -> playerTrustRegion(player, args);
      case "playerremove" -> playerRemove(player, args);
      case "player" -> playerList(player, args);
      case "clickcommand", "blockcommand" -> clickCommand(player, args);
      case "bitcommand", "piececommand" -> pieceCommand(player, args);
      case "animation", "anim" -> animation(player, args);
      case "brush" -> detailBrush(player, args);
      case "trim" -> detailTrim(player, args);
      case "damage" -> detailMode(player, DetailToolMode.DAMAGE, "Damage");
      case "overgrow", "overgrowth" -> detailMode(player, DetailToolMode.OVERGROW, "Overgrowth");
      case "stamp" -> detailStamp(player, args);
      case "detail" -> detailRegion(player, args);
      case "intensity" -> detailIntensity(player, args);
      case "tools", "gui" -> {
        plugin.getDetailToolListener().open(player);
        yield true;
      }
      default -> {
        sendHelp(player, label);
        yield true;
      }
    };
  }

  private boolean detailBrush(Player player, String[] args) {
    if (args.length < 2) {
      plugin.getDetailToolListener().open(player);
      return true;
    }
    BrushType type = BrushType.parse(args[1]);
    if (type == null) {
      player.sendMessage(
          Component.text(
                  "Unknown brush. Use moss, cracks, dirt, snow, ash, runes, wooden_beam, or"
                      + " stone_detail.")
              .color(NamedTextColor.RED));
      return true;
    }
    var s = plugin.getDetailManager().settings(player);
    s.mode = DetailToolMode.BRUSH;
    s.brush = type;
    plugin.getModeToggleListener().setToolMode(player, ToolMode.DETAIL);
    player.sendMessage(
        Component.text(
                "Detail brush: "
                    + type.name().toLowerCase(Locale.ROOT)
                    + ". Right-click a block face with the Voxel Axe.")
            .color(NamedTextColor.GREEN));
    return true;
  }

  private boolean detailTrim(Player player, String[] args) {
    var s = plugin.getDetailManager().settings(player);
    s.mode = DetailToolMode.TRIM;
    plugin.getModeToggleListener().setToolMode(player, ToolMode.DETAIL);
    if (args.length >= 2) {
      Material material = Material.matchMaterial(args[1]);
      if (material == null || !material.isBlock()) {
        player.sendMessage(
            Component.text("That is not a block material.").color(NamedTextColor.RED));
        return true;
      }
      s.material = material;
    }
    player.sendMessage(
        Component.text(
                "Trim tool: "
                    + s.material.name().toLowerCase(Locale.ROOT)
                    + ". Click near an edge.")
            .color(NamedTextColor.GREEN));
    return true;
  }

  private boolean detailMode(Player player, DetailToolMode mode, String label) {
    plugin.getDetailManager().settings(player).mode = mode;
    plugin.getModeToggleListener().setToolMode(player, ToolMode.DETAIL);
    player.sendMessage(
        Component.text(label + " tool selected. Right-click a block face with the Voxel Axe.")
            .color(NamedTextColor.GREEN));
    return true;
  }

  private boolean detailStamp(Player player, String[] args) {
    if (args.length < 2) {
      plugin.getDetailToolListener().open(player);
      return true;
    }
    DetailPreset preset = DetailPreset.parse(args[1]);
    if (preset == null) {
      player.sendMessage(Component.text("Unknown built-in preset.").color(NamedTextColor.RED));
      return true;
    }
    var s = plugin.getDetailManager().settings(player);
    s.mode = DetailToolMode.STAMP;
    s.preset = preset;
    plugin.getModeToggleListener().setToolMode(player, ToolMode.DETAIL);
    player.sendMessage(
        Component.text("Stamp selected: " + preset.name().toLowerCase(Locale.ROOT))
            .color(NamedTextColor.GREEN));
    return true;
  }

  private boolean detailRegion(Player player, String[] args) {
    if (args.length < 2) {
      plugin.getDetailToolListener().open(player);
      return true;
    }
    String style = args[1].toLowerCase(Locale.ROOT);
    if (!style.equals("ruined") && !style.equals("overgrown")) {
      player.sendMessage(
          Component.text("MVP region styles: ruined, overgrown.").color(NamedTextColor.YELLOW));
      return true;
    }
    int count = plugin.getDetailManager().detailRegion(player, style);
    player.sendMessage(
        Component.text(
                count == -1
                    ? "Make a Copy selection first."
                    : count == -2
                        ? "Selection exceeds the safe 4096-block limit."
                        : "Detailed " + count + " exposed faces.")
            .color(count >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
    return true;
  }

  private boolean detailIntensity(Player player, String[] args) {
    if (args.length < 2) {
      plugin.getDetailToolListener().open(player);
      return true;
    }
    try {
      double value = Double.parseDouble(args[1]);
      if (value < 0 || value > 1) throw new NumberFormatException();
      plugin.getDetailManager().settings(player).intensity = value;
      player.sendMessage(Component.text("Detail intensity: " + value).color(NamedTextColor.GREEN));
    } catch (NumberFormatException ex) {
      player.sendMessage(Component.text("Usage: /vox intensity <0-1>").color(NamedTextColor.RED));
    }
    return true;
  }

  private boolean refresh(Player player) {
    if (!player.hasPermission("smallvoxels.admin")) {
      player.sendMessage(
          Component.text("You do not have permission to refresh voxel displays.")
              .color(NamedTextColor.RED));
      return true;
    }
    int count = plugin.refreshVoxelDisplays();
    player.sendMessage(
        Component.text("Refreshed " + count + " voxel block displays.")
            .color(NamedTextColor.GREEN));
    return true;
  }

  private boolean delete(Player player, String[] args) {
    if (!player.hasPermission("smallvoxels.admin")) {
      player.sendMessage(
          Component.text("You do not have permission to delete world voxels.")
              .color(NamedTextColor.RED));
      return true;
    }
    if (args.length >= 2 && "confirm".equalsIgnoreCase(args[1])) {
      return confirmDelete(player);
    }
    if (args.length < 2) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels delete <2x|4x|8x|16x|all> [--player <name>|--mine]")
              .color(NamedTextColor.YELLOW));
      return true;
    }

    Integer pieceSize = pieceSizeFromFlag(args[1]);
    if (pieceSize == null && !"all".equalsIgnoreCase(args[1])) {
      player.sendMessage(
          Component.text("Use 2x, 4x, 8x, 16x, or all.").color(NamedTextColor.YELLOW));
      return true;
    }

    OfflinePlayer owner = ownerFilter(player, args);
    if (owner == null && hasPlayerFlag(args)) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels delete " + args[1] + " --player <name>")
              .color(NamedTextColor.YELLOW));
      return true;
    }

    DeleteScan scan = scanDelete(player, pieceSize, owner);
    if (scan.pieceCount == 0) {
      player.sendMessage(
          Component.text("No matching miniblocks found in " + player.getWorld().getName() + ".")
              .color(NamedTextColor.YELLOW));
      return true;
    }

    PendingDelete pending =
        new PendingDelete(
            player.getWorld().getName(),
            pieceSize,
            owner == null ? null : owner.getUniqueId(),
            scan.pieceCount,
            scan.blockCount,
            System.currentTimeMillis() + 30_000L);
    pendingDeletes.put(player.getUniqueId(), pending);

    String scope = owner == null ? "all players" : displayName(owner);
    String scale = pieceSize == null ? "all scales" : args[1].toLowerCase(Locale.ROOT);
    player.sendMessage(
        Component.text(
                "This will remove "
                    + scan.pieceCount
                    + " miniblocks across "
                    + scan.blockCount
                    + " host blocks in "
                    + pending.worldName
                    + " ("
                    + scale
                    + ", "
                    + scope
                    + ").")
            .color(NamedTextColor.YELLOW));
    player.sendMessage(
        Component.text("Run /smallvoxels delete confirm within 30 seconds to continue.")
            .color(NamedTextColor.RED));
    return true;
  }

  private boolean confirmDelete(Player player) {
    PendingDelete pending = pendingDeletes.get(player.getUniqueId());
    if (pending == null || pending.expiresAt < System.currentTimeMillis()) {
      pendingDeletes.remove(player.getUniqueId());
      player.sendMessage(
          Component.text("No pending delete. Run /smallvoxels delete <2x|4x|8x|16x|all> first.")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    if (!pending.worldName.equals(player.getWorld().getName())) {
      pendingDeletes.remove(player.getUniqueId());
      player.sendMessage(
          Component.text(
                  "Pending delete was for "
                      + pending.worldName
                      + ". Stand in that world and run it again.")
              .color(NamedTextColor.RED));
      return true;
    }

    DeleteScan scan =
        scanDelete(
            player,
            pending.pieceSize,
            pending.ownerId == null ? null : org.bukkit.Bukkit.getOfflinePlayer(pending.ownerId));
    int removedPieces = 0;
    int touchedBlocks = 0;

    for (Block block : scan.blocks) {
      List<VoxelPiece> pieces = plugin.getDataManager().getVoxelPieces(block);
      List<VoxelPiece> kept = new ArrayList<>();
      int removedFromBlock = 0;
      for (VoxelPiece piece : pieces) {
        if (pending.pieceSize == null || piece.size == pending.pieceSize) {
          removedFromBlock++;
        } else {
          kept.add(piece);
        }
      }
      if (removedFromBlock == 0) {
        continue;
      }

      removedPieces += removedFromBlock;
      touchedBlocks++;
      plugin.getFallingBlockManager().removeBlockDisplay(block);

      if (kept.isEmpty()) {
        String standUUID = plugin.getDataManager().getCollisionStandUUID(block);
        if (standUUID != null) {
          CollisionStandManager.removeCollisionStand(block, standUUID);
        }
        plugin.getDataManager().removeCarvedBlockAndMetadata(block);
        plugin.getVoxelProtectionManager().removeOwner(block);
        if (block.getType() == Material.BARRIER) {
          block.setType(Material.AIR);
        }
      } else {
        VoxelPieceManager.compact(kept);
        plugin.getDataManager().setVoxelPieces(block, kept);
        plugin.getFallingBlockManager().updateBlockDisplay(block, null);
        CollisionBlockManager.updateCollisionBlock(
            block, kept, plugin.getDataManager().isBlockLocked(block));
      }
    }

    plugin.getDataManager().saveAllData();
    pendingDeletes.remove(player.getUniqueId());
    player.sendMessage(
        Component.text(
                "Removed "
                    + removedPieces
                    + " miniblocks from "
                    + touchedBlocks
                    + " host blocks in "
                    + player.getWorld().getName()
                    + ".")
            .color(NamedTextColor.GREEN));
    return true;
  }

  private boolean save(Player player, String[] args) {
    if (!canUseSchematics(player)) {
      return true;
    }
    if (args.length < 2) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels save <name>").color(NamedTextColor.YELLOW));
      return true;
    }
    if (!plugin.getVoxelClipboard().hasCopy(player)
        && !plugin.getVoxelSelectionManager().hasLastCopySelection(player)) {
      player.sendMessage(
          Component.text("Copy a voxel selection first.").color(NamedTextColor.YELLOW));
      return true;
    }

    File file = schematicFile(player, args[1]);
    if (file == null) {
      player.sendMessage(
          Component.text("Use letters, numbers, dash, or underscore for the name.")
              .color(NamedTextColor.RED));
      return true;
    }

    VoxelSelectionManager.WorldSelection selection =
        plugin.getVoxelSelectionManager().lastCopySelection(player);
    if (selection != null) {
      return saveNativeSelection(player, file, selection, hasFlag(args, "--animations"));
    }

    try (var writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
      SchematicFile schematic = new SchematicFile();
      schematic.version = 1;
      schematic.pieces = plugin.getVoxelClipboard().get(player);
      gson.toJson(schematic, writer);
      player.sendMessage(
          Component.text("Saved " + schematic.pieces.size() + " voxels to " + file.getName())
              .color(NamedTextColor.GREEN));
    } catch (IOException ex) {
      player.sendMessage(
          Component.text("Could not save that schematic.").color(NamedTextColor.RED));
      plugin.getLogger().warning("Could not save schematic " + file + ": " + ex.getMessage());
    }
    return true;
  }

  private boolean load(Player player, String[] args) {
    if (!canUseSchematics(player)) {
      return true;
    }
    if (args.length < 2) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels load <name>").color(NamedTextColor.YELLOW));
      return true;
    }

    File file = schematicFile(player, args[1]);
    if (file == null || !file.exists()) {
      player.sendMessage(Component.text("That schematic was not found.").color(NamedTextColor.RED));
      return true;
    }

    try (var reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
      RegionSchematicFile region = gson.fromJson(reader, RegionSchematicFile.class);
      if (region != null && region.blocks != null && !region.blocks.isEmpty()) {
        return pasteNativeSelection(player, region);
      }
    } catch (IOException ex) {
      player.sendMessage(
          Component.text("Could not load that schematic.").color(NamedTextColor.RED));
      plugin.getLogger().warning("Could not load schematic " + file + ": " + ex.getMessage());
      return true;
    }

    try (var reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
      SchematicFile schematic = gson.fromJson(reader, SchematicFile.class);
      List<VoxelPiece> pieces =
          schematic == null || schematic.pieces == null ? List.of() : schematic.pieces;
      plugin.getVoxelClipboard().set(player, pieces);
      player.sendMessage(
          Component.text(
                  "Loaded " + plugin.getVoxelClipboard().count(player) + " voxels into your copy.")
              .color(NamedTextColor.GREEN));
    } catch (IOException ex) {
      player.sendMessage(
          Component.text("Could not load that schematic.").color(NamedTextColor.RED));
      plugin.getLogger().warning("Could not load schematic " + file + ": " + ex.getMessage());
    }
    return true;
  }

  private boolean saveNativeSelection(
      Player player,
      File file,
      VoxelSelectionManager.WorldSelection selection,
      boolean includeAnimations) {
    org.bukkit.World world = org.bukkit.Bukkit.getWorld(selection.worldName());
    if (world == null) {
      player.sendMessage(
          Component.text("The copied selection world is no longer loaded.")
              .color(NamedTextColor.RED));
      return true;
    }
    int minBlockX = Math.floorDiv(selection.minX(), 16);
    int minBlockY = Math.floorDiv(selection.minY(), 16);
    int minBlockZ = Math.floorDiv(selection.minZ(), 16);
    int maxBlockX = Math.floorDiv(selection.maxX() - 1, 16);
    int maxBlockY = Math.floorDiv(selection.maxY() - 1, 16);
    int maxBlockZ = Math.floorDiv(selection.maxZ() - 1, 16);

    RegionSchematicFile schematic = new RegionSchematicFile();
    schematic.version = 2;
    schematic.minBlockX = minBlockX;
    schematic.minBlockY = minBlockY;
    schematic.minBlockZ = minBlockZ;
    for (int x = minBlockX; x <= maxBlockX; x++) {
      for (int y = minBlockY; y <= maxBlockY; y++) {
        for (int z = minBlockZ; z <= maxBlockZ; z++) {
          Block block = world.getBlockAt(x, y, z);
          List<VoxelPiece> pieces = plugin.getDataManager().getVoxelPieces(block);
          if (block.getType() == Material.AIR && pieces.isEmpty()) {
            continue;
          }
          SchematicBlock saved = new SchematicBlock();
          saved.dx = x - minBlockX;
          saved.dy = y - minBlockY;
          saved.dz = z - minBlockZ;
          saved.material = block.getType().name();
          saved.blockData = block.getBlockData().getAsString();
          saved.pieces = pieces;
          schematic.blocks.add(saved);
        }
      }
    }
    if (includeAnimations) {
      for (AnimatedVoxelObject object : plugin.getAnimatedObjectManager().storage().all()) {
        if (!selection.worldName().equals(object.world)) {
          continue;
        }
        int objectX = (int) Math.floor(object.originX);
        int objectY = (int) Math.floor(object.originY);
        int objectZ = (int) Math.floor(object.originZ);
        if (objectX >= minBlockX
            && objectX <= maxBlockX
            && objectY >= minBlockY
            && objectY <= maxBlockY
            && objectZ >= minBlockZ
            && objectZ <= maxBlockZ) {
          AnimatedVoxelObject copy = gson.fromJson(gson.toJson(object), AnimatedVoxelObject.class);
          copy.originX -= minBlockX;
          copy.originY -= minBlockY;
          copy.originZ -= minBlockZ;
          schematic.animations.add(copy);
        }
      }
    }

    try (var writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
      gson.toJson(schematic, writer);
      player.sendMessage(
          Component.text(
                  "Saved selection schematic with "
                      + schematic.blocks.size()
                      + " blocks"
                      + (schematic.animations.isEmpty()
                          ? ""
                          : " and " + schematic.animations.size() + " animation(s)")
                      + " to "
                      + file.getName())
              .color(NamedTextColor.GREEN));
    } catch (IOException ex) {
      player.sendMessage(
          Component.text("Could not save that schematic.").color(NamedTextColor.RED));
      plugin
          .getLogger()
          .warning("Could not save selection schematic " + file + ": " + ex.getMessage());
    }
    return true;
  }

  private boolean pasteNativeSelection(Player player, RegionSchematicFile schematic) {
    Block anchor = player.getLocation().getBlock();
    int pasted = 0;
    for (SchematicBlock saved : schematic.blocks) {
      Block target =
          player
              .getWorld()
              .getBlockAt(
                  anchor.getX() + saved.dx, anchor.getY() + saved.dy, anchor.getZ() + saved.dz);
      if (!plugin.getVoxelProtectionManager().canEdit(player, target)) {
        continue;
      }
      plugin.getVoxelProtectionManager().claimIfNeeded(player, target);
      List<VoxelPiece> pieces = saved.pieces == null ? List.of() : saved.pieces;
      if (pieces.isEmpty()) {
        target.setBlockData(
            org.bukkit.Bukkit.createBlockData(
                saved.blockData == null ? saved.material : saved.blockData),
            false);
        plugin.getDataManager().removeCarvedBlockAndMetadata(target);
        plugin.getFallingBlockManager().removeBlockDisplay(target);
      } else {
        List<VoxelPiece> merged = plugin.getDataManager().getVoxelPieces(target);
        if (!plugin.getDataManager().hasCarvedData(target)
            && target.getType() != Material.AIR
            && target.getType() != Material.BARRIER) {
          merged.add(
              new VoxelPiece(
                  0, 0, 0, 16, target.getType().name(), target.getBlockData().getAsString()));
        }
        for (VoxelPiece piece : pieces) merged.add(piece.copy());
        VoxelPieceManager.compact(merged);
        target.setType(Material.BARRIER, false);
        plugin.getDataManager().setVoxelPieces(target, merged);
        plugin.getFallingBlockManager().updateBlockDisplay(target, null);
        CollisionBlockManager.updateCollisionBlock(
            target, merged, plugin.getDataManager().isBlockLocked(target));
      }
      pasted++;
    }
    for (AnimatedVoxelObject saved :
        schematic.animations == null ? List.<AnimatedVoxelObject>of() : schematic.animations) {
      AnimatedVoxelObject object = gson.fromJson(gson.toJson(saved), AnimatedVoxelObject.class);
      object.id = uniqueAnimationId(object.id);
      object.world = player.getWorld().getName();
      object.originX = anchor.getX() + saved.originX;
      object.originY = anchor.getY() + saved.originY;
      object.originZ = anchor.getZ() + saved.originZ;
      plugin.getAnimatedObjectManager().storage().put(object);
      plugin.getAnimatedObjectManager().storage().save(object);
      plugin.getAnimatedObjectManager().show(object.id);
    }
    plugin.getDataManager().saveAllData();
    player.sendMessage(
        Component.text("Pasted " + pasted + " schematic blocks.").color(NamedTextColor.GREEN));
    return true;
  }

  private String uniqueAnimationId(String base) {
    String clean =
        base == null || base.isBlank()
            ? "schematic_animation"
            : base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    String candidate = clean;
    int index = 1;
    while (plugin.getAnimatedObjectManager().storage().get(candidate) != null) {
      candidate = clean + "_" + index++;
    }
    return candidate;
  }

  private boolean list(Player player) {
    if (!canUseSchematics(player)) {
      return true;
    }
    File folder = schematicFolder(player);
    String[] files = folder.list((dir, name) -> name.endsWith(".json"));
    if (files == null || files.length == 0) {
      player.sendMessage(
          Component.text("No SmallVoxels schematics saved yet.").color(NamedTextColor.YELLOW));
      return true;
    }

    Arrays.sort(files);
    List<String> names = new ArrayList<>();
    for (String file : files) {
      names.add(file.substring(0, file.length() - ".json".length()));
    }
    TextComponent.Builder message =
        Component.text().append(Component.text("Schematics: ").color(NamedTextColor.RED));
    for (int i = 0; i < names.size(); i++) {
      if (i > 0) {
        message.append(Component.text(", ").color(NamedTextColor.GREEN));
      }
      message.append(Component.text(names.get(i)).color(NamedTextColor.GREEN));
    }
    player.sendMessage(message.build());
    return true;
  }

  private boolean rotate(Player player) {
    if (!canUseSchematics(player)) {
      return true;
    }
    boolean rotated = plugin.getVoxelClipboard().rotateY(player);
    player.sendMessage(
        Component.text(rotated ? "Copy rotated." : "Copy something first.")
            .color(rotated ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
    return true;
  }

  private boolean flip(Player player, String[] args) {
    if (!canUseSchematics(player)) {
      return true;
    }
    if (args.length < 2) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels flip <x|y|z>").color(NamedTextColor.YELLOW));
      return true;
    }

    boolean flipped = plugin.getVoxelClipboard().flip(player, args[1]);
    player.sendMessage(
        Component.text(flipped ? "Copy flipped." : "Use x, y, or z after flip.")
            .color(flipped ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
    return true;
  }

  private boolean playerTrust(Player player, String[] args) {
    if (!canUseTrust(player)) {
      return true;
    }
    if (args.length < 2) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels playertrust <player>").color(NamedTextColor.YELLOW));
      return true;
    }
    if (!plugin.getVoxelProtectionManager().isWorldProtected(player.getWorld())) {
      player.sendMessage(
          Component.text("This world does not have protected voxels enabled.")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    OfflinePlayer trusted = BukkitPlayer(args[1]);
    plugin.getVoxelProtectionManager().trustWorld(player, trusted);
    player.sendMessage(
        Component.text("Trusted " + displayName(trusted) + " in this world.")
            .color(NamedTextColor.GREEN));
    return true;
  }

  private boolean playerTrustRegion(Player player, String[] args) {
    if (!canUseTrust(player)) {
      return true;
    }
    if (args.length < 2) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels playertrustregion <player>")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    if (!plugin.getVoxelProtectionManager().isWorldProtected(player.getWorld())) {
      player.sendMessage(
          Component.text("This world does not have protected voxels enabled.")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    OfflinePlayer trusted = BukkitPlayer(args[1]);
    plugin.getVoxelProtectionManager().startRegionTrust(player, trusted);
    player.sendMessage(
        Component.text("Click two corners for " + displayName(trusted) + "'s trust region.")
            .color(NamedTextColor.GREEN));
    return true;
  }

  private boolean playerRemove(Player player, String[] args) {
    if (!canUseTrust(player)) {
      return true;
    }
    if (args.length < 2) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels playerremove <player>").color(NamedTextColor.YELLOW));
      return true;
    }
    OfflinePlayer trusted = BukkitPlayer(args[1]);
    plugin.getVoxelProtectionManager().removeTrust(player, trusted);
    player.sendMessage(
        Component.text("Removed trust for " + displayName(trusted) + " in this world.")
            .color(NamedTextColor.GREEN));
    return true;
  }

  private boolean playerList(Player player, String[] args) {
    if (!canUseTrust(player)) {
      return true;
    }
    if (args.length < 2 || !"list".equalsIgnoreCase(args[1])) {
      sendHelp(player, "smallvoxels");
      return true;
    }
    List<String> names = plugin.getVoxelProtectionManager().trustedNames(player);
    if (names.isEmpty()) {
      player.sendMessage(Component.text("Trusted: none").color(NamedTextColor.YELLOW));
    } else {
      player.sendMessage(
          Component.text("Trusted: ")
              .color(NamedTextColor.RED)
              .append(Component.text(String.join(", ", names)).color(NamedTextColor.GREEN)));
    }
    return true;
  }

  private void sendHelp(Player player, String label) {
    player.sendMessage(Component.text("/" + label + " refresh").color(NamedTextColor.GRAY));
    player.sendMessage(
        Component.text("/" + label + " delete <2x|4x|8x|16x|all> [--player <name>|--mine]")
            .color(NamedTextColor.GRAY));
    player.sendMessage(
        Component.text("/" + label + " save <name> | load <name>").color(NamedTextColor.GRAY));
    player.sendMessage(
        Component.text("/" + label + " list | rotate | flip <x|y|z>").color(NamedTextColor.GRAY));
    player.sendMessage(
        Component.text("/" + label + " clickcommand set|clear ... | bitcommand set|clear ...")
            .color(NamedTextColor.GRAY));
    player.sendMessage(
        Component.text(
                "/"
                    + label
                    + " animation <axe|create|gui|select|list|delete|show|hide|play|stop|info>")
            .color(NamedTextColor.GRAY));
    player.sendMessage(
        Component.text(
                "/"
                    + label
                    + " playertrust <player> | playertrustregion <player> | playerremove <player> |"
                    + " player list")
            .color(NamedTextColor.GRAY));
  }

  private boolean clickCommand(Player player, String[] args) {
    if (!player.hasPermission("smallvoxels.command-bind")
        && !player.hasPermission("smallvoxels.admin")) {
      player.sendMessage(
          Component.text("You do not have permission to bind voxel click commands.")
              .color(NamedTextColor.RED));
      return true;
    }
    if (!plugin.getVoxelSelectionManager().hasLastCopySelection(player)) {
      player.sendMessage(
          Component.text("Make a voxel Copy selection with the normal axe first.")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    if (args.length < 2
        || (!"set".equalsIgnoreCase(args[1]) && !"clear".equalsIgnoreCase(args[1]))) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels clickcommand set <command...> | clear")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    if ("clear".equalsIgnoreCase(args[1])) {
      int cleared = plugin.getVoxelSelectionManager().clearClickCommandFromLastCopy(player);
      player.sendMessage(
          Component.text("Cleared click commands from " + cleared + " selected voxel blocks.")
              .color(NamedTextColor.GREEN));
      return true;
    }
    if (args.length < 3) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels clickcommand set <command...>")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    int bound =
        plugin.getVoxelSelectionManager().bindClickCommandToLastCopy(player, joinArgs(args, 2));
    player.sendMessage(
        Component.text("Bound click command to " + bound + " selected voxel blocks.")
            .color(NamedTextColor.GREEN));
    return true;
  }

  private boolean pieceCommand(Player player, String[] args) {
    if (!player.hasPermission("smallvoxels.command-bind")
        && !player.hasPermission("smallvoxels.admin")) {
      player.sendMessage(
          Component.text("You do not have permission to bind voxel click commands.")
              .color(NamedTextColor.RED));
      return true;
    }
    Block block = player.getTargetBlockExact(6);
    if (block == null || !plugin.getDataManager().hasCarvedData(block)) {
      player.sendMessage(
          Component.text("Look at a SmallVoxels block first.").color(NamedTextColor.YELLOW));
      return true;
    }
    if (args.length < 6
        || (!"set".equalsIgnoreCase(args[1]) && !"clear".equalsIgnoreCase(args[1]))) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels bitcommand set|clear <x> <y> <z> <size> [command...]")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    Integer x = parseInt(args[2]);
    Integer y = parseInt(args[3]);
    Integer z = parseInt(args[4]);
    Integer size = parseInt(args[5]);
    if (x == null || y == null || z == null || size == null) {
      player.sendMessage(
          Component.text("x/y/z/size must be whole numbers.").color(NamedTextColor.YELLOW));
      return true;
    }
    if ("clear".equalsIgnoreCase(args[1])) {
      plugin.getDataManager().clearPieceClickCommand(block, x, y, z, size);
      player.sendMessage(
          Component.text("Cleared click command for that voxel bit.").color(NamedTextColor.GREEN));
      return true;
    }
    if (args.length < 7) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels bitcommand set <x> <y> <z> <size> <command...>")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    plugin.getDataManager().setPieceClickCommand(block, x, y, z, size, joinArgs(args, 6));
    player.sendMessage(
        Component.text("Bound click command to that voxel bit.").color(NamedTextColor.GREEN));
    return true;
  }

  private boolean animation(Player player, String[] args) {
    if (!hasAnyAnimationPermission(player)) {
      player.sendMessage(
          Component.text("You do not have permission to use SmallVoxels animations.")
              .color(NamedTextColor.RED));
      return true;
    }
    if (args.length < 2) {
      sendAnimationHelp(player);
      return true;
    }

    return switch (args[1].toLowerCase(Locale.ROOT)) {
      case "create", "createenv", "createworld" ->
          requireAnimationPermission(player, "smallvoxels.animation.manage")
              && ("create".equalsIgnoreCase(args[1])
                  ? animationCreate(player, args)
                  : animationCreateEnvironment(player, args));
      case "windcreate" ->
          requireAnimationPermission(player, "smallvoxels.animation.wind")
              && animationWindCreate(player, args);
      case "delete", "remove" ->
          requireAnimationPermission(player, "smallvoxels.animation.delete")
              && animationDelete(player, args);
      case "axe", "tool" ->
          requireAnimationPermission(player, "smallvoxels.animation.get") && animationAxe(player);
      case "gui", "editor", "open" ->
          requireAnimationPermission(player, "smallvoxels.animation.settings")
              && animationGui(player, args);
      case "select",
              "show",
              "hide",
              "play",
              "playsequence",
              "sequencedelay",
              "sequencetrigger",
              "sequencewait",
              "sequenceloop",
              "stop",
              "list",
              "info" ->
          requireAnimationPermission(player, "smallvoxels.animation.manage")
              && animationUtility(player, args);
      case "keyframe", "material", "image", "sound" ->
          requireAnimationPermission(player, "smallvoxels.animation.settings")
              && animationFrameUtility(player, args);
      case "trigger", "chance", "entitytrigger" ->
          requireAnimationPermission(player, "smallvoxels.animation.trigger")
              && animationTrigger(player, args);
      case "copy", "save", "load" ->
          requireAnimationPermission(player, "smallvoxels.animation.schematic")
              && animationSchematic(player, args);
      default -> {
        sendAnimationHelp(player);
        yield true;
      }
    };
  }

  private boolean animationUtility(Player player, String[] args) {
    return switch (args[1].toLowerCase(Locale.ROOT)) {
      case "select" -> animationSelect(player, args);
      case "show" -> animationShow(player, args);
      case "hide" -> animationHide(player, args);
      case "play" -> animationPlay(player, args);
      case "playsequence" -> animationPlaySequence(player, args);
      case "sequencedelay" -> animationSequenceDelay(player, args);
      case "sequencetrigger" -> animationSequenceTrigger(player, args);
      case "sequencewait" -> animationSequenceWait(player, args);
      case "sequenceloop" -> animationSequenceLoop(player, args);
      case "stop" -> animationStop(player, args);
      case "list" -> animationList(player);
      case "info" -> animationInfo(player, args);
      default -> true;
    };
  }

  private boolean animationSchematic(Player player, String[] args) {
    return switch (args[1].toLowerCase(Locale.ROOT)) {
      case "copy" -> animationCopy(player, args);
      case "save" -> animationSave(player, args);
      case "load" -> animationLoad(player, args);
      default -> true;
    };
  }

  private boolean animationFrameUtility(Player player, String[] args) {
    return switch (args[1].toLowerCase(Locale.ROOT)) {
      case "keyframe" -> animationKeyframe(player, args);
      case "material" -> animationMaterial(player, args);
      case "image" -> animationImage(player, args);
      case "sound" -> animationSound(player, args);
      default -> true;
    };
  }

  private boolean hasAnyAnimationPermission(Player player) {
    return player.hasPermission("smallvoxels.admin")
        || player.hasPermission("smallvoxels.animation.use")
        || player.hasPermission("smallvoxels.animation.get")
        || player.hasPermission("smallvoxels.animation.manage")
        || player.hasPermission("smallvoxels.animation.settings")
        || player.hasPermission("smallvoxels.animation.wind")
        || player.hasPermission("smallvoxels.animation.trigger")
        || player.hasPermission("smallvoxels.animation.delete")
        || player.hasPermission("smallvoxels.animation.schematic");
  }

  private boolean requireAnimationPermission(Player player, String permission) {
    if (player.hasPermission("smallvoxels.admin")
        || player.hasPermission("smallvoxels.animation.use")
        || player.hasPermission(permission)) {
      return true;
    }
    player.sendMessage(
        Component.text("You do not have permission for that animation action.")
            .color(NamedTextColor.RED));
    return false;
  }

  private boolean animationCreate(Player player, String[] args) {
    if (args.length < 3) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels animation create <id>").color(NamedTextColor.YELLOW));
      return true;
    }
    AnimatedVoxelObject object = plugin.getAnimatedObjectManager().createEmpty(player, args[2]);
    if (object == null) {
      player.sendMessage(
          Component.text("Use letters, numbers, dash, or underscore for the id.")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    plugin.getAnimatedObjectManager().selectEditorObject(player, object.id);
    plugin.getAnimationAxeManager().setEditing(player, true);
    player.sendMessage(
        Component.text(
                "Created animation label " + object.id + ". Edit blocks, then capture frames.")
            .color(NamedTextColor.GREEN));
    new AnimationEditorGUI(plugin).open(player);
    return true;
  }

  private boolean animationWindCreate(Player player, String[] args) {
    if (args.length < 3) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels animation windcreate <id>")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    AnimatedVoxelObject object = plugin.getAnimatedObjectManager().createWind(player, args[2]);
    if (object == null) {
      player.sendMessage(
          Component.text("Use a unique id with letters, numbers, dash, or underscore.")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    plugin.getAnimatedObjectManager().selectEditorObject(player, object.id);
    plugin.getAnimationAxeManager().setEditing(player, true);
    player.sendMessage(
        Component.text(
                "Created wind animation "
                    + object.id
                    + ". Capture voxels, then mark connectors and nodes with the Animation Axe.")
            .color(NamedTextColor.GREEN));
    new AnimationEditorGUI(plugin).open(player);
    return true;
  }

  private boolean animationAxe(Player player) {
    plugin.getAnimationAxeManager().giveAnimationAxe(player);
    return true;
  }

  private boolean animationCreateEnvironment(Player player, String[] args) {
    if (args.length < 3) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels animation createenv <id> [radiusBlocks]")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    int radius = 8;
    if (args.length >= 4) {
      Integer parsed = parseInt(args[3]);
      if (parsed == null || parsed < 1) {
        player.sendMessage(
            Component.text("Radius must be a positive whole number.").color(NamedTextColor.YELLOW));
        return true;
      }
      radius = parsed;
    }

    AnimatedVoxelObject object =
        plugin.getAnimatedObjectManager().createFromEnvironment(player, args[2], radius);
    if (object == null) {
      player.sendMessage(
          Component.text("No saved voxel pieces found nearby, or the id was invalid.")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    plugin.getAnimatedObjectManager().selectEditorObject(player, object.id);
    player.sendMessage(
        Component.text(
                "Created animated object "
                    + object.id
                    + " from "
                    + object.parts.size()
                    + " world voxels.")
            .color(NamedTextColor.GREEN));
    new AnimationEditorGUI(plugin).open(player);
    return true;
  }

  private boolean animationDelete(Player player, String[] args) {
    if (args.length < 3) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels animation delete <id> [confirm]")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    AnimatedVoxelObject object = plugin.getAnimatedObjectManager().storage().get(args[2]);
    if (object == null) {
      player.sendMessage(Component.text("Animation object not found.").color(NamedTextColor.RED));
      return true;
    }
    if (args.length >= 4 && "confirm".equalsIgnoreCase(args[3])) {
      PendingAnimationDelete pending = pendingAnimationDeletes.get(player.getUniqueId());
      if (pending == null
          || pending.expiresAt < System.currentTimeMillis()
          || !pending.id.equalsIgnoreCase(object.id)) {
        player.sendMessage(
            Component.text(
                    "Run /smallvoxels animation delete "
                        + object.id
                        + " first, then confirm within 30 seconds.")
                .color(NamedTextColor.YELLOW));
        pendingAnimationDeletes.remove(player.getUniqueId());
        return true;
      }
      pendingAnimationDeletes.remove(player.getUniqueId());
      boolean deleted = plugin.getAnimatedObjectManager().delete(object.id);
      player.sendMessage(
          Component.text(
                  deleted
                      ? "Deleted animation object " + object.id + "."
                      : "Animation object not found.")
              .color(deleted ? NamedTextColor.GREEN : NamedTextColor.RED));
      return true;
    }
    pendingAnimationDeletes.put(
        player.getUniqueId(),
        new PendingAnimationDelete(object.id, System.currentTimeMillis() + 30_000L));
    player.sendMessage(
        Component.text("Are you sure you wish to delete " + object.id + "?")
            .color(NamedTextColor.YELLOW));
    player.sendMessage(
        Component.text(
                "Run /smallvoxels animation delete " + object.id + " confirm within 30 seconds.")
            .color(NamedTextColor.RED));
    return true;
  }

  private boolean animationGui(Player player, String[] args) {
    if (args.length >= 3
        && !plugin.getAnimatedObjectManager().selectEditorObject(player, args[2])) {
      player.sendMessage(Component.text("Animation object not found.").color(NamedTextColor.RED));
      return true;
    }
    new AnimationEditorGUI(plugin).open(player);
    return true;
  }

  private boolean animationSelect(Player player, String[] args) {
    if (args.length < 3) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels animation select <id>").color(NamedTextColor.YELLOW));
      return true;
    }
    boolean selected = plugin.getAnimatedObjectManager().selectEditorObject(player, args[2]);
    player.sendMessage(
        Component.text(selected ? "Selected animation object." : "Animation object not found.")
            .color(selected ? NamedTextColor.GREEN : NamedTextColor.RED));
    if (selected) {
      new AnimationEditorGUI(plugin).open(player);
    }
    return true;
  }

  private boolean animationShow(Player player, String[] args) {
    if (args.length < 3) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels animation show <id>").color(NamedTextColor.YELLOW));
      return true;
    }
    boolean shown = plugin.getAnimatedObjectManager().show(args[2]);
    player.sendMessage(
        Component.text(shown ? "Animation object shown." : "Animation object not found.")
            .color(shown ? NamedTextColor.GREEN : NamedTextColor.RED));
    return true;
  }

  private boolean animationHide(Player player, String[] args) {
    if (args.length < 3) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels animation hide <id>").color(NamedTextColor.YELLOW));
      return true;
    }
    boolean hidden = plugin.getAnimatedObjectManager().hide(args[2]);
    player.sendMessage(
        Component.text(hidden ? "Animation object hidden." : "Animation object not found.")
            .color(hidden ? NamedTextColor.GREEN : NamedTextColor.RED));
    return true;
  }

  private boolean animationPlay(Player player, String[] args) {
    if (args.length < 3) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels animation play <id> [animation]")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    String animationId = args.length >= 4 ? args[3] : "main";
    boolean playing = plugin.getAnimatedObjectManager().play(args[2], animationId);
    player.sendMessage(
        Component.text(playing ? "Animation playing." : "Animation object or timeline not found.")
            .color(playing ? NamedTextColor.GREEN : NamedTextColor.RED));
    return true;
  }

  private boolean animationPlaySequence(Player player, String[] args) {
    if (args.length < 3) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels animation playsequence <id> [sequence]")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    String sequenceId = args.length >= 4 ? args[3] : "main";
    boolean playing = plugin.getAnimatedObjectManager().playSequence(args[2], sequenceId);
    player.sendMessage(
        Component.text(
                playing ? "Animation sequence playing." : "Animation object or sequence not found.")
            .color(playing ? NamedTextColor.GREEN : NamedTextColor.RED));
    return true;
  }

  private boolean animationSequenceDelay(Player player, String[] args) {
    if (args.length < 7) {
      player.sendMessage(
          Component.text(
                  "Usage: /smallvoxels animation sequencedelay <id> <sequence> <step> <beforeTicks>"
                      + " <afterTicks>")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    Integer step = parseInt(args[4]);
    Integer before = parseInt(args[5]);
    Integer after = parseInt(args[6]);
    if (step == null || before == null || after == null) {
      player.sendMessage(
          Component.text("Step and delays must be whole numbers.").color(NamedTextColor.YELLOW));
      return true;
    }
    boolean changed =
        plugin
            .getAnimatedObjectManager()
            .configureSequenceStep(args[2], args[3], step, before, after);
    player.sendMessage(
        Component.text(changed ? "Updated sequence step delays." : "Sequence step not found.")
            .color(changed ? NamedTextColor.GREEN : NamedTextColor.RED));
    return true;
  }

  private boolean animationSequenceTrigger(Player player, String[] args) {
    if (args.length < 6) {
      player.sendMessage(
          Component.text(
                  "Usage: /smallvoxels animation sequencetrigger <id> <sequence> <step>"
                      + " <auto|playernear|playeraway|entitynear|entityaway> [radius] [players]"
                      + " [includeInvisible]")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    Integer step = parseInt(args[4]);
    Integer radius = args.length >= 7 ? parseInt(args[6]) : null;
    Integer players = args.length >= 8 ? parseInt(args[7]) : null;
    Boolean invisible = args.length >= 9 ? Boolean.parseBoolean(args[8]) : null;
    if (step == null
        || (args.length >= 7 && radius == null)
        || (args.length >= 8 && players == null)) {
      player.sendMessage(
          Component.text("Step, radius, and players must be whole numbers.")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    boolean changed =
        plugin
            .getAnimatedObjectManager()
            .configureSequenceStepTrigger(
                args[2], args[3], step, args[5], radius, players, invisible);
    player.sendMessage(
        Component.text(
                changed
                    ? "Updated sequence step trigger."
                    : "Sequence step or trigger type not found.")
            .color(changed ? NamedTextColor.GREEN : NamedTextColor.RED));
    return true;
  }

  private boolean animationSequenceWait(Player player, String[] args) {
    if (args.length < 6) {
      player.sendMessage(
          Component.text(
                  "Usage: /smallvoxels animation sequencewait <id> <sequence> <step> <true|false>")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    Integer step = parseInt(args[4]);
    if (step == null) {
      player.sendMessage(
          Component.text("Step must be a whole number.").color(NamedTextColor.YELLOW));
      return true;
    }
    boolean changed =
        plugin
            .getAnimatedObjectManager()
            .configureSequenceStepWait(args[2], args[3], step, Boolean.parseBoolean(args[5]));
    player.sendMessage(
        Component.text(changed ? "Updated sequence wait behavior." : "Sequence step not found.")
            .color(changed ? NamedTextColor.GREEN : NamedTextColor.RED));
    return true;
  }

  private boolean animationSequenceLoop(Player player, String[] args) {
    if (args.length < 5) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels animation sequenceloop <id> <sequence> <true|false>")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    boolean changed =
        plugin
            .getAnimatedObjectManager()
            .setSequenceLoop(args[2], args[3], Boolean.parseBoolean(args[4]));
    player.sendMessage(
        Component.text(changed ? "Updated sequence loop setting." : "Animation object not found.")
            .color(changed ? NamedTextColor.GREEN : NamedTextColor.RED));
    return true;
  }

  private boolean animationStop(Player player, String[] args) {
    if (args.length < 3) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels animation stop <id>").color(NamedTextColor.YELLOW));
      return true;
    }
    boolean stopped = plugin.getAnimatedObjectManager().stop(args[2]);
    player.sendMessage(
        Component.text(stopped ? "Animation stopped." : "That animation was not running.")
            .color(stopped ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
    return true;
  }

  private boolean animationKeyframe(Player player, String[] args) {
    if (args.length < 7) {
      player.sendMessage(
          Component.text(
                  "Usage: /smallvoxels animation keyframe <id> <tick> <x> <y> <z> [yaw]"
                      + " [animation]")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    Integer tick = parseInt(args[3]);
    Double x = parseDouble(args[4]);
    Double y = parseDouble(args[5]);
    Double z = parseDouble(args[6]);
    Float yaw = args.length >= 8 ? parseFloat(args[7]) : 0.0f;
    String animationId = args.length >= 9 ? args[8] : "main";
    if (tick == null || x == null || y == null || z == null || yaw == null || tick < 0) {
      player.sendMessage(
          Component.text("Tick must be positive, and x/y/z/yaw must be numbers.")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    boolean added =
        plugin
            .getAnimatedObjectManager()
            .addKeyframe(args[2], animationId, new AnimationKeyframe(tick, x, y, z, yaw));
    player.sendMessage(
        Component.text(added ? "Keyframe added." : "Animation object not found.")
            .color(added ? NamedTextColor.GREEN : NamedTextColor.RED));
    return true;
  }

  private boolean animationMaterial(Player player, String[] args) {
    if (args.length < 6) {
      player.sendMessage(
          Component.text(
                  "Usage: /smallvoxels animation material <id> <tick> <partId> <material>"
                      + " [animation]")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    Integer tick = parseInt(args[3]);
    if (tick == null || tick < 0 || Material.matchMaterial(args[5]) == null) {
      player.sendMessage(
          Component.text("Use a positive tick and a valid block material.")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    String animationId = args.length >= 7 ? args[6] : "main";
    boolean added =
        plugin
            .getAnimatedObjectManager()
            .addMaterialFrame(
                args[2], animationId, tick, args[4], Material.matchMaterial(args[5]).name());
    player.sendMessage(
        Component.text(added ? "Voxel frame change added." : "Animation object or part not found.")
            .color(added ? NamedTextColor.GREEN : NamedTextColor.RED));
    return true;
  }

  private boolean animationImage(Player player, String[] args) {
    if (args.length < 4) {
      player.sendMessage(
          Component.text(
                  "Usage: /smallvoxels animation image <name> <directImageOrGifUrl> [startTick]"
                      + " [endTick]")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    String name = args[2];
    String url = args[3];
    Integer startTick = args.length >= 5 ? parseInt(args[4]) : null;
    Integer endTick = args.length >= 6 ? parseInt(args[5]) : null;
    if ((args.length >= 5 && startTick == null) || (args.length >= 6 && endTick == null)) {
      player.sendMessage(
          Component.text("Start/end ticks must be whole numbers.").color(NamedTextColor.YELLOW));
      return true;
    }
    String ratio =
        plugin.getAnimatedObjectManager().queueEditorImage(player, name, url, startTick, endTick);
    if (ratio == null) {
      player.sendMessage(
          Component.text("Select an animation/state first, then provide a direct image or GIF URL.")
              .color(NamedTextColor.RED));
    } else if ("unsupported".equals(ratio)) {
      player.sendMessage(
          Component.text(
                  "That URL is not an image/GIF Java can read. Video import needs a video decoder"
                      + " and is not available in this jar.")
              .color(NamedTextColor.RED));
    } else {
      String[] parts = ratio.split(":");
      String size = parts.length >= 2 ? parts[0] + "x" + parts[1] : ratio.replace(':', 'x');
      String frames = parts.length >= 3 ? parts[2] : "1";
      String cleanName = parts.length >= 4 ? parts[3] : name;
      String recommended = parts.length >= 5 ? parts[4] : "match the image ratio";
      String tickText =
          frames.equals("1")
              ? "selected tick"
              : (startTick == null ? "selected tick" : startTick)
                  + "-"
                  + (endTick == null ? "auto" : endTick);
      player.sendMessage(
          Component.text(
                  "Queued image "
                      + cleanName
                      + " ("
                      + size
                      + ", "
                      + frames
                      + " frame"
                      + ("1".equals(frames) ? "" : "s")
                      + ", "
                      + tickText
                      + "). Suggested: "
                      + recommended
                      + ". Use Animation Axe Image Frame mode and select the paste surface.")
              .color(NamedTextColor.GREEN));
    }
    return true;
  }

  private boolean animationSound(Player player, String[] args) {
    if (args.length < 5) {
      player.sendMessage(
          Component.text(
                  "Usage: /smallvoxels animation sound <id> <state> <sound|clear> [volume] [pitch]")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    if ("clear".equalsIgnoreCase(args[4]) || "none".equalsIgnoreCase(args[4])) {
      boolean changed =
          plugin
              .getAnimatedObjectManager()
              .configureAnimationSound(args[2], args[3], null, 1.0f, 1.0f);
      player.sendMessage(
          Component.text(changed ? "Cleared animation state sound." : "Animation object not found.")
              .color(changed ? NamedTextColor.GREEN : NamedTextColor.RED));
      return true;
    }
    Sound sound;
    try {
      sound = Sound.valueOf(args[4].toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      player.sendMessage(
          Component.text(
                  "Unknown sound. Use tab complete after the sound argument for valid names.")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    Float volume = args.length >= 6 ? parseFloat(args[5]) : 1.0f;
    Float pitch = args.length >= 7 ? parseFloat(args[6]) : 1.0f;
    if (volume == null || pitch == null) {
      player.sendMessage(
          Component.text("Volume and pitch must be numbers.").color(NamedTextColor.YELLOW));
      return true;
    }
    boolean changed =
        plugin
            .getAnimatedObjectManager()
            .configureAnimationSound(args[2], args[3], sound.name(), volume, pitch);
    player.sendMessage(
        Component.text(
                changed
                    ? "Assigned sound " + sound.name() + " to " + args[3] + "."
                    : "Animation object not found.")
            .color(changed ? NamedTextColor.GREEN : NamedTextColor.RED));
    return true;
  }

  private boolean animationTrigger(Player player, String[] args) {
    String subcommand = args[1].toLowerCase(Locale.ROOT);
    if ("chance".equals(subcommand)) {
      return animationChanceTrigger(player, args);
    }
    if ("entitytrigger".equals(subcommand)) {
      return animationEntityTrigger(player, args);
    }
    if (args.length < 5) {
      player.sendMessage(
          Component.text(
                  "Usage: /smallvoxels animation trigger <id> <state>"
                      + " <manual|playerdistance|redstone|probability|entity> [options]")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    String type = args[4].toUpperCase(Locale.ROOT).replace("-", "_");
    if ("PLAYER".equals(type) || "PLAYER_ENTER".equals(type)) {
      type = "PLAYER_DISTANCE";
    }
    if ("TIMER".equals(type) || "RANDOM".equals(type)) {
      type = "PROBABILITY";
    }
    if ("ENTITY".equals(type)) {
      type = "ENTITY_NEARBY";
    }
    if (!List.of("MANUAL", "PLAYER_DISTANCE", "REDSTONE", "PROBABILITY", "ENTITY_NEARBY")
        .contains(type)) {
      player.sendMessage(
          Component.text(
                  "Trigger must be manual, playerdistance, redstone, probability, or entity.")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    if ("PROBABILITY".equals(type)) {
      Double chance = args.length >= 6 ? parseDouble(args[5]) : 100.0;
      Integer interval = args.length >= 7 ? parseInt(args[6]) : 1200;
      Integer cooldown = args.length >= 8 ? parseInt(args[7]) : null;
      if (chance == null || interval == null) {
        player.sendMessage(
            Component.text(
                    "Usage: /smallvoxels animation trigger <id> <state> probability <chancePercent>"
                        + " <intervalTicks> [cooldownTicks]")
                .color(NamedTextColor.YELLOW));
        return true;
      }
      boolean changed =
          plugin
              .getAnimatedObjectManager()
              .configureProbabilityTrigger(args[2], args[3], chance, interval, cooldown);
      player.sendMessage(
          Component.text(changed ? "Updated probability trigger." : "Animation object not found.")
              .color(changed ? NamedTextColor.GREEN : NamedTextColor.RED));
      return true;
    }
    if ("ENTITY_NEARBY".equals(type)) {
      Integer radius = args.length >= 6 ? parseInt(args[5]) : 3;
      boolean includeInvisible = args.length >= 7 && Boolean.parseBoolean(args[6]);
      Integer cooldown = args.length >= 8 ? parseInt(args[7]) : null;
      if (radius == null) {
        player.sendMessage(
            Component.text(
                    "Usage: /smallvoxels animation trigger <id> <state> entity <radius>"
                        + " [includeInvisible] [cooldownTicks]")
                .color(NamedTextColor.YELLOW));
        return true;
      }
      boolean changed =
          plugin
              .getAnimatedObjectManager()
              .configureEntityTrigger(args[2], args[3], radius, includeInvisible, cooldown);
      player.sendMessage(
          Component.text(changed ? "Updated entity trigger." : "Animation object not found.")
              .color(changed ? NamedTextColor.GREEN : NamedTextColor.RED));
      return true;
    }
    Integer radius = args.length >= 6 ? parseInt(args[5]) : null;
    Integer players = args.length >= 7 ? parseInt(args[6]) : null;
    Block redstone = "REDSTONE".equals(type) ? player.getTargetBlockExact(6) : null;
    boolean changed =
        plugin
            .getAnimatedObjectManager()
            .configureTrigger(args[2], args[3], type, radius, players, redstone);
    player.sendMessage(
        Component.text(
                changed
                    ? "Updated trigger"
                        + ("REDSTONE".equals(type) && redstone != null
                            ? " at targeted block."
                            : ".")
                    : "Animation object not found.")
            .color(changed ? NamedTextColor.GREEN : NamedTextColor.RED));
    return true;
  }

  private boolean animationChanceTrigger(Player player, String[] args) {
    if (args.length < 6) {
      player.sendMessage(
          Component.text(
                  "Usage: /smallvoxels animation chance <id> <state> <chancePercent>"
                      + " <intervalTicks> [cooldownTicks]")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    Double chance = parseDouble(args[4]);
    Integer interval = parseInt(args[5]);
    Integer cooldown = args.length >= 7 ? parseInt(args[6]) : null;
    if (chance == null || interval == null) {
      player.sendMessage(
          Component.text("Chance must be a percent and interval must be ticks.")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    boolean changed =
        plugin
            .getAnimatedObjectManager()
            .configureProbabilityTrigger(args[2], args[3], chance, interval, cooldown);
    player.sendMessage(
        Component.text(changed ? "Updated probability trigger." : "Animation object not found.")
            .color(changed ? NamedTextColor.GREEN : NamedTextColor.RED));
    return true;
  }

  private boolean animationEntityTrigger(Player player, String[] args) {
    if (args.length < 5) {
      player.sendMessage(
          Component.text(
                  "Usage: /smallvoxels animation entitytrigger <id> <state> <radius>"
                      + " [includeInvisible] [cooldownTicks]")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    Integer radius = parseInt(args[4]);
    boolean includeInvisible = args.length >= 6 && Boolean.parseBoolean(args[5]);
    Integer cooldown = args.length >= 7 ? parseInt(args[6]) : null;
    if (radius == null) {
      player.sendMessage(Component.text("Radius must be a number.").color(NamedTextColor.YELLOW));
      return true;
    }
    boolean changed =
        plugin
            .getAnimatedObjectManager()
            .configureEntityTrigger(args[2], args[3], radius, includeInvisible, cooldown);
    player.sendMessage(
        Component.text(changed ? "Updated entity trigger." : "Animation object not found.")
            .color(changed ? NamedTextColor.GREEN : NamedTextColor.RED));
    return true;
  }

  private boolean animationCopy(Player player, String[] args) {
    if (args.length < 4) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels animation copy <sourceId> <newId>")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    AnimatedVoxelObject object =
        plugin.getAnimatedObjectManager().copyObject(player, args[2], args[3]);
    if (object == null) {
      player.sendMessage(
          Component.text("Could not copy animation. Check the source id and new id.")
              .color(NamedTextColor.RED));
      return true;
    }
    plugin.getAnimatedObjectManager().selectEditorObject(player, object.id);
    player.sendMessage(
        Component.text("Copied animation to " + object.id + ".").color(NamedTextColor.GREEN));
    new AnimationEditorGUI(plugin).open(player);
    return true;
  }

  private boolean animationSave(Player player, String[] args) {
    if (args.length < 4) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels animation save <id> <name>")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    File file = animationSchematicFile(player, args[3]);
    if (file == null) {
      player.sendMessage(
          Component.text("Use letters, numbers, dash, or underscore for the schematic name.")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    boolean saved = plugin.getAnimatedObjectManager().saveSchematic(args[2], file);
    player.sendMessage(
        Component.text(
                saved
                    ? "Saved animation schematic " + file.getName() + "."
                    : "Animation object not found.")
            .color(saved ? NamedTextColor.GREEN : NamedTextColor.RED));
    return true;
  }

  private boolean animationLoad(Player player, String[] args) {
    if (args.length < 4) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels animation load <name> [newId]")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    String newId = args.length >= 5 ? args[4] : args[3];
    File file = animationSchematicFile(player, args[3]);
    if (file == null) {
      player.sendMessage(
          Component.text("Use letters, numbers, dash, or underscore for the schematic name.")
              .color(NamedTextColor.YELLOW));
      return true;
    }
    AnimatedVoxelObject object =
        plugin.getAnimatedObjectManager().loadSchematic(player, file, newId);
    if (object == null) {
      player.sendMessage(
          Component.text("Could not load animation schematic.").color(NamedTextColor.RED));
      return true;
    }
    plugin.getAnimatedObjectManager().selectEditorObject(player, object.id);
    player.sendMessage(
        Component.text("Loaded animation schematic as " + object.id + ".")
            .color(NamedTextColor.GREEN));
    new AnimationEditorGUI(plugin).open(player);
    return true;
  }

  private boolean animationList(Player player) {
    List<String> ids =
        plugin.getAnimatedObjectManager().storage().all().stream()
            .map(object -> object.id)
            .sorted()
            .toList();
    if (ids.isEmpty()) {
      player.sendMessage(
          Component.text("No animated voxel objects saved yet.").color(NamedTextColor.YELLOW));
    } else {
      player.sendMessage(
          Component.text("Animated objects: ")
              .color(NamedTextColor.RED)
              .append(Component.text(String.join(", ", ids)).color(NamedTextColor.GREEN)));
    }
    return true;
  }

  private boolean animationInfo(Player player, String[] args) {
    if (args.length < 3) {
      player.sendMessage(
          Component.text("Usage: /smallvoxels animation info <id>").color(NamedTextColor.YELLOW));
      return true;
    }
    AnimatedVoxelObject object = plugin.getAnimatedObjectManager().storage().get(args[2]);
    if (object == null) {
      player.sendMessage(Component.text("Animation object not found.").color(NamedTextColor.RED));
      return true;
    }
    List<String> timelines = object.animations.stream().map(animation -> animation.id).toList();
    player.sendMessage(
        Component.text(
                object.id
                    + ": "
                    + object.parts.size()
                    + " parts, timelines: "
                    + String.join(", ", timelines))
            .color(NamedTextColor.GREEN));
    if (!object.parts.isEmpty()) {
      player.sendMessage(
          Component.text(
                  "First part ids: " + object.parts.stream().limit(8).map(part -> part.id).toList())
              .color(NamedTextColor.GRAY));
    }
    return true;
  }

  private void sendAnimationHelp(Player player) {
    player.sendMessage(
        Component.text("/smallvoxels animation axe | create <id> | windcreate <id> | gui [id]")
            .color(NamedTextColor.GRAY));
    player.sendMessage(
        Component.text("/smallvoxels animation list | delete <id>").color(NamedTextColor.GRAY));
    player.sendMessage(
        Component.text("/smallvoxels animation show|hide|play|playsequence|stop|info|list")
            .color(NamedTextColor.GRAY));
    player.sendMessage(
        Component.text(
                "/smallvoxels animation sequencedelay|sequencetrigger|sequencewait|sequenceloop"
                    + " ...")
            .color(NamedTextColor.GRAY));
    player.sendMessage(
        Component.text("/smallvoxels animation trigger|chance|entitytrigger ...")
            .color(NamedTextColor.GRAY));
    player.sendMessage(
        Component.text(
                "/smallvoxels animation image <name> <directImageOrGifUrl> [startTick] [endTick]")
            .color(NamedTextColor.GRAY));
    player.sendMessage(
        Component.text("/smallvoxels animation sound <id> <state> <sound|clear> [volume] [pitch]")
            .color(NamedTextColor.GRAY));
    player.sendMessage(
        Component.text("/smallvoxels animation copy|save|load ...").color(NamedTextColor.GRAY));
  }

  private Integer parseInt(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private Double parseDouble(String value) {
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private Float parseFloat(String value) {
    try {
      return Float.parseFloat(value);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private String joinArgs(String[] args, int start) {
    if (start >= args.length) {
      return "";
    }
    StringBuilder builder = new StringBuilder(args[start]);
    for (int i = start + 1; i < args.length; i++) {
      builder.append(' ').append(args[i]);
    }
    return builder.toString();
  }

  private DeleteScan scanDelete(Player player, Integer pieceSize, OfflinePlayer owner) {
    DeleteScan scan = new DeleteScan();
    String ownerId = owner == null ? null : owner.getUniqueId().toString();
    for (Block block : plugin.getDataManager().getSavedVoxelBlocks(player.getWorld())) {
      if (ownerId != null && !ownerId.equals(plugin.getVoxelProtectionManager().ownerOf(block))) {
        continue;
      }
      List<VoxelPiece> pieces = plugin.getDataManager().getVoxelPieces(block);
      int matching = 0;
      for (VoxelPiece piece : pieces) {
        if (pieceSize == null || piece.size == pieceSize) {
          matching++;
        }
      }
      if (matching > 0) {
        scan.blocks.add(block);
        scan.blockCount++;
        scan.pieceCount += matching;
      }
    }
    return scan;
  }

  private Integer pieceSizeFromFlag(String flag) {
    return switch (flag.toLowerCase(Locale.ROOT)) {
      case "2x" -> VoxelPieceManager.getPieceSizeForScale(2);
      case "4x" -> VoxelPieceManager.getPieceSizeForScale(4);
      case "8x" -> VoxelPieceManager.getPieceSizeForScale(8);
      case "16x" -> VoxelPieceManager.getPieceSizeForScale(16);
      default -> null;
    };
  }

  private OfflinePlayer ownerFilter(Player player, String[] args) {
    for (int i = 2; i < args.length; i++) {
      String arg = args[i];
      if ("--mine".equalsIgnoreCase(arg)) {
        return player;
      }
      if (arg.toLowerCase(Locale.ROOT).startsWith("--player=")
          || arg.toLowerCase(Locale.ROOT).startsWith("player:")) {
        String name = arg.substring(arg.indexOf(arg.contains("=") ? "=" : ":") + 1);
        return name.isBlank() ? null : BukkitPlayer(name);
      }
      if ("--player".equalsIgnoreCase(arg) || "player".equalsIgnoreCase(arg)) {
        if (i + 1 >= args.length) {
          return null;
        }
        return BukkitPlayer(args[i + 1]);
      }
    }
    return null;
  }

  private boolean hasPlayerFlag(String[] args) {
    for (String arg : args) {
      String lower = arg.toLowerCase(Locale.ROOT);
      if (lower.equals("--player")
          || lower.equals("player")
          || lower.startsWith("--player=")
          || lower.startsWith("player:")) {
        return true;
      }
    }
    return false;
  }

  private boolean hasFlag(String[] args, String flag) {
    for (String arg : args) {
      if (flag.equalsIgnoreCase(arg)) {
        return true;
      }
    }
    return false;
  }

  private File schematicFolder(Player player) {
    File root = new File(plugin.getDataFolder(), "schematics");
    File worldFolder = new File(root, player.getWorld().getName());
    File folder =
        plugin.getVoxelProtectionManager().usesPlayerSchematics(player.getWorld())
            ? new File(worldFolder, player.getUniqueId().toString())
            : worldFolder;
    if (!folder.exists()) {
      folder.mkdirs();
    }
    return folder;
  }

  private File schematicFile(Player player, String rawName) {
    String name = rawName.toLowerCase(Locale.ROOT);
    if (!name.matches("[a-z0-9_-]{1,48}")) {
      return null;
    }
    return new File(schematicFolder(player), name + ".json");
  }

  private File animationSchematicFile(Player player, String rawName) {
    String name = rawName.toLowerCase(Locale.ROOT);
    if (!name.matches("[a-z0-9_-]{1,48}")) {
      return null;
    }
    File folder =
        new File(
            new File(plugin.getDataFolder(), "animation-schematics"), player.getWorld().getName());
    if (!folder.exists()) {
      folder.mkdirs();
    }
    return new File(folder, name + ".json");
  }

  private boolean canUseSchematics(Player player) {
    if (player.hasPermission("smallvoxels.schematic.use")) {
      return true;
    }
    player.sendMessage(
        Component.text("You do not have permission to use SmallVoxels schematics.")
            .color(NamedTextColor.RED));
    return false;
  }

  private boolean canUseTrust(Player player) {
    if (player.hasPermission("smallvoxels.trust.use")) {
      return true;
    }
    player.sendMessage(
        Component.text("You do not have permission to manage voxel trust.")
            .color(NamedTextColor.RED));
    return false;
  }

  private OfflinePlayer BukkitPlayer(String name) {
    return org.bukkit.Bukkit.getOfflinePlayer(name);
  }

  private String displayName(OfflinePlayer player) {
    return player.getName() == null ? player.getUniqueId().toString() : player.getName();
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String alias, String[] args) {
    if (args.length == 1) {
      List<String> detail =
          List.of("tools", "brush", "trim", "damage", "overgrow", "stamp", "detail", "intensity");
      List<String> values =
          new ArrayList<>(
              List.of(
                  "refresh",
                  "save",
                  "load",
                  "list",
                  "rotate",
                  "flip",
                  "delete",
                  "clickcommand",
                  "bitcommand",
                  "animation",
                  "anim",
                  "playertrust",
                  "playertrustregion",
                  "playerremove",
                  "player"));
      values.addAll(detail);
      return values;
    }
    if (args.length == 2 && "brush".equalsIgnoreCase(args[0]))
      return Arrays.stream(BrushType.values()).map(v -> v.name().toLowerCase(Locale.ROOT)).toList();
    if (args.length == 2 && "stamp".equalsIgnoreCase(args[0]))
      return Arrays.stream(DetailPreset.values())
          .map(v -> v.name().toLowerCase(Locale.ROOT))
          .toList();
    if (args.length == 2 && "detail".equalsIgnoreCase(args[0]))
      return List.of("ruined", "overgrown");
    if (args.length == 2 && "intensity".equalsIgnoreCase(args[0]))
      return List.of("0.25", "0.5", "0.75", "1.0");
    if (args.length == 2 && "trim".equalsIgnoreCase(args[0]))
      return List.of(
          "oak_planks", "spruce_planks", "stone_bricks", "deepslate_bricks", "copper_block");
    if (args.length == 2
        && ("animation".equalsIgnoreCase(args[0]) || "anim".equalsIgnoreCase(args[0]))) {
      return List.of(
          "axe",
          "create",
          "windcreate",
          "gui",
          "select",
          "show",
          "hide",
          "play",
          "playsequence",
          "sequencedelay",
          "sequencetrigger",
          "sequencewait",
          "sequenceloop",
          "stop",
          "list",
          "info",
          "delete",
          "trigger",
          "chance",
          "entitytrigger",
          "image",
          "sound",
          "copy",
          "save",
          "load");
    }
    if (args.length == 2 && "save".equalsIgnoreCase(args[0])) {
      return List.of("<name>");
    }
    if (args.length == 3 && "save".equalsIgnoreCase(args[0])) {
      return List.of("--animations");
    }
    if (args.length == 2 && "clickcommand".equalsIgnoreCase(args[0])) {
      return sender instanceof Player player
              && plugin.getVoxelSelectionManager().hasLastCopySelection(player)
          ? List.of("set", "clear")
          : List.of("set", "clear", "<make-copy-selection-first>");
    }
    if (args.length >= 3
        && "clickcommand".equalsIgnoreCase(args[0])
        && "set".equalsIgnoreCase(args[1])) {
      return List.of(
          "<any command...>",
          "console:<any command...>",
          "smallvoxels animation play <id> <state>");
    }
    if (("animation".equalsIgnoreCase(args[0]) || "anim".equalsIgnoreCase(args[0]))
        && args.length >= 3) {
      String sub = args[1].toLowerCase(Locale.ROOT);
      if (List.of(
                  "select",
                  "show",
                  "hide",
                  "play",
                  "playsequence",
                  "sequencedelay",
                  "sequencetrigger",
                  "sequencewait",
                  "sequenceloop",
                  "stop",
                  "info",
                  "delete",
                  "copy",
                  "save",
                  "sound")
              .contains(sub)
          && args.length == 3) {
        return animationIds();
      }
      if ("delete".equals(sub) && args.length == 4) {
        return List.of("confirm");
      }
      if (List.of("play", "trigger", "chance", "entitytrigger", "sound").contains(sub)
          && args.length == 4) {
        return animationStateIds(args[2]);
      }
      if ("sound".equals(sub) && args.length == 5) {
        return soundNames(args[4]);
      }
      if ("sound".equals(sub) && args.length == 6) {
        return List.of("0.5", "1", "2", "4");
      }
      if ("sound".equals(sub) && args.length == 7) {
        return List.of("0.5", "1", "1.2", "2");
      }
      if ("playsequence".equals(sub) && args.length == 4) {
        return sequenceIds(args[2]);
      }
      if (List.of("sequencedelay", "sequencetrigger", "sequencewait").contains(sub)
          && args.length == 4) {
        return sequenceIds(args[2]);
      }
      if (List.of("sequencedelay", "sequencetrigger", "sequencewait").contains(sub)
          && args.length == 5) {
        return sequenceStepNumbers(args[2], args[3]);
      }
      if ("sequencedelay".equals(sub) && (args.length == 6 || args.length == 7)) {
        return List.of("0", "10", "20", "40", "100");
      }
      if ("sequencetrigger".equals(sub) && args.length == 6) {
        return List.of("auto", "playernear", "playeraway", "entitynear", "entityaway");
      }
      if ("sequencetrigger".equals(sub) && args.length == 7) {
        return List.of("3", "5", "10", "20");
      }
      if ("sequencetrigger".equals(sub) && args.length == 8) {
        return List.of("1", "2", "5", "10");
      }
      if ("sequencetrigger".equals(sub) && args.length == 9) {
        return List.of("false", "true");
      }
      if ("sequencewait".equals(sub) && args.length == 6) {
        return List.of("true", "false");
      }
      if ("sequenceloop".equals(sub) && args.length == 4) {
        return sequenceIds(args[2]);
      }
      if ("sequenceloop".equals(sub) && args.length == 5) {
        return List.of("true", "false");
      }
      if ("trigger".equals(sub) && args.length == 5) {
        return List.of("manual", "playerdistance", "redstone", "probability", "entity");
      }
      if ("trigger".equals(sub) && args.length >= 6) {
        return List.of("3", "5", "10", "20", "true", "false");
      }
      if ("chance".equals(sub) && args.length == 5) {
        return List.of("2", "5", "20", "100");
      }
      if ("chance".equals(sub) && args.length == 6) {
        return List.of("20", "100", "1200", "6000");
      }
      if ("entitytrigger".equals(sub) && args.length == 5) {
        return List.of("3", "5", "10", "20");
      }
      if ("entitytrigger".equals(sub) && args.length == 6) {
        return List.of("false", "true");
      }
      if ("image".equals(sub) && args.length == 3) {
        return List.of("logo", "poster", "frame_1");
      }
      if ("image".equals(sub) && args.length == 4) {
        return List.of("https://example.com/image.png", "https://example.com/animation.gif");
      }
      if ("image".equals(sub) && (args.length == 5 || args.length == 6)) {
        return List.of("0", "20", "40", "100");
      }
      if ("load".equals(sub) && args.length == 3) {
        return animationSchematicNames(sender);
      }
      if ("copy".equals(sub) && args.length == 4) {
        return List.of("<new-id>");
      }
    }
    if (args.length == 5
        && ("animation".equalsIgnoreCase(args[0]) || "anim".equalsIgnoreCase(args[0]))
        && "trigger".equalsIgnoreCase(args[1])) {
      return List.of("manual", "playerdistance", "redstone", "probability", "entity");
    }
    if (args.length == 2
        && ("clickcommand".equalsIgnoreCase(args[0]) || "blockcommand".equalsIgnoreCase(args[0]))) {
      return List.of("set", "clear");
    }
    if (args.length == 2
        && ("bitcommand".equalsIgnoreCase(args[0]) || "piececommand".equalsIgnoreCase(args[0]))) {
      return List.of("set", "clear");
    }
    if (args.length == 2 && "delete".equalsIgnoreCase(args[0])) {
      return List.of("2x", "4x", "8x", "16x", "all", "confirm");
    }
    if (args.length == 3
        && "delete".equalsIgnoreCase(args[0])
        && !"confirm".equalsIgnoreCase(args[1])) {
      return List.of("--mine", "--player");
    }
    if (args.length == 2 && "flip".equalsIgnoreCase(args[0])) {
      return List.of("x", "y", "z");
    }
    if (args.length == 2 && "load".equalsIgnoreCase(args[0])) {
      if (!(sender instanceof Player player)) {
        return List.of();
      }
      String[] files = schematicFolder(player).list((dir, name) -> name.endsWith(".json"));
      if (files == null) {
        return List.of();
      }
      List<String> names = new ArrayList<>();
      for (String file : files) {
        names.add(file.substring(0, file.length() - ".json".length()));
      }
      return names;
    }
    if (args.length == 2 && "player".equalsIgnoreCase(args[0])) {
      return List.of("list");
    }
    return List.of();
  }

  private List<String> animationIds() {
    return plugin.getAnimatedObjectManager().storage().all().stream()
        .map(object -> object.id)
        .sorted()
        .toList();
  }

  private List<String> animationStateIds(String objectId) {
    AnimatedVoxelObject object = plugin.getAnimatedObjectManager().storage().get(objectId);
    if (object == null) {
      return List.of("main");
    }
    return object.animations.stream().map(animation -> animation.id).sorted().toList();
  }

  private List<String> sequenceIds(String objectId) {
    AnimatedVoxelObject object = plugin.getAnimatedObjectManager().storage().get(objectId);
    if (object == null || object.sequences == null || object.sequences.isEmpty()) {
      return List.of("main");
    }
    return object.sequences.stream().map(sequence -> sequence.id).sorted().toList();
  }

  private List<String> sequenceStepNumbers(String objectId, String sequenceId) {
    AnimatedVoxelObject object = plugin.getAnimatedObjectManager().storage().get(objectId);
    if (object == null || object.sequence(sequenceId) == null) {
      return List.of("1");
    }
    List<String> steps = new ArrayList<>();
    for (int i = 1; i <= object.sequence(sequenceId).steps.size(); i++) {
      steps.add(String.valueOf(i));
    }
    return steps.isEmpty() ? List.of("1") : steps;
  }

  private List<String> soundNames(String prefix) {
    String normalized = prefix == null ? "" : prefix.toUpperCase(Locale.ROOT);
    List<String> names = new ArrayList<>();
    if ("".equals(normalized) || "CLEAR".startsWith(normalized)) {
      names.add("clear");
    }
    for (Sound sound : Sound.values()) {
      String name = sound.name();
      if (normalized.isBlank() || name.startsWith(normalized)) {
        names.add(name);
      }
      if (names.size() >= 60) {
        break;
      }
    }
    return names;
  }

  private List<String> animationSchematicNames(CommandSender sender) {
    if (!(sender instanceof Player player)) {
      return List.of();
    }
    File folder =
        new File(
            new File(plugin.getDataFolder(), "animation-schematics"), player.getWorld().getName());
    String[] files = folder.list((dir, name) -> name.endsWith(".json"));
    if (files == null) {
      return List.of();
    }
    List<String> names = new ArrayList<>();
    for (String file : files) {
      names.add(file.substring(0, file.length() - ".json".length()));
    }
    names.sort(String::compareToIgnoreCase);
    return names;
  }

  private static class SchematicFile {
    int version;
    List<VoxelPiece> pieces = List.of();
  }

  private static class RegionSchematicFile {
    int version;
    int minBlockX;
    int minBlockY;
    int minBlockZ;
    List<SchematicBlock> blocks = new ArrayList<>();
    List<AnimatedVoxelObject> animations = new ArrayList<>();
  }

  private static class SchematicBlock {
    int dx;
    int dy;
    int dz;
    String material;
    String blockData;
    List<VoxelPiece> pieces = List.of();
  }

  private static class DeleteScan {
    private final List<Block> blocks = new ArrayList<>();
    private int pieceCount;
    private int blockCount;
  }

  private static class PendingDelete {
    private final String worldName;
    private final Integer pieceSize;
    private final UUID ownerId;
    private final int pieceCount;
    private final int blockCount;
    private final long expiresAt;

    PendingDelete(
        String worldName,
        Integer pieceSize,
        UUID ownerId,
        int pieceCount,
        int blockCount,
        long expiresAt) {
      this.worldName = worldName;
      this.pieceSize = pieceSize;
      this.ownerId = ownerId;
      this.pieceCount = pieceCount;
      this.blockCount = blockCount;
      this.expiresAt = expiresAt;
    }
  }

  private record PendingAnimationDelete(String id, long expiresAt) {}
}
