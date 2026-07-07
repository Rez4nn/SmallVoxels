package me.reube.SmallVoxels.detail;

@FunctionalInterface
public interface FacePattern {
  boolean includes(int u, int v, DetailContext context);
}
