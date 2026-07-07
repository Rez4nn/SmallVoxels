package me.reube.SmallVoxels.listeners;

import java.util.Arrays;
import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.detail.BrushType;
import me.reube.SmallVoxels.detail.DetailPreset;
import me.reube.SmallVoxels.detail.DetailToolMode;
import me.reube.SmallVoxels.detail.TrimShape;
import me.reube.SmallVoxels.managers.ToolMode;
import me.reube.SmallVoxels.ui.DetailToolGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

public final class DetailToolListener implements Listener {
  private static final Material[] TRIM_MATERIALS = {
    Material.OAK_PLANKS,
    Material.SPRUCE_PLANKS,
    Material.DARK_OAK_PLANKS,
    Material.STONE_BRICKS,
    Material.DEEPSLATE_BRICKS,
    Material.COPPER_BLOCK
  };
  private static final DetailToolMode[] TOOL_MODES = {
    DetailToolMode.BRUSH,
    DetailToolMode.TRIM,
    DetailToolMode.DAMAGE,
    DetailToolMode.OVERGROW,
    DetailToolMode.STAMP
  };
  private static final int[] DETAIL_LEVELS = {2, 4, 8, 16};

  private final SmallVoxels plugin;
  private final DetailToolGUI gui;

  public DetailToolListener(SmallVoxels plugin) {
    this.plugin = plugin;
    this.gui = new DetailToolGUI(plugin);
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onSurfaceClick(PlayerInteractEvent event) {
    if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
      return;
    }

    Player player = event.getPlayer();
    if (plugin.getModeToggleListener().getToolMode(player) != ToolMode.DETAIL
        || !plugin.getChiselManager().isChiselAxe(player.getInventory().getItemInMainHand())
        || !canUseDetailTools(player)) {
      return;
    }

    event.setCancelled(true);
    boolean applied =
        plugin
            .getDetailManager()
            .apply(
                player, event.getClickedBlock(), event.getBlockFace(), event.getClickedPosition());
    player.sendActionBar(
        Component.text(applied ? "Detail applied" : "That surface could not be edited")
            .color(applied ? NamedTextColor.GREEN : NamedTextColor.RED));
  }

  @EventHandler
  public void onInventoryClick(InventoryClickEvent event) {
    if (!event.getView().title().equals(DetailToolGUI.TITLE)
        || !(event.getWhoClicked() instanceof Player player)) {
      return;
    }

    event.setCancelled(true);
    var settings = plugin.getDetailManager().settings(player);
    if (event.getClickedInventory() == player.getInventory()) {
      updatePalette(player, event.getCurrentItem());
      return;
    }
    if (event.getClickedInventory() != event.getView().getTopInventory()) {
      return;
    }

    int direction = event.isRightClick() ? -1 : 1;
    switch (event.getSlot()) {
      case 10 -> settings.mode = cycle(TOOL_MODES, settings.mode, direction);
      case 12 -> cycleVariant(settings, direction);
      case 14 -> settings.material = cycle(TRIM_MATERIALS, settings.material, direction);
      case 16 -> settings.intensity = clamp(settings.intensity + direction * 0.1, 0.1, 1.0);
      case 19 -> {
        int amount = event.isShiftClick() ? 8 : 1;
        settings.layer = Math.max(-(settings.resolution - 1), settings.layer + direction * amount);
      }
      case 21 -> settings.connectedLayers = !settings.connectedLayers;
      case 23 -> settings.naturalize = !settings.naturalize;
      case 25 -> {
        settings.resolution = cycle(DETAIL_LEVELS, settings.resolution, direction);
        settings.layer = Math.max(-(settings.resolution - 1), settings.layer);
      }
      case 28, 30, 32, 34 -> removePaletteEntry(settings.palette, (event.getSlot() - 28) / 2);
      case 31 -> settings.palette.clear();
      case 46 -> applyRegionStyle(player, "overgrown");
      case 48 -> applyRegionStyle(player, "ruined");
      case 53 -> {
        settings.mode = DetailToolMode.OFF;
        plugin.getModeToggleListener().setToolMode(player, ToolMode.REMOVE);
        player.closeInventory();
        return;
      }
      default -> {
        return;
      }
    }
    gui.open(player);
  }

  public void open(Player player) {
    if (plugin.getModeToggleListener().getToolMode(player) != ToolMode.DETAIL) {
      plugin.getModeToggleListener().setToolMode(player, ToolMode.DETAIL);
    }
    if (plugin.getDetailManager().settings(player).mode == DetailToolMode.OFF) {
      plugin.getDetailManager().settings(player).mode = DetailToolMode.BRUSH;
    }
    gui.open(player);
  }

  private boolean canUseDetailTools(Player player) {
    return player.hasPermission("smallvoxels.voxel.use")
        || player.hasPermission("smallvoxels.chisel.use")
        || player.hasPermission("smallvoxels.admin");
  }

  private void updatePalette(Player player, ItemStack clicked) {
    if (clicked == null || clicked.getType() == Material.AIR || !clicked.getType().isBlock()) {
      return;
    }

    var palette = plugin.getDetailManager().settings(player).palette;
    Material material = clicked.getType();
    if (palette.remove(material)) {
      gui.open(player);
      return;
    }
    if (palette.size() >= 4) {
      player.sendActionBar(
          Component.text("Palette can contain four materials").color(NamedTextColor.YELLOW));
      return;
    }
    palette.add(material);
    gui.open(player);
  }

  private void cycleVariant(
      me.reube.SmallVoxels.detail.DetailManager.Settings settings, int direction) {
    switch (settings.mode) {
      case BRUSH -> settings.brush = cycle(BrushType.values(), settings.brush, direction);
      case TRIM -> settings.trimShape = cycle(TrimShape.values(), settings.trimShape, direction);
      case STAMP -> settings.preset = cycle(DetailPreset.values(), settings.preset, direction);
      default -> {}
    }
  }

  private void applyRegionStyle(Player player, String style) {
    int changed = plugin.getDetailManager().detailRegion(player, style);
    String message =
        changed == -1
            ? "Make a Copy selection first."
            : changed == -2
                ? "Selection exceeds 4096 blocks."
                : "Detailed " + changed + " exposed faces.";
    player.sendMessage(
        Component.text(message).color(changed >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
  }

  private void removePaletteEntry(java.util.List<Material> palette, int index) {
    if (index < palette.size()) {
      palette.remove(index);
    }
  }

  private <T> T cycle(T[] values, T current, int direction) {
    int index = Arrays.asList(values).indexOf(current);
    return values[Math.floorMod(index + direction, values.length)];
  }

  private int cycle(int[] values, int current, int direction) {
    int index = Arrays.stream(values).boxed().toList().indexOf(current);
    return values[Math.floorMod(index + direction, values.length)];
  }

  private double clamp(double value, double minimum, double maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }
}
