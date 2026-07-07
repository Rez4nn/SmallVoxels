package me.reube.SmallVoxels.managers.animation;

import java.util.ArrayList;
import java.util.List;

public class WindSourceBlock {
  public String world;
  public int x;
  public int y;
  public int z;
  public String blockData;
  public boolean carved;
  public List<AnimatedVoxelPart> pieces = new ArrayList<>();

  public WindSourceBlock() {}

  public WindSourceBlock(
      String world,
      int x,
      int y,
      int z,
      String blockData,
      boolean carved,
      List<AnimatedVoxelPart> pieces) {
    this.world = world;
    this.x = x;
    this.y = y;
    this.z = z;
    this.blockData = blockData;
    this.carved = carved;
    this.pieces = pieces;
  }

  public String key() {
    return world + ":" + x + ":" + y + ":" + z;
  }
}
