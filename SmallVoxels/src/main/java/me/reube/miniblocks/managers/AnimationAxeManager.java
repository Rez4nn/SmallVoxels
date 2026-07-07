package me.reube.SmallVoxels.managers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.reube.SmallVoxels.SmallVoxels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

public class AnimationAxeManager {
  private final SmallVoxels plugin;
  private final Map<String, AnimationAxeMode> modes = new HashMap<>();
  private final Map<String, List<String>> nearbyAnimationIds = new HashMap<>();
  private final Map<String, Integer> nearbyAnimationIndex = new HashMap<>();
  private final Map<String, Boolean> editing = new HashMap<>();
  private final Map<String, List<String>> lastSidebarLines = new HashMap<>();

  public AnimationAxeManager(SmallVoxels plugin) {
    this.plugin = plugin;
    startSidebarTask();
  }

  public ItemStack createAnimationAxe() {
    ItemStack axe = new ItemStack(Material.GOLDEN_AXE);
    ItemMeta meta = axe.getItemMeta();
    if (meta != null) {
      meta.displayName(
          Component.text("Animation Axe").color(NamedTextColor.AQUA).decorate(TextDecoration.BOLD));
      meta.lore(
          List.of(
              Component.text("Q: switch animation mode").color(NamedTextColor.GRAY),
              Component.text("F: open animation settings").color(NamedTextColor.GRAY),
              Component.text("Left-click: use current animation mode").color(NamedTextColor.GRAY)));
      meta.setUnbreakable(true);
      axe.setItemMeta(meta);
    }
    return axe;
  }

  public boolean isAnimationAxe(ItemStack item) {
    if (item == null || !item.hasItemMeta()) {
      return false;
    }
    ItemMeta meta = item.getItemMeta();
    if (meta == null || meta.displayName() == null) {
      return false;
    }
    return meta.displayName()
        .equals(
            Component.text("Animation Axe")
                .color(NamedTextColor.AQUA)
                .decorate(TextDecoration.BOLD));
  }

  public void giveAnimationAxe(Player player) {
    if (player.getInventory().firstEmpty() == -1) {
      player.sendMessage(Component.text("Your inventory is full!").color(NamedTextColor.RED));
      return;
    }
    player.getInventory().addItem(createAnimationAxe());
    player.sendMessage(
        Component.text("You received the Animation Axe!").color(NamedTextColor.AQUA));
  }

  public AnimationAxeMode mode(Player player) {
    return modes.getOrDefault(key(player), AnimationAxeMode.SELECT_ANIMATION);
  }

  public void cycleMode(Player player) {
    var object = plugin.getAnimatedObjectManager().editorObject(player);
    boolean wind = object != null && "WIND".equalsIgnoreCase(object.objectType);
    AnimationAxeMode next = mode(player).next(wind);
    modes.put(key(player), next);
    NamedTextColor color = isEditing(player) ? NamedTextColor.GREEN : NamedTextColor.RED;
    player.sendActionBar(
        Component.text(
                "Animation: " + next.label() + " | Edit " + (isEditing(player) ? "ON" : "OFF"))
            .color(color));
    showSidebar(player);
  }

  public boolean isEditing(Player player) {
    return editing.getOrDefault(key(player), false);
  }

  public boolean toggleEditing(Player player) {
    boolean next = !isEditing(player);
    editing.put(key(player), next);
    player.sendActionBar(
        Component.text(next ? "Animation editing ON" : "Animation editing OFF")
            .color(next ? NamedTextColor.GREEN : NamedTextColor.RED));
    showSidebar(player);
    return next;
  }

  public void setEditing(Player player, boolean enabled) {
    editing.put(key(player), enabled);
    player.sendActionBar(
        Component.text(enabled ? "Animation editing ON" : "Animation editing OFF")
            .color(enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
    showSidebar(player);
  }

  public List<String> refreshNearbyAnimations(Player player) {
    List<String> ids = new ArrayList<>();
    Location location = player.getLocation();
    double radius = 10.0 * 16.0;
    double radiusSquared = radius * radius;
    for (me.reube.SmallVoxels.managers.animation.AnimatedVoxelObject object :
        plugin.getAnimatedObjectManager().storage().all()) {
      if (!player.getWorld().getName().equals(object.world)) {
        continue;
      }
      Location origin =
          new Location(player.getWorld(), object.originX, object.originY, object.originZ);
      if (origin.distanceSquared(location) <= radiusSquared) {
        ids.add(object.id);
      }
    }
    ids.sort(String::compareToIgnoreCase);
    nearbyAnimationIds.put(key(player), ids);
    nearbyAnimationIndex.put(key(player), 0);
    return ids;
  }

  public String currentNearbyAnimation(Player player) {
    List<String> ids = nearbyAnimationIds.get(key(player));
    if (ids == null) {
      ids = refreshNearbyAnimations(player);
    }
    if (ids.isEmpty()) {
      return null;
    }
    int index =
        Math.max(0, Math.min(nearbyAnimationIndex.getOrDefault(key(player), 0), ids.size() - 1));
    return ids.get(index);
  }

  public String cycleNearbyAnimation(Player player) {
    List<String> ids = nearbyAnimationIds.get(key(player));
    boolean refreshed = false;
    if (ids == null) {
      ids = refreshNearbyAnimations(player);
      refreshed = true;
    }
    if (ids.isEmpty()) {
      return null;
    }
    if (refreshed) {
      nearbyAnimationIndex.put(key(player), 0);
      return ids.get(0);
    }
    int next = (nearbyAnimationIndex.getOrDefault(key(player), 0) + 1) % ids.size();
    nearbyAnimationIndex.put(key(player), next);
    return ids.get(next);
  }

  private String key(Player player) {
    return player.getUniqueId().toString();
  }

  private void startSidebarTask() {
    Bukkit.getScheduler()
        .runTaskTimer(
            plugin,
            () -> {
              for (Player player : Bukkit.getOnlinePlayers()) {
                if (isAnimationAxe(player.getInventory().getItemInMainHand())) {
                  showSidebar(player);
                } else if (player.getScoreboard().getObjective("smallvoxanim") != null) {
                  lastSidebarLines.remove(key(player));
                  player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                }
              }
            },
            20L,
            20L);
  }

  public void showSidebar(Player player) {
    AnimationAxeMode current = mode(player);
    String selected =
        plugin.getAnimatedObjectManager().editorObject(player) == null
            ? "none"
            : plugin.getAnimatedObjectManager().editorObject(player).id;
    me.reube.SmallVoxels.managers.animation.VoxelAnimation animation =
        plugin.getAnimatedObjectManager().editorAnimation(player);
    String state =
        animation == null
            ? "none"
            : (animation.displayName == null ? animation.id : animation.displayName);
    me.reube.SmallVoxels.managers.animation.VoxelAnimation currentState =
        plugin.getAnimatedObjectManager().editorAnimation(player);
    String capture = currentState == null ? "none" : currentState.defaultTransition;
    boolean wind =
        plugin.getAnimatedObjectManager().editorObject(player) != null
            && "WIND"
                .equalsIgnoreCase(
                    plugin.getAnimatedObjectManager().editorObject(player).objectType);
    List<String> lines =
        List.of(
            modeLine(AnimationAxeMode.SELECT_ANIMATION, current, true)
                + ChatColor.GRAY
                + "  "
                + modeLine(AnimationAxeMode.EDIT_TOGGLE, current, true),
            modeLine(AnimationAxeMode.CAPTURE_FRAME, current, true)
                + ChatColor.GRAY
                + "  "
                + modeLine(AnimationAxeMode.IMAGE_FRAME, current, true),
            modeLine(AnimationAxeMode.SEQUENCE, current, true)
                + ChatColor.GRAY
                + "  "
                + modeLine(AnimationAxeMode.PLAY, current, true),
            modeLine(AnimationAxeMode.WIND_CONNECTOR, current, wind)
                + ChatColor.GRAY
                + "  "
                + modeLine(AnimationAxeMode.WIND_NODE, current, wind),
            blankLine(8),
            isEditing(player) ? ChatColor.GREEN + "Edit: ON" : ChatColor.RED + "Edit: OFF",
            ChatColor.AQUA + "Object: " + ChatColor.WHITE + trim(selected, 18),
            ChatColor.AQUA + "Type: " + ChatColor.WHITE + (wind ? "Wind" : "Normal"),
            ChatColor.AQUA + "State: " + ChatColor.WHITE + trim(state, 18),
            ChatColor.AQUA + "Capture: " + ChatColor.WHITE + capture,
            ChatColor.GRAY + "Q: Mode",
            ChatColor.GRAY + "F: Settings");
    if (lines.equals(lastSidebarLines.get(key(player)))
        && player.getScoreboard().getObjective("smallvoxanim") != null) {
      return;
    }
    lastSidebarLines.put(key(player), lines);

    Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
    Objective objective =
        board.registerNewObjective("smallvoxanim", "dummy", ChatColor.AQUA + "SmallVoxels Anim");
    objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    int score = 12;
    for (String line : lines) {
      addLine(objective, line, score);
      score--;
    }
    player.setScoreboard(board);
  }

  private String modeLine(AnimationAxeMode listed, AnimationAxeMode current) {
    return (listed == current ? ChatColor.GREEN + "> " : ChatColor.GRAY + "  ") + listed.label();
  }

  private String modeLine(AnimationAxeMode listed, AnimationAxeMode current, boolean available) {
    if (!available) {
      return ChatColor.DARK_GRAY + "x " + listed.label();
    }
    return modeLine(listed, current);
  }

  private void addLine(Objective objective, String text, int score) {
    objective.getScore(text).setScore(score);
  }

  private String blankLine(int score) {
    return ChatColor.DARK_GRAY.toString() + ChatColor.values()[score].toString();
  }

  private String trim(String value, int max) {
    return value.length() > max ? value.substring(0, max) : value;
  }
}
