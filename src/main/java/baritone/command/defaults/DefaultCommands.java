package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.ICommand;
import java.util.*;

public final class DefaultCommands {
  private DefaultCommands() {
  }

  public static List<ICommand> createAll(IBaritone baritone) {
    Objects.requireNonNull(baritone);
    List<ICommand> commands = new ArrayList<>(Arrays.asList(new HelpCommand(baritone), new ConfigCommand(baritone), new GeofenceCommand(baritone), new GetCommand(baritone), new SetCommand(baritone),
      new GoalCommand(baritone), new GotoCommand(baritone), new PathCommand(baritone), new ProcCommand(baritone), new ETACommand(baritone), new ProfileCommand(baritone), new VersionCommand(baritone),
      new RepackCommand(baritone), new BuildCommand(baritone), new ComeCommand(baritone), new AxisCommand(baritone), new ForceCancelCommand(baritone), new GcCommand(baritone),
      new InvertCommand(baritone), new TunnelCommand(baritone), new RenderCommand(baritone), new FarmCommand(baritone), new FollowCommand(baritone), new PickupCommand(baritone),
      new ExploreFilterCommand(baritone), new ReloadAllCommand(baritone), new SaveAllCommand(baritone), new ExploreCommand(baritone), new BlacklistCommand(baritone), new FindCommand(baritone),
      new MineCommand(baritone), new ClickCommand(baritone), new SurfaceCommand(baritone), new ThisWayCommand(baritone), new PlayerTelemetryCommand(baritone), new WaypointsCommand(baritone),
      new XaeroWaypointsCommand(baritone), new CommandAlias(baritone, "sethome", "Sets your home waypoint", "waypoints save home"),
      new CommandAlias(baritone, "home", "Path to your home waypoint", "waypoints goto home"), new SelCommand(baritone), new ElytraCommand(baritone)));
    ExecutionControlCommands prc = new ExecutionControlCommands(baritone);
    commands.add(prc.pauseCommand);
    commands.add(prc.resumeCommand);
    commands.add(prc.pausedCommand);
    commands.add(prc.cancelCommand);
    return Collections.unmodifiableList(commands);
  }
}
