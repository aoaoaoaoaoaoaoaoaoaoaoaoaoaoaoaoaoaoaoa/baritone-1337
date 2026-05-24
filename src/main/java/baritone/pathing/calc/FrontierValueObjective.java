package baritone.pathing.calc;

import baritone.api.pathing.goals.Goal;

/**
 * A goal augmented by scalar continuation values at exact pathing-data frontiers.
 *
 * <p>The source node is exact. The boundary position is the first static primitive probe outside pathing data. Implementations must not infer interior local
 * exits from coarse cells; this seam exists solely for exact expansion discovering that an otherwise ordinary movement would leave known terrain.</p>
 */
public interface FrontierValueObjective extends Goal {

  double frontierExitValue(int sourceX, int sourceY, int sourceZ, int boundaryX, int boundaryY, int boundaryZ);

  default double terminalExitValue(int x, int y, int z) {
    return 0D;
  }
}
