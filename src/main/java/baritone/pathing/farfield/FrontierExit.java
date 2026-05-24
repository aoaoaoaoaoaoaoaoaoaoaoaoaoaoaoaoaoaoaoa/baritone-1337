package baritone.pathing.farfield;

import baritone.api.utils.BetterBlockPos;

public record FrontierExit(BetterBlockPos source, int boundaryX, int boundaryY, int boundaryZ) {
  public FrontierExit(int sourceX, int sourceY, int sourceZ, int boundaryX, int boundaryY, int boundaryZ) {
    this(new BetterBlockPos(sourceX, sourceY, sourceZ), boundaryX, boundaryY, boundaryZ);
  }

  public int outwardX() {
    return Integer.compare(boundaryX, source.x);
  }

  public int outwardZ() {
    return Integer.compare(boundaryZ, source.z);
  }
}
