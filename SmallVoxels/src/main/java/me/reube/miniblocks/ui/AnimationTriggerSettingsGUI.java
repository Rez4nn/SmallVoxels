package me.reube.SmallVoxels.ui;

import java.util.List;
import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.managers.animation.AnimationTriggerCause;
import me.reube.SmallVoxels.managers.animation.VoxelAnimation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class AnimationTriggerSettingsGUI {
  public static final Component TITLE =
      Component.text("Animation Triggers").color(NamedTextColor.AQUA);
  private final SmallVoxels plugin;

  public AnimationTriggerSettingsGUI(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  public void open(Player player) {
    Inventory gui = Bukkit.createInventory(null, 45, TITLE);
    VoxelAnimation animation = plugin.getAnimatedObjectManager().editorAnimation(player);
    if (animation == null) {
      gui.setItem(
          13,
          button(
              Material.BARRIER,
              "No State",
              "Select an animation first",
              "Triggers belong to the selected state"));
      player.openInventory(gui);
      return;
    }
    String trigger = animation.trigger == null ? "MANUAL" : animation.trigger.toUpperCase();
    AnimationTriggerCause cause = plugin.getAnimatedObjectManager().editorTriggerCause(player);
    gui.setItem(
        4,
        cycleButton(
            Material.TRIPWIRE_HOOK,
            "Current Trigger",
            readable(trigger),
            "manual|Only plays from GUI or command",
            "player distance|Starts when enough players are nearby",
            "redstone|Starts from linked redstone",
            "entity nearby|Starts when an entity is nearby"));
    gui.setItem(
        10,
        button(
            Material.LEVER,
            "Manual",
            selected(trigger, "MANUAL"),
            "Only plays from GUI or command"));
    gui.setItem(
        12,
        button(
            Material.PLAYER_HEAD,
            "Player Distance",
            selected(trigger, "PLAYER_DISTANCE"),
            "Starts when enough players are nearby"));
    gui.setItem(
        14,
        button(
            Material.REDSTONE,
            "Redstone",
            selected(trigger, "REDSTONE"),
            "Starts when linked redstone is powered"));
    gui.setItem(
        16,
        button(
            Material.ENDER_EYE,
            "Entity Nearby",
            selected(trigger, "ENTITY_NEARBY"),
            "Starts when an entity is nearby"));

    if ("PLAYER_DISTANCE".equals(trigger) || "PLAYER_ENTER".equals(trigger)) {
      gui.setItem(
          19,
          button(
              Material.RED_DYE,
              "Distance -1",
              animation.triggerRadius + " blocks",
              "Smaller activation radius"));
      gui.setItem(
          20,
          button(
              Material.LIME_DYE,
              "Distance +1",
              animation.triggerRadius + " blocks",
              "Larger activation radius"));
      gui.setItem(
          21,
          button(
              Material.RED_STAINED_GLASS_PANE,
              "Players -1",
              animation.triggerPlayerCount + " needed",
              "Require fewer players"));
      gui.setItem(
          22,
          button(
              Material.LIME_STAINED_GLASS_PANE,
              "Players +1",
              animation.triggerPlayerCount + " needed",
              "Require more players"));
    } else if ("ENTITY_NEARBY".equals(trigger)) {
      int radius = cause == null ? animation.triggerRadius : cause.radius;
      boolean invisible = cause != null && cause.includeInvisibleEntities;
      gui.setItem(
          19,
          button(Material.RED_DYE, "Radius -1", radius + " blocks", "Smaller activation radius"));
      gui.setItem(
          20,
          button(Material.LIME_DYE, "Radius +1", radius + " blocks", "Larger activation radius"));
      gui.setItem(
          22,
          cycleButton(
              Material.GLASS,
              "Invisible Entities",
              invisible ? "Included" : "Ignored",
              "Included|Invisible entities count",
              "Ignored|Only visible entities count"));
    } else if ("REDSTONE".equals(trigger)) {
      String linked =
          animation.redstoneWorld == null
              ? "No primary link"
              : animation.redstoneX + ", " + animation.redstoneY + ", " + animation.redstoneZ;
      gui.setItem(
          20,
          button(
              Material.TARGET,
              "Link Redstone",
              linked,
              "Click, then right-click button/plate/dust"));
      int causeCount = animation.triggerCauses == null ? 0 : animation.triggerCauses.size();
      gui.setItem(
          22,
          button(
              Material.CAULDRON,
              "Clear Links",
              causeCount + " causes",
              "Removes redstone/player causes"));
    }

    if (cause != null) {
      gui.setItem(
          28,
          button(
              Material.CLOCK,
              "Cooldown -20t",
              cause.cooldownTicks + " ticks",
              "How often this trigger may play"));
      gui.setItem(
          29,
          button(
              Material.REPEATER,
              "Cooldown +20t",
              cause.cooldownTicks + " ticks",
              "Shift uses larger steps"));
      gui.setItem(
          31,
          cycleButton(
              Material.COMPARATOR,
              "After Play",
              readable(cause.afterAction),
              "default|Uses normal playback behaviour",
              "reverse|Plays back in reverse",
              "next|Starts the selected next state",
              "nothing|Holds without another action"));
      var object = plugin.getAnimatedObjectManager().editorObject(player);
      String[] states =
          object == null
              ? new String[0]
              : object.animations.stream()
                  .map(value -> value.id + "|State used after this trigger")
                  .toArray(String[]::new);
      gui.setItem(
          33,
          cycleButton(
              Material.JUKEBOX,
              "Next State",
              cause.nextAnimationId == null ? "" : cause.nextAnimationId,
              states));
    }

    gui.setItem(
        44, button(Material.ARROW, "Back", "State settings", "Return to timing and capture"));
    player.openInventory(gui);
  }

  private String selected(String trigger, String value) {
    return trigger.equals(value)
            || ("PLAYER_ENTER".equals(trigger) && "PLAYER_DISTANCE".equals(value))
        ? "Selected"
        : "Click to select";
  }

  private String readable(String trigger) {
    return trigger.toLowerCase().replace('_', ' ');
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
