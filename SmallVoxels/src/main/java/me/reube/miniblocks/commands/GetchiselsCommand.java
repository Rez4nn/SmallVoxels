package me.reube.SmallVoxels.commands;

import me.reube.SmallVoxels.SmallVoxels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class GetchiselsCommand implements CommandExecutor {

  private final SmallVoxels plugin;

  public GetchiselsCommand(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean onCommand(
      @NotNull CommandSender sender,
      @NotNull Command command,
      @NotNull String label,
      @NotNull String[] args) {
    if (!(sender instanceof Player)) {
      sender.sendMessage(
          Component.text("Only players can use this command!").color(NamedTextColor.RED));
      return true;
    }

    Player player = (Player) sender;

    if (args.length >= 1
        && ("animation".equalsIgnoreCase(args[0]) || "anim".equalsIgnoreCase(args[0]))) {
      if (!player.hasPermission("smallvoxels.animation.get")
          && !player.hasPermission("smallvoxels.animation.use")
          && !player.hasPermission("smallvoxels.admin")) {
        player.sendMessage(
            Component.text("You don't have permission to use this command!")
                .color(NamedTextColor.RED));
        return true;
      }
      plugin.getAnimationAxeManager().giveAnimationAxe(player);
      return true;
    }

    if (!player.hasPermission("smallvoxels.voxel.get")
        && !player.hasPermission("smallvoxels.chisel.get")) {
      player.sendMessage(
          Component.text("You don't have permission to use this command!")
              .color(NamedTextColor.RED));
      return true;
    }

    plugin.getChiselManager().giveChiselAxe(player);
    return true;
  }
}
