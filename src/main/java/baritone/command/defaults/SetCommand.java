package baritone.command.defaults;

import static baritone.api.command.IBaritoneChatControl.FORCE_COMMAND_PREFIX;
import static baritone.api.utils.SettingsUtil.*;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.RelativeFile;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.exception.CommandInvalidTypeException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.utils.SettingsUtil;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

public class SetCommand extends Command {
  public SetCommand(IBaritone baritone) {
    super(baritone, "set");
  }

  @Override
  public void execute(String label, IArgConsumer args) throws CommandException {
    if (!args.hasAny()) {
      logDirect("Usage: set <setting> <value>. Use get for read-only inspection.");
      return;
    }
    String arg = args.getString().toLowerCase(Locale.US);
    if (Arrays.asList("s", "save").contains(arg)) {
      SettingsUtil.save(Baritone.settings());
      logDirect("Settings saved");
      return;
    }
    if (Arrays.asList("load", "ld").contains(arg)) {
      String file = SETTINGS_DEFAULT_NAME;
      if (args.hasAny()) {
        file = args.getString();
      }
      // reset to defaults
      SettingsUtil.modifiedSettings(Baritone.settings()).forEach(Settings.Setting::reset);
      // then load from disk
      SettingsUtil.readAndApply(Baritone.settings(), file);
      logDirect("Settings reloaded from " + file);
      return;
    }
    args.requireMax(1);
    boolean resetting = arg.equalsIgnoreCase("reset");
    boolean toggling = arg.equalsIgnoreCase("toggle");
    boolean doingSomething = resetting || toggling;
    if (resetting) {
      if (!args.hasAny()) {
        logDirect("Please specify 'all' as an argument to reset to confirm you'd really like to do this");
        logDirect("ALL settings will be reset. Use 'get modified' to see what will be reset.");
        logDirect("Specify a setting name instead of 'all' to only reset one setting");
        return;
      } else if (args.peekString().equalsIgnoreCase("all")) {
        SettingsUtil.modifiedSettings(Baritone.settings()).forEach(Settings.Setting::reset);
        logDirect("All settings have been reset to their default values");
        SettingsUtil.save(Baritone.settings());
        return;
      }
    }
    if (toggling) {
      args.requireMin(1);
    }
    String settingName = doingSomething ? args.getString() : arg;
    Settings.Setting<?> setting = Baritone.settings().allSettings.stream().filter(s -> s.getName().equalsIgnoreCase(settingName)).findFirst().orElse(null);
    if (setting == null) {
      throw new CommandInvalidTypeException(args.consumed(), "a valid setting");
    }
    if (setting.isJavaOnly()) {
      // ideally it would act as if the setting didn't exist
      // but users will see it in Settings.java or its javadoc
      // so at some point we have to tell them or they will see it as a bug
      throw new CommandInvalidStateException(String.format("Setting %s can only be used via the api.", setting.getName()));
    }
    if (!doingSomething && !args.hasAny()) {
      logDirect("Use get " + setting.getName() + " to inspect this setting.");
      return;
    }
    String oldValue = settingValueToString(setting);
    if (resetting) {
      setting.reset();
    } else if (toggling) {
      if (setting.getValueClass() != Boolean.class) {
        throw new CommandInvalidTypeException(args.consumed(), "a toggleable setting", "some other setting");
      }
      Settings.Setting<Boolean> bool = boolSetting(setting);
      bool.value ^= true;
      logDirect(String.format("Toggled setting %s to %s", setting.getName(), Boolean.toString(bool.value)));
    } else {
      String newValue = args.getString();
      try {
        SettingsUtil.parseAndApply(Baritone.settings(), arg, newValue);
      } catch (Throwable t) {
        t.printStackTrace();
        throw new CommandInvalidTypeException(args.consumed(), "a valid value", t);
      }
    }
    if (!toggling) {
      logDirect(String.format("Successfully %s %s to %s", resetting ? "reset" : "set", setting.getName(), settingValueToString(setting)));
    }
    MutableComponent oldValueComponent = Component.literal(String.format("Old value: %s", oldValue));
    oldValueComponent.setStyle(oldValueComponent.getStyle().withColor(ChatFormatting.GRAY).withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to set the setting back to this value")))
      .withClickEvent(new ClickEvent.RunCommand(FORCE_COMMAND_PREFIX + String.format("set %s %s", setting.getName(), oldValue))));
    logDirect(oldValueComponent);
    if (setting.getName().equals("chatControl") && !(Boolean) setting.value && !Baritone.settings().prefixControl.value) {
      logDirect("Warning: Normal chat command entry is now disabled. Click the old value above to revert.", ChatFormatting.RED);
    } else if (setting.getName().equals("prefixControl") && !(Boolean) setting.value) {
      logDirect("Warning: Prefixed commands will no longer work. If you want to revert this change, use chat control (if enabled) or click the old value listed above.", ChatFormatting.RED);
    }
    SettingsUtil.save(Baritone.settings());
  }

  @SuppressWarnings("unchecked")
  private static Settings.Setting<Boolean> boolSetting(Settings.Setting<?> setting) {
    return (Settings.Setting<Boolean>) setting;
  }

  @Override
  public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
    if (args.hasAny()) {
      String arg = args.getString();
      if (args.hasExactlyOne() && !Arrays.asList("s", "save").contains(args.peekString().toLowerCase(Locale.US))) {
        if (arg.equalsIgnoreCase("reset")) {
          return new TabCompleteHelper().addModifiedSettings().prepend("all").filterPrefix(args.getString()).stream();
        } else if (arg.equalsIgnoreCase("toggle")) {
          return new TabCompleteHelper().addToggleableSettings().filterPrefix(args.getString()).stream();
        } else if (Arrays.asList("ld", "load").contains(arg.toLowerCase(Locale.US))) {
          // settings always use the directory of the main Minecraft instance
          return RelativeFile.tabComplete(args, Minecraft.getInstance().gameDirectory.toPath().resolve("baritone").toFile());
        }
        Settings.Setting<?> setting = Baritone.settings().byLowerName.get(arg.toLowerCase(Locale.US));
        if (setting != null) {
          if (setting.getType() == Boolean.class) {
            TabCompleteHelper helper = new TabCompleteHelper();
            if ((Boolean) setting.value) {
              helper.append("true", "false");
            } else {
              helper.append("false", "true");
            }
            return helper.filterPrefix(args.getString()).stream();
          } else {
            return Stream.of(settingValueToString(setting));
          }
        }
      } else if (!args.hasAny()) {
        return new TabCompleteHelper().addSettings().sortAlphabetically().prepend("reset", "toggle", "save", "load").filterPrefix(arg).stream();
      }
    }
    return Stream.empty();
  }

  @Override
  public String getShortDesc() { return "Change settings"; }

  @Override
  public List<String> getLongDesc() {
    return Arrays.asList("Using the set command, you can mutate Baritone settings. Use get for read-only inspection.", "", "Usage:", "> set <setting> <value> - Set the value of a setting",
      "> set reset all - Reset ALL SETTINGS to their defaults", "> set reset <setting> - Reset a setting to its default", "> set toggle <setting> - Toggle a boolean setting",
      "> set save - Save all settings (this is automatic tho)", "> set load - Load settings from settings.txt", "> set load [filename] - Load settings from another file in your minecraft/baritone");
  }
}
