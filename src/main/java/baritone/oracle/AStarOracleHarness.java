package baritone.oracle;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.SettingsUtil;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.calc.PathingIncumbentPolicy;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.ResourcePricing;
import baritone.pathing.movement.ResourcePricingState;
import baritone.playtest.PlaytestScenario;
import baritone.utils.pathing.Favoring;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import net.minecraft.SharedConstants;
import net.minecraft.WorldVersion;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

public final class AStarOracleHarness {
  private static final String ENABLED = "baritone.oracle.enabled";
  private static final String INBOX = "baritone.oracle.inbox";
  private static final String RESULTS = "baritone.oracle.results";
  private static final long DEFAULT_TIMEOUT_MS = 300_000L;
  private static final double ORDINARY_COST_HEURISTIC = 3.563D;
  private static final double HEURISTIC_EPS = 1.0e-9D;
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static AStarOracleHarness instance;

  private final Path inbox;
  private final Path results;

  private AStarOracleHarness(Path inbox, Path results) {
    this.inbox = inbox;
    this.results = results;
  }

  public static void tick(MinecraftServer server) {
    if (!enabled()) {
      return;
    }
    AStarOracleHarness harness = instance;
    if (harness == null) {
      harness = instance = new AStarOracleHarness(Path.of(System.getProperty(INBOX)).toAbsolutePath(), Path.of(System.getProperty(RESULTS)).toAbsolutePath());
    }
    harness.tickServer(server);
  }

  private static boolean enabled() {
    return Boolean.getBoolean(ENABLED) || present(System.getProperty(INBOX)) && present(System.getProperty(RESULTS));
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }

  private void tickServer(MinecraftServer server) {
    try {
      Files.createDirectories(inbox);
      Files.createDirectories(results);
      try (var stream = Files.list(inbox)) {
        Path next = stream.filter(path -> path.getFileName().toString().endsWith(".json")).min(Comparator.comparing(path -> path.getFileName().toString())).orElse(null);
        if (next != null) {
          process(server, next);
        }
      }
    } catch (IOException e) {
      throw new RuntimeException("Unable to tick A* oracle inbox " + inbox, e);
    }
  }

  private void process(MinecraftServer server, Path requestPath) throws IOException {
    String stem = stem(requestPath);
    JsonObject request;
    try (Reader reader = Files.newBufferedReader(requestPath, StandardCharsets.UTF_8)) {
      request = JsonParser.parseReader(reader).getAsJsonObject();
    }
    String runId = string(request, "runId", stem);
    Path result = results.resolve(runId + ".json");
    long started = System.nanoTime();
    JsonObject output;
    try {
      output = run(server, request, runId, started);
    } catch (Throwable t) {
      output = failure(request, runId, t, started);
    }
    writeJson(result, output);
    Files.deleteIfExists(requestPath);
  }

  private JsonObject run(MinecraftServer server, JsonObject request, String runId, long started) throws IOException {
    Path scenarioPath = Path.of(string(request, "scenarioPath", ""));
    if (!scenarioPath.isAbsolute()) {
      scenarioPath = Path.of("").toAbsolutePath().resolve(scenarioPath).normalize();
    }
    PlaytestScenario scenario = PlaytestScenario.load(scenarioPath, runId);
    JsonObject overrides = object(request, "settings");
    installSettings(scenario, overrides);
    ServerLevel level = level(server, scenario.dimension());
    OracleBlockStateInterface bsi = new OracleBlockStateInterface(level);
    List<ItemStack> hotbar = hotbar(scenario);
    ResourcePricing.Prices prices = resourcePrices(hotbar);
    CalculationContext context = new CalculationContext(level, bsi, hotbar, hasThrowaway(hotbar), prices, hasWaterBucket(hotbar), 0, results.resolve(runId + "-profiles"));
    BetterBlockPos start = new BetterBlockPos(Mth.floor(scenario.start().x()), Mth.floor(scenario.start().y()), Mth.floor(scenario.start().z()));
    Goal goal = scenario.baritoneGoal();
    double upperBoundTicks = doubleValue(overrides, "oracleUpperBoundTicks", Double.POSITIVE_INFINITY);
    AStarPathFinder finder = new AStarPathFinder(start, start.x, start.y, start.z, goal, new Favoring(null, context), context, PathingIncumbentPolicy.pedestrian(), upperBoundTicks);
    long timeout = longValue(request, "timeoutMS", DEFAULT_TIMEOUT_MS);
    long searchStarted = System.nanoTime();
    AStarPathFinder.OracleSearchResult result = finder.calculateForOracle(timeout);
    long searchNanos = System.nanoTime() - searchStarted;

    JsonObject json = new JsonObject();
    json.addProperty("schema", "baritone.oracle-a-star.v1");
    json.addProperty("status", result.reachedGoal() ? "SUCCESS_TO_GOAL" : result.positions().isEmpty() ? "SEARCH_EXHAUSTED" : "SUCCESS_SEGMENT");
    json.addProperty("runId", runId);
    json.addProperty("scenario", scenario.id());
    json.addProperty("scenarioPath", scenarioPath.toString());
    json.addProperty("dimension", scenario.dimension());
    json.addProperty("seed", scenario.seed());
    json.addProperty("serverSeed", level.getSeed());
    json.addProperty("timeoutMS", timeout);
    json.addProperty("elapsedNanos", System.nanoTime() - started);
    json.addProperty("searchNanos", searchNanos);
    JsonObject settings = settingsSummary();
    json.add("settings", settings);
    json.addProperty("settingsCrc32", crc32(settings.toString()));
    JsonObject bounds = new JsonObject();
    bounds.addProperty("timeoutMS", timeout);
    bounds.addProperty("pathingMaxNodes", BaritoneAPI.getSettings().pathingMaxNodes.value);
    if (Double.isFinite(upperBoundTicks)) {
      bounds.addProperty("oracleUpperBoundTicks", upperBoundTicks);
    }
    json.add("bounds", bounds);
    JsonObject world = new JsonObject();
    WorldVersion version = SharedConstants.getCurrentVersion();
    world.addProperty("minecraftVersion", version.name());
    world.addProperty("minecraftVersionId", version.id());
    world.addProperty("dataVersion", version.dataVersion().version());
    world.addProperty("dataSeries", version.dataVersion().series());
    world.addProperty("stableVersion", version.stable());
    world.addProperty("dimension", scenario.dimension());
    world.addProperty("scenarioSeed", scenario.seed());
    world.addProperty("serverSeed", level.getSeed());
    json.add("world", world);
    json.add("quality", quality(result));
    JsonObject objective = new JsonObject();
    objective.addProperty("nominalCostTicks", result.nominalCostTicks());
    objective.addProperty("pathLengthBlocks", pathLength(result.positions()));
    objective.addProperty("positionCount", result.positions().size());
    json.add("objective", objective);
    JsonObject search = new JsonObject();
    search.addProperty("stopReason", result.stopReason());
    search.addProperty("nodesExpanded", result.nodesExpanded());
    search.addProperty("movementsConsidered", result.movementsConsidered());
    search.addProperty("emptyChunkFetches", result.emptyChunkFetches());
    search.addProperty("nodeMapSize", result.nodeMapSize());
    search.addProperty("chunksRequestedByAStar", bsi.requestedChunks());
    search.addProperty("chunksSnapshotted", bsi.snapshottedChunks());
    json.add("search", search);
    JsonObject path = new JsonObject();
    path.add("start", blockPos(start));
    path.add("goal", goalObject(scenario.goal()));
    path.addProperty("crc32", pathCrc32(result.positions()));
    path.add("positions", positions(result.positions()));
    json.add("path", path);
    return json;
  }

  private static JsonObject quality(AStarPathFinder.OracleSearchResult result) {
    double heuristic = BaritoneAPI.getSettings().costHeuristic.value;
    boolean guided = Math.abs(heuristic - ORDINARY_COST_HEURISTIC) > HEURISTIC_EPS;
    JsonObject json = new JsonObject();
    json.addProperty("class", qualityClass(result, guided));
    json.addProperty("ordinaryCostHeuristic", ORDINARY_COST_HEURISTIC);
    json.addProperty("appliedCostHeuristic", heuristic);
    json.addProperty("guided", guided);
    json.addProperty("goalReached", result.reachedGoal());
    json.addProperty("stopReason", result.stopReason());
    json.addProperty("globalOptimalityProof", !guided && result.reachedGoal() ? "heuristic-contract-dependent" : "none");
    return json;
  }

  private static String qualityClass(AStarPathFinder.OracleSearchResult result, boolean guided) {
    if (result.reachedGoal()) {
      return guided ? "GUIDED_UPPER_BOUND" : "ORDINARY_ASTAR_GOAL";
    }
    if (result.positions().isEmpty()) {
      return "SEARCH_EXHAUSTED";
    }
    return guided ? "GUIDED_SEGMENT" : "ORDINARY_ASTAR_BOUNDED_SEGMENT";
  }

  private static JsonObject settingsSummary() {
    Settings settings = BaritoneAPI.getSettings();
    JsonObject json = new JsonObject();
    json.addProperty("pathingMaxNodes", settings.pathingMaxNodes.value);
    json.addProperty("costHeuristic", settings.costHeuristic.value);
    json.addProperty("pathingMaxChunkBorderFetch", settings.pathingMaxChunkBorderFetch.value);
    json.addProperty("cutoffAtLoadBoundary", settings.cutoffAtLoadBoundary.value);
    json.addProperty("pathThroughCachedOnly", settings.pathThroughCachedOnly.value);
    json.addProperty("elytraEnabled", settings.elytraEnabled.value);
    return json;
  }

  private static void installSettings(PlaytestScenario scenario, JsonObject overrides) {
    Settings settings = BaritoneAPI.getSettings();
    scenario.settings().forEach((name, value) -> applyIfPresent(settings, name, value));
    applyIfPresent(settings, "cutoffAtLoadBoundary", "false");
    applyIfPresent(settings, "pathThroughCachedOnly", "false");
    applyIfPresent(settings, "elytraEnabled", "false");
    if (present(System.getProperty("baritone.oracle.pathingMaxNodes"))) {
      applyIfPresent(settings, "pathingMaxNodes", System.getProperty("baritone.oracle.pathingMaxNodes"));
    }
    for (var entry : overrides.entrySet()) {
      applyIfPresent(settings, entry.getKey(), entry.getValue().getAsString());
    }
  }

  private static void applyIfPresent(Settings settings, String name, String value) {
    try {
      SettingsUtil.parseAndApply(settings, name.toLowerCase(Locale.US), value);
    } catch (IllegalStateException ignored) {
    }
  }

  private static List<ItemStack> hotbar(PlaytestScenario scenario) {
    ArrayList<ItemStack> stacks =
      new ArrayList<>(List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY));
    for (PlaytestScenario.LoadoutItem item : scenario.loadout()) {
      if (item.slot() < 0 || item.slot() >= 9) {
        continue;
      }
      Item resolved = BuiltInRegistries.ITEM.getValue(Identifier.parse(item.item()));
      stacks.set(item.slot(), new ItemStack(resolved == null ? Items.AIR : resolved, Math.max(0, item.count())));
    }
    return stacks;
  }

  private static ResourcePricing.Prices resourcePrices(List<ItemStack> hotbar) {
    int throwaways = hotbar.stream().filter(AStarOracleHarness::throwawayBlock).mapToInt(ItemStack::getCount).sum();
    double pick = hotbar.stream().filter(stack -> !stack.isEmpty() && stack.is(ItemTags.PICKAXES) && stack.getMaxDamage() > 1)
      .mapToDouble(stack -> (double) Math.max(0, stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage()).max().orElse(Double.POSITIVE_INFINITY);
    ResourcePricing.Parameters parameters =
      new ResourcePricing.Parameters(BaritoneAPI.getSettings().dynamicResourcePricing.value, false, BaritoneAPI.getSettings().dynamicResourcePricingLowBlocks.value,
        BaritoneAPI.getSettings().dynamicResourcePricingHighBlocks.value, BaritoneAPI.getSettings().dynamicResourcePricingScarcePlacementMultiplier.value,
        BaritoneAPI.getSettings().dynamicResourcePricingAbundantPlacementMultiplier.value, BaritoneAPI.getSettings().dynamicResourcePricingScarceBreakMultiplier.value,
        BaritoneAPI.getSettings().dynamicResourcePricingFragilePickPlacementMultiplier.value, BaritoneAPI.getSettings().dynamicResourcePricingFragilePickBreakMultiplier.value,
        BaritoneAPI.getSettings().dynamicResourcePricingFragilePickRemainingFraction.value, BaritoneAPI.getSettings().dynamicResourcePricingMinimumEpochTicks.value);
    return new ResourcePricingState().prices(new ResourcePricing.Snapshot(throwaways, 0, pick), parameters, BaritoneAPI.getSettings().blockPlacementPenalty.value,
      BaritoneAPI.getSettings().blockBreakAdditionalPenalty.value, Long.MAX_VALUE / 4, true);
  }

  private static boolean hasThrowaway(List<ItemStack> hotbar) {
    return hotbar.stream().anyMatch(AStarOracleHarness::throwawayBlock);
  }

  private static boolean throwawayBlock(ItemStack stack) {
    return stack.getItem() instanceof BlockItem && BaritoneAPI.getSettings().acceptableThrowawayItems.value.contains(stack.getItem());
  }

  private static boolean hasWaterBucket(List<ItemStack> hotbar) {
    return hotbar.stream().anyMatch(stack -> stack.is(Items.WATER_BUCKET));
  }

  private static ServerLevel level(MinecraftServer server, String dimension) {
    ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension));
    ServerLevel level = server.getLevel(key);
    if (level == null) {
      throw new IllegalArgumentException("No loaded dimension " + dimension);
    }
    return level;
  }

  private static double pathLength(List<BetterBlockPos> positions) {
    double length = 0D;
    for (int i = 1; i < positions.size(); i++) {
      BetterBlockPos a = positions.get(i - 1);
      BetterBlockPos b = positions.get(i);
      length += Math.sqrt(square(a.x - b.x) + square(a.y - b.y) + square(a.z - b.z));
    }
    return length;
  }

  private static long pathCrc32(List<BetterBlockPos> positions) {
    CRC32 crc = new CRC32();
    for (BetterBlockPos pos : positions) {
      update(crc, pos.x);
      update(crc, pos.y);
      update(crc, pos.z);
    }
    return crc.getValue();
  }

  private static long crc32(String value) {
    CRC32 crc = new CRC32();
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    crc.update(bytes, 0, bytes.length);
    return crc.getValue();
  }

  private static void update(CRC32 crc, int value) {
    crc.update(value);
    crc.update(value >>> 8);
    crc.update(value >>> 16);
    crc.update(value >>> 24);
  }

  private static int square(int value) {
    return value * value;
  }

  private static JsonObject failure(JsonObject request, String runId, Throwable t, long started) {
    JsonObject json = new JsonObject();
    json.addProperty("schema", "baritone.oracle-a-star.v1");
    json.addProperty("status", "EXCEPTION");
    json.addProperty("runId", runId);
    json.addProperty("scenarioPath", string(request, "scenarioPath", ""));
    json.addProperty("exception", t.getClass().getName());
    json.addProperty("message", t.getMessage());
    if (t.getCause() != null) {
      json.addProperty("cause", t.getCause().getClass().getName());
      json.addProperty("causeMessage", t.getCause().getMessage());
    }
    StringWriter stack = new StringWriter();
    t.printStackTrace(new PrintWriter(stack));
    json.addProperty("stack", stack.toString());
    json.addProperty("elapsedNanos", System.nanoTime() - started);
    return json;
  }

  private static JsonArray positions(List<BetterBlockPos> positions) {
    JsonArray array = new JsonArray();
    for (BetterBlockPos position : positions) {
      array.add(blockPos(position));
    }
    return array;
  }

  private static JsonArray blockPos(BetterBlockPos pos) {
    JsonArray array = new JsonArray();
    array.add(pos.x);
    array.add(pos.y);
    array.add(pos.z);
    return array;
  }

  private static JsonObject goalObject(PlaytestScenario.GoalSpec goal) {
    JsonObject json = new JsonObject();
    json.addProperty("type", goal.type());
    json.addProperty("x", goal.x());
    json.addProperty("y", goal.y());
    json.addProperty("z", goal.z());
    json.addProperty("radius", goal.radius());
    return json;
  }

  private static void writeJson(Path path, JsonObject json) throws IOException {
    Files.createDirectories(path.getParent());
    Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
    Files.writeString(tmp, GSON.toJson(json) + "\n", StandardCharsets.UTF_8);
    Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
  }

  private static String stem(Path path) {
    String name = path.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return dot < 0 ? name : name.substring(0, dot);
  }

  private static JsonObject object(JsonObject json, String key) {
    JsonElement element = json.get(key);
    return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
  }

  private static String string(JsonObject json, String key, String fallback) {
    JsonElement element = json.get(key);
    return element == null || element.isJsonNull() ? fallback : element.getAsString();
  }

  private static long longValue(JsonObject json, String key, long fallback) {
    JsonElement element = json.get(key);
    return element == null || element.isJsonNull() ? fallback : element.getAsLong();
  }

  private static double doubleValue(JsonObject json, String key, double fallback) {
    JsonElement element = json.get(key);
    return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
  }
}
