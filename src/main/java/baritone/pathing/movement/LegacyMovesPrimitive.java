package baritone.pathing.movement;

import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.movements.MovementAscend;
import baritone.pathing.movement.movements.MovementDescend;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementDownward;
import baritone.pathing.movement.movements.MovementFall;
import baritone.pathing.movement.movements.MovementParkour;
import baritone.pathing.movement.movements.MovementPillar;
import baritone.pathing.movement.movements.MovementTraverse;
import net.minecraft.core.Direction;

public final class LegacyMovesPrimitive implements MovementPrimitive {
  private final Moves move;
  private final DestinationSpec destinationSpec;

  LegacyMovesPrimitive(Moves move) {
    this.move = move;
    BlockOffset offset = new BlockOffset(move.xOffset, move.yOffset, move.zOffset);
    this.destinationSpec = move.dynamicXZ || move.dynamicY ? new DestinationSpec.Dynamic(offset, move.dynamicXZ, move.dynamicY) : new DestinationSpec.Static(offset);
  }

  public Moves move() {
    return move;
  }

  @Override
  public String debugName() {
    return move.name();
  }

  @Override
  public DestinationSpec destinationSpec() {
    return destinationSpec;
  }

  @Override
  public void evaluate(CalculationContext ctx, int x, int y, int z, EdgeEvalScratch out) {
    out.blocked();
    if (!move.dynamicXZ && !move.dynamicY) {
      double cost = staticCost(ctx, x, y, z, out.nodeFacts);
      if (cost >= ActionCosts.COST_INF) {
        return;
      }
      out.reachable(x + move.xOffset, y + move.yOffset, z + move.zOffset, cost, 0);
      return;
    }
    dynamicApply(ctx, x, y, z, out.nodeFacts, out);
  }

  @Override
  public double minimumCost(CalculationContext ctx) {
    if (ctx.costs.breakBlockAdditional() < 0 || ctx.placement.blockCost() < 0) {
      return 0;
    }
    return switch (move) {
      case TRAVERSE_NORTH, TRAVERSE_SOUTH, TRAVERSE_EAST, TRAVERSE_WEST ->
        ctx.costs.walkOnWaterOnePenalty() < 0 ? 0 : Math.min(ctx.movement.canSprint() ? ActionCosts.SPRINT_ONE_BLOCK_COST : ActionCosts.WALK_ONE_BLOCK_COST, ctx.costs.waterWalkSpeed());
      case ASCEND_NORTH, ASCEND_SOUTH, ASCEND_EAST, ASCEND_WEST -> ctx.costs.jumpPenalty() < 0 ? 0 : ActionCosts.WALK_ONE_BLOCK_COST;
      default -> 0;
    };
  }

  @Override
  public Movement instantiate(CalculationContext ctx, BetterBlockPos src, BetterBlockPos dest, int payload) {
    if (move.dynamicXZ || move.dynamicY) {
      EdgeEvalScratch eval = new EdgeEvalScratch();
      evaluate(ctx, src.x, src.y, src.z, eval);
      if (eval.status != EdgeEvalStatus.REACHABLE) {
        return null;
      }
      dest = new BetterBlockPos(eval.x, eval.y, eval.z);
    }
    return switch (move) {
      case DOWNWARD -> new MovementDownward(ctx.getBaritone(), src, src.below());
      case PILLAR -> new MovementPillar(ctx.getBaritone(), src, src.above());
      case TRAVERSE_NORTH, TRAVERSE_SOUTH, TRAVERSE_EAST, TRAVERSE_WEST -> new MovementTraverse(ctx.getBaritone(), src, offset(src));
      case ASCEND_NORTH, ASCEND_SOUTH, ASCEND_EAST, ASCEND_WEST -> new MovementAscend(ctx.getBaritone(), src, offset(src));
      case DESCEND_NORTH, DESCEND_SOUTH, DESCEND_EAST, DESCEND_WEST ->
        dest.y == src.y - 1 ? new MovementDescend(ctx.getBaritone(), src, dest) : new MovementFall(ctx.getBaritone(), src, dest);
      case DIAGONAL_NORTHEAST -> new MovementDiagonal(ctx.getBaritone(), src, Direction.NORTH, Direction.EAST, dest.y - src.y);
      case DIAGONAL_NORTHWEST -> new MovementDiagonal(ctx.getBaritone(), src, Direction.NORTH, Direction.WEST, dest.y - src.y);
      case DIAGONAL_SOUTHEAST -> new MovementDiagonal(ctx.getBaritone(), src, Direction.SOUTH, Direction.EAST, dest.y - src.y);
      case DIAGONAL_SOUTHWEST -> new MovementDiagonal(ctx.getBaritone(), src, Direction.SOUTH, Direction.WEST, dest.y - src.y);
      case PARKOUR_NORTH -> MovementParkour.fromDestination(ctx.getBaritone(), src, dest, Direction.NORTH);
      case PARKOUR_SOUTH -> MovementParkour.fromDestination(ctx.getBaritone(), src, dest, Direction.SOUTH);
      case PARKOUR_EAST -> MovementParkour.fromDestination(ctx.getBaritone(), src, dest, Direction.EAST);
      case PARKOUR_WEST -> MovementParkour.fromDestination(ctx.getBaritone(), src, dest, Direction.WEST);
    };
  }

  private BetterBlockPos offset(BetterBlockPos src) {
    return new BetterBlockPos(src.x + move.xOffset, src.y + move.yOffset, src.z + move.zOffset);
  }

  @Override
  public boolean revalidatesDestinationDuringAssembly() {
    return move.dynamicXZ || move.dynamicY;
  }

  private double staticCost(CalculationContext ctx, int x, int y, int z, NodeTerrainFacts facts) {
    return switch (move) {
      case DOWNWARD -> MovementDownward.cost(ctx, x, y, z);
      case PILLAR -> MovementPillar.cost(ctx, x, y, z);
      case TRAVERSE_NORTH -> MovementTraverse.cost(ctx, facts, x, y, z, x, z - 1);
      case TRAVERSE_SOUTH -> MovementTraverse.cost(ctx, facts, x, y, z, x, z + 1);
      case TRAVERSE_EAST -> MovementTraverse.cost(ctx, facts, x, y, z, x + 1, z);
      case TRAVERSE_WEST -> MovementTraverse.cost(ctx, facts, x, y, z, x - 1, z);
      case ASCEND_NORTH -> MovementAscend.cost(ctx, facts, x, y, z, x, z - 1);
      case ASCEND_SOUTH -> MovementAscend.cost(ctx, facts, x, y, z, x, z + 1);
      case ASCEND_EAST -> MovementAscend.cost(ctx, facts, x, y, z, x + 1, z);
      case ASCEND_WEST -> MovementAscend.cost(ctx, facts, x, y, z, x - 1, z);
      default -> throw new UnsupportedOperationException(move + " is not a static primitive");
    };
  }

  private void dynamicApply(CalculationContext ctx, int x, int y, int z, NodeTerrainFacts facts, EdgeEvalScratch result) {
    switch (move) {
      case DESCEND_EAST -> MovementDescend.cost(ctx, facts, x, y, z, x + 1, z, result);
      case DESCEND_WEST -> MovementDescend.cost(ctx, facts, x, y, z, x - 1, z, result);
      case DESCEND_NORTH -> MovementDescend.cost(ctx, facts, x, y, z, x, z - 1, result);
      case DESCEND_SOUTH -> MovementDescend.cost(ctx, facts, x, y, z, x, z + 1, result);
      case DIAGONAL_NORTHEAST -> MovementDiagonal.cost(ctx, x, y, z, x + 1, z - 1, result);
      case DIAGONAL_NORTHWEST -> MovementDiagonal.cost(ctx, x, y, z, x - 1, z - 1, result);
      case DIAGONAL_SOUTHEAST -> MovementDiagonal.cost(ctx, x, y, z, x + 1, z + 1, result);
      case DIAGONAL_SOUTHWEST -> MovementDiagonal.cost(ctx, x, y, z, x - 1, z + 1, result);
      case PARKOUR_NORTH -> MovementParkour.cost(ctx, x, y, z, Direction.NORTH, result);
      case PARKOUR_SOUTH -> MovementParkour.cost(ctx, x, y, z, Direction.SOUTH, result);
      case PARKOUR_EAST -> MovementParkour.cost(ctx, x, y, z, Direction.EAST, result);
      case PARKOUR_WEST -> MovementParkour.cost(ctx, x, y, z, Direction.WEST, result);
      default -> throw new UnsupportedOperationException(move + " is not a dynamic primitive");
    }
  }
}
