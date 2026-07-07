package me.reube.SmallVoxels.managers;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public class ChiselManager {

  private final JavaPlugin plugin;

  public ChiselManager(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  public ItemStack createChiselAxe() {
    ItemStack axe = new ItemStack(Material.DIAMOND_AXE);
    ItemMeta meta = axe.getItemMeta();

    if (meta != null) {
      meta.displayName(
          Component.text("Voxel Axe").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));

      meta.lore(
          List.of(
              Component.text("Left-click to edit voxels").color(NamedTextColor.GRAY),
              Component.text("Drop Item to switch mode").color(NamedTextColor.GRAY),
              Component.text("Use the GUI to select blocks").color(NamedTextColor.GRAY)));

      meta.setUnbreakable(true);
      axe.setItemMeta(meta);
    }

    return axe;
  }

  public boolean isChiselAxe(ItemStack item) {
    if (item == null || !item.hasItemMeta()) return false;
    ItemMeta meta = item.getItemMeta();
    if (meta == null) return false;

    Component displayName = meta.displayName();
    if (displayName == null) return false;

    Component newName =
        Component.text("Voxel Axe").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD);
    Component oldName =
        Component.text("Chisel Axe").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD);
    return displayName.equals(newName) || displayName.equals(oldName);
  }

  public void giveChiselAxe(Player player) {
    if (player.getInventory().firstEmpty() != -1) {
      player.getInventory().addItem(createChiselAxe());
      player.sendMessage(Component.text("You received the Voxel Axe!").color(NamedTextColor.GOLD));
    } else {
      player.sendMessage(Component.text("Your inventory is full!").color(NamedTextColor.RED));
    }
  }
}
