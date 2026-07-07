package me.reube.SmallVoxels.managers.animation;

public class WindLink {
  public String fromPartId;
  public String toPartId;
  public double restDistance;

  public WindLink() {}

  public WindLink(String fromPartId, String toPartId, double restDistance) {
    this.fromPartId = fromPartId;
    this.toPartId = toPartId;
    this.restDistance = restDistance;
  }
}
