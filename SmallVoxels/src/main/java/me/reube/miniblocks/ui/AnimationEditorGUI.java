package me.reube.SmallVoxels.ui;

import java.util.List;
import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.managers.AnimatedObjectManager;
import me.reube.SmallVoxels.managers.animation.AnimatedVoxelObject;
import me.reube.SmallVoxels.managers.animation.VoxelAnimation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class AnimationEditorGUI {
  public static final Component TITLE =
      Component.text("SmallVoxels Animation").color(NamedTextColor.AQUA);

  private final SmallVoxels plugin;

  public AnimationEditorGUI(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  public void open(Player player) {
    Inventory gui = Bukkit.createInventory(null, 36, TITLE);
    AnimatedObjectManager.EditorState state = plugin.getAnimatedObjectManager().editor(player);
    AnimatedVoxelObject object = plugin.getAnimatedObjectManager().editorObject(player);
    VoxelAnimation animation = plugin.getAnimatedObjectManager().editorAnimation(player);

    if (object == null || animation == null || state == null) {
      gui.setItem(
          13,
          button(
              Material.EMERALD_BLOCK,
              "New Animation",
              "Type the name in chat",
              "Only you will see the prompt"));
      gui.setItem(
          22,
          button(
              Material.SPYGLASS,
              "Select Nearby",
              "Use Animation Axe select mode",
              "Right-click cycles nearby labels"));
      player.openInventory(gui);
      return;
    }

    gui.setItem(
        0,
        button(
            Material.EMERALD_BLOCK,
            "New Animation",
            "Type the name in chat",
            "Creates an empty label"));
    gui.setItem(
        4,
        button(
            Material.ARMOR_STAND, "Animation", object.id, object.parts.size() + " captured parts"));
    gui.setItem(
        5,
        cycleButton(
            Material.NAME_TAG,
            "State",
            animation.id,
            object.animations.stream()
                .map(
                    value ->
                        value.id
                            + "|"
                            + (value.displayName == null ? "Animation state" : value.displayName))
                .toArray(String[]::new)));
    gui.setItem(
        6,
        cycleButton(
            plugin.getAnimationAxeManager().isEditing(player)
                ? Material.LIME_DYE
                : Material.RED_DYE,
            "Edit Mode",
            plugin.getAnimationAxeManager().isEditing(player) ? "ON" : "OFF",
            "ON|World edits can be captured",
            "OFF|Preview and playback only"));

    gui.setItem(
        11,
        button(
            Material.RED_STAINED_GLASS_PANE,
            "Tick -10",
            String.valueOf(Math.max(0, state.tick - 10)),
            "Shift: -50"));
    gui.setItem(
        12,
        button(
            Material.RED_DYE, "Tick -1", String.valueOf(Math.max(0, state.tick - 1)), "Shift: -5"));
    gui.setItem(
        13,
        button(
            Material.CLOCK,
            "Tick " + state.tick,
            "Length " + animation.lengthTicks,
            animation.stateKeyframes.size() + " keyframes"));
    gui.setItem(
        14,
        button(
            Material.LIME_DYE,
            "Tick +1",
            String.valueOf(Math.min(animation.lengthTicks, state.tick + 1)),
            "Shift: +5"));
    gui.setItem(
        15,
        button(
            Material.LIME_STAINED_GLASS_PANE,
            "Tick +10",
            String.valueOf(Math.min(animation.lengthTicks, state.tick + 10)),
            "Shift: +50"));

    gui.setItem(
        19,
        button(
            "SOFT".equalsIgnoreCase(animation.defaultTransition)
                ? Material.SLIME_BLOCK
                : Material.IRON_BLOCK,
            "Capture Frame",
            animation.defaultTransition + " / " + animation.captureMode,
            "Saves selected world voxels at this tick"));
    gui.setItem(
        21,
        button(
            Material.NOTE_BLOCK,
            "Play",
            animation.displayName == null ? animation.id : animation.displayName,
            "Preview this state"));
    gui.setItem(22, button(Material.BARRIER, "Stop", object.id, "Stops active playback"));
    gui.setItem(
        23, button(Material.BOOKSHELF, "Keyframes", "Ticks and frame duration", "Manage frames"));
    gui.setItem(
        24,
        button(
            Material.WRITABLE_BOOK,
            "State Settings",
            "Timing, triggers, capture",
            "Open detailed settings"));
    gui.setItem(
        25, button(Material.TNT, "Delete Animation", object.id, "Deletes this saved object"));

    player.openInventory(gui);
  }

  private ItemStack button(Material material, String name, String firstLine, String secondLine) {
    ItemStack item = new ItemStack(material);
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.displayName(Component.text(name).color(NamedTextColor.AQUA));
      meta.lore(
          List.of(
              Component.text(firstLine).color(NamedTextColor.GRAY),
              Component.text(secondLine).color(NamedTextColor.DARK_GRAY)));
      item.setItemMeta(meta);
    }
    return item;
  }

  private ItemStack cycleButton(
      Material material, String name, String selected, String... options) {
    ItemStack item = new ItemStack(material);
    ItemMeta meta = item.getItemMeta();
    meta.displayName(Component.text(name).color(NamedTextColor.AQUA));
    java.util.ArrayList<Component> lore = new java.util.ArrayList<>();
    lore.add(Component.text("Left: next   Right: previous").color(NamedTextColor.DARK_GRAY));
    lore.add(Component.empty());
    for (String option : options) {
      String[] parts = option.split("\\|", 2);
      boolean active = parts[0].equalsIgnoreCase(selected);
      lore.add(
          Component.text((active ? "> " : "  ") + parts[0])
              .color(active ? NamedTextColor.AQUA : NamedTextColor.GRAY));
      if (parts.length > 1)
        lore.add(Component.text("    " + parts[1]).color(NamedTextColor.DARK_GRAY));
    }
    meta.lore(lore);
    item.setItemMeta(meta);
    return item;
  }
}
