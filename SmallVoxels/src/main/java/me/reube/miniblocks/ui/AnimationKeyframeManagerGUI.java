package me.reube.SmallVoxels.ui;

import java.util.List;
import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.managers.animation.AnimatedVoxelObject;
import me.reube.SmallVoxels.managers.animation.VoxelAnimation;
import me.reube.SmallVoxels.managers.animation.VoxelStateKeyframe;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class AnimationKeyframeManagerGUI {
  public static final Component TITLE =
      Component.text("Animation Keyframes").color(NamedTextColor.AQUA);

  private final SmallVoxels plugin;

  public AnimationKeyframeManagerGUI(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  public void open(Player player) {
    Inventory gui = Bukkit.createInventory(null, 54, TITLE);
    AnimatedVoxelObject object = plugin.getAnimatedObjectManager().editorObject(player);
    VoxelAnimation animation = plugin.getAnimatedObjectManager().editorAnimation(player);
    if (object == null || animation == null) {
      gui.setItem(
          22,
          button(
              Material.BARRIER,
              "No Animation Selected",
              "Select or create an animation",
              "F with Animation Axe opens settings"));
      player.openInventory(gui);
      return;
    }

    animation.sort();
    gui.setItem(
        4,
        button(
            Material.NAME_TAG,
            "State: " + (animation.displayName == null ? animation.id : animation.displayName),
            object.id,
            "Click to switch state"));
    gui.setItem(
        5,
        button(
            Material.TRIPWIRE_HOOK,
            "Trigger",
            animation.trigger,
            "Loop: " + (animation.loop ? "on" : "off")));
    gui.setItem(
        6, button(Material.EMERALD, "New State", "Type name in chat", "Creates and selects it"));
    gui.setItem(
        7,
        button(
            Material.COMPARATOR,
            "Priority",
            String.valueOf(animation.priority),
            "Higher wins clashes"));
    gui.setItem(
        8,
        button(
            "ADD".equalsIgnoreCase(animation.captureMode) ? Material.HOPPER : Material.CAULDRON,
            "Save Current Tick",
            animation.defaultTransition + " / " + animation.captureMode,
            "Saves edited world as keyframe"));
    gui.setItem(
        45,
        button(
            Material.PAPER,
            "Create Frame",
            "Duplicate previous, or blank",
            "Creates a new keyframe"));
    int slot = 9;
    for (VoxelStateKeyframe frame : animation.stateKeyframes) {
      if (slot >= 45) {
        break;
      }
      Material material = frame.soft() ? Material.SLIME_BLOCK : Material.IRON_BLOCK;
      gui.setItem(
          slot,
          button(
              material,
              "Tick " + frame.tick,
              frame.transition + " - " + frame.parts.size() + " voxels",
              "Left load, right extend, shift-right delete"));
      slot++;
    }
    gui.setItem(49, button(Material.ARROW, "Back", "Animation settings", "Click to return"));
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
}
