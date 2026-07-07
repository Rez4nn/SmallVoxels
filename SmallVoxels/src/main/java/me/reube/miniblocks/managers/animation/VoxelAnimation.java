package me.reube.SmallVoxels.managers.animation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class VoxelAnimation {
  public String id = "main";
  public String displayName = "main";
  public int lengthTicks = 100;
  public boolean loop = false;
  public double playbackSpeed = 1.0;
  public String trigger = "MANUAL";
  public int triggerRadius = 3;
  public int triggerPlayerCount = 1;
  public String redstoneWorld = null;
  public Integer redstoneX = null;
  public Integer redstoneY = null;
  public Integer redstoneZ = null;
  public List<AnimationTriggerCause> triggerCauses = new ArrayList<>();
  public int priority = 0;
  public String defaultTransition = "HARD";
  public String captureMode = "REPLACE";
  public boolean windEnabled = false;
  public String sound = null;
  public float soundVolume = 1.0f;
  public float soundPitch = 1.0f;
  public List<AnimationKeyframe> transformKeyframes = new ArrayList<>();
  public List<VoxelFrame> voxelFrames = new ArrayList<>();
  public List<VoxelStateKeyframe> stateKeyframes = new ArrayList<>();

  public VoxelAnimation() {}

  public VoxelAnimation(String id) {
    this.id = id;
    this.displayName = id;
  }

  public void sort() {
    transformKeyframes.sort(Comparator.comparingInt(keyframe -> keyframe.tick));
    voxelFrames.sort(Comparator.comparingInt(frame -> frame.tick));
    stateKeyframes.sort(Comparator.comparingInt(frame -> frame.tick));
  }
}
