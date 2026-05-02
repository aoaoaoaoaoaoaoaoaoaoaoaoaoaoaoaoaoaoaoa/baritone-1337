package baritone.utils.schematic.format;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BlockStateCodec {

  private static final Pattern SPONGE = Pattern.compile("(?<id>[a-z0-9_.-]+(?::[a-z0-9_./-]+)?)(?:\\[(?<properties>[^\\]]+)])?");

  private BlockStateCodec() {
  }

  public static BlockState parseSponge(String serialized) {
    Matcher matcher = SPONGE.matcher(serialized);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Malformed Sponge block state: " + serialized);
    }
    return blockState(matcher.group("id"), spongeProperties(matcher.group("properties")));
  }

  public static BlockState parseLitematica(CompoundTag paletteEntry) {
    String name = paletteEntry.getString("Name").orElseThrow(() -> new IllegalArgumentException("Litematica palette entry missing block name"));
    CompoundTag properties = paletteEntry.getCompound("Properties").orElse(new CompoundTag());
    return blockState(name, nbtProperties(properties));
  }

  public static BlockState defaultState(String rawIdentifier) {
    return block(rawIdentifier).defaultBlockState();
  }

  private static BlockState blockState(String rawIdentifier, Map<String, String> properties) {
    Block block = block(rawIdentifier);
    BlockState state = block.defaultBlockState();
    for (Map.Entry<String, String> property : properties.entrySet()) {
      state = setPropertyValue(state, property(block, property.getKey()), property.getValue());
    }
    return state;
  }

  private static Block block(String rawIdentifier) {
    Identifier identifier = Identifier.parse(rawIdentifier);
    return BuiltInRegistries.BLOCK.getOptional(identifier).orElseThrow(() -> new IllegalArgumentException("Unknown block: " + identifier));
  }

  private static Property<?> property(Block block, String key) {
    Property<?> property = block.getStateDefinition().getProperty(key);
    if (property == null) {
      throw new IllegalArgumentException("Unknown property " + key + " for block " + BuiltInRegistries.BLOCK.getKey(block));
    }
    return property;
  }

  private static Map<String, String> spongeProperties(String serialized) {
    Map<String, String> properties = new TreeMap<>();
    if (serialized == null) {
      return properties;
    }
    for (String entry : serialized.split(",", -1)) {
      int separator = entry.indexOf('=');
      if (separator <= 0 || separator == entry.length() - 1) {
        throw new IllegalArgumentException("Malformed block-state property: " + entry);
      }
      String key = entry.substring(0, separator);
      String value = entry.substring(separator + 1);
      if (properties.put(key, value) != null) {
        throw new IllegalArgumentException("Duplicate block-state property: " + key);
      }
    }
    return properties;
  }

  private static Map<String, String> nbtProperties(CompoundTag tag) {
    Map<String, String> properties = new TreeMap<>();
    for (String key : tag.keySet()) {
      properties.put(key, tag.getString(key).orElseThrow(() -> new IllegalArgumentException("NBT block-state property is not a string: " + key)));
    }
    return properties;
  }

  private static <T extends Comparable<T>> BlockState setPropertyValue(BlockState state, Property<T> property, String value) {
    Optional<T> parsed = property.getValue(value);
    if (parsed.isEmpty()) {
      throw new IllegalArgumentException("Invalid value " + value + " for property " + property.getName());
    }
    return state.setValue(property, parsed.get());
  }
}
