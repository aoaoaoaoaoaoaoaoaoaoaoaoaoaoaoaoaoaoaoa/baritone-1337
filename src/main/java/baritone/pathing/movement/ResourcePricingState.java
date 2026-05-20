package baritone.pathing.movement;

public final class ResourcePricingState {
  private ResourcePricing.Epoch active = ResourcePricing.Epoch.BASE;
  private ResourcePricing.Epoch pending = ResourcePricing.Epoch.BASE;
  private long lastAdoptedTick = Long.MIN_VALUE / 4;

  public synchronized ResourcePricing.Prices prices(ResourcePricing.Snapshot snapshot, ResourcePricing.Parameters parameters, double basePlacementPenalty, double baseBreakAdditionalPenalty,
    long nowTick, boolean canAdopt) {
    ResourcePricing.Epoch candidate = ResourcePricing.classify(snapshot, parameters, active);
    if (candidate.equals(active)) {
      pending = candidate;
      return ResourcePricing.prices(basePlacementPenalty, baseBreakAdditionalPenalty, active, parameters);
    }
    pending = candidate;
    if (canAdopt && nowTick - lastAdoptedTick >= parameters.minimumEpochTicks()) {
      active = pending;
      lastAdoptedTick = nowTick;
    }
    return ResourcePricing.prices(basePlacementPenalty, baseBreakAdditionalPenalty, active, parameters);
  }

  public synchronized ResourcePricing.Epoch activeEpoch() {
    return active;
  }

  public synchronized ResourcePricing.Epoch pendingEpoch() {
    return pending;
  }
}
