package me.reube.SmallVoxels.managers.animation;

public class AnimatedImageFramePart {
  public String id;
  public int localX;
  public int localY;
  public int localZ;
  public String plane = "XY";
  public int tileX;
  public int tileY;
  public int columns = 1;
  public int rows = 1;
  public String imagePngBase64;

  public AnimatedImageFramePart() {}

  public AnimatedImageFramePart(
      String id,
      int localX,
      int localY,
      int localZ,
      String plane,
      int tileX,
      int tileY,
      int columns,
      int rows,
      String imagePngBase64) {
    this.id = id;
    this.localX = localX;
    this.localY = localY;
    this.localZ = localZ;
    this.plane = plane;
    this.tileX = tileX;
    this.tileY = tileY;
    this.columns = columns;
    this.rows = rows;
    this.imagePngBase64 = imagePngBase64;
  }
}
