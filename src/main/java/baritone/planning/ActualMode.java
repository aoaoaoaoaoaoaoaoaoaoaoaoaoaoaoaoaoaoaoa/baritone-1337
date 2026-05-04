package baritone.planning;

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;

public sealed interface ActualMode permits ActualMode.OnFoot, ActualMode.InWater, ActualMode.RidingBoat, ActualMode.FallFlying, ActualMode.InPortal {
  ModeKind kind();

  static ActualMode capture(Baritone baritone, IPlayerContext ctx, InventorySnapshot inventory) {
    Entity vehicle = ctx.player().getVehicle();
    if (vehicle instanceof AbstractBoat boat) {
      return new RidingBoat(boat.getId(), boat.position(), boat.getDeltaMovement(), boat.getYRot());
    }
    if (ctx.player().isFallFlying()) {
      return new FallFlying(ctx.player().getDeltaMovement(), inventory.equippedElytraDurability(), inventory.plainFireworks());
    }
    if (ctx.player().isInWater() || ctx.player().isSwimming() || ctx.player().isEyeInFluid(FluidTags.WATER)) {
      return new InWater(ctx.player().getAirSupply(), ctx.player().isEyeInFluid(FluidTags.WATER), ctx.player().isSwimming());
    }
    return new OnFoot(ctx.player().onGround());
  }

  record OnFoot(boolean onGround) implements ActualMode {
    @Override
    public ModeKind kind() {
      return ModeKind.PEDESTRIAN;
    }
  }

  record InWater(int air, boolean eyesWet, boolean swimming) implements ActualMode {
    @Override
    public ModeKind kind() {
      return ModeKind.SWIM;
    }
  }

  record RidingBoat(int entityId, Vec3 position, Vec3 velocity, float yaw) implements ActualMode {
    @Override
    public ModeKind kind() {
      return ModeKind.BOAT;
    }
  }

  record FallFlying(Vec3 velocity, int elytraDurability, int fireworksAvailable) implements ActualMode {
    @Override
    public ModeKind kind() {
      return ModeKind.ELYTRA;
    }
  }

  record InPortal(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> currentDimension) implements ActualMode {
    @Override
    public ModeKind kind() {
      return ModeKind.PORTAL;
    }
  }
}
