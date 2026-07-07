package me.reube.SmallVoxels.ui;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.managers.animation.AnimatedVoxelObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class AnimationWindSettingsGUI {
  public enum Page {
    OVERVIEW,
    MOTION,
    FABRIC,
    WEATHER,
    REACTIONS
  }

  public static final Component TITLE = Component.text("Wind Settings").color(NamedTextColor.AQUA);
  private static final Map<UUID, Page> pages = new ConcurrentHashMap<>();
  private final SmallVoxels plugin;

  public AnimationWindSettingsGUI(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  public static Page page(Player player) {
    return pages.getOrDefault(player.getUniqueId(), Page.OVERVIEW);
  }

  public void open(Player player) {
    open(player, page(player));
  }

  public void open(Player player, Page page) {
    pages.put(player.getUniqueId(), page);
    Inventory gui = Bukkit.createInventory(null, 54, TITLE);
    AnimatedVoxelObject object = plugin.getAnimatedObjectManager().editorObject(player);
    if (object == null || !"WIND".equalsIgnoreCase(object.objectType)) {
      gui.setItem(
          13,
          button(
              Material.BARRIER,
              "No Wind Object",
              "/sv anim windcreate <name>",
              "Select a wind animation first"));
      player.openInventory(gui);
      return;
    }

    gui.setItem(
        4,
        button(
            Material.FEATHER,
            "Wind Object",
            object.id,
            plugin.getAnimatedObjectManager().windStatus(player)));
    switch (page) {
      case OVERVIEW -> overview(gui, object);
      case MOTION -> motion(gui, object);
      case FABRIC -> fabric(gui, object);
      case WEATHER -> weather(gui, object);
      case REACTIONS -> reactions(gui, object);
    }
    navigation(gui, page, player);
    player.openInventory(gui);
  }

  private void overview(Inventory gui, AnimatedVoxelObject object) {
    gui.setItem(
        19,
        pageButton(Material.PISTON, "Motion", "Displacement, damping", "Direction and wind power"));
    gui.setItem(
        21,
        pageButton(Material.STRING, "Fabric", "Stiffness, idle", "How connected parts hold shape"));
    gui.setItem(
        23, pageButton(Material.CLOCK, "Weather", "Sun, rain, thunder", "World weather intensity"));
    gui.setItem(
        25,
        pageButton(
            Material.COMPASS, "Reactions", "Environment, players", "Arrows and velocity gusts"));

    gui.setItem(
        30,
        button(
            Material.CHAIN,
            "Connectors",
            object.windConnectorPartIds.size() + " pinned",
            "Static roots or edges"));
    gui.setItem(
        31,
        button(
            Material.WHITE_WOOL,
            "Nodes",
            object.windNodePartIds.size() + " fluttering",
            "Moving fabric pieces"));
    gui.setItem(
        32,
        button(
            Material.LEAD,
            "Links",
            object.windLinks.size() + " constraints",
            "Automatic fabric constraints"));
  }

  private void motion(Inventory gui, AnimatedVoxelObject object) {
    row(
        gui,
        20,
        Material.PHANTOM_MEMBRANE,
        "Displacement",
        object.windDisplacement,
        "Higher means larger visible sway");
    row(gui, 24, Material.HONEYCOMB, "Damping", object.windDamping, "Higher means calmer movement");
    directionRow(gui, 29, object);
  }

  private void fabric(Inventory gui, AnimatedVoxelObject object) {
    row(
        gui,
        20,
        Material.SLIME_BALL,
        "Stiffness",
        object.windStiffness,
        "Higher keeps linked parts tighter");
    row(
        gui,
        24,
        Material.OAK_LEAVES,
        "Idle",
        object.windIdleIntensity,
        "Gentle flutter with no gust");
    row(
        gui,
        29,
        Material.WHITE_WOOL,
        "Coherence",
        object.windCoherence,
        "Higher keeps neighbors moving together");
  }

  private void weather(Inventory gui, AnimatedVoxelObject object) {
    row(gui, 20, Material.SUNFLOWER, "Sun", object.windSunIntensity, "Clear-weather wind strength");
    row(
        gui,
        24,
        Material.WATER_BUCKET,
        "Rain",
        object.windRainIntensity,
        "Rain-weather wind strength");
    row(
        gui,
        29,
        Material.LIGHTNING_ROD,
        "Thunder",
        object.windThunderIntensity,
        "Storm wind strength");
  }

  private void reactions(Inventory gui, AnimatedVoxelObject object) {
    gui.setItem(
        20,
        cycleButton(
            object.windEnvironmentReactive ? Material.LIME_DYE : Material.GRAY_DYE,
            "Environment Reactive",
            object.windEnvironmentReactive ? "ON" : "OFF",
            "ON|Caves and corners bias wind toward open space",
            "OFF|Ignores nearby environment geometry"));
    row(
        gui,
        24,
        Material.MOSS_BLOCK,
        "Environment",
        object.windEnvironmentStrength,
        "How much caves/corners affect wind");
    row(
        gui,
        29,
        Material.PLAYER_HEAD,
        "Players",
        object.windPlayerReactionStrength,
        "Walking/sprinting pushes nearby fabric");
    row(
        gui,
        33,
        Material.ARROW,
        "Arrows",
        object.windProjectileReactionStrength,
        "Projectiles add fast gust direction");
    row(
        gui,
        38,
        Material.ELYTRA,
        "Velocity",
        object.windVelocityReactionStrength,
        "Other moving entities add gusts");
  }

  private void navigation(Inventory gui, Page page, Player player) {
    gui.setItem(45, tab(page, Page.OVERVIEW, Material.BOOK, "Overview"));
    gui.setItem(46, tab(page, Page.MOTION, Material.PISTON, "Motion"));
    gui.setItem(47, tab(page, Page.FABRIC, Material.STRING, "Fabric"));
    gui.setItem(48, tab(page, Page.WEATHER, Material.CLOCK, "Weather"));
    gui.setItem(50, tab(page, Page.REACTIONS, Material.COMPASS, "Reactions"));
    gui.setItem(
        49,
        button(
            Material.SPYGLASS,
            "Preview Wind",
            plugin.getAnimatedObjectManager().windStatus(player),
            "Click to force a visible wind pose"));
    gui.setItem(53, button(Material.ARROW, "Back", "Animation state settings", "Return"));
  }

  private ItemStack tab(Page current, Page target, Material material, String name) {
    return button(
        current == target ? Material.LIME_STAINED_GLASS_PANE : material,
        name,
        current == target ? "Current page" : "Open page",
        "Wind settings");
  }

  private ItemStack pageButton(
      Material material, String name, String firstLine, String secondLine) {
    return button(material, name, firstLine, secondLine);
  }

  private void row(
      Inventory gui, int slot, Material material, String name, double value, String help) {
    gui.setItem(slot, button(Material.RED_DYE, name + " -", fmt(value), help));
    gui.setItem(slot + 1, button(material, name, fmt(value), help));
    gui.setItem(slot + 2, button(Material.LIME_DYE, name + " +", fmt(value), help));
  }

  private void directionRow(Inventory gui, int slot, AnimatedVoxelObject object) {
    gui.setItem(
        slot,
        button(
            Material.RED_DYE,
            "Direction -",
            yawFmt(object),
            "Click turns wind, shift changes strength"));
    gui.setItem(
        slot + 1,
        button(Material.COMPASS, "Direction", yawFmt(object), "Base wind direction and power"));
    gui.setItem(
        slot + 2,
        button(
            Material.LIME_DYE,
            "Direction +",
            yawFmt(object),
            "Click turns wind, shift changes strength"));
  }

  private String yawFmt(AnimatedVoxelObject object) {
    return String.format(
        java.util.Locale.ROOT,
        "%.0f deg / %.2f power",
        object.windDirectionYaw,
        object.windDirectionStrength);
  }

  private String fmt(double value) {
    return String.format(java.util.Locale.ROOT, "%.2f", value);
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
