package me.reube.SmallVoxels.managers.animation;

import java.util.ArrayList;
import java.util.List;

public class AnimationSequence {
  public String id = "sequence";
  public boolean loop = false;
  public List<AnimationSequenceStep> steps = new ArrayList<>();

  public AnimationSequence() {}

  public AnimationSequence(String id) {
    this.id = id;
  }
}
