package baritone.playtest.physics;

import baritone.utils.schematic.format.BlockStateCodec;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class HorsePhysicsHarness {
  private static final String ENABLED = "baritone.physics.enabled";
  private static final String INBOX = "baritone.physics.inbox";
  private static final String RESULTS = "baritone.physics.results";
  private static final String DEFAULT_DIMENSION = "minecraft:overworld";
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static HorsePhysicsHarness instance;

  private final Path inbox;
  private final Path results;

  private HorsePhysicsHarness(Path inbox, Path results) {
    this.inbox = inbox;
    this.results = results;
  }

  public static void tick(MinecraftServer server) {
    if (!enabled()) {
      return;
    }
    HorsePhysicsHarness harness = instance;
    if (harness == null) {
      harness = instance = new HorsePhysicsHarness(Path.of(System.getProperty(INBOX)).toAbsolutePath(), Path.of(System.getProperty(RESULTS)).toAbsolutePath());
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
      throw new RuntimeException("Unable to tick horse physics harness inbox " + inbox, e);
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
    JsonObject output;
    long started = System.nanoTime();
    try {
      output = run(server, request, runId, requestPath, started);
    } catch (Throwable t) {
      output = new JsonObject();
      output.addProperty("schema", 1);
      output.addProperty("id", string(request, "id", runId));
      output.addProperty("runId", runId);
      output.addProperty("success", false);
      output.addProperty("terminalReason", "EXCEPTION");
      output.addProperty("exception", t.getClass().getName());
      output.addProperty("message", stackless(t));
      output.addProperty("elapsedNanos", System.nanoTime() - started);
    }
    writeJson(result, output);
    Files.deleteIfExists(requestPath);
  }

  private JsonObject run(MinecraftServer server, JsonObject request, String runId, Path requestPath, long started) {
    Request parsed = Request.parse(request);
    ServerLevel level = level(server, parsed.dimension);
    JsonArray cases = new JsonArray();
    boolean success = true;
    int trialCount = 0;
    int successCount = 0;
    for (CaseSpec spec : parsed.cases) {
      CaseResult result = runCase(level, parsed.horse, spec);
      cases.add(result.toJson());
      success &= result.success;
      trialCount += result.trialCount();
      successCount += result.successCount();
    }
    JsonObject json = new JsonObject();
    json.addProperty("schema", 1);
    json.addProperty("id", parsed.id);
    json.addProperty("runId", runId);
    json.addProperty("success", success);
    json.addProperty("terminalReason", success ? "SUCCESS" : "PHYSICS_REJECTED");
    json.addProperty("worldKey", parsed.worldKey);
    json.addProperty("dimension", parsed.dimension);
    json.addProperty("request", requestPath.toString());
    JsonElement mining = request.get("mining");
    if (mining != null) {
      json.add("mining", mining.deepCopy());
    }
    json.add("cases", cases);
    json.addProperty("caseCount", parsed.cases.size());
    json.addProperty("trialCount", trialCount);
    json.addProperty("successCount", successCount);
    json.addProperty("elapsedNanos", System.nanoTime() - started);
    return json;
  }

  private static ServerLevel level(MinecraftServer server, String dimension) {
    ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension));
    ServerLevel level = server.getLevel(key);
    if (level == null) {
      throw new IllegalArgumentException("No loaded dimension " + dimension);
    }
    return level;
  }

  private static CaseResult runCase(ServerLevel level, HorseSpec horseSpec, CaseSpec spec) {
    preload(level, spec);
    try (AppliedTerrain ignored = spec.terrain.apply(level, spec.start)) {
      TerrainSignature signature = TerrainSignature.capture(level, spec);
      ArrayList<TrialResult> trials = new ArrayList<>();
      boolean success = true;
      int trialCount = 0;
      int successCount = 0;
      int reachedCount = 0;
      int overshotCount = 0;
      int overrunCount = 0;
      int acceptedOverrunCount = 0;
      int fellBelowCount = 0;
      int damagedCount = 0;
      int dismountedCount = 0;
      int removedCount = 0;
      int missedCount = 0;
      double worstBestEndDistance = 0D;
      double worstEndDistance = 0D;
      double worstMaxFallDistance = 0D;
      double minFinalProgress = Double.POSITIVE_INFINITY;
      double maxFinalProgress = Double.NEGATIVE_INFINITY;
      for (double longitudinal : spec.perturbations.longitudinal) {
        for (double lateral : spec.perturbations.lateral) {
          for (double yaw : spec.perturbations.yaw) {
            for (double speed : spec.perturbations.initialSpeed) {
              for (double lateralSpeed : spec.perturbations.initialLateralSpeed) {
                TrialResult trial = runTrial(level, horseSpec, spec, new Perturbation(longitudinal, lateral, yaw, speed, lateralSpeed));
                if (spec.emitTrials) {
                  trials.add(trial);
                }
                trialCount++;
                if (trial.success) {
                  successCount++;
                }
                if (trial.reached) {
                  reachedCount++;
                }
                if (trial.overshot) {
                  overshotCount++;
                }
                if (trial.overrun) {
                  overrunCount++;
                }
                if (trial.acceptedOverrun) {
                  acceptedOverrunCount++;
                }
                if (trial.fellBelow) {
                  fellBelowCount++;
                }
                if (trial.damaged) {
                  damagedCount++;
                }
                if (trial.dismounted) {
                  dismountedCount++;
                }
                if (trial.removed) {
                  removedCount++;
                }
                if (!trial.reached && !trial.overshot && !trial.fellBelow && !trial.damaged && !trial.dismounted && trial.alive && !trial.removed) {
                  missedCount++;
                }
                worstBestEndDistance = Math.max(worstBestEndDistance, trial.bestEndDistance);
                worstEndDistance = Math.max(worstEndDistance, trial.endDistance);
                worstMaxFallDistance = Math.max(worstMaxFallDistance, trial.maxFallDistance);
                minFinalProgress = Math.min(minFinalProgress, trial.finalProgress);
                maxFinalProgress = Math.max(maxFinalProgress, trial.finalProgress);
                success &= trial.success;
              }
            }
          }
        }
      }
      return new CaseResult(spec.id, success, signature, trialCount, successCount, reachedCount, overshotCount, overrunCount, acceptedOverrunCount, fellBelowCount, damagedCount, dismountedCount,
        removedCount, missedCount, worstBestEndDistance, worstEndDistance, worstMaxFallDistance, finiteOrZero(minFinalProgress), finiteOrZero(maxFinalProgress), List.copyOf(trials));
    }
  }

  private static TrialResult runTrial(ServerLevel level, HorseSpec horseSpec, CaseSpec spec, Perturbation perturbation) {
    Pose3 start = spec.start.perturbed(perturbation);
    HarnessHorse horse = new HarnessHorse(level);
    configure(horse, horseSpec, start, spec.initialVelocity, spec.initialOnGround, spec.initialFallDistance, perturbation.initialSpeed == 0D ? horseSpec.initialSpeed : perturbation.initialSpeed,
      perturbation.initialLateralSpeed);
    SyntheticRider rider = new SyntheticRider(level);
    configureRider(rider, start, spec.input);
    boolean mounted = rider.startRiding(horse, true, true);
    List<Pose3> targets = spec.targets();
    double initialHealth = horse.getHealth();
    boolean saddled = horse.isSaddled();
    boolean controllingPassenger = horse.getControllingPassenger() == rider;
    double minY = horse.getY();
    double maxHorizontalSpeed = 0D;
    double maxFallDistance = horse.fallDistance;
    double bestEndDistance = spec.end == null ? 0D : Math.hypot(horse.getX() - spec.end.x, horse.getZ() - spec.end.z);
    boolean damaged = false;
    boolean fellBelow = false;
    boolean dismounted = !mounted;
    boolean overshot = false;
    boolean overrun = false;
    boolean acceptedOverrun = false;
    double maxSegmentProgress = 0D;
    int targetIndex = targets.isEmpty() ? 1 : 0;
    boolean reached = spec.end == null || bestEndDistance <= spec.endRadius && Math.abs(horse.getY() - spec.end.y) <= spec.maxEndYError;
    double travelX = spec.end == null ? 0D : spec.end.x - start.x;
    double travelZ = spec.end == null ? 0D : spec.end.z - start.z;
    double travelSq = travelX * travelX + travelZ * travelZ;
    ArrayList<TraceSample> samples = spec.emitTrials ? new ArrayList<>(spec.ticks + 1) : null;
    if (samples != null) {
      samples.add(TraceSample.capture(0, horse));
    }
    for (int tick = 0; tick < spec.ticks && !horse.isRemoved() && horse.isAlive(); tick++) {
      Pose3 target = targetIndex < targets.size() ? targets.get(targetIndex) : spec.end;
      Pose3 segmentStart = targetIndex <= 0 ? start : targets.get(targetIndex - 1);
      applyInput(rider, targetYaw(start, horse, target), spec.input);
      horse.tick();
      if (samples != null) {
        samples.add(TraceSample.capture(tick + 1, horse));
      }
      minY = Math.min(minY, horse.getY());
      Vec3 velocity = horse.getDeltaMovement();
      maxHorizontalSpeed = Math.max(maxHorizontalSpeed, Math.hypot(velocity.x, velocity.z));
      maxFallDistance = Math.max(maxFallDistance, horse.fallDistance);
      boolean segmentReached = false;
      if (target != null) {
        segmentReached = Math.hypot(horse.getX() - target.x, horse.getZ() - target.z) <= spec.endRadius && Math.abs(horse.getY() - target.y) <= spec.maxEndYError;
        double segmentX = target.x - segmentStart.x;
        double segmentZ = target.z - segmentStart.z;
        double segmentSq = segmentX * segmentX + segmentZ * segmentZ;
        if (segmentSq > 1.0E-9D) {
          double progress = ((horse.getX() - segmentStart.x) * segmentX + (horse.getZ() - segmentStart.z) * segmentZ) / segmentSq;
          maxSegmentProgress = Math.max(maxSegmentProgress, progress);
          boolean segmentOverrun = progress > 1D + spec.overshootSlack / segmentSq;
          overrun |= segmentOverrun;
          acceptedOverrun |= segmentOverrun && segmentReached;
          overshot |= spec.failOnOvershoot && !segmentReached && segmentOverrun;
        }
      }
      if (spec.end != null) {
        double tickEndDistance = Math.hypot(horse.getX() - spec.end.x, horse.getZ() - spec.end.z);
        bestEndDistance = Math.min(bestEndDistance, tickEndDistance);
      }
      if (segmentReached && targetIndex < targets.size()) {
        targetIndex++;
      }
      reached |= targetIndex >= targets.size();
      damaged |= horse.getHealth() + 1.0E-4F < initialHealth || horse.hurtTime > 0;
      fellBelow |= horse.getY() + 1.0E-4D < spec.minY;
      dismounted |= rider.getVehicle() != horse;
      if (reached && spec.stopAtEnd) {
        break;
      }
      if (overshot) {
        break;
      }
    }
    double endDistance = spec.end == null ? 0D : Math.hypot(horse.getX() - spec.end.x, horse.getZ() - spec.end.z);
    double endYError = spec.end == null ? 0D : Math.abs(horse.getY() - spec.end.y);
    double finalProgress = travelSq <= 1.0E-9D ? 0D : ((horse.getX() - start.x) * travelX + (horse.getZ() - start.z) * travelZ) / travelSq;
    boolean success = mounted && reached && (!spec.requireNoDamage || !damaged) && (!spec.requireMounted || !dismounted) && !fellBelow && !overshot && horse.isAlive() && !horse.isRemoved();
    return new TrialResult(success, perturbation, horse.getX(), horse.getY(), horse.getZ(), horse.getYRot(), horse.getDeltaMovement(), minY, maxHorizontalSpeed, horse.getHealth(), bestEndDistance,
      maxFallDistance, endDistance, endYError, finalProgress, maxSegmentProgress, reached, mounted, saddled, controllingPassenger, damaged, fellBelow, dismounted, overshot, overrun, acceptedOverrun,
      horse.isAlive(), horse.isRemoved(), samples == null ? List.of() : List.copyOf(samples));
  }

  private static void configure(HarnessHorse horse, HorseSpec spec, Pose3 start, Vec3 initialVelocity, Boolean initialOnGround, double initialFallDistance, double initialSpeed,
    double initialLateralSpeed) {
    horse.setTamed(true);
    horse.setTemper(100);
    horse.setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
    horse.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(spec.movementSpeed);
    horse.getAttribute(Attributes.JUMP_STRENGTH).setBaseValue(spec.jumpStrength);
    horse.absSnapTo(start.x, start.y, start.z, start.yaw, start.pitch);
    horse.setYHeadRot(start.yaw);
    horse.setYBodyRot(start.yaw);
    if (initialOnGround != null) {
      horse.setOnGround(initialOnGround);
    }
    horse.fallDistance = (float) initialFallDistance;
    if (initialVelocity != null) {
      horse.setDeltaMovement(initialVelocity);
    } else {
      Vec3 forward = forward(start.yaw);
      Vec3 right = right(start.yaw);
      horse.setDeltaMovement(forward.x * initialSpeed + right.x * initialLateralSpeed, 0D, forward.z * initialSpeed + right.z * initialLateralSpeed);
    }
    horse.setOldPosAndRot();
  }

  private static void configureRider(SyntheticRider rider, Pose3 start, InputSpec input) {
    rider.absSnapTo(start.x, start.y, start.z, start.yaw, start.pitch);
    rider.setYHeadRot(start.yaw);
    rider.setYBodyRot(start.yaw);
    applyInput(rider, start.yaw, input);
  }

  private static void applyInput(SyntheticRider rider, float yaw, InputSpec input) {
    rider.setYRot(yaw);
    rider.setXRot(0F);
    rider.setYHeadRot(yaw);
    rider.setYBodyRot(yaw);
    rider.xxa = (input.left ? 1F : 0F) + (input.right ? -1F : 0F);
    rider.zza = (input.forward ? 1F : 0F) + (input.backward ? -1F : 0F);
    rider.setSprinting(input.sprint);
  }

  private static Vec3 forward(float yaw) {
    double radians = Math.toRadians(yaw);
    return new Vec3(-Math.sin(radians), 0D, Math.cos(radians));
  }

  private static Vec3 right(float yaw) {
    double radians = Math.toRadians(yaw);
    return new Vec3(Math.cos(radians), 0D, Math.sin(radians));
  }

  private static float targetYaw(Pose3 start, HarnessHorse horse, Pose3 end) {
    return end == null ? start.yaw : yawTo(horse.getX(), horse.getZ(), end.x, end.z);
  }

  private static float yawTo(double fromX, double fromZ, double toX, double toZ) {
    return (float) Math.toDegrees(Math.atan2(fromX - toX, toZ - fromZ));
  }

  private static void preload(ServerLevel level, CaseSpec spec) {
    int minX = Mth.floor(Math.min(spec.start.x, spec.end == null ? spec.start.x : spec.end.x)) - 3;
    int maxX = Mth.floor(Math.max(spec.start.x, spec.end == null ? spec.start.x : spec.end.x)) + 3;
    int minZ = Mth.floor(Math.min(spec.start.z, spec.end == null ? spec.start.z : spec.end.z)) - 3;
    int maxZ = Mth.floor(Math.max(spec.start.z, spec.end == null ? spec.start.z : spec.end.z)) + 3;
    int y = Mth.floor(spec.start.y);
    for (int x = minX; x <= maxX; x += 16) {
      for (int z = minZ; z <= maxZ; z += 16) {
        level.getChunkAt(new BlockPos(x, y, z));
      }
    }
    level.getChunkAt(new BlockPos(maxX, y, maxZ));
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

  private static JsonArray array(JsonObject json, String key) {
    JsonElement element = json.get(key);
    return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
  }

  private static String string(JsonObject json, String key, String fallback) {
    JsonElement element = json.get(key);
    return element == null || element.isJsonNull() ? fallback : element.getAsString();
  }

  private static int integer(JsonObject json, String key, int fallback) {
    JsonElement element = json.get(key);
    return element == null || element.isJsonNull() ? fallback : element.getAsInt();
  }

  private static double decimal(JsonObject json, String key, double fallback) {
    JsonElement element = json.get(key);
    return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
  }

  private static double finiteOrZero(double value) {
    return Double.isFinite(value) ? value : 0D;
  }

  private static boolean bool(JsonObject json, String key, boolean fallback) {
    JsonElement element = json.get(key);
    return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
  }

  private static Boolean boxedBool(JsonObject json, String key) {
    JsonElement element = json.get(key);
    return element == null || element.isJsonNull() ? null : element.getAsBoolean();
  }

  private static int prefixCollisionMask(int height, int floorDy, int clearDy) {
    int mask = 0;
    for (int dy = floorDy; dy <= clearDy; dy++) {
      if (dy > floorDy && dy <= height) {
        mask |= bit(dy, floorDy);
      }
    }
    return mask;
  }

  private static int topHeight(int mask, int floorDy, int clearDy) {
    for (int dy = clearDy; dy >= floorDy; dy--) {
      if ((mask & bit(dy, floorDy)) != 0) {
        return dy;
      }
    }
    return floorDy;
  }

  private static int bit(int dy, int floorDy) {
    return 1 << (dy - floorDy);
  }

  private static double[] decimals(JsonObject json, String key, double... fallback) {
    JsonArray array = array(json, key);
    if (array.size() == 0) {
      return fallback;
    }
    double[] out = new double[array.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = array.get(i).getAsDouble();
    }
    return out;
  }

  private static String stackless(Throwable t) {
    StringWriter writer = new StringWriter();
    writer.append(t.getMessage() == null ? "" : t.getMessage());
    Throwable cause = t.getCause();
    while (cause != null) {
      writer.append(" <- ").append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage() == null ? "" : cause.getMessage());
      cause = cause.getCause();
    }
    return writer.toString();
  }

  private record Request(String id, String worldKey, String dimension, HorseSpec horse, List<CaseSpec> cases) {
    static Request parse(JsonObject json) {
      String id = string(json, "id", "horse_physics");
      String worldKey = string(json, "worldKey", "default");
      String dimension = string(json, "dimension", DEFAULT_DIMENSION);
      HorseSpec horse = HorseSpec.parse(object(json, "horse"));
      JsonArray rawCases = array(json, "cases");
      ArrayList<CaseSpec> cases = new ArrayList<>();
      if (rawCases.size() == 0) {
        cases.add(CaseSpec.parse(json, json, 0));
      } else {
        for (int i = 0; i < rawCases.size(); i++) {
          cases.add(CaseSpec.parse(rawCases.get(i).getAsJsonObject(), json, i));
        }
      }
      return new Request(id, worldKey, dimension, horse, List.copyOf(cases));
    }
  }

  private record HorseSpec(double movementSpeed, double jumpStrength, double initialSpeed) {
    static HorseSpec parse(JsonObject json) {
      return new HorseSpec(decimal(json, "movementSpeed", 0.30D), decimal(json, "jumpStrength", 0.90D), decimal(json, "initialSpeed", 0D));
    }
  }

  private record CaseSpec(String id, Pose3 start, Vec3 initialVelocity, Boolean initialOnGround, double initialFallDistance, Pose3 end, List<Pose3> targets, InputSpec input,
    PerturbationGrid perturbations, TerrainPatch terrain, int ticks, double minY, double endRadius, double maxEndYError, boolean requireNoDamage, boolean requireMounted, boolean stopAtEnd,
    boolean failOnOvershoot, double overshootSlack, boolean emitTrials) {
    static CaseSpec parse(JsonObject json, JsonObject root, int index) {
      JsonObject rootStart = object(root, "start");
      Pose3 start = Pose3.parse(object(json, "start").entrySet().isEmpty() ? rootStart : object(json, "start"));
      Vec3 initialVelocity = Vec3Spec.parseOptional(object(json, "initialVelocity").entrySet().isEmpty() ? object(root, "initialVelocity") : object(json, "initialVelocity"));
      Boolean initialOnGround = boxedBool(json, "initialOnGround");
      if (initialOnGround == null) {
        initialOnGround = boxedBool(root, "initialOnGround");
      }
      ArrayList<Pose3> targets = new ArrayList<>();
      JsonArray targetArray = array(json, "targets");
      if (targetArray.isEmpty()) {
        targetArray = array(root, "targets");
      }
      for (JsonElement target : targetArray) {
        targets.add(Pose3.parse(target.getAsJsonObject()));
      }
      JsonObject endJson = object(json, "end");
      if (endJson.entrySet().isEmpty()) {
        endJson = object(root, "end");
      }
      Pose3 explicitEnd = endJson.entrySet().isEmpty() ? null : Pose3.parse(endJson);
      Pose3 end = targets.isEmpty() ? explicitEnd : targets.getLast();
      List<Pose3> parsedTargets = targets.isEmpty() && explicitEnd != null ? List.of(explicitEnd) : List.copyOf(targets);
      JsonObject inputJson = object(json, "input").entrySet().isEmpty() ? object(root, "input") : object(json, "input");
      JsonObject perturbationJson = object(json, "perturbations").entrySet().isEmpty() ? object(root, "perturbations") : object(json, "perturbations");
      JsonObject terrainJson = object(json, "terrain").entrySet().isEmpty() ? object(root, "terrain") : object(json, "terrain");
      double defaultMinY = end == null ? Double.NEGATIVE_INFINITY : Math.min(start.y, end.y) - 0.20D;
      return new CaseSpec(string(json, "id", string(root, "id", "case") + "#" + index), start, initialVelocity, initialOnGround,
        decimal(json, "initialFallDistance", decimal(root, "initialFallDistance", 0D)), end, parsedTargets, InputSpec.parse(inputJson), PerturbationGrid.parse(perturbationJson),
        TerrainPatch.parse(terrainJson), integer(json, "ticks", integer(root, "ticks", 40)), decimal(json, "minY", decimal(root, "minY", defaultMinY)),
        decimal(json, "endRadius", decimal(root, "endRadius", 0.80D)), decimal(json, "maxEndYError", decimal(root, "maxEndYError", 1.25D)),
        bool(json, "requireNoDamage", bool(root, "requireNoDamage", true)), bool(json, "requireMounted", bool(root, "requireMounted", true)), bool(json, "stopAtEnd", bool(root, "stopAtEnd", true)),
        bool(json, "failOnOvershoot", bool(root, "failOnOvershoot", false)), decimal(json, "overshootSlack", decimal(root, "overshootSlack", 0D)),
        bool(json, "emitTrials", bool(root, "emitTrials", true)));
    }
  }

  private record Pose3(double x, double y, double z, float yaw, float pitch) {
    static Pose3 parse(JsonObject json) {
      return new Pose3(decimal(json, "x", 0D), decimal(json, "y", 80D), decimal(json, "z", 0D), (float) decimal(json, "yaw", 0D), (float) decimal(json, "pitch", 0D));
    }

    Pose3 perturbed(Perturbation perturbation) {
      Vec3 forward = forward(yaw);
      Vec3 right = right(yaw);
      return new Pose3(x + perturbation.longitudinal * forward.x + perturbation.lateral * right.x, y, z + perturbation.longitudinal * forward.z + perturbation.lateral * right.z,
        yaw + (float) perturbation.yaw, pitch);
    }
  }

  private record Vec3Spec(double x, double y, double z) {
    static Vec3 parseOptional(JsonObject json) {
      return json.entrySet().isEmpty() ? null : new Vec3(decimal(json, "x", 0D), decimal(json, "y", 0D), decimal(json, "z", 0D));
    }
  }

  private record InputSpec(boolean forward, boolean backward, boolean left, boolean right, boolean sprint) {
    static InputSpec parse(JsonObject json) {
      return new InputSpec(bool(json, "forward", true), bool(json, "backward", false), bool(json, "left", false), bool(json, "right", false), bool(json, "sprint", false));
    }
  }

  private record PerturbationGrid(double[] longitudinal, double[] lateral, double[] yaw, double[] initialSpeed, double[] initialLateralSpeed) {
    static PerturbationGrid parse(JsonObject json) {
      return new PerturbationGrid(decimals(json, "longitudinal", 0D), decimals(json, "lateral", 0D), decimals(json, "yaw", 0D), decimals(json, "initialSpeed", 0D),
        decimals(json, "initialLateralSpeed", 0D));
    }
  }

  private record Perturbation(double longitudinal, double lateral, double yaw, double initialSpeed, double initialLateralSpeed) {
    JsonObject toJson() {
      JsonObject json = new JsonObject();
      json.addProperty("longitudinal", longitudinal);
      json.addProperty("lateral", lateral);
      json.addProperty("yaw", yaw);
      json.addProperty("initialSpeed", initialSpeed);
      json.addProperty("initialLateralSpeed", initialLateralSpeed);
      return json;
    }
  }

  private record CaseResult(String id, boolean success, TerrainSignature signature, int trialCount, int successCount, int reachedCount, int overshotCount, int overrunCount, int acceptedOverrunCount,
    int fellBelowCount, int damagedCount, int dismountedCount, int removedCount, int missedCount, double worstBestEndDistance, double worstEndDistance, double worstMaxFallDistance,
    double minFinalProgress, double maxFinalProgress, List<TrialResult> trials) {
    JsonObject toJson() {
      JsonObject json = new JsonObject();
      json.addProperty("id", id);
      json.addProperty("success", success);
      json.addProperty("trialCount", trialCount);
      json.addProperty("successCount", successCount);
      json.addProperty("reachedCount", reachedCount);
      json.addProperty("overshotCount", overshotCount);
      json.addProperty("overrunCount", overrunCount);
      json.addProperty("acceptedOverrunCount", acceptedOverrunCount);
      json.addProperty("fellBelowCount", fellBelowCount);
      json.addProperty("damagedCount", damagedCount);
      json.addProperty("dismountedCount", dismountedCount);
      json.addProperty("removedCount", removedCount);
      json.addProperty("missedCount", missedCount);
      json.addProperty("worstBestEndDistance", worstBestEndDistance);
      json.addProperty("worstEndDistance", worstEndDistance);
      json.addProperty("worstMaxFallDistance", worstMaxFallDistance);
      json.addProperty("minFinalProgress", minFinalProgress);
      json.addProperty("maxFinalProgress", maxFinalProgress);
      json.add("terrainSignature", signature.toJson());
      if (!trials.isEmpty()) {
        JsonArray array = new JsonArray();
        for (TrialResult trial : trials) {
          array.add(trial.toJson());
        }
        json.add("trials", array);
      }
      return json;
    }
  }

  private record TrialResult(boolean success, Perturbation perturbation, double x, double y, double z, float yaw, Vec3 velocity, double minY, double maxHorizontalSpeed, double health,
    double bestEndDistance, double maxFallDistance, double endDistance, double endYError, double finalProgress, double maxSegmentProgress, boolean reached, boolean mountedInitially, boolean saddled,
    boolean controllingPassenger, boolean damaged, boolean fellBelow, boolean dismounted, boolean overshot, boolean overrun, boolean acceptedOverrun, boolean alive, boolean removed,
    List<TraceSample> samples) {
    JsonObject toJson() {
      JsonObject json = new JsonObject();
      json.addProperty("success", success);
      json.add("perturbation", perturbation.toJson());
      JsonObject finalPose = new JsonObject();
      finalPose.addProperty("x", x);
      finalPose.addProperty("y", y);
      finalPose.addProperty("z", z);
      finalPose.addProperty("yaw", yaw);
      json.add("final", finalPose);
      JsonObject v = new JsonObject();
      v.addProperty("x", velocity.x);
      v.addProperty("y", velocity.y);
      v.addProperty("z", velocity.z);
      json.add("velocity", v);
      json.addProperty("minY", minY);
      json.addProperty("maxHorizontalSpeed", maxHorizontalSpeed);
      json.addProperty("maxFallDistance", maxFallDistance);
      json.addProperty("health", health);
      json.addProperty("bestEndDistance", bestEndDistance);
      json.addProperty("endDistance", endDistance);
      json.addProperty("endYError", endYError);
      json.addProperty("finalProgress", finalProgress);
      json.addProperty("maxSegmentProgress", maxSegmentProgress);
      json.addProperty("reached", reached);
      json.addProperty("mountedInitially", mountedInitially);
      json.addProperty("saddled", saddled);
      json.addProperty("controllingPassenger", controllingPassenger);
      json.addProperty("damaged", damaged);
      json.addProperty("fellBelow", fellBelow);
      json.addProperty("dismounted", dismounted);
      json.addProperty("overshot", overshot);
      json.addProperty("overrun", overrun);
      json.addProperty("acceptedOverrun", acceptedOverrun);
      json.addProperty("alive", alive);
      json.addProperty("removed", removed);
      if (!samples.isEmpty()) {
        JsonArray array = new JsonArray();
        for (TraceSample sample : samples) {
          array.add(sample.toJson());
        }
        json.add("samples", array);
      }
      return json;
    }
  }

  private record TraceSample(int tick, double x, double y, double z, float yaw, Vec3 velocity, boolean onGround, double fallDistance, AABB box) {
    static TraceSample capture(int tick, Horse horse) {
      return new TraceSample(tick, horse.getX(), horse.getY(), horse.getZ(), horse.getYRot(), horse.getDeltaMovement(), horse.onGround(), horse.fallDistance, horse.getBoundingBox());
    }

    JsonObject toJson() {
      JsonObject json = new JsonObject();
      json.addProperty("tick", tick);
      json.addProperty("x", x);
      json.addProperty("y", y);
      json.addProperty("z", z);
      json.addProperty("yaw", yaw);
      JsonObject v = new JsonObject();
      v.addProperty("x", velocity.x);
      v.addProperty("y", velocity.y);
      v.addProperty("z", velocity.z);
      json.add("velocity", v);
      json.addProperty("onGround", onGround);
      json.addProperty("fallDistance", fallDistance);
      JsonObject b = new JsonObject();
      b.addProperty("minX", box.minX);
      b.addProperty("minY", box.minY);
      b.addProperty("minZ", box.minZ);
      b.addProperty("maxX", box.maxX);
      b.addProperty("maxY", box.maxY);
      b.addProperty("maxZ", box.maxZ);
      json.add("box", b);
      return json;
    }
  }

  private record TerrainPatch(boolean synthetic, int minDx, int maxDx, int minDz, int maxDz, int floorDy, int clearDy, int defaultMask, BlockState block, BlockState air, List<Column> columns) {
    private static final TerrainPatch NONE = new TerrainPatch(false, 0, 0, 0, 0, 0, 0, 0, Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState(), List.of());
    private static final int MAX_COLUMNS = 25 * 25;
    private static final int MAX_TOUCHED_BLOCKS = 25 * 25 * 16;
    private static final int UPDATE_FLAGS = Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;

    static TerrainPatch parse(JsonObject json) {
      if (json.entrySet().isEmpty()) {
        return NONE;
      }
      int minDx = integer(json, "minDx", -3);
      int maxDx = integer(json, "maxDx", 3);
      int minDz = integer(json, "minDz", -3);
      int maxDz = integer(json, "maxDz", 3);
      int floorDy = integer(json, "floorDy", -3);
      int clearDy = integer(json, "clearDy", 5);
      if (minDx > maxDx || minDz > maxDz || floorDy > clearDy) {
        throw new IllegalArgumentException("inverted terrain patch bounds");
      }
      if (clearDy - floorDy > 15) {
        throw new IllegalArgumentException("terrain patch height range exceeds packed signature nibble budget: floorDy=" + floorDy + " clearDy=" + clearDy);
      }
      int columnCount = (maxDx - minDx + 1) * (maxDz - minDz + 1);
      int touched = columnCount * (clearDy - floorDy + 1);
      if (columnCount > MAX_COLUMNS || touched > MAX_TOUCHED_BLOCKS) {
        throw new IllegalArgumentException("terrain patch too large: columns=" + columnCount + " touched=" + touched);
      }
      BlockState block = BlockStateCodec.parseSponge(string(json, "block", "minecraft:stone"));
      BlockState air = BlockStateCodec.parseSponge(string(json, "air", "minecraft:air"));
      int defaultHeight = integer(json, "defaultHeight", 0);
      int defaultMask = integer(json, "defaultMask", prefixCollisionMask(defaultHeight, floorDy, clearDy));
      ArrayList<Column> columns = new ArrayList<>();
      for (JsonElement element : array(json, "columns")) {
        JsonObject column = element.getAsJsonObject();
        int dx = integer(column, "dx", 0);
        int dz = integer(column, "dz", 0);
        if (dx < minDx || dx > maxDx || dz < minDz || dz > maxDz) {
          throw new IllegalArgumentException("terrain column outside patch bounds: dx=" + dx + " dz=" + dz);
        }
        int mask = integer(column, "mask", prefixCollisionMask(integer(column, "height", defaultHeight), floorDy, clearDy));
        columns.add(new Column(dx, dz, mask, column.has("block") ? BlockStateCodec.parseSponge(string(column, "block", "minecraft:stone")) : block));
      }
      return new TerrainPatch(true, minDx, maxDx, minDz, maxDz, floorDy, clearDy, defaultMask, block, air, List.copyOf(columns));
    }

    AppliedTerrain apply(ServerLevel level, Pose3 start) {
      if (!synthetic) {
        return AppliedTerrain.empty(level);
      }
      int feetX = Mth.floor(start.x);
      int feetY = Mth.floor(start.y + 1.0E-6D);
      int feetZ = Mth.floor(start.z);
      int width = maxDx - minDx + 1;
      int depth = maxDz - minDz + 1;
      int[] masks = denseMasks(width, depth);
      BlockState[] blocks = denseBlocks(width, depth);
      ArrayList<SavedBlock> saved = new ArrayList<>(width * depth * (clearDy - floorDy + 1));
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      for (int dz = minDz; dz <= maxDz; dz++) {
        for (int dx = minDx; dx <= maxDx; dx++) {
          int index = (dz - minDz) * width + (dx - minDx);
          int mask = masks[index];
          BlockState support = blocks[index];
          for (int sampleDy = floorDy; sampleDy <= clearDy; sampleDy++) {
            pos.set(feetX + dx, feetY + sampleDy - 1, feetZ + dz);
            saved.add(new SavedBlock(pos.immutable(), level.getBlockState(pos)));
            level.setBlock(pos, (mask & bit(sampleDy, floorDy)) != 0 ? support : air, UPDATE_FLAGS);
          }
        }
      }
      return new AppliedTerrain(level, List.copyOf(saved));
    }

    TerrainSignature signature(ServerLevel level, CaseSpec spec) {
      if (!synthetic) {
        return TerrainSignature.sample(level, spec);
      }
      int width = maxDx - minDx + 1;
      int depth = maxDz - minDz + 1;
      return TerrainSignature.syntheticMasks(minDx, maxDx, minDz, maxDz, floorDy, clearDy, denseMasks(width, depth));
    }

    private int[] denseMasks(int width, int depth) {
      int[] masks = new int[width * depth];
      java.util.Arrays.fill(masks, defaultMask);
      for (Column column : columns) {
        masks[(column.dz - minDz) * width + (column.dx - minDx)] = column.mask;
      }
      return masks;
    }

    private BlockState[] denseBlocks(int width, int depth) {
      BlockState[] blocks = new BlockState[width * depth];
      java.util.Arrays.fill(blocks, block);
      for (Column column : columns) {
        blocks[(column.dz - minDz) * width + (column.dx - minDx)] = column.block;
      }
      return blocks;
    }
  }

  private record Column(int dx, int dz, int mask, BlockState block) {
  }

  private record SavedBlock(BlockPos pos, BlockState state) {
  }

  private record AppliedTerrain(ServerLevel level, List<SavedBlock> saved) implements AutoCloseable {
    static AppliedTerrain empty(ServerLevel level) {
      return new AppliedTerrain(level, List.of());
    }

    @Override
    public void close() {
      for (int i = saved.size() - 1; i >= 0; i--) {
        SavedBlock block = saved.get(i);
        level.setBlock(block.pos, block.state, TerrainPatch.UPDATE_FLAGS);
      }
    }
  }

  private record TerrainSignature(String source, int minDx, int maxDx, int minDz, int maxDz, int floorDy, int clearDy, int[] heights, int[] occupancyMasks) {
    static TerrainSignature capture(ServerLevel level, CaseSpec spec) {
      return spec.terrain.signature(level, spec);
    }

    static TerrainSignature synthetic(int minDx, int maxDx, int minDz, int maxDz, int floorDy, int clearDy, int[] heights) {
      return new TerrainSignature("synthetic-collision-columnmask-v1", minDx, maxDx, minDz, maxDz, floorDy, clearDy, heights.clone(), syntheticOccupancy(heights, floorDy, clearDy));
    }

    static TerrainSignature syntheticMasks(int minDx, int maxDx, int minDz, int maxDz, int floorDy, int clearDy, int[] masks) {
      return new TerrainSignature("synthetic-collision-columnmask-v2", minDx, maxDx, minDz, maxDz, floorDy, clearDy, heightsFromMasks(masks, floorDy, clearDy), masks.clone());
    }

    private static int[] syntheticOccupancy(int[] heights, int floorDy, int clearDy) {
      int[] masks = new int[heights.length];
      for (int i = 0; i < heights.length; i++) {
        int mask = 0;
        for (int dy = floorDy; dy <= clearDy; dy++) {
          if (dy > floorDy && dy <= heights[i]) {
            mask |= bit(dy, floorDy);
          }
        }
        masks[i] = mask;
      }
      return masks;
    }

    private static int[] heightsFromMasks(int[] masks, int floorDy, int clearDy) {
      int[] heights = new int[masks.length];
      for (int i = 0; i < masks.length; i++) {
        heights[i] = topHeight(masks[i], floorDy, clearDy);
      }
      return heights;
    }

    static TerrainSignature sample(ServerLevel level, CaseSpec spec) {
      int startX = Mth.floor(spec.start.x);
      int startY = Mth.floor(spec.start.y + 1.0E-6D);
      int startZ = Mth.floor(spec.start.z);
      int endX = spec.end == null ? startX : Mth.floor(spec.end.x);
      int endZ = spec.end == null ? startZ : Mth.floor(spec.end.z);
      int minDx = Math.min(0, endX - startX) - 2;
      int maxDx = Math.max(0, endX - startX) + 2;
      int minDz = Math.min(0, endZ - startZ) - 2;
      int maxDz = Math.max(0, endZ - startZ) + 2;
      int floorDy = -3;
      int clearDy = 5;
      int width = maxDx - minDx + 1;
      int[] heights = new int[width * (maxDz - minDz + 1)];
      int[] masks = new int[heights.length];
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      for (int dz = minDz; dz <= maxDz; dz++) {
        for (int dx = minDx; dx <= maxDx; dx++) {
          int height = floorDy;
          int mask = 0;
          for (int dy = clearDy; dy >= floorDy; dy--) {
            pos.set(startX + dx, startY + dy - 1, startZ + dz);
            BlockState state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos).isEmpty()) {
              mask |= 1 << (dy - floorDy);
              height = Math.max(height, dy);
            }
          }
          int index = (dz - minDz) * width + (dx - minDx);
          heights[index] = height;
          masks[index] = mask;
        }
      }
      return new TerrainSignature("sampled-collision-columnmask-v1", minDx, maxDx, minDz, maxDz, floorDy, clearDy, heights, masks);
    }

    JsonObject toJson() {
      JsonObject json = new JsonObject();
      json.addProperty("source", source);
      json.addProperty("minDx", minDx);
      json.addProperty("maxDx", maxDx);
      json.addProperty("minDz", minDz);
      json.addProperty("maxDz", maxDz);
      json.addProperty("floorDy", floorDy);
      json.addProperty("clearDy", clearDy);
      json.addProperty("width", maxDx - minDx + 1);
      json.addProperty("depth", maxDz - minDz + 1);
      json.addProperty("packed4", packed4());
      JsonArray raw = new JsonArray();
      for (int height : heights) {
        raw.add(height);
      }
      json.add("heights", raw);
      JsonArray masks = new JsonArray();
      for (int mask : occupancyMasks) {
        masks.add(mask);
      }
      json.add("occupancyMasks", masks);
      json.addProperty("occupancyPackedHex", occupancyPackedHex());
      return json;
    }

    private String packed4() {
      StringBuilder out = new StringBuilder((heights.length + 1) / 2);
      for (int i = 0; i < heights.length; i += 2) {
        int lo = nibble(heights[i]);
        int hi = i + 1 < heights.length ? nibble(heights[i + 1]) : 0;
        out.append(Character.forDigit((hi << 4 | lo) >>> 4, 16)).append(Character.forDigit((hi << 4 | lo) & 15, 16));
      }
      return out.toString();
    }

    private String occupancyPackedHex() {
      int bitsPerCell = clearDy - floorDy + 1;
      long acc = 0L;
      int accBits = 0;
      StringBuilder out = new StringBuilder((occupancyMasks.length * bitsPerCell + 3) / 4);
      for (int mask : occupancyMasks) {
        acc |= (long) mask << accBits;
        accBits += bitsPerCell;
        while (accBits >= 4) {
          out.append(Character.forDigit((int) (acc & 15L), 16));
          acc >>>= 4;
          accBits -= 4;
        }
      }
      if (accBits > 0) {
        out.append(Character.forDigit((int) (acc & 15L), 16));
      }
      return out.toString();
    }

    private int nibble(int height) {
      int encoded = height - floorDy;
      if (encoded < 0 || encoded > 15) {
        throw new IllegalArgumentException("terrain signature height outside packed range: " + height + " floor=" + floorDy);
      }
      return encoded;
    }
  }

  private static final class HarnessHorse extends Horse {
    private HarnessHorse(Level level) {
      super(EntityType.HORSE, level);
    }

    @Override
    public boolean isSaddled() { return true; }
  }

  private static final class SyntheticRider extends Player {
    private SyntheticRider(Level level) {
      super(level, new GameProfile(UUID.nameUUIDFromBytes("baritone-horse-physics-rider".getBytes(StandardCharsets.UTF_8)), "HorsePhysics"));
      setInvulnerable(true);
    }

    @Override
    public GameType gameMode() {
      return GameType.SURVIVAL;
    }

    @Override
    public boolean isSpectator() { return false; }

    @Override
    public boolean isCreative() { return false; }

    @Override
    public HumanoidArm getMainArm() { return HumanoidArm.RIGHT; }

    @Override
    public boolean hurtServer(ServerLevel level, net.minecraft.world.damagesource.DamageSource damageSource, float amount) {
      return false;
    }

    @Override
    public boolean isAlwaysTicking() { return true; }

    @Override
    public boolean isClientAuthoritative() { return false; }

    @Override
    public void tick() {
    }

    @Override
    public void rideTick() {
      Entity vehicle = getVehicle();
      if (vehicle != null) {
        absSnapTo(vehicle.getX(), vehicle.getY(), vehicle.getZ(), getYRot(), getXRot());
      }
    }

  }
}
