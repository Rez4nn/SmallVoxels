package me.reube.SmallVoxels.detail;

@FunctionalInterface
public interface DetailBrush {
  FacePattern pattern(DetailContext context);
}
