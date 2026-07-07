package me.reube.SmallVoxels.managers;

import org.bukkit.Material;
import org.joml.Quaternionf;

/**
 * Represents a carved voxel piece in 16x16x16 coordinate space. Each piece occupies a cubic volume
 * of size×size×size.
 *
 * <p>Scale sizes: - 8: 2×2×2 grid (8 pieces per block edge) - 4: 4×4×4 grid (4 pieces per block
 * edge, default) - 2: 8×8×8 grid (2 pieces per block edge, fine detail) - 1: 16×16×16 (single large
 * piece, ultra-fine)
 */
public class VoxelPiece {
  public int x;
  public int y;
  public int z;
  public int size;
  public String material;
  public String blockData;

  /** Visual transform around this piece's centre. Values are stored in block units/degrees. */
  public double offsetX, offsetY, offsetZ;

  public float rotationX, rotationY, rotationZ;
  public float quaternionX, quaternionY, quaternionZ, quaternionW = 1.0f;
  public double visualScale = 1.0;
  public double scaleX = 1.0, scaleY = 1.0, scaleZ = 1.0;
  public String transformGroup;

  public VoxelPiece(int x, int y, int z, int size, String material) {
    this(x, y, z, size, material, null);
  }

  public VoxelPiece(int x, int y, int z, int size, String material, String blockData) {
    this.x = x;
    this.y = y;
    this.z = z;
    this.size = size;
    this.material = material;
    this.blockData = blockData;
  }

  public boolean isValid() {
    if (size != 1 && size != 2 && size != 4 && size != 8 && size != 16) {
      return false;
    }

    if (x % size != 0 || y % size != 0 || z % size != 0) {
      return false;
    }

    int maxCoord = 16 - size;
    if (x < 0 || x > maxCoord || y < 0 || y > maxCoord || z < 0 || z > maxCoord) {
      return false;
    }

    return material != null && !material.isEmpty();
  }

  public boolean contains(int cx, int cy, int cz) {
    return cx >= x && cx < x + size && cy >= y && cy < y + size && cz >= z && cz < z + size;
  }

  public boolean overlaps(VoxelPiece other) {
    return !(x + size <= other.x
        || x >= other.x + other.size
        || y + size <= other.y
        || y >= other.y + other.size
        || z + size <= other.z
        || z >= other.z + other.size);
  }

  public Material getAsMaterial() {
    try {
      return Material.valueOf(material.toUpperCase());
    } catch (IllegalArgumentException e) {
      return Material.STONE;
    }
  }

  public VoxelPiece copy() {
    VoxelPiece copy = new VoxelPiece(x, y, z, size, material, blockData);
    copy.offsetX = offsetX;
    copy.offsetY = offsetY;
    copy.offsetZ = offsetZ;
    copy.rotationX = rotationX;
    copy.rotationY = rotationY;
    copy.rotationZ = rotationZ;
    copy.quaternionX = quaternionX;
    copy.quaternionY = quaternionY;
    copy.quaternionZ = quaternionZ;
    copy.quaternionW = quaternionW;
    copy.visualScale = visualScale;
    copy.scaleX = scaleX;
    copy.scaleY = scaleY;
    copy.scaleZ = scaleZ;
    copy.transformGroup = transformGroup;
    return copy;
  }

  public boolean hasVisualTransform() {
    return Math.abs(offsetX) > 1.0e-7
        || Math.abs(offsetY) > 1.0e-7
        || Math.abs(offsetZ) > 1.0e-7
        || Math.abs(rotationX) > 1.0e-4
        || Math.abs(rotationY) > 1.0e-4
        || Math.abs(rotationZ) > 1.0e-4
        || Math.abs(quaternionX) > 1.0e-6
        || Math.abs(quaternionY) > 1.0e-6
        || Math.abs(quaternionZ) > 1.0e-6
        || Math.abs(quaternionW - 1.0f) > 1.0e-6
        || Math.abs(visualScale - 1.0) > 1.0e-7
        || Math.abs(scaleX - 1.0) > 1.0e-7
        || Math.abs(scaleY - 1.0) > 1.0e-7
        || Math.abs(scaleZ - 1.0) > 1.0e-7;
  }

  public Quaternionf rotationQuaternion() {
    if (Math.abs(quaternionX) > 1.0e-6
        || Math.abs(quaternionY) > 1.0e-6
        || Math.abs(quaternionZ) > 1.0e-6
        || Math.abs(quaternionW - 1.0f) > 1.0e-6) {
      return new Quaternionf(quaternionX, quaternionY, quaternionZ, quaternionW).normalize();
    }
    return new Quaternionf()
        .rotationXYZ(
            (float) Math.toRadians(rotationX),
            (float) Math.toRadians(rotationY),
            (float) Math.toRadians(rotationZ));
  }

  public void setRotationQuaternion(Quaternionf rotation) {
    Quaternionf normalized = new Quaternionf(rotation).normalize();
    quaternionX = normalized.x;
    quaternionY = normalized.y;
    quaternionZ = normalized.z;
    quaternionW = normalized.w;
  }

  @Override
  public String toString() {
    return String.format("VoxelPiece[%d,%d,%d size=%d material=%s]", x, y, z, size, material);
  }
}
