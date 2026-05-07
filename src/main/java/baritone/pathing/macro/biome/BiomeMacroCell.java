package baritone.pathing.macro.biome;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.macro.core.MacroCellEvidence;

public record BiomeMacroCell(int x, int z, String biome, int surfaceY, MacroCellEvidence evidence) {
  public BiomeMacroCell(int x, int z, String biome, int surfaceY, boolean factual) {
    this(x, z, biome, surfaceY, factual ? MacroCellEvidence.CACHED : MacroCellEvidence.PRIOR);
  }

  public boolean factual() {
    return evidence.concrete();
  }

  public long packed() {
    return pack(x, z);
  }

  public BetterBlockPos center(int cellBlocks) {
    return new BetterBlockPos(x * cellBlocks + cellBlocks / 2, surfaceY, z * cellBlocks + cellBlocks / 2);
  }

  public static long pack(int x, int z) {
    return ((long) x << 32) ^ (z & 0xFFFF_FFFFL);
  }
}
