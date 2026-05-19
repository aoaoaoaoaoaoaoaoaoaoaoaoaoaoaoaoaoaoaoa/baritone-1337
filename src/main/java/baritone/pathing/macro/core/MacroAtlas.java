package baritone.pathing.macro.core;

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.macro.biome.BiomeFactAtlas;
import baritone.pathing.macro.biome.BiomeMacroCell;
import baritone.pathing.macro.biome.BiomeSurfaceCost;
import baritone.pathing.macro.biome.BiomeTraversalPriorTable;
import baritone.pathing.macro.biome.SeedlessBiomePredictor;
import baritone.pathing.macro.water.SurfaceWaterAtlas;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

public final class MacroAtlas {
  private final CalculationContext context;
  private final int cellBlocks;
  private final int scale;
  private final Optional<BiomeFactAtlas> biomeFacts;
  private final BiomeTraversalPriorTable biomePriors;
  private final boolean empiricalPriors;
  private final SeedlessBiomePredictor predictor;
  private final Optional<SurfaceWaterAtlas> water;
  private final double unknownPenalty;
  private final int fallbackY;
  private final Long2ObjectOpenHashMap<BiomeMacroCell> factCache = new Long2ObjectOpenHashMap<>();
  private final Long2ByteOpenHashMap surfaceWaterCostCache = new Long2ByteOpenHashMap();
  private final LongOpenHashSet factMisses = new LongOpenHashSet();

  private MacroAtlas(CalculationContext context, int cellBlocks, int scale, Optional<BiomeFactAtlas> biomeFacts, BiomeTraversalPriorTable biomePriors, boolean empiricalPriors,
    SeedlessBiomePredictor predictor, Optional<SurfaceWaterAtlas> water, double unknownPenalty, int fallbackY) {
    this.context = context;
    this.cellBlocks = cellBlocks;
    this.scale = scale;
    this.biomeFacts = biomeFacts;
    this.biomePriors = biomePriors;
    this.empiricalPriors = empiricalPriors;
    this.predictor = predictor;
    this.water = water;
    this.unknownPenalty = unknownPenalty;
    this.fallbackY = fallbackY;
    this.surfaceWaterCostCache.defaultReturnValue((byte) 0);
  }

  public static MacroAtlas build(CalculationContext context, BetterBlockPos start) {
    return build(context, start, true);
  }

  public static MacroAtlas build(CalculationContext context, BetterBlockPos start, boolean includeWater) {
    boolean empiricalSurfaceCosts = Baritone.settings().macroBiome.value;
    Optional<BiomeFactAtlas> facts = empiricalSurfaceCosts ? BiomeFactAtlas.configured() : Optional.empty();
    int cellBlocks = facts.map(BiomeFactAtlas::cellBlocks).orElse(Baritone.settings().macroBiomeCellBlocks.value);
    int scale = Math.max(0, Integer.numberOfTrailingZeros(Math.max(16, cellBlocks)) - 4);
    Optional<BiomeTraversalPriorTable> configuredPriors = empiricalSurfaceCosts ? BiomeTraversalPriorTable.configured() : Optional.empty();
    BiomeTraversalPriorTable priors = configuredPriors.orElseGet(() -> new BiomeTraversalPriorTable(java.util.Map.of()));
    Optional<SurfaceWaterAtlas> water = includeWater ? Optional.of(SurfaceWaterAtlas.build(context, start, Baritone.settings().macroWaterHorizonBlocks.value)) : Optional.empty();
    SeedlessBiomePredictor predictor = SeedlessBiomePredictor.of(cellBlocks, configuredPriors.isPresent() || facts.isPresent() ? predictorObservations(context, facts, cellBlocks, start) : List.of());
    return new MacroAtlas(context, cellBlocks, scale, facts, priors, configuredPriors.isPresent(), predictor, water, empiricalSurfaceCosts ? Baritone.settings().macroBiomeUnknownPenalty.value : 0D,
      start.y);
  }

  public int cellBlocks() {
    return cellBlocks;
  }

  CalculationContext context() {
    return context;
  }

  public int scale() {
    return scale;
  }

  public ResourceKey<Level> dimension() {
    return context.world.dimension();
  }

  public Optional<SurfaceWaterAtlas> water() {
    return water;
  }

  public boolean empiricalPriors() {
    return empiricalPriors;
  }

  public Optional<BiomeMacroCell> biomeFact(int cellX, int cellZ) {
    long key = BiomeMacroCell.pack(cellX, cellZ);
    BiomeMacroCell cached = factCache.get(key);
    if (cached != null) {
      return Optional.of(cached);
    }
    if (factMisses.contains(key)) {
      return Optional.empty();
    }
    Optional<BiomeMacroCell> live = liveBiomeFact(cellX, cellZ);
    if (live.isPresent()) {
      factCache.put(key, live.get());
      return live;
    }
    Optional<BiomeMacroCell> configured = biomeFacts.flatMap(atlas -> atlas.cell(cellX, cellZ));
    if (configured.isPresent()) {
      factCache.put(key, configured.get());
      return configured;
    }
    Optional<BiomeMacroCell> predicted = predictor.predict(cellX, cellZ);
    predicted.ifPresentOrElse(cell -> factCache.put(key, cell), () -> factMisses.add(key));
    return predicted;
  }

  public BiomeSurfaceCost surfaceCost(int cellX, int cellZ) {
    Optional<BiomeMacroCell> fact = biomeFact(cellX, cellZ);
    double penalty = empiricalPriors ? unknownPenalty : 0D;
    return fact.map(cell -> biomePriors.surface(cell.biome(), cell.evidence() == MacroCellEvidence.PREDICTED ? penalty * 0.5D : penalty))
      .orElseGet(() -> biomePriors.fallback().asUnknown("baritone:unknown", penalty));
  }

  public boolean factual(int cellX, int cellZ) {
    return biomeFact(cellX, cellZ).map(BiomeMacroCell::factual).orElse(false);
  }

  public MacroCellEvidence evidence(int cellX, int cellZ) {
    return biomeFact(cellX, cellZ).map(BiomeMacroCell::evidence).orElse(MacroCellEvidence.PRIOR);
  }

  public boolean surfaceWaterPrior(int cellX, int cellZ) {
    if (macroCellKnown(cellX, cellZ)) {
      return false;
    }
    if (configuredWaterFact(cellX, cellZ).isPresent()) {
      return true;
    }
    return biomeFact(cellX, cellZ).map(cell -> surfaceWaterBiome(cell.biome())).orElse(false);
  }

  public boolean surfaceWaterCostCell(int cellX, int cellZ) {
    return factualSurfaceWaterCostCell(cellX, cellZ) || configuredWaterFact(cellX, cellZ).isPresent() || biomeFact(cellX, cellZ).map(cell -> surfaceWaterBiome(cell.biome())).orElse(false);
  }

  public BetterBlockPos surfaceWaterCenter(int cellX, int cellZ) {
    return configuredWaterFact(cellX, cellZ).or(() -> biomeFact(cellX, cellZ).filter(cell -> surfaceWaterBiome(cell.biome()))).map(cell -> cell.center(cellBlocks))
      .orElseGet(() -> center(cellX, cellZ));
  }

  public boolean predictedPriors() {
    return predictor.useful();
  }

  public BetterBlockPos center(int cellX, int cellZ) {
    Optional<BiomeMacroCell> fact = biomeFact(cellX, cellZ);
    if (fact.isPresent()) {
      return fact.get().center(cellBlocks);
    }
    return new BetterBlockPos(cellX * cellBlocks + cellBlocks / 2, fallbackY, cellZ * cellBlocks + cellBlocks / 2);
  }

  private Optional<BiomeMacroCell> configuredWaterFact(int cellX, int cellZ) {
    return biomeFacts.flatMap(atlas -> atlas.cell(cellX, cellZ)).filter(cell -> surfaceWaterBiome(cell.biome()));
  }

  private boolean factualSurfaceWaterCostCell(int cellX, int cellZ) {
    if (!macroCellKnown(cellX, cellZ)) {
      return false;
    }
    long key = BiomeMacroCell.pack(cellX, cellZ);
    byte cached = surfaceWaterCostCache.get(key);
    if (cached != 0) {
      return cached == 1;
    }
    boolean water = computeFactualSurfaceWaterCostCell(cellX, cellZ);
    surfaceWaterCostCache.put(key, water ? (byte) 1 : (byte) 2);
    return water;
  }

  private boolean computeFactualSurfaceWaterCostCell(int cellX, int cellZ) {
    int baseX = cellX * cellBlocks;
    int baseZ = cellZ * cellBlocks;
    int centerX = baseX + cellBlocks / 2;
    int centerZ = baseZ + cellBlocks / 2;
    int q = Math.max(1, cellBlocks / 4);
    int hintY = center(cellX, cellZ).y;
    if (surfaceWaterColumn(centerX, centerZ, hintY)) {
      return true;
    }
    int hits = 0;
    hits += surfaceWaterColumn(centerX - q, centerZ, hintY) ? 1 : 0;
    hits += surfaceWaterColumn(centerX + q, centerZ, hintY) ? 1 : 0;
    hits += surfaceWaterColumn(centerX, centerZ - q, hintY) ? 1 : 0;
    hits += surfaceWaterColumn(centerX, centerZ + q, hintY) ? 1 : 0;
    hits += surfaceWaterColumn(centerX - q, centerZ - q, hintY) ? 1 : 0;
    hits += surfaceWaterColumn(centerX + q, centerZ + q, hintY) ? 1 : 0;
    return hits >= 3;
  }

  private boolean surfaceWaterColumn(int x, int z, int hintY) {
    if (!context.hasPathingData(x, z)) {
      return false;
    }
    int minY = Math.max(context.world.getMinY(), hintY - 4);
    int maxY = Math.min(context.world.getMaxY() - 2, hintY + 1);
    for (int y = maxY; y >= minY; y--) {
      if (MovementHelper.isWater(context.get(x, y, z)) && !MovementHelper.isWater(context.get(x, y + 1, z))) {
        return true;
      }
    }
    return false;
  }

  private boolean macroCellKnown(int cellX, int cellZ) {
    int minX = cellX * cellBlocks;
    int minZ = cellZ * cellBlocks;
    int maxX = minX + cellBlocks - 1;
    int maxZ = minZ + cellBlocks - 1;
    int midX = minX + cellBlocks / 2;
    int midZ = minZ + cellBlocks / 2;
    return context.hasPathingData(midX, midZ) || context.hasPathingData(minX, minZ) || context.hasPathingData(maxX, minZ) || context.hasPathingData(minX, maxZ) || context.hasPathingData(maxX, maxZ);
  }

  private Optional<BiomeMacroCell> liveBiomeFact(int cellX, int cellZ) {
    int x = cellX * cellBlocks + cellBlocks / 2;
    int z = cellZ * cellBlocks + cellBlocks / 2;
    if (!context.hasLiveChunk(x, z)) {
      return Optional.empty();
    }
    int surfaceY = Math.max(context.world.getMinY(), context.world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
    int biomeY = Math.max(context.world.getMinY(), Math.min(context.world.getMaxY() - 1, surfaceY <= context.world.getMinY() ? fallbackY : surfaceY));
    Holder<Biome> holder = context.world.getBiome(new BlockPos(x, biomeY, z));
    String biome = holder.unwrapKey().map(key -> key.identifier().toString()).orElseGet(() -> context.world.registryAccess().lookupOrThrow(Registries.BIOME).getKey(holder.value()).toString());
    return Optional.of(new BiomeMacroCell(cellX, cellZ, biome, surfaceY, MacroCellEvidence.LIVE));
  }

  private static boolean surfaceWaterBiome(String biome) {
    String id = biome.startsWith("minecraft:") ? biome.substring("minecraft:".length()) : biome;
    return id.equals("river") || id.equals("frozen_river") || id.endsWith("_ocean") || id.equals("ocean");
  }

  private static java.util.Collection<BiomeMacroCell> predictorObservations(CalculationContext context, Optional<BiomeFactAtlas> facts, int cellBlocks, BetterBlockPos start) {
    ArrayList<BiomeMacroCell> observations = new ArrayList<>();
    int sx = Math.floorDiv(start.x, cellBlocks);
    int sz = Math.floorDiv(start.z, cellBlocks);
    int factRadius = Math.max(2, Baritone.settings().macroBiomeHorizonBlocks.value * 2 / cellBlocks);
    facts.ifPresent(atlas -> {
      for (BiomeMacroCell cell : atlas.cells()) {
        if (Math.max(Math.abs(cell.x() - sx), Math.abs(cell.z() - sz)) <= factRadius) {
          observations.add(cell);
        }
      }
    });
    int radius = Math.max(2, Math.min(Baritone.settings().macroBiomeHorizonBlocks.value, 1024) / cellBlocks);
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dz = -radius; dz <= radius; dz++) {
        BiomeFactAtlas.live(context, sx + dx, sz + dz, cellBlocks, start.y).ifPresent(observations::add);
      }
    }
    if (observations.size() <= 2_048) {
      return observations;
    }
    observations.sort(java.util.Comparator.comparingInt(cell -> {
      int dx = cell.x() - sx;
      int dz = cell.z() - sz;
      return dx * dx + dz * dz;
    }));
    return observations.subList(0, 2_048);
  }
}
