package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.pathing.goals.GoalXZ;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class ThisWayCommand extends Command {
  private static final int TOLERANCE_BLOCKS_PER_DISTANCE = 100;
  private static final int MAX_TOLERANCE_BLOCKS = 20;

  public ThisWayCommand(IBaritone baritone) {
    super(baritone, "thisway", "forward");
  }

  @Override
  public void execute(String label, IArgConsumer args) throws CommandException {
    args.requireExactly(1);
    double distance = args.getAs(Double.class);
    GoalXZ goal = GoalXZ.fromDirection(ctx.playerFeetAsVec(), ctx.player().getYHeadRot(), distance, tolerance(distance));
    baritone.getCustomGoalProcess().setGoalAndPath(goal);
    logDirect(String.format("Pathing to: %s", goal));
  }

  static int tolerance(double distance) {
    return Math.min(MAX_TOLERANCE_BLOCKS, (int) (Math.abs(distance) / TOLERANCE_BLOCKS_PER_DISTANCE));
  }

  @Override
  public Stream<String> tabComplete(String label, IArgConsumer args) {
    return Stream.empty();
  }

  @Override
  public String getShortDesc() { return "Path in your current direction"; }

  @Override
  public List<String> getLongDesc() {
    return Arrays.asList("Path toward an XZ target some amount of blocks in the direction you're currently looking", "", "Usage:",
      "> thisway <distance> - paths toward an XZ target distance blocks in front of you, with tolerance floor(distance / 100) capped at 20 blocks");
  }
}
