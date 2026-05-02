package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.RelativeBlockPos;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.exception.CommandInvalidTypeException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.selection.ISelection;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.GeofenceBox;
import baritone.config.GeofenceConfigScreen;
import baritone.geofence.GeofenceSettings;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;

public final class GeofenceCommand extends Command {
  public GeofenceCommand(IBaritone baritone) {
    super(baritone, "geofence", "gf");
  }

  @Override
  public void execute(String label, IArgConsumer args) throws CommandException {
    String action = args.hasAny() ? args.getString().toLowerCase(Locale.US) : "list";
    switch (action) {
      case "list", "l" -> list(args);
      case "add", "a" -> add(args);
      case "remove", "rm", "delete", "del" -> remove(args);
      case "clear", "c" -> clear(args);
      case "gui", "menu", "config" -> gui(args);
      default -> throw new CommandInvalidTypeException(args.consumed(), "a geofence action");
    }
  }

  private void list(IArgConsumer args) throws CommandException {
    args.requireMax(0);
    List<GeofenceBox> boxes = GeofenceSettings.boxes();
    if (boxes.isEmpty()) {
      logDirect("No modification geofences.");
      return;
    }
    logDirect("Modification geofences:");
    for (int i = 0; i < boxes.size(); i++) {
      logDirect((i + 1) + ". " + boxes.get(i).describe());
    }
  }

  private void add(IArgConsumer args) throws CommandException {
    if (args.hasAny() && Arrays.asList("selection", "sel", "s").contains(args.peekString().toLowerCase(Locale.US))) {
      args.get();
      args.requireMax(0);
      ISelection[] selections = baritone.getSelectionManager().getSelections();
      int added = GeofenceSettings.addSelections(selections);
      if (added == 0) {
        throw new CommandInvalidStateException("No selections to add.");
      }
      logDirect("Added " + added + " selection geofence" + (added == 1 ? "" : "s") + ".");
      return;
    }
    BetterBlockPos origin = ctx.viewerPos();
    BetterBlockPos first = args.getDatatypePost(RelativeBlockPos.INSTANCE, origin);
    BetterBlockPos second = args.getDatatypePost(RelativeBlockPos.INSTANCE, origin);
    args.requireMax(0);
    GeofenceBox box = GeofenceBox.between(GeofenceSettings.currentDimension(), first, second);
    GeofenceSettings.add(box);
    logDirect("Added modification geofence " + box.describe());
  }

  private void remove(IArgConsumer args) throws CommandException {
    int index = args.getAs(Integer.class);
    args.requireMax(0);
    List<GeofenceBox> boxes = GeofenceSettings.boxes();
    if (index < 1 || index > boxes.size()) {
      throw new CommandInvalidStateException("Geofence index must be between 1 and " + boxes.size());
    }
    GeofenceBox removed = GeofenceSettings.remove(index - 1);
    logDirect("Removed modification geofence " + removed.describe());
  }

  private void clear(IArgConsumer args) throws CommandException {
    args.requireMax(0);
    int count = GeofenceSettings.clear();
    logDirect("Removed " + count + " modification geofence" + (count == 1 ? "" : "s") + ".");
  }

  private void gui(IArgConsumer args) throws CommandException {
    args.requireMax(0);
    Minecraft client = Minecraft.getInstance();
    client.execute(() -> GeofenceConfigScreen.open(client.screen));
  }

  @Override
  public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
    if (!args.hasAny()) {
      return Stream.empty();
    }
    String action = args.getString();
    if (!args.hasAny()) {
      return new TabCompleteHelper().append("list", "add", "remove", "clear", "gui").filterPrefix(action).sortAlphabetically().stream();
    }
    if (action.equalsIgnoreCase("add")) {
      if (args.hasExactlyOne()) {
        return new TabCompleteHelper().append("selection").filterPrefix(args.getString()).stream();
      }
      if (args.hasAtMost(6)) {
        return args.tabCompleteDatatype(RelativeBlockPos.INSTANCE);
      }
    }
    if (Arrays.asList("remove", "rm", "delete", "del").contains(action.toLowerCase(Locale.US)) && args.hasExactlyOne()) {
      String prefix = args.getString();
      return IntStream.rangeClosed(1, GeofenceSettings.boxes().size()).mapToObj(Integer::toString).filter(s -> s.startsWith(prefix));
    }
    return Stream.empty();
  }

  @Override
  public String getShortDesc() { return "Manage break/place geofences"; }

  @Override
  public List<String> getLongDesc() {
    return List.of("Manage 3D boxes where Baritone may not break or place blocks.", "", "Usage:", "> geofence - List geofences", "> geofence gui - Open the geofence menu",
      "> geofence add selection - Add all current selections as geofences in the current dimension", "> geofence add <x1> <y1> <z1> <x2> <y2> <z2> - Add a current-dimension geofence",
      "> geofence remove <index> - Remove a geofence", "> geofence clear - Remove all geofences");
  }
}
