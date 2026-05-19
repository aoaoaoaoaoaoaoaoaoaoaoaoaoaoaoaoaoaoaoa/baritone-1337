package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class ProfileCommand extends Command {
  protected ProfileCommand(IBaritone baritone) {
    super(baritone, "profile", "prof");
  }

  @Override
  public void execute(String label, IArgConsumer args) throws CommandException {
    if (args.hasAny()) {
      throw new CommandInvalidStateException("#profile is a single toggle; run it once to arm the next path and again before pathing to disarm");
    }
    logDirect(((Baritone) baritone).getPathProfileController().toggle());
  }

  @Override
  public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
    return Stream.empty();
  }

  @Override
  public String getShortDesc() { return "Toggle full next-path profiling"; }

  @Override
  public List<String> getLongDesc() {
    return Arrays.asList("Toggles a full profile for the next path attempt. The armed path records async-profiler HTML, passive mocap JSONL, and every per-calculation path JSON.",
      "The profile disarms itself when the path attempt ends. Running #profile again before pathing cancels the arm.", "", "Usage:", "> profile");
  }
}
