package me.reube.SmallVoxels.managers.animation;

public class VoxelFrameChange {
  public String partId;
  public String material;
  public String blockData;
  public Boolean visible;
  public Integer localX;
  public Integer localY;
  public Integer localZ;

  public VoxelFrameChange() {}

  public VoxelFrameChange(String partId) {
    this.partId = partId;
  }
}
