package baritone.pathing.movement;

public record MovementPolicy(
    boolean canSprint,
    boolean allowParkour,
    boolean allowParkourPlace,
    boolean allowJumpAtBuildLimit,
    boolean allowParkourAscend,
    boolean assumeWalkOnWater,
    int frostWalker,
    boolean allowDiagonalDescend,
    boolean allowDiagonalAscend,
    boolean allowObliqueWalk,
    boolean allowDownward,
    boolean allowWalkOnMagmaBlocks
) {}
