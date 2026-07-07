package me.reube.SmallVoxels.managers;

import java.util.ArrayList;
import java.util.List;

public class VoxelPieceManager {
  public static List<VoxelPiece> getPiecesAt(List<VoxelPiece> pieces, int x, int y, int z) {
    List<VoxelPiece> result = new ArrayList<>();
    for (VoxelPiece piece : pieces) {
      if (piece.contains(x, y, z)) {
        result.add(piece);
      }
    }
    return result;
  }

  public static VoxelPiece getPieceAt(List<VoxelPiece> pieces, int x, int y, int z) {
    VoxelPiece smallest = null;
    for (VoxelPiece piece : pieces) {
      if (piece.contains(x, y, z) && (smallest == null || piece.size < smallest.size)) {
        smallest = piece;
      }
    }
    return smallest;
  }

  public static boolean canPlace(List<VoxelPiece> pieces, VoxelPiece newPiece) {
    if (!newPiece.isValid()) {
      return false;
    }

    for (VoxelPiece existing : pieces) {
      if (newPiece.overlaps(existing)) {
        return false;
      }
    }

    return true;
  }

  public static boolean place(List<VoxelPiece> pieces, VoxelPiece newPiece) {
    if (canPlace(pieces, newPiece)) {
      pieces.add(newPiece);
      return true;
    }
    return false;
  }

  public static int removeAt(List<VoxelPiece> pieces, int x, int y, int z) {
    int removed = 0;
    java.util.Iterator<VoxelPiece> iterator = pieces.iterator();
    while (iterator.hasNext()) {
      if (iterator.next().contains(x, y, z)) {
        iterator.remove();
        removed++;
      }
    }
    return removed;
  }

  public static int chiselAt(List<VoxelPiece> pieces, int x, int y, int z, int cutSize) {
    VoxelPiece target = getPieceAt(pieces, x, y, z);
    if (target == null) {
      return 0;
    }

    pieces.remove(target);
    if (cutSize >= target.size) {
      return 1;
    }

    int carvedX = ((x - target.x) / cutSize) * cutSize + target.x;
    int carvedY = ((y - target.y) / cutSize) * cutSize + target.y;
    int carvedZ = ((z - target.z) / cutSize) * cutSize + target.z;

    for (int px = target.x; px < target.x + target.size; px += cutSize) {
      for (int py = target.y; py < target.y + target.size; py += cutSize) {
        for (int pz = target.z; pz < target.z + target.size; pz += cutSize) {
          if (px == carvedX && py == carvedY && pz == carvedZ) {
            continue;
          }
          pieces.add(new VoxelPiece(px, py, pz, cutSize, target.material, target.blockData));
        }
      }
    }

    compact(pieces);
    return 1;
  }

  public static boolean remove(List<VoxelPiece> pieces, VoxelPiece piece) {
    return pieces.remove(piece);
  }

  public static void clear(List<VoxelPiece> pieces) {
    pieces.clear();
  }

  public static int countPieces(List<VoxelPiece> pieces) {
    return pieces.size();
  }

  public static List<VoxelPiece> convertFromSlotArray(String[] carvedDataSlots) {
    List<VoxelPiece> pieces = new ArrayList<>();

    if (carvedDataSlots == null || carvedDataSlots.length != 64) {
      return pieces;
    }

    for (int i = 0; i < 64; i++) {
      String material = carvedDataSlots[i];
      if (material == null || material.equals("air")) {
        continue;
      }

      // Convert slot index to 4x4x4 grid coordinates
      int x = (i % 4) * 4;
      int y = ((i / 4) % 4) * 4;
      int z = (i / 16) * 4;

      VoxelPiece piece = new VoxelPiece(x, y, z, 4, material);
      pieces.add(piece);
    }

    return pieces;
  }

  public static int getTotalVolume(List<VoxelPiece> pieces) {
    int total = 0;
    for (VoxelPiece piece : pieces) {
      total += piece.size * piece.size * piece.size;
    }
    return total;
  }

  public static int getPieceSizeForScale(int scale) {
    return switch (scale) {
      case 2 -> 8;
      case 4 -> 4;
      case 8 -> 2;
      case 16 -> 1;
      default -> 4;
    };
  }

  public static void compact(List<VoxelPiece> pieces) {
    if (pieces == null || pieces.isEmpty()) {
      return;
    }

    boolean[][][] filled = new boolean[16][16][16];
    String[][][] materials = new String[16][16][16];
    String[][][] blockData = new String[16][16][16];

    for (VoxelPiece piece : pieces) {
      if (!piece.isValid()) {
        continue;
      }
      boolean clear = "AIR".equalsIgnoreCase(piece.material);
      for (int x = piece.x; x < piece.x + piece.size; x++) {
        for (int y = piece.y; y < piece.y + piece.size; y++) {
          for (int z = piece.z; z < piece.z + piece.size; z++) {
            filled[x][y][z] = !clear;
            materials[x][y][z] = clear ? null : piece.material;
            blockData[x][y][z] = clear ? null : piece.blockData;
          }
        }
      }
    }

    List<VoxelPiece> compacted = new ArrayList<>();
    int[] sizes = {16, 8, 4, 2, 1};
    for (int size : sizes) {
      for (int x = 0; x <= 16 - size; x += size) {
        for (int y = 0; y <= 16 - size; y += size) {
          for (int z = 0; z <= 16 - size; z += size) {
            if (!canTakeVoxelVolume(filled, materials, blockData, x, y, z, size)) {
              continue;
            }
            String material = materials[x][y][z];
            String data = blockData[x][y][z];
            compacted.add(new VoxelPiece(x, y, z, size, material, data));
            for (int px = x; px < x + size; px++) {
              for (int py = y; py < y + size; py++) {
                for (int pz = z; pz < z + size; pz++) {
                  filled[px][py][pz] = false;
                }
              }
            }
          }
        }
      }
    }

    pieces.clear();
    pieces.addAll(compacted);
  }

  private static boolean canTakeVoxelVolume(
      boolean[][][] filled,
      String[][][] materials,
      String[][][] blockData,
      int x,
      int y,
      int z,
      int size) {
    if (!filled[x][y][z]) {
      return false;
    }
    String material = materials[x][y][z];
    String data = blockData[x][y][z];
    for (int px = x; px < x + size; px++) {
      for (int py = y; py < y + size; py++) {
        for (int pz = z; pz < z + size; pz++) {
          if (!filled[px][py][pz]) {
            return false;
          }
          if (!java.util.Objects.equals(material, materials[px][py][pz])
              || !java.util.Objects.equals(data, blockData[px][py][pz])) {
            return false;
          }
        }
      }
    }
    return true;
  }

  public static boolean validateNoOverlaps(List<VoxelPiece> pieces) {
    for (int i = 0; i < pieces.size(); i++) {
      for (int j = i + 1; j < pieces.size(); j++) {
        if (pieces.get(i).overlaps(pieces.get(j))) {
          return false;
        }
      }
    }
    return true;
  }
}
