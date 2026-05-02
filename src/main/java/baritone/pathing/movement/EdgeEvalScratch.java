package baritone.pathing.movement;

import baritone.utils.pathing.MutableMoveResult;

public final class EdgeEvalScratch {
  private final MutableMoveResult legacy = new MutableMoveResult();

  public EdgeEvalStatus status;
  public int x;
  public int y;
  public int z;
  public double cost;
  public int payload;
  public NodeTerrainFacts nodeFacts;

  public EdgeEvalScratch() {
    blocked();
  }

  public MutableMoveResult legacy() {
    return legacy;
  }

  public void blocked() {
    status = EdgeEvalStatus.BLOCKED;
    x = 0;
    y = 0;
    z = 0;
    cost = 0;
    payload = 0;
    legacy.reset();
  }

  public void reachable(int x, int y, int z, double finitePositiveCost, int payload) {
    status = EdgeEvalStatus.REACHABLE;
    this.x = x;
    this.y = y;
    this.z = z;
    this.cost = finitePositiveCost;
    this.payload = payload;
  }
}
