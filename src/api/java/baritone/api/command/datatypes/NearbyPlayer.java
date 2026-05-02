package baritone.api.command.datatypes;

import baritone.api.IBaritone;
import baritone.api.command.exception.CommandException;
import baritone.api.command.helpers.TabCompleteHelper;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * An {@link IDatatype} used to resolve nearby players, those within
 * render distance of the target {@link IBaritone} instance.
 */
public enum NearbyPlayer implements IDatatypeFor<Player> {
  INSTANCE;

  @Override
  public Player get(IDatatypeContext ctx) throws CommandException {
    final String username = ctx.getConsumer().getString();
    return getPlayers(ctx).stream().filter(s -> s.getName().getString().equalsIgnoreCase(username)).findFirst().orElse(null);
  }

  @Override
  public Stream<String> tabComplete(IDatatypeContext ctx) throws CommandException {
    return new TabCompleteHelper().append(getPlayers(ctx).stream().map(Player::getName).map(Component::getString)).filterPrefix(ctx.getConsumer().getString()).sortAlphabetically().stream();
  }

  private static List<? extends Player> getPlayers(IDatatypeContext ctx) {
    return ctx.getBaritone().getPlayerContext().world().players();
  }
}
