package me.reube.SmallVoxels.managers;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.reube.SmallVoxels.SmallVoxels;
import org.bukkit.Bukkit;

public class VersionManager {

  private static final Pattern MINECRAFT_VERSION = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)");

  private final SmallVoxels plugin;

  public VersionManager(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  public boolean isSupportedServer() {
    String minecraftVersion = minecraftVersion();
    if (minecraftVersion == null || minecraftVersion.isBlank()) {
      plugin
          .getLogger()
          .warning(
              "Could not detect the Minecraft version. SmallVoxels will continue in compatibility"
                  + " mode.");
      return true;
    }

    if (isAtLeast121(minecraftVersion)) {
      return true;
    }

    plugin
        .getLogger()
        .severe(
            "SmallVoxels requires Minecraft 1.21 or newer. Detected "
                + minecraftVersion
                + " from '"
                + Bukkit.getBukkitVersion()
                + "'. Disabling plugin.");
    return false;
  }

  public String minecraftVersion() {
    String raw = Bukkit.getBukkitVersion();
    if (raw == null) {
      return "";
    }

    Matcher matcher = MINECRAFT_VERSION.matcher(raw.toLowerCase(Locale.ROOT));
    return matcher.find() ? matcher.group(1) : raw;
  }

  private boolean isAtLeast121(String version) {
    String[] parts = version.split("\\.");
    if (parts.length < 2) {
      return false;
    }

    try {
      int major = Integer.parseInt(parts[0]);
      int minor = Integer.parseInt(parts[1]);

      if (major > 1) {
        return true;
      }
      return major == 1 && minor >= 21;
    } catch (NumberFormatException ex) {
      return false;
    }
  }
}
