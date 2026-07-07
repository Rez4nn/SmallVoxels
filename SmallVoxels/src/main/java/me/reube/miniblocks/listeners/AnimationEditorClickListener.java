package me.reube.SmallVoxels.listeners;

import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.ui.AnimationEditorGUI;
import me.reube.SmallVoxels.ui.AnimationKeyframeManagerGUI;
import me.reube.SmallVoxels.ui.AnimationSequenceGUI;
import me.reube.SmallVoxels.ui.AnimationStateSettingsGUI;
import me.reube.SmallVoxels.ui.AnimationTriggerSettingsGUI;
import me.reube.SmallVoxels.ui.AnimationWindSettingsGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public class AnimationEditorClickListener implements Listener {
  private final SmallVoxels plugin;
  private final AnimationEditorGUI gui;

  public AnimationEditorClickListener(SmallVoxels plugin) {
    this.plugin = plugin;
    this.gui = new AnimationEditorGUI(plugin);
  }

  @EventHandler
  public void onInventoryClick(InventoryClickEvent event) {
    if (event.getView().title().equals(AnimationKeyframeManagerGUI.TITLE)) {
      if (!canUseGui(event, "smallvoxels.animation.settings")) {
        return;
      }
      handleKeyframeManager(event);
      return;
    }
    if (event.getView().title().equals(AnimationStateSettingsGUI.TITLE)) {
      if (!canUseGui(event, "smallvoxels.animation.settings")) {
        return;
      }
      handleStateSettings(event);
      return;
    }
    if (event.getView().title().equals(AnimationTriggerSettingsGUI.TITLE)) {
      if (!canUseGui(event, "smallvoxels.animation.trigger")) {
        return;
      }
      handleTriggerSettings(event);
      return;
    }
    if (event.getView().title().equals(AnimationWindSettingsGUI.TITLE)) {
      if (!canUseGui(event, "smallvoxels.animation.wind")) {
        return;
      }
      handleWindSettings(event);
      return;
    }
    if (event.getView().title().equals(AnimationSequenceGUI.TITLE)) {
      if (!canUseGui(event, "smallvoxels.animation.manage")) {
        return;
      }
      handleSequenceSettings(event);
      return;
    }
    if (event.getView().title().equals(AnimationSequenceGUI.STEP_TITLE)) {
      if (!canUseGui(event, "smallvoxels.animation.manage")) {
        return;
      }
      handleSequenceStepSettings(event);
      return;
    }

    if (!event.getView().title().equals(AnimationEditorGUI.TITLE)) {
      return;
    }
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    if (!hasAnimationPermission(player, "smallvoxels.animation.settings")) {
      event.setCancelled(true);
      player.sendActionBar(Component.text("No animation GUI permission").color(NamedTextColor.RED));
      return;
    }

    event.setCancelled(true);
    if (event.getClickedInventory() != event.getView().getTopInventory()) {
      return;
    }

    switch (event.getSlot()) {
      case 0 -> {
        AnimationChatInputListener.promptAnimationName(player);
        return;
      }
      case 13 -> {
        if (plugin.getAnimatedObjectManager().editorObject(player) == null) {
          AnimationChatInputListener.promptAnimationName(player);
          return;
        } else {
          return;
        }
      }
      case 5 ->
          plugin.getAnimatedObjectManager().cycleEditorState(player, event.isRightClick() ? -1 : 1);
      case 6 -> plugin.getAnimationAxeManager().toggleEditing(player);
      case 11 ->
          plugin
              .getAnimatedObjectManager()
              .adjustEditorTick(player, event.isShiftClick() ? -50 : -10);
      case 12 ->
          plugin
              .getAnimatedObjectManager()
              .adjustEditorTick(player, event.isShiftClick() ? -5 : -1);
      case 14 ->
          plugin.getAnimatedObjectManager().adjustEditorTick(player, event.isShiftClick() ? 5 : 1);
      case 15 ->
          plugin
              .getAnimatedObjectManager()
              .adjustEditorTick(player, event.isShiftClick() ? 50 : 10);
      case 19 -> captureState(player);
      case 21 -> plugin.getAnimatedObjectManager().playEditor(player);
      case 22 -> {
        if (plugin.getAnimatedObjectManager().editorObject(player) == null) {
          selectNearbyAnimation(player);
        } else {
          plugin.getAnimatedObjectManager().stopEditor(player);
        }
      }
      case 23 -> {
        new AnimationKeyframeManagerGUI(plugin).open(player);
        return;
      }
      case 24 -> {
        new AnimationStateSettingsGUI(plugin).open(player);
        return;
      }
      case 25 -> {
        if (!hasAnimationPermission(player, "smallvoxels.animation.delete")) {
          player.sendActionBar(
              Component.text("No animation delete permission").color(NamedTextColor.RED));
          return;
        }
        deleteObject(player);
        return;
      }
      default -> {
        return;
      }
    }

    gui.open(player);
  }

  private void handleStateSettings(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    event.setCancelled(true);
    if (event.getClickedInventory() != event.getView().getTopInventory()) {
      return;
    }
    switch (event.getSlot()) {
      case 10 -> plugin.getAnimatedObjectManager().adjustEditorLength(player, -20);
      case 12 -> plugin.getAnimatedObjectManager().adjustEditorLength(player, 20);
      case 14 ->
          plugin.getAnimatedObjectManager().cycleEditorSpeed(player, event.isRightClick() ? -1 : 1);
      case 16 -> plugin.getAnimatedObjectManager().toggleEditorLoop(player);
      case 20 -> {
        if (!hasAnimationPermission(player, "smallvoxels.animation.trigger")) {
          player.sendActionBar(Component.text("No trigger permission").color(NamedTextColor.RED));
          return;
        }
        new AnimationTriggerSettingsGUI(plugin).open(player);
        return;
      }
      case 22 -> plugin.getAnimatedObjectManager().cycleEditorDefaultTransition(player);
      case 24 -> plugin.getAnimatedObjectManager().adjustEditorPriority(player, -1);
      case 26 -> plugin.getAnimatedObjectManager().adjustEditorPriority(player, 1);
      case 28 -> plugin.getAnimatedObjectManager().cycleEditorCaptureMode(player);
      case 30 -> {
        if (!hasAnimationPermission(player, "smallvoxels.animation.wind")) {
          player.sendActionBar(
              Component.text("No wind animation permission").color(NamedTextColor.RED));
          return;
        }
        plugin.getAnimatedObjectManager().toggleEditorStateWind(player);
      }
      case 32 -> {
        if (!hasAnimationPermission(player, "smallvoxels.animation.wind")) {
          player.sendActionBar(
              Component.text("No wind animation permission").color(NamedTextColor.RED));
          return;
        }
        new AnimationWindSettingsGUI(plugin).open(player);
        return;
      }
      case 34 -> {
        player.sendActionBar(
            Component.text("Use /sv anim sound <id> <state> <sound> [volume] [pitch]")
                .color(NamedTextColor.YELLOW));
        return;
      }
      case 31 -> {
        gui.open(player);
        return;
      }
      default -> {
        return;
      }
    }
    new AnimationStateSettingsGUI(plugin).open(player);
  }

  private void handleTriggerSettings(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    event.setCancelled(true);
    if (event.getClickedInventory() != event.getView().getTopInventory()) {
      return;
    }
    me.reube.SmallVoxels.managers.animation.VoxelAnimation animation =
        plugin.getAnimatedObjectManager().editorAnimation(player);
    String trigger =
        animation == null || animation.trigger == null
            ? "MANUAL"
            : animation.trigger.toUpperCase(java.util.Locale.ROOT);
    switch (event.getSlot()) {
      case 4 -> {
        String[] values = {"MANUAL", "PLAYER_DISTANCE", "REDSTONE", "ENTITY_NEARBY"};
        int index = java.util.Arrays.asList(values).indexOf(trigger);
        plugin
            .getAnimatedObjectManager()
            .setEditorTrigger(
                player,
                values[Math.floorMod(index + (event.isRightClick() ? -1 : 1), values.length)]);
      }
      case 10 -> plugin.getAnimatedObjectManager().setEditorTrigger(player, "MANUAL");
      case 12 -> plugin.getAnimatedObjectManager().setEditorTrigger(player, "PLAYER_DISTANCE");
      case 14 -> plugin.getAnimatedObjectManager().setEditorTrigger(player, "REDSTONE");
      case 16 -> plugin.getAnimatedObjectManager().setEditorTrigger(player, "ENTITY_NEARBY");
      case 19 -> plugin.getAnimatedObjectManager().adjustEditorTriggerRadius(player, -1);
      case 20 -> {
        if ("REDSTONE".equals(trigger)) {
          AnimationTriggerLinkListener.begin(player);
          player.closeInventory();
          player.sendMessage(
              Component.text("Right-click a button, pressure plate, or redstone dust to link it.")
                  .color(NamedTextColor.AQUA));
          return;
        }
        plugin.getAnimatedObjectManager().adjustEditorTriggerRadius(player, 1);
      }
      case 21 -> plugin.getAnimatedObjectManager().adjustEditorTriggerPlayers(player, -1);
      case 22 -> {
        if ("REDSTONE".equals(trigger)) {
          plugin.getAnimatedObjectManager().clearEditorTriggerLinks(player);
        } else if ("ENTITY_NEARBY".equals(trigger)) {
          plugin.getAnimatedObjectManager().toggleEditorTriggerInvisibleEntities(player);
        } else {
          plugin.getAnimatedObjectManager().adjustEditorTriggerPlayers(player, 1);
        }
      }
      case 28 ->
          plugin
              .getAnimatedObjectManager()
              .adjustEditorTriggerCooldown(player, event.isShiftClick() ? -100 : -20);
      case 29 ->
          plugin
              .getAnimatedObjectManager()
              .adjustEditorTriggerCooldown(player, event.isShiftClick() ? 100 : 20);
      case 31 ->
          plugin
              .getAnimatedObjectManager()
              .cycleEditorTriggerAfterAction(player, event.isRightClick() ? -1 : 1);
      case 33 ->
          plugin
              .getAnimatedObjectManager()
              .cycleEditorTriggerNextAnimation(player, event.isRightClick() ? -1 : 1);
      case 44 -> {
        new AnimationStateSettingsGUI(plugin).open(player);
        return;
      }
      default -> {
        return;
      }
    }
    new AnimationTriggerSettingsGUI(plugin).open(player);
  }

  private void handleWindSettings(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    event.setCancelled(true);
    if (event.getClickedInventory() != event.getView().getTopInventory()) {
      return;
    }
    double step = event.isShiftClick() ? 0.5 : 0.1;
    double displacementStep = event.isShiftClick() ? 1.0 : 0.25;
    double yawStep = 15.0;
    AnimationWindSettingsGUI.Page page = AnimationWindSettingsGUI.page(player);
    switch (event.getSlot()) {
      case 19 -> {
        if (page != AnimationWindSettingsGUI.Page.OVERVIEW) {
          return;
        }
        openWindPage(player, AnimationWindSettingsGUI.Page.MOTION);
      }
      case 21 -> {
        if (page != AnimationWindSettingsGUI.Page.OVERVIEW) {
          return;
        }
        openWindPage(player, AnimationWindSettingsGUI.Page.FABRIC);
      }
      case 23 -> {
        if (page != AnimationWindSettingsGUI.Page.OVERVIEW) {
          return;
        }
        openWindPage(player, AnimationWindSettingsGUI.Page.WEATHER);
      }
      case 25 -> {
        if (page != AnimationWindSettingsGUI.Page.OVERVIEW) {
          return;
        }
        openWindPage(player, AnimationWindSettingsGUI.Page.REACTIONS);
      }
      case 45 -> openWindPage(player, AnimationWindSettingsGUI.Page.OVERVIEW);
      case 46 -> openWindPage(player, AnimationWindSettingsGUI.Page.MOTION);
      case 47 -> openWindPage(player, AnimationWindSettingsGUI.Page.FABRIC);
      case 48 -> openWindPage(player, AnimationWindSettingsGUI.Page.WEATHER);
      case 50 -> openWindPage(player, AnimationWindSettingsGUI.Page.REACTIONS);
      case 49 -> {
        plugin.getAnimatedObjectManager().renderWindPreview(player);
        player.sendActionBar(
            Component.text(plugin.getAnimatedObjectManager().windStatus(player))
                .color(NamedTextColor.AQUA));
        return;
      }
      case 53 -> {
        new AnimationStateSettingsGUI(plugin).open(player);
        return;
      }
      default -> {
        if (!handleWindPageClick(player, page, event.getSlot(), step, displacementStep, yawStep)) {
          return;
        }
      }
    }
    new AnimationWindSettingsGUI(plugin).open(player, AnimationWindSettingsGUI.page(player));
  }

  private void handleSequenceSettings(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    event.setCancelled(true);
    if (event.getClickedInventory() != event.getView().getTopInventory()) {
      return;
    }
    int slot = event.getSlot();
    if (slot == 4) {
      if (event.isShiftClick()) {
        plugin.getAnimatedObjectManager().playEditorSequence(player);
      } else {
        plugin.getAnimatedObjectManager().toggleEditorSequenceLoop(player);
      }
      new AnimationSequenceGUI(plugin).open(player);
      return;
    }
    if (slot >= 0 && slot < 18) {
      new AnimationSequenceGUI(plugin).openStep(player, slot);
      return;
    }
    if (slot == 45) {
      gui.open(player);
      return;
    }
    if (slot >= 27 && slot < 54) {
      me.reube.SmallVoxels.managers.animation.AnimatedVoxelObject object =
          plugin.getAnimatedObjectManager().editorObject(player);
      int index = slot - 27;
      if (object != null && index >= 0 && index < object.animations.size()) {
        plugin
            .getAnimatedObjectManager()
            .addEditorSequenceStep(player, object.animations.get(index).id);
      }
      new AnimationSequenceGUI(plugin).open(player);
      return;
    }
  }

  private void handleSequenceStepSettings(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    event.setCancelled(true);
    if (event.getClickedInventory() != event.getView().getTopInventory()) {
      return;
    }
    int step = AnimationSequenceGUI.editingStep(player);
    if (step < 0) {
      new AnimationSequenceGUI(plugin).open(player);
      return;
    }
    switch (event.getSlot()) {
      case 10 ->
          plugin.getAnimatedObjectManager().setEditorSequenceStepTrigger(player, step, "AUTO");
      case 11 ->
          plugin
              .getAnimatedObjectManager()
              .setEditorSequenceStepTrigger(player, step, "PLAYER_NEAR");
      case 12 ->
          plugin
              .getAnimatedObjectManager()
              .setEditorSequenceStepTrigger(player, step, "PLAYER_AWAY");
      case 14 ->
          plugin
              .getAnimatedObjectManager()
              .setEditorSequenceStepTrigger(player, step, "ENTITY_NEAR");
      case 15 ->
          plugin
              .getAnimatedObjectManager()
              .setEditorSequenceStepTrigger(player, step, "ENTITY_AWAY");
      case 19 ->
          plugin
              .getAnimatedObjectManager()
              .adjustEditorSequenceStepDelay(player, step, true, event.isShiftClick() ? -100 : -20);
      case 20 ->
          plugin
              .getAnimatedObjectManager()
              .adjustEditorSequenceStepDelay(player, step, true, event.isShiftClick() ? 100 : 20);
      case 21 ->
          plugin
              .getAnimatedObjectManager()
              .adjustEditorSequenceStepDelay(
                  player, step, false, event.isShiftClick() ? -100 : -20);
      case 22 ->
          plugin
              .getAnimatedObjectManager()
              .adjustEditorSequenceStepDelay(player, step, false, event.isShiftClick() ? 100 : 20);
      case 24 ->
          plugin
              .getAnimatedObjectManager()
              .adjustEditorSequenceStepRadius(player, step, event.isShiftClick() ? -5 : -1);
      case 25 ->
          plugin
              .getAnimatedObjectManager()
              .adjustEditorSequenceStepRadius(player, step, event.isShiftClick() ? 5 : 1);
      case 28 ->
          plugin.getAnimatedObjectManager().adjustEditorSequenceStepPlayerCount(player, step, -1);
      case 29 ->
          plugin.getAnimatedObjectManager().adjustEditorSequenceStepPlayerCount(player, step, 1);
      case 31 -> plugin.getAnimatedObjectManager().toggleEditorSequenceStepWait(player, step);
      case 33 ->
          plugin.getAnimatedObjectManager().toggleEditorSequenceStepInvisibleEntities(player, step);
      case 38 -> {
        plugin.getAnimatedObjectManager().moveEditorSequenceStep(player, step, -1);
        step = Math.max(0, step - 1);
      }
      case 40 -> {
        plugin.getAnimatedObjectManager().removeEditorSequenceStep(player, step);
        new AnimationSequenceGUI(plugin).open(player);
        return;
      }
      case 42 -> {
        plugin.getAnimatedObjectManager().moveEditorSequenceStep(player, step, 1);
        step++;
      }
      case 45 -> {
        new AnimationSequenceGUI(plugin).open(player);
        return;
      }
      default -> {
        return;
      }
    }
    new AnimationSequenceGUI(plugin).openStep(player, step);
  }

  private boolean handleWindPageClick(
      Player player,
      AnimationWindSettingsGUI.Page page,
      int slot,
      double step,
      double displacementStep,
      double yawStep) {
    switch (page) {
      case MOTION -> {
        switch (slot) {
          case 20 ->
              plugin
                  .getAnimatedObjectManager()
                  .adjustWindSetting(player, "displacement", -displacementStep);
          case 22 ->
              plugin
                  .getAnimatedObjectManager()
                  .adjustWindSetting(player, "displacement", displacementStep);
          case 24 -> plugin.getAnimatedObjectManager().adjustWindSetting(player, "damping", -step);
          case 26 -> plugin.getAnimatedObjectManager().adjustWindSetting(player, "damping", step);
          case 29 -> plugin.getAnimatedObjectManager().adjustWindSetting(player, "yaw", -yawStep);
          case 31 -> plugin.getAnimatedObjectManager().adjustWindSetting(player, "yaw", yawStep);
          case 30 -> plugin.getAnimatedObjectManager().adjustWindSetting(player, "direction", step);
          default -> {
            return false;
          }
        }
        return true;
      }
      case FABRIC -> {
        switch (slot) {
          case 20 ->
              plugin.getAnimatedObjectManager().adjustWindSetting(player, "stiffness", -step);
          case 22 -> plugin.getAnimatedObjectManager().adjustWindSetting(player, "stiffness", step);
          case 24 -> plugin.getAnimatedObjectManager().adjustWindSetting(player, "idle", -step);
          case 26 -> plugin.getAnimatedObjectManager().adjustWindSetting(player, "idle", step);
          case 29 ->
              plugin.getAnimatedObjectManager().adjustWindSetting(player, "coherence", -step);
          case 31 -> plugin.getAnimatedObjectManager().adjustWindSetting(player, "coherence", step);
          default -> {
            return false;
          }
        }
        return true;
      }
      case WEATHER -> {
        switch (slot) {
          case 20 -> plugin.getAnimatedObjectManager().adjustWindSetting(player, "sun", -step);
          case 22 -> plugin.getAnimatedObjectManager().adjustWindSetting(player, "sun", step);
          case 24 -> plugin.getAnimatedObjectManager().adjustWindSetting(player, "rain", -step);
          case 26 -> plugin.getAnimatedObjectManager().adjustWindSetting(player, "rain", step);
          case 29 -> plugin.getAnimatedObjectManager().adjustWindSetting(player, "thunder", -step);
          case 31 -> plugin.getAnimatedObjectManager().adjustWindSetting(player, "thunder", step);
          default -> {
            return false;
          }
        }
        return true;
      }
      case REACTIONS -> {
        switch (slot) {
          case 20 -> plugin.getAnimatedObjectManager().toggleWindEnvironmentReactive(player);
          case 24 ->
              plugin.getAnimatedObjectManager().adjustWindSetting(player, "environment", -step);
          case 26 ->
              plugin.getAnimatedObjectManager().adjustWindSetting(player, "environment", step);
          case 29 -> plugin.getAnimatedObjectManager().adjustWindSetting(player, "players", -step);
          case 31 -> plugin.getAnimatedObjectManager().adjustWindSetting(player, "players", step);
          case 33 ->
              plugin.getAnimatedObjectManager().adjustWindSetting(player, "projectiles", -step);
          case 35 ->
              plugin.getAnimatedObjectManager().adjustWindSetting(player, "projectiles", step);
          case 38 -> plugin.getAnimatedObjectManager().adjustWindSetting(player, "velocity", -step);
          case 40 -> plugin.getAnimatedObjectManager().adjustWindSetting(player, "velocity", step);
          default -> {
            return false;
          }
        }
        return true;
      }
      default -> {
        return false;
      }
    }
  }

  private void openWindPage(Player player, AnimationWindSettingsGUI.Page page) {
    new AnimationWindSettingsGUI(plugin).open(player, page);
  }

  private boolean canUseGui(InventoryClickEvent event, String permission) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return false;
    }
    if (hasAnimationPermission(player, permission)) {
      return true;
    }
    event.setCancelled(true);
    player.sendActionBar(Component.text("No animation permission").color(NamedTextColor.RED));
    return false;
  }

  private boolean hasAnimationPermission(Player player, String permission) {
    return player.hasPermission("smallvoxels.admin")
        || player.hasPermission("smallvoxels.animation.use")
        || player.hasPermission(permission);
  }

  private void handleKeyframeManager(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    event.setCancelled(true);
    if (event.getClickedInventory() != event.getView().getTopInventory()) {
      return;
    }
    if (event.getSlot() == 49) {
      gui.open(player);
      return;
    }
    if (event.getSlot() == 45) {
      boolean duplicated =
          plugin.getAnimatedObjectManager().duplicatePreviousEditorStateKeyframe(player);
      player.sendActionBar(
          Component.text(duplicated ? "Created keyframe" : "Could not create keyframe")
              .color(duplicated ? NamedTextColor.GREEN : NamedTextColor.RED));
      new AnimationKeyframeManagerGUI(plugin).open(player);
      return;
    }
    if (event.getSlot() == 4) {
      plugin.getAnimatedObjectManager().cycleEditorState(player, event.isRightClick() ? -1 : 1);
      new AnimationKeyframeManagerGUI(plugin).open(player);
      return;
    }
    if (event.getSlot() == 6) {
      AnimationChatInputListener.promptStateName(player);
      return;
    }
    if (event.getSlot() == 8) {
      captureState(player);
      new AnimationKeyframeManagerGUI(plugin).open(player);
      return;
    }
    if (event.getSlot() < 9 || event.getSlot() >= 45) {
      return;
    }
    me.reube.SmallVoxels.managers.animation.VoxelAnimation animation =
        plugin.getAnimatedObjectManager().editorAnimation(player);
    if (animation == null) {
      return;
    }
    animation.sort();
    int index = event.getSlot() - 9;
    if (index < 0 || index >= animation.stateKeyframes.size()) {
      return;
    }
    int tick = animation.stateKeyframes.get(index).tick;
    if (event.isShiftClick() && event.getClick().isRightClick()) {
      boolean removed = plugin.getAnimatedObjectManager().deleteEditorStateKeyframe(player, tick);
      player.sendActionBar(
          Component.text(removed ? "Deleted keyframe at tick " + tick : "Keyframe not found")
              .color(removed ? NamedTextColor.GREEN : NamedTextColor.RED));
      new AnimationKeyframeManagerGUI(plugin).open(player);
      return;
    }
    if (event.isShiftClick()) {
      int amount = event.getClick().isRightClick() ? 1 : -1;
      boolean moved =
          plugin.getAnimatedObjectManager().moveEditorStateKeyframe(player, tick, amount);
      player.sendActionBar(
          Component.text(
                  moved
                      ? "Moved keyframe to tick " + Math.max(0, tick + amount)
                      : "That tick is already used")
              .color(moved ? NamedTextColor.GREEN : NamedTextColor.RED));
      new AnimationKeyframeManagerGUI(plugin).open(player);
      return;
    }
    if (event.getClick().isRightClick()) {
      boolean extended =
          plugin.getAnimatedObjectManager().extendEditorStateKeyframe(player, tick, 1);
      player.sendActionBar(
          Component.text(extended ? "Extended keyframe by 1 tick" : "No later keyframe to move")
              .color(extended ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
      new AnimationKeyframeManagerGUI(plugin).open(player);
      return;
    }
    boolean loaded =
        plugin.getAnimationAxeManager().isEditing(player)
            ? plugin.getAnimatedObjectManager().loadEditorStateKeyframeForEditing(player, tick)
            : loadPreviewTick(player, tick);
    player.sendActionBar(
        Component.text(
                loaded
                    ? (plugin.getAnimationAxeManager().isEditing(player)
                        ? "Loaded tick " + tick + " into the world for editing"
                        : "Previewing tick " + tick)
                    : "Could not load keyframe")
            .color(loaded ? NamedTextColor.AQUA : NamedTextColor.RED));
    new AnimationKeyframeManagerGUI(plugin).open(player);
  }

  private boolean loadPreviewTick(Player player, int tick) {
    plugin.getAnimatedObjectManager().setEditorTick(player, tick);
    return true;
  }

  private void captureState(Player player) {
    me.reube.SmallVoxels.managers.animation.VoxelAnimation animation =
        plugin.getAnimatedObjectManager().editorAnimation(player);
    String transition = animation == null ? "HARD" : animation.defaultTransition;
    String mode = animation == null ? "REPLACE" : animation.captureMode;
    boolean captured = plugin.getAnimatedObjectManager().captureEditorState(player, transition);
    player.sendActionBar(
        Component.text(
                captured
                    ? transition + " / " + mode + " voxel frame captured"
                    : "Use Capture Frame mode to select two corners")
            .color(captured ? NamedTextColor.GREEN : NamedTextColor.RED));
  }

  private void deleteObject(Player player) {
    me.reube.SmallVoxels.managers.AnimatedObjectManager.EditorState state =
        plugin.getAnimatedObjectManager().editor(player);
    boolean deleted = state != null && plugin.getAnimatedObjectManager().delete(state.objectId);
    player.closeInventory();
    player.sendMessage(
        Component.text(deleted ? "Deleted animation object." : "Animation object not found.")
            .color(deleted ? NamedTextColor.GREEN : NamedTextColor.RED));
  }

  private void selectNearbyAnimation(Player player) {
    String id = plugin.getAnimationAxeManager().currentNearbyAnimation(player);
    if (id == null) {
      player.sendActionBar(Component.text("No animations nearby").color(NamedTextColor.YELLOW));
      return;
    }
    boolean selected = plugin.getAnimatedObjectManager().selectEditorObject(player, id);
    player.sendActionBar(
        Component.text(selected ? "Selected " + id : "Animation not found")
            .color(selected ? NamedTextColor.GREEN : NamedTextColor.RED));
  }
}
