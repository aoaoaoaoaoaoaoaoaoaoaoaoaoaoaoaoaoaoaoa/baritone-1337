package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.ItemById;
import baritone.api.command.exception.CommandException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public class PickupCommand extends Command {

  public PickupCommand(IBaritone baritone) {
    super(baritone, "pickup");
  }

  @Override
  public void execute(String label, IArgConsumer args) throws CommandException {
    Set<Item> collecting = new HashSet<>();
    while (args.hasAny()) {
      Item item = args.getDatatypeFor(ItemById.INSTANCE);
      collecting.add(item);
    }
    if (collecting.isEmpty()) {
      baritone.getFollowProcess().pickup(stack -> true);
      logDirect("Picking up all items");
    } else {
      baritone.getFollowProcess().pickup(stack -> collecting.contains(stack.getItem()));
      logDirect("Picking up these items:");
      collecting.stream().map(BuiltInRegistries.ITEM::getKey).map(Identifier::toString).forEach(this::logDirect);
    }
  }

  @Override
  public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
    while (args.has(2)) {
      if (args.peekDatatypeOrNull(ItemById.INSTANCE) == null) {
        return Stream.empty();
      }
      args.get();
    }
    return args.tabCompleteDatatype(ItemById.INSTANCE);
  }

  @Override
  public String getShortDesc() { return "Pickup items"; }

  @Override
  public List<String> getLongDesc() { return Arrays.asList("Usage:", "> pickup - Pickup anything", "> pickup <item1> <item2> <...> - Pickup certain items"); }
}
