package baritone.pathing.macro.core;

import baritone.pathing.macro.biome.BiomeSurfaceCost;

public record MacroCostVector(double timeTicks, double jumpTicks, double sprintTicks, double waterExposureTicks, double damageHazard, double failureHazard, double uncertainty) {
  public static final MacroCostVector ZERO = new MacroCostVector(0D, 0D, 0D, 0D, 0D, 0D, 0D);

  public MacroCostVector {
    timeTicks = finiteNonnegative(timeTicks);
    jumpTicks = finiteNonnegative(jumpTicks);
    sprintTicks = finiteNonnegative(sprintTicks);
    waterExposureTicks = finiteNonnegative(waterExposureTicks);
    damageHazard = finiteNonnegative(damageHazard);
    failureHazard = finiteNonnegative(failureHazard);
    uncertainty = finiteNonnegative(uncertainty);
  }

  public MacroCostVector plus(MacroCostVector other) {
    return new MacroCostVector(timeTicks + other.timeTicks, jumpTicks + other.jumpTicks, sprintTicks + other.sprintTicks, waterExposureTicks + other.waterExposureTicks,
      damageHazard + other.damageHazard, failureHazard + other.failureHazard, uncertainty + other.uncertainty);
  }

  public MacroCostVector times(double scale) {
    return new MacroCostVector(timeTicks * scale, jumpTicks * scale, sprintTicks * scale, waterExposureTicks * scale, damageHazard * scale, failureHazard * scale, uncertainty * scale);
  }

  public static MacroCostVector surfacePerBlock(BiomeSurfaceCost cost) {
    return new MacroCostVector(cost.medianTicksPerBlock(), cost.jumpTicksPerBlock(), cost.sprintTicksPerBlock(), cost.waterTicksPerBlock(), hazard(cost.damageRate(), cost.totalBlocks()),
      hazard(cost.failureRate(), cost.totalBlocks()), cost.uncertainty());
  }

  public static MacroCostVector fixedTime(double ticks) {
    return new MacroCostVector(ticks, 0D, 0D, 0D, 0D, 0D, 0D);
  }

  public static MacroCostVector boatTransit(double distance, double costPerBlock) {
    return new MacroCostVector(distance * costPerBlock, 0D, 0D, distance, 0D, 0D, 0D);
  }

  public static MacroCostVector surfaceSwim(double distance, double costPerBlock) {
    return new MacroCostVector(distance * costPerBlock, 0D, distance, distance, 0D, 0D, 0D);
  }

  private static double hazard(double probability, double distance) {
    if (probability <= 0D || distance <= 0D) {
      return 0D;
    }
    double bounded = Math.min(0.999_999D, probability);
    return -Math.log1p(-bounded) / Math.max(1D, distance);
  }

  private static double finiteNonnegative(double value) {
    if (!Double.isFinite(value) || value < 0D) {
      throw new IllegalArgumentException("macro cost dimensions must be finite and nonnegative: " + value);
    }
    return value;
  }
}
