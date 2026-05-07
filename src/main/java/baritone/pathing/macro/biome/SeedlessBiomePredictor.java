package baritone.pathing.macro.biome;

import baritone.pathing.macro.core.MacroCellEvidence;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;

public final class SeedlessBiomePredictor {
  private static final double SHORT_SCALE_BLOCKS = 256D;
  private static final double MEDIUM_SCALE_BLOCKS = 768D;
  private static final double LONG_SCALE_BLOCKS = 2_048D;
  private static final double SHORT_CORRELATION_EXPONENT = 1D / (2D * SHORT_SCALE_BLOCKS * SHORT_SCALE_BLOCKS);
  private static final double MEDIUM_CORRELATION_EXPONENT = 1D / (2D * MEDIUM_SCALE_BLOCKS * MEDIUM_SCALE_BLOCKS);
  private static final double LONG_CORRELATION_EXPONENT = 1D / (2D * LONG_SCALE_BLOCKS * LONG_SCALE_BLOCKS);
  private static final ClimateCatalog CLIMATE = ClimateCatalog.load();

  private final int cellBlocks;
  private final List<Observation> observations;
  private final int meanSurfaceY;
  private final Long2ObjectOpenHashMap<BiomeMacroCell> cache = new Long2ObjectOpenHashMap<>();

  private SeedlessBiomePredictor(int cellBlocks, List<Observation> observations) {
    this.cellBlocks = cellBlocks;
    this.observations = List.copyOf(observations);
    int surfaceSum = 0;
    for (Observation observation : observations) {
      surfaceSum += observation.surfaceY;
    }
    this.meanSurfaceY = observations.isEmpty() ? 64 : Math.round(surfaceSum / (float) observations.size());
  }

  public static SeedlessBiomePredictor of(int cellBlocks, Collection<BiomeMacroCell> observations) {
    ArrayList<Observation> accepted = new ArrayList<>();
    for (BiomeMacroCell cell : observations) {
      CLIMATE.biomeCenter(cell.biome()).ifPresent(center -> accepted.add(new Observation(cell.x(), cell.z(), cell.surfaceY(), center.index, center.vector)));
    }
    return new SeedlessBiomePredictor(cellBlocks, accepted);
  }

  public Optional<BiomeMacroCell> predict(int cellX, int cellZ) {
    if (observations.isEmpty() || CLIMATE.empty()) {
      return Optional.empty();
    }
    long key = BiomeMacroCell.pack(cellX, cellZ);
    BiomeMacroCell cached = cache.get(key);
    if (cached != null) {
      return Optional.of(cached);
    }
    WeightedClimate climate = new WeightedClimate();
    double[] localSupport = new double[CLIMATE.biomeCount()];
    for (Observation observation : observations) {
      double dx = (cellX - observation.cellX) * (double) cellBlocks;
      double dz = (cellZ - observation.cellZ) * (double) cellBlocks;
      double weight = correlation(dx * dx + dz * dz);
      climate.add(observation.climate, weight);
      localSupport[observation.biomeIndex] += weight;
    }
    if (climate.weight == 0D) {
      return Optional.empty();
    }
    ClimateVector target = climate.mean();
    for (int i = 0; i < localSupport.length; i++) {
      localSupport[i] = localSupport[i] <= 0D ? 0D : Math.log1p(localSupport[i]);
    }
    String biome = CLIMATE.nearestBiome(target, localSupport);
    BiomeMacroCell predicted = new BiomeMacroCell(cellX, cellZ, biome, meanSurfaceY, MacroCellEvidence.PREDICTED);
    cache.put(key, predicted);
    return Optional.of(predicted);
  }

  public boolean useful() {
    return !observations.isEmpty() && !CLIMATE.empty();
  }

  private static double correlation(double blockDistanceSq) {
    return 0.60D * Math.exp(-blockDistanceSq * SHORT_CORRELATION_EXPONENT) + 0.30D * Math.exp(-blockDistanceSq * MEDIUM_CORRELATION_EXPONENT)
      + 0.10D * Math.exp(-blockDistanceSq * LONG_CORRELATION_EXPONENT);
  }

  private record Observation(int cellX, int cellZ, int surfaceY, int biomeIndex, ClimateVector climate) {
  }

  private static final class WeightedClimate {
    private double temperature;
    private double humidity;
    private double continentalness;
    private double erosion;
    private double depth;
    private double weirdness;
    private double weight;

    private void add(ClimateVector vector, double weight) {
      temperature += vector.temperature * weight;
      humidity += vector.humidity * weight;
      continentalness += vector.continentalness * weight;
      erosion += vector.erosion * weight;
      depth += vector.depth * weight;
      weirdness += vector.weirdness * weight;
      this.weight += weight;
    }

    private ClimateVector mean() {
      return new ClimateVector(temperature / weight, humidity / weight, continentalness / weight, erosion / weight, depth / weight, weirdness / weight);
    }
  }

  private record ClimateVector(double temperature, double humidity, double continentalness, double erosion, double depth, double weirdness) {
    private double distanceSq(ClimateVector other) {
      double dt = temperature - other.temperature;
      double dh = humidity - other.humidity;
      double dc = continentalness - other.continentalness;
      double de = erosion - other.erosion;
      double dd = depth - other.depth;
      double dw = weirdness - other.weirdness;
      return dt * dt + dh * dh + dc * dc + de * de + dd * dd + dw * dw;
    }
  }

  private static final class ClimateCatalog {
    private final Map<String, BiomeClimate> centerByBiome;
    private final ClimateSample[] samples;

    private ClimateCatalog(Map<String, List<ClimateVector>> vectorsByBiome) {
      HashMap<String, BiomeClimate> centers = new HashMap<>();
      ArrayList<ClimateSample> samples = new ArrayList<>();
      int index = 0;
      for (Map.Entry<String, List<ClimateVector>> entry : vectorsByBiome.entrySet()) {
        int biomeIndex = index++;
        WeightedClimate sum = new WeightedClimate();
        for (ClimateVector vector : entry.getValue()) {
          sum.add(vector, 1D);
          samples.add(new ClimateSample(biomeIndex, entry.getKey(), vector));
        }
        centers.put(entry.getKey(), new BiomeClimate(biomeIndex, sum.mean()));
      }
      this.centerByBiome = Map.copyOf(centers);
      this.samples = samples.toArray(ClimateSample[]::new);
    }

    private static ClimateCatalog load() {
      try {
        HashMap<String, List<ClimateVector>> vectors = new HashMap<>();
        var overworld = MultiNoiseBiomeSourceParameterList.knownPresets().get(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD);
        if (overworld == null) {
          return new ClimateCatalog(Map.of());
        }
        for (Pair<Climate.ParameterPoint, ResourceKey<Biome>> pair : overworld.values()) {
          String biome = pair.getSecond().identifier().toString();
          vectors.computeIfAbsent(biome, ignored -> new ArrayList<>()).add(vector(pair.getFirst()));
        }
        return new ClimateCatalog(vectors);
      } catch (RuntimeException e) {
        return new ClimateCatalog(Map.of());
      }
    }

    private Optional<BiomeClimate> biomeCenter(String biome) {
      return Optional.ofNullable(centerByBiome.get(biome));
    }

    private int biomeCount() {
      return centerByBiome.size();
    }

    private boolean empty() {
      return samples.length == 0;
    }

    private String nearestBiome(ClimateVector target, double[] localSupport) {
      String bestBiome = null;
      double best = Double.POSITIVE_INFINITY;
      for (ClimateSample sample : samples) {
        double score = sample.vector.distanceSq(target) - 0.01D * localSupport[sample.biomeIndex];
        if (score < best) {
          best = score;
          bestBiome = sample.biome;
        }
      }
      return bestBiome == null ? "minecraft:plains" : bestBiome;
    }

    private static ClimateVector vector(Climate.ParameterPoint point) {
      return new ClimateVector(center(point.temperature()), center(point.humidity()), center(point.continentalness()), center(point.erosion()), center(point.depth()), center(point.weirdness()));
    }

    private static double center(Climate.Parameter parameter) {
      return Climate.unquantizeCoord((parameter.min() + parameter.max()) >> 1);
    }

    private record BiomeClimate(int index, ClimateVector vector) {
    }

    private record ClimateSample(int biomeIndex, String biome, ClimateVector vector) {
    }
  }
}
