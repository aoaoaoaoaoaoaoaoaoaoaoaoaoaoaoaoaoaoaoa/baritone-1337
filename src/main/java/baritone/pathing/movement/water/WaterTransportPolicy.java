package baritone.pathing.movement.water;

import baritone.Baritone;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

public record WaterTransportPolicy(boolean boatAvailable, boolean boatMounted, int boatMinimumBlocks, double boatSetupCost, double boatPickupCost, double boatCostPerBlock, double boatHeadingX,
  double boatHeadingZ) {
  private static final int BOAT_MINIMUM_BLOCKS = 40;
  private static final double BOAT_SETUP_COST = 35D;
  private static final double BOAT_PICKUP_AXE_COST = 55D;
  private static final double BOAT_PICKUP_BARE_HAND_COST = 85D;
  private static final double BOAT_COST_PER_BLOCK = 2.55D;

  public static WaterTransportPolicy snapshot(Baritone baritone) {
    double pickupCost = baritone.getInventoryBehavior().hasAxe() ? BOAT_PICKUP_AXE_COST : BOAT_PICKUP_BARE_HAND_COST;
    Entity vehicle = baritone.getPlayerContext().player().getVehicle();
    if (vehicle instanceof AbstractBoat boat) {
      double vx = boat.getDeltaMovement().x;
      double vz = boat.getDeltaMovement().z;
      double speed = Math.hypot(vx, vz);
      if (speed > 0.05D) {
        return new WaterTransportPolicy(true, true, BOAT_MINIMUM_BLOCKS, BOAT_SETUP_COST, pickupCost, BOAT_COST_PER_BLOCK, vx / speed, vz / speed);
      }
      return new WaterTransportPolicy(true, true, BOAT_MINIMUM_BLOCKS, BOAT_SETUP_COST, pickupCost, BOAT_COST_PER_BLOCK, 0D, 0D);
    }
    return new WaterTransportPolicy(baritone.getInventoryBehavior().hasBoat(), false, BOAT_MINIMUM_BLOCKS, BOAT_SETUP_COST, pickupCost, BOAT_COST_PER_BLOCK, 0D, 0D);
  }

  public boolean hasBoatHeading() {
    return boatHeadingX != 0D || boatHeadingZ != 0D;
  }
}
