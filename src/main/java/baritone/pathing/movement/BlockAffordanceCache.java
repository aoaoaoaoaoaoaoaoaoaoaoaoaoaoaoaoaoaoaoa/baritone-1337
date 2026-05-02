package baritone.pathing.movement;

import baritone.pathing.calc.BlockKey;
import baritone.utils.pathing.BetterWorldBorder;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;

public final class BlockAffordanceCache {
  private static final int CAN_WALK_THROUGH_KNOWN = 1 << 0;
  private static final int CAN_WALK_THROUGH_VALUE = 1 << 1;
  private static final int FULLY_PASSABLE_KNOWN = 1 << 2;
  private static final int FULLY_PASSABLE_VALUE = 1 << 3;
  private static final int CAN_WALK_ON_KNOWN = 1 << 4;
  private static final int CAN_WALK_ON_VALUE = 1 << 5;
  private static final int REPLACEABLE_KNOWN = 1 << 6;
  private static final int REPLACEABLE_VALUE = 1 << 7;
  private static final int AVOID_WALKING_INTO_KNOWN = 1 << 8;
  private static final int AVOID_WALKING_INTO_VALUE = 1 << 9;
  private static final int FLOWING_FLUID_KNOWN = 1 << 10;
  private static final int FLOWING_FLUID_VALUE = 1 << 11;
  private static final int CAN_PLACE_AGAINST_KNOWN = 1 << 12;
  private static final int CAN_PLACE_AGAINST_VALUE = 1 << 13;

  private final CalculationContext context;
  private final Long2IntOpenHashMap positionAffordanceBits = new Long2IntOpenHashMap();
  private final Long2DoubleOpenHashMap miningCost = new Long2DoubleOpenHashMap();
  private final Long2DoubleOpenHashMap miningCostWithFalling = new Long2DoubleOpenHashMap();
  private final Long2DoubleOpenHashMap placementCost = new Long2DoubleOpenHashMap();

  BlockAffordanceCache(CalculationContext context) {
    this.context = context;
    miningCost.defaultReturnValue(Double.NaN);
    miningCostWithFalling.defaultReturnValue(Double.NaN);
    placementCost.defaultReturnValue(Double.NaN);
  }

  public BlockState stateAt(int x, int y, int z) {
    return context.get(x, y, z);
  }

  public boolean canWalkThrough(int x, int y, int z) {
    return canWalkThrough(x, y, z, stateAt(x, y, z));
  }

  public boolean canWalkThrough(int x, int y, int z, BlockState state) {
    long key = BlockKey.pack(x, y, z);
    int bits = positionAffordanceBits.get(key);
    if ((bits & CAN_WALK_THROUGH_KNOWN) != 0) return (bits & CAN_WALK_THROUGH_VALUE) != 0;
    boolean result = context.precomputedData.canWalkThrough(context.bsi, x, y, z, state);
    positionAffordanceBits.put(key, bits | CAN_WALK_THROUGH_KNOWN | (result ? CAN_WALK_THROUGH_VALUE : 0));
    return result;
  }

  public boolean fullyPassable(int x, int y, int z) {
    return fullyPassable(x, y, z, stateAt(x, y, z));
  }

  public boolean fullyPassable(int x, int y, int z, BlockState state) {
    long key = BlockKey.pack(x, y, z);
    int bits = positionAffordanceBits.get(key);
    if ((bits & FULLY_PASSABLE_KNOWN) != 0) return (bits & FULLY_PASSABLE_VALUE) != 0;
    boolean result = context.precomputedData.fullyPassable(context.bsi, x, y, z, state);
    positionAffordanceBits.put(key, bits | FULLY_PASSABLE_KNOWN | (result ? FULLY_PASSABLE_VALUE : 0));
    return result;
  }

  public boolean canWalkOn(int x, int y, int z) {
    return canWalkOn(x, y, z, stateAt(x, y, z));
  }

  public boolean canWalkOn(int x, int y, int z, BlockState state) {
    long key = BlockKey.pack(x, y, z);
    int bits = positionAffordanceBits.get(key);
    if ((bits & CAN_WALK_ON_KNOWN) != 0) return (bits & CAN_WALK_ON_VALUE) != 0;
    boolean result = context.precomputedData.canWalkOn(context.bsi, x, y, z, state);
    positionAffordanceBits.put(key, bits | CAN_WALK_ON_KNOWN | (result ? CAN_WALK_ON_VALUE : 0));
    return result;
  }

  public boolean replaceable(int x, int y, int z) {
    return replaceable(x, y, z, stateAt(x, y, z));
  }

  public boolean replaceable(int x, int y, int z, BlockState state) {
    long key = BlockKey.pack(x, y, z);
    int bits = positionAffordanceBits.get(key);
    if ((bits & REPLACEABLE_KNOWN) != 0) return (bits & REPLACEABLE_VALUE) != 0;
    boolean result = MovementHelper.isReplaceable(x, y, z, state, context.bsi);
    positionAffordanceBits.put(key, bits | REPLACEABLE_KNOWN | (result ? REPLACEABLE_VALUE : 0));
    return result;
  }

  public boolean avoidWalkingInto(int x, int y, int z) {
    return avoidWalkingInto(x, y, z, stateAt(x, y, z));
  }

  public boolean avoidWalkingInto(int x, int y, int z, BlockState state) {
    long key = BlockKey.pack(x, y, z);
    int bits = positionAffordanceBits.get(key);
    if ((bits & AVOID_WALKING_INTO_KNOWN) != 0) return (bits & AVOID_WALKING_INTO_VALUE) != 0;
    boolean result = MovementHelper.avoidWalkingInto(state);
    positionAffordanceBits.put(key, bits | AVOID_WALKING_INTO_KNOWN | (result ? AVOID_WALKING_INTO_VALUE : 0));
    return result;
  }

  public boolean flowingFluid(int x, int y, int z) {
    return flowingFluid(x, y, z, stateAt(x, y, z));
  }

  public boolean flowingFluid(int x, int y, int z, BlockState state) {
    long key = BlockKey.pack(x, y, z);
    int bits = positionAffordanceBits.get(key);
    if ((bits & FLOWING_FLUID_KNOWN) != 0) return (bits & FLOWING_FLUID_VALUE) != 0;
    boolean result = MovementHelper.isFlowing(x, y, z, state, context.bsi);
    positionAffordanceBits.put(key, bits | FLOWING_FLUID_KNOWN | (result ? FLOWING_FLUID_VALUE : 0));
    return result;
  }

  public boolean canPlaceAgainst(int x, int y, int z) {
    return canPlaceAgainst(x, y, z, stateAt(x, y, z));
  }

  public boolean canPlaceAgainst(int x, int y, int z, BlockState state) {
    long key = BlockKey.pack(x, y, z);
    int bits = positionAffordanceBits.get(key);
    if ((bits & CAN_PLACE_AGAINST_KNOWN) != 0) return (bits & CAN_PLACE_AGAINST_VALUE) != 0;
    BetterWorldBorder border = context.worldBorder;
    boolean result = border.canPlaceAt(x, z) && (MovementHelper.isBlockNormalCube(state) || MovementHelper.isGlassLike(state));
    positionAffordanceBits.put(key, bits | CAN_PLACE_AGAINST_KNOWN | (result ? CAN_PLACE_AGAINST_VALUE : 0));
    return result;
  }

  public double miningCost(int x, int y, int z, boolean includeFalling) {
    return miningCost(x, y, z, stateAt(x, y, z), includeFalling);
  }

  public double miningCost(int x, int y, int z, BlockState state, boolean includeFalling) {
    long key = BlockKey.pack(x, y, z);
    Long2DoubleOpenHashMap cache = includeFalling ? miningCostWithFalling : miningCost;
    double cached = cache.get(key);
    if (!Double.isNaN(cached)) return cached;
    double result = MovementHelper.computeMiningDurationTicks(context, x, y, z, state, includeFalling);
    cache.put(key, result);
    return result;
  }

  public double placementCost(int x, int y, int z) {
    return placementCost(x, y, z, stateAt(x, y, z));
  }

  public double placementCost(int x, int y, int z, BlockState state) {
    long key = BlockKey.pack(x, y, z);
    double cached = placementCost.get(key);
    if (!Double.isNaN(cached)) return cached;
    double result = context.uncachedCostOfPlacingAt(x, y, z, state);
    placementCost.put(key, result);
    return result;
  }
}
