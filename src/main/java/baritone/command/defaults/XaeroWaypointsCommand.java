package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.Paginator;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.pathing.goals.Goal;
import baritone.integration.xaero.XaeroWaypoint;
import baritone.integration.xaero.XaeroWaypointStore;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import static baritone.api.command.IBaritoneChatControl.FORCE_COMMAND_PREFIX;

public final class XaeroWaypointsCommand extends Command {
  public XaeroWaypointsCommand(IBaritone baritone) {
    super(baritone, "xwp", "xaerowaypoints", "xaerowaypoint");
  }

  @Override
  public void execute(String label, IArgConsumer args) throws CommandException {
    Action action = args.hasAny() ? Action.getByName(args.peekString()) : Action.LIST;
    if (action == null) {
      action = Action.GOTO;
    } else {
      args.get();
    }
    XaeroWaypointStore store = new XaeroWaypointStore(ctx);
    if (action == Action.LIST) {
      boolean all = consumeAll(args);
      List<XaeroWaypoint> waypoints = all ? store.readAllDimensions() : store.readCurrentDimension();
      if (waypoints.isEmpty()) {
        throw new CommandInvalidStateException(all ? "No Xaero waypoints found" : "No Xaero waypoints found in this dimension");
      }
      Paginator.paginate(args, waypoints, () -> logDirect(all ? "Xaero waypoints:" : "Xaero waypoints in this dimension:"), wp -> component(label, wp, Action.INFO),
        String.format("%s%s list%s", FORCE_COMMAND_PREFIX, label, all ? " all" : ""));
      return;
    }
    XaeroWaypoint waypoint = select(label, args, store, action == Action.INFO);
    if ((action == Action.GOAL || action == Action.GOTO) && !waypoint.sameDimension(store.currentDimensionDirectory())) {
      throw new CommandInvalidStateException("Xaero waypoint is in " + waypoint.dimensionDirectory() + ", but you are in " + store.currentDimensionDirectory());
    }
    if (action == Action.INFO) {
      showInfo(label, waypoint);
      return;
    }
    Goal goal = waypoint.goal();
    if (action == Action.GOAL) {
      baritone.getCustomGoalProcess().setGoal(goal);
      logDirect("Goal: " + goal);
    } else {
      baritone.getCustomGoalProcess().setGoalAndPath(goal);
      logDirect("Going to Xaero waypoint " + waypoint.name() + ": " + goal);
    }
  }

  private XaeroWaypoint select(String label, IArgConsumer args, XaeroWaypointStore store, boolean allowOtherDimensions) throws CommandException {
    args.requireMin(1);
    List<XaeroWaypoint> all = store.readAllDimensions();
    Optional<XaeroWaypoint> byId = consumeId(args, all);
    if (byId.isPresent()) {
      args.requireMax(0);
      return byId.get();
    }
    String query = args.rawRest().trim();
    while (args.hasAny()) {
      args.get();
    }
    List<XaeroWaypoint> matches = matches(query, all, store.currentDimensionDirectory(), allowOtherDimensions);
    if (matches.isEmpty()) {
      throw new CommandInvalidStateException("No Xaero waypoint matching \"" + query + "\"");
    }
    if (matches.size() == 1) {
      return matches.getFirst();
    }
    logDirect("Multiple Xaero waypoints matched:");
    matches.stream().limit(12).map(wp -> component(label, wp, Action.INFO)).forEach(this::logDirect);
    throw new CommandInvalidStateException("Use the clickable result or narrow the name");
  }

  private Optional<XaeroWaypoint> consumeId(IArgConsumer args, List<XaeroWaypoint> waypoints) throws CommandException {
    if (!args.hasAny() || !args.peekString().equals("@")) {
      return Optional.empty();
    }
    args.requireExactly(2);
    args.get();
    String id = args.getString();
    return Optional.of(waypoints.stream().filter(wp -> wp.id().equals(id)).findFirst().orElseThrow(() -> new CommandInvalidStateException("No Xaero waypoint with id " + id)));
  }

  private List<XaeroWaypoint> matches(String query, List<XaeroWaypoint> waypoints, String currentDimension, boolean allowOtherDimensions) {
    String needle = query.toLowerCase(Locale.ROOT);
    Stream<XaeroWaypoint> scoped = waypoints.stream().filter(wp -> allowOtherDimensions || wp.sameDimension(currentDimension));
    List<XaeroWaypoint> exact = scoped.filter(wp -> wp.name().equalsIgnoreCase(query) || wp.initials().equalsIgnoreCase(query)).toList();
    if (!exact.isEmpty()) {
      return exact;
    }
    return waypoints.stream().filter(wp -> allowOtherDimensions || wp.sameDimension(currentDimension))
      .filter(wp -> wp.name().toLowerCase(Locale.ROOT).contains(needle) || wp.initials().toLowerCase(Locale.ROOT).contains(needle) || wp.set().toLowerCase(Locale.ROOT).contains(needle))
      .sorted(Comparator.comparing(XaeroWaypoint::name, String.CASE_INSENSITIVE_ORDER)).toList();
  }

  private void showInfo(String label, XaeroWaypoint waypoint) {
    logDirect(component(label, waypoint, Action.INFO));
    logDirect("Position: " + waypoint.positionString());
    logDirect("World: " + waypoint.world() + " / " + waypoint.dimensionDirectory() + " / set " + waypoint.set() + (waypoint.disabled() ? " / disabled" : ""));
    MutableComponent goal = Component.literal("Click to set goal to this Xaero waypoint");
    goal.setStyle(goal.getStyle().withClickEvent(new ClickEvent.RunCommand(String.format("%s%s goal @ %s", FORCE_COMMAND_PREFIX, label, waypoint.id()))));
    MutableComponent go = Component.literal("Click to path to this Xaero waypoint");
    go.setStyle(go.getStyle().withClickEvent(new ClickEvent.RunCommand(String.format("%s%s goto @ %s", FORCE_COMMAND_PREFIX, label, waypoint.id()))));
    logDirect(goal);
    logDirect(go);
  }

  private Component component(String label, XaeroWaypoint waypoint, Action clickAction) {
    MutableComponent component = Component.literal("");
    MutableComponent name = Component.literal(waypoint.name().isEmpty() ? "<empty>" : waypoint.name());
    name.setStyle(name.getStyle().withColor(waypoint.disabled() ? ChatFormatting.DARK_GRAY : ChatFormatting.GRAY));
    MutableComponent pos = Component.literal(" " + waypoint.positionString());
    pos.setStyle(pos.getStyle().withColor(ChatFormatting.DARK_GRAY));
    MutableComponent dim = Component.literal(" " + waypoint.dimensionDirectory());
    dim.setStyle(dim.getStyle().withColor(ChatFormatting.DARK_GRAY));
    component.append(name).append(pos).append(dim);
    component.setStyle(component.getStyle().withHoverEvent(new HoverEvent.ShowText(Component.literal("Xaero waypoint " + waypoint.id())))
      .withClickEvent(new ClickEvent.RunCommand(String.format("%s%s %s @ %s", FORCE_COMMAND_PREFIX, label, clickAction.names[0], waypoint.id()))));
    return component;
  }

  private boolean consumeAll(IArgConsumer args) throws CommandException {
    if (args.hasAny() && args.peekString().equalsIgnoreCase("all")) {
      args.get();
      return true;
    }
    return false;
  }

  @Override
  public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
    XaeroWaypointStore store = new XaeroWaypointStore(ctx);
    if (args.hasExactlyOne()) {
      return new TabCompleteHelper().append(Action.names()).append(store.readCurrentDimension().stream().map(XaeroWaypoint::name)).sortAlphabetically().filterPrefix(args.getString()).stream();
    }
    if (args.has(2)) {
      Action action = Action.getByName(args.peekString());
      if (action == Action.LIST && args.hasExactly(2)) {
        args.get();
        return new TabCompleteHelper().append("all").filterPrefix(args.getString()).stream();
      }
      if (action == Action.INFO || action == Action.GOAL || action == Action.GOTO) {
        args.get();
        return new TabCompleteHelper().append(store.readCurrentDimension().stream().map(XaeroWaypoint::name)).sortAlphabetically().filterPrefix(args.getString()).stream();
      }
    }
    return Stream.empty();
  }

  @Override
  public String getShortDesc() { return "Read Xaero waypoints"; }

  @Override
  public List<String> getLongDesc() {
    return Arrays.asList("The xwp command reads Xaero Minimap waypoint files without linking against Xaero.", "", "Usage:", "> xwp - List Xaero waypoints in the current dimension",
      "> xwp list [all] - List current-dimension or all Xaero waypoints", "> xwp <name> - Path to a Xaero waypoint in the current dimension", "> xwp goto <name> - Path to a Xaero waypoint",
      "> xwp goal <name> - Set goal to a Xaero waypoint", "> xwp info <name> - Show waypoint details");
  }

  private enum Action {
    LIST("list", "l"), INFO("info", "show", "i"), GOAL("goal", "g"), GOTO("goto", "go");

    private final String[] names;

    Action(String... names) {
      this.names = names;
    }

    static Action getByName(String name) {
      for (Action action : values()) {
        for (String alias : action.names) {
          if (alias.equalsIgnoreCase(name)) {
            return action;
          }
        }
      }
      return null;
    }

    static String[] names() {
      return Arrays.stream(values()).flatMap(action -> Arrays.stream(action.names)).toArray(String[]::new);
    }
  }
}
