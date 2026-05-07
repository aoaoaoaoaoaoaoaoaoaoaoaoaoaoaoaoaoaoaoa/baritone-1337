package baritone.pathing.macro.biome;

import baritone.Baritone;
import baritone.pathing.macro.MacroFiles;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BiomeTraversalPriorTable {
  private static final Logger LOGGER = LoggerFactory.getLogger(BiomeTraversalPriorTable.class);
  private static final double EMPTY_FALLBACK_TICKS_PER_BLOCK = 4.5D;
  private static volatile Cache configured;

  private final Map<String, BiomeSurfaceCost> surface;
  private final BiomeSurfaceCost fallback;

  public BiomeTraversalPriorTable(Map<String, BiomeSurfaceCost> surface) {
    this.surface = Map.copyOf(surface);
    this.fallback = fallback(this.surface);
  }

  public static Optional<BiomeTraversalPriorTable> configured() {
    String raw = Baritone.settings().macroBiomePriorsFile.value.trim();
    if (raw.isEmpty()) {
      return Optional.empty();
    }
    Path path = MacroFiles.resolve(raw);
    try {
      long modified = Files.getLastModifiedTime(path).toMillis();
      Cache cache = configured;
      if (cache != null && cache.path.equals(path) && cache.modified == modified) {
        return Optional.of(cache.table);
      }
      BiomeTraversalPriorTable table = load(path);
      configured = new Cache(path, modified, table);
      return Optional.of(table);
    } catch (IOException | RuntimeException e) {
      LOGGER.warn("Unable to load macro biome priors from {}", path, e);
      return Optional.empty();
    }
  }

  public static BiomeTraversalPriorTable load(Path path) throws IOException {
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      return parse(JsonParser.parseReader(reader).getAsJsonObject());
    }
  }

  public static BiomeTraversalPriorTable parse(JsonObject root) {
    HashMap<String, BiomeSurfaceCost> costs = new HashMap<>();
    JsonObject biomes = object(root, "biomes");
    for (Map.Entry<String, JsonElement> entry : biomes.entrySet()) {
      JsonObject surface = object(entry.getValue().getAsJsonObject(), "surfacePedestrian");
      Double median = nullableDouble(surface, "medianTicksPerBlock");
      if (median == null || median <= 0D) {
        continue;
      }
      String biome = entry.getKey();
      int samples = integer(surface, "samples");
      int successes = integer(surface, "successfulSamples");
      double uncertainty = successes <= 0 ? 1D : 1D / Math.sqrt(successes);
      costs.put(biome,
        new BiomeSurfaceCost(biome, samples, successes, integer(surface, "successfulTicks"), decimal(surface, "totalBlocks"), median, decimal(surface, "p25TicksPerBlock", median),
          decimal(surface, "p75TicksPerBlock", median), decimal(surface, "failureRate"), decimal(surface, "damageRate"), decimal(surface, "jumpTicksPerBlock"), decimal(surface, "sprintTicksPerBlock"),
          decimal(surface, "moveForwardTicksPerBlock"), decimal(surface, "waterTicksPerBlock"), uncertainty));
    }
    return new BiomeTraversalPriorTable(costs);
  }

  public Optional<BiomeSurfaceCost> exact(String biome) {
    return Optional.ofNullable(surface.get(biome));
  }

  public BiomeSurfaceCost surface(String biome, double unknownPenalty) {
    BiomeSurfaceCost exact = surface.get(biome);
    return exact == null ? fallback.asUnknown(biome, unknownPenalty) : exact;
  }

  public BiomeSurfaceCost fallback() {
    return fallback;
  }

  public int size() {
    return surface.size();
  }

  private static BiomeSurfaceCost fallback(Map<String, BiomeSurfaceCost> surface) {
    if (surface.isEmpty()) {
      return new BiomeSurfaceCost("baritone:unknown", 0, 0, 0, 0D, EMPTY_FALLBACK_TICKS_PER_BLOCK, EMPTY_FALLBACK_TICKS_PER_BLOCK, EMPTY_FALLBACK_TICKS_PER_BLOCK, 0D, 0D, 0D, 0D, 0D, 0D, 0D);
    }
    ArrayList<BiomeSurfaceCost> rows = new ArrayList<>(surface.values());
    rows.sort(Comparator.comparingDouble(BiomeSurfaceCost::medianTicksPerBlock));
    BiomeSurfaceCost median = rows.get(rows.size() / 2);
    double totalBlocks = rows.stream().mapToDouble(BiomeSurfaceCost::totalBlocks).sum();
    int totalSamples = rows.stream().mapToInt(BiomeSurfaceCost::samples).sum();
    int totalSuccesses = rows.stream().mapToInt(BiomeSurfaceCost::successfulSamples).sum();
    int totalTicks = rows.stream().mapToInt(BiomeSurfaceCost::successfulTicks).sum();
    return new BiomeSurfaceCost("baritone:empirical_median", totalSamples, totalSuccesses, totalTicks, totalBlocks, median.medianTicksPerBlock(), median.p25TicksPerBlock(), median.p75TicksPerBlock(),
      average(rows, BiomeSurfaceCost::failureRate), average(rows, BiomeSurfaceCost::damageRate), average(rows, BiomeSurfaceCost::jumpTicksPerBlock),
      average(rows, BiomeSurfaceCost::sprintTicksPerBlock), average(rows, BiomeSurfaceCost::moveForwardTicksPerBlock), average(rows, BiomeSurfaceCost::waterTicksPerBlock),
      totalSuccesses <= 0 ? 1D : 1D / Math.sqrt(totalSuccesses));
  }

  private static double average(ArrayList<BiomeSurfaceCost> rows, java.util.function.ToDoubleFunction<BiomeSurfaceCost> f) {
    return rows.stream().mapToDouble(f).average().orElse(0D);
  }

  private static JsonObject object(JsonObject json, String name) {
    JsonElement value = json.get(name);
    return value == null || !value.isJsonObject() ? new JsonObject() : value.getAsJsonObject();
  }

  private static int integer(JsonObject json, String name) {
    JsonElement value = json.get(name);
    return value == null || value.isJsonNull() ? 0 : value.getAsInt();
  }

  private static double decimal(JsonObject json, String name) {
    return decimal(json, name, 0D);
  }

  private static double decimal(JsonObject json, String name, double fallback) {
    Double value = nullableDouble(json, name);
    return value == null ? fallback : value;
  }

  private static Double nullableDouble(JsonObject json, String name) {
    JsonElement value = json.get(name);
    return value == null || value.isJsonNull() ? null : value.getAsDouble();
  }

  private record Cache(Path path, long modified, BiomeTraversalPriorTable table) {
  }
}
