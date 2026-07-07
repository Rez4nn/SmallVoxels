package me.reube.SmallVoxels.ui;

import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.detail.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class DetailToolGUI {
  public static final Component TITLE =
      Component.text("SmallVoxels Detail Tools").color(NamedTextColor.DARK_GREEN);
  private final SmallVoxels plugin;

  public DetailToolGUI(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  public void open(Player player) {
    var s = plugin.getDetailManager().settings(player);
    Inventory inv = Bukkit.createInventory(null, 54, TITLE);
    inv.setItem(
        4,
        item(
            Material.PAINTING,
            "Detail Tools",
            "Choose a setting, then right-click a surface",
            "Left click: next   Right click: previous"));
    inv.setItem(
        10,
        cycleItem(
            Material.NETHER_STAR,
            "Tool",
            pretty(s.mode.name()),
            "Brush|Paints procedural surface materials",
            "Trim|Adds straight or curved borders",
            "Damage|Carves chips and connected cracks",
            "Overgrow|Spreads continuous moss and leaves",
            "Stamp|Places a reusable decorative pattern"));
    inv.setItem(12, variantItem(s));
    inv.setItem(
        14,
        cycleItem(
            s.material,
            "Trim Material",
            pretty(s.material.name()),
            "Oak planks|Light wooden framing",
            "Spruce planks|Dark rustic framing",
            "Dark oak planks|Heavy dark timber",
            "Stone bricks|Classic masonry trim",
            "Deepslate bricks|Dark stone edging",
            "Copper block|Metallic architectural trim"));
    inv.setItem(
        16,
        item(
            Material.LIME_DYE,
            "Intensity",
            String.format("%.2f", s.intensity),
            "Left: +0.10   Right: -0.10"));

    inv.setItem(
        19,
        item(
            Material.SNOW,
            "Depth",
            s.layer == 0 ? "Surface" : s.layer > 0 ? s.layer + " outward" : (-s.layer) + " inward",
            "Left/right: one   Shift: eight"));
    inv.setItem(
        21,
        cycleItem(
            s.connectedLayers ? Material.CHAIN : Material.STRING,
            "Connected Layers",
            s.connectedLayers ? "On" : "Off",
            "On|Supports outward details and carves inward paths",
            "Off|Changes only the selected depth"));
    inv.setItem(
        23,
        cycleItem(
            s.naturalize ? Material.GRASS_BLOCK : Material.DIRT,
            "Naturalise",
            s.naturalize ? "On" : "Off",
            "On|Changes covered surfaces into natural substrate",
            "Off|Preserves every original material"));
    String level =
        s.resolution == 2
            ? "Low"
            : s.resolution == 4 ? "Mid" : s.resolution == 8 ? "High" : "Very High";
    inv.setItem(
        25,
        cycleItem(
            Material.DIAMOND,
            "Detail Level",
            level,
            "Low - 2x|Large shapes with very few entities",
            "Mid - 4x|Balanced chunky detail",
            "High - 8x|Fine detail with moderate density",
            "Very High - 16x|Full one-sixteenth precision"));

    for (int i = 0; i < 4; i++) {
      Material material =
          i < s.palette.size() ? s.palette.get(i) : Material.LIGHT_GRAY_STAINED_GLASS_PANE;
      int slot = 28 + i * 2;
      inv.setItem(
          slot,
          item(
              material,
              "Palette " + (i + 1),
              i < s.palette.size() ? pretty(material.name()) : "Empty",
              "Click a block in your inventory to add/remove"));
    }
    inv.setItem(
        31,
        item(
            Material.BUCKET,
            "Default Palette",
            s.palette.isEmpty() ? "Currently using defaults" : "Clear custom mix",
            "Click to reset"));
    inv.setItem(
        40,
        item(
            Material.BOOK,
            "Palette Help",
            "Click block items in your inventory",
            "Up to four materials can be mixed"));
    inv.setItem(
        46,
        item(
            Material.MOSSY_COBBLESTONE,
            "Auto-detail: Overgrown",
            "Uses your last Copy selection",
            "Maximum 4096 blocks"));
    inv.setItem(
        48,
        item(
            Material.CRACKED_STONE_BRICKS,
            "Auto-detail: Ruined",
            "Uses your last Copy selection",
            "Maximum 4096 blocks"));
    inv.setItem(
        53,
        item(
            Material.BARRIER,
            "Exit Detail Mode",
            "Returns the axe to Remove mode",
            "Click to exit"));
    player.openInventory(inv);
  }

  private ItemStack variantItem(me.reube.SmallVoxels.detail.DetailManager.Settings s) {
    return switch (s.mode) {
      case BRUSH ->
          cycleItem(
              Material.MOSS_BLOCK,
              "Brush",
              pretty(s.brush.name()),
              "Moss|Organic patches favouring tops and edges",
              "Cracks|Continuous recessed fracture lines",
              "Dirt|Irregular earthy surface patches",
              "Snow|Settled covering that favours top faces",
              "Ash|Scattered grey and black deposits",
              "Runes|Structured glowing markings",
              "Wooden beam|Timber border and beam patterns",
              "Stone detail|Masonry lines and accents");
      case TRIM ->
          cycleItem(
              Material.CHAIN,
              "Trim Shape",
              pretty(s.trimShape.name()),
              "Edge|Follows the nearest clicked edge",
              "Circle|Draws a complete curved border",
              "Half circle|Draws an upper arch-shaped border");
      case STAMP ->
          cycleItem(
              Material.BRICK,
              "Stamp",
              pretty(s.preset.name()),
              "Small gothic arch|Compact pointed masonry arch",
              "Broken window trim|Damaged rectangular frame",
              "Mossy corner|Organic corner growth",
              "Wooden beam joint|Crossed timber connection",
              "Cracked stone face|Damaged masonry surface",
              "Tiny shelf|Small projecting wooden shelf",
              "Pipe segment|Straight decorative pipe section",
              "Bolt cluster|Four compact metal fasteners");
      case DAMAGE ->
          item(Material.IRON_PICKAXE, "Damage", "Cracks and chipped areas", "No secondary variant");
      case OVERGROW ->
          item(Material.VINE, "Overgrowth", "Continuous moss and leaves", "No secondary variant");
      case OFF ->
          item(
              Material.BARRIER,
              "No Tool Selected",
              "Cycle the Tool control",
              "to select a detail tool");
    };
  }

  private ItemStack cycleItem(Material material, String name, String selected, String... options) {
    ItemStack out = new ItemStack(material);
    ItemMeta meta = out.getItemMeta();
    meta.displayName(Component.text(name).color(NamedTextColor.GREEN));
    java.util.ArrayList<Component> lore = new java.util.ArrayList<>();
    lore.add(Component.text("Left: next   Right: previous").color(NamedTextColor.DARK_GRAY));
    lore.add(Component.empty());
    for (String option : options) {
      String[] parts = option.split("\\|", 2);
      String label = parts[0];
      boolean active =
          label.equalsIgnoreCase(selected)
              || label.toLowerCase().startsWith(selected.toLowerCase() + " -");
      lore.add(
          Component.text((active ? "> " : "  ") + label)
              .color(active ? NamedTextColor.GREEN : NamedTextColor.GRAY));
      if (parts.length > 1)
        lore.add(Component.text("    " + parts[1]).color(NamedTextColor.DARK_GRAY));
    }
    meta.lore(lore);
    out.setItemMeta(meta);
    return out;
  }

  private ItemStack item(Material material, String name, String... lore) {
    ItemStack out = new ItemStack(material);
    ItemMeta meta = out.getItemMeta();
    meta.displayName(Component.text(name).color(NamedTextColor.GREEN));
    meta.lore(
        java.util.Arrays.stream(lore)
            .map(x -> Component.text(x).color(NamedTextColor.GRAY))
            .toList());
    out.setItemMeta(meta);
    return out;
  }

  private String pretty(String value) {
    String s = value.toLowerCase().replace('_', ' ');
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
