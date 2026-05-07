package baritone.pathing.macro.biome;

import baritone.Baritone;
import baritone.pathing.macro.MacroFiles;
import baritone.pathing.macro.core.MacroCellEvidence;
import baritone.pathing.movement.CalculationContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Collection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BiomeFactAtlas {
  private static final Logger LOGGER = LoggerFactory.getLogger(BiomeFactAtlas.class);
  private static volatile Cache configured;

  private final int cellBlocks;
  private final Long2ObjectOpenHashMap<BiomeMacroCell> cells;

  private BiomeFactAtlas(int cellBlocks, Long2ObjectOpenHashMap<BiomeMacroCell> cells) {
    this.cellBlocks = cellBlocks;
    this.cells = cells;
  }

  public static Optional<BiomeFactAtlas> configured() {
    String raw = Baritone.settings().macroBiomeFactsFile.value.trim();
    if (raw.isEmpty()) {
      return Optional.empty();
    }
    Path path = MacroFiles.resolve(raw);
    try {
      long modified = Files.getLastModifiedTime(path).toMillis();
      Cache cache = configured;
      if (cache != null && cache.path.equals(path) && cache.modified == modified) {
        return Optional.of(cache.atlas);
      }
      BiomeFactAtlas atlas = load(path);
      configured = new Cache(path, modified, atlas);
      return Optional.of(atlas);
    } catch (IOException | RuntimeException e) {
      LOGGER.warn("Unable to load macro biome facts from {}", path, e);
      return Optional.empty();
    }
  }

  public static BiomeFactAtlas load(Path path) throws IOException {
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      return parse(JsonParser.parseReader(reader).getAsJsonObject());
    }
  }

  public static BiomeFactAtlas parse(JsonObject root) {
    int cellBlocks = integer(root, "cellBlocks", 16);
    MacroCellEvidence defaultEvidence = evidence(root, MacroCellEvidence.CACHED);
    Long2ObjectOpenHashMap<BiomeMacroCell> cells = new Long2ObjectOpenHashMap<>();
    cells.defaultReturnValue(null);
    JsonArray compact = root.getAsJsonArray("cells");
    if (compact != null) {
      for (JsonElement element : compact) {
        JsonArray row = element.getAsJsonArray();
        int x = row.get(0).getAsInt();
        int z = row.get(1).getAsInt();
        String biome = row.get(2).getAsString();
        int surfaceY = row.size() > 3 && !row.get(3).isJsonNull() ? row.get(3).getAsInt() : 64;
        MacroCellEvidence cellEvidence = row.size() > 4 && !row.get(4).isJsonNull() ? evidence(row.get(4).getAsString(), defaultEvidence) : defaultEvidence;
        cells.put(BiomeMacroCell.pack(x, z), new BiomeMacroCell(x, z, biome, surfaceY, cellEvidence));
      }
    }
    JsonArray verbose = root.getAsJsonArray("chunks");
    if (verbose != null) {
      for (JsonElement element : verbose) {
        JsonObject row = element.getAsJsonObject();
        int x = integer(row, "cx", integer(row, "x", 0));
        int z = integer(row, "cz", integer(row, "z", 0));
        String biome = string(row, "biome", "minecraft:plains");
        int surfaceY = integer(row, "surfaceY", 64);
        cells.put(BiomeMacroCell.pack(x, z), new BiomeMacroCell(x, z, biome, surfaceY, evidence(row, defaultEvidence)));
      }
    }
    return new BiomeFactAtlas(cellBlocks, cells);
  }

  public int cellBlocks() {
    return cellBlocks;
  }

  public Optional<BiomeMacroCell> cell(int x, int z) {
    return Optional.ofNullable(cells.get(BiomeMacroCell.pack(x, z)));
  }

  public Collection<BiomeMacroCell> cells() {
    return java.util.List.copyOf(cells.values());
  }

  public int size() {
    return cells.size();
  }

  public static Optional<BiomeMacroCell> live(CalculationContext context, int cellX, int cellZ, int cellBlocks, int fallbackY) {
    int x = cellX * cellBlocks + cellBlocks / 2;
    int z = cellZ * cellBlocks + cellBlocks / 2;
    if (!context.isLoaded(x, z)) {
      return Optional.empty();
    }
    int surfaceY = Math.max(context.world.getMinY(), context.world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
    int biomeY = Math.max(context.world.getMinY(), Math.min(context.world.getMaxY() - 1, surfaceY <= context.world.getMinY() ? fallbackY : surfaceY));
    Holder<Biome> holder = context.world.getBiome(new BlockPos(x, biomeY, z));
    String biome = holder.unwrapKey().map(key -> key.identifier().toString()).orElseGet(() -> context.world.registryAccess().lookupOrThrow(Registries.BIOME).getKey(holder.value()).toString());
    return Optional.of(new BiomeMacroCell(cellX, cellZ, biome, surfaceY, MacroCellEvidence.LIVE));
  }

  private static int integer(JsonObject json, String name, int fallback) {
    JsonElement value = json.get(name);
    return value == null || value.isJsonNull() ? fallback : value.getAsInt();
  }

  private static String string(JsonObject json, String name, String fallback) {
    JsonElement value = json.get(name);
    return value == null || value.isJsonNull() ? fallback : value.getAsString();
  }

  private static MacroCellEvidence evidence(JsonObject json, MacroCellEvidence fallback) {
    return evidence(string(json, "evidence", string(json, "source", fallback.name())), fallback);
  }

  private static MacroCellEvidence evidence(String raw, MacroCellEvidence fallback) {
    return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "live", "loaded" -> MacroCellEvidence.LIVE;
      case "cached", "cache", "observed", "observed_cache" -> MacroCellEvidence.CACHED;
      case "predicted", "generated", "generator", "seed", "seeded" -> MacroCellEvidence.PREDICTED;
      case "prior", "unknown" -> MacroCellEvidence.PRIOR;
      default -> fallback;
    };
  }

  private record Cache(Path path, long modified, BiomeFactAtlas atlas) {
  }
}
