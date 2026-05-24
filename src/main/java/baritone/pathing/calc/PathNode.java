package baritone.pathing.calc;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.SettingsUtil;
import java.util.Arrays;

/**
 * A node in the path, containing the cost and steps to get to it.
 *
 * @author leijurv
 */
public final class PathNode {
  /**
   * The position of this node
   */
  public final int x;
  public final int y;
  public final int z;

  /**
   * Cached, should always be equal to goal.heuristic(pos)
   */
  public final double estimatedCostToGoal;

  /**
   * Total cost of getting from start to here
   * Mutable and changed by PathFinder
   */
  public double cost;

  /**
   * Should always be equal to estimatedCosttoGoal + cost
   * Mutable and changed by PathFinder
   */
  public double combinedCost;

  /**
   * In the graph search, what previous node contributed to the cost
   * Mutable and changed by PathFinder
   */
  public PathNode previous;

  public short previousPrimitiveIndex;

  public int previousEdgePayload;

  public double previousEdgeCost;

  /**
   * Where is this node in the array flattenization of the binary heap? Needed for decrease-key operations.
   */
  public int heapPosition;

  public int expansionGeneration;

  public int expansionSerial;

  private long[] consumedExpansionWords;

  public PathNode(int x, int y, int z, Goal goal) {
    this.previous = null;
    this.previousPrimitiveIndex = -1;
    this.cost = ActionCosts.COST_INF;
    this.estimatedCostToGoal = goal.heuristic(x, y, z);
    if (Double.isNaN(estimatedCostToGoal)) {
      throw new IllegalStateException(
        String.format("%s calculated implausible heuristic NaN at %s %s %s", goal, SettingsUtil.maybeCensor(x), SettingsUtil.maybeCensor(y), SettingsUtil.maybeCensor(z)));
    }
    this.heapPosition = -1;
    this.x = x;
    this.y = y;
    this.z = z;
  }

  public boolean isOpen() { return heapPosition != -1; }

  public void resetExpansion(int primitiveCount) {
    expansionGeneration++;
    expansionSerial++;
    int words = primitiveCount + 63 >>> 6;
    if (consumedExpansionWords == null || consumedExpansionWords.length < words) {
      consumedExpansionWords = new long[words];
    } else {
      Arrays.fill(consumedExpansionWords, 0, words, 0L);
    }
  }

  public boolean expansionConsumed(int primitiveIndex) {
    return consumedExpansionWords != null && (consumedExpansionWords[primitiveIndex >>> 6] & 1L << (primitiveIndex & 63)) != 0;
  }

  public void consumeExpansion(int primitiveIndex) {
    consumedExpansionWords[primitiveIndex >>> 6] |= 1L << (primitiveIndex & 63);
  }

  /**
   * TODO: Possibly reimplement hashCode and equals. They are necessary for this class to function but they could be done better
   *
   * @return The hash code value for this {@link PathNode}
   */
  @Override
  public int hashCode() {
    return (int) BetterBlockPos.longHash(x, y, z);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof PathNode other)) {
      return false;
    }
    return x == other.x && y == other.y && z == other.z;
  }
}
