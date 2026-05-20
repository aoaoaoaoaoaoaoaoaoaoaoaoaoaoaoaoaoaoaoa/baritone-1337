package baritone.pathing.movement;

import java.util.Objects;

public final class ResourcePricing {
  private ResourcePricing() {
  }

  public enum BlockSupplyBand {
    SCARCE, ORDINARY, ABUNDANT
  }

  public enum ToolEnduranceBand {
    NONE_OR_UNBREAKABLE, FRAGILE, HEALTHY
  }

  public record Epoch(BlockSupplyBand blocks, ToolEnduranceBand pick) {
    public static final Epoch BASE = new Epoch(BlockSupplyBand.ORDINARY, ToolEnduranceBand.HEALTHY);
  }

  public record Snapshot(int hotbarThrowawayBlocks, int inventoryThrowawayBlocks, double bestPickRemainingFraction) {
    public Snapshot {
      hotbarThrowawayBlocks = Math.max(0, hotbarThrowawayBlocks);
      inventoryThrowawayBlocks = Math.max(0, inventoryThrowawayBlocks);
      if (Double.isNaN(bestPickRemainingFraction) || bestPickRemainingFraction < 0D) {
        bestPickRemainingFraction = Double.POSITIVE_INFINITY;
      }
    }

    public int usableThrowawayBlocks(boolean countInventory) {
      return countInventory ? hotbarThrowawayBlocks + inventoryThrowawayBlocks : hotbarThrowawayBlocks;
    }
  }

  public record Parameters(boolean enabled, boolean countInventory, int lowBlocks, int highBlocks, double scarcePlacementMultiplier, double abundantPlacementMultiplier, double scarceBreakMultiplier,
    double fragilePickPlacementMultiplier, double fragilePickBreakMultiplier, double fragilePickRemainingFraction, int minimumEpochTicks) {
    public Parameters {
      lowBlocks = Math.max(0, lowBlocks);
      highBlocks = Math.max(lowBlocks + 1, highBlocks);
      scarcePlacementMultiplier = finiteNonnegative(scarcePlacementMultiplier);
      abundantPlacementMultiplier = finiteNonnegative(abundantPlacementMultiplier);
      scarceBreakMultiplier = finiteNonnegative(scarceBreakMultiplier);
      fragilePickPlacementMultiplier = finiteNonnegative(fragilePickPlacementMultiplier);
      fragilePickBreakMultiplier = finiteNonnegative(fragilePickBreakMultiplier);
      fragilePickRemainingFraction = Math.clamp(fragilePickRemainingFraction, 0D, 1D);
      minimumEpochTicks = Math.max(0, minimumEpochTicks);
    }
  }

  public record Prices(double placementPenalty, double breakAdditionalPenalty, Epoch epoch) {
    public Prices {
      placementPenalty = finiteNonnegative(placementPenalty);
      breakAdditionalPenalty = finiteNonnegative(breakAdditionalPenalty);
      epoch = Objects.requireNonNull(epoch);
    }
  }

  public static Prices base(double placementPenalty, double breakAdditionalPenalty) {
    return new Prices(placementPenalty, breakAdditionalPenalty, Epoch.BASE);
  }

  static Epoch classify(Snapshot snapshot, Parameters parameters, Epoch active) {
    if (!parameters.enabled) {
      return Epoch.BASE;
    }
    return new Epoch(classifyBlocks(snapshot.usableThrowawayBlocks(parameters.countInventory), parameters, active.blocks), classifyPick(snapshot.bestPickRemainingFraction, parameters, active.pick));
  }

  static Prices prices(double basePlacementPenalty, double baseBreakAdditionalPenalty, Epoch epoch, Parameters parameters) {
    if (!parameters.enabled) {
      return base(basePlacementPenalty, baseBreakAdditionalPenalty);
    }
    double placement = basePlacementPenalty;
    double breaking = baseBreakAdditionalPenalty;
    switch (epoch.blocks) {
      case SCARCE -> {
        placement *= parameters.scarcePlacementMultiplier;
        breaking *= parameters.scarceBreakMultiplier;
      }
      case ABUNDANT -> placement *= parameters.abundantPlacementMultiplier;
      case ORDINARY -> {
      }
    }
    if (epoch.pick == ToolEnduranceBand.FRAGILE) {
      placement *= parameters.fragilePickPlacementMultiplier;
      breaking *= parameters.fragilePickBreakMultiplier;
    }
    return new Prices(placement, breaking, epoch);
  }

  private static BlockSupplyBand classifyBlocks(int blocks, Parameters parameters, BlockSupplyBand active) {
    int lowExit = parameters.lowBlocks + Math.max(4, parameters.lowBlocks / 2);
    int highExit = parameters.highBlocks - Math.max(8, parameters.highBlocks / 4);
    if (active == BlockSupplyBand.SCARCE && blocks < lowExit) {
      return BlockSupplyBand.SCARCE;
    }
    if (active == BlockSupplyBand.ABUNDANT && blocks >= highExit) {
      return BlockSupplyBand.ABUNDANT;
    }
    if (blocks < parameters.lowBlocks) {
      return BlockSupplyBand.SCARCE;
    }
    if (blocks >= parameters.highBlocks) {
      return BlockSupplyBand.ABUNDANT;
    }
    return BlockSupplyBand.ORDINARY;
  }

  private static ToolEnduranceBand classifyPick(double remainingFraction, Parameters parameters, ToolEnduranceBand active) {
    if (!Double.isFinite(remainingFraction)) {
      return ToolEnduranceBand.NONE_OR_UNBREAKABLE;
    }
    double enter = parameters.fragilePickRemainingFraction;
    double exit = Math.min(1D, Math.max(enter + 0.05D, enter * 1.5D));
    if (active == ToolEnduranceBand.FRAGILE && remainingFraction <= exit) {
      return ToolEnduranceBand.FRAGILE;
    }
    return remainingFraction <= enter ? ToolEnduranceBand.FRAGILE : ToolEnduranceBand.HEALTHY;
  }

  private static double finiteNonnegative(double value) {
    if (!Double.isFinite(value)) {
      return 0D;
    }
    return Math.max(0D, value);
  }
}
