package baritone.pathing.control;

import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public record ObservationSnapshot(BetterBlockPos feet, Vec3 position, Entity vehicle) {
  public static ObservationSnapshot capture(IPlayerContext ctx) {
    return new ObservationSnapshot(ctx.playerFeet(), ctx.player().position(), ctx.player().getVehicle());
  }
}
