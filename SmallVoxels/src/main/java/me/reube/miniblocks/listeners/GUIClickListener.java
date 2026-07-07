package me.reube.SmallVoxels.listeners;

import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.ui.BlockSelectionGUI;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

public class GUIClickListener implements Listener {

  private final SmallVoxels plugin;
  private final ModeToggleListener modeToggleListener;

  public GUIClickListener(SmallVoxels plugin, ModeToggleListener modeToggleListener) {
    this.plugin = plugin;
    this.modeToggleListener = modeToggleListener;
  }

  @EventHandler
  public void onInventoryClick(InventoryClickEvent event) {
    if (!event.getView().title().equals(BlockSelectionGUI.TITLE)) {
      return;
    }

    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }

    if (event.getClickedInventory() == event.getView().getTopInventory()) {
      event.setCancelled(true);
      if (modeToggleListener.getToolMode(player) == me.reube.SmallVoxels.managers.ToolMode.ROTATE
          && (event.getSlot() == 4
              || event.getSlot() == 11
              || event.getSlot() == 13
              || event.getSlot() == 15)) {
        int direction = event.isRightClick() ? -1 : 1;
        if (event.getSlot() == 4) modeToggleListener.cycleRotateAngle(player, direction);
        else {
          String axis = event.getSlot() == 11 ? "x" : event.getSlot() == 13 ? "y" : "z";
          modeToggleListener.setRotateAxis(player, axis);
          modeToggleListener.setStatus(
              player,
              (axis.equals("y") ? "Rotation" : "Tilt")
                  + " axis selected: "
                  + axis.toUpperCase()
                  + " (Shift-scroll to apply)");
        }
        modeToggleListener.blockGUI.openGUI(player);
        return;
      }
      if (modeToggleListener.getToolMode(player) == me.reube.SmallVoxels.managers.ToolMode.MOVE
          && event.getSlot() == 4) {
        modeToggleListener.toggleMoveSpace(player);
        modeToggleListener.blockGUI.openGUI(player);
        return;
      }
      if (modeToggleListener.getToolMode(player) == me.reube.SmallVoxels.managers.ToolMode.MOVE
          && (event.getSlot() == 11
              || event.getSlot() == 12
              || event.getSlot() == 13
              || event.getSlot() == 14)) {
        int direction = event.isRightClick() ? -1 : 1;
        if (event.getSlot() == 11) modeToggleListener.cycleToolAxis(player, direction);
        else if (event.getSlot() == 13) modeToggleListener.cycleMoveStep(player, direction);
        else {
          int amount = modeToggleListener.getMoveStep(player) * (event.getSlot() == 14 ? 1 : -1);
          int dx = 0, dy = 0, dz = 0;
          if (modeToggleListener.getToolAxis(player).equals("x")) dx = amount;
          else if (modeToggleListener.getToolAxis(player).equals("z")) dz = amount;
          else dy = amount;
          boolean moved =
              modeToggleListener.isMoveLocalSpace(player)
                  ? plugin.getVoxelSelectionManager().moveSelectionLocal(player, dx, dy, dz)
                  : plugin.getVoxelSelectionManager().moveSelection(player, dx, dy, dz);
          modeToggleListener.setStatus(
              player,
              moved
                  ? "Moved " + modeToggleListener.getToolAxis(player).toUpperCase()
                  : "Move blocked");
        }
        modeToggleListener.blockGUI.openGUI(player);
        return;
      }
      if (modeToggleListener.getToolMode(player) == me.reube.SmallVoxels.managers.ToolMode.SCALE
          && (event.getSlot() == 4
              || event.getSlot() == 11
              || event.getSlot() == 13
              || event.getSlot() == 15)) {
        int direction = event.isRightClick() ? -1 : 1;
        if (event.getSlot() == 4) modeToggleListener.cycleScaleStep(player, direction);
        else if (event.getSlot() == 11) modeToggleListener.cycleScaleAxis(player, direction);
        else {
          double step = modeToggleListener.getScaleStep(player);
          double factor = event.getSlot() == 15 ? 1.0 + step : Math.max(0.05, 1.0 - step);
          boolean scaled =
              plugin
                  .getVoxelSelectionManager()
                  .scaleSelection(player, modeToggleListener.getScaleAxis(player), factor);
          modeToggleListener.setStatus(player, scaled ? "Scaled selection" : "Shift-select first");
        }
        modeToggleListener.blockGUI.openGUI(player);
        return;
      }
      if (event.getSlot() == 13) {
        if (modeToggleListener.getToolMode(player) == me.reube.SmallVoxels.managers.ToolMode.MOVE) {
          modeToggleListener.blockGUI.openGUI(player);
          return;
        }
        if (modeToggleListener.getToolMode(player)
            == me.reube.SmallVoxels.managers.ToolMode.REPLACE) {
          modeToggleListener.clearReplaceFromBlock(player);
          modeToggleListener.blockGUI.openGUI(player);
          return;
        }
        if (modeToggleListener.getToolMode(player)
            == me.reube.SmallVoxels.managers.ToolMode.BRUSH) {
          modeToggleListener.toggleBrushRound(player);
          modeToggleListener.blockGUI.openGUI(player);
          return;
        }
        if (modeToggleListener.getToolMode(player) == me.reube.SmallVoxels.managers.ToolMode.SET) {
          modeToggleListener.toggleSetLine(player);
          modeToggleListener.blockGUI.openGUI(player);
          return;
        }
        if (modeToggleListener.getToolMode(player)
            == me.reube.SmallVoxels.managers.ToolMode.REMOVE) {
          modeToggleListener.toggleRemoveMass(player);
          modeToggleListener.blockGUI.openGUI(player);
          return;
        }
        if (modeToggleListener.getToolMode(player)
            == me.reube.SmallVoxels.managers.ToolMode.PASTE) {
          modeToggleListener.flipPaste(player, "y");
          modeToggleListener.blockGUI.openGUI(player);
          return;
        }
        boolean enabled = !modeToggleListener.isPlacementPreviewEnabled(player);
        modeToggleListener.setPlacementPreview(player, enabled);
        modeToggleListener.blockGUI.openGUI(player);
      } else if (event.getSlot() == 4
          && modeToggleListener.getToolMode(player)
              == me.reube.SmallVoxels.managers.ToolMode.PASTE) {
        modeToggleListener.rotatePaste(player);
        modeToggleListener.blockGUI.openGUI(player);
      } else if (modeToggleListener.getToolMode(player)
              == me.reube.SmallVoxels.managers.ToolMode.MOVE
          && (event.getSlot() == 3
              || event.getSlot() == 5
              || event.getSlot() == 11
              || event.getSlot() == 15
              || event.getSlot() == 21
              || event.getSlot() == 23)) {
        int dx = 0;
        int dy = 0;
        int dz = 0;
        if (event.getSlot() == 3) {
          dx = -step(player);
        } else if (event.getSlot() == 5) {
          dx = step(player);
        } else if (event.getSlot() == 11) {
          dy = -step(player);
        } else if (event.getSlot() == 15) {
          dy = step(player);
        } else if (event.getSlot() == 21) {
          dz = -step(player);
        } else if (event.getSlot() == 23) {
          dz = step(player);
        }
        boolean moved =
            modeToggleListener.isMoveLocalSpace(player)
                ? plugin.getVoxelSelectionManager().moveSelectionLocal(player, dx, dy, dz)
                : plugin.getVoxelSelectionManager().moveSelection(player, dx, dy, dz);
        modeToggleListener.setStatus(player, moved ? "Moved selection" : "Move blocked");
        modeToggleListener.blockGUI.openGUI(player);
      } else if (event.getSlot() == 9
          && modeToggleListener.getToolMode(player)
              == me.reube.SmallVoxels.managers.ToolMode.BRUSH) {
        modeToggleListener.cycleBrushRadius(player, event.isRightClick() ? -1 : 1);
        modeToggleListener.blockGUI.openGUI(player);
      } else if (event.getSlot() == 11) {
        if (modeToggleListener.getToolMode(player)
            == me.reube.SmallVoxels.managers.ToolMode.PASTE) {
          modeToggleListener.flipPaste(player, "x");
        } else if (modeToggleListener.getToolMode(player)
            == me.reube.SmallVoxels.managers.ToolMode.BRUSH) {
          modeToggleListener.toggleBrushSmooth(player);
        } else if (modeToggleListener.getToolMode(player)
            == me.reube.SmallVoxels.managers.ToolMode.REPLACE) {
          modeToggleListener.armReplacePicker(player, "from");
        } else if (modeToggleListener.getToolMode(player)
            == me.reube.SmallVoxels.managers.ToolMode.SET) {
          modeToggleListener.toggleSetKeepCorner(player);
        } else if (modeToggleListener.getToolMode(player)
            == me.reube.SmallVoxels.managers.ToolMode.REMOVE) {
          modeToggleListener.toggleRemoveKeepCorner(player);
        } else {
          modeToggleListener.cycleBrushRadius(player, event.isRightClick() ? -1 : 1);
        }
        modeToggleListener.blockGUI.openGUI(player);
      } else if (event.getSlot() == 15) {
        if (modeToggleListener.getToolMode(player)
            == me.reube.SmallVoxels.managers.ToolMode.BRUSH) {
          modeToggleListener.toggleBrushMask(player);
        } else if (modeToggleListener.getToolMode(player)
            == me.reube.SmallVoxels.managers.ToolMode.SET) {
          modeToggleListener.toggleSetMask(player);
        } else if (modeToggleListener.getToolMode(player)
            == me.reube.SmallVoxels.managers.ToolMode.REPLACE) {
          modeToggleListener.armReplacePicker(player, "to");
        } else if (modeToggleListener.getToolMode(player)
            == me.reube.SmallVoxels.managers.ToolMode.PASTE) {
          modeToggleListener.flipPaste(player, "z");
        }
        modeToggleListener.blockGUI.openGUI(player);
      } else if (event.getSlot() == 16) {
        modeToggleListener.cyclePlayerVoxelScale(player, event.isRightClick() ? -1 : 1);
        modeToggleListener.blockGUI.openGUI(player);
      } else if (event.getSlot() == 24) {
        modeToggleListener.cycleToolAxis(player, event.isRightClick() ? -1 : 1);
        modeToggleListener.blockGUI.openGUI(player);
      } else if (event.getSlot() == 22
          && modeToggleListener.getToolMode(player)
              == me.reube.SmallVoxels.managers.ToolMode.PASTE) {
        modeToggleListener.togglePasteMask(player);
        modeToggleListener.blockGUI.openGUI(player);
      } else if (event.getSlot() == 22
          && modeToggleListener.getToolMode(player)
              == me.reube.SmallVoxels.managers.ToolMode.REPLACE) {
        boolean changed =
            plugin
                .getVoxelSelectionManager()
                .applyReplaceSelection(player, modeToggleListener.getReplaceToBlock(player));
        modeToggleListener.setStatus(player, changed ? "Selection replaced" : "Selection empty");
        modeToggleListener.blockGUI.openGUI(player);
      }
      return;
    }

    if (event.getClickedInventory() == player.getInventory()) {
      event.setCancelled(true);
      ItemStack clicked = event.getCurrentItem();
      if (clicked != null && clicked.getType() != Material.AIR) {
        selectBlock(player, clicked);
      }
    }
  }

  private void selectBlock(Player player, ItemStack item) {
    Material selected = item.getType();
    String blockName = selected.name();

    if (modeToggleListener.getToolMode(player) == me.reube.SmallVoxels.managers.ToolMode.REPLACE) {
      String picker = modeToggleListener.consumeReplacePicker(player);
      if ("from".equals(picker)) {
        modeToggleListener.setReplaceFromBlock(player, blockName);
        modeToggleListener.blockGUI.openGUI(player);
        return;
      }
      if ("to".equals(picker)) {
        modeToggleListener.setReplaceToBlock(player, blockName);
        modeToggleListener.blockGUI.openGUI(player);
        return;
      }
    }

    if (plugin.getVoxelSelectionManager().hasReplaceSelection(player)) {
      boolean changed =
          plugin
              .getVoxelSelectionManager()
              .applyReplaceSelection(player, modeToggleListener.getReplaceToBlock(player));
      player.closeInventory();
      modeToggleListener.setStatus(player, changed ? "Selection replaced" : "Selection empty");
      return;
    }

    if (item.hasItemMeta()) {
      modeToggleListener.setPlayerSelectedBlockWithMeta(player, blockName, item.clone());
    } else {
      modeToggleListener.setPlayerSelectedBlock(player, blockName);
    }

    modeToggleListener.blockGUI.openGUI(player);
    modeToggleListener.setStatus(player, "Selected " + selected.name().replace("_", " "));
  }

  private int step(Player player) {
    return me.reube.SmallVoxels.managers.VoxelPieceManager.getPieceSizeForScale(
        modeToggleListener.getPlayerVoxelScale(player));
  }
}
