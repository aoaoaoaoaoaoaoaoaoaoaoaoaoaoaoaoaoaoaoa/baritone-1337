package baritone.playtest;

import baritone.Baritone;
import baritone.api.event.events.PathEvent;
import baritone.api.utils.input.Input;
import baritone.pathing.transport.TransportMode;
import baritone.pathing.transport.TransportSnapshot;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

final class PlaytestRun {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private final PlaytestScenario scenario;
  private final Path resultDir;
  private final Path scenarioFile;
  private final EnumMap<TransportMode, Integer> actualTicks = new EnumMap<>(TransportMode.class);
  private final EnumMap<TransportMode, Integer> plannedTicks = new EnumMap<>(TransportMode.class);
  private final long startedNanos = System.nanoTime();
  private final int startTick;
  private int ticks;
  private int minAir = Integer.MAX_VALUE;
  private float initialHealth = Float.NaN;
  private float minHealth = Float.POSITIVE_INFINITY;
  private int pathEvents;
  private PathEvent lastPathEvent;
  private boolean calcFailed;
  private boolean sawBoat;
  private boolean sawWater;
  private boolean sawPathing;

  PlaytestRun(PlaytestScenario scenario, Path resultRoot, Path scenarioFile, int startTick) {
    this.scenario = scenario;
    this.resultDir = resultRoot.resolve(scenario.runId());
    this.scenarioFile = scenarioFile;
    this.startTick = startTick;
    try {
      Files.createDirectories(resultDir);
      if (scenarioFile != null && Files.isRegularFile(scenarioFile)) {
        Files.copy(scenarioFile, resultDir.resolve("scenario.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  PlaytestScenario scenario() {
    return scenario;
  }

  Path tracePath() {
    return resultDir.resolve("trace.jsonl");
  }

  int ticks() {
    return ticks;
  }

  void pathEvent(PathEvent event) {
    pathEvents++;
    lastPathEvent = event;
    calcFailed |= event == PathEvent.CALC_FAILED || event == PathEvent.NEXT_CALC_FAILED;
  }

  void sample(Baritone baritone, Minecraft minecraft) {
    ticks++;
    LocalPlayer player = minecraft.player;
    if (player == null) {
      return;
    }
    sawPathing |= baritone.getPathingBehavior().isPathing();
    if (Float.isNaN(initialHealth)) {
      initialHealth = player.getHealth();
    }
    minHealth = Math.min(minHealth, player.getHealth());
    minAir = Math.min(minAir, player.getAirSupply());
    sawWater |= player.isInWater() || player.isUnderWater() || player.isSwimming();
    sawBoat |= player.getVehicle() instanceof AbstractBoat;
    TransportSnapshot snapshot = baritone.getPathingBehavior().transportSnapshot();
    increment(actualTicks, snapshot.actual());
    TransportSnapshot.Plan plan = snapshot.current().current();
    if (plan != null && plan.mode() != null) {
      increment(plannedTicks, plan.mode());
    }
  }

  boolean calcFailed() {
    return calcFailed;
  }

  boolean sawWater() {
    return sawWater;
  }

  boolean sawPathing() {
    return sawPathing;
  }

  boolean sawBoat() {
    return sawBoat;
  }

  boolean tookDamage() {
    return !Float.isNaN(initialHealth) && minHealth + 1.0E-4F < initialHealth;
  }

  void write(TerminalReason reason, boolean success, Baritone baritone, Minecraft minecraft, Path telemetryPath, boolean staleInputsBeforeClear, boolean staleInputsAfterClear) {
    try {
      JsonObject json = new JsonObject();
      json.addProperty("schema", 1);
      json.addProperty("id", scenario.id());
      json.addProperty("runId", scenario.runId());
      json.addProperty("success", success);
      json.addProperty("terminalReason", reason.name());
      json.addProperty("startedTick", startTick);
      json.addProperty("elapsedTicks", ticks);
      json.addProperty("elapsedNanos", System.nanoTime() - startedNanos);
      json.addProperty("finishedAt", Instant.now().toString());
      json.addProperty("worldKey", scenario.worldKey());
      json.addProperty("seed", scenario.seed());
      json.addProperty("dimension", scenario.dimension());
      json.add("start", position(scenario.start().x(), scenario.start().y(), scenario.start().z()));
      json.add("goal", block(scenario.goal().x(), scenario.goal().y(), scenario.goal().z()));
      LocalPlayer player = minecraft.player;
      if (player != null) {
        json.add("finalPos", position(player.position()));
        json.add("finalFeet", block(player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ()));
        json.addProperty("distanceToGoal", distanceToGoal(player.position()));
        json.addProperty("health", player.getHealth());
        json.addProperty("air", player.getAirSupply());
        json.addProperty("onGround", player.onGround());
        json.addProperty("boatItems", boatItems(player));
        json.addProperty("vehicle", player.getVehicle() == null ? null : player.getVehicle().getType().toString());
      }
      json.addProperty("initialHealth", Float.isNaN(initialHealth) ? null : initialHealth);
      json.addProperty("minHealth", minHealth == Float.POSITIVE_INFINITY ? null : minHealth);
      json.addProperty("tookDamage", tookDamage());
      json.add("actualModeTicks", modeTicks(actualTicks));
      json.add("plannedModeTicks", modeTicks(plannedTicks));
      json.addProperty("pathEvents", pathEvents);
      json.addProperty("lastPathEvent", lastPathEvent == null ? null : lastPathEvent.name());
      json.addProperty("calcFailed", calcFailed);
      json.addProperty("sawPathing", sawPathing);
      json.addProperty("sawWater", sawWater);
      json.addProperty("sawBoat", sawBoat);
      if (minAir == Integer.MAX_VALUE) {
        json.add("minAir", null);
      } else {
        json.addProperty("minAir", minAir);
      }
      json.addProperty("telemetry", telemetryPath == null ? null : telemetryPath.toAbsolutePath().toString());
      json.addProperty("scenarioFile", scenarioFile == null ? null : scenarioFile.toAbsolutePath().toString());
      json.addProperty("staleInputsBeforeClear", staleInputsBeforeClear);
      json.addProperty("staleInputsAfterClear", staleInputsAfterClear);
      json.addProperty("pathingActiveAfterClear", baritone.getPathingBehavior().isPathing());
      Files.writeString(resultDir.resolve("summary.json"), GSON.toJson(json), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private double distanceToGoal(Vec3 position) {
    double dx = position.x - (scenario.goal().x() + 0.5D);
    double dy = position.y - scenario.goal().y();
    double dz = position.z - (scenario.goal().z() + 0.5D);
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  private static int boatItems(LocalPlayer player) {
    return player.getInventory().getNonEquipmentItems().stream().filter(stack -> stack.getItem() instanceof BoatItem).mapToInt(ItemStack::getCount).sum();
  }

  static boolean staleInputs(Baritone baritone) {
    for (Input input : Input.values()) {
      if (baritone.getInputOverrideHandler().isInputForcedDown(input)) {
        return true;
      }
    }
    return false;
  }

  private static void increment(EnumMap<TransportMode, Integer> map, TransportMode mode) {
    map.merge(mode, 1, Integer::sum);
  }

  private static JsonObject modeTicks(Map<TransportMode, Integer> map) {
    JsonObject json = new JsonObject();
    for (TransportMode mode : TransportMode.values()) {
      json.addProperty(mode.name(), map.getOrDefault(mode, 0));
    }
    return json;
  }

  private static JsonObject position(Vec3 pos) {
    return position(pos.x, pos.y, pos.z);
  }

  private static JsonObject position(double x, double y, double z) {
    JsonObject json = new JsonObject();
    json.addProperty("x", x);
    json.addProperty("y", y);
    json.addProperty("z", z);
    return json;
  }

  private static JsonObject block(int x, int y, int z) {
    JsonObject json = new JsonObject();
    json.addProperty("x", x);
    json.addProperty("y", y);
    json.addProperty("z", z);
    return json;
  }

  enum TerminalReason {
    SUCCESS, TIMEOUT, DEATH, DISCONNECT, CALC_FAILED, PATH_STOPPED, EXCEPTION
  }
}
