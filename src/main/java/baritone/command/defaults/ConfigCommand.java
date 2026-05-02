package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.config.BaritoneConfigScreen;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;

public final class ConfigCommand extends Command {
  public ConfigCommand(IBaritone baritone) {
    super(baritone, "config", "cfg");
  }

  @Override
  public void execute(String label, IArgConsumer args) throws CommandException {
    args.requireMax(0);
    Minecraft client = Minecraft.getInstance();
    client.execute(() -> BaritoneConfigScreen.open(client.screen));
  }

  @Override
  public Stream<String> tabComplete(String label, IArgConsumer args) {
    return Stream.empty();
  }

  @Override
  public String getShortDesc() {
    return "Open the config GUI";
  }

  @Override
  public List<String> getLongDesc() {
    return List.of("Opens the curated baritone-1337 config GUI. Expert settings remain available through get/set.");
  }
}
