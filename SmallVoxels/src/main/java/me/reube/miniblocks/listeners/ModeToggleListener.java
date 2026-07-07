package me.reube.SmallVoxels.listeners;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.managers.ToolMode;
import me.reube.SmallVoxels.ui.BlockSelectionGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

public class ModeToggleListener implements Listener {

  private final SmallVoxels plugin;
  private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
  private final File playerFile;
  private final Map<String, ToolMode> playerMode = new HashMap<>();
  private final Map<String, String> playerSelectedBlock = new HashMap<>();
  private final Map<String, ItemStack> playerBlockMetadata = new HashMap<>();
  private final Map<String, Integer> playerVoxelScale = new HashMap<>();
  private final Map<String, Boolean> placementPreview = new HashMap<>();
  private final Map<String, Boolean> lockOutline = new HashMap<>();
  private final Map<String, Integer> brushRadius = new HashMap<>();
  private final Map<String, Boolean> brushRound = new HashMap<>();
  private final Map<String, Boolean> brushSmooth = new HashMap<>();
  private final Map<String, Boolean> brushMask = new HashMap<>();
  private final Map<String, Boolean> setLine = new HashMap<>();
  private final Map<String, Boolean> setMask = new HashMap<>();
  private final Map<String, Boolean> setKeepCorner = new HashMap<>();
  private final Map<String, Boolean> removeMass = new HashMap<>();
  private final Map<String, Boolean> removeKeepCorner = new HashMap<>();
  private final Map<String, Boolean> pasteMask = new HashMap<>();
  private final Map<String, Integer> toolHeight = new HashMap<>();
  private final Map<String, String> toolAxis = new HashMap<>();
  private final Map<String, String> rotateAxis = new HashMap<>();
  private final Map<String, Integer> rotateAngle = new HashMap<>();
  private final Map<String, Double> rotateScale = new HashMap<>();
  private final Map<String, Boolean> moveLocalSpace = new HashMap<>();
  private final Map<String, Integer> moveStep = new HashMap<>();
  private final Map<String, String> scaleAxis = new HashMap<>();
  private final Map<String, Double> scaleStep = new HashMap<>();
  private final Map<String, String> replaceFromBlock = new HashMap<>();
  private final Map<String, String> replaceToBlock = new HashMap<>();
  private final Map<String, String> replacePicker = new HashMap<>();
  private final Map<String, String> sidebarStatus = new HashMap<>();
  private final Map<String, Long> sidebarStatusUntil = new HashMap<>();
  private final Map<String, List<String>> lastSidebarLines = new HashMap<>();
  private final Map<String, Long> modeCooldown = new HashMap<>();
  public final BlockSelectionGUI blockGUI;

  public ModeToggleListener(SmallVoxels plugin) {
    this.plugin = plugin;
    this.blockGUI = new BlockSelectionGUI(plugin);
    this.playerFile = new File(plugin.getDataFolder(), "players.json");
    loadPlayers();
    startSidebarTask();
  }

  @EventHandler
  public void onPlayerQuit(PlayerQuitEvent event) {
    savePlayers();
    event.getPlayer().setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
  }

  @EventHandler
  public void onPlayerJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    if (!plugin.getConfig().getBoolean("startup-message", true)) {
      return;
    }
    if (!player.hasPermission("smallvoxels.voxel.get")
        && !player.hasPermission("smallvoxels.chisel.get")
        && !player.hasPermission("smallvoxels.admin")) {
      return;
    }

    Bukkit.getScheduler()
        .runTaskLater(
            plugin,
            () -> {
              player.sendMessage(
                  Component.text("SmallVoxels is installed. Use /getvoxel to get the Voxel Axe.")
                      .color(NamedTextColor.GREEN));
            },
            40L);
  }

  @EventHandler
  public void onDrop(PlayerDropItemEvent event) {
    Player player = event.getPlayer();
    ItemStack dropped = event.getItemDrop().getItemStack();
    if (!plugin.getChiselManager().isChiselAxe(player.getInventory().getItemInMainHand())
        && !plugin.getChiselManager().isChiselAxe(dropped)) {
      return;
    }

    event.setCancelled(true);
    if (!canUseChisel(player)) {
      setStatus(player, "No permission");
      return;
    }
    cycleToolMode(player);
  }

  @EventHandler
  public void onSwapHands(PlayerSwapHandItemsEvent event) {
    Player player = event.getPlayer();
    if (!plugin.getChiselManager().isChiselAxe(player.getInventory().getItemInMainHand())) {
      return;
    }

    event.setCancelled(true);
    if (!canUseChisel(player)) {
      setStatus(player, "No permission");
      return;
    }
    openCurrentSettings(player);
  }

  @EventHandler
  public void onHotbarScroll(PlayerItemHeldEvent event) {
    Player player = event.getPlayer();
    if (!plugin.getChiselManager().isChiselAxe(player.getInventory().getItemInMainHand())) {
      return;
    }
    if (!player.isSneaking()) {
      return;
    }

    event.setCancelled(true);
    // Hotbar slots form a ring. Normalize the delta so 8 -> 0 is forward
    // and 0 -> 8 is backward instead of treating both wraps alike.
    int diff = Math.floorMod(event.getNewSlot() - event.getPreviousSlot(), 9);
    int direction = diff <= 4 ? 1 : -1;
    if (plugin.getChiselInteractionListener() != null
        && plugin.getChiselInteractionListener().adjustTransformSelection(player, direction))
      return;
    if (getToolMode(player) == ToolMode.ROTATE
        && plugin.getVoxelSelectionManager().hasMoveSelection(player)) {
      boolean changed =
          plugin
              .getVoxelSelectionManager()
              .rotateSelection(player, getRotateAxis(player), direction * getRotateAngle(player));
      setStatus(
          player,
          changed
              ? "Rotated "
                  + (direction > 0 ? "+" : "-")
                  + getRotateAngle(player)
                  + "° "
                  + getRotateAxis(player).toUpperCase()
              : "Shift-select first");
      return;
    }
    if (getToolMode(player) == ToolMode.SCALE
        && plugin.getVoxelSelectionManager().hasMoveSelection(player)) {
      double step = getScaleStep(player);
      double factor = direction > 0 ? 1.0 + step : Math.max(0.05, 1.0 - step);
      boolean changed =
          plugin.getVoxelSelectionManager().scaleSelection(player, getScaleAxis(player), factor);
      setStatus(
          player, changed ? "Scaled " + getScaleAxis(player).toUpperCase() : "Shift-select first");
      return;
    }
    if (getToolMode(player) == ToolMode.MOVE
        && plugin.getVoxelSelectionManager().hasMoveSelection(player)) {
      int step = getMoveStep(player) * direction;
      int dx = 0, dy = 0, dz = 0;
      if (getToolAxis(player).equals("x")) dx = step;
      else if (getToolAxis(player).equals("z")) dz = step;
      else dy = step;
      boolean changed =
          isMoveLocalSpace(player)
              ? plugin.getVoxelSelectionManager().moveSelectionLocal(player, dx, dy, dz)
              : plugin.getVoxelSelectionManager().moveSelection(player, dx, dy, dz);
      setStatus(
          player,
          changed
              ? "Moved " + (direction > 0 ? "+" : "-") + getToolAxis(player).toUpperCase()
              : "Move blocked");
      return;
    }
    adjustToolHeight(player, direction);
  }

  public ToolMode getToolMode(Player player) {
    return playerMode.getOrDefault(key(player), ToolMode.REMOVE);
  }

  public boolean isInPlaceMode(Player player) {
    return getToolMode(player) == ToolMode.PLACE;
  }

  public void setModeForPlayer(Player player, boolean placeMode) {
    setToolMode(player, placeMode ? ToolMode.PLACE : ToolMode.REMOVE);
  }

  public void setToolMode(Player player, ToolMode mode) {
    playerMode.put(key(player), mode);
    savePlayers();
    plugin.getVoxelPreviewManager().clearLive(player);
    setStatus(player, mode.label());
    showSidebar(player);
  }

  public void cycleToolMode(Player player) {
    String id = key(player);
    long now = System.currentTimeMillis();
    Long last = modeCooldown.get(id);
    if (last != null && now - last < 180L) {
      return;
    }
    modeCooldown.put(id, now);

    setToolMode(player, getToolMode(player).next());
  }

  public void openCurrentSettings(Player player) {
    ToolMode mode = getToolMode(player);

    if (mode == ToolMode.DETAIL) {
      plugin.getDetailToolListener().open(player);
      return;
    }

    if (mode == ToolMode.PLACE
        || mode == ToolMode.REMOVE
        || mode == ToolMode.REPLACE
        || mode == ToolMode.PASTE
        || mode == ToolMode.BRUSH
        || mode == ToolMode.SET
        || mode == ToolMode.MOVE
        || mode == ToolMode.ROTATE
        || mode == ToolMode.SCALE) {
      blockGUI.openGUI(player);
      return;
    }

    if (mode == ToolMode.LOCK) {
      boolean enabled = !isLockOutlineEnabled(player);
      lockOutline.put(key(player), enabled);
      savePlayers();
      setStatus(player, "Lock outline: " + (enabled ? "On" : "Off"));
      return;
    }

    if (mode == ToolMode.UNDO) {
      plugin.getVoxelEditHistory().undo(player);
      return;
    }

    if (mode == ToolMode.REDO) {
      plugin.getVoxelEditHistory().redo(player);
      return;
    }

    setStatus(player, mode.label() + " uses left click");
  }

  public void setPlayerSelectedBlock(Player player, String material) {
    playerSelectedBlock.put(key(player), material);
    playerBlockMetadata.remove(key(player));
    savePlayers();
  }

  public void setPlayerSelectedBlockWithMeta(Player player, String material, ItemStack metadata) {
    playerSelectedBlock.put(key(player), material);
    playerBlockMetadata.put(key(player), metadata.clone());
    savePlayers();
  }

  public String getPlayerSelectedBlock(Player player) {
    return playerSelectedBlock.getOrDefault(key(player), "STONE");
  }

  public ItemStack getPlayerBlockMetadata(Player player) {
    return playerBlockMetadata.get(key(player));
  }

  public void clearPlayerMode(Player player) {
    playerMode.remove(key(player));
    playerSelectedBlock.remove(key(player));
    playerBlockMetadata.remove(key(player));
    savePlayers();
  }

  public int getPlayerVoxelScale(Player player) {
    return playerVoxelScale.getOrDefault(key(player), 4);
  }

  public void cyclePlayerVoxelScale(Player player) {
    cyclePlayerVoxelScale(player, 1);
  }

  public void cyclePlayerVoxelScale(Player player, int direction) {
    int[] values = {2, 4, 8, 16};
    int index =
        java.util.Arrays.stream(values).boxed().toList().indexOf(getPlayerVoxelScale(player));
    int next = values[Math.floorMod(index + direction, values.length)];

    playerVoxelScale.put(key(player), next);
    savePlayers();
    setStatus(player, "Scale: " + next + "x");
  }

  public void setPlayerVoxelScale(Player player, int scale) {
    if (scale != 2 && scale != 4 && scale != 8 && scale != 16) {
      scale = 4;
    }

    playerVoxelScale.put(key(player), scale);
    savePlayers();
    setStatus(player, "Scale: " + scale + "x");
  }

  public boolean isPlacementPreviewEnabled(Player player) {
    return placementPreview.getOrDefault(key(player), true);
  }

  public void setPlacementPreview(Player player, boolean enabled) {
    placementPreview.put(key(player), enabled);
    savePlayers();
    setStatus(player, "Preview: " + (enabled ? "On" : "Off"));
  }

  public boolean isLockOutlineEnabled(Player player) {
    return lockOutline.getOrDefault(key(player), true);
  }

  public void setStatus(Player player, String message) {
    String id = key(player);
    String current = sidebarStatus.get(id);
    Long until = sidebarStatusUntil.get(id);
    if (message.equals(current) && until != null && until > System.currentTimeMillis() + 1000L) {
      return;
    }
    sidebarStatus.put(id, message);
    sidebarStatusUntil.put(id, System.currentTimeMillis() + 2500L);
    player.sendActionBar(Component.text(message).color(NamedTextColor.GREEN));
    showSidebar(player);
  }

  public int getBrushRadius(Player player) {
    return brushRadius.getOrDefault(key(player), 1);
  }

  public void cycleBrushRadius(Player player) {
    cycleBrushRadius(player, 1);
  }

  public void cycleBrushRadius(Player player, int direction) {
    int max = Math.max(1, plugin.getConfig().getInt("limits.brush-radius", 3));
    int next = Math.floorMod(getBrushRadius(player) - 1 + direction, max) + 1;
    brushRadius.put(key(player), next);
    savePlayers();
    setStatus(player, "Brush radius " + next);
  }

  public boolean isBrushRound(Player player) {
    return brushRound.getOrDefault(key(player), true);
  }

  public void toggleBrushRound(Player player) {
    boolean next = !isBrushRound(player);
    brushRound.put(key(player), next);
    savePlayers();
    setStatus(player, next ? "Brush sphere" : "Brush voxel box");
  }

  public boolean isBrushSmooth(Player player) {
    return brushSmooth.getOrDefault(key(player), false);
  }

  public void toggleBrushSmooth(Player player) {
    boolean next = !isBrushSmooth(player);
    brushSmooth.put(key(player), next);
    savePlayers();
    setStatus(player, "Smooth: " + (next ? "On" : "Off"));
  }

  public boolean isBrushMask(Player player) {
    return brushMask.getOrDefault(key(player), true);
  }

  public void toggleBrushMask(Player player) {
    boolean next = !isBrushMask(player);
    brushMask.put(key(player), next);
    savePlayers();
    setStatus(player, "Brush Global Mask: " + (next ? "On" : "Off"));
  }

  public boolean isSetLine(Player player) {
    return setLine.getOrDefault(key(player), false);
  }

  public void toggleSetLine(Player player) {
    boolean next = !isSetLine(player);
    setLine.put(key(player), next);
    savePlayers();
    setStatus(player, next ? "Set line" : "Set fill");
  }

  public boolean isSetMask(Player player) {
    return setMask.getOrDefault(key(player), true);
  }

  public void toggleSetMask(Player player) {
    boolean next = !isSetMask(player);
    setMask.put(key(player), next);
    savePlayers();
    setStatus(player, "Set Global Mask: " + (next ? "On" : "Off"));
  }

  public boolean isSetKeepCorner(Player player) {
    return setKeepCorner.getOrDefault(key(player), false);
  }

  public void toggleSetKeepCorner(Player player) {
    boolean next = !isSetKeepCorner(player);
    setKeepCorner.put(key(player), next);
    savePlayers();
    setStatus(player, "Set corner: " + (next ? "Keep" : "Clear"));
  }

  public boolean isRemoveMass(Player player) {
    return removeMass.getOrDefault(key(player), false);
  }

  public void toggleRemoveMass(Player player) {
    boolean next = !isRemoveMass(player);
    removeMass.put(key(player), next);
    savePlayers();
    setStatus(player, "Mass remove: " + (next ? "On" : "Off"));
  }

  public boolean isRemoveKeepCorner(Player player) {
    return removeKeepCorner.getOrDefault(key(player), false);
  }

  public void toggleRemoveKeepCorner(Player player) {
    boolean next = !isRemoveKeepCorner(player);
    removeKeepCorner.put(key(player), next);
    savePlayers();
    setStatus(player, "Remove corner: " + (next ? "Keep" : "Clear"));
  }

  public boolean isPasteMask(Player player) {
    return pasteMask.getOrDefault(key(player), true);
  }

  public void togglePasteMask(Player player) {
    boolean next = !isPasteMask(player);
    pasteMask.put(key(player), next);
    savePlayers();
    setStatus(player, "Global Mask: " + (next ? "On" : "Off"));
  }

  public String getReplaceFromBlock(Player player) {
    return replaceFromBlock.getOrDefault(key(player), "");
  }

  public String getReplaceToBlock(Player player) {
    return replaceToBlock.getOrDefault(key(player), getPlayerSelectedBlock(player));
  }

  public void clearReplaceFromBlock(Player player) {
    replaceFromBlock.remove(key(player));
    savePlayers();
    setStatus(player, "Replace from: Any");
  }

  public void setReplaceFromBlock(Player player, String material) {
    replaceFromBlock.put(key(player), material);
    savePlayers();
    setStatus(player, "Replace from: " + shortBlockName(material));
  }

  public void setReplaceToBlock(Player player, String material) {
    replaceToBlock.put(key(player), material);
    savePlayers();
    setStatus(player, "Replace to: " + shortBlockName(material));
  }

  public void armReplacePicker(Player player, String target) {
    replacePicker.put(key(player), target);
    setStatus(player, "Pick replace " + target);
  }

  public String consumeReplacePicker(Player player) {
    return replacePicker.remove(key(player));
  }

  public void rotatePaste(Player player) {
    if (plugin.getVoxelClipboard().rotateY(player)) {
      plugin.getVoxelPreviewManager().clearLive(player);
      setStatus(player, "Paste: rotated");
    } else {
      setStatus(player, "Copy something first");
    }
  }

  public void flipPaste(Player player, String axis) {
    if (plugin.getVoxelClipboard().flip(player, axis)) {
      plugin.getVoxelPreviewManager().clearLive(player);
      setStatus(player, "Paste: flipped " + axis.toUpperCase());
    } else {
      setStatus(player, "Copy something first");
    }
  }

  public int getToolHeight(Player player) {
    return toolHeight.getOrDefault(key(player), 1);
  }

  public String getToolAxis(Player player) {
    return toolAxis.getOrDefault(key(player), "y");
  }

  public String getRotateAxis(Player player) {
    return rotateAxis.getOrDefault(key(player), "y");
  }

  public int getRotateAngle(Player player) {
    return rotateAngle.getOrDefault(key(player), 45);
  }

  public double getRotateScale(Player player) {
    return rotateScale.getOrDefault(key(player), 1.0);
  }

  public void cycleRotateAxis(Player player, int direction) {
    List<String> values = List.of("x", "y", "z");
    rotateAxis.put(
        key(player),
        values.get(
            Math.floorMod(values.indexOf(getRotateAxis(player)) + direction, values.size())));
    savePlayers();
  }

  public void setRotateAxis(Player player, String axis) {
    String normalized = axis.toLowerCase();
    if (!List.of("x", "y", "z").contains(normalized)) return;
    rotateAxis.put(key(player), normalized);
    savePlayers();
  }

  public void cycleRotateAngle(Player player, int direction) {
    List<Integer> values = List.of(15, 30, 45, 90);
    rotateAngle.put(
        key(player),
        values.get(
            Math.floorMod(values.indexOf(getRotateAngle(player)) + direction, values.size())));
    savePlayers();
  }

  public void cycleRotateScale(Player player, int direction) {
    List<Double> values = List.of(0.5, 0.75, 1.0, 1.5, 2.0);
    rotateScale.put(
        key(player),
        values.get(
            Math.floorMod(values.indexOf(getRotateScale(player)) + direction, values.size())));
    savePlayers();
  }

  public boolean isMoveLocalSpace(Player player) {
    return moveLocalSpace.getOrDefault(key(player), false);
  }

  public int getMoveStep(Player player) {
    return moveStep.getOrDefault(key(player), 1);
  }

  public void cycleMoveStep(Player player, int direction) {
    List<Integer> values = List.of(1, 2, 4, 8, 16);
    moveStep.put(
        key(player),
        values.get(Math.floorMod(values.indexOf(getMoveStep(player)) + direction, values.size())));
    savePlayers();
  }

  public void toggleMoveSpace(Player player) {
    moveLocalSpace.put(key(player), !isMoveLocalSpace(player));
    savePlayers();
  }

  public String getScaleAxis(Player player) {
    return scaleAxis.getOrDefault(key(player), "uniform");
  }

  public double getScaleStep(Player player) {
    return scaleStep.getOrDefault(key(player), 0.25);
  }

  public void cycleScaleAxis(Player player, int direction) {
    List<String> values = List.of("uniform", "x", "y", "z");
    scaleAxis.put(
        key(player),
        values.get(Math.floorMod(values.indexOf(getScaleAxis(player)) + direction, values.size())));
    savePlayers();
  }

  public void cycleScaleStep(Player player, int direction) {
    List<Double> values = List.of(0.05, 0.1, 0.25, 0.5, 1.0);
    scaleStep.put(
        key(player),
        values.get(Math.floorMod(values.indexOf(getScaleStep(player)) + direction, values.size())));
    savePlayers();
  }

  public void adjustToolHeight(Player player, int delta) {
    int max = Math.max(1, plugin.getConfig().getInt("limits.tool-height", 16));
    int next = Math.max(1, Math.min(max, getToolHeight(player) + delta));
    toolHeight.put(key(player), next);
    savePlayers();
    plugin.getVoxelPreviewManager().clearLive(player);
    setStatus(player, "Height: " + next);
  }

  public void cycleToolAxis(Player player) {
    cycleToolAxis(player, 1);
  }

  public void cycleToolAxis(Player player, int direction) {
    java.util.List<String> values = java.util.List.of("x", "y", "z");
    String next =
        values.get(Math.floorMod(values.indexOf(getToolAxis(player)) + direction, values.size()));
    toolAxis.put(key(player), next);
    savePlayers();
    plugin.getVoxelPreviewManager().clearLive(player);
    setStatus(player, "Direction: " + next.toUpperCase());
  }

  private void startSidebarTask() {
    Bukkit.getScheduler()
        .runTaskTimer(
            plugin,
            () -> {
              for (Player player : Bukkit.getOnlinePlayers()) {
                if (plugin
                    .getChiselManager()
                    .isChiselAxe(player.getInventory().getItemInMainHand())) {
                  showSidebar(player);
                } else if (player.getScoreboard().getObjective("smallvoxels") != null) {
                  lastSidebarLines.remove(key(player));
                  player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                }
              }
            },
            20L,
            20L);
  }

  private void showSidebar(Player player) {
    ToolMode mode = getToolMode(player);
    String status = currentStatus(player);
    List<String> lines = new ArrayList<>();
    lines.add(
        modeLine(ToolMode.PLACE, mode)
            + ChatColor.GRAY
            + "  "
            + modeLine(ToolMode.REMOVE, mode)
            + ChatColor.GRAY
            + "  "
            + modeLine(ToolMode.DETAIL, mode));
    lines.add(
        modeLine(ToolMode.REPLACE, mode)
            + ChatColor.GRAY
            + "  "
            + modeLine(ToolMode.BRUSH, mode)
            + ChatColor.GRAY
            + "  "
            + modeLine(ToolMode.SET, mode));
    lines.add(
        modeLine(ToolMode.MOVE, mode)
            + ChatColor.GRAY
            + "  "
            + modeLine(ToolMode.ROTATE, mode)
            + ChatColor.GRAY
            + "  "
            + modeLine(ToolMode.SCALE, mode));
    lines.add(
        modeLine(ToolMode.COPY, mode)
            + ChatColor.GRAY
            + "  "
            + modeLine(ToolMode.PASTE, mode)
            + ChatColor.GRAY
            + "  "
            + modeLine(ToolMode.LOCK, mode));
    lines.add(
        modeLine(ToolMode.UNDO, mode) + ChatColor.GRAY + "  " + modeLine(ToolMode.REDO, mode));
    lines.add(blankLine(11));
    lines.add(settingsLine(player, mode));
    lines.add(ChatColor.GOLD + "Scale: " + ChatColor.WHITE + getPlayerVoxelScale(player) + "x");
    lines.add(
        ChatColor.GREEN
            + "Block: "
            + ChatColor.WHITE
            + shortBlockName(getPlayerSelectedBlock(player)));
    lines.add(
        ChatColor.AQUA
            + "Preview: "
            + ChatColor.WHITE
            + (isPlacementPreviewEnabled(player) ? "On" : "Off")
            + ChatColor.GRAY
            + " Height: "
            + ChatColor.WHITE
            + getToolHeight(player)
            + ChatColor.GRAY
            + " "
            + getToolAxis(player).toUpperCase());
    lines.add(
        ChatColor.AQUA
            + "Lock ESP: "
            + ChatColor.WHITE
            + (isLockOutlineEnabled(player) ? "On" : "Off"));
    lines.add(blankLine(5));
    lines.add(status == null ? blankLine(4) : ChatColor.YELLOW + trim(status, 32));
    lines.add(ChatColor.GRAY + "Drop Item: Mode");
    lines.add(ChatColor.GRAY + "Place Block: Scale");
    lines.add(ChatColor.GRAY + "Switch Hand: Settings");

    if (lines.equals(lastSidebarLines.get(key(player)))
        && player.getScoreboard().getObjective("smallvoxels") != null) {
      return;
    }
    lastSidebarLines.put(key(player), lines);

    Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
    Objective objective =
        board.registerNewObjective("smallvoxels", "dummy", ChatColor.GREEN + "SmallVoxels");
    objective.setDisplaySlot(DisplaySlot.SIDEBAR);

    int score = 15;
    for (String line : lines) {
      addLine(objective, line, score--);
    }

    player.setScoreboard(board);
  }

  private void addLine(Objective objective, String text, int score) {
    objective.getScore(text).setScore(score);
  }

  private String modeLine(ToolMode listed, ToolMode current) {
    return (listed == current ? ChatColor.GREEN + "> " : ChatColor.GRAY + "  ") + listed.label();
  }

  private String settingsLine(Player player, ToolMode mode) {
    if (mode == ToolMode.DETAIL) {
      me.reube.SmallVoxels.detail.DetailManager.Settings settings =
          plugin.getDetailManager().settings(player);
      String selected =
          switch (settings.mode) {
            case BRUSH -> settings.brush.name();
            case TRIM -> settings.material.name();
            case STAMP -> settings.preset.name();
            case DAMAGE -> "DAMAGE";
            case OVERGROW -> "OVERGROWTH";
            case OFF -> "MOSS";
          };
      return ChatColor.AQUA
          + "Detail: "
          + ChatColor.WHITE
          + shortBlockName(selected)
          + ChatColor.GRAY
          + " "
          + ChatColor.WHITE
          + settings.resolution
          + "x"
          + ChatColor.GRAY
          + " L:"
          + ChatColor.WHITE
          + settings.layer
          + ChatColor.GRAY
          + " I:"
          + ChatColor.WHITE
          + String.format(java.util.Locale.ROOT, "%.2f", settings.intensity);
    }
    if (mode == ToolMode.BRUSH) {
      return ChatColor.AQUA
          + "Brush: "
          + ChatColor.WHITE
          + getBrushRadius(player)
          + ChatColor.GRAY
          + " "
          + (isBrushSmooth(player) ? "Smooth" : (isBrushRound(player) ? "Sphere" : "Voxel Box"))
          + ChatColor.GRAY
          + " Global Mask: "
          + ChatColor.WHITE
          + (isBrushMask(player) ? "On" : "Off");
    }
    if (mode == ToolMode.REMOVE) {
      return ChatColor.AQUA
          + "Mass: "
          + ChatColor.WHITE
          + (isRemoveMass(player) ? "On" : "Off")
          + ChatColor.GRAY
          + " Corner: "
          + ChatColor.WHITE
          + (isRemoveKeepCorner(player) ? "Keep" : "Clear");
    }
    if (mode == ToolMode.SET) {
      return ChatColor.AQUA
          + "Set: "
          + ChatColor.WHITE
          + (isSetLine(player) ? "Line" : "Fill")
          + ChatColor.GRAY
          + " Global Mask: "
          + ChatColor.WHITE
          + (isSetMask(player) ? "On" : "Off")
          + ChatColor.GRAY
          + " Corner: "
          + ChatColor.WHITE
          + (isSetKeepCorner(player) ? "Keep" : "Clear");
    }
    if (mode == ToolMode.REPLACE) {
      String from = getReplaceFromBlock(player);
      return ChatColor.AQUA
          + "Replace: "
          + ChatColor.WHITE
          + (from.isBlank() ? "Any" : shortBlockName(from))
          + ChatColor.GRAY
          + " to "
          + ChatColor.WHITE
          + shortBlockName(getReplaceToBlock(player));
    }
    if (mode == ToolMode.PASTE) {
      return ChatColor.AQUA
          + "Global Mask: "
          + ChatColor.WHITE
          + (isPasteMask(player) ? "On" : "Off");
    }
    if (mode == ToolMode.MOVE) {
      return ChatColor.AQUA
          + "Move: "
          + ChatColor.WHITE
          + (isMoveLocalSpace(player) ? "Local" : "World")
          + " axes";
    }
    if (mode == ToolMode.ROTATE) {
      return ChatColor.AQUA
          + "Rotate: "
          + ChatColor.WHITE
          + getRotateAxis(player).toUpperCase()
          + ChatColor.GRAY
          + " "
          + getRotateAngle(player)
          + " degrees";
    }
    if (mode == ToolMode.SCALE) {
      return ChatColor.AQUA
          + "Scale: "
          + ChatColor.WHITE
          + getScaleAxis(player).toUpperCase()
          + ChatColor.GRAY
          + " step "
          + getScaleStep(player);
    }
    if (mode == ToolMode.LOCK) {
      return ChatColor.AQUA
          + "Locked ESP: "
          + ChatColor.WHITE
          + (isLockOutlineEnabled(player) ? "On" : "Off");
    }
    return blankLine(10);
  }

  private String blankLine(int score) {
    return ChatColor.DARK_GRAY.toString() + ChatColor.values()[score].toString();
  }

  private boolean canUseChisel(Player player) {
    return player.hasPermission("smallvoxels.voxel.use")
        || player.hasPermission("smallvoxels.chisel.use");
  }

  private String shortBlockName(String material) {
    String name = material.replace("_", " ").toLowerCase();
    return name.length() > 18 ? name.substring(0, 18) : name;
  }

  private String trim(String text, int max) {
    return text.length() > max ? text.substring(0, max) : text;
  }

  private String currentStatus(Player player) {
    String id = key(player);
    Long until = sidebarStatusUntil.get(id);
    if (until == null || until < System.currentTimeMillis()) {
      return null;
    }

    String status = sidebarStatus.get(id);
    return status == null || status.isBlank() ? null : trim(status, 28);
  }

  private String key(Player player) {
    return player.getUniqueId().toString();
  }

  private String itemToText(ItemStack item) {
    try {
      YamlConfiguration config = new YamlConfiguration();
      config.set("item", item);
      return Base64.getEncoder()
          .encodeToString(config.saveToString().getBytes(StandardCharsets.UTF_8));
    } catch (Exception exception) {
      plugin.getLogger().fine("Could not serialize a saved tool item: " + exception.getMessage());
      return null;
    }
  }

  private ItemStack itemFromText(String text) {
    try {
      String yaml = new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
      YamlConfiguration config = new YamlConfiguration();
      config.loadFromString(yaml);
      return config.getItemStack("item");
    } catch (Exception exception) {
      plugin.getLogger().fine("Could not read a saved tool item: " + exception.getMessage());
      return null;
    }
  }

  private void loadPlayers() {
    if (!playerFile.exists()) {
      return;
    }

    try (var reader = Files.newBufferedReader(playerFile.toPath(), StandardCharsets.UTF_8)) {
      JsonObject root = gson.fromJson(reader, JsonObject.class);
      if (root == null) {
        return;
      }

      for (String id : root.keySet()) {
        JsonObject data = root.getAsJsonObject(id);
        if (data.has("mode")) {
          try {
            playerMode.put(id, ToolMode.valueOf(data.get("mode").getAsString()));
          } catch (IllegalArgumentException ignored) {
            playerMode.put(id, ToolMode.REMOVE);
          }
        }
        if (data.has("block")) {
          playerSelectedBlock.put(id, data.get("block").getAsString());
        }
        if (data.has("blockItem")) {
          ItemStack item = itemFromText(data.get("blockItem").getAsString());
          if (item != null) {
            playerBlockMetadata.put(id, item);
          }
        }
        if (data.has("scale")) {
          playerVoxelScale.put(id, data.get("scale").getAsInt());
        }
        if (data.has("preview")) {
          placementPreview.put(id, data.get("preview").getAsBoolean());
        }
        if (data.has("lockOutline")) {
          lockOutline.put(id, data.get("lockOutline").getAsBoolean());
        }
        if (data.has("brushRadius")) {
          brushRadius.put(id, data.get("brushRadius").getAsInt());
        }
        if (data.has("brushRound")) {
          brushRound.put(id, data.get("brushRound").getAsBoolean());
        }
        if (data.has("brushSmooth")) {
          brushSmooth.put(id, data.get("brushSmooth").getAsBoolean());
        }
        if (data.has("brushMask")) {
          brushMask.put(id, data.get("brushMask").getAsBoolean());
        }
        if (data.has("setLine")) {
          setLine.put(id, data.get("setLine").getAsBoolean());
        }
        if (data.has("setMask")) {
          setMask.put(id, data.get("setMask").getAsBoolean());
        }
        if (data.has("setKeepCorner")) {
          setKeepCorner.put(id, data.get("setKeepCorner").getAsBoolean());
        }
        if (data.has("removeMass")) {
          removeMass.put(id, data.get("removeMass").getAsBoolean());
        }
        if (data.has("removeKeepCorner")) {
          removeKeepCorner.put(id, data.get("removeKeepCorner").getAsBoolean());
        }
        if (data.has("pasteMask")) {
          pasteMask.put(id, data.get("pasteMask").getAsBoolean());
        }
        if (data.has("toolHeight")) {
          toolHeight.put(id, data.get("toolHeight").getAsInt());
        }
        if (data.has("toolAxis")) {
          toolAxis.put(id, data.get("toolAxis").getAsString());
        }
        if (data.has("rotateAxis")) rotateAxis.put(id, data.get("rotateAxis").getAsString());
        if (data.has("rotateAngle")) rotateAngle.put(id, data.get("rotateAngle").getAsInt());
        if (data.has("rotateScale")) rotateScale.put(id, data.get("rotateScale").getAsDouble());
        if (data.has("moveLocalSpace"))
          moveLocalSpace.put(id, data.get("moveLocalSpace").getAsBoolean());
        if (data.has("moveStep")) moveStep.put(id, data.get("moveStep").getAsInt());
        if (data.has("scaleAxis")) scaleAxis.put(id, data.get("scaleAxis").getAsString());
        if (data.has("scaleStep")) scaleStep.put(id, data.get("scaleStep").getAsDouble());
        if (data.has("replaceFrom")) {
          replaceFromBlock.put(id, data.get("replaceFrom").getAsString());
        }
        if (data.has("replaceTo")) {
          replaceToBlock.put(id, data.get("replaceTo").getAsString());
        }
      }
    } catch (IOException exception) {
      plugin.getLogger().warning("Could not load player tool settings: " + exception.getMessage());
    }
  }

  private void savePlayers() {
    try {
      Files.createDirectories(plugin.getDataFolder().toPath());
    } catch (IOException exception) {
      plugin
          .getLogger()
          .warning("Could not create the plugin data folder: " + exception.getMessage());
      return;
    }

    JsonObject root = new JsonObject();
    for (String id : playerMode.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("mode", playerMode.get(id).name());
      root.add(id, data);
    }
    for (String id : playerSelectedBlock.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("block", playerSelectedBlock.get(id));
      root.add(id, data);
    }
    for (String id : playerBlockMetadata.keySet()) {
      String itemText = itemToText(playerBlockMetadata.get(id));
      if (itemText == null) {
        continue;
      }
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("blockItem", itemText);
      root.add(id, data);
    }
    for (String id : playerVoxelScale.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("scale", playerVoxelScale.get(id));
      root.add(id, data);
    }
    for (String id : placementPreview.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("preview", placementPreview.get(id));
      root.add(id, data);
    }
    for (String id : lockOutline.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("lockOutline", lockOutline.get(id));
      root.add(id, data);
    }
    for (String id : brushRadius.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("brushRadius", brushRadius.get(id));
      root.add(id, data);
    }
    for (String id : brushRound.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("brushRound", brushRound.get(id));
      root.add(id, data);
    }
    for (String id : brushSmooth.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("brushSmooth", brushSmooth.get(id));
      root.add(id, data);
    }
    for (String id : brushMask.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("brushMask", brushMask.get(id));
      root.add(id, data);
    }
    for (String id : setLine.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("setLine", setLine.get(id));
      root.add(id, data);
    }
    for (String id : setMask.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("setMask", setMask.get(id));
      root.add(id, data);
    }
    for (String id : setKeepCorner.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("setKeepCorner", setKeepCorner.get(id));
      root.add(id, data);
    }
    for (String id : removeMass.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("removeMass", removeMass.get(id));
      root.add(id, data);
    }
    for (String id : removeKeepCorner.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("removeKeepCorner", removeKeepCorner.get(id));
      root.add(id, data);
    }
    for (String id : pasteMask.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("pasteMask", pasteMask.get(id));
      root.add(id, data);
    }
    for (String id : toolHeight.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("toolHeight", toolHeight.get(id));
      root.add(id, data);
    }
    for (String id : toolAxis.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("toolAxis", toolAxis.get(id));
      root.add(id, data);
    }
    for (String id : rotateAxis.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("rotateAxis", rotateAxis.get(id));
      root.add(id, data);
    }
    for (String id : rotateAngle.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("rotateAngle", rotateAngle.get(id));
      root.add(id, data);
    }
    for (String id : rotateScale.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("rotateScale", rotateScale.get(id));
      root.add(id, data);
    }
    for (String id : moveLocalSpace.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("moveLocalSpace", moveLocalSpace.get(id));
      root.add(id, data);
    }
    for (String id : moveStep.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("moveStep", moveStep.get(id));
      root.add(id, data);
    }
    for (String id : scaleAxis.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("scaleAxis", scaleAxis.get(id));
      root.add(id, data);
    }
    for (String id : scaleStep.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("scaleStep", scaleStep.get(id));
      root.add(id, data);
    }
    for (String id : replaceFromBlock.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("replaceFrom", replaceFromBlock.get(id));
      root.add(id, data);
    }
    for (String id : replaceToBlock.keySet()) {
      JsonObject data = root.has(id) ? root.getAsJsonObject(id) : new JsonObject();
      data.addProperty("replaceTo", replaceToBlock.get(id));
      root.add(id, data);
    }

    try (var writer = Files.newBufferedWriter(playerFile.toPath(), StandardCharsets.UTF_8)) {
      gson.toJson(root, writer);
    } catch (IOException exception) {
      plugin.getLogger().warning("Could not save player tool settings: " + exception.getMessage());
    }
  }
}
