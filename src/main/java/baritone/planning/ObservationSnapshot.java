package baritone.planning;

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;

public record ObservationSnapshot(long tick, WorldRevision worldRevision, WorldCell feet, Vec3 position, Vec3 velocity, ActualMode actualMode, PlayerVitals vitals, InventorySnapshot inventory,
  EnvironmentSnapshot environment, Optional<PlanCursor> cursor) {
  public static ObservationSnapshot capture(Baritone baritone, IPlayerContext ctx) {
    return capture(baritone, ctx, Optional.empty());
  }

  public static ObservationSnapshot capture(Baritone baritone, IPlayerContext ctx, Optional<PlanCursor> cursor) {
    InventorySnapshot inventory = InventorySnapshot.capture(ctx.player());
    return new ObservationSnapshot(ctx.world().getGameTime(), WorldRevision.observed(inventory), WorldCell.of(ctx.world().dimension(), ctx.playerFeet()), ctx.player().position(),
      ctx.player().getDeltaMovement(), ActualMode.capture(baritone, ctx, inventory), PlayerVitals.capture(ctx.player()), inventory, EnvironmentSnapshot.capture(ctx), cursor);
  }
}
