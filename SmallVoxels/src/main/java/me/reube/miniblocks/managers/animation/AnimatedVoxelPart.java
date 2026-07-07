package me.reube.SmallVoxels.managers.animation;

import org.bukkit.Material;
import org.joml.Quaternionf;

public class AnimatedVoxelPart {
  public String id;
  public int localX;
  public int localY;
  public int localZ;
  public int size;
  public String material;
  public String blockData;
  public double offsetX, offsetY, offsetZ;
  public float rotationX, rotationY, rotationZ;
  public float quaternionX, quaternionY, quaternionZ, quaternionW = 1.0f;
  public double visualScale = 1.0;
  public double scaleX = 1.0, scaleY = 1.0, scaleZ = 1.0;
  public String transformGroup;

  public AnimatedVoxelPart() {}

  public AnimatedVoxelPart copy() {
    AnimatedVoxelPart copy =
        new AnimatedVoxelPart(id, localX, localY, localZ, size, material, blockData);
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

  public Quaternionf rotationQuaternion() {
    if (Math.abs(quaternionX) > 1.0e-6
        || Math.abs(quaternionY) > 1.0e-6
        || Math.abs(quaternionZ) > 1.0e-6
        || Math.abs(quaternionW - 1) > 1.0e-6)
      return new Quaternionf(quaternionX, quaternionY, quaternionZ, quaternionW).normalize();
    return new Quaternionf()
        .rotationXYZ(
            (float) Math.toRadians(rotationX),
            (float) Math.toRadians(rotationY),
            (float) Math.toRadians(rotationZ));
  }

  public AnimatedVoxelPart(
      String id, int localX, int localY, int localZ, int size, String material, String blockData) {
    this.id = id;
    this.localX = localX;
    this.localY = localY;
    this.localZ = localZ;
    this.size = size;
    this.material = material;
    this.blockData = blockData;
  }

  public Material getAsMaterial() {
    try {
      return Material.valueOf(material.toUpperCase());
    } catch (Exception ignored) {
      return Material.STONE;
    }
  }
}
