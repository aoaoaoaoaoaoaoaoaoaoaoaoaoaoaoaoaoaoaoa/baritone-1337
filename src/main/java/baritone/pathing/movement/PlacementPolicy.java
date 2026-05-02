package baritone.pathing.movement;

public record PlacementPolicy(boolean hasThrowaway, double blockCost, boolean allowInSourceFluid, boolean allowInFlowingFluid) {}
