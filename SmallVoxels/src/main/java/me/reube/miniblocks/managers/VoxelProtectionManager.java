package me.reube.SmallVoxels.managers;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import me.reube.SmallVoxels.SmallVoxels;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

public class VoxelProtectionManager {

  private final SmallVoxels plugin;
  private final File file;
  private FileConfiguration data;
  private final java.util.Map<UUID, PendingRegion> pendingRegions = new java.util.HashMap<>();

  public VoxelProtectionManager(SmallVoxels plugin) {
    this.plugin = plugin;
    this.file = new File(plugin.getDataFolder(), "protection.yml");
    load();
  }

  public void load() {
    if (!plugin.getDataFolder().exists()) {
      plugin.getDataFolder().mkdirs();
    }
    data = YamlConfiguration.loadConfiguration(file);
  }

  public void save() {
    try {
      data.save(file);
    } catch (IOException ex) {
      plugin.getLogger().warning("Could not save voxel protection data: " + ex.getMessage());
    }
  }

  public boolean isWorldProtected(World world) {
    String path = "worlds_name." + world.getName() + ".protected-voxels";
    String legacyPath = "worlds." + world.getName() + ".protected-voxels";
    return plugin
        .getConfig()
        .getBoolean(
            path,
            plugin
                .getConfig()
                .getBoolean(
                    legacyPath, plugin.getConfig().getBoolean("defaults.protected-voxels", false)));
  }

  public boolean usesPlayerSchematics(World world) {
    String path = "worlds_name." + world.getName() + ".per-player-schematics";
    String legacyPath = "worlds." + world.getName() + ".per-player-schematics";
    return plugin
        .getConfig()
        .getBoolean(
            path,
            plugin
                .getConfig()
                .getBoolean(
                    legacyPath,
                    plugin.getConfig().getBoolean("defaults.per-player-schematics", false)));
  }

  public boolean canEdit(Player player, Block block) {
    if (plugin.getWorldGuardSupport() != null
        && !plugin.getWorldGuardSupport().canEdit(player, block)) {
      return false;
    }
    if (!isWorldProtected(block.getWorld())) {
      return true;
    }
    if (player.hasPermission("smallvoxels.protection.bypass")) {
      return true;
    }

    String owner = owner(block);
    if (owner == null || owner.equals(player.getUniqueId().toString())) {
      return true;
    }

    String playerId = player.getUniqueId().toString();
    return worldTrusted(block.getWorld(), owner, playerId) || regionTrusted(block, owner, playerId);
  }

  public void claimIfNeeded(Player player, Block block) {
    if (!isWorldProtected(block.getWorld())) {
      return;
    }
    if (owner(block) == null) {
      data.set(ownerPath(block), player.getUniqueId().toString());
      save();
    }
  }

  public String ownerOf(Block block) {
    return owner(block);
  }

  public void removeOwner(Block block) {
    data.set(ownerPath(block), null);
    save();
  }

  public void trustWorld(Player owner, OfflinePlayer trusted) {
    String path = "trusts.worlds." + owner.getWorld().getName() + "." + owner.getUniqueId();
    Set<String> trustedIds = new HashSet<>(data.getStringList(path));
    trustedIds.add(trusted.getUniqueId().toString());
    data.set(path, new ArrayList<>(trustedIds));
    save();
  }

  public void removeTrust(Player owner, OfflinePlayer trusted) {
    String worldName = owner.getWorld().getName();
    String trustedId = trusted.getUniqueId().toString();
    String path = "trusts.worlds." + worldName + "." + owner.getUniqueId();
    List<String> trustedIds = new ArrayList<>(data.getStringList(path));
    trustedIds.remove(trustedId);
    data.set(path, trustedIds);

    List<java.util.Map<?, ?>> regions = data.getMapList("trusts.regions");
    List<java.util.Map<String, Object>> kept = new ArrayList<>();
    for (java.util.Map<?, ?> raw : regions) {
      String rawOwner = String.valueOf(raw.get("owner"));
      String rawTrusted = String.valueOf(raw.get("trusted"));
      String rawWorld = String.valueOf(raw.get("world"));
      if (rawOwner.equals(owner.getUniqueId().toString())
          && rawTrusted.equals(trustedId)
          && rawWorld.equals(worldName)) {
        continue;
      }
      java.util.Map<String, Object> copy = new java.util.LinkedHashMap<>();
      for (java.util.Map.Entry<?, ?> entry : raw.entrySet()) {
        copy.put(String.valueOf(entry.getKey()), entry.getValue());
      }
      kept.add(copy);
    }
    data.set("trusts.regions", kept);
    save();
  }

  public List<String> trustedNames(Player owner) {
    String worldName = owner.getWorld().getName();
    Set<String> ids =
        new java.util.LinkedHashSet<>(
            data.getStringList("trusts.worlds." + worldName + "." + owner.getUniqueId()));
    for (java.util.Map<?, ?> region : data.getMapList("trusts.regions")) {
      if (String.valueOf(region.get("owner")).equals(owner.getUniqueId().toString())
          && String.valueOf(region.get("world")).equals(worldName)) {
        ids.add(String.valueOf(region.get("trusted")));
      }
    }

    List<String> names = new ArrayList<>();
    for (String id : ids) {
      try {
        OfflinePlayer player = Bukkit.getOfflinePlayer(UUID.fromString(id));
        names.add(player.getName() == null ? id : player.getName());
      } catch (IllegalArgumentException ignored) {
        names.add(id);
      }
    }
    return names;
  }

  public void startRegionTrust(Player owner, OfflinePlayer trusted) {
    pendingRegions.put(
        owner.getUniqueId(), new PendingRegion(trusted.getUniqueId(), trusted.getName()));
  }

  public boolean hasPendingRegion(Player player) {
    return pendingRegions.containsKey(player.getUniqueId());
  }

  public boolean handleRegionClick(Player player, Block block) {
    PendingRegion pending = pendingRegions.get(player.getUniqueId());
    if (pending == null) {
      return false;
    }

    if (pending.first == null) {
      pending.first = block.getLocation();
      plugin.getModeToggleListener().setStatus(player, "Trust corner 1 set");
      return true;
    }

    addRegionTrust(player, pending, pending.first, block.getLocation());
    pendingRegions.remove(player.getUniqueId());
    plugin.getModeToggleListener().setStatus(player, "Region trust saved");
    return true;
  }

  private void addRegionTrust(
      Player owner, PendingRegion pending, Location first, Location second) {
    int minX = Math.min(first.getBlockX(), second.getBlockX());
    int minY = Math.min(first.getBlockY(), second.getBlockY());
    int minZ = Math.min(first.getBlockZ(), second.getBlockZ());
    int maxX = Math.max(first.getBlockX(), second.getBlockX());
    int maxY = Math.max(first.getBlockY(), second.getBlockY());
    int maxZ = Math.max(first.getBlockZ(), second.getBlockZ());

    List<java.util.Map<?, ?>> existing = data.getMapList("trusts.regions");
    List<java.util.Map<String, Object>> regions = new ArrayList<>();
    for (java.util.Map<?, ?> raw : existing) {
      java.util.Map<String, Object> copy = new java.util.LinkedHashMap<>();
      for (java.util.Map.Entry<?, ?> entry : raw.entrySet()) {
        copy.put(String.valueOf(entry.getKey()), entry.getValue());
      }
      regions.add(copy);
    }

    java.util.Map<String, Object> region = new java.util.LinkedHashMap<>();
    region.put("owner", owner.getUniqueId().toString());
    region.put("trusted", pending.trusted.toString());
    region.put("trusted-name", pending.trustedName);
    region.put("world", first.getWorld().getName());
    region.put("min-x", minX);
    region.put("min-y", minY);
    region.put("min-z", minZ);
    region.put("max-x", maxX);
    region.put("max-y", maxY);
    region.put("max-z", maxZ);
    regions.add(region);
    data.set("trusts.regions", regions);
    save();
  }

  private boolean worldTrusted(World world, String owner, String trusted) {
    return data.getStringList("trusts.worlds." + world.getName() + "." + owner).contains(trusted);
  }

  private boolean regionTrusted(Block block, String owner, String trusted) {
    for (java.util.Map<?, ?> region : data.getMapList("trusts.regions")) {
      if (!String.valueOf(region.get("owner")).equals(owner)) {
        continue;
      }
      if (!String.valueOf(region.get("trusted")).equals(trusted)) {
        continue;
      }
      if (!String.valueOf(region.get("world")).equals(block.getWorld().getName())) {
        continue;
      }
      int x = block.getX();
      int y = block.getY();
      int z = block.getZ();
      if (x >= intValue(region, "min-x")
          && x <= intValue(region, "max-x")
          && y >= intValue(region, "min-y")
          && y <= intValue(region, "max-y")
          && z >= intValue(region, "min-z")
          && z <= intValue(region, "max-z")) {
        return true;
      }
    }
    return false;
  }

  private String owner(Block block) {
    return data.getString(ownerPath(block), null);
  }

  private String ownerPath(Block block) {
    String key = block.getX() + "," + block.getY() + "," + block.getZ();
    return "owners." + block.getWorld().getName().toLowerCase(Locale.ROOT) + "." + key;
  }

  private int intValue(java.util.Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    return Integer.parseInt(String.valueOf(value));
  }

  private static class PendingRegion {
    private final UUID trusted;
    private final String trustedName;
    private Location first;

    PendingRegion(UUID trusted, String trustedName) {
      this.trusted = trusted;
      this.trustedName = trustedName;
    }
  }
}
