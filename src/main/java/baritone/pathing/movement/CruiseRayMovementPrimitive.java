package baritone.pathing.movement;

import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.movements.MovementCruiseRay;

final class CruiseRayMovementPrimitive implements MovementPrimitive {
  private final int dx;
  private final int dz;
  private final String debugName;
  private final DestinationSpec destinationSpec;
  private final double length;

  CruiseRayMovementPrimitive(int dx, int dz) {
    this.dx = dx;
    this.dz = dz;
    this.debugName = "CRUISE_RAY_" + signed(dx) + "_" + signed(dz);
    this.destinationSpec = new DestinationSpec.Static(new BlockOffset(dx, 0, dz));
    this.length = Math.hypot(dx, dz);
  }

  @Override
  public String debugName() {
    return debugName;
  }

  @Override
  public DestinationSpec destinationSpec() {
    return destinationSpec;
  }

  @Override
  public void evaluate(CalculationContext ctx, int x, int y, int z, EdgeEvalScratch out) {
    out.blocked();
    double cost = MovementCruiseRay.cost(ctx, x, y, z, dx, dz);
    cost = ctx.reversibility.recost(TrailReversibility.INTRINSIC, cost);
    if (cost >= ActionCosts.COST_INF) {
      return;
    }
    out.reachable(x + dx, y, z + dz, cost, 0);
  }

  @Override
  public Movement instantiate(CalculationContext ctx, BetterBlockPos src, BetterBlockPos dest, int payload) {
    return new MovementCruiseRay(ctx.getBaritone(), src, dx, dz);
  }

  @Override
  public double minimumCost(CalculationContext ctx) {
    return length * Math.min(ctx.movement.canSprint() ? ActionCosts.SPRINT_ONE_BLOCK_COST : ActionCosts.WALK_ONE_BLOCK_COST, ctx.costs.waterMoveCost());
  }

  private static String signed(int value) {
    return value < 0 ? "M" + -value : "P" + value;
  }
}
