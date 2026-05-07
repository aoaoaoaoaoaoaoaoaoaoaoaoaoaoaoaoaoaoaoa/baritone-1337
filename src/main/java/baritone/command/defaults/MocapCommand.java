package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.behavior.MocapBehavior;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class MocapCommand extends Command {
  public MocapCommand(IBaritone baritone) {
    super(baritone, "mocap", "motioncapture");
  }

  @Override
  public void execute(String label, IArgConsumer args) throws CommandException {
    MocapBehavior mocap = ((Baritone) baritone).getMocapBehavior();
    if (!args.hasAny()) {
      logDirect(mocap.toggle());
      return;
    }
    String action = args.getString().toLowerCase(Locale.US);
    args.requireMax(0);
    switch (action) {
      case "start", "on" -> logDirect(mocap.start());
      case "stop", "off" -> logDirect(mocap.stop());
      case "toggle" -> logDirect(mocap.toggle());
      case "status" -> logDirect(mocap.status());
      case "last" -> logDirect(mocap.output().map(path -> "Last mocap: " + path).orElse("No mocap has been saved yet"));
      default -> throw new CommandInvalidStateException("Unknown mocap action: " + action);
    }
  }

  @Override
  public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
    if (args.hasExactlyOne()) {
      return new TabCompleteHelper().append("start", "stop", "toggle", "status", "last").filterPrefix(args.getString()).stream();
    }
    return Stream.empty();
  }

  @Override
  public String getShortDesc() { return "Toggle passive player mocap"; }

  @Override
  public List<String> getLongDesc() {
    return Arrays.asList("Passively records per-tick player position, velocity, facing, physical inputs, Baritone forced inputs, path state, water state, and a 3x3 bathymetry sounding grid.",
      "It does not teleport, stage, set goals, change inventory, issue commands, or otherwise touch world/player state.", "Output is JSONL under .minecraft/baritone/profiles/.", "", "Usage:",
      "> mocap - Toggle mocap", "> mocap start - Start passive mocap", "> mocap stop - Stop and save mocap", "> mocap status - Show mocap status", "> mocap last - Show last output path");
  }
}
