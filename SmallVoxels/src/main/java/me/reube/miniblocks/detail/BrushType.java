package me.reube.SmallVoxels.detail;

public enum BrushType {
  MOSS,
  CRACKS,
  DIRT,
  SNOW,
  ASH,
  RUNES,
  WOODEN_BEAM,
  STONE_DETAIL;

  public static BrushType parse(String value) {
    try {
      return valueOf(value.toUpperCase(java.util.Locale.ROOT));
    } catch (Exception ignored) {
      return null;
    }
  }
}
