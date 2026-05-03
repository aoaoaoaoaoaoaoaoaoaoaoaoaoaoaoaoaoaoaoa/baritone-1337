package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.behavior.PlayerTelemetryBehavior;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class PlayerTelemetryCommand extends Command {
  public PlayerTelemetryCommand(IBaritone baritone) {
    super(baritone, "trace", "motiontrace", "playertrace");
  }

  @Override
  public void execute(String label, IArgConsumer args) throws CommandException {
    PlayerTelemetryBehavior telemetry = ((Baritone) baritone).getPlayerTelemetryBehavior();
    if (!args.hasAny()) {
      logDirect(telemetry.toggle());
      return;
    }
    String action = args.getString().toLowerCase(Locale.US);
    args.requireMax(0);
    switch (action) {
      case "start", "on" -> logDirect(telemetry.start());
      case "stop", "off" -> logDirect(telemetry.stop());
      case "toggle" -> logDirect(telemetry.toggle());
      case "status" -> logDirect(telemetry.status());
      case "last" -> logDirect(telemetry.output().map(path -> "Last player telemetry: " + path).orElse("No player telemetry has been saved yet"));
      default -> throw new CommandInvalidStateException("Unknown trace action: " + action);
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
  public String getShortDesc() { return "Toggle tick-by-tick player telemetry"; }

  @Override
  public List<String> getLongDesc() {
    return Arrays.asList("Records per-tick player position, velocity, facing, movement inputs, path state, water state, and a 3x3 bathymetry sounding grid.",
      "Output is JSONL under .minecraft/baritone/profiles/.", "", "Usage:", "> trace - Toggle telemetry", "> trace start - Start telemetry", "> trace stop - Stop and save telemetry",
      "> trace status - Show telemetry status", "> trace last - Show last output path");
  }
}
