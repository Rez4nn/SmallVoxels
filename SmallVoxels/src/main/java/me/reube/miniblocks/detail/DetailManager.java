package me.reube.SmallVoxels.detail;

import java.util.*;
import me.reube.SmallVoxels.SmallVoxels;
import me.reube.SmallVoxels.managers.VoxelPiece;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

public final class DetailManager {
  private static final int REGION_LIMIT = 4096;
  private final SmallVoxels plugin;
  private final Map<UUID, Settings> settings = new HashMap<>();

  public DetailManager(SmallVoxels plugin) {
    this.plugin = plugin;
  }

  public Settings settings(Player player) {
    return settings.computeIfAbsent(player.getUniqueId(), ignored -> new Settings());
  }

  public void disable(Player player) {
    settings(player).mode = DetailToolMode.OFF;
  }

  public boolean apply(Player player, Block source, BlockFace face, Vector hit) {
    Settings s = settings(player);
    if (s.mode == DetailToolMode.OFF || !face.isCartesian()) return false;
    Support support = findSupport(source, hit);
    if (support == null) return false;
    int selectedLayer = s.layer;
    if (!s.connectedLayers || selectedLayer == 0) {
      try {
        s.carveLayer = selectedLayer < 0;
        return applySingle(player, source, face, hit, support);
      } finally {
        s.carveLayer = false;
      }
    }
    boolean changed = false;
    int direction = selectedLayer > 0 ? 1 : -1;
    try {
      for (int layer = 0; ; layer += direction) {
        s.layer = layer;
        s.carveLayer = selectedLayer < 0 && layer != selectedLayer;
        s.supportExpansion = selectedLayer > 0 ? Math.min(4, selectedLayer - layer) : 0;
        changed |= applySingle(player, source, face, hit, support);
        if (layer == selectedLayer) break;
      }
    } finally {
      s.layer = selectedLayer;
      s.carveLayer = false;
      s.supportExpansion = 0;
    }
    return changed;
  }

  private boolean applySingle(
      Player player, Block source, BlockFace face, Vector hit, Support support) {
    Settings s = settings(player);
    int cellSize = Math.min(16 / s.resolution, support.size());
    int localNormal = normalCoordinate(support, face, s.layer, cellSize);
    int worldNormal =
        switch (face) {
          case EAST, WEST -> source.getX() * 16 + localNormal;
          case UP, DOWN -> source.getY() * 16 + localNormal;
          default -> source.getZ() * 16 + localNormal;
        };
    int targetAxisBlock = Math.floorDiv(worldNormal, 16);
    Block detail =
        switch (face) {
          case EAST, WEST ->
              source.getWorld().getBlockAt(targetAxisBlock, source.getY(), source.getZ());
          case UP, DOWN ->
              source.getWorld().getBlockAt(source.getX(), targetAxisBlock, source.getZ());
          default -> source.getWorld().getBlockAt(source.getX(), source.getY(), targetAxisBlock);
        };
    boolean sameBlock = detail.equals(source);
    if (!sameBlock
        && detail.getType() != Material.AIR
        && detail.getType() != Material.BARRIER
        && !plugin.getDataManager().hasCarvedData(detail)) return false;
    if (!plugin.getVoxelProtectionManager().canEdit(player, detail)) return false;
    Material baseMaterial = source.getType();
    List<VoxelPiece> before = plugin.getVoxelEditHistory().snapshot(detail);
    List<VoxelPiece> pieces = plugin.getDataManager().getVoxelPieces(detail);
    if (!plugin.getDataManager().hasCarvedData(detail) && sameBlock) {
      if (baseMaterial == Material.AIR
          || baseMaterial == Material.BARRIER
          || !baseMaterial.isBlock()) return false;
      pieces =
          new ArrayList<>(
              List.of(
                  new VoxelPiece(
                      0, 0, 0, 16, baseMaterial.name(), source.getBlockData().getAsString())));
    }
    boolean naturalized = false;
    if (s.naturalize) {
      for (VoxelPiece piece : pieces)
        if (piece.size > cellSize) {
          String natural = naturalMaterial(piece.material);
          if (!natural.equals(piece.material)) {
            piece.material = natural;
            naturalized = true;
          }
        }
    }
    SurfaceTarget target =
        target(
            source,
            detail,
            face,
            hit == null ? new Vector(.5, .5, .5) : hit,
            s,
            baseMaterial,
            Math.floorMod(worldNormal, 16),
            sameBlock,
            support,
            cellSize);
    int changed =
        switch (s.mode) {
          case BRUSH -> paintBrush(pieces, target, s.brush);
          case TRIM -> paintTrim(pieces, target, s.material);
          case DAMAGE -> paintDamage(pieces, target);
          case OVERGROW -> paintBrush(pieces, target, BrushType.MOSS);
          case STAMP -> paintPreset(pieces, target, s.preset);
          default -> 0;
        };
    if (changed == 0 && support.size() <= Math.max(2, cellSize))
      changed = paintFallback(pieces, target, s);
    if (changed == 0 && !naturalized) return false;
    me.reube.SmallVoxels.managers.VoxelPieceManager.compact(pieces);
    detail.setType(Material.BARRIER, false);
    plugin.getVoxelProtectionManager().claimIfNeeded(player, detail);
    plugin.getDataManager().setVoxelPieces(detail, pieces);
    plugin.getFallingBlockManager().updateBlockDisplay(detail, null);
    plugin.getVoxelEditHistory().remember(player, detail, before, pieces);
    return true;
  }

  private int paintFallback(List<VoxelPiece> pieces, SurfaceTarget target, Settings settings) {
    int size = target.cellSize();
    double rawU =
        switch (target.face()) {
          case EAST, WEST -> target.hitPosition().getZ() * 16;
          default -> target.hitPosition().getX() * 16;
        };
    double rawV =
        switch (target.face()) {
          case UP, DOWN -> target.hitPosition().getZ() * 16;
          default -> target.hitPosition().getY() * 16;
        };
    int u =
        Math.max(
            target.minU(),
            Math.min(
                target.maxU() - size,
                target.minU() + Math.floorDiv((int) rawU - target.minU(), size) * size));
    int v =
        Math.max(
            target.minV(),
            Math.min(
                target.maxV() - size,
                target.minV() + Math.floorDiv((int) rawV - target.minV(), size) * size));
    Random random = new Random(cellSeed(target, settings.brush, u, v));
    Material material =
        switch (settings.mode) {
          case BRUSH -> material(settings.brush, target.baseMaterial(), random);
          case DAMAGE -> Material.DEEPSLATE;
          case OVERGROW -> Material.MOSS_BLOCK;
          default -> settings.material;
        };
    return put(
            pieces,
            target,
            point(target, u, v, size),
            paletteMaterial(target, material, random),
            size)
        ? 1
        : 0;
  }

  private Support findSupport(Block source, Vector hit) {
    List<VoxelPiece> pieces = plugin.getDataManager().getVoxelPieces(source);
    if (pieces.isEmpty()) {
      return source.getType() != Material.AIR && source.getType() != Material.BARRIER
          ? new Support(0, 0, 0, 16)
          : null;
    }
    Vector position = hit == null ? new Vector(.5, .5, .5) : hit;
    int x = Math.max(0, Math.min(15, (int) Math.floor(position.getX() * 16)));
    int y = Math.max(0, Math.min(15, (int) Math.floor(position.getY() * 16)));
    int z = Math.max(0, Math.min(15, (int) Math.floor(position.getZ() * 16)));
    VoxelPiece piece = me.reube.SmallVoxels.managers.VoxelPieceManager.getPieceAt(pieces, x, y, z);
    if (piece == null)
      piece =
          pieces.stream()
              .min(
                  java.util.Comparator.comparingDouble(
                      p ->
                          Math.pow(p.x + p.size / 2.0 - x, 2)
                              + Math.pow(p.y + p.size / 2.0 - y, 2)
                              + Math.pow(p.z + p.size / 2.0 - z, 2)))
              .orElse(null);
    return piece == null ? null : new Support(piece.x, piece.y, piece.z, piece.size);
  }

  private int normalCoordinate(Support support, BlockFace face, int layer, int size) {
    return switch (face) {
      case EAST ->
          layer > 0
              ? support.x + support.size + (layer - 1) * size
              : support.x + support.size - size + layer * size;
      case WEST -> layer > 0 ? support.x - layer * size : support.x - layer * size;
      case UP ->
          layer > 0
              ? support.y + support.size + (layer - 1) * size
              : support.y + support.size - size + layer * size;
      case DOWN -> layer > 0 ? support.y - layer * size : support.y - layer * size;
      case SOUTH ->
          layer > 0
              ? support.z + support.size + (layer - 1) * size
              : support.z + support.size - size + layer * size;
      case NORTH -> layer > 0 ? support.z - layer * size : support.z - layer * size;
      default -> 0;
    };
  }

  private String naturalMaterial(String material) {
    return switch (material) {
      case "GRASS_BLOCK", "PODZOL", "MYCELIUM", "DIRT_PATH", "FARMLAND" -> "DIRT";
      case "ROOTED_DIRT" -> "COARSE_DIRT";
      default -> material;
    };
  }

  private SurfaceTarget target(
      Block source,
      Block detail,
      BlockFace face,
      Vector hit,
      Settings s,
      Material baseMaterial,
      int layerDepth,
      boolean inlaid,
      Support support,
      int cellSize) {
    int minU =
        switch (face) {
          case EAST, WEST -> support.z;
          default -> support.x;
        };
    int maxU = minU + support.size;
    int minV =
        switch (face) {
          case UP, DOWN -> support.z;
          default -> support.y;
        };
    int maxV = minV + support.size;
    double rawU =
        switch (face) {
          case EAST, WEST -> hit.getZ() * 16;
          default -> hit.getX() * 16;
        };
    double rawV =
        switch (face) {
          case UP, DOWN -> hit.getZ() * 16;
          default -> hit.getY() * 16;
        };
    double u = Math.max(0, Math.min(.999, (rawU - minU) / Math.max(1, support.size)));
    double v = Math.max(0, Math.min(.999, (rawV - minV) / Math.max(1, support.size)));
    if (face == BlockFace.WEST || face == BlockFace.NORTH) u = 1.0 - u;
    if (face == BlockFace.DOWN) v = 1.0 - v;
    double[] distances = {u, 1 - u, v, 1 - v};
    int nearest = 0;
    for (int i = 1; i < 4; i++) if (distances[i] < distances[nearest]) nearest = i;
    long seed = 1469598103934665603L;
    seed = (seed ^ source.getWorld().getUID().hashCode()) * 1099511628211L;
    seed = (seed ^ source.getX()) * 1099511628211L;
    seed = (seed ^ source.getY()) * 1099511628211L;
    seed = (seed ^ source.getZ()) * 1099511628211L;
    seed = (seed ^ face.ordinal()) * 1099511628211L;
    return new SurfaceTarget(
        source.getWorld(),
        source,
        detail,
        face,
        hit,
        EdgePattern.values()[nearest],
        s.material,
        baseMaterial,
        s.intensity,
        s.resolution,
        List.copyOf(s.palette),
        s.trimShape,
        layerDepth,
        inlaid,
        s.carveLayer,
        s.supportExpansion,
        cellSize,
        minU,
        maxU,
        minV,
        maxV,
        layerDepth,
        seed);
  }

  private int paintBrush(List<VoxelPiece> pieces, SurfaceTarget t, BrushType type) {
    int changed = 0;
    int size = t.cellSize();
    for (int u = t.minU(); u < t.maxU(); u += size)
      for (int v = t.minV(); v < t.maxV(); v += size) {
        int sampleU = u + size / 2;
        int sampleV = v + size / 2;
        int[] global = globalSurfaceCoordinates(t, sampleU, sampleV);
        boolean selected = matchesBrush(type, t, sampleU, sampleV, global[0], global[1]);
        for (int du = -t.supportExpansion(); !selected && du <= t.supportExpansion(); du++)
          for (int dv = -t.supportExpansion(); !selected && dv <= t.supportExpansion(); dv++)
            selected =
                matchesBrush(
                    type,
                    t,
                    sampleU + du * size,
                    sampleV + dv * size,
                    global[0] + du * size,
                    global[1] + dv * size);
        if (!selected) continue;
        Random random = new Random(cellSeed(t, type, global[0], global[1]));
        Material mat = paletteMaterial(t, material(type, t.baseMaterial(), random), random);
        changed += put(pieces, t, point(t, u, v, size), mat, size) ? 1 : 0;
      }
    return changed;
  }

  private boolean matchesBrush(
      BrushType type, SurfaceTarget target, int u, int v, int globalU, int globalV) {
    Random random = new Random(cellSeed(target, type, globalU, globalV));
    double density = density(type, u, v, globalU, globalV, target, random);
    return random.nextDouble() <= density * target.intensity();
  }

  private double density(
      BrushType type, int u, int v, int globalU, int globalV, SurfaceTarget t, Random r) {
    return switch (type) {
      case MOSS ->
          coherentNoise(globalU, globalV, surfaceSeed(t))
              * (t.face() == BlockFace.UP ? .78 : .42)
              * (1.15 - v / 28.0);
      case CRACKS ->
          crackDistance(globalU, globalV, surfaceSeed(t)) <= Math.max(1, 16 / t.resolution())
              ? .95
              : .008;
      case DIRT -> .28;
      case SNOW -> t.face() == BlockFace.UP ? .72 : .10;
      case ASH -> .36;
      case RUNES -> ((u == 4 || u == 11 || v == 3 || v == 12) && (u + v) % 3 != 0) ? .85 : 0;
      case WOODEN_BEAM -> (u < 3 || u > 12 || v < 3 || v > 12) ? .9 : 0;
      case STONE_DETAIL -> ((u + v) % 5 == 0 || u == 1 || v == 1) ? .55 : .04;
    };
  }

  private Material material(BrushType type, Material base, Random r) {
    return switch (type) {
      case MOSS -> r.nextInt(4) == 0 ? Material.OAK_LEAVES : Material.MOSS_BLOCK;
      case CRACKS -> base.name().contains("BRICK") ? Material.DEEPSLATE_BRICKS : Material.DEEPSLATE;
      case DIRT -> r.nextBoolean() ? Material.DIRT : Material.COARSE_DIRT;
      case SNOW -> Material.SNOW_BLOCK;
      case ASH -> r.nextBoolean() ? Material.GRAY_CONCRETE_POWDER : Material.BLACK_CONCRETE_POWDER;
      case RUNES -> Material.SEA_LANTERN;
      case WOODEN_BEAM -> Material.SPRUCE_PLANKS;
      case STONE_DETAIL -> Material.STONE_BRICKS;
    };
  }

  private int paintTrim(List<VoxelPiece> pieces, SurfaceTarget t, Material material) {
    if (t.trimShape() != TrimShape.EDGE) return paintCurvedTrim(pieces, t, material, t.trimShape());
    int changed = 0;
    int size = t.cellSize(), cells = Math.max(1, t.maxU() - t.minU()) / size;
    int rows = Math.max(1, t.maxV() - t.minV()) / size;
    int width = Math.min(cells, cells >= 8 ? 2 + t.supportExpansion() : 1 + t.supportExpansion());
    int length =
        (t.nearestEdge() == EdgePattern.MIN_U || t.nearestEdge() == EdgePattern.MAX_U)
            ? rows
            : cells;
    for (int n = 0; n < length; n++)
      for (int w = 0; w < width; w++) {
        int u = 0, v = 0;
        switch (t.nearestEdge()) {
          case MIN_U -> {
            u = t.minU() + w * size;
            v = t.minV() + n * size;
          }
          case MAX_U -> {
            u = t.maxU() - size - w * size;
            v = t.minV() + n * size;
          }
          case MIN_V -> {
            u = t.minU() + n * size;
            v = t.minV() + w * size;
          }
          case MAX_V -> {
            u = t.minU() + n * size;
            v = t.maxV() - size - w * size;
          }
        }
        Random random = new Random(cellSeed(t, BrushType.WOODEN_BEAM, u, v));
        changed +=
            put(pieces, t, point(t, u, v, size), paletteMaterial(t, material, random), size)
                ? 1
                : 0;
      }
    return changed;
  }

  private int paintDamage(List<VoxelPiece> pieces, SurfaceTarget t) {
    int changed = paintBrush(pieces, t, BrushType.CRACKS);
    Random r = new Random(t.randomSeed() ^ 0xDABA6E);
    int size = t.cellSize(),
        columns = Math.max(1, (t.maxU() - t.minU()) / size),
        rows = Math.max(1, (t.maxV() - t.minV()) / size);
    for (int i = 0; i < Math.max(2, columns / 2); i++) {
      int u = t.minU() + r.nextInt(columns) * size;
      int v = t.minV() + r.nextInt(rows) * size;
      changed +=
          put(
                  pieces,
                  t,
                  point(t, u, v, size),
                  paletteMaterial(t, Material.COBBLED_DEEPSLATE, r),
                  size)
              ? 1
              : 0;
    }
    return changed;
  }

  private int paintPreset(List<VoxelPiece> pieces, SurfaceTarget t, DetailPreset preset) {
    if (preset == DetailPreset.MOSSY_CORNER) return paintBrush(pieces, t, BrushType.MOSS);
    if (preset == DetailPreset.CRACKED_STONE_FACE) return paintDamage(pieces, t);
    Material m =
        preset == DetailPreset.WOODEN_BEAM_JOINT || preset == DetailPreset.TINY_SHELF
            ? Material.SPRUCE_PLANKS
            : t.material();
    int changed = 0;
    int size = t.cellSize(),
        columns = Math.max(1, (t.maxU() - t.minU()) / size),
        rows = Math.max(1, (t.maxV() - t.minV()) / size);
    for (int gu = 0; gu < columns; gu++)
      for (int gv = 0; gv < rows; gv++) {
        int actualU = t.minU() + gu * size, actualV = t.minV() + gv * size;
        int u = (int) Math.floor((gu + .5) * 16 / columns),
            v = (int) Math.floor((gv + .5) * 16 / rows);
        int tolerance = Math.max(1, size / 2) + t.supportExpansion() * size;
        boolean on =
            switch (preset) {
              case SMALL_GOTHIC_ARCH -> v < size || Math.abs(Math.abs(u - 8) + v - 10) <= tolerance;
              case BROKEN_WINDOW_TRIM ->
                  (u < size || u >= 16 - size || v < size || v >= 16 - size)
                      && ((u * 17 + v * 7 + t.randomSeed()) % 5 != 0);
              case WOODEN_BEAM_JOINT ->
                  Math.abs(u - 8) <= tolerance || Math.abs(v - 8) <= tolerance;
              case TINY_SHELF -> v < size && u >= size && u < 16 - size;
              case PIPE_SEGMENT -> Math.abs(v - 8) <= tolerance;
              case BOLT_CLUSTER ->
                  (Math.abs(u - 5) <= tolerance || Math.abs(u - 10) <= tolerance)
                      && (Math.abs(v - 5) <= tolerance || Math.abs(v - 10) <= tolerance);
              default -> false;
            };
        Random random = new Random(cellSeed(t, BrushType.STONE_DETAIL, u, v));
        if (on)
          changed +=
              put(pieces, t, point(t, actualU, actualV, size), paletteMaterial(t, m, random), size)
                  ? 1
                  : 0;
      }
    return changed;
  }

  // Face-local coordinates map directly onto the outer layer of the edited block.
  private int[] point(SurfaceTarget target, int u, int v, int size) {
    int depth = target.normalCoordinate();
    BlockFace face = target.face();
    return switch (face) {
      case EAST, WEST -> new int[] {depth, v, u};
      case UP, DOWN -> new int[] {u, depth, v};
      case SOUTH, NORTH -> new int[] {u, v, depth};
      default -> new int[] {u, v, 0};
    };
  }

  private int paintCurvedTrim(
      List<VoxelPiece> pieces, SurfaceTarget target, Material fallback, TrimShape shape) {
    int size = target.cellSize(),
        columns = Math.max(1, (target.maxU() - target.minU()) / size),
        rows = Math.max(1, (target.maxV() - target.minV()) / size),
        changed = 0;
    double centerU = (columns - 1) / 2.0,
        centerV = (rows - 1) / 2.0,
        radius = Math.max(.5, Math.min(columns, rows) / 2.0 - 1.0),
        thickness = (columns >= 8 ? 1.25 : 0.8) + target.supportExpansion();
    for (int gu = 0; gu < columns; gu++)
      for (int gv = 0; gv < rows; gv++) {
        if (shape == TrimShape.HALF_CIRCLE && gv < centerV) continue;
        double distance = Math.hypot(gu - centerU, gv - centerV);
        if (Math.abs(distance - radius) > thickness) continue;
        int u = target.minU() + gu * size, v = target.minV() + gv * size;
        Random random = new Random(cellSeed(target, BrushType.STONE_DETAIL, u, v));
        if (put(
            pieces,
            target,
            point(target, u, v, size),
            paletteMaterial(target, fallback, random),
            size)) changed++;
      }
    return changed;
  }

  private boolean put(
      List<VoxelPiece> pieces, SurfaceTarget target, int[] p, Material material, int size) {
    String materialName = target.carveLayer() ? Material.AIR.name() : material.name();
    for (VoxelPiece existing : pieces)
      if (existing.size == size
          && existing.x == p[0]
          && existing.y == p[1]
          && existing.z == p[2]
          && existing.material.equals(materialName)) return false;
    pieces.add(new VoxelPiece(p[0], p[1], p[2], size, materialName));
    return true;
  }

  private Material paletteMaterial(SurfaceTarget target, Material fallback, Random random) {
    return target.palette().isEmpty()
        ? fallback
        : target.palette().get(random.nextInt(target.palette().size()));
  }

  private int[] globalSurfaceCoordinates(SurfaceTarget target, int u, int v) {
    Block block = target.sourceBlock();
    return switch (target.face()) {
      case EAST, WEST -> new int[] {block.getZ() * 16 + u, block.getY() * 16 + v};
      case UP, DOWN -> new int[] {block.getX() * 16 + u, block.getZ() * 16 + v};
      default -> new int[] {block.getX() * 16 + u, block.getY() * 16 + v};
    };
  }

  private long cellSeed(SurfaceTarget target, BrushType type, int u, int v) {
    long seed =
        target.world().getUID().getMostSignificantBits() ^ type.ordinal() * 0x9E3779B97F4A7C15L;
    seed = (seed ^ u) * 1099511628211L;
    return (seed ^ v) * 1099511628211L;
  }

  private long surfaceSeed(SurfaceTarget target) {
    Block block = target.sourceBlock();
    long plane =
        switch (target.face()) {
          case EAST -> block.getX() + 1L;
          case WEST -> block.getX();
          case UP -> block.getY() + 1L;
          case DOWN -> block.getY();
          case SOUTH -> block.getZ() + 1L;
          case NORTH -> block.getZ();
          default -> 0L;
        };
    int axis =
        switch (target.face()) {
          case EAST, WEST -> 1;
          case UP, DOWN -> 2;
          default -> 3;
        };
    return target.world().getUID().getLeastSignificantBits() ^ plane * 73428767L ^ axis * 912931L;
  }

  private double coherentNoise(int u, int v, long seed) {
    long value = (u >> 2) * 73428767L ^ (v >> 2) * 912931L ^ seed;
    value ^= value >>> 33;
    value *= 0xff51afd7ed558ccdL;
    value ^= value >>> 33;
    return (value & 0xffff) / (double) 0xffff;
  }

  private double crackDistance(int u, int v, long seed) {
    double phase = (seed & 0xffff) / 65535.0 * Math.PI * 2.0;
    double path = 7.0 * Math.sin(v * .115 + phase) + 3.0 * Math.sin(v * .037 + phase * .4);
    int offset = (int) Math.round(path) + (int) Math.floorMod(seed >>> 16, 32);
    int distance = Math.floorMod(u - offset, 32);
    return Math.min(distance, 32 - distance);
  }

  public int detailRegion(Player player, String style) {
    var selection = plugin.getVoxelSelectionManager().lastCopySelection(player);
    if (selection == null) return -1;
    var world = org.bukkit.Bukkit.getWorld(selection.worldName());
    if (world == null) return -1;
    int minX = Math.floorDiv(selection.minX(), 16),
        minY = Math.floorDiv(selection.minY(), 16),
        minZ = Math.floorDiv(selection.minZ(), 16);
    int maxX = Math.floorDiv(selection.maxX() - 1, 16),
        maxY = Math.floorDiv(selection.maxY() - 1, 16),
        maxZ = Math.floorDiv(selection.maxZ() - 1, 16);
    long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    if (volume > REGION_LIMIT) return -2;
    Settings s = settings(player);
    DetailToolMode old = s.mode;
    BrushType oldBrush = s.brush;
    s.mode = "overgrown".equals(style) ? DetailToolMode.OVERGROW : DetailToolMode.DAMAGE;
    int changed = 0;
    for (int x = minX; x <= maxX; x++)
      for (int y = minY; y <= maxY; y++)
        for (int z = minZ; z <= maxZ; z++) {
          Block b = world.getBlockAt(x, y, z);
          if (b.getType().isAir() || b.getType() == Material.BARRIER) continue;
          for (BlockFace face :
              List.of(
                  BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST))
            if (b.getRelative(face).getType().isAir()
                && apply(player, b, face, new Vector(.5, .5, .5))) changed++;
        }
    s.mode = old;
    s.brush = oldBrush;
    return changed;
  }

  private List<VoxelPiece> copy(List<VoxelPiece> source) {
    List<VoxelPiece> out = new ArrayList<>();
    for (VoxelPiece p : source) out.add(p.copy());
    return out;
  }

  public static final class Settings {
    public DetailToolMode mode = DetailToolMode.OFF;
    public BrushType brush = BrushType.MOSS;
    public Material material = Material.OAK_PLANKS;
    public DetailPreset preset = DetailPreset.MOSSY_CORNER;
    public double intensity = .45;
    public int resolution = 16;
    public final List<Material> palette = new ArrayList<>();
    public int layer = 0;
    public TrimShape trimShape = TrimShape.EDGE;
    public boolean naturalize = true;
    public boolean connectedLayers = true;
    private boolean carveLayer = false;
    private int supportExpansion = 0;
  }

  private record Support(int x, int y, int z, int size) {}
}
