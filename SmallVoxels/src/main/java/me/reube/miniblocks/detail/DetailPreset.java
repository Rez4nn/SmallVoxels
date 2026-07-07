package me.reube.SmallVoxels.detail;

public enum DetailPreset {
  SMALL_GOTHIC_ARCH,
  BROKEN_WINDOW_TRIM,
  MOSSY_CORNER,
  WOODEN_BEAM_JOINT,
  CRACKED_STONE_FACE,
  TINY_SHELF,
  PIPE_SEGMENT,
  BOLT_CLUSTER;

  public static DetailPreset parse(String value) {
    try {
      return valueOf(value.toUpperCase(java.util.Locale.ROOT));
    } catch (Exception ignored) {
      return null;
    }
  }
}
