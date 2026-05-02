package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.pathing.calc.PathingProfiler;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class ProfileCommand extends Command {
  protected ProfileCommand(IBaritone baritone) {
    super(baritone, "profile", "prof");
  }

  @Override
  public void execute(String label, IArgConsumer args) throws CommandException {
    PathingProfiler profiler = ((Baritone) baritone).getPathingProfiler();
    if (!args.hasAny()) {
      logDirect(profiler.status());
      return;
    }
    String action = args.getString().toLowerCase(Locale.US);
    args.requireMax(0);
    switch (action) {
      case "next" -> logDirect(profiler.armNext());
      case "toggle" -> logDirect(profiler.toggleNext());
      case "status" -> logDirect(profiler.status());
      case "cancel" -> logDirect(profiler.cancelPending());
      case "last" -> logDirect(profiler.lastOutput().map(path -> "Last path profile: " + path).orElse("No path profile has been saved yet"));
      default -> throw new CommandInvalidStateException("Unknown profile action: " + action);
    }
  }

  @Override
  public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
    if (args.hasExactlyOne()) {
      return new TabCompleteHelper().append("next", "toggle", "status", "cancel", "last").filterPrefix(args.getString()).stream();
    }
    return Stream.empty();
  }

  @Override
  public String getShortDesc() { return "Profile the next A* calculation segment"; }

  @Override
  public List<String> getLongDesc() {
    return Arrays.asList("The profile command arms a low-overhead profiler for the next A* calculation segment.",
      "It records search counters and per-movement timing into .minecraft/baritone/profiles/.", "Long gotos may chain multiple segments; this command intentionally records one microprofile.", "",
      "Usage:", "> profile - Show profiler status", "> profile next - Save a JSON profile for the next A* calculation segment", "> profile toggle - Arm or disarm the next path profile",
      "> profile cancel - Cancel a pending profile", "> profile last - Show the last saved profile path");
  }
}
