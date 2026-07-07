package me.reube.SmallVoxels.managers.animation;

public class AnimationTriggerCause {
  public String type = "MANUAL";
  public int radius = 3;
  public int playerCount = 1;
  public int cooldownTicks = 40;
  public int intervalTicks = 1200;
  public double chance = 1.0;
  public boolean includeInvisibleEntities = false;
  public String afterAction = "DEFAULT";
  public String nextAnimationId = null;
  public String world = null;
  public Integer x = null;
  public Integer y = null;
  public Integer z = null;

  public AnimationTriggerCause() {}

  public AnimationTriggerCause(String type) {
    this.type = type;
  }
}
