package me.reube.SmallVoxels.managers;

import com.google.gson.JsonArray;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class VoxelManager {

  public static int getVoxelIndex(int x, int y, int z) {
    return y * 16 + z * 4 + x;
  }

  public static int[] getVoxelCoords(int index) {
    int x = index % 4;
    int z = (index / 4) % 4;
    int y = index / 16;
    return new int[] {x, y, z};
  }

  public static JsonArray removeVoxel(JsonArray data, int index) {
    data.set(index, com.google.gson.JsonNull.INSTANCE);
    return data;
  }

  public static JsonArray setVoxel(JsonArray data, int index, String materialType) {
    data.set(index, new com.google.gson.JsonPrimitive(materialType));
    return data;
  }

  public static Material getMaterialFromString(String materialStr) {
    try {
      return Material.valueOf(materialStr);
    } catch (IllegalArgumentException e) {
      return Material.AIR;
    }
  }

  public static String getMaterialString(Material material) {
    return material.name();
  }

  public static boolean isTopLayerEmpty(JsonArray data) {
    for (int z = 0; z < 4; z++) {
      for (int x = 0; x < 4; x++) {
        int index = getVoxelIndex(x, 3, z);
        String block = data.get(index).getAsString();
        if (!block.equals("air")) return false;
      }
    }
    return true;
  }

  public static boolean areSidesEmpty(JsonArray data) {
    for (int y = 0; y < 4; y++) {
      for (int x = 0; x < 4; x++) {
        if (!data.get(getVoxelIndex(x, y, 0)).getAsString().equals("air")) return false;
        if (!data.get(getVoxelIndex(x, y, 3)).getAsString().equals("air")) return false;
      }
      for (int z = 0; z < 4; z++) {
        if (!data.get(getVoxelIndex(0, y, z)).getAsString().equals("air")) return false;
        if (!data.get(getVoxelIndex(3, y, z)).getAsString().equals("air")) return false;
      }
    }
    return true;
  }

  public static String getPatternType(JsonArray data) {
    boolean topEmpty = isTopLayerEmpty(data);
    boolean sidesEmpty = areSidesEmpty(data);

    if (topEmpty && sidesEmpty) {
      return "invisible_stairs"; // Only diagonal remains
    } else if (topEmpty) {
      return "invisible_slab_top"; // Top removed, bottom remains
    } else if (sidesEmpty) {
      return "shelf"; // Sides removed
    }

    return "carved_block"; // General carved state
  }

  public static int countFilledVoxels(JsonArray data) {
    int count = 0;
    for (int i = 0; i < 64; i++) {
      if (!data.get(i).isJsonNull() && !data.get(i).getAsString().equals("air")) {
        count++;
      }
    }
    return count;
  }

  public static int getClickedVoxelIndexOnFace(Block block, Player player, BlockFace face) {
    org.bukkit.Location eye = player.getEyeLocation();
    org.bukkit.util.Vector direction = eye.getDirection().normalize();

    double step = 0.01;
    double maxDistance = 6;

    for (double dist = 0; dist < maxDistance; dist += step) {
      org.bukkit.Location checkLoc = eye.clone().add(direction.clone().multiply(dist));

      if (checkLoc.getBlockX() == block.getX()
          && checkLoc.getBlockY() == block.getY()
          && checkLoc.getBlockZ() == block.getZ()) {

        double relX = checkLoc.getX() - block.getX();
        double relY = checkLoc.getY() - block.getY();
        double relZ = checkLoc.getZ() - block.getZ();

        relX = Math.max(0.0001, Math.min(0.9999, relX));
        relY = Math.max(0.0001, Math.min(0.9999, relY));
        relZ = Math.max(0.0001, Math.min(0.9999, relZ));

        int voxelX = Math.min(3, Math.max(0, (int) Math.floor(relX * 4)));
        int voxelY = Math.min(3, Math.max(0, (int) Math.floor(relY * 4)));
        int voxelZ = Math.min(3, Math.max(0, (int) Math.floor(relZ * 4)));

        switch (face) {
          case UP -> voxelY = 3;
          case DOWN -> voxelY = 0;
          case EAST -> voxelX = 3;
          case WEST -> voxelX = 0;
          case SOUTH -> voxelZ = 3;
          case NORTH -> voxelZ = 0;
          default -> {}
        }

        return getVoxelIndex(voxelX, voxelY, voxelZ);
      }
    }

    return getClickedVoxelIndex(block, player);
  }

  public static int getClickedVoxelIndex(Block block, Player player) {
    org.bukkit.Location eye = player.getEyeLocation();
    org.bukkit.util.Vector direction = eye.getDirection().normalize();

    double step = 0.0005;
    double maxDistance = 100;

    int lastVoxelIndex = -1;
    double lastRelX = -1, lastRelY = -1, lastRelZ = -1;

    for (double dist = 0; dist < maxDistance; dist += step) {
      org.bukkit.Location checkLoc = eye.clone().add(direction.clone().multiply(dist));

      if (checkLoc.getBlockX() == block.getX()
          && checkLoc.getBlockY() == block.getY()
          && checkLoc.getBlockZ() == block.getZ()) {

        double relX = checkLoc.getX() - block.getX();
        double relY = checkLoc.getY() - block.getY();
        double relZ = checkLoc.getZ() - block.getZ();

        relX = Math.max(0.0001, Math.min(0.9999, relX));
        relY = Math.max(0.0001, Math.min(0.9999, relY));
        relZ = Math.max(0.0001, Math.min(0.9999, relZ));

        int voxelX = Math.min(3, Math.max(0, (int) Math.floor(relX * 4)));
        int voxelY = Math.min(3, Math.max(0, (int) Math.floor(relY * 4)));
        int voxelZ = Math.min(3, Math.max(0, (int) Math.floor(relZ * 4)));

        int currentIndex = getVoxelIndex(voxelX, voxelY, voxelZ);

        if (lastVoxelIndex != -1 && currentIndex != lastVoxelIndex) {
          return lastVoxelIndex;
        }

        if (lastRelX != -1) {
          boolean xBoundary = Math.floor(lastRelX * 4) != Math.floor(relX * 4);
          boolean yBoundary = Math.floor(lastRelY * 4) != Math.floor(relY * 4);
          boolean zBoundary = Math.floor(lastRelZ * 4) != Math.floor(relZ * 4);

          if ((xBoundary || yBoundary || zBoundary) && lastVoxelIndex != -1) {
            return lastVoxelIndex;
          }
        }

        lastVoxelIndex = currentIndex;
        lastRelX = relX;
        lastRelY = relY;
        lastRelZ = relZ;
      } else if (lastVoxelIndex != -1) {
        return lastVoxelIndex;
      }
    }

    if (lastVoxelIndex != -1) {
      return lastVoxelIndex;
    }

    return getClickedVoxelIndexFallback(block, player);
  }

  private static int getClickedVoxelIndexFallback(Block block, Player player) {
    double relX = player.getEyeLocation().getX() - block.getX();
    double relY = player.getEyeLocation().getY() - block.getY();
    double relZ = player.getEyeLocation().getZ() - block.getZ();

    relX = Math.max(0, Math.min(0.999, relX));
    relY = Math.max(0, Math.min(0.999, relY));
    relZ = Math.max(0, Math.min(0.999, relZ));

    int voxelX = (int) (relX * 4);
    int voxelY = (int) (relY * 4);
    int voxelZ = (int) (relZ * 4);

    return getVoxelIndex(voxelX, voxelY, voxelZ);
  }

  public static boolean canPlaceVoxel(JsonArray data, int voxelIndex) {
    String target = data.get(voxelIndex).getAsString();
    if (!target.equals("air")) {
      return false;
    }

    int[] coords = getVoxelCoords(voxelIndex);
    int x = coords[0], y = coords[1], z = coords[2];

    int filledCount = countFilledVoxels(data);
    if (filledCount == 0) {
      return true;
    }

    int[][] adjacent = {
      {x + 1, y, z}, {x - 1, y, z},
      {x, y + 1, z}, {x, y - 1, z},
      {x, y, z + 1}, {x, y, z - 1}
    };

    for (int[] adj : adjacent) {
      if (adj[0] >= 0 && adj[0] < 4 && adj[1] >= 0 && adj[1] < 4 && adj[2] >= 0 && adj[2] < 4) {
        int adjIndex = getVoxelIndex(adj[0], adj[1], adj[2]);
        String adjVoxel = data.get(adjIndex).getAsString();
        if (!adjVoxel.equals("air")) {
          return true;
        }
      }
    }

    return false;
  }

  public static class RaycastResult {
    public Block block;
    public int voxelIndex;
    public Vector entryDirection;
    public BlockFace hitFace;
    public VoxelPiece piece;
    public int hitX;
    public int hitY;
    public int hitZ;
    public boolean hitPiece;
    public double hitDistance = Double.POSITIVE_INFINITY;

    public RaycastResult(Block block, int voxelIndex, Vector entryDirection, BlockFace hitFace) {
      this.block = block;
      this.voxelIndex = voxelIndex;
      this.entryDirection = entryDirection;
      this.hitFace = hitFace;
      this.piece = null;
      this.hitPiece = false;
      int[] coords = getVoxelCoords(voxelIndex);
      this.hitX = coords[0] * 4 + 2;
      this.hitY = coords[1] * 4 + 2;
      this.hitZ = coords[2] * 4 + 2;
    }

    public RaycastResult(
        Block block,
        VoxelPiece piece,
        int hitX,
        int hitY,
        int hitZ,
        Vector entryDirection,
        BlockFace hitFace) {
      this.block = block;
      this.piece = piece;
      this.hitX = hitX;
      this.hitY = hitY;
      this.hitZ = hitZ;
      this.entryDirection = entryDirection;
      this.hitFace = hitFace;
      this.hitPiece = true;
      int slotX = Math.min(3, Math.max(0, hitX / 4));
      int slotY = Math.min(3, Math.max(0, hitY / 4));
      int slotZ = Math.min(3, Math.max(0, hitZ / 4));
      this.voxelIndex = getVoxelIndex(slotX, slotY, slotZ);
    }
  }

  public static boolean isMultiPartBlock(Material material) {
    return material == Material.TORCH
        || material == Material.WALL_TORCH
        || material == Material.REDSTONE_TORCH
        || material == Material.OAK_LEAVES
        || material == Material.BIRCH_LEAVES
        || material == Material.SPRUCE_LEAVES
        || material == Material.JUNGLE_LEAVES
        || material == Material.ACACIA_LEAVES
        || material == Material.DARK_OAK_LEAVES
        || material == Material.MANGROVE_LEAVES
        || material == Material.CHERRY_LEAVES
        || material == Material.LANTERN
        || material == Material.SOUL_LANTERN
        || material == Material.REPEATER
        || material == Material.COMPARATOR
        || material == Material.FLOWER_POT
        || material == Material.SEAGRASS
        || material == Material.TALL_SEAGRASS;
  }

  public static org.bukkit.block.data.BlockData getDefaultBlockData(Material material) {
    return material.createBlockData();
  }

  public static class PlacementTarget {
    public Block block;
    public int voxelIndex;
    public boolean isCrossBlock;

    public PlacementTarget(Block block, int voxelIndex, boolean isCrossBlock) {
      this.block = block;
      this.voxelIndex = voxelIndex;
      this.isCrossBlock = isCrossBlock;
    }
  }

  public static PlacementTarget calculatePlacementTarget(
      BlockFace hitFace, int[] clickedCoords, Block clickedBlock) {
    int[] targetCoords = clickedCoords.clone();
    Block targetBlock = clickedBlock;
    boolean isCrossBlock = false;

    switch (hitFace) {
      case WEST -> targetCoords[0] -= 1;
      case EAST -> targetCoords[0] += 1;
      case DOWN -> targetCoords[1] -= 1;
      case UP -> targetCoords[1] += 1;
      case NORTH -> targetCoords[2] -= 1;
      case SOUTH -> targetCoords[2] += 1;
      default -> {
        return null;
      }
    }

    if (targetCoords[0] < 0) {
      targetBlock = targetBlock.getRelative(BlockFace.WEST);
      targetCoords[0] = 3;
      isCrossBlock = true;
    } else if (targetCoords[0] > 3) {
      targetBlock = targetBlock.getRelative(BlockFace.EAST);
      targetCoords[0] = 0;
      isCrossBlock = true;
    }

    if (targetCoords[1] < 0) {
      targetBlock = targetBlock.getRelative(BlockFace.DOWN);
      targetCoords[1] = 3;
      isCrossBlock = true;
    } else if (targetCoords[1] > 3) {
      targetBlock = targetBlock.getRelative(BlockFace.UP);
      targetCoords[1] = 0;
      isCrossBlock = true;
    }

    if (targetCoords[2] < 0) {
      targetBlock = targetBlock.getRelative(BlockFace.NORTH);
      targetCoords[2] = 3;
      isCrossBlock = true;
    } else if (targetCoords[2] > 3) {
      targetBlock = targetBlock.getRelative(BlockFace.SOUTH);
      targetCoords[2] = 0;
      isCrossBlock = true;
    }

    int targetIndex = getVoxelIndex(targetCoords[0], targetCoords[1], targetCoords[2]);
    return new PlacementTarget(targetBlock, targetIndex, isCrossBlock);
  }

  private static double firstBoundaryT(double s, double ds) {
    if (ds == 0) return Double.POSITIVE_INFINITY;

    double nextBoundary = ds > 0 ? Math.floor(s) + 1.0 : Math.ceil(s) - 1.0;
    return (nextBoundary - s) / ds;
  }

  private static double deltaT(double ds) {
    if (ds == 0) return Double.POSITIVE_INFINITY;
    return Math.abs(1.0 / ds);
  }

  private static BlockFace faceFromStep(int stepX, int stepY, int stepZ, int axis) {
    return switch (axis) {
      case 0 -> stepX > 0 ? BlockFace.WEST : BlockFace.EAST;
      case 1 -> stepY > 0 ? BlockFace.DOWN : BlockFace.UP;
      case 2 -> stepZ > 0 ? BlockFace.NORTH : BlockFace.SOUTH;
      default -> null;
    };
  }

  private static boolean isInsideVoxelBounds(double x, double y, double z) {
    return x >= 0.0 && x < 1.0 && y >= 0.0 && y < 1.0 && z >= 0.0 && z < 1.0;
  }

  public static RaycastResult raycastThroughBarriers(
      org.bukkit.Location eye, Vector direction, DataManager dataManager) {
    return raycastCarvedData(eye, direction, dataManager, 100.0, true, true);
  }

  public static RaycastResult raycastCarvedData(
      org.bukkit.Location eye, Vector direction, DataManager dataManager, double maxDistance) {
    return raycastCarvedData(eye, direction, dataManager, maxDistance, true, false);
  }

  private static RaycastResult raycastCarvedData(
      org.bukkit.Location eye,
      Vector direction,
      DataManager dataManager,
      double maxDistance,
      boolean requireBarrier,
      boolean includeShell) {
    Vector dir = direction.clone().normalize();
    RaycastResult transformedCandidate =
        raycastTransformedHosts(eye, dir, dataManager, maxDistance);

    double x = eye.getX();
    double y = eye.getY();
    double z = eye.getZ();

    int blockX = (int) Math.floor(x);
    int blockY = (int) Math.floor(y);
    int blockZ = (int) Math.floor(z);

    int stepX = dir.getX() > 0 ? 1 : dir.getX() < 0 ? -1 : 0;
    int stepY = dir.getY() > 0 ? 1 : dir.getY() < 0 ? -1 : 0;
    int stepZ = dir.getZ() > 0 ? 1 : dir.getZ() < 0 ? -1 : 0;

    double tMaxX = firstBoundaryT(x, dir.getX());
    double tMaxY = firstBoundaryT(y, dir.getY());
    double tMaxZ = firstBoundaryT(z, dir.getZ());

    double tDeltaX = deltaT(dir.getX());
    double tDeltaY = deltaT(dir.getY());
    double tDeltaZ = deltaT(dir.getZ());

    double traveled = 0.0;

    while (traveled <= maxDistance) {
      Block currentBlock = eye.getWorld().getBlockAt(blockX, blockY, blockZ);

      if ((!requireBarrier || currentBlock.getType() == Material.BARRIER)
          && dataManager.hasCarvedData(currentBlock)) {
        List<VoxelPiece> pieces = dataManager.getVoxelPieces(currentBlock);
        RaycastResult result;
        if (!pieces.isEmpty()) {
          result = raycastPiecesInBlock(eye, dir, currentBlock, pieces);
        } else {
          result =
              raycastVoxelsInBlock(
                  eye, dir, currentBlock, dataManager.getCarvedBlockData(currentBlock));
          if (result == null && includeShell) {
            result = raycastCarvedBlockShell(eye, dir, currentBlock);
          }
        }
        if (result != null) {
          return transformedCandidate != null
                  && transformedCandidate.hitDistance < result.hitDistance
              ? transformedCandidate
              : result;
        }
      }

      if (tMaxX < tMaxY && tMaxX < tMaxZ) {
        blockX += stepX;
        traveled = tMaxX;
        tMaxX += tDeltaX;
      } else if (tMaxY < tMaxZ) {
        blockY += stepY;
        traveled = tMaxY;
        tMaxY += tDeltaY;
      } else {
        blockZ += stepZ;
        traveled = tMaxZ;
        tMaxZ += tDeltaZ;
      }
    }

    return transformedCandidate;
  }

  private static RaycastResult raycastTransformedHosts(
      org.bukkit.Location eye, Vector direction, DataManager dataManager, double maxDistance) {
    Set<String> visitedChunks = new HashSet<>();
    RaycastResult best = null;
    for (double distance = 0; distance <= maxDistance; distance += 8.0) {
      double x = eye.getX() + direction.getX() * distance;
      double z = eye.getZ() + direction.getZ() * distance;
      int chunkX = Math.floorDiv((int) Math.floor(x), 16);
      int chunkZ = Math.floorDiv((int) Math.floor(z), 16);
      for (int ox = -1; ox <= 1; ox++)
        for (int oz = -1; oz <= 1; oz++) {
          int cx = chunkX + ox, cz = chunkZ + oz;
          if (!visitedChunks.add(cx + ":" + cz)) continue;
          for (Block block : dataManager.getSavedVoxelBlocks(eye.getWorld(), cx, cz)) {
            List<VoxelPiece> transformed =
                dataManager.getVoxelPieces(block).stream()
                    .filter(VoxelPiece::hasVisualTransform)
                    .toList();
            if (transformed.isEmpty()) continue;
            RaycastResult hit = raycastPiecesInBlock(eye, direction, block, transformed);
            if (hit != null
                && hit.hitDistance <= maxDistance
                && (best == null || hit.hitDistance < best.hitDistance)) best = hit;
          }
        }
    }
    return best;
  }

  private static RaycastResult raycastPiecesInBlock(
      org.bukkit.Location eye, Vector direction, Block block, List<VoxelPiece> pieces) {
    double originX = eye.getX() - block.getX();
    double originY = eye.getY() - block.getY();
    double originZ = eye.getZ() - block.getZ();

    RaycastResult bestResult = null;
    double bestT = Double.POSITIVE_INFINITY;

    for (VoxelPiece piece : pieces) {
      if (!piece.isValid()) {
        continue;
      }

      PieceHit hit = intersectPiece(originX, originY, originZ, direction, piece);
      if (hit == null || hit.t < 0.0 || hit.t >= bestT) {
        continue;
      }

      double localX = originX + direction.getX() * (hit.t + 1e-7);
      double localY = originY + direction.getY() * (hit.t + 1e-7);
      double localZ = originZ + direction.getZ() * (hit.t + 1e-7);
      if (piece.hasVisualTransform()) {
        double cx = (piece.x + piece.size / 2.0) / 16.0 + piece.offsetX;
        double cy = (piece.y + piece.size / 2.0) / 16.0 + piece.offsetY;
        double cz = (piece.z + piece.size / 2.0) / 16.0 + piece.offsetZ;
        Vector3f point =
            inverseRotation(piece)
                .transform(
                    new Vector3f(
                        (float) (localX - cx), (float) (localY - cy), (float) (localZ - cz)));
        localX =
            (piece.x + piece.size / 2.0) / 16.0
                + point.x / Math.max(1.0e-6, piece.visualScale * piece.scaleX);
        localY =
            (piece.y + piece.size / 2.0) / 16.0
                + point.y / Math.max(1.0e-6, piece.visualScale * piece.scaleY);
        localZ =
            (piece.z + piece.size / 2.0) / 16.0
                + point.z / Math.max(1.0e-6, piece.visualScale * piece.scaleZ);
      }

      int hitX = Math.min(15, Math.max(0, (int) Math.floor(localX * 16.0)));
      int hitY = Math.min(15, Math.max(0, (int) Math.floor(localY * 16.0)));
      int hitZ = Math.min(15, Math.max(0, (int) Math.floor(localZ * 16.0)));

      bestT = hit.t;
      bestResult = new RaycastResult(block, piece, hitX, hitY, hitZ, direction.clone(), hit.face);
      bestResult.hitDistance = hit.t;
    }

    return bestResult;
  }

  private static class PieceHit {
    double t;
    BlockFace face;

    PieceHit(double t, BlockFace face) {
      this.t = t;
      this.face = face;
    }
  }

  private static PieceHit intersectPiece(
      double originX, double originY, double originZ, Vector direction, VoxelPiece piece) {
    double minX = piece.x / 16.0, minY = piece.y / 16.0, minZ = piece.z / 16.0;
    double maxX = (piece.x + piece.size) / 16.0,
        maxY = (piece.y + piece.size) / 16.0,
        maxZ = (piece.z + piece.size) / 16.0;
    if (piece.hasVisualTransform()) {
      double cx = (piece.x + piece.size / 2.0) / 16.0 + piece.offsetX;
      double cy = (piece.y + piece.size / 2.0) / 16.0 + piece.offsetY;
      double cz = (piece.z + piece.size / 2.0) / 16.0 + piece.offsetZ;
      Quaternionf inverse = inverseRotation(piece);
      Vector3f transformedOrigin =
          inverse.transform(
              new Vector3f((float) (originX - cx), (float) (originY - cy), (float) (originZ - cz)));
      Vector3f transformedDirection =
          inverse.transform(
              new Vector3f(
                  (float) direction.getX(), (float) direction.getY(), (float) direction.getZ()));
      originX = transformedOrigin.x;
      originY = transformedOrigin.y;
      originZ = transformedOrigin.z;
      direction =
          new Vector(transformedDirection.x, transformedDirection.y, transformedDirection.z);
      double half = piece.size / 32.0 * piece.visualScale;
      minX = -half * piece.scaleX;
      maxX = half * piece.scaleX;
      minY = -half * piece.scaleY;
      maxY = half * piece.scaleY;
      minZ = -half * piece.scaleZ;
      maxZ = half * piece.scaleZ;
    }

    double[] origin = {originX, originY, originZ};
    double[] dir = {direction.getX(), direction.getY(), direction.getZ()};
    double[] min = {minX, minY, minZ};
    double[] max = {maxX, maxY, maxZ};

    double tMin = 0.0;
    double tMax = 100.0;
    BlockFace entryFace = null;
    BlockFace exitFace = null;

    for (int axis = 0; axis < 3; axis++) {
      if (Math.abs(dir[axis]) < 1e-12) {
        if (origin[axis] < min[axis] || origin[axis] > max[axis]) {
          return null;
        }
        continue;
      }

      double t1 = (min[axis] - origin[axis]) / dir[axis];
      double t2 = (max[axis] - origin[axis]) / dir[axis];
      BlockFace face1 = minFace(axis);
      BlockFace face2 = maxFace(axis);

      if (t1 > t2) {
        double tmp = t1;
        t1 = t2;
        t2 = tmp;
        BlockFace tmpFace = face1;
        face1 = face2;
        face2 = tmpFace;
      }

      if (t1 > tMin) {
        tMin = t1;
        entryFace = face1;
      }
      if (t2 < tMax) {
        tMax = t2;
        exitFace = face2;
      }
      if (tMax < tMin) {
        return null;
      }
    }

    if (tMax < 0.0) {
      return null;
    }

    return new PieceHit(Math.max(tMin, 0.0), entryFace != null ? entryFace : exitFace);
  }

  private static Quaternionf inverseRotation(VoxelPiece piece) {
    return piece.rotationQuaternion().invert();
  }

  private static RaycastResult raycastCarvedBlockShell(
      org.bukkit.Location eye, Vector direction, Block block) {
    double startX = eye.getX() - block.getX();
    double startY = eye.getY() - block.getY();
    double startZ = eye.getZ() - block.getZ();

    double[] origin = {startX, startY, startZ};
    double[] dir = {direction.getX(), direction.getY(), direction.getZ()};

    double tMin = 0.0;
    double tMax = 100.0;
    BlockFace face = null;

    for (int axis = 0; axis < 3; axis++) {
      if (Math.abs(dir[axis]) < 1e-12) {
        if (origin[axis] < 0.0 || origin[axis] > 1.0) {
          return null;
        }
        continue;
      }

      double t1 = (0.0 - origin[axis]) / dir[axis];
      double t2 = (1.0 - origin[axis]) / dir[axis];
      BlockFace face1 = minFace(axis);
      BlockFace face2 = maxFace(axis);

      if (t1 > t2) {
        double tmp = t1;
        t1 = t2;
        t2 = tmp;
        BlockFace tmpFace = face1;
        face1 = face2;
        face2 = tmpFace;
      }

      if (t1 > tMin) {
        tMin = t1;
        face = face1;
      }
      tMax = Math.min(tMax, t2);
      if (tMax < tMin) {
        return null;
      }
    }

    if (tMax < 0.0) {
      return null;
    }

    double t = Math.max(tMin, 0.0);
    double x = startX + direction.getX() * (t + 1e-7);
    double y = startY + direction.getY() * (t + 1e-7);
    double z = startZ + direction.getZ() * (t + 1e-7);

    x = Math.max(0.0, Math.min(0.999999, x));
    y = Math.max(0.0, Math.min(0.999999, y));
    z = Math.max(0.0, Math.min(0.999999, z));

    int hitX = Math.min(15, Math.max(0, (int) Math.floor(x * 16.0)));
    int hitY = Math.min(15, Math.max(0, (int) Math.floor(y * 16.0)));
    int hitZ = Math.min(15, Math.max(0, (int) Math.floor(z * 16.0)));

    int slotX = Math.min(3, Math.max(0, hitX / 4));
    int slotY = Math.min(3, Math.max(0, hitY / 4));
    int slotZ = Math.min(3, Math.max(0, hitZ / 4));
    RaycastResult result =
        new RaycastResult(block, getVoxelIndex(slotX, slotY, slotZ), direction.clone(), face);
    result.hitX = hitX;
    result.hitY = hitY;
    result.hitZ = hitZ;
    return result;
  }

  private static BlockFace minFace(int axis) {
    return switch (axis) {
      case 0 -> BlockFace.WEST;
      case 1 -> BlockFace.DOWN;
      case 2 -> BlockFace.NORTH;
      default -> null;
    };
  }

  private static BlockFace maxFace(int axis) {
    return switch (axis) {
      case 0 -> BlockFace.EAST;
      case 1 -> BlockFace.UP;
      case 2 -> BlockFace.SOUTH;
      default -> null;
    };
  }

  private static RaycastResult raycastVoxelsInBlock(
      org.bukkit.Location eye, Vector direction, Block block, JsonArray carvedData) {
    double startX = eye.getX() - block.getX();
    double startY = eye.getY() - block.getY();
    double startZ = eye.getZ() - block.getZ();

    double tMin = 0.0;
    double tMax = 100.0;

    double[] origin = {startX, startY, startZ};
    double[] dir = {direction.getX(), direction.getY(), direction.getZ()};

    for (int axis = 0; axis < 3; axis++) {
      if (Math.abs(dir[axis]) < 1e-12) {
        if (origin[axis] < 0.0 || origin[axis] > 1.0) {
          return null;
        }
      } else {
        double t1 = (0.0 - origin[axis]) / dir[axis];
        double t2 = (1.0 - origin[axis]) / dir[axis];

        double enter = Math.min(t1, t2);
        double exit = Math.max(t1, t2);

        tMin = Math.max(tMin, enter);
        tMax = Math.min(tMax, exit);

        if (tMax < tMin) {
          return null;
        }
      }
    }

    double epsilon = 1e-7;
    double entryT = Math.max(tMin, 0.0);

    double x = startX + direction.getX() * (entryT + epsilon);
    double y = startY + direction.getY() * (entryT + epsilon);
    double z = startZ + direction.getZ() * (entryT + epsilon);

    if (!isInsideVoxelBounds(x, y, z)) {
      return null;
    }

    double voxelScale = 4.0;
    double vx = x * voxelScale;
    double vy = y * voxelScale;
    double vz = z * voxelScale;

    int ix = Math.min(3, Math.max(0, (int) Math.floor(vx)));
    int iy = Math.min(3, Math.max(0, (int) Math.floor(vy)));
    int iz = Math.min(3, Math.max(0, (int) Math.floor(vz)));

    int stepX = direction.getX() > 0 ? 1 : direction.getX() < 0 ? -1 : 0;
    int stepY = direction.getY() > 0 ? 1 : direction.getY() < 0 ? -1 : 0;
    int stepZ = direction.getZ() > 0 ? 1 : direction.getZ() < 0 ? -1 : 0;

    double dirX = direction.getX() * voxelScale;
    double dirY = direction.getY() * voxelScale;
    double dirZ = direction.getZ() * voxelScale;

    double tMaxX = firstBoundaryT(vx, dirX);
    double tMaxY = firstBoundaryT(vy, dirY);
    double tMaxZ = firstBoundaryT(vz, dirZ);

    double tDeltaX = deltaT(dirX);
    double tDeltaY = deltaT(dirY);
    double tDeltaZ = deltaT(dirZ);

    BlockFace entryFace = null;

    if (entryT > epsilon) {
      double hitX = startX + direction.getX() * entryT;
      double hitY = startY + direction.getY() * entryT;
      double hitZ = startZ + direction.getZ() * entryT;

      double dWest = Math.abs(hitX - 0.0);
      double dEast = Math.abs(hitX - 1.0);
      double dDown = Math.abs(hitY - 0.0);
      double dUp = Math.abs(hitY - 1.0);
      double dNorth = Math.abs(hitZ - 0.0);
      double dSouth = Math.abs(hitZ - 1.0);

      double min = dWest;
      entryFace = BlockFace.WEST;

      if (dEast < min) {
        min = dEast;
        entryFace = BlockFace.EAST;
      }
      if (dDown < min) {
        min = dDown;
        entryFace = BlockFace.DOWN;
      }
      if (dUp < min) {
        min = dUp;
        entryFace = BlockFace.UP;
      }
      if (dNorth < min) {
        min = dNorth;
        entryFace = BlockFace.NORTH;
      }
      if (dSouth < min) {
        entryFace = BlockFace.SOUTH;
      }
    }

    while (ix >= 0 && ix < 4 && iy >= 0 && iy < 4 && iz >= 0 && iz < 4) {
      int voxelIndex = getVoxelIndex(ix, iy, iz);
      String voxelContent = carvedData.get(voxelIndex).getAsString();

      if (!voxelContent.equals("air")) {
        return new RaycastResult(block, voxelIndex, direction.clone(), entryFace);
      }

      if (tMaxX < tMaxY && tMaxX < tMaxZ) {
        ix += stepX;
        entryFace = faceFromStep(stepX, 0, 0, 0);
        tMaxX += tDeltaX;
      } else if (tMaxY < tMaxZ) {
        iy += stepY;
        entryFace = faceFromStep(0, stepY, 0, 1);
        tMaxY += tDeltaY;
      } else {
        iz += stepZ;
        entryFace = faceFromStep(0, 0, stepZ, 2);
        tMaxZ += tDeltaZ;
      }
    }

    return null;
  }

  /** Returns the grid-aligned piece containing the supplied local coordinate. */
  public static int[] getGridAlignedCoords(int x, int y, int z, int scale) {
    int pieceSize = VoxelPieceManager.getPieceSizeForScale(scale);

    return new int[] {
      (x / pieceSize) * pieceSize, (y / pieceSize) * pieceSize, (z / pieceSize) * pieceSize
    };
  }

  /** Converts block-relative coordinates to the internal 16-unit grid. */
  public static int[] getVoxelCoordsFrom16x16x16(double relX, double relY, double relZ) {
    int vx = Math.min(15, Math.max(0, (int) Math.floor(relX * 16)));
    int vy = Math.min(15, Math.max(0, (int) Math.floor(relY * 16)));
    int vz = Math.min(15, Math.max(0, (int) Math.floor(relZ * 16)));
    return new int[] {vx, vy, vz};
  }

  public static int getPieceSize(List<VoxelPiece> pieces, int x, int y, int z) {
    VoxelPiece piece = VoxelPieceManager.getPieceAt(pieces, x, y, z);
    return piece != null ? piece.size : 0;
  }

  /** Calculates grid-aligned local coordinates for the selected detail level. */
  public static int[] calculatePieceCoords(double relX, double relY, double relZ, int playerScale) {
    int vx = Math.min(15, Math.max(0, (int) Math.floor(relX * 16)));
    int vy = Math.min(15, Math.max(0, (int) Math.floor(relY * 16)));
    int vz = Math.min(15, Math.max(0, (int) Math.floor(relZ * 16)));

    return getGridAlignedCoords(vx, vy, vz, playerScale);
  }
}
