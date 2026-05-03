package baritone.integration.xaero;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.BetterBlockPos;
import java.nio.file.Path;
import java.util.OptionalInt;

public record XaeroWaypoint(String id, String world, String dimensionDirectory, String name, String initials, int x, OptionalInt y, int z, int color, boolean disabled, int type, String set, Path file,
  int line) {
  public Goal goal() {
    return y.isPresent() ? new GoalBlock(new BetterBlockPos(x, y.getAsInt(), z)) : new GoalXZ(x, z);
  }

  public String positionString() {
    return x + " " + (y.isPresent() ? Integer.toString(y.getAsInt()) : "~") + " " + z;
  }

  public boolean sameDimension(String currentDimensionDirectory) {
    return dimensionDirectory.equals(currentDimensionDirectory);
  }
}
