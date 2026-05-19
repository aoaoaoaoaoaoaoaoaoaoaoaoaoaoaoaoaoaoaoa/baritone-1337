package baritone.pathing.macro.core;

import baritone.Baritone;
import baritone.pathing.macro.biome.BiomeSurfaceCost;
import baritone.pathing.mounted.HorseRoutePlanner;
import baritone.pathing.movement.CalculationContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;

public record MacroTraversalProfile(Kind kind, double horseTicksPerBlock) {
  private static final double PEDESTRIAN_BASE_TICKS_PER_BLOCK = 3.563D;

  public MacroTraversalProfile {
    if (kind != Kind.HORSE) {
      horseTicksPerBlock = Double.NaN;
    } else if (!Double.isFinite(horseTicksPerBlock) || horseTicksPerBlock <= 0D) {
      throw new IllegalArgumentException("horse macro profile requires positive finite ticks/block: " + horseTicksPerBlock);
    }
  }

  public static MacroTraversalProfile physical(CalculationContext context) {
    Entity vehicle = context.getBaritone().getPlayerContext().player().getVehicle();
    return vehicle instanceof AbstractHorse horse ? horse(HorseRoutePlanner.ticksPerBlock(horse)) : pedestrian();
  }

  public static MacroTraversalProfile pedestrian() {
    return new MacroTraversalProfile(Kind.PEDESTRIAN, Double.NaN);
  }

  public static MacroTraversalProfile horse(double ticksPerBlock) {
    return new MacroTraversalProfile(Kind.HORSE, ticksPerBlock);
  }

  public boolean horse() {
    return kind == Kind.HORSE;
  }

  public MacroAgentState physicalState(CalculationContext context) {
    if (horse()) {
      return MacroAgentState.horse();
    }
    return MacroAgentState.physical(context);
  }

  public boolean surfaceTraversalState(MacroAgentState state) {
    return horse() ? state.horseMounted() : state.pedestrianMode();
  }

  public boolean permitsSurfaceWaterTransitions() {
    return !horse();
  }

  public boolean permitsPortalTransitions() {
    return !horse();
  }

  public boolean permitsPredictiveFallback() {
    return horse();
  }

  public MacroCostVector surfaceCost(MacroAtlas atlas, int cellX, int cellZ, double distance) {
    return surfaceCostPerBlock(atlas, cellX, cellZ).times(distance);
  }

  public MacroCostVector surfaceCostPerBlock(MacroAtlas atlas, int cellX, int cellZ) {
    BiomeSurfaceCost prior = atlas.surfaceCost(cellX, cellZ);
    if (!horse()) {
      return MacroCostVector.surfacePerBlock(prior);
    }
    boolean water = atlas.surfaceWaterCostCell(cellX, cellZ);
    double ticksPerBlock = water ? horseTicksPerBlock * HorseRoutePlanner.waterWadeCostMultiplier() : horseTicksPerBlock * Math.max(1D, prior.medianTicksPerBlock() / PEDESTRIAN_BASE_TICKS_PER_BLOCK);
    return new MacroCostVector(ticksPerBlock, 0D, 0D, water ? ticksPerBlock : prior.waterTicksPerBlock(), hazard(prior.damageRate(), prior.totalBlocks()),
      hazard(prior.failureRate(), prior.totalBlocks()), prior.uncertainty());
  }

  public double lowerBoundTicksPerBlock(CalculationContext calculation) {
    double pedestrian = Baritone.settings().costHeuristic.value;
    return horse() ? Math.min(pedestrian, horseTicksPerBlock) : pedestrian;
  }

  public MacroAgentState canonicalSurfaceState() {
    return horse() ? MacroAgentState.horse() : MacroAgentState.pedestrian();
  }

  private static double hazard(double probability, double distance) {
    if (probability <= 0D || distance <= 0D) {
      return 0D;
    }
    return -Math.log1p(-Math.min(0.999_999D, probability)) / Math.max(1D, distance);
  }

  public enum Kind {
    PEDESTRIAN, HORSE
  }
}
