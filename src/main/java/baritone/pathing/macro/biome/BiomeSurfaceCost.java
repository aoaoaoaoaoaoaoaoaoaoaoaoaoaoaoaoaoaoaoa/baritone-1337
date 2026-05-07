package baritone.pathing.macro.biome;

public record BiomeSurfaceCost(String biome, int samples, int successfulSamples, int successfulTicks, double totalBlocks, double medianTicksPerBlock, double p25TicksPerBlock, double p75TicksPerBlock,
  double failureRate, double damageRate, double jumpTicksPerBlock, double sprintTicksPerBlock, double moveForwardTicksPerBlock, double waterTicksPerBlock, double uncertainty) {
  public BiomeSurfaceCost asUnknown(String biome, double extraUncertainty) {
    return new BiomeSurfaceCost(biome, samples, successfulSamples, successfulTicks, totalBlocks, medianTicksPerBlock, p25TicksPerBlock, p75TicksPerBlock, failureRate, damageRate, jumpTicksPerBlock,
      sprintTicksPerBlock, moveForwardTicksPerBlock, waterTicksPerBlock, uncertainty + extraUncertainty);
  }
}
