package me.reube.SmallVoxels.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.managers.animation.AnimatedVoxelObject;
import me.reube.SmallVoxels.managers.animation.AnimationSequence;
import me.reube.SmallVoxels.managers.animation.AnimationSequenceStep;
import me.reube.SmallVoxels.managers.animation.VoxelAnimation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class AnimationSequenceGUI {
  public static final Component TITLE =
      Component.text("Animation Sequence").color(NamedTextColor.AQUA);
  public static final Component STEP_TITLE =
      Component.text("Sequence Step").color(NamedTextColor.AQUA);
  private static final Map<UUID, Integer> EDITING_STEPS = new HashMap<>();

  private final SmallVoxels plugin;

  public AnimationSequenceGUI(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  public void open(Player player) {
    Inventory gui = Bukkit.createInventory(null, 54, TITLE);
    AnimatedVoxelObject object = plugin.getAnimatedObjectManager().editorObject(player);
    if (object == null) {
      gui.setItem(
          22,
          button(
              Material.BARRIER,
              "No Animation Selected",
              List.of(
                  "Use Animation Axe Select mode first", "Then right-click Sequence mode again")));
      player.openInventory(gui);
      return;
    }

    AnimationSequence sequence = plugin.getAnimatedObjectManager().editorSequence(player);
    gui.setItem(
        4,
        choiceButton(
            sequence.loop ? Material.LIME_DYE : Material.GRAY_DYE,
            "Sequence Loop",
            sequence.loop ? "On" : "Off",
            "On|Repeats the complete sequence",
            "Off|Stops after the final step"));

    for (int i = 0; i < Math.min(18, sequence.steps.size()); i++) {
      AnimationSequenceStep step = sequence.steps.get(i);
      VoxelAnimation animation = object.animation(step.animationId);
      gui.setItem(
          i,
          button(
              Material.MUSIC_DISC_13,
              (i + 1) + ". " + displayName(animation, step.animationId),
              sequenceLore(object, animation, step, i + 1)));
    }

    int slot = 27;
    for (VoxelAnimation animation : object.animations) {
      if (slot >= 54) {
        break;
      }
      gui.setItem(
          slot++,
          button(
              Material.NOTE_BLOCK,
              displayName(animation, animation.id),
              availableLore(object, animation)));
    }

    gui.setItem(45, button(Material.ARROW, "Back", List.of("Return to animation settings")));
    gui.setItem(
        49,
        button(
            Material.COMPARATOR,
            "Step Settings",
            List.of(
                "Click any sequence step above",
                "Then edit triggers, delays, and wait behavior",
                "Add states from the lower list")));
    player.openInventory(gui);
  }

  public void openStep(Player player, int stepIndex) {
    Inventory gui = Bukkit.createInventory(null, 54, STEP_TITLE);
    AnimatedVoxelObject object = plugin.getAnimatedObjectManager().editorObject(player);
    AnimationSequence sequence = plugin.getAnimatedObjectManager().editorSequence(player);
    if (object == null || sequence == null || stepIndex < 0 || stepIndex >= sequence.steps.size()) {
      open(player);
      return;
    }
    EDITING_STEPS.put(player.getUniqueId(), stepIndex);
    AnimationSequenceStep step = sequence.steps.get(stepIndex);
    VoxelAnimation animation = object.animation(step.animationId);
    String trigger = readableTrigger(step.triggerType);

    gui.setItem(
        4,
        button(
            Material.MUSIC_DISC_13,
            "Step " + (stepIndex + 1) + ": " + displayName(animation, step.animationId),
            sequenceLore(object, animation, step, stepIndex + 1)));

    gui.setItem(
        10,
        triggerButton(
            "AUTO",
            trigger,
            Material.LIGHT_WEIGHTED_PRESSURE_PLATE,
            "Plays after the before delay"));
    gui.setItem(
        11,
        triggerButton(
            "PLAYER_NEAR", trigger, Material.PLAYER_HEAD, "Waits until enough players are nearby"));
    gui.setItem(
        12,
        triggerButton(
            "PLAYER_AWAY",
            trigger,
            Material.LEATHER_BOOTS,
            "Waits until players leave the radius"));
    gui.setItem(
        14,
        triggerButton(
            "ENTITY_NEAR", trigger, Material.ENDER_EYE, "Waits until an entity is nearby"));
    gui.setItem(
        15,
        triggerButton(
            "ENTITY_AWAY", trigger, Material.ENDER_PEARL, "Waits until entities leave the radius"));

    gui.setItem(
        19,
        button(
            Material.REDSTONE_TORCH,
            "Before Delay -20",
            List.of("Current: " + step.delayBeforeTicks + " ticks")));
    gui.setItem(
        20,
        button(
            Material.REDSTONE,
            "Before Delay +20",
            List.of("Current: " + step.delayBeforeTicks + " ticks")));
    gui.setItem(
        21,
        button(
            Material.REPEATER,
            "After Delay -20",
            List.of("Current: " + step.delayAfterTicks + " ticks")));
    gui.setItem(
        22,
        button(
            Material.COMPARATOR,
            "After Delay +20",
            List.of("Current: " + step.delayAfterTicks + " ticks")));

    gui.setItem(
        24,
        button(
            Material.SPYGLASS, "Radius -1", List.of("Current: " + step.triggerRadius + " blocks")));
    gui.setItem(
        25,
        button(
            Material.SPYGLASS, "Radius +1", List.of("Current: " + step.triggerRadius + " blocks")));
    gui.setItem(
        28,
        button(
            Material.NAME_TAG,
            "Required Count -1",
            List.of("Current: " + step.triggerPlayerCount)));
    gui.setItem(
        29,
        button(
            Material.NAME_TAG,
            "Required Count +1",
            List.of("Current: " + step.triggerPlayerCount)));

    gui.setItem(
        31,
        choiceButton(
            step.waitForCompletion ? Material.LIME_DYE : Material.GRAY_DYE,
            "Wait For Finish",
            step.waitForCompletion ? "On" : "Off",
            "On|Waits for this state to finish",
            "Off|Continues after this state starts"));
    gui.setItem(
        33,
        choiceButton(
            step.includeInvisibleEntities ? Material.LIME_DYE : Material.GRAY_DYE,
            "Invisible Entities",
            step.includeInvisibleEntities ? "Included" : "Ignored",
            "Included|Invisible entities count for triggers",
            "Ignored|Only visible entities count"));

    gui.setItem(38, button(Material.ARROW, "Move Left", List.of("Move this step earlier")));
    gui.setItem(
        40,
        button(Material.BARRIER, "Remove Step", List.of("Deletes this step from the sequence")));
    gui.setItem(42, button(Material.ARROW, "Move Right", List.of("Move this step later")));

    gui.setItem(45, button(Material.ARROW, "Back", List.of("Return to sequence")));
    gui.setItem(
        49,
        button(
            Material.CLOCK,
            "Timing Summary",
            List.of(
                "Before: " + step.delayBeforeTicks + " ticks",
                "Trigger: " + trigger,
                "After: " + step.delayAfterTicks + " ticks")));
    player.openInventory(gui);
  }

  public static int editingStep(Player player) {
    return EDITING_STEPS.getOrDefault(player.getUniqueId(), -1);
  }

  private List<String> sequenceLore(
      AnimatedVoxelObject object,
      VoxelAnimation animation,
      AnimationSequenceStep step,
      int stepNumber) {
    List<String> lore = new ArrayList<>(availableLore(object, animation));
    lore.add("Before delay: " + step.delayBeforeTicks + " ticks");
    lore.add("After delay: " + step.delayAfterTicks + " ticks");
    lore.add("Trigger: " + readableTrigger(step.triggerType));
    if (!"AUTO".equals(readableTrigger(step.triggerType))) {
      lore.add("Radius: " + step.triggerRadius + " blocks");
      lore.add("Players/entities: " + step.triggerPlayerCount);
      lore.add("Invisible entities: " + (step.includeInvisibleEntities ? "yes" : "no"));
    }
    lore.add("Wait for finish: " + (step.waitForCompletion ? "yes" : "no"));
    lore.add("Click: edit this step");
    return lore;
  }

  private List<String> availableLore(AnimatedVoxelObject object, VoxelAnimation animation) {
    int partCount =
        animation == null
            ? object.parts.size()
            : plugin.getAnimatedObjectManager().animationVoxelCount(object, animation);
    int blockCount =
        animation == null
            ? 0
            : plugin.getAnimatedObjectManager().animationBlockCount(object, animation);
    int chunkX = (int) Math.floor(object.originX) >> 4;
    int chunkZ = (int) Math.floor(object.originZ) >> 4;
    return List.of(
        "World: " + object.world,
        "Chunk: " + chunkX + ", " + chunkZ,
        "Voxels: " + partCount,
        "Real blocks: " + blockCount,
        "Left-click: add to sequence");
  }

  private String displayName(VoxelAnimation animation, String fallback) {
    if (animation == null) {
      return fallback;
    }
    return animation.displayName == null || animation.displayName.isBlank()
        ? animation.id
        : animation.displayName;
  }

  private String readableTrigger(String triggerType) {
    if (triggerType == null || triggerType.isBlank()) {
      return "AUTO";
    }
    return switch (triggerType.toUpperCase().replace("-", "_")) {
      case "PLAYER_NEAR", "PLAYER_DISTANCE", "NEAR" -> "PLAYER_NEAR";
      case "PLAYER_AWAY", "AWAY", "LEAVE" -> "PLAYER_AWAY";
      case "ENTITY_NEAR", "ENTITY_NEARBY" -> "ENTITY_NEAR";
      case "ENTITY_AWAY", "ENTITY_LEAVE" -> "ENTITY_AWAY";
      default -> "AUTO";
    };
  }

  private ItemStack triggerButton(String value, String current, Material material, String detail) {
    return button(
        value.equals(current) ? Material.LIME_CONCRETE : material,
        value,
        List.of(value.equals(current) ? "Selected" : "Click to select", detail));
  }

  private ItemStack button(Material material, String name, List<String> loreLines) {
    ItemStack item = new ItemStack(material);
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.displayName(Component.text(name).color(NamedTextColor.AQUA));
      List<Component> lore = new ArrayList<>();
      for (String line : loreLines) {
        lore.add(Component.text(line).color(NamedTextColor.GRAY));
      }
      meta.lore(lore);
      item.setItemMeta(meta);
    }
    return item;
  }

  private ItemStack choiceButton(
      Material material, String name, String selected, String... options) {
    List<String> lore = new ArrayList<>();
    lore.add("Left: next   Right: previous");
    lore.add("");
    for (String option : options) {
      String[] parts = option.split("\\|", 2);
      lore.add((parts[0].equalsIgnoreCase(selected) ? "> " : "  ") + parts[0]);
      if (parts.length > 1) lore.add("    " + parts[1]);
    }
    return button(material, name, lore);
  }
}
