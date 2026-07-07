package me.reube.SmallVoxels.managers.animation;

import java.util.ArrayList;
import java.util.List;

public class VoxelFrame {
  public int tick;
  public List<VoxelFrameChange> changes = new ArrayList<>();

  public VoxelFrame() {}

  public VoxelFrame(int tick) {
    this.tick = tick;
  }
}
