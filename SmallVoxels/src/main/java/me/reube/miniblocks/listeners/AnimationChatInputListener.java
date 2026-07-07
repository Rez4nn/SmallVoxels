package me.reube.SmallVoxels.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.ui.AnimationEditorGUI;
import me.reube.SmallVoxels.ui.AnimationKeyframeManagerGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class AnimationChatInputListener implements Listener {
  private static final Map<UUID, PendingInput> PENDING = new ConcurrentHashMap<>();

  private final SmallVoxels plugin;

  public AnimationChatInputListener(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  public static void promptAnimationName(Player player) {
    PENDING.put(player.getUniqueId(), PendingInput.CREATE_ANIMATION);
    player.closeInventory();
    player.sendMessage(
        Component.text("Type the animation name in chat. Type cancel to stop.")
            .color(NamedTextColor.AQUA));
  }

  public static void promptStateName(Player player) {
    PENDING.put(player.getUniqueId(), PendingInput.CREATE_STATE);
    player.closeInventory();
    player.sendMessage(
        Component.text("Type the state name in chat. Type cancel to stop.")
            .color(NamedTextColor.AQUA));
  }

  @EventHandler
  public void onChat(AsyncChatEvent event) {
    Player player = event.getPlayer();
    PendingInput pending = PENDING.remove(player.getUniqueId());
    if (pending == null) {
      return;
    }

    event.setCancelled(true);
    String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
    if (input.equalsIgnoreCase("cancel")) {
      player.sendMessage(
          Component.text("Animation naming cancelled.").color(NamedTextColor.YELLOW));
      return;
    }

    Bukkit.getScheduler().runTask(plugin, () -> handleInput(player, pending, input));
  }

  private void handleInput(Player player, PendingInput pending, String input) {
    String id = input.toLowerCase(Locale.ROOT).replace(' ', '_');
    if (!id.matches("[a-z0-9_-]{1,48}")) {
      player.sendMessage(
          Component.text("Use letters, numbers, dashes, or underscores only.")
              .color(NamedTextColor.RED));
      return;
    }

    if (pending == PendingInput.CREATE_ANIMATION) {
      var object = plugin.getAnimatedObjectManager().createEmpty(player, id);
      if (object == null) {
        player.sendMessage(
            Component.text("That animation name is not available.").color(NamedTextColor.RED));
        return;
      }
      plugin.getAnimatedObjectManager().selectEditorObject(player, object.id);
      plugin.getAnimationAxeManager().setEditing(player, true);
      player.sendMessage(
          Component.text("Created animation " + object.id + ".").color(NamedTextColor.GREEN));
      new AnimationEditorGUI(plugin).open(player);
      return;
    }

    boolean created = plugin.getAnimatedObjectManager().createEditorState(player, id);
    player.sendMessage(
        Component.text(created ? "Created state " + id + "." : "That state name is not available.")
            .color(created ? NamedTextColor.GREEN : NamedTextColor.RED));
    new AnimationKeyframeManagerGUI(plugin).open(player);
  }

  private enum PendingInput {
    CREATE_ANIMATION,
    CREATE_STATE
  }
}
