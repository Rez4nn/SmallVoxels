package me.reube.SmallVoxels.managers;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import me.reube.SmallVoxels.SmallVoxels;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public class WorldGuardSupport {

  private final SmallVoxels plugin;
  private Object stateFlag;
  private boolean available;

  public WorldGuardSupport(SmallVoxels plugin) {
    this.plugin = plugin;
    load();
  }

  public boolean isAvailable() {
    return available && stateFlag != null;
  }

  public boolean canEdit(Player player, Block block) {
    if (!isAvailable() || player.hasPermission("smallvoxels.protection.bypass")) {
      return true;
    }
    try {
      Object worldGuard = worldGuard();
      Object platform = worldGuard.getClass().getMethod("getPlatform").invoke(worldGuard);
      Object regionContainer = platform.getClass().getMethod("getRegionContainer").invoke(platform);
      Object query = regionContainer.getClass().getMethod("createQuery").invoke(regionContainer);
      Object localPlayer = wrapPlayer(player);
      Object adaptedLocation = adapt(block.getLocation());
      Method testState = testStateMethod(query);
      if (testState == null) {
        return true;
      }
      Object flags = Array.newInstance(stateFlag.getClass(), 1);
      Array.set(flags, 0, stateFlag);
      Object result = testState.invoke(query, adaptedLocation, localPlayer, flags);
      return !(result instanceof Boolean allowed) || allowed;
    } catch (Exception ex) {
      return true;
    }
  }

  private Method testStateMethod(Object query) {
    for (Method method : query.getClass().getMethods()) {
      if ("testState".equals(method.getName()) && method.getParameterCount() == 3) {
        return method;
      }
    }
    return null;
  }

  private void load() {
    if (plugin.getServer().getPluginManager().getPlugin("WorldGuard") == null) {
      return;
    }
    try {
      Object registry = worldGuard().getClass().getMethod("getFlagRegistry").invoke(worldGuard());
      Class<?> stateFlagClass = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag");
      Constructor<?> constructor = stateFlagClass.getConstructor(String.class, boolean.class);
      Object flag = constructor.newInstance("smallvoxels-build", true);
      try {
        registry
            .getClass()
            .getMethod("register", Class.forName("com.sk89q.worldguard.protection.flags.Flag"))
            .invoke(registry, flag);
        stateFlag = flag;
      } catch (Exception ignored) {
        stateFlag =
            registry
                .getClass()
                .getMethod("get", String.class)
                .invoke(registry, "smallvoxels-build");
      }
      available = stateFlag != null;
      if (available) {
        plugin.getLogger().info("WorldGuard support enabled with smallvoxels-build flag.");
      }
    } catch (Exception ex) {
      plugin
          .getLogger()
          .warning(
              "WorldGuard was found, but SmallVoxels could not register its region flag: "
                  + ex.getMessage());
    }
  }

  private Object worldGuard() throws Exception {
    Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard");
    return worldGuardClass.getMethod("getInstance").invoke(null);
  }

  private Object wrapPlayer(Player player) throws Exception {
    Class<?> pluginClass = Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin");
    Object worldGuardPlugin = pluginClass.getMethod("inst").invoke(null);
    return pluginClass.getMethod("wrapPlayer", Player.class).invoke(worldGuardPlugin, player);
  }

  private Object adapt(Location location) throws Exception {
    Class<?> adapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
    return adapterClass.getMethod("adapt", Location.class).invoke(null, location);
  }
}
