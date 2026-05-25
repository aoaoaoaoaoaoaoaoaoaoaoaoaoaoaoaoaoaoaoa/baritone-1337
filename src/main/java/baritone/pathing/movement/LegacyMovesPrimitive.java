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
  private static final double SQRT_2 = Math.sqrt(2);

  private final Moves move;
  private final Variant variant;
  private final DestinationSpec destinationSpec;
  private final String debugName;

  LegacyMovesPrimitive(Moves move) {
    this(move, Variant.ANY);
  }

  private LegacyMovesPrimitive(Moves move, Variant variant) {
    this.move = move;
    this.variant = variant;
    this.debugName = variant == Variant.ANY ? move.name() : move.name() + variant.suffix;
    BlockOffset offset = variant.descendFeetDrop > 0 ? new BlockOffset(move.xOffset, -variant.descendFeetDrop, move.zOffset) : new BlockOffset(move.xOffset, move.yOffset, move.zOffset);
    this.destinationSpec = variant.staticDestination ? new DestinationSpec.Static(offset)
      : move.dynamicXZ || move.dynamicY ? new DestinationSpec.Dynamic(offset, move.dynamicXZ, move.dynamicY) : new DestinationSpec.Static(offset);
  }

  static LegacyMovesPrimitive descendOneBlock(Moves move) {
    return new LegacyMovesPrimitive(move, Variant.DESCEND_ONE_BLOCK);
  }

  static LegacyMovesPrimitive descendFall(Moves move) {
    return new LegacyMovesPrimitive(move, Variant.DESCEND_FALL);
  }

  static LegacyMovesPrimitive descendExactFall(Moves move, int feetDrop) {
    return new LegacyMovesPrimitive(move, switch (feetDrop) {
      case 2 -> Variant.DESCEND_FALL_TWO;
      case 3 -> Variant.DESCEND_FALL_THREE;
      default -> throw new IllegalArgumentException("unsupported exact fall feetDrop " + feetDrop);
    });
  }

  static LegacyMovesPrimitive traverseClean(Moves move) {
    return new LegacyMovesPrimitive(move, Variant.TRAVERSE_CLEAN);
  }

  static LegacyMovesPrimitive traverseComplex(Moves move) {
    return new LegacyMovesPrimitive(move, Variant.TRAVERSE_COMPLEX);
  }

  public Moves move() {
    return move;
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
    if (variant.descendFeetDrop > 0 || variant == Variant.DESCEND_FALL) {
      evaluateDescendVariant(ctx, x, y, z, out);
      return;
    }
    if (variant == Variant.TRAVERSE_CLEAN || variant == Variant.TRAVERSE_COMPLEX) {
      evaluateTraverseVariant(ctx, x, y, z, out);
      return;
    }
    if (!move.dynamicXZ && !move.dynamicY) {
      double cost = staticCost(ctx, x, y, z, out.nodeFacts);
      cost = ctx.reversibility.recost(reversibilityFor(x, y, z, x + move.xOffset, y + move.yOffset, z + move.zOffset), cost);
      if (cost >= ActionCosts.COST_INF) {
        return;
      }
      out.reachable(x + move.xOffset, y + move.yOffset, z + move.zOffset, cost, 0);
      return;
    }
    dynamicApply(ctx, x, y, z, out.nodeFacts, out);
    if (out.status == EdgeEvalStatus.REACHABLE) {
      double cost = ctx.reversibility.recost(reversibilityFor(x, y, z, out.x, out.y, out.z), out.cost);
      if (cost >= ActionCosts.COST_INF) {
        out.blocked();
      } else {
        out.cost = cost;
      }
    }
  }

  @Override
  public double minimumCost(CalculationContext ctx) {
    if (variant == Variant.DESCEND_ONE_BLOCK) {
      return descendLowerBound(ctx);
    }
    if (variant == Variant.DESCEND_FALL_TWO || variant == Variant.DESCEND_FALL_THREE) {
      return exactFallLowerBound(variant.descendFeetDrop);
    }
    if (variant == Variant.DESCEND_FALL) {
      return fallLowerBound(ctx);
    }
    if (variant == Variant.TRAVERSE_CLEAN) {
      return traverseLowerBound(ctx);
    }
    if (variant == Variant.TRAVERSE_COMPLEX) {
      return traverseComplexLowerBound(ctx);
    }
    return switch (move) {
      case TRAVERSE_NORTH, TRAVERSE_SOUTH, TRAVERSE_EAST, TRAVERSE_WEST -> traverseLowerBound(ctx);
      case ASCEND_NORTH, ASCEND_SOUTH, ASCEND_EAST, ASCEND_WEST -> ascendLowerBound(ctx);
      case DIAGONAL_NORTHEAST, DIAGONAL_NORTHWEST, DIAGONAL_SOUTHEAST, DIAGONAL_SOUTHWEST -> diagonalLowerBound(ctx);
      case DESCEND_NORTH, DESCEND_SOUTH, DESCEND_EAST, DESCEND_WEST -> descendLowerBound(ctx);
      case DOWNWARD -> downwardLowerBound(ctx);
      case PILLAR -> pillarLowerBound(ctx);
      case PARKOUR_NORTH, PARKOUR_SOUTH, PARKOUR_EAST, PARKOUR_WEST -> parkourLowerBound(ctx);
    };
  }

  private static double traverseLowerBound(CalculationContext ctx) {
    // Certificates, not preferences: each value must undercut the cheapest concrete regime the primitive can legally emit.
    double walkOnWater = ActionCosts.WALK_ONE_BLOCK_COST + Math.min(0D, ctx.costs.walkOnWaterOnePenalty());
    if (ctx.movement.canSprint()) {
      walkOnWater *= ActionCosts.SPRINT_MULTIPLIER;
    }
    return nonnegative(Math.min(Math.min(flatStepLowerBound(ctx), ctx.costs.waterMoveCost()), walkOnWater));
  }

  private static double traverseComplexLowerBound(CalculationContext ctx) {
    double passage = Math.min(Math.max(0D, ctx.costs.breakBlockAdditional()), ctx.ephemeralObstacles.enabled() ? ctx.ephemeralObstacles.clearCostTicks() : ActionCosts.COST_INF);
    double bridge = ctx.placement.hasThrowaway() ? Math.max(0D, ctx.placement.blockCost()) : ActionCosts.COST_INF;
    return nonnegative(traverseLowerBound(ctx) + Math.min(passage, bridge));
  }

  private static double ascendLowerBound(CalculationContext ctx) {
    return ActionCosts.WALK_ONE_BLOCK_COST;
  }

  private static double diagonalLowerBound(CalculationContext ctx) {
    double walkOnWater = ActionCosts.WALK_ONE_BLOCK_COST + Math.min(0D, ctx.costs.walkOnWaterOnePenalty()) * SQRT_2;
    if (ctx.movement.canSprint()) {
      walkOnWater *= ActionCosts.SPRINT_MULTIPLIER;
    }
    return nonnegative(SQRT_2 * Math.min(Math.min(flatStepLowerBound(ctx), ctx.costs.waterMoveCost()), walkOnWater));
  }

  private static double descendLowerBound(CalculationContext ctx) {
    return ActionCosts.WALK_OFF_BLOCK_COST + Math.max(ActionCosts.FALL_N_BLOCKS_COST[1], ActionCosts.CENTER_AFTER_FALL_COST);
  }

  private static double fallLowerBound(CalculationContext ctx) {
    int directLandingHeight = Math.max(3, ctx.fall.minHeight());
    double directLanding = ActionCosts.FALL_N_BLOCKS_COST[directLandingHeight];
    double ladderReset = ActionCosts.FALL_N_BLOCKS_COST[2] + ActionCosts.LADDER_DOWN_ONE_COST;
    return ActionCosts.WALK_OFF_BLOCK_COST + Math.min(directLanding, ladderReset);
  }

  private static double exactFallLowerBound(int feetDrop) {
    return ActionCosts.WALK_OFF_BLOCK_COST + ActionCosts.FALL_N_BLOCKS_COST[feetDrop + 1];
  }

  private static double downwardLowerBound(CalculationContext ctx) {
    return Math.min(ActionCosts.LADDER_DOWN_ONE_COST, ActionCosts.FALL_N_BLOCKS_COST[1]);
  }

  private static double pillarLowerBound(CalculationContext ctx) {
    return nonnegative(Math.min(Math.min(ActionCosts.LADDER_UP_ONE_COST, ctx.costs.waterWalkCost()), ActionCosts.JUMP_ONE_BLOCK_COST + Math.min(0D, ctx.costs.jumpPenalty())));
  }

  private static double parkourLowerBound(CalculationContext ctx) {
    double base = ctx.movement.canSprint() && ctx.movement.allowParkourAscend() ? 2 * ActionCosts.SPRINT_ONE_BLOCK_COST : 2 * ActionCosts.WALK_ONE_BLOCK_COST;
    return nonnegative(base + Math.min(0D, ctx.costs.jumpPenalty()));
  }

  private static double flatStepLowerBound(CalculationContext ctx) {
    return ctx.movement.canSprint() ? ActionCosts.SPRINT_ONE_BLOCK_COST : ActionCosts.WALK_ONE_BLOCK_COST;
  }

  private static double nonnegative(double value) {
    return Math.max(0D, value);
  }

  @Override
  public Movement instantiate(CalculationContext ctx, BetterBlockPos src, BetterBlockPos dest, int payload) {
    if (variant == Variant.DESCEND_FALL || variant == Variant.ANY && (move.dynamicXZ || move.dynamicY)) {
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
      case DESCEND_NORTH, DESCEND_SOUTH, DESCEND_EAST, DESCEND_WEST -> dest.y == src.y - 1 ? new MovementDescend(ctx.getBaritone(), src, dest) : new MovementFall(ctx.getBaritone(), src, dest);
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

  TrailReversibility reversibilityFor(int srcX, int srcY, int srcZ, int destX, int destY, int destZ) {
    return switch (move) {
      case DOWNWARD, PILLAR -> TrailReversibility.IRREVERSIBLE;
      case PARKOUR_NORTH, PARKOUR_SOUTH, PARKOUR_EAST, PARKOUR_WEST -> TrailReversibility.SUSPECT;
      case DESCEND_EAST, DESCEND_WEST, DESCEND_NORTH, DESCEND_SOUTH -> destY == srcY - 1 ? TrailReversibility.INTRINSIC : TrailReversibility.IRREVERSIBLE;
      case TRAVERSE_NORTH, TRAVERSE_SOUTH, TRAVERSE_EAST, TRAVERSE_WEST, ASCEND_NORTH, ASCEND_SOUTH, ASCEND_EAST, ASCEND_WEST, DIAGONAL_NORTHEAST, DIAGONAL_NORTHWEST, DIAGONAL_SOUTHEAST,
        DIAGONAL_SOUTHWEST -> TrailReversibility.INTRINSIC;
    };
  }

  @Override
  public boolean revalidatesDestinationDuringAssembly() {
    return variant == Variant.DESCEND_FALL || variant == Variant.ANY && (move.dynamicXZ || move.dynamicY);
  }

  private double staticCost(CalculationContext ctx, int x, int y, int z, NodeTerrainFacts facts) {
    return switch (move) {
      case DOWNWARD -> MovementDownward.cost(ctx, x, y, z);
      case PILLAR -> MovementPillar.cost(ctx, x, y, z);
      case TRAVERSE_NORTH -> traverseCost(ctx, facts, x, y, z, x, z - 1);
      case TRAVERSE_SOUTH -> traverseCost(ctx, facts, x, y, z, x, z + 1);
      case TRAVERSE_EAST -> traverseCost(ctx, facts, x, y, z, x + 1, z);
      case TRAVERSE_WEST -> traverseCost(ctx, facts, x, y, z, x - 1, z);
      case ASCEND_NORTH -> MovementAscend.cost(ctx, facts, x, y, z, x, z - 1);
      case ASCEND_SOUTH -> MovementAscend.cost(ctx, facts, x, y, z, x, z + 1);
      case ASCEND_EAST -> MovementAscend.cost(ctx, facts, x, y, z, x + 1, z);
      case ASCEND_WEST -> MovementAscend.cost(ctx, facts, x, y, z, x - 1, z);
      default -> throw new UnsupportedOperationException(move + " is not a static primitive");
    };
  }

  private double traverseCost(CalculationContext ctx, NodeTerrainFacts facts, int x, int y, int z, int destX, int destZ) {
    return switch (variant) {
      case TRAVERSE_CLEAN -> MovementTraverse.cleanCost(ctx, facts, x, y, z, destX, destZ);
      case TRAVERSE_COMPLEX -> MovementTraverse.complexCost(ctx, facts, x, y, z, destX, destZ);
      default -> MovementTraverse.cost(ctx, facts, x, y, z, destX, destZ);
    };
  }

  private void dynamicApply(CalculationContext ctx, int x, int y, int z, NodeTerrainFacts facts, EdgeEvalScratch result) {
    switch (move) {
      case DESCEND_EAST -> MovementDescend.cost(ctx, facts, x, y, z, x + 1, z, result);
      case DESCEND_WEST -> MovementDescend.cost(ctx, facts, x, y, z, x - 1, z, result);
      case DESCEND_NORTH -> MovementDescend.cost(ctx, facts, x, y, z, x, z - 1, result);
      case DESCEND_SOUTH -> MovementDescend.cost(ctx, facts, x, y, z, x, z + 1, result);
      case DIAGONAL_NORTHEAST -> MovementDiagonal.cost(ctx, facts, x, y, z, x + 1, z - 1, result);
      case DIAGONAL_NORTHWEST -> MovementDiagonal.cost(ctx, facts, x, y, z, x - 1, z - 1, result);
      case DIAGONAL_SOUTHEAST -> MovementDiagonal.cost(ctx, facts, x, y, z, x + 1, z + 1, result);
      case DIAGONAL_SOUTHWEST -> MovementDiagonal.cost(ctx, facts, x, y, z, x - 1, z + 1, result);
      case PARKOUR_NORTH -> MovementParkour.cost(ctx, x, y, z, Direction.NORTH, result);
      case PARKOUR_SOUTH -> MovementParkour.cost(ctx, x, y, z, Direction.SOUTH, result);
      case PARKOUR_EAST -> MovementParkour.cost(ctx, x, y, z, Direction.EAST, result);
      case PARKOUR_WEST -> MovementParkour.cost(ctx, x, y, z, Direction.WEST, result);
      default -> throw new UnsupportedOperationException(move + " is not a dynamic primitive");
    }
  }

  private void evaluateDescendVariant(CalculationContext ctx, int x, int y, int z, EdgeEvalScratch out) {
    int destX = x + move.xOffset;
    int destZ = z + move.zOffset;
    if (variant == Variant.DESCEND_ONE_BLOCK) {
      MovementDescend.oneBlockCost(ctx, out.nodeFacts, x, y, z, destX, destZ, out);
    } else if (variant == Variant.DESCEND_FALL_TWO || variant == Variant.DESCEND_FALL_THREE) {
      MovementDescend.exactNoWaterFallCost(ctx, out.nodeFacts, x, y, z, destX, destZ, variant.descendFeetDrop, out);
    } else {
      MovementDescend.fallCost(ctx, out.nodeFacts, x, y, z, destX, destZ, out);
    }
    if (out.status == EdgeEvalStatus.REACHABLE) {
      double cost = ctx.reversibility.recost(variant == Variant.DESCEND_ONE_BLOCK ? TrailReversibility.INTRINSIC : TrailReversibility.IRREVERSIBLE, out.cost);
      if (cost >= ActionCosts.COST_INF) {
        out.blocked();
      } else {
        out.cost = cost;
      }
    }
  }

  private void evaluateTraverseVariant(CalculationContext ctx, int x, int y, int z, EdgeEvalScratch out) {
    double cost = staticCost(ctx, x, y, z, out.nodeFacts);
    cost = ctx.reversibility.recost(TrailReversibility.INTRINSIC, cost);
    if (cost >= ActionCosts.COST_INF) {
      return;
    }
    out.reachable(x + move.xOffset, y, z + move.zOffset, cost, 0);
  }

  private enum Variant {
    ANY("", false, 0), TRAVERSE_CLEAN("_CLEAN", false, 0), TRAVERSE_COMPLEX("_COMPLEX", false, 0), DESCEND_ONE_BLOCK("_ONE", true, 1), DESCEND_FALL_TWO("_FALL2", true, 2), DESCEND_FALL_THREE("_FALL3",
      true, 3), DESCEND_FALL("_FALL", false, 0);

    final String suffix;
    final boolean staticDestination;
    final int descendFeetDrop;

    Variant(String suffix, boolean staticDestination, int descendFeetDrop) {
      this.suffix = suffix;
      this.staticDestination = staticDestination;
      this.descendFeetDrop = descendFeetDrop;
    }
  }
}
