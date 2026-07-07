package me.reube.SmallVoxels.managers.animation;

public class AnimatedBlockPart {
  public String id;
  public int localX;
  public int localY;
  public int localZ;
  public String material;
  public String blockData;

  public AnimatedBlockPart() {}

  public AnimatedBlockPart(
      String id, int localX, int localY, int localZ, String material, String blockData) {
    this.id = id;
    this.localX = localX;
    this.localY = localY;
    this.localZ = localZ;
    this.material = material;
    this.blockData = blockData;
  }
}
