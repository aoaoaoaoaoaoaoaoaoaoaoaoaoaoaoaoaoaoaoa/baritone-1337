package baritone.pathing.movement.water;

public record WaterLineProfile(SurfaceWaterMode mode, double halfWidth, int minimumBlocks, double setupCost, double pickupCost, double costPerBlock) {
  public static WaterLineProfile swim(double costPerBlock) {
    return new WaterLineProfile(new SurfaceWaterMode.Swim(), 0.30000001192092896D, 6, 0D, 0D, costPerBlock);
  }

  public static WaterLineProfile boat(WaterTransportPolicy policy) {
    return boatLaunch(policy, true);
  }

  public static WaterLineProfile mountedBoat(WaterTransportPolicy policy) {
    return mountedBoat(policy, true);
  }

  public static WaterLineProfile boatLaunch(WaterTransportPolicy policy, boolean terminal) {
    return new WaterLineProfile(new SurfaceWaterMode.Boat(), 0.85D, policy.boatMinimumBlocks(), policy.boatSetupCost(), terminal ? policy.boatPickupCost() : 0D, policy.boatCostPerBlock());
  }

  public static WaterLineProfile mountedBoat(WaterTransportPolicy policy, boolean terminal) {
    return new WaterLineProfile(new SurfaceWaterMode.Boat(), 0.85D, 1, 0D, terminal ? policy.boatPickupCost() : 0D, policy.boatCostPerBlock());
  }

  public double cost(double blocks) {
    return setupCost + pickupCost + costPerBlock * blocks;
  }

  public boolean boat() {
    return mode.boat();
  }
}
