package me.reube.SmallVoxels.ui;

import java.util.List;
import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.managers.ToolMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class BlockSelectionGUI {

  public static final Component TITLE =
      Component.text("SmallVoxels Settings").color(NamedTextColor.GREEN);

  private final SmallVoxels plugin;

  public BlockSelectionGUI(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  public void openGUI(Player player) {
    String selectedBlockName = plugin.getModeToggleListener().getPlayerSelectedBlock(player);
    Material selectedMaterial = Material.getMaterial(selectedBlockName);
    if (selectedMaterial == null) {
      selectedMaterial = Material.STONE;
    }

    Inventory gui = Bukkit.createInventory(null, 27, TITLE);
    ToolMode mode = plugin.getModeToggleListener().getToolMode(player);

    ItemStack savedItem = plugin.getModeToggleListener().getPlayerBlockMetadata(player);
    ItemStack selected = savedItem == null ? new ItemStack(selectedMaterial) : savedItem.clone();
    selected.setAmount(1);
    ItemMeta selectedMeta = selected.getItemMeta();
    if (selectedMeta != null) {
      String itemName = selectedMaterial.name().replace("_", " ");
      if (savedItem != null
          && savedItem.hasItemMeta()
          && savedItem.getItemMeta().hasDisplayName()) {
        itemName =
            PlainTextComponentSerializer.plainText()
                .serialize(savedItem.getItemMeta().displayName());
      }
      selectedMeta.displayName(Component.text("Selected: " + itemName).color(NamedTextColor.GREEN));
      selectedMeta.lore(
          List.of(
              Component.text(selectedMaterial.name().replace("_", " ")).color(NamedTextColor.GRAY),
              Component.text("Click an item in your inventory to change it.")
                  .color(NamedTextColor.DARK_GRAY)));
      selected.setItemMeta(selectedMeta);
    }
    gui.setItem(10, selected);

    boolean preview = plugin.getModeToggleListener().isPlacementPreviewEnabled(player);
    gui.setItem(
        13,
        toggleButton(
            preview ? Material.LIME_STAINED_GLASS : Material.GRAY_STAINED_GLASS,
            "Placement Preview",
            preview,
            "On|Shows placement before confirming",
            "Off|Places without a preview"));

    int scale = plugin.getModeToggleListener().getPlayerVoxelScale(player);
    gui.setItem(
        16,
        cycleButton(
            Material.IRON_NUGGET,
            "Voxel Scale",
            scale + "x",
            "2x|Half-block pieces",
            "4x|Quarter-block pieces",
            "8x|One-eighth pieces",
            "16x|Finest one-sixteenth pieces"));
    gui.setItem(
        25,
        button(
            Material.FEATHER,
            "Tool Height",
            String.valueOf(plugin.getModeToggleListener().getToolHeight(player)),
            "Shift-scroll with the axe"));
    gui.setItem(
        24,
        cycleButton(
            Material.ARROW,
            "Tool Direction",
            plugin.getModeToggleListener().getToolAxis(player).toUpperCase(),
            "X|Repeat horizontally east/west",
            "Y|Repeat vertically",
            "Z|Repeat horizontally north/south"));

    if (mode == ToolMode.BRUSH) {
      int radius = plugin.getModeToggleListener().getBrushRadius(player);
      int maxRadius = Math.max(1, plugin.getConfig().getInt("limits.brush-radius", 3));
      String[] radii =
          java.util.stream.IntStream.rangeClosed(1, maxRadius)
              .mapToObj(
                  value ->
                      value
                          + "|Extends "
                          + value
                          + " voxel"
                          + (value == 1 ? "" : "s")
                          + " from the centre")
              .toArray(String[]::new);
      gui.setItem(
          9, cycleButton(Material.SLIME_BALL, "Brush Radius", String.valueOf(radius), radii));
      gui.setItem(
          11,
          toggleButton(
              plugin.getModeToggleListener().isBrushSmooth(player)
                  ? Material.HONEYCOMB
                  : Material.GRAY_DYE,
              "Smooth Tool",
              plugin.getModeToggleListener().isBrushSmooth(player),
              "On|Smooths isolated edges",
              "Off|Uses direct placement"));
      gui.setItem(
          13,
          cycleButton(
              plugin.getModeToggleListener().isBrushRound(player)
                  ? Material.SNOWBALL
                  : Material.IRON_BLOCK,
              "Brush Shape",
              plugin.getModeToggleListener().isBrushRound(player) ? "Sphere" : "Voxel Box",
              "Voxel Box|Uses a cubic brush volume",
              "Sphere|Uses a rounded brush volume"));
      gui.setItem(
          15,
          toggleButton(
              plugin.getModeToggleListener().isBrushMask(player)
                  ? Material.LIME_STAINED_GLASS
                  : Material.RED_STAINED_GLASS,
              "Global Mask",
              plugin.getModeToggleListener().isBrushMask(player),
              "On|Preserves occupied voxels",
              "Off|Allows the brush to replace overlaps"));
    } else if (mode == ToolMode.REMOVE) {
      gui.setItem(
          11,
          toggleButton(
              plugin.getModeToggleListener().isRemoveKeepCorner(player)
                  ? Material.LIME_DYE
                  : Material.GRAY_DYE,
              "Keep Corner",
              plugin.getModeToggleListener().isRemoveKeepCorner(player),
              "On|Keeps the starting corner",
              "Off|Includes the full selection"));
      gui.setItem(
          13,
          toggleButton(
              plugin.getModeToggleListener().isRemoveMass(player)
                  ? Material.REDSTONE_BLOCK
                  : Material.GRAY_DYE,
              "Mass Remove",
              plugin.getModeToggleListener().isRemoveMass(player),
              "On|Removes a selected volume",
              "Off|Removes individual voxels"));
    } else if (mode == ToolMode.REPLACE) {
      String from = plugin.getModeToggleListener().getReplaceFromBlock(player);
      Material fromMaterial = from.isBlank() ? Material.BARRIER : Material.getMaterial(from);
      if (fromMaterial == null) {
        fromMaterial = Material.BARRIER;
      }
      Material toMaterial =
          Material.getMaterial(plugin.getModeToggleListener().getReplaceToBlock(player));
      if (toMaterial == null) {
        toMaterial = Material.STONE;
      }
      gui.setItem(
          11,
          button(
              fromMaterial,
              "Replace From",
              from.isBlank() ? "Any block" : from.replace("_", " "),
              "Click, then pick an item"));
      gui.setItem(
          13,
          button(
              Material.BARRIER,
              "From: Any",
              "Replace any selected block",
              "Click to clear filter"));
      gui.setItem(
          15,
          button(
              toMaterial,
              "Replace To",
              plugin.getModeToggleListener().getReplaceToBlock(player).replace("_", " "),
              "Click, then pick an item"));
      gui.setItem(
          22,
          button(
              plugin.getVoxelSelectionManager().hasReplaceSelection(player)
                  ? Material.LIME_DYE
                  : Material.GRAY_DYE,
              "Apply Replace",
              plugin.getVoxelSelectionManager().hasReplaceSelection(player)
                  ? "Ready"
                  : "Select area first",
              "Click to apply"));
    } else if (mode == ToolMode.SET) {
      gui.setItem(
          11,
          toggleButton(
              plugin.getModeToggleListener().isSetKeepCorner(player)
                  ? Material.LIME_DYE
                  : Material.GRAY_DYE,
              "Keep Corner",
              plugin.getModeToggleListener().isSetKeepCorner(player),
              "On|Keeps the starting corner",
              "Off|Includes the full selection"));
      gui.setItem(
          13,
          cycleButton(
              plugin.getModeToggleListener().isSetLine(player)
                  ? Material.STRING
                  : Material.LIME_CONCRETE,
              "Set Shape",
              plugin.getModeToggleListener().isSetLine(player) ? "Line" : "Fill",
              "Fill|Fills the selected volume",
              "Line|Draws between selection points"));
      gui.setItem(
          15,
          toggleButton(
              plugin.getModeToggleListener().isSetMask(player)
                  ? Material.LIME_STAINED_GLASS
                  : Material.RED_STAINED_GLASS,
              "Global Mask",
              plugin.getModeToggleListener().isSetMask(player),
              "On|Preserves occupied voxels",
              "Off|Allows overlap replacement"));
    } else if (mode == ToolMode.PASTE) {
      gui.setItem(4, button(Material.COMPASS, "Rotate Paste", "90 degrees", "Click to rotate"));
      gui.setItem(11, button(Material.RED_CONCRETE, "Flip X", "Mirror paste", "Click to flip"));
      gui.setItem(13, button(Material.LIME_CONCRETE, "Flip Y", "Mirror paste", "Click to flip"));
      gui.setItem(15, button(Material.BLUE_CONCRETE, "Flip Z", "Mirror paste", "Click to flip"));
      gui.setItem(
          22,
          toggleButton(
              plugin.getModeToggleListener().isPasteMask(player)
                  ? Material.LIME_STAINED_GLASS
                  : Material.RED_STAINED_GLASS,
              "Global Mask",
              plugin.getModeToggleListener().isPasteMask(player),
              "On|Only pastes into empty space",
              "Off|Merges over occupied voxels"));
    } else if (mode == ToolMode.ROTATE) {
      String axis = plugin.getModeToggleListener().getRotateAxis(player).toUpperCase();
      int angle = plugin.getModeToggleListener().getRotateAngle(player);
      gui.setItem(
          4,
          cycleButton(
              Material.CLOCK,
              "Rotation Step",
              angle + "°",
              "15°|Fine adjustment",
              "30°|Gentle diagonal",
              "45°|Square diagonal",
              "90°|Quarter turn"));
      gui.setItem(
          11,
          button(
              axis.equals("X") ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
              "Tilt X",
              axis.equals("X") ? "Selected direction" : "Click to select",
              "Shift-scroll: - / + " + angle + "°"));
      gui.setItem(
          13,
          button(
              axis.equals("Y") ? Material.LIME_CONCRETE : Material.YELLOW_CONCRETE,
              "Rotation Y",
              axis.equals("Y") ? "Selected direction" : "Click to select",
              "Shift-scroll: - / + " + angle + "°"));
      gui.setItem(
          15,
          button(
              axis.equals("Z") ? Material.LIME_CONCRETE : Material.BLUE_CONCRETE,
              "Tilt Z",
              axis.equals("Z") ? "Selected direction" : "Click to select",
              "Shift-scroll: - / + " + angle + "°"));
    } else if (mode == ToolMode.SCALE) {
      String axis = plugin.getModeToggleListener().getScaleAxis(player);
      double step = plugin.getModeToggleListener().getScaleStep(player);
      gui.setItem(
          4,
          cycleButton(
              Material.COMPARATOR,
              "Scale Step",
              step + "x",
              "0.05x|Very fine",
              "0.1x|Fine",
              "0.25x|Normal",
              "0.5x|Large",
              "1.0x|Double-size step"));
      gui.setItem(
          11,
          cycleButton(
              Material.TARGET,
              "Scale Axis",
              axis.equals("uniform") ? "Uniform" : axis.toUpperCase(),
              "Uniform|Changes every dimension",
              "X|Width only",
              "Y|Height only",
              "Z|Depth only"));
      gui.setItem(
          13,
          button(
              Material.RED_DYE,
              "Scale Down",
              "Selected axis: " + axis,
              "Click or Shift-scroll backward"));
      gui.setItem(
          15,
          button(
              Material.LIME_DYE,
              "Scale Up",
              "Selected axis: " + axis,
              "Click or Shift-scroll forward"));
    } else if (mode == ToolMode.MOVE) {
      gui.setItem(
          4,
          cycleButton(
              Material.RECOVERY_COMPASS,
              "Move Space",
              plugin.getModeToggleListener().isMoveLocalSpace(player) ? "Local" : "World",
              "World|Uses Minecraft X, Y and Z",
              "Local|Follows the rotated object's axes"));
      String moveAxis = plugin.getModeToggleListener().getToolAxis(player).toUpperCase();
      int moveStep = plugin.getModeToggleListener().getMoveStep(player);
      gui.setItem(
          11,
          cycleButton(
              Material.TARGET,
              "Move Axis",
              moveAxis,
              "X|Width direction",
              "Y|Height direction",
              "Z|Depth direction"));
      gui.setItem(
          12,
          button(
              Material.RED_DYE,
              "Move Backward",
              "-" + moveStep + "/16 block on " + moveAxis,
              "Click or Shift-scroll backward"));
      gui.setItem(
          13,
          cycleButton(
              Material.COMPARATOR,
              "Move Precision",
              moveStep + "/16",
              "1/16|Finest",
              "2/16|Fine",
              "4/16|Quarter block",
              "8/16|Half block",
              "16/16|Full block"));
      gui.setItem(
          14,
          button(
              Material.LIME_DYE,
              "Move Forward",
              "+" + moveStep + "/16 block on " + moveAxis,
              "Click or Shift-scroll forward"));
    }

    if (mode != ToolMode.PASTE && mode != ToolMode.REPLACE) {
      gui.setItem(
          22,
          button(
              Material.LIME_DYE,
              "Controls",
              "Drop Item: mode",
              "Place Block: scale, Switch Hand: settings"));
    }

    player.openInventory(gui);
  }

  private ItemStack button(Material material, String name, String firstLine, String secondLine) {
    ItemStack item = new ItemStack(material);
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.displayName(Component.text(name).color(NamedTextColor.GREEN));
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
    meta.displayName(Component.text(name).color(NamedTextColor.GREEN));
    java.util.ArrayList<Component> lore = new java.util.ArrayList<>();
    lore.add(Component.text("Left: next   Right: previous").color(NamedTextColor.DARK_GRAY));
    lore.add(Component.empty());
    for (String option : options) {
      String[] parts = option.split("\\|", 2);
      String label = parts[0];
      boolean active = label.equalsIgnoreCase(selected);
      lore.add(
          Component.text((active ? "> " : "  ") + label)
              .color(active ? NamedTextColor.GREEN : NamedTextColor.GRAY));
      if (parts.length > 1)
        lore.add(Component.text("    " + parts[1]).color(NamedTextColor.DARK_GRAY));
    }
    meta.lore(lore);
    item.setItemMeta(meta);
    return item;
  }

  private ItemStack toggleButton(
      Material material, String name, boolean enabled, String... options) {
    return cycleButton(material, name, enabled ? "On" : "Off", options);
  }
}
