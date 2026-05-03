package baritone.process.elytra;

import baritone.Baritone;
import baritone.api.utils.ElytraFireworkPolicy;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public record ElytraFlightPolicy(ResourceKey<Level> dimension, int minY, int maxYExclusive, int defaultTargetY, int autoLaunchY, int cruiseFloorY, int landingColumnHeight, int landingBubbleRadius,
  int landingSupportRadius, ElytraFireworkPolicy fireworkPolicy, boolean preferNativeNetherBackend) {
  private static final int NETHER_MIN_Y = 0;
  private static final int NETHER_MAX_Y_EXCLUSIVE = 128;
  private static final int SKY_POWERED_CRUISE_MARGIN = 96;
  private static final int SKY_ENERGY_CRUISE_MARGIN = 128;

  public static ElytraFlightPolicy capture(Level world) {
    ResourceKey<Level> dimension = world.dimension();
    ElytraFireworkPolicy fireworkPolicy = Baritone.settings().elytraFireworkPolicy.value;
    if (dimension == Level.NETHER) {
      return new ElytraFlightPolicy(dimension, NETHER_MIN_Y, NETHER_MAX_Y_EXCLUSIVE, 64, 31, 80, 15, 4, 1, fireworkPolicy, NetherPathfinderContext.isSupported());
    }
    int minY = world.getMinY();
    int maxYExclusive = minY + world.getHeight();
    int cruiseFloor = clamp(192, minY + 32, maxYExclusive - 24);
    int defaultTarget = clamp(cruiseFloor, minY + 16, maxYExclusive - 24);
    int launch = clamp(96, minY + 16, maxYExclusive - 32);
    return new ElytraFlightPolicy(dimension, minY, maxYExclusive, defaultTarget, launch, cruiseFloor, 15, 4, 1, fireworkPolicy, false);
  }

  public ElytraPathfinderContext createPathfinderContext(IPlayerContext ctx) {
    if (preferNativeNetherBackend) {
      return new NetherPathfinderContext(Baritone.settings().elytraNetherSeed.value);
    }
    return new LoadedWorldElytraPathfinderContext(ctx, this);
  }

  public int targetY(IPlayerContext ctx) {
    return defaultTargetY;
  }

  public boolean inBounds(BlockPos pos) {
    return pos.getY() >= minY && pos.getY() < maxYExclusive;
  }

  public boolean validGoalY(int y) {
    return y > minY && y < maxYExclusive;
  }

  public int cruiseY(int srcY, int dstY, int loadedTerrainCeilingY) {
    int terrainClearance = terrainClearance();
    return clamp(Math.max(Math.max(cruiseFloorY, Math.max(srcY, dstY)), loadedTerrainCeilingY + terrainClearance), minY + 16, maxYExclusive - 24);
  }

  public int terrainClearance() {
    return dimension == Level.NETHER ? 40 : fireworkPolicy.energyGlide() ? SKY_ENERGY_CRUISE_MARGIN : SKY_POWERED_CRUISE_MARGIN;
  }

  public boolean safeLandingBlock(IPlayerContext ctx, BlockPos pos) {
    BlockState state = ctx.world().getBlockState(pos);
    if (!state.getFluidState().isEmpty()) {
      return false;
    }
    if (dimension == Level.NETHER) {
      Block block = state.getBlock();
      return block == Blocks.NETHERRACK || block == Blocks.GRAVEL || (block == Blocks.NETHER_BRICKS && Baritone.settings().elytraAllowLandOnNetherFortress.value);
    }
    return MovementHelper.canWalkOn(ctx, new BetterBlockPos(pos)) && !MovementHelper.avoidWalkingInto(state);
  }

  public boolean supportsTerrainPrediction() {
    return preferNativeNetherBackend;
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
