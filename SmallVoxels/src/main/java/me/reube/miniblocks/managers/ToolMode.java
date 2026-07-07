package me.reube.SmallVoxels.managers;

public enum ToolMode {
  PLACE("Place"),
  REMOVE("Remove"),
  DETAIL("Detail"),
  REPLACE("Replace"),
  BRUSH("Brush"),
  SET("Set"),
  MOVE("Move"),
  ROTATE("Rotate"),
  SCALE("Scale"),
  COPY("Copy"),
  PASTE("Paste"),
  LOCK("Lock"),
  UNDO("Undo"),
  REDO("Redo");

  private final String label;

  ToolMode(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public ToolMode next() {
    ToolMode[] modes = values();
    return modes[(ordinal() + 1) % modes.length];
  }

  public ToolMode previous() {
    ToolMode[] modes = values();
    return modes[Math.floorMod(ordinal() - 1, modes.length)];
  }
}
