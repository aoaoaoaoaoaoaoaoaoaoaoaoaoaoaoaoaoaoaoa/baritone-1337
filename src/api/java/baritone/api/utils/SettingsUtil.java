package baritone.api.utils;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import java.awt.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

public class SettingsUtil {
  public static final String SETTINGS_DEFAULT_NAME = "settings.txt";
  private static final Pattern SETTING_PATTERN = Pattern.compile("^(?<setting>[^ ]+) +(?<value>.+)"); // key and value split by the first space

  private static boolean isComment(String line) {
    return line.startsWith("#") || line.startsWith("//");
  }

  private static void forEachLine(Path file, Consumer<String> consumer) throws IOException {
    try (BufferedReader scan = Files.newBufferedReader(file)) {
      String line;
      while ((line = scan.readLine()) != null) {
        if (line.isEmpty() || isComment(line)) {
          continue;
        }
        consumer.accept(line);
      }
    }
  }

  public static void readAndApply(Settings settings, String settingsName) {
    try {
      forEachLine(settingsByName(settingsName), line -> {
        Matcher matcher = SETTING_PATTERN.matcher(line);
        if (!matcher.matches()) {
          Helper.HELPER.logDirect("Invalid syntax in setting file: " + line);
          return;
        }

        String settingName = matcher.group("setting").toLowerCase(Locale.US);
        String settingValue = matcher.group("value");
        try {
          parseAndApply(settings, settingName, settingValue);
        } catch (Exception ex) {
          Helper.HELPER.logDirect("Unable to parse line " + line);
          ex.printStackTrace();
        }
      });
    } catch (NoSuchFileException ignored) {
      Helper.HELPER.logDirect("Baritone settings file not found, resetting.");
    } catch (Exception ex) {
      Helper.HELPER.logDirect("Exception while reading Baritone settings, some settings may be reset to default values!");
      ex.printStackTrace();
    }
  }

  public static synchronized void save(Settings settings) {
    try (BufferedWriter out = Files.newBufferedWriter(settingsByName(SETTINGS_DEFAULT_NAME))) {
      for (Settings.Setting<?> setting : modifiedSettings(settings)) {
        out.write(settingToString(setting) + "\n");
      }
    } catch (Exception ex) {
      Helper.HELPER.logDirect("Exception thrown while saving Baritone settings!");
      ex.printStackTrace();
    }
  }

  private static Path settingsByName(String name) {
    return Minecraft.getInstance().gameDirectory.toPath().resolve("baritone").resolve(name);
  }

  public static List<Settings.Setting<?>> modifiedSettings(Settings settings) {
    List<Settings.Setting<?>> modified = new ArrayList<>();
    for (Settings.Setting<?> setting : settings.allSettings) {
      if (setting.value == null) {
        System.out.println("NULL SETTING?" + setting.getName());
        continue;
      }
      if (setting.isJavaOnly()) {
        continue; // NO
      }
      if (setting.value == setting.defaultValue) {
        continue;
      }
      modified.add(setting);
    }
    return modified;
  }

  /**
   * Gets the type of a setting and returns it as a string, with package names stripped.
   * <p>
   * For example, if the setting type is {@code java.util.List<java.lang.String>}, this function returns
   * {@code List<String>}.
   *
   * @param setting The setting
   * @return The type
   */
  public static String settingTypeToString(Settings.Setting<?> setting) {
    return setting.getType().getTypeName().replaceAll("(?:\\w+\\.)+(\\w+)", "$1");
  }

  public static <T> String settingValueToString(Settings.Setting<T> setting, T value) throws IllegalArgumentException {
    Parser io = Parser.getParser(setting.getType());

    if (io == null) {
      throw new IllegalStateException("Missing " + setting.getValueClass() + " " + setting.getName());
    }

    return io.toString(setting.getType(), value);
  }

  public static String settingValueToString(Settings.Setting<?> setting) throws IllegalArgumentException {
    return settingValueToString0(setting);
  }

  private static <T> String settingValueToString0(Settings.Setting<T> setting) throws IllegalArgumentException {
    return settingValueToString(setting, setting.value);
  }

  public static String settingDefaultToString(Settings.Setting<?> setting) throws IllegalArgumentException {
    return settingDefaultToString0(setting);
  }

  private static <T> String settingDefaultToString0(Settings.Setting<T> setting) throws IllegalArgumentException {
    return settingValueToString(setting, setting.defaultValue);
  }

  public static String maybeCensor(int coord) {
    if (BaritoneAPI.getSettings().censorCoordinates.value) {
      return "<censored>";
    }

    return Integer.toString(coord);
  }

  public static String settingToString(Settings.Setting<?> setting) throws IllegalStateException {
    if (setting.isJavaOnly()) {
      return setting.getName();
    }

    return setting.getName() + " " + settingValueToString(setting);
  }

  /**
   * Deprecated. Use {@link Settings.Setting#isJavaOnly()} instead.
   *
   * @param setting The Setting
   * @return true if the setting can not be set or read by the user
   */
  @Deprecated
  public static boolean javaOnlySetting(Settings.Setting<?> setting) {
    return setting.isJavaOnly();
  }

  public static void parseAndApply(Settings settings, String settingName, String settingValue) throws IllegalStateException, NumberFormatException {
    Settings.Setting<?> setting = settings.byLowerName.get(settingName);
    if (setting == null) {
      throw new IllegalStateException("No setting by that name");
    }
    Class<?> intendedType = setting.getValueClass();
    SettingParser ioMethod = Parser.getParser(setting.getType());
    if (ioMethod == null) {
      throw new IllegalStateException("Missing " + intendedType + " " + setting.getName());
    }
    Object parsed = ioMethod.parse(setting.getType(), settingValue);
    if (!intendedType.isInstance(parsed)) {
      throw new IllegalStateException(ioMethod + " parser returned incorrect type, expected " + intendedType + " got " + parsed + " which is " + parsed.getClass());
    }
    applyParsed(setting, parsed);
  }

  private static <T> void applyParsed(Settings.Setting<T> setting, Object parsed) {
    setting.value = setting.getValueClass().cast(parsed);
  }

  private interface SettingParser {
    Object parse(Type type, String raw);

    String toString(Type type, Object value);

    boolean accepts(Type type);
  }

  private enum Parser implements SettingParser {
    DOUBLE(Double.class, Double::parseDouble), BOOLEAN(Boolean.class, Boolean::parseBoolean), INTEGER(Integer.class, Integer::parseInt), FLOAT(Float.class, Float::parseFloat), LONG(Long.class,
      Long::parseLong), STRING(String.class, String::new), GEOFENCE_BOX(GeofenceBox.class, GeofenceBox::parse,
        GeofenceBox::serialized), MIRROR(Mirror.class, Mirror::valueOf, Mirror::name), ROTATION(Rotation.class, Rotation::valueOf, Rotation::name), COLOR(Color.class, str -> {
          String[] parts = str.split(",");
          return new Color(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }, color -> color.getRed() + "," + color.getGreen() + "," + color.getBlue()), VEC3I(Vec3i.class, str -> {
          String[] parts = str.split(",");
          return new Vec3i(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }, vec -> vec.getX() + "," + vec.getY() + "," + vec.getZ()), BLOCK(Block.class, str -> BlockUtils.stringToBlockRequired(str.trim()), BlockUtils::blockToString), ITEM(Item.class,
          str -> BuiltInRegistries.ITEM.get(Identifier.parse(str.trim())).map(Holder.Reference::value).orElse(null), item -> BuiltInRegistries.ITEM.getKey(item).toString()), ENUM() {
            @Override
            @SuppressWarnings({"rawtypes", "unchecked"})
            public Object parse(Type type, String raw) {
              return Enum.valueOf((Class<? extends Enum>) type, raw.trim().toUpperCase(Locale.US));
            }

            @Override
            public String toString(Type type, Object value) {
              return ((Enum<?>) value).name();
            }

            @Override
            public boolean accepts(Type type) {
              return type instanceof Class<?> c && c.isEnum();
            }
          },
    LIST() {
      @Override
      public Object parse(Type type, String raw) {
        if (raw.isBlank()) {
          return new ArrayList<>();
        }
        Type elementType = ((ParameterizedType) type).getActualTypeArguments()[0];
        Parser parser = Parser.getParser(elementType);
        if (parser == null) {
          throw new IllegalStateException("Missing list element parser for " + elementType);
        }
        return Stream.of(raw.split(",")).map(s -> parser.parse(elementType, s)).collect(Collectors.toList());
      }

      @Override
      public String toString(Type type, Object value) {
        Type elementType = ((ParameterizedType) type).getActualTypeArguments()[0];
        Parser parser = Parser.getParser(elementType);
        if (parser == null) {
          throw new IllegalStateException("Missing list element serializer for " + elementType);
        }

        return ((List<?>) value).stream().map(o -> parser.toString(elementType, o)).collect(Collectors.joining(","));
      }

      @Override
      public boolean accepts(Type type) {
        return List.class.isAssignableFrom(TypeUtils.resolveBaseClass(type));
      }
    },
    MAPPING() {
      @Override
      public Object parse(Type type, String raw) {
        Type keyType = ((ParameterizedType) type).getActualTypeArguments()[0];
        Type valueType = ((ParameterizedType) type).getActualTypeArguments()[1];
        Parser keyParser = Parser.getParser(keyType);
        Parser valueParser = Parser.getParser(valueType);
        if (keyParser == null || valueParser == null) {
          throw new IllegalStateException("Missing map parser for " + type);
        }

        return Stream.of(raw.split(",(?=[^,]*->)")).map(s -> s.split("->")).collect(Collectors.toMap(s -> keyParser.parse(keyType, s[0]), s -> valueParser.parse(valueType, s[1])));
      }

      @Override
      public String toString(Type type, Object value) {
        Type keyType = ((ParameterizedType) type).getActualTypeArguments()[0];
        Type valueType = ((ParameterizedType) type).getActualTypeArguments()[1];
        Parser keyParser = Parser.getParser(keyType);
        Parser valueParser = Parser.getParser(valueType);
        if (keyParser == null || valueParser == null) {
          throw new IllegalStateException("Missing map serializer for " + type);
        }

        return ((Map<?, ?>) value).entrySet().stream().map(o -> keyParser.toString(keyType, o.getKey()) + "->" + valueParser.toString(valueType, o.getValue())).collect(Collectors.joining(","));
      }

      @Override
      public boolean accepts(Type type) {
        return Map.class.isAssignableFrom(TypeUtils.resolveBaseClass(type));
      }
    };

    private final Class<?> type;
    private final Function<String, Object> parser;
    private final Function<Object, String> serializer;

    Parser() {
      this.type = null;
      this.parser = null;
      this.serializer = null;
    }

    <T> Parser(Class<T> type, Function<String, T> parser) {
      this(type, parser, Object::toString);
    }

    <T> Parser(Class<T> type, Function<String, T> parser, Function<T, String> serializer) {
      this.type = type;
      this.parser = parser::apply;
      this.serializer = value -> serializer.apply(type.cast(value));
    }

    @Override
    public Object parse(Type type, String raw) {
      Object parsed = this.parser.apply(raw);
      Objects.requireNonNull(parsed);
      return parsed;
    }

    @Override
    public String toString(Type type, Object value) {
      return this.serializer.apply(value);
    }

    @Override
    public boolean accepts(Type type) {
      return type instanceof Class<?> c && this.type.isAssignableFrom(c);
    }

    public static Parser getParser(Type type) {
      return Stream.of(values()).filter(parser -> parser.accepts(type)).findFirst().orElse(null);
    }
  }
}
