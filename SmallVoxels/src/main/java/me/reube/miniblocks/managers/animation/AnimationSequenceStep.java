package me.reube.SmallVoxels.managers.animation;

public class AnimationSequenceStep {
  public String animationId = "main";
  public int delayBeforeTicks = 0;
  public int delayAfterTicks = 0;
  public String triggerType = "AUTO";
  public int triggerRadius = 3;
  public int triggerPlayerCount = 1;
  public boolean includeInvisibleEntities = false;
  public boolean waitForCompletion = true;

  public AnimationSequenceStep() {}

  public AnimationSequenceStep(String animationId, int delayBeforeTicks, int delayAfterTicks) {
    this.animationId = animationId;
    this.delayBeforeTicks = delayBeforeTicks;
    this.delayAfterTicks = delayAfterTicks;
  }
}
