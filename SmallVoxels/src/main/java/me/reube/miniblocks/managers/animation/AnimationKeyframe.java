package me.reube.SmallVoxels.managers.animation;

public class AnimationKeyframe {
  public int tick;
  public double x;
  public double y;
  public double z;
  public float yaw;

  public AnimationKeyframe() {}

  public AnimationKeyframe(int tick, double x, double y, double z, float yaw) {
    this.tick = tick;
    this.x = x;
    this.y = y;
    this.z = z;
    this.yaw = yaw;
  }
}
