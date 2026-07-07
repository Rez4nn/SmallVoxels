package me.reube.SmallVoxels.managers;

public enum AnimationAxeMode {
  SELECT_ANIMATION("Select Animation"),
  EDIT_TOGGLE("Edit Toggle"),
  CAPTURE_FRAME("Capture Frame"),
  IMAGE_FRAME("Image Frame"),
  SEQUENCE("Sequence"),
  PLAY("Play"),
  WIND_CONNECTOR("Wind Connector"),
  WIND_NODE("Wind Node");

  private final String label;

  AnimationAxeMode(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public AnimationAxeMode next() {
    AnimationAxeMode[] modes = values();
    return modes[(ordinal() + 1) % modes.length];
  }

  public AnimationAxeMode next(boolean windObject) {
    AnimationAxeMode next = next();
    if (windObject) {
      return next;
    }
    while (next == WIND_CONNECTOR || next == WIND_NODE) {
      next = next.next();
    }
    return next;
  }
}
