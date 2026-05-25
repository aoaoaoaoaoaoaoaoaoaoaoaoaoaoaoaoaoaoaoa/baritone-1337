package baritone.pathing.movement;

import baritone.api.BaritoneAPI;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.ActionCosts;
import baritone.cache.WorldData;
import baritone.pathing.calc.BlockKey;
import baritone.pathing.calc.PathingProfiler;
import baritone.pathing.movement.water.WaterTransportPolicy;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.ChunkFactState;
import it.unimi.dsi.fastutil.HashCommon;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * @author Brady
 * @since 8/7/2018
 */
public class CalculationContext {
  private static final ItemStack STACK_BUCKET_WATER = new ItemStack(Items.WATER_BUCKET);
  private static final int BLOCK_STATE_CACHE_SIZE = 1 << 15;
  private static final int BLOCK_STATE_CACHE_MASK = BLOCK_STATE_CACHE_SIZE - 1;

  public final boolean safeForThreadedUse;
  public final IBaritone baritone;
  public final Level world;
  public final WorldData worldData;
  public final BlockStateInterface bsi;
  public final ToolSet toolSet;
  public final PlacementPolicy placement;
  public final BreakPolicy breaking;
  public final EphemeralObstaclePolicy ephemeralObstacles;
  public final MovementPolicy movement;
  public final TrailReversibilityPolicy reversibility;
  public FallPolicy fall;
  public CostPolicy costs;
  public final BetterWorldBorder worldBorder;
  public final ModificationGeofence modificationGeofence;

  public final BlockAffordanceCache affordances;
  public final PathingProfiler pathingProfiler;
  public final MovementCatalog movementCatalog;
  public final WaterTransportPolicy waterTransport;
  private final long[] blockStateCacheKeys;
  private final BlockState[] blockStateCacheValues;

  public CalculationContext(IBaritone baritone) {
    this(baritone, false);
  }

  public CalculationContext(IBaritone baritone, boolean forUseOnAnotherThread) {
    this.safeForThreadedUse = forUseOnAnotherThread;
    this.baritone = baritone;
    LocalPlayer player = baritone.getPlayerContext().player();
    this.world = baritone.getPlayerContext().world();
    this.worldData = (WorldData) baritone.getPlayerContext().worldData();
    this.bsi = new BlockStateInterface(baritone.getPlayerContext(), forUseOnAnotherThread);
    this.toolSet = new ToolSet(player);
    // todo: technically there can now be datapack enchants that replace blocks with any other at any range
    int frostWalkerLevel = 0;
    for (EquipmentSlot slot : EquipmentSlot.values()) {
      ItemEnchantments itemEnchantments = baritone.getPlayerContext().player().getItemBySlot(slot).getEnchantments();
      for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
        if (enchant.is(Enchantments.FROST_WALKER)) {
          frostWalkerLevel = itemEnchantments.getLevel(enchant);
        }
      }
    }
    boolean canSprint = BaritoneAPI.getSettings().allowSprint.value && player.getFoodData().getFoodLevel() > 6;
    boolean sprintInWater = BaritoneAPI.getSettings().sprintInWater.value;
    double waterMoveCost = canSprint && sprintInWater ? ActionCosts.SPRINT_SWIM_ONE_BLOCK_COST : ActionCosts.WALK_ONE_IN_WATER_COST;
    ResourcePricing.Prices resourcePrices = ((Baritone) baritone).getInventoryBehavior().pathingResourcePrices(((Baritone) baritone).getPathingBehavior().resourceRepricingHeadroom());
    this.placement = new PlacementPolicy(BaritoneAPI.getSettings().allowPlace.value && ((Baritone) baritone).getInventoryBehavior().hasGenericThrowaway(), resourcePrices.placementPenalty(),
      BaritoneAPI.getSettings().allowPlaceInFluidsSource.value, BaritoneAPI.getSettings().allowPlaceInFluidsFlow.value);
    this.breaking = BreakPolicy.snapshot();
    this.ephemeralObstacles = EphemeralObstaclePolicy.snapshot();
    this.movement = new MovementPolicy(canSprint, BaritoneAPI.getSettings().allowParkour.value, BaritoneAPI.getSettings().allowParkourPlace.value,
      BaritoneAPI.getSettings().allowJumpAtBuildLimit.value, BaritoneAPI.getSettings().allowParkourAscend.value, BaritoneAPI.getSettings().assumeWalkOnWater.value, frostWalkerLevel,
      BaritoneAPI.getSettings().allowDiagonalDescend.value, BaritoneAPI.getSettings().allowDiagonalAscend.value, BaritoneAPI.getSettings().allowObliqueWalk.value,
      BaritoneAPI.getSettings().allowDownward.value, BaritoneAPI.getSettings().allowWalkOnMagmaBlocks.value, sprintInWater);
    this.reversibility = new TrailReversibilityPolicy(BaritoneAPI.getSettings().pedestrianTrailReversibilityMode.value, BaritoneAPI.getSettings().pedestrianTrailReversibilitySuspectPenalty.value,
      BaritoneAPI.getSettings().pedestrianTrailReversibilityIrreversiblePenalty.value);
    this.fall = new FallPolicy(
      BaritoneAPI.getSettings().allowWaterBucketFall.value && Inventory.isHotbarSlot(player.getInventory().findSlotMatchingItem(STACK_BUCKET_WATER)) && world.dimension() != Level.NETHER, false, 3,
      BaritoneAPI.getSettings().maxFallHeightNoWater.value, BaritoneAPI.getSettings().maxFallHeightBucket.value);
    this.costs = new CostPolicy(resourcePrices.breakAdditionalPenalty(), BaritoneAPI.getSettings().backtrackCostFavoringCoefficient.value, BaritoneAPI.getSettings().jumpPenalty.value,
      BaritoneAPI.getSettings().walkOnWaterOnePenalty.value, BaritoneAPI.getSettings().pedestrianLavaProximityPenalty.value, BaritoneAPI.getSettings().pedestrianCruiseRayBoundaryDividend.value,
      ActionCosts.WALK_ONE_IN_WATER_COST, waterMoveCost);
    // why cache these things here, why not let the movements just get directly from settings?
    // because if some movements are calculated one way and others are calculated another way,
    // then you get a wildly inconsistent path that isn't optimal for either scenario.
    this.worldBorder = new BetterWorldBorder(world.getWorldBorder());
    this.modificationGeofence = ModificationGeofence.snapshot(world.dimension().identifier().toString(), BaritoneAPI.getSettings().modificationGeofences.value);
    this.affordances = new BlockAffordanceCache(this);
    this.pathingProfiler = ((Baritone) baritone).getPathingProfiler();
    this.waterTransport = WaterTransportPolicy.snapshot((Baritone) baritone);
    this.movementCatalog = MovementCatalog.legacyWalking(this);
    this.blockStateCacheKeys = forUseOnAnotherThread ? new long[BLOCK_STATE_CACHE_SIZE] : null;
    this.blockStateCacheValues = forUseOnAnotherThread ? new BlockState[BLOCK_STATE_CACHE_SIZE] : null;
  }

  public CalculationContext(Level world, BlockStateInterface bsi, List<ItemStack> hotbar, boolean hasThrowaway, ResourcePricing.Prices resourcePrices, boolean hasWaterBucket, Path profilerDirectory) {
    this.safeForThreadedUse = true;
    this.baritone = null;
    this.world = world;
    this.worldData = null;
    this.bsi = bsi;
    this.toolSet = new ToolSet(hotbar);
    boolean canSprint = BaritoneAPI.getSettings().allowSprint.value;
    boolean sprintInWater = BaritoneAPI.getSettings().sprintInWater.value;
    double waterMoveCost = canSprint && sprintInWater ? ActionCosts.SPRINT_SWIM_ONE_BLOCK_COST : ActionCosts.WALK_ONE_IN_WATER_COST;
    this.placement = new PlacementPolicy(BaritoneAPI.getSettings().allowPlace.value && hasThrowaway, resourcePrices.placementPenalty(), BaritoneAPI.getSettings().allowPlaceInFluidsSource.value,
      BaritoneAPI.getSettings().allowPlaceInFluidsFlow.value);
    this.breaking = BreakPolicy.snapshot();
    this.ephemeralObstacles = EphemeralObstaclePolicy.snapshot();
    this.movement = new MovementPolicy(canSprint, BaritoneAPI.getSettings().allowParkour.value, BaritoneAPI.getSettings().allowParkourPlace.value,
      BaritoneAPI.getSettings().allowJumpAtBuildLimit.value, BaritoneAPI.getSettings().allowParkourAscend.value, BaritoneAPI.getSettings().assumeWalkOnWater.value, 0,
      BaritoneAPI.getSettings().allowDiagonalDescend.value, BaritoneAPI.getSettings().allowDiagonalAscend.value, BaritoneAPI.getSettings().allowObliqueWalk.value,
      BaritoneAPI.getSettings().allowDownward.value, BaritoneAPI.getSettings().allowWalkOnMagmaBlocks.value, sprintInWater);
    this.reversibility = new TrailReversibilityPolicy(BaritoneAPI.getSettings().pedestrianTrailReversibilityMode.value, BaritoneAPI.getSettings().pedestrianTrailReversibilitySuspectPenalty.value,
      BaritoneAPI.getSettings().pedestrianTrailReversibilityIrreversiblePenalty.value);
    this.fall = new FallPolicy(BaritoneAPI.getSettings().allowWaterBucketFall.value && hasWaterBucket && world.dimension() != Level.NETHER, false, 3,
      BaritoneAPI.getSettings().maxFallHeightNoWater.value, BaritoneAPI.getSettings().maxFallHeightBucket.value);
    this.costs = new CostPolicy(resourcePrices.breakAdditionalPenalty(), BaritoneAPI.getSettings().backtrackCostFavoringCoefficient.value, BaritoneAPI.getSettings().jumpPenalty.value,
      BaritoneAPI.getSettings().walkOnWaterOnePenalty.value, BaritoneAPI.getSettings().pedestrianLavaProximityPenalty.value, BaritoneAPI.getSettings().pedestrianCruiseRayBoundaryDividend.value,
      ActionCosts.WALK_ONE_IN_WATER_COST, waterMoveCost);
    this.worldBorder = new BetterWorldBorder(world.getWorldBorder());
    this.modificationGeofence = ModificationGeofence.snapshot(world.dimension().identifier().toString(), BaritoneAPI.getSettings().modificationGeofences.value);
    this.affordances = new BlockAffordanceCache(this);
    this.pathingProfiler = new PathingProfiler(profilerDirectory);
    this.waterTransport = new WaterTransportPolicy(false, false, 40, 35D, 85D, 2.55D, 0D, 0D);
    this.movementCatalog = MovementCatalog.legacyWalking(this);
    this.blockStateCacheKeys = new long[BLOCK_STATE_CACHE_SIZE];
    this.blockStateCacheValues = new BlockState[BLOCK_STATE_CACHE_SIZE];
  }

  private CalculationContext(CalculationContext source, BlockStateInterface bsi) {
    this.safeForThreadedUse = true;
    this.baritone = source.baritone;
    this.world = source.world;
    this.worldData = source.worldData;
    this.bsi = bsi;
    this.toolSet = source.toolSet;
    this.placement = source.placement;
    this.breaking = source.breaking;
    this.ephemeralObstacles = source.ephemeralObstacles;
    this.movement = source.movement;
    this.reversibility = source.reversibility;
    this.fall = source.fall;
    this.costs = source.costs;
    this.worldBorder = source.worldBorder;
    this.modificationGeofence = source.modificationGeofence;
    this.affordances = new BlockAffordanceCache(this);
    this.pathingProfiler = source.pathingProfiler;
    this.waterTransport = source.waterTransport;
    this.movementCatalog = source.movementCatalog;
    this.blockStateCacheKeys = new long[BLOCK_STATE_CACHE_SIZE];
    this.blockStateCacheValues = new BlockState[BLOCK_STATE_CACHE_SIZE];
  }

  public CalculationContext forkThreadSafe() {
    return new CalculationContext(this, bsi.forkThreadLocalCursor());
  }

  public final IBaritone getBaritone() { return baritone; }

  public BlockState get(int x, int y, int z) {
    if (blockStateCacheValues == null) {
      return bsi.get0(x, y, z); // laughs maniacally
    }
    long key = BlockKey.pack(x, y, z);
    int index = (int) HashCommon.mix(key) & BLOCK_STATE_CACHE_MASK;
    BlockState cached = blockStateCacheValues[index];
    if (cached != null && blockStateCacheKeys[index] == key) {
      return cached;
    }
    BlockState state = bsi.get0(x, y, z);
    blockStateCacheKeys[index] = key;
    blockStateCacheValues[index] = state;
    return state;
  }

  public ChunkFactState chunkFactState(int x, int z) {
    return bsi.chunkFactState(x, z);
  }

  public boolean hasLiveChunk(int x, int z) {
    return bsi.hasLiveChunk(x, z);
  }

  public boolean hasLivePathingData(int x, int z) {
    return bsi.hasLivePathingData(x, z);
  }

  public boolean hasPathingData(int x, int z) {
    return bsi.hasPathingData(x, z);
  }

  public BlockState get(BlockPos pos) {
    return get(pos.getX(), pos.getY(), pos.getZ());
  }

  public Block getBlock(int x, int y, int z) {
    return get(x, y, z).getBlock();
  }

  public double costOfPlacingAt(int x, int y, int z, BlockState current) {
    return affordances.placementCost(x, y, z, current);
  }

  double uncachedCostOfPlacingAt(int x, int y, int z, BlockState current) {
    if (!placement.hasThrowaway()) { // only true if allowPlace is true, see constructor
      return COST_INF;
    }
    if (isPossiblyProtected(x, y, z)) {
      return COST_INF;
    }
    if (!worldBorder.canPlaceAt(x, z)) {
      return COST_INF;
    }
    if (!placement.allowInSourceFluid() && current.getFluidState().isSource()) {
      return COST_INF;
    }
    if (!placement.allowInFlowingFluid() && !current.getFluidState().isEmpty() && !current.getFluidState().isSource()) {
      return COST_INF;
    }
    return placement.blockCost();
  }

  public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
    if (!breaking.allows(current.getBlock())) {
      return COST_INF;
    }
    if (isPossiblyProtected(x, y, z)) {
      return COST_INF;
    }
    return 1;
  }

  public double placeBucketCostAt(int x, int y, int z) {
    return isPossiblyProtected(x, y, z) ? COST_INF : placement.blockCost(); // shrug
  }

  public boolean isPossiblyProtected(int x, int y, int z) {
    return modificationGeofence.forbids(x, y, z);
  }
}
