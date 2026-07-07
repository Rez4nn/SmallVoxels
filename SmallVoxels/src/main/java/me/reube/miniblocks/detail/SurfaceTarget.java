package me.reube.SmallVoxels.detail;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

public record SurfaceTarget(
    World world,
    Block sourceBlock,
    Block detailBlock,
    BlockFace face,
    Vector hitPosition,
    EdgePattern nearestEdge,
    Material material,
    Material baseMaterial,
    double intensity,
    int resolution,
    List<Material> palette,
    TrimShape trimShape,
    int layerDepth,
    boolean inlaid,
    boolean carveLayer,
    int supportExpansion,
    int cellSize,
    int minU,
    int maxU,
    int minV,
    int maxV,
    int normalCoordinate,
    long randomSeed) {}
