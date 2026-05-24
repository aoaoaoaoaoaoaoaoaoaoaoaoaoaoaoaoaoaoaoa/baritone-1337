package baritone.pathing.movement;

import baritone.api.BaritoneAPI;

import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public record EphemeralObstaclePolicy(boolean enabled, Set<Block> blocks, double clearCostTicks) {

  public EphemeralObstaclePolicy {
    blocks = Set.copyOf(blocks);
  }

  public static EphemeralObstaclePolicy snapshot() {
    return new EphemeralObstaclePolicy(BaritoneAPI.getSettings().allowEphemeralObstacleClearing.value, Set.copyOf(BaritoneAPI.getSettings().ephemeralObstacleBlocks.value),
      BaritoneAPI.getSettings().ephemeralObstacleClearPenalty.value);
  }

  public boolean canClear(CalculationContext context, int x, int y, int z, BlockState state) {
    Block block = state.getBlock();
    return enabled && blocks.contains(block) && context.breaking.allows(block) && !context.breaking.forbids(block) && state.getFluidState().isEmpty() && !context.isPossiblyProtected(x, y, z)
      && context.worldBorder.canPlaceAt(x, z) && officiallyToolLossless(state);
  }

  public static boolean officiallyToolLossless(BlockState state) {
    try {
      return state.getDestroySpeed(null, null) == 0F;
    } catch (NullPointerException ignored) {
      return false;
    }
  }
}
