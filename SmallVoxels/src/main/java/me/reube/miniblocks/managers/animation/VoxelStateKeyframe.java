package me.reube.SmallVoxels.managers.animation;

import java.util.ArrayList;
import java.util.List;

public class VoxelStateKeyframe {
  public int tick;
  public String transition = "HARD";
  public List<AnimatedVoxelPart> parts = new ArrayList<>();
  public List<AnimatedBlockPart> blocks = new ArrayList<>();
  public List<AnimatedImageFramePart> imageFrames = new ArrayList<>();

  public VoxelStateKeyframe() {}

  public VoxelStateKeyframe(int tick, String transition, List<AnimatedVoxelPart> parts) {
    this.tick = tick;
    this.transition = transition;
    this.parts = parts;
  }

  public VoxelStateKeyframe(
      int tick, String transition, List<AnimatedVoxelPart> parts, List<AnimatedBlockPart> blocks) {
    this.tick = tick;
    this.transition = transition;
    this.parts = parts;
    this.blocks = blocks;
  }

  public boolean soft() {
    return "SOFT".equalsIgnoreCase(transition);
  }
}
