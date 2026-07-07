package me.reube.SmallVoxels;

import me.reube.SmallVoxels.api.SmallVoxelsAPI;
import me.reube.SmallVoxels.api.SmallVoxelsService;
import me.reube.SmallVoxels.commands.GetchiselsCommand;
import me.reube.SmallVoxels.commands.SmallVoxelsCommand;
import me.reube.SmallVoxels.detail.DetailManager;
import me.reube.SmallVoxels.listeners.AnimationAxeListener;
import me.reube.SmallVoxels.listeners.AnimationChatInputListener;
import me.reube.SmallVoxels.listeners.AnimationEditorClickListener;
import me.reube.SmallVoxels.listeners.AnimationTriggerLinkListener;
import me.reube.SmallVoxels.listeners.BlockBreakListener;
import me.reube.SmallVoxels.listeners.ChiselInteractionListener;
import me.reube.SmallVoxels.listeners.ChunkLoadListener;
import me.reube.SmallVoxels.listeners.CollisionStandListener;
import me.reube.SmallVoxels.listeners.DetailToolListener;
import me.reube.SmallVoxels.listeners.GUIClickListener;
import me.reube.SmallVoxels.listeners.ModeToggleListener;
import me.reube.SmallVoxels.listeners.SurvivalVoxelListener;
import me.reube.SmallVoxels.listeners.TrustSelectionListener;
import me.reube.SmallVoxels.listeners.VoxelEntityListener;
import me.reube.SmallVoxels.managers.AnimatedObjectManager;
import me.reube.SmallVoxels.managers.AnimationAxeManager;
import me.reube.SmallVoxels.managers.ChiselManager;
import me.reube.SmallVoxels.managers.CollisionStandManager;
import me.reube.SmallVoxels.managers.DataManager;
import me.reube.SmallVoxels.managers.FallingBlockManager;
import me.reube.SmallVoxels.managers.PerformanceMonitor;
import me.reube.SmallVoxels.managers.SurvivalVoxelManager;
import me.reube.SmallVoxels.managers.VersionManager;
import me.reube.SmallVoxels.managers.VoxelClipboard;
import me.reube.SmallVoxels.managers.VoxelEditHistory;
import me.reube.SmallVoxels.managers.VoxelPreviewManager;
import me.reube.SmallVoxels.managers.VoxelProtectionManager;
import me.reube.SmallVoxels.managers.VoxelSelectionManager;
import me.reube.SmallVoxels.managers.WorldGuardSupport;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

public class SmallVoxels extends JavaPlugin {

  private static SmallVoxels instance;
  private DataManager dataManager;
  private ChiselManager chiselManager;
  private FallingBlockManager fallingBlockManager;
  private ModeToggleListener modeToggleListener;
  private VoxelEditHistory voxelEditHistory;
  private VoxelClipboard voxelClipboard;
  private VoxelPreviewManager voxelPreviewManager;
  private VoxelSelectionManager voxelSelectionManager;
  private VoxelProtectionManager voxelProtectionManager;
  private AnimatedObjectManager animatedObjectManager;
  private AnimationAxeManager animationAxeManager;
  private SurvivalVoxelManager survivalVoxelManager;
  private WorldGuardSupport worldGuardSupport;
  private SmallVoxelsAPI api;
  private DetailManager detailManager;
  private DetailToolListener detailToolListener;
  private PerformanceMonitor performanceMonitor;
  private ChiselInteractionListener chiselInteractionListener;

  @Override
  public void onEnable() {
    instance = this;
    saveDefaultConfig();
    ensureConfigDefaults();

    if (!new VersionManager(this).isSupportedServer()) {
      getServer().getPluginManager().disablePlugin(this);
      return;
    }

    dataManager = new DataManager(this);
    performanceMonitor = new PerformanceMonitor(this);
    chiselManager = new ChiselManager(this);
    fallingBlockManager = new FallingBlockManager(this);
    voxelEditHistory = new VoxelEditHistory(this);
    voxelClipboard = new VoxelClipboard();
    voxelPreviewManager = new VoxelPreviewManager(this);
    worldGuardSupport = new WorldGuardSupport(this);
    voxelProtectionManager = new VoxelProtectionManager(this);
    animatedObjectManager = new AnimatedObjectManager(this);
    animationAxeManager = new AnimationAxeManager(this);
    survivalVoxelManager = new SurvivalVoxelManager(this);
    voxelSelectionManager = new VoxelSelectionManager(this);
    modeToggleListener = new ModeToggleListener(this);
    detailManager = new DetailManager(this);
    detailToolListener = new DetailToolListener(this);
    api = new SmallVoxelsService(this);
    getServer()
        .getServicesManager()
        .register(SmallVoxelsAPI.class, api, this, org.bukkit.plugin.ServicePriority.Normal);

    dataManager.loadAllData();
    animatedObjectManager.loadAll();
    getServer().getScheduler().runTaskLater(this, this::refreshVoxelDisplaysBatched, 20L);

    getServer().getPluginManager().registerEvents(new TrustSelectionListener(this), this);
    chiselInteractionListener = new ChiselInteractionListener(this, modeToggleListener);
    getServer().getPluginManager().registerEvents(chiselInteractionListener, this);
    getServer().getPluginManager().registerEvents(modeToggleListener, this);
    getServer()
        .getPluginManager()
        .registerEvents(new GUIClickListener(this, modeToggleListener), this);
    getServer().getPluginManager().registerEvents(new BlockBreakListener(this), this);
    getServer().getPluginManager().registerEvents(new CollisionStandListener(this), this);
    getServer().getPluginManager().registerEvents(new VoxelEntityListener(this), this);
    getServer().getPluginManager().registerEvents(new ChunkLoadListener(this), this);
    getServer().getPluginManager().registerEvents(new AnimationEditorClickListener(this), this);
    getServer().getPluginManager().registerEvents(new AnimationAxeListener(this), this);
    getServer().getPluginManager().registerEvents(new AnimationChatInputListener(this), this);
    getServer().getPluginManager().registerEvents(new AnimationTriggerLinkListener(this), this);
    getServer().getPluginManager().registerEvents(new SurvivalVoxelListener(this), this);
    getServer().getPluginManager().registerEvents(detailToolListener, this);

    getCommand("getvoxel").setExecutor(new GetchiselsCommand(this));
    SmallVoxelsCommand smallVoxelsCommand = new SmallVoxelsCommand(this);
    getCommand("smallvoxels").setExecutor(smallVoxelsCommand);
    getCommand("smallvoxels").setTabCompleter(smallVoxelsCommand);

    getServer()
        .getScheduler()
        .scheduleSyncRepeatingTask(
            this,
            () -> {
              dataManager.saveAllData();
              animatedObjectManager.saveAll();
              dataManager.logHeavyVoxelAreas();
            },
            6000L,
            6000L);

    getLogger().info("SmallVoxels enabled.");
  }

  @Override
  public void onDisable() {
    if (dataManager != null) {
      dataManager.saveAllData();
    }

    if (fallingBlockManager != null) {
      fallingBlockManager.removePersistedVoxelEntities();
    }
    if (animatedObjectManager != null) {
      animatedObjectManager.shutdown();
    }
    if (voxelPreviewManager != null) {
      voxelPreviewManager.clearAll();
    }

    getLogger().info("SmallVoxels disabled.");
  }

  public static SmallVoxels getInstance() {
    return instance;
  }

  public DataManager getDataManager() {
    return dataManager;
  }

  public ChiselManager getChiselManager() {
    return chiselManager;
  }

  public FallingBlockManager getFallingBlockManager() {
    return fallingBlockManager;
  }

  public ModeToggleListener getModeToggleListener() {
    return modeToggleListener;
  }

  public VoxelEditHistory getVoxelEditHistory() {
    return voxelEditHistory;
  }

  public VoxelClipboard getVoxelClipboard() {
    return voxelClipboard;
  }

  public VoxelPreviewManager getVoxelPreviewManager() {
    return voxelPreviewManager;
  }

  public VoxelSelectionManager getVoxelSelectionManager() {
    return voxelSelectionManager;
  }

  public VoxelProtectionManager getVoxelProtectionManager() {
    return voxelProtectionManager;
  }

  public AnimatedObjectManager getAnimatedObjectManager() {
    return animatedObjectManager;
  }

  public AnimationAxeManager getAnimationAxeManager() {
    return animationAxeManager;
  }

  public SurvivalVoxelManager getSurvivalVoxelManager() {
    return survivalVoxelManager;
  }

  public WorldGuardSupport getWorldGuardSupport() {
    return worldGuardSupport;
  }

  /** Stable entry point for integrations. Prefer this over manager access. */
  public SmallVoxelsAPI getAPI() {
    return api;
  }

  public DetailManager getDetailManager() {
    return detailManager;
  }

  public DetailToolListener getDetailToolListener() {
    return detailToolListener;
  }

  public PerformanceMonitor getPerformanceMonitor() {
    return performanceMonitor;
  }

  public ChiselInteractionListener getChiselInteractionListener() {
    return chiselInteractionListener;
  }

  public int refreshVoxelDisplays() {
    int refreshed = 0;
    if (dataManager == null || fallingBlockManager == null) {
      return refreshed;
    }

    int removed = fallingBlockManager.removePersistedVoxelEntities();
    for (Block block : dataManager.getSavedVoxelBlocks()) {
      if (!dataManager.hasCarvedData(block)) {
        continue;
      }
      if (block.getType() == Material.BARRIER && dataManager.getVoxelPieces(block).isEmpty()) {
        continue;
      }
      fallingBlockManager.updateBlockDisplay(block, null);
      if (dataManager.isBlockLocked(block)) {
        org.bukkit.entity.ArmorStand stand = CollisionStandManager.spawnCollisionStand(block);
        dataManager.setCollisionStandUUID(block, stand.getUniqueId().toString());
      } else {
        dataManager.setCollisionStandUUID(block, null);
      }
      refreshed++;
    }
    if (removed > 0) {
      getLogger()
          .info("Removed " + removed + " stale SmallVoxels entities before rebuilding displays.");
    }
    return refreshed;
  }

  private void refreshVoxelDisplaysBatched() {
    fallingBlockManager.removePersistedVoxelEntities();
    java.util.Iterator<Block> blocks = dataManager.getSavedVoxelBlocks().iterator();
    final int blocksPerTick = Math.max(1, getConfig().getInt("loading.blocks-per-tick", 8));
    new org.bukkit.scheduler.BukkitRunnable() {
      @Override
      public void run() {
        int processed = 0;
        while (blocks.hasNext() && processed++ < blocksPerTick) {
          Block block = blocks.next();
          if (!dataManager.hasCarvedData(block)) continue;
          if (block.getType() == Material.BARRIER && dataManager.getVoxelPieces(block).isEmpty())
            continue;
          fallingBlockManager.updateBlockDisplay(block, null);
          if (dataManager.isBlockLocked(block)) {
            org.bukkit.entity.ArmorStand stand = CollisionStandManager.spawnCollisionStand(block);
            dataManager.setCollisionStandUUID(block, stand.getUniqueId().toString());
          } else {
            dataManager.setCollisionStandUUID(block, null);
          }
        }
        if (!blocks.hasNext()) cancel();
      }
    }.runTaskTimer(this, 0L, 1L);
  }

  private void ensureConfigDefaults() {
    getConfig().addDefault("startup-message", true);
    getConfig().addDefault("heavy-voxel-tracking.enabled", false);
    getConfig().addDefault("heavy-voxel-tracking.chunk-threshold", 400);
    getConfig().addDefault("heavy-voxel-tracking.block-threshold", 80);
    getConfig().addDefault("loading.blocks-per-tick", 8);
    getConfig().addDefault("rendering.view-range", 1.0);
    getConfig().addDefault("rendering.cull-hidden-voxels", true);
    getConfig().addDefault("lag-tracking.enabled", true);
    getConfig().addDefault("lag-tracking.warn-ops", true);
    getConfig().addDefault("lag-tracking.operation-warning-ms", 25.0);
    getConfig().addDefault("lag-tracking.cooldown-seconds", 60);
    getConfig().addDefault("defaults.survival.mining-individual", false);
    getConfig().addDefault("defaults.survival.crafting-table.enabled", false);
    getConfig().addDefault("defaults.survival.crafting-table.component.enabled", false);
    getConfig()
        .addDefault("defaults.survival.crafting-table.component.name", "smallvoxels_crafting");
    getConfig().addDefault("defaults.survival.stonecutter.enabled", false);
    getConfig().addDefault("defaults.survival.stonecutter.component.enabled", false);
    getConfig()
        .addDefault("defaults.survival.stonecutter.component.name", "smallvoxels_stonecutter");
    getConfig().options().copyDefaults(true);
    saveConfig();
  }

}
