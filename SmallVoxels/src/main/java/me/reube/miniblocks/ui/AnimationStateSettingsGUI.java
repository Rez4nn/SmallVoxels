package me.reube.SmallVoxels.ui;

import java.util.List;
import me.reube.SmallVoxels.SmallVoxels;
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

public class AnimationStateSettingsGUI {
  public static final Component TITLE =
      Component.text("Animation State").color(NamedTextColor.AQUA);

  private final SmallVoxels plugin;

  public AnimationStateSettingsGUI(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  public void open(Player player) {
    Inventory gui = Bukkit.createInventory(null, 36, TITLE);
    AnimatedVoxelObject object = plugin.getAnimatedObjectManager().editorObject(player);
    VoxelAnimation animation = plugin.getAnimatedObjectManager().editorAnimation(player);
    if (object == null || animation == null) {
      gui.setItem(
          13,
          button(
              Material.BARRIER,
              "No State Selected",
              "Select an animation first",
              "Use Animation Axe"));
      player.openInventory(gui);
      return;
    }

    gui.setItem(
        4,
        button(
            Material.NAME_TAG,
            "State",
            animation.displayName == null ? animation.id : animation.displayName,
            object.id));
    gui.setItem(
        10,
        button(
            Material.CLOCK,
            "Length -20",
            String.valueOf(Math.max(1, animation.lengthTicks - 20)),
            "Shortens manual timeline"));
    gui.setItem(
        12,
        button(
            Material.CLOCK,
            "Length +20",
            String.valueOf(animation.lengthTicks + 20),
            "Adds time after the last frame"));
    gui.setItem(
        14,
        cycleButton(
            Material.REPEATER,
            "Speed",
            speedText(animation.playbackSpeed),
            "0.5x|Slow motion",
            "1x|Normal speed",
            "2x|Double speed",
            "4x|Four times speed"));
    gui.setItem(
        16,
        cycleButton(
            animation.loop ? Material.LIME_DYE : Material.GRAY_DYE,
            "Loop",
            animation.loop ? "On" : "Off",
            "On|Restarts after the final frame",
            "Off|Stops after the final frame"));
    gui.setItem(
        20, button(Material.TRIPWIRE_HOOK, "Triggers", animation.trigger, "Open trigger setup"));
    gui.setItem(
        22,
        cycleButton(
            "SOFT".equalsIgnoreCase(animation.defaultTransition)
                ? Material.SLIME_BLOCK
                : Material.IRON_BLOCK,
            "Default Capture",
            animation.defaultTransition,
            "HARD|Changes immediately at the keyframe",
            "SOFT|Blends between keyframes"));
    gui.setItem(
        24,
        button(
            Material.COMPARATOR,
            "Priority -1",
            String.valueOf(animation.priority),
            "Lower loses animation clashes"));
    gui.setItem(
        26,
        button(
            Material.COMPARATOR,
            "Priority +1",
            String.valueOf(animation.priority),
            "Higher wins animation clashes"));
    gui.setItem(
        28,
        cycleButton(
            "ADD".equalsIgnoreCase(animation.captureMode) ? Material.HOPPER : Material.CAULDRON,
            "Capture Mode",
            animation.captureMode,
            "ADD|Merges capture into the frame",
            "REPLACE|Overwrites the captured frame"));
    gui.setItem(
        30,
        cycleButton(
            animation.windEnabled ? Material.FEATHER : Material.GRAY_DYE,
            "State Wind",
            animation.windEnabled ? "On" : "Off",
            "On|Applies configured wind motion",
            "Off|Keeps this state static"));
    gui.setItem(31, button(Material.ARROW, "Back", "Animation manager", "Click to return"));
    gui.setItem(
        32,
        button(
            Material.WIND_CHARGE,
            "Wind Settings",
            plugin.getAnimatedObjectManager().windStatus(player),
            "Motion, weather, gusts"));
    gui.setItem(
        34,
        button(
            animation.sound == null || animation.sound.isBlank()
                ? Material.NOTE_BLOCK
                : Material.JUKEBOX,
            "Sound",
            animation.sound == null || animation.sound.isBlank() ? "None" : animation.sound,
            "/sv anim sound " + object.id + " " + animation.id + " <sound>"));
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

  private String speedText(double speed) {
    if (speed == (long) speed) {
      return (long) speed + "x";
    }
    return speed + "x";
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
