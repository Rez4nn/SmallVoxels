package me.reube.SmallVoxels.managers.animation;

import java.util.ArrayList;
import java.util.List;

public class AnimatedVoxelObject {
  public int version = 1;
  public String id;
  public String world;
  public double originX;
  public double originY;
  public double originZ;
  public float yaw;
  public int priority = 0;
  public String objectType = "NORMAL";
  public double windDisplacement = 2.0;
  public double windIdleIntensity = 0.25;
  public double windSunIntensity = 0.5;
  public double windRainIntensity = 0.55;
  public double windThunderIntensity = 0.9;
  public double windStiffness = 0.9;
  public double windDamping = 0.65;
  public double windCoherence = 0.85;
  public boolean windEnvironmentReactive = true;
  public double windEnvironmentStrength = 0.6;
  public double windDirectionYaw = 0.0;
  public double windDirectionStrength = 0.8;
  public double windPlayerReactionStrength = 0.8;
  public double windProjectileReactionStrength = 1.0;
  public double windVelocityReactionStrength = 0.7;
  public List<String> windConnectorPartIds = new ArrayList<>();
  public List<String> windNodePartIds = new ArrayList<>();
  public List<WindLink> windLinks = new ArrayList<>();
  public List<WindSourceBlock> windSourceBlocks = new ArrayList<>();
  public List<AnimatedVoxelPart> parts = new ArrayList<>();
  public List<VoxelAnimation> animations = new ArrayList<>();
  public List<AnimationSequence> sequences = new ArrayList<>();
  public List<AnimationAnchor> anchors = new ArrayList<>();
  public List<AnimatedBounds> bounds = new ArrayList<>();

  public AnimatedVoxelObject() {}

  public AnimatedVoxelObject(
      String id, String world, double originX, double originY, double originZ) {
    this.id = id;
    this.world = world;
    this.originX = originX;
    this.originY = originY;
    this.originZ = originZ;
  }

  public VoxelAnimation animation(String animationId) {
    for (VoxelAnimation animation : animations) {
      if (animation.id.equalsIgnoreCase(animationId)) {
        return animation;
      }
    }
    return null;
  }

  public AnimationSequence sequence(String sequenceId) {
    for (AnimationSequence sequence : sequences) {
      if (sequence.id.equalsIgnoreCase(sequenceId)) {
        return sequence;
      }
    }
    return null;
  }
}
