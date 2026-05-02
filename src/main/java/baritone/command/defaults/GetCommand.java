package baritone.command.defaults;

import static baritone.api.command.IBaritoneChatControl.FORCE_COMMAND_PREFIX;
import static baritone.api.utils.SettingsUtil.settingDefaultToString;
import static baritone.api.utils.SettingsUtil.settingTypeToString;
import static baritone.api.utils.SettingsUtil.settingValueToString;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidTypeException;
import baritone.api.command.helpers.Paginator;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.utils.SettingsUtil;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class GetCommand extends Command {
  public GetCommand(IBaritone baritone) {
    super(baritone, "get");
  }

  @Override
  public void execute(String label, IArgConsumer args) throws CommandException {
    String arg = args.hasAny() ? args.getString().toLowerCase(Locale.US) : "list";
    boolean viewModified = Arrays.asList("m", "mod", "modified").contains(arg);
    boolean viewAll = Arrays.asList("all", "l", "list").contains(arg);
    if (viewModified || viewAll) {
      String search = args.hasAny() && args.peekAsOrNull(Integer.class) == null ? args.getString() : "";
      args.requireMax(1);
      Stream<Settings.Setting<?>> source = viewModified ? SettingsUtil.modifiedSettings(Baritone.settings()).stream() : Baritone.settings().allSettings.stream();
      List<Settings.Setting<?>> settings = source.filter(s -> !s.isJavaOnly()).filter(s -> s.getName().toLowerCase(Locale.US).contains(search.toLowerCase(Locale.US)))
        .sorted((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName())).collect(Collectors.toList());
      Paginator.paginate(args, new Paginator<>(settings),
        () -> logDirect(!search.isEmpty() ? String.format("%ssettings containing '%s':", viewModified ? "Modified " : "", search) : String.format("%ssettings:", viewModified ? "Modified " : "All ")),
        this::renderSettingSummary, FORCE_COMMAND_PREFIX + "get " + arg + " " + search);
      return;
    }

    args.requireMax(0);
    Settings.Setting<?> setting = Baritone.settings().byLowerName.get(arg);
    if (setting == null || setting.isJavaOnly()) {
      throw new CommandInvalidTypeException(args.consumed(), "a readable setting");
    }
    logDirect(renderSettingDetails(setting));
  }

  private MutableComponent renderSettingSummary(Settings.Setting<?> setting) {
    MutableComponent type = Component.literal(" (" + settingTypeToString(setting) + ")");
    type.setStyle(type.getStyle().withColor(ChatFormatting.DARK_GRAY));
    MutableComponent line = Component.literal(setting.getName());
    line.setStyle(line.getStyle().withColor(ChatFormatting.GRAY));
    line.append(type);
    line.append(Component.literal(" = " + settingValueToString(setting)).withStyle(ChatFormatting.WHITE));
    return line;
  }

  private MutableComponent renderSettingDetails(Settings.Setting<?> setting) {
    MutableComponent line = Component.literal(setting.getName());
    line.setStyle(line.getStyle().withColor(ChatFormatting.GRAY));
    line.append(Component.literal(" = " + settingValueToString(setting)).withStyle(ChatFormatting.WHITE));
    line.append(Component.literal("\nType: " + settingTypeToString(setting)).withStyle(ChatFormatting.DARK_GRAY));
    line.append(Component.literal("\nDefault: " + settingDefaultToString(setting)).withStyle(ChatFormatting.DARK_GRAY));
    return line;
  }

  @Override
  public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
    if (!args.hasAny()) {
      return Stream.empty();
    }
    String arg = args.getString();
    if (!args.hasAny()) {
      return new TabCompleteHelper().addSettings().sortAlphabetically().prepend("list", "modified").filterPrefix(arg).stream();
    }
    return Stream.empty();
  }

  @Override
  public String getShortDesc() { return "Read settings"; }

  @Override
  public List<String> getLongDesc() {
    return Arrays.asList("Read Baritone settings without mutating them.", "", "Usage:", "> get - Same as `get list`", "> get list [page] - View all settings",
      "> get modified [page] - View modified settings", "> get <setting> - View a setting");
  }
}
