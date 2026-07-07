package me.reube.SmallVoxels.managers;

import java.util.UUID;
import me.reube.SmallVoxels.SmallVoxels;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.persistence.PersistentDataType;

/** Manages the invisible interaction targets used by locked voxel blocks. */
public final class CollisionStandManager {

  private CollisionStandManager() {}

  public static ArmorStand spawnCollisionStand(Block block) {
    Location centerLoc = block.getLocation().add(0.5, 0.5, 0.5);

    World world = block.getWorld();
    ArmorStand stand = (ArmorStand) world.spawnEntity(centerLoc, EntityType.ARMOR_STAND);

    stand.setVisible(false);
    stand.setMarker(true);
    stand.setSmall(true);
    stand.setGravity(false);
    stand.setInvulnerable(true);
    stand.customName(net.kyori.adventure.text.Component.text("carved_block_stand"));
    stand.setCustomNameVisible(false);
    SmallVoxels plugin = SmallVoxels.getInstance();
    if (plugin != null) {
      stand
          .getPersistentDataContainer()
          .set(new NamespacedKey(plugin, "collision_stand"), PersistentDataType.BYTE, (byte) 1);
    }

    return stand;
  }

  public static void removeCollisionStand(Block block, String standUUID) {
    if (standUUID == null || standUUID.isEmpty()) {
      return;
    }

    try {
      UUID uuid = UUID.fromString(standUUID);
      Entity entity = block.getWorld().getEntity(uuid);
      if (entity instanceof ArmorStand) {
        entity.remove();
      }
    } catch (IllegalArgumentException ignored) {
      // Saved UUIDs may refer to entities removed outside SmallVoxels.
    }
  }

  public static ArmorStand getCollisionStand(Block block, String standUUID) {
    if (standUUID == null || standUUID.isEmpty()) {
      return null;
    }

    try {
      UUID uuid = UUID.fromString(standUUID);
      Entity entity = block.getWorld().getEntity(uuid);
      if (entity instanceof ArmorStand) {
        return (ArmorStand) entity;
      }
    } catch (IllegalArgumentException ignored) {
      // Treat malformed legacy values as missing stands.
    }

    return null;
  }

  public static boolean isCollisionStand(Entity entity) {
    if (!(entity instanceof ArmorStand)) {
      return false;
    }

    ArmorStand stand = (ArmorStand) entity;
    SmallVoxels plugin = SmallVoxels.getInstance();
    if (plugin != null
        && stand
            .getPersistentDataContainer()
            .has(new NamespacedKey(plugin, "collision_stand"), PersistentDataType.BYTE)) {
      return true;
    }
    net.kyori.adventure.text.Component name = stand.customName();
    return name != null && name.toString().contains("carved_block_stand");
  }
}
