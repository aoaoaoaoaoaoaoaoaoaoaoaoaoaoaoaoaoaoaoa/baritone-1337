package baritone.planning;

import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.tags.FluidTags;

public record EnvironmentSnapshot(boolean feetWater, boolean belowWater, boolean eyesWet, boolean canSeeSky, int minY, int maxY) {
  public static EnvironmentSnapshot capture(IPlayerContext ctx) {
    return new EnvironmentSnapshot(MovementHelper.isWater(ctx, ctx.playerFeet()), MovementHelper.isWater(ctx, ctx.playerFeet().below()), ctx.player().isEyeInFluid(FluidTags.WATER),
      ctx.world().canSeeSky(ctx.playerFeet().above()), ctx.world().getMinY(), ctx.world().getMaxY());
  }
}
