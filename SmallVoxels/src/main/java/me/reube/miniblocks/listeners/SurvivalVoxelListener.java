package me.reube.SmallVoxels.listeners;

import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.managers.SurvivalVoxelManager;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareInventoryResultEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.StonecutterInventory;

public class SurvivalVoxelListener implements Listener {
  private final SmallVoxels plugin;

  public SurvivalVoxelListener(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onPlaceVoxelItem(PlayerInteractEvent event) {
    if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
      return;
    }
    if (event.getAction() != Action.RIGHT_CLICK_BLOCK
        || event.getClickedBlock() == null
        || event.getBlockFace() == null) {
      return;
    }
    Player player = event.getPlayer();
    SurvivalVoxelManager survival = plugin.getSurvivalVoxelManager();
    if (!survival.isMiningIndividualEnabled(player.getWorld())) {
      return;
    }
    ItemStack item = player.getInventory().getItemInMainHand();
    if (!survival.isVoxelItem(item)) {
      return;
    }
    event.setCancelled(true);
    boolean placed =
        survival.placeVoxel(player, item, event.getClickedBlock(), event.getBlockFace());
    plugin
        .getModeToggleListener()
        .setStatus(player, placed ? "Placed voxel bit" : "Cannot place voxel bit");
  }

  @EventHandler(priority = EventPriority.HIGH)
  public void onPrepareCraft(PrepareItemCraftEvent event) {
    if (!(event.getView().getPlayer() instanceof Player player)) {
      return;
    }
    if (!plugin
        .getSurvivalVoxelManager()
        .isCraftingTableEnabled(player.getWorld(), event.getView())) {
      return;
    }
    ItemStack result =
        plugin.getSurvivalVoxelManager().craftResult(event.getInventory().getMatrix());
    if (result != null) {
      event.getInventory().setResult(result);
    }
  }

  @EventHandler(priority = EventPriority.HIGH)
  public void onPrepareResult(PrepareInventoryResultEvent event) {
    if (!(event.getView().getPlayer() instanceof Player player)) {
      return;
    }
    if (!plugin
        .getSurvivalVoxelManager()
        .isStonecutterEnabled(player.getWorld(), event.getView())) {
      return;
    }
    Inventory top = event.getView().getTopInventory();
    if (top instanceof StonecutterInventory stonecutter) {
      ItemStack result =
          plugin.getSurvivalVoxelManager().stonecutterResult(stonecutter.getInputItem());
      if (result != null) {
        event.setResult(result);
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGH)
  public void onResultClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    if (event.getSlotType() != InventoryType.SlotType.RESULT) {
      return;
    }
    Inventory top = event.getView().getTopInventory();
    boolean craftingTable =
        top instanceof CraftingInventory
            && plugin
                .getSurvivalVoxelManager()
                .isCraftingTableEnabled(player.getWorld(), event.getView());
    boolean stonecutter =
        top instanceof StonecutterInventory
            && plugin
                .getSurvivalVoxelManager()
                .isStonecutterEnabled(player.getWorld(), event.getView());
    if (!craftingTable && !stonecutter) {
      return;
    }
    ItemStack current = event.getCurrentItem();
    if (current == null || current.getType() == Material.AIR) {
      return;
    }
    if (craftingTable && top instanceof CraftingInventory crafting) {
      ItemStack expected = plugin.getSurvivalVoxelManager().craftResult(crafting.getMatrix());
      if (expected == null || !expected.isSimilar(current)) {
        return;
      }
      event.setCancelled(true);
      if (giveResult(player, expected.clone())) {
        consumeCraftingMatrix(crafting);
      }
      return;
    }
    if (stonecutter && top instanceof StonecutterInventory stonecutterInventory) {
      ItemStack expected =
          plugin.getSurvivalVoxelManager().stonecutterResult(stonecutterInventory.getInputItem());
      if (expected == null || !expected.isSimilar(current)) {
        return;
      }
      event.setCancelled(true);
      if (giveResult(player, expected.clone())) {
        ItemStack input = stonecutterInventory.getInputItem();
        if (input != null
            && input.getType() != Material.AIR
            && player.getGameMode() != GameMode.CREATIVE) {
          input.setAmount(input.getAmount() - 1);
          stonecutterInventory.setInputItem(input.getAmount() <= 0 ? null : input);
        }
      }
    }
  }

  private boolean giveResult(Player player, ItemStack result) {
    ItemStack cursor = player.getItemOnCursor();
    if (cursor == null || cursor.getType() == Material.AIR) {
      player.setItemOnCursor(result);
      return true;
    }
    if (cursor.isSimilar(result)
        && cursor.getAmount() + result.getAmount() <= cursor.getMaxStackSize()) {
      cursor.setAmount(cursor.getAmount() + result.getAmount());
      player.setItemOnCursor(cursor);
      return true;
    }
    return player.getInventory().addItem(result).isEmpty();
  }

  private void consumeCraftingMatrix(CraftingInventory crafting) {
    ItemStack[] matrix = crafting.getMatrix();
    for (int i = 0; i < matrix.length; i++) {
      ItemStack item = matrix[i];
      if (item == null || item.getType() == Material.AIR) {
        continue;
      }
      item.setAmount(item.getAmount() - 1);
      matrix[i] = item.getAmount() <= 0 ? null : item;
    }
    crafting.setMatrix(matrix);
  }
}
