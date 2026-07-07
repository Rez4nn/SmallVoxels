package me.reube.SmallVoxels.managers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import me.reube.SmallVoxels.managers.animation.AnimatedVoxelObject;
import org.bukkit.plugin.java.JavaPlugin;

public class AnimationStorageManager {
  private final JavaPlugin plugin;
  private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
  private final File objectFolder;
  private final Map<String, AnimatedVoxelObject> objects = new LinkedHashMap<>();

  public AnimationStorageManager(JavaPlugin plugin) {
    this.plugin = plugin;
    this.objectFolder = new File(plugin.getDataFolder(), "animations/objects");
    try {
      Files.createDirectories(objectFolder.toPath());
    } catch (IOException exception) {
      plugin
          .getLogger()
          .warning("Could not create animation data folder: " + exception.getMessage());
    }
  }

  public void loadAll() {
    objects.clear();
    File[] files = objectFolder.listFiles((dir, name) -> name.endsWith(".json"));
    if (files == null) {
      return;
    }
    for (File file : files) {
      try (var reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
        AnimatedVoxelObject object = gson.fromJson(reader, AnimatedVoxelObject.class);
        if (object != null && object.id != null && !object.id.isBlank()) {
          if (object.animations != null) {
            object.animations.forEach(animation -> animation.sort());
          }
          objects.put(key(object.id), object);
        }
      } catch (IOException | RuntimeException ex) {
        plugin
            .getLogger()
            .warning("Could not load animation object " + file.getName() + ": " + ex.getMessage());
      }
    }
  }

  public void saveAll() {
    for (AnimatedVoxelObject object : objects.values()) {
      save(object);
    }
  }

  public void save(AnimatedVoxelObject object) {
    if (object == null || object.id == null || object.id.isBlank()) {
      return;
    }
    File file = new File(objectFolder, safeName(object.id) + ".json");
    try (var writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
      gson.toJson(object, writer);
    } catch (IOException ex) {
      plugin
          .getLogger()
          .warning("Could not save animation object " + object.id + ": " + ex.getMessage());
    }
  }

  public void put(AnimatedVoxelObject object) {
    objects.put(key(object.id), object);
  }

  public AnimatedVoxelObject get(String id) {
    return objects.get(key(id));
  }

  public boolean delete(String id) {
    AnimatedVoxelObject removed = objects.remove(key(id));
    File file = new File(objectFolder, safeName(id) + ".json");
    boolean deleted = !file.exists() || file.delete();
    return removed != null && deleted;
  }

  public Collection<AnimatedVoxelObject> all() {
    return objects.values();
  }

  public boolean isValidId(String id) {
    return id != null && id.matches("[a-zA-Z0-9_-]{1,48}");
  }

  private String key(String id) {
    return id.toLowerCase(Locale.ROOT);
  }

  private String safeName(String id) {
    return id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
  }
}
