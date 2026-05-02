package baritone.command;

import static baritone.api.command.IBaritoneChatControl.FORCE_COMMAND_PREFIX;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.command.argument.ICommandArgument;
import baritone.api.command.exception.CommandNotEnoughArgumentsException;
import baritone.api.command.exception.CommandNotFoundException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.command.manager.ICommandManager;
import baritone.api.event.events.ChatEvent;
import baritone.api.event.events.TabCompleteEvent;
import baritone.api.utils.Helper;
import baritone.behavior.Behavior;
import baritone.command.argument.ArgConsumer;
import baritone.command.argument.CommandArguments;
import baritone.command.manager.CommandManager;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Tuple;
import net.minecraft.util.Util;

public class ExampleBaritoneControl extends Behavior implements Helper {
  private static final Settings settings = BaritoneAPI.getSettings();
  private final ICommandManager manager;

  public ExampleBaritoneControl(Baritone baritone) {
    super(baritone);
    this.manager = baritone.getCommandManager();
  }

  @Override
  public void onSendChatMessage(ChatEvent event) {
    String msg = event.getMessage();
    String prefix = settings.prefix.value;
    boolean forceRun = msg.startsWith(FORCE_COMMAND_PREFIX);
    if ((settings.prefixControl.value && msg.startsWith(prefix)) || forceRun) {
      event.cancel();
      String commandStr = msg.substring(forceRun ? FORCE_COMMAND_PREFIX.length() : prefix.length());
      if (!runCommand(commandStr) && !commandStr.trim().isEmpty()) {
        new CommandNotFoundException(CommandManager.expand(commandStr).getA()).handle(null, null);
      }
    } else if (settings.chatControl.value && runCommand(msg)) {
      event.cancel();
    }
  }

  private void logRanCommand(String command, String rest) {
    if (settings.echoCommands.value) {
      String msg = command + rest;
      String toDisplay = settings.censorRanCommands.value ? command + " ..." : msg;
      MutableComponent component = Component.literal(String.format("> %s", toDisplay));
      component.setStyle(component.getStyle().withColor(ChatFormatting.WHITE).withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to rerun command")))
        .withClickEvent(new ClickEvent.RunCommand(FORCE_COMMAND_PREFIX + msg)));
      logDirect(component);
    }
  }

  public boolean runCommand(String msg) {
    if (msg.trim().equalsIgnoreCase("damn")) {
      logDirect("daniel");
      return false;
    } else if (msg.trim().equalsIgnoreCase("orderpizza")) {
      try {
        Util.getPlatform().openUri("https://www.dominos.com/en/pages/order/");
      } catch (Exception ignored) {
      }
      return false;
    }
    if (msg.isEmpty()) {
      return this.runCommand("help");
    }
    Tuple<String, List<ICommandArgument>> pair = CommandManager.expand(msg);
    String command = pair.getA();
    String rest = msg.substring(pair.getA().length());

    // If the command exists, then handle echoing the input
    if (this.manager.getCommand(pair.getA()) != null) {
      logRanCommand(command, rest);
    }

    return this.manager.execute(pair);
  }

  @Override
  public void onPreTabComplete(TabCompleteEvent event) {
    if (!settings.prefixControl.value) {
      return;
    }
    String prefix = event.prefix;
    String commandPrefix = settings.prefix.value;
    if (!prefix.startsWith(commandPrefix)) {
      return;
    }
    String msg = prefix.substring(commandPrefix.length());
    List<ICommandArgument> args = CommandArguments.from(msg, true);
    Stream<String> stream = tabComplete(msg);
    if (args.size() == 1) {
      stream = stream.map(x -> commandPrefix + x);
    }
    event.completions = stream.toArray(String[]::new);
  }

  public Stream<String> tabComplete(String msg) {
    try {
      List<ICommandArgument> args = CommandArguments.from(msg, true);
      ArgConsumer argc = new ArgConsumer(this.manager, args);
      if (argc.hasExactly(1)) {
        return new TabCompleteHelper().addCommands(this.manager).filterPrefix(argc.getString()).stream();
      }
      return this.manager.tabComplete(msg);
    } catch (CommandNotEnoughArgumentsException ignored) { // Shouldn't happen, the operation is safe
      return Stream.empty();
    }
  }
}
