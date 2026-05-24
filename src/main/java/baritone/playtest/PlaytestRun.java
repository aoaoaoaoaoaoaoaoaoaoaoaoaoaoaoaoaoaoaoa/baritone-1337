package baritone.playtest;

import baritone.Baritone;
import baritone.api.event.events.PathEvent;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.GoldenTuning;
import baritone.api.utils.input.Input;
import baritone.pathing.farfield.FarfieldObjective;
import baritone.pathing.macro.core.MacroActionInstance;
import baritone.pathing.macro.core.MacroActionKind;
import baritone.pathing.macro.core.MacroNodeKey;
import baritone.pathing.macro.core.MacroPlan;
import baritone.pathing.path.RouteExecutor;
import baritone.pathing.route.RouteLeg;
import baritone.pathing.route.SurfaceRouteLeg;
import baritone.pathing.transport.TransportMode;
import baritone.pathing.transport.TransportSnapshot;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

final class PlaytestRun {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final int TOOL_DAMAGE_UNSET = -2;
  private static final double YAW_REVERSAL_DEGREES = 7.5D;

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
  private int initialFood = Integer.MIN_VALUE;
  private int minFood = Integer.MAX_VALUE;
  private int pathEvents;
  private final EnumMap<PathEvent, Integer> pathEventCounts = new EnumMap<>(PathEvent.class);
  private int nextCalcFailures;
  private PathEvent lastPathEvent;
  private boolean calcFailed;
  private int firstPlanningTick = -1;
  private int firstActuationTick = -1;
  private int firstPathingTick = -1;
  private int firstRouteTick = -1;
  private int firstMacroPlanTick = -1;
  private int totalStalledTicks;
  private int preActuationStalledTicks;
  private int postActuationStalledTicks;
  private int stallEpisodes;
  private int currentStallTicks;
  private int maxStallTicks;
  private boolean sawBoat;
  private boolean sawHorse;
  private boolean lostHorseAfterEncounter;
  private boolean sawWater;
  private boolean sawPathing;
  private boolean sawBuilder;
  private boolean sawMacroRoute;
  private boolean sawMacroBiomeRoute;
  private boolean sawPlannedBoat;
  private boolean sawPlannedHorse;
  private int maxMacroBoatLegs;
  private double maxMacroBoatDistance;
  private double bestRouteDestDistance = Double.POSITIVE_INFINITY;
  private BetterBlockPos bestRouteDest;
  private String lastRouteSequence;
  private String lastMacroBiomeSequence;
  private double bestMacroBiomeEstimatedTicks = Double.POSITIVE_INFINITY;
  private boolean sawMacroPlanBoat;
  private int maxMacroPlanBoatActions;
  private double maxMacroPlanBoatDistance;
  private boolean sawMacroPlanSurfaceTransition;
  private int maxMacroPlanSurfaceActions;
  private double maxMacroPlanSurfaceDistance;
  private boolean sawMacroPlanPortal;
  private int maxMacroPlanPortalActions;
  private int maxMacroPlanPortalBuildExitActions;
  private double maxMacroPlanNetherDistanceBeforePortalExit;
  private int maxMacroBiomeFactualCells;
  private int maxMacroBiomeUnknownCells;
  private int maxMacroBiomeLiveCells;
  private int maxMacroBiomeCachedCells;
  private int maxMacroBiomePredictedCells;
  private int maxMacroBiomePriorCells;
  private boolean sawFarfieldRoute;
  private double bestFarfieldEstimatedTicks = Double.POSITIVE_INFINITY;
  private int maxFarfieldLiveStates;
  private int maxFarfieldCachedStates;
  private int maxFarfieldPriorStates;
  private int maxFarfieldStates;
  private int lastFarfieldStartStratum = -1;
  private int lastFarfieldTargetStratum = -1;
  private int moveForwardTicks;
  private int jumpTicks;
  private int sprintTicks;
  private int sprintInputTicks;
  private int sneakTicks;
  private final YawMetrics playerYaw = new YawMetrics();
  private final YawMetrics vehicleYaw = new YawMetrics();
  private final YawMetrics targetYaw = new YawMetrics();
  private final YawMetrics pathYaw = new YawMetrics();
  private int initialPickaxeDamage = TOOL_DAMAGE_UNSET;
  private int maxPickaxeDamage = TOOL_DAMAGE_UNSET;
  private int initialAxeDamage = TOOL_DAMAGE_UNSET;
  private int maxAxeDamage = TOOL_DAMAGE_UNSET;
  private int initialShovelDamage = TOOL_DAMAGE_UNSET;
  private int maxShovelDamage = TOOL_DAMAGE_UNSET;
  private int initialCobblestone = Integer.MIN_VALUE;
  private int minCobblestone = Integer.MAX_VALUE;
  private String initialDimension;
  private String finalDimension;
  private boolean sawDimensionChange;
  private double closestHorseDistanceToGoal = Double.POSITIVE_INFINITY;
  private Vec3 closestHorsePos;
  private int closestHorseTick = -1;

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
    pathEventCounts.merge(event, 1, Integer::sum);
    lastPathEvent = event;
    calcFailed |= event == PathEvent.CALC_FAILED;
    nextCalcFailures += event == PathEvent.NEXT_CALC_FAILED ? 1 : 0;
  }

  void sample(Baritone baritone, Minecraft minecraft) {
    ticks++;
    LocalPlayer player = minecraft.player;
    if (player == null) {
      return;
    }
    boolean activeWork = activeWork(baritone);
    if (baritone.getPathingBehavior().isPathing()) {
      sawPathing = true;
      firstPathingTick = firstSeen(firstPathingTick);
    }
    sawBuilder |= baritone.getBuilderProcess().isActive();
    if (baritone.getPathingBehavior().getPlanningStart().isPresent()) {
      firstPlanningTick = firstSeen(firstPlanningTick);
    }
    boolean actuating = pathingInputForced(baritone);
    if (actuating) {
      firstActuationTick = firstSeen(firstActuationTick);
    }
    if (Float.isNaN(initialHealth)) {
      initialHealth = player.getHealth();
    }
    sampleDimension(player);
    if (initialFood == Integer.MIN_VALUE) {
      initialFood = player.getFoodData().getFoodLevel();
    }
    minHealth = Math.min(minHealth, player.getHealth());
    minFood = Math.min(minFood, player.getFoodData().getFoodLevel());
    minAir = Math.min(minAir, player.getAirSupply());
    boolean mountedHorse = player.getVehicle() instanceof AbstractHorse;
    if (mountedHorse) {
      sampleHorseClosestApproach((AbstractHorse) player.getVehicle());
    }
    sawWater |= player.isInWater() || player.isUnderWater() || player.isSwimming() || mountedHorseWater(player);
    sawBoat |= player.getVehicle() instanceof AbstractBoat;
    lostHorseAfterEncounter |= sawHorse && !mountedHorse;
    sawHorse |= mountedHorse;
    boolean moveForward = forced(baritone, Input.MOVE_FORWARD);
    sampleStall(player, activeWork, actuating);
    moveForwardTicks += moveForward ? 1 : 0;
    jumpTicks += forced(baritone, Input.JUMP) ? 1 : 0;
    sprintTicks += player.isSprinting() ? 1 : 0;
    sprintInputTicks += forced(baritone, Input.SPRINT) ? 1 : 0;
    sneakTicks += forced(baritone, Input.SNEAK) ? 1 : 0;
    sampleYaw(player, moveForward);
    sampleInventoryUse(player);
    sampleRoute(baritone.getPathingBehavior().getCurrent(), baritone);
    sampleRoute(baritone.getPathingBehavior().getNext(), baritone);
    sampleMacroPlan(baritone.getPathingBehavior().getMacroPlan().orElse(null));
    sampleFarfield(baritone.getPathingBehavior().getRenderableFarfield().orElse(null));
    TransportSnapshot snapshot = baritone.getPathingBehavior().transportSnapshot();
    increment(actualTicks, snapshot.actual());
    TransportSnapshot.Plan plan = snapshot.current().current();
    if (plan != null && plan.mode() != null) {
      increment(plannedTicks, plan.mode());
      sawPlannedBoat |= plan.mode().boat();
      sawPlannedHorse |= plan.mode() == TransportMode.HORSE;
    }
    if (snapshot.control() != null && snapshot.control().targetYaw() != null) {
      targetYaw.sample(snapshot.control().targetYaw(), moveForward);
    }
    samplePathYaw(snapshot, moveForward);
    sampleSequence(snapshot.current().sequence());
    sampleSequence(snapshot.next().sequence());
  }

  private void sampleYaw(LocalPlayer player, boolean moveForward) {
    playerYaw.sample(player.getYRot(), moveForward || player.getDeltaMovement().horizontalDistanceSqr() > 0.0004D);
    if (player.getVehicle() != null) {
      vehicleYaw.sample(player.getVehicle().getYRot(), moveForward || player.getVehicle().getDeltaMovement().horizontalDistanceSqr() > 0.0004D);
    }
  }

  private void samplePathYaw(TransportSnapshot snapshot, boolean moveForward) {
    TransportSnapshot.Plan plan = snapshot.current().current();
    if (plan == null || plan.src() == null || plan.dest() == null) {
      return;
    }
    int dx = plan.dest().x - plan.src().x;
    int dz = plan.dest().z - plan.src().z;
    if (dx == 0 && dz == 0) {
      return;
    }
    pathYaw.sample(Math.atan2(dx, dz) * Mth.RAD_TO_DEG, moveForward);
  }

  boolean sawDimensionChange() {
    return sawDimensionChange;
  }

  private void sampleDimension(LocalPlayer player) {
    finalDimension = player.level().dimension().toString();
    if (initialDimension == null) {
      initialDimension = finalDimension;
    }
    sawDimensionChange |= !finalDimension.equals(initialDimension);
  }

  boolean calcFailed() {
    return calcFailed;
  }

  boolean sawWater() {
    return sawWater;
  }

  private static boolean mountedHorseWater(LocalPlayer player) {
    if (!(player.getVehicle() instanceof AbstractHorse horse)) {
      return false;
    }
    int x = Mth.floor(horse.getX());
    int y = Mth.floor(horse.getBoundingBox().minY + 0.01D);
    int z = Mth.floor(horse.getZ());
    return horse.isInWater() || horse.isUnderWater() || player.level().getFluidState(new BlockPos(x, y, z)).is(FluidTags.WATER)
      || player.level().getFluidState(new BlockPos(x, y - 1, z)).is(FluidTags.WATER);
  }

  private void sampleHorseClosestApproach(AbstractHorse horse) {
    double distance = distanceToGoal(horse.position());
    if (distance < closestHorseDistanceToGoal) {
      closestHorseDistanceToGoal = distance;
      closestHorsePos = horse.position();
      closestHorseTick = ticks;
    }
  }

  boolean sawPathing() {
    return sawPathing;
  }

  boolean sawBuilder() {
    return sawBuilder;
  }

  boolean sawBoat() {
    return sawBoat;
  }

  boolean sawHorse() {
    return sawHorse;
  }

  boolean lostHorseAfterEncounter() {
    return lostHorseAfterEncounter;
  }

  int jumpTicks() {
    return jumpTicks;
  }

  int firstPathingTick() {
    return firstPathingTick;
  }

  int firstPlanningTick() {
    return firstPlanningTick;
  }

  int firstActuationTick() {
    return firstActuationTick;
  }

  int firstRouteTick() {
    return firstRouteTick;
  }

  int firstMacroPlanTick() {
    return firstMacroPlanTick;
  }

  int totalStalledTicks() {
    return totalStalledTicks;
  }

  int postActuationStalledTicks() {
    return postActuationStalledTicks;
  }

  boolean sawMacroRoute() {
    return sawMacroRoute;
  }

  boolean sawMacroBiomeRoute() {
    return sawMacroBiomeRoute;
  }

  boolean sawPlannedBoat() {
    return sawPlannedBoat;
  }

  boolean sawPlannedHorse() {
    return sawPlannedHorse;
  }

  boolean sawMacroPlanBoat() {
    return sawMacroPlanBoat;
  }

  int maxMacroBoatLegs() {
    return maxMacroBoatLegs;
  }

  double maxMacroBoatDistance() {
    return maxMacroBoatDistance;
  }

  int maxMacroPlanBoatActions() {
    return maxMacroPlanBoatActions;
  }

  double maxMacroPlanBoatDistance() {
    return maxMacroPlanBoatDistance;
  }

  boolean sawMacroPlanSurfaceTransition() {
    return sawMacroPlanSurfaceTransition;
  }

  int maxMacroPlanSurfaceActions() {
    return maxMacroPlanSurfaceActions;
  }

  double maxMacroPlanSurfaceDistance() {
    return maxMacroPlanSurfaceDistance;
  }

  boolean sawMacroPlanPortal() {
    return sawMacroPlanPortal;
  }

  int maxMacroPlanPortalActions() {
    return maxMacroPlanPortalActions;
  }

  int maxMacroPlanPortalBuildExitActions() {
    return maxMacroPlanPortalBuildExitActions;
  }

  double maxMacroPlanNetherDistanceBeforePortalExit() {
    return maxMacroPlanNetherDistanceBeforePortalExit;
  }

  double routeDestDistance(PlaytestScenario.GoalSpec goal) {
    return bestRouteDestDistance;
  }

  double macroBiomeUnknownFraction() {
    int total = maxMacroBiomeFactualCells + maxMacroBiomeUnknownCells;
    return total == 0 ? 0D : maxMacroBiomeUnknownCells / (double) total;
  }

  boolean tookDamage() {
    return !Float.isNaN(initialHealth) && minHealth + 1.0E-4F < initialHealth;
  }

  private void sampleInventoryUse(LocalPlayer player) {
    int pickaxeDamage = toolDamage(player, "pickaxe");
    initialPickaxeDamage = initialDamage(initialPickaxeDamage, pickaxeDamage);
    maxPickaxeDamage = maxDamage(maxPickaxeDamage, pickaxeDamage);
    int axeDamage = toolDamage(player, "axe");
    initialAxeDamage = initialDamage(initialAxeDamage, axeDamage);
    maxAxeDamage = maxDamage(maxAxeDamage, axeDamage);
    int shovelDamage = toolDamage(player, "shovel");
    initialShovelDamage = initialDamage(initialShovelDamage, shovelDamage);
    maxShovelDamage = maxDamage(maxShovelDamage, shovelDamage);
    int cobblestone = itemCount(player, "minecraft:cobblestone");
    if (initialCobblestone == Integer.MIN_VALUE) {
      initialCobblestone = cobblestone;
    }
    minCobblestone = Math.min(minCobblestone, cobblestone);
  }

  private static int initialDamage(int initial, int current) {
    return initial == TOOL_DAMAGE_UNSET && current >= 0 ? current : initial;
  }

  private static int maxDamage(int max, int current) {
    return current < 0 ? max : Math.max(max, current);
  }

  private static int toolDamage(LocalPlayer player, String kind) {
    int max = -1;
    for (int i = 0; i < 9; i++) {
      ItemStack stack = player.getInventory().getItem(i);
      if (!stack.isEmpty() && itemId(stack).endsWith("_" + kind)) {
        max = Math.max(max, stack.getDamageValue());
      }
    }
    return max;
  }

  private static int itemCount(LocalPlayer player, String itemId) {
    int count = 0;
    for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
      ItemStack stack = player.getInventory().getItem(i);
      if (!stack.isEmpty() && itemId(stack).equals(itemId)) {
        count += stack.getCount();
      }
    }
    return count;
  }

  private static String itemId(ItemStack stack) {
    return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
  }

  private static int damageDelta(int initial, int max) {
    return initial < 0 || max < 0 ? 0 : Math.max(0, max - initial);
  }

  private int cobblestoneSpent() {
    return initialCobblestone == Integer.MIN_VALUE || minCobblestone == Integer.MAX_VALUE ? 0 : Math.max(0, initialCobblestone - minCobblestone);
  }

  private static boolean forced(Baritone baritone, Input input) {
    return baritone.getInputOverrideHandler().isInputForcedDown(input);
  }

  private static boolean pathingInputForced(Baritone baritone) {
    for (Input input : Input.values()) {
      if (forced(baritone, input)) {
        return true;
      }
    }
    return false;
  }

  private static boolean activeWork(Baritone baritone) {
    return baritone.getPathingBehavior().isPathing() || baritone.getPathingBehavior().getInProgress().isPresent() || baritone.getPathingBehavior().getPlanningStart().isPresent()
      || baritone.getBuilderProcess().isActive() || baritone.getPortalTaskProcess().isActive();
  }

  private void sampleStall(LocalPlayer player, boolean activeWork, boolean actuating) {
    if (!activeWork || actuating || physicallyMoving(player)) {
      currentStallTicks = 0;
      return;
    }
    totalStalledTicks++;
    if (firstActuationTick < 0) {
      preActuationStalledTicks++;
    } else {
      postActuationStalledTicks++;
    }
    if (currentStallTicks++ == 0) {
      stallEpisodes++;
    }
    maxStallTicks = Math.max(maxStallTicks, currentStallTicks);
  }

  private static boolean physicallyMoving(LocalPlayer player) {
    Vec3 delta = player.getVehicle() == null ? player.getDeltaMovement() : player.getVehicle().getDeltaMovement();
    return delta.lengthSqr() > 1.0E-4D;
  }

  private void sampleRoute(RouteExecutor executor, Baritone baritone) {
    if (executor == null || executor.getPath() != null) {
      return;
    }
    firstRouteTick = firstSeen(firstRouteTick);
    sawMacroRoute = true;
    int boatLegs = 0;
    double boatDistance = 0D;
    for (RouteLeg leg : executor.route().legs()) {
      if (leg instanceof SurfaceRouteLeg surface && surface.segment().mode().boat()) {
        boatLegs++;
        boatDistance += surface.segment().length();
      }
    }
    if (boatLegs > 0) {
      sawPlannedBoat = true;
      if (boatLegs > maxMacroBoatLegs || boatDistance > maxMacroBoatDistance) {
        maxMacroBoatLegs = Math.max(maxMacroBoatLegs, boatLegs);
        maxMacroBoatDistance = Math.max(maxMacroBoatDistance, boatDistance);
        sampleSequence(executor.transportSequence(baritone.getPlayerContext(), 32));
      }
    }
    double distance = routeDestDistance(executor.dest(), scenario.goal());
    if (distance < bestRouteDestDistance) {
      bestRouteDestDistance = distance;
      bestRouteDest = executor.dest();
    }
  }

  private void sampleMacroPlan(MacroPlan plan) {
    if (plan == null) {
      return;
    }
    firstMacroPlanTick = firstSeen(firstMacroPlanTick);
    sawMacroBiomeRoute = true;
    lastMacroBiomeSequence = plan.sequence();
    bestMacroBiomeEstimatedTicks = Math.min(bestMacroBiomeEstimatedTicks, plan.totalVector().timeTicks());
    int boatActions = plan.boatActions();
    double boatDistance = plan.boatDistance();
    int surfaceActions = plan.surfaceTransitionActions();
    double surfaceDistance = plan.surfaceTransitionDistance();
    int portalActions = plan.portalActions();
    PortalPlanSample portalSample = portalPlanSample(plan);
    sawMacroPlanBoat |= boatActions > 0;
    sawMacroPlanSurfaceTransition |= surfaceActions > 0;
    sawMacroPlanPortal |= portalActions > 0;
    maxMacroPlanBoatActions = Math.max(maxMacroPlanBoatActions, boatActions);
    maxMacroPlanBoatDistance = Math.max(maxMacroPlanBoatDistance, boatDistance);
    maxMacroPlanSurfaceActions = Math.max(maxMacroPlanSurfaceActions, surfaceActions);
    maxMacroPlanSurfaceDistance = Math.max(maxMacroPlanSurfaceDistance, surfaceDistance);
    maxMacroPlanPortalActions = Math.max(maxMacroPlanPortalActions, portalActions);
    maxMacroPlanPortalBuildExitActions = Math.max(maxMacroPlanPortalBuildExitActions, portalSample.buildExitActions());
    maxMacroPlanNetherDistanceBeforePortalExit = Math.max(maxMacroPlanNetherDistanceBeforePortalExit, portalSample.netherDistanceBeforeBuildExit());
    maxMacroBiomeFactualCells = Math.max(maxMacroBiomeFactualCells, plan.factualCells());
    maxMacroBiomeUnknownCells = Math.max(maxMacroBiomeUnknownCells, plan.unknownCells());
    maxMacroBiomeLiveCells = Math.max(maxMacroBiomeLiveCells, plan.liveCells());
    maxMacroBiomeCachedCells = Math.max(maxMacroBiomeCachedCells, plan.cachedCells());
    maxMacroBiomePredictedCells = Math.max(maxMacroBiomePredictedCells, plan.predictedCells());
    maxMacroBiomePriorCells = Math.max(maxMacroBiomePriorCells, plan.priorCells());
  }

  private void sampleFarfield(FarfieldObjective objective) {
    if (objective == null) {
      return;
    }
    sawFarfieldRoute = true;
    bestFarfieldEstimatedTicks = Math.min(bestFarfieldEstimatedTicks, objective.expectedAtStart());
    maxFarfieldLiveStates = Math.max(maxFarfieldLiveStates, objective.snapshot().liveStates());
    maxFarfieldCachedStates = Math.max(maxFarfieldCachedStates, objective.snapshot().cachedStates());
    maxFarfieldPriorStates = Math.max(maxFarfieldPriorStates, objective.snapshot().priorStates());
    maxFarfieldStates = Math.max(maxFarfieldStates, objective.snapshot().stateCount());
    lastFarfieldStartStratum = objective.startStratum();
    lastFarfieldTargetStratum = objective.snapshot().targetStratum();
  }

  private void sampleSequence(String sequence) {
    if (sequence == null || sequence.isEmpty()) {
      return;
    }
    lastRouteSequence = sequence;
    sawPlannedBoat |= sequence.indexOf('b') >= 0 || sequence.indexOf('B') >= 0;
    sawPlannedHorse |= sequence.indexOf('H') >= 0;
  }

  private static PortalPlanSample portalPlanSample(MacroPlan plan) {
    int buildExit = 0;
    double netherSincePortalEnter = 0D;
    double bestNetherBeforeExit = 0D;
    boolean postEnter = false;
    for (MacroActionInstance action : plan.actions()) {
      if (entersNether(action)) {
        postEnter = true;
        netherSincePortalEnter = 0D;
        continue;
      }
      if (postEnter && action.kind() == MacroActionKind.SURFACE_TRAVERSE && MacroNodeKey.dimensionId(action.fromNode()) == MacroNodeKey.DIMENSION_NETHER
        && MacroNodeKey.dimensionId(action.toNode()) == MacroNodeKey.DIMENSION_NETHER && action.renderPositions().size() >= 2) {
        netherSincePortalEnter += flatDistance(action.renderPositions().get(0), action.renderPositions().get(action.renderPositions().size() - 1));
      }
      if (action.kind() == MacroActionKind.PORTAL_BUILD_EXIT) {
        buildExit++;
        bestNetherBeforeExit = Math.max(bestNetherBeforeExit, netherSincePortalEnter);
        postEnter = false;
      }
    }
    return new PortalPlanSample(buildExit, bestNetherBeforeExit);
  }

  private static boolean entersNether(MacroActionInstance action) {
    return (action.kind() == MacroActionKind.PORTAL_ENTER || action.kind() == MacroActionKind.PORTAL_BUILD_ENTER) && MacroNodeKey.dimensionId(action.toNode()) == MacroNodeKey.DIMENSION_NETHER;
  }

  private int firstSeen(int current) {
    return current >= 0 ? current : ticks;
  }

  private static double routeDestDistance(BetterBlockPos dest, PlaytestScenario.GoalSpec goal) {
    double dx = dest.x - goal.x();
    double dz = dest.z - goal.z();
    if (goal.xz()) {
      return Math.sqrt(dx * dx + dz * dz);
    }
    double dy = dest.y - goal.y();
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  private static double flatDistance(BetterBlockPos a, BetterBlockPos b) {
    double dx = a.x - b.x;
    double dz = a.z - b.z;
    return Math.sqrt(dx * dx + dz * dz);
  }

  private record PortalPlanSample(int buildExitActions, double netherDistanceBeforeBuildExit) {
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
      String codeStamp = PlaytestHarnessBehavior.codeStamp();
      String expectedCodeStamp = scenario.harness().expectedCodeStamp();
      json.addProperty("playtestCodeStamp", codeStamp);
      json.addProperty("expectedCodeStamp", expectedCodeStamp == null ? "" : expectedCodeStamp);
      json.addProperty("codeStampMatched", scenario.harness().acceptsCodeStamp(codeStamp));
      GoldenTuning.Metadata goldenTuning = GoldenTuning.metadata();
      JsonObject goldenTuningJson = new JsonObject();
      goldenTuningJson.addProperty("profile", goldenTuning.profile());
      goldenTuningJson.addProperty("digest", goldenTuning.digest());
      goldenTuningJson.addProperty("builtin", goldenTuning.builtin());
      json.add("goldenTuning", goldenTuningJson);
      json.addProperty("goldenTuningProfile", goldenTuning.profile());
      json.addProperty("goldenTuningDigest", goldenTuning.digest());
      json.addProperty("goldenTuningBuiltin", goldenTuning.builtin());
      json.addProperty("initialActualDimension", initialDimension);
      json.addProperty("finalActualDimension", finalDimension);
      json.addProperty("sawDimensionChange", sawDimensionChange);
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
        if (player.getVehicle() != null) {
          json.add("vehiclePos", position(player.getVehicle().position()));
          json.addProperty("vehicleOnGround", player.getVehicle().onGround());
          json.addProperty("vehicleDistanceToGoal", distanceToGoal(player.getVehicle().position()));
        }
      }
      json.addProperty("closestHorseDistanceToGoal", closestHorseDistanceToGoal == Double.POSITIVE_INFINITY ? null : closestHorseDistanceToGoal);
      json.add("closestHorsePos", closestHorsePos == null ? JsonNull.INSTANCE : position(closestHorsePos));
      json.addProperty("closestHorseTick", closestHorseTick < 0 ? null : closestHorseTick);
      json.addProperty("initialHealth", Float.isNaN(initialHealth) ? null : initialHealth);
      json.addProperty("minHealth", minHealth == Float.POSITIVE_INFINITY ? null : minHealth);
      json.addProperty("tookDamage", tookDamage());
      json.addProperty("initialFood", initialFood == Integer.MIN_VALUE ? null : initialFood);
      json.addProperty("minFood", minFood == Integer.MAX_VALUE ? null : minFood);
      json.addProperty("moveForwardTicks", moveForwardTicks);
      json.addProperty("jumpTicks", jumpTicks);
      json.addProperty("sprintTicks", sprintTicks);
      json.addProperty("sprintInputTicks", sprintInputTicks);
      json.addProperty("sneakTicks", sneakTicks);
      json.add("playerYaw", playerYaw.json());
      json.add("vehicleYaw", vehicleYaw.json());
      json.add("targetYaw", targetYaw.json());
      json.add("pathYaw", pathYaw.json());
      json.add("yawExcessRatio", yawExcessRatio());
      json.addProperty("pickaxeDamageDelta", damageDelta(initialPickaxeDamage, maxPickaxeDamage));
      json.addProperty("axeDamageDelta", damageDelta(initialAxeDamage, maxAxeDamage));
      json.addProperty("shovelDamageDelta", damageDelta(initialShovelDamage, maxShovelDamage));
      json.addProperty("toolDamageDelta", damageDelta(initialPickaxeDamage, maxPickaxeDamage) + damageDelta(initialAxeDamage, maxAxeDamage) + damageDelta(initialShovelDamage, maxShovelDamage));
      json.addProperty("cobblestoneSpent", cobblestoneSpent());
      json.add("actualModeTicks", modeTicks(actualTicks));
      json.add("plannedModeTicks", modeTicks(plannedTicks));
      json.addProperty("pathEvents", pathEvents);
      json.add("pathEventCounts", pathEventCounts());
      json.addProperty("nextCalcFailures", nextCalcFailures);
      json.addProperty("lastPathEvent", lastPathEvent == null ? null : lastPathEvent.name());
      json.addProperty("lastPathingFailure", baritone.getPathingBehavior().lastPathingFailure());
      json.addProperty("lastNextPathingFailure", baritone.getPathingBehavior().lastNextPathingFailure());
      JsonArray recentNextPathingFailures = new JsonArray();
      for (String failure : baritone.getPathingBehavior().recentNextPathingFailures()) {
        recentNextPathingFailures.add(failure);
      }
      json.add("recentNextPathingFailures", recentNextPathingFailures);
      json.addProperty("lastRouteFailure", baritone.getPathingBehavior().lastRouteFailure());
      json.addProperty("calcFailed", calcFailed);
      json.addProperty("firstPlanningTick", firstPlanningTick < 0 ? null : firstPlanningTick);
      json.addProperty("firstActuationTick", firstActuationTick < 0 ? null : firstActuationTick);
      json.addProperty("firstPathingTick", firstPathingTick < 0 ? null : firstPathingTick);
      json.addProperty("firstRouteTick", firstRouteTick < 0 ? null : firstRouteTick);
      json.addProperty("firstMacroPlanTick", firstMacroPlanTick < 0 ? null : firstMacroPlanTick);
      json.addProperty("totalStalledTicks", totalStalledTicks);
      json.addProperty("totalStalledSeconds", totalStalledTicks / 20D);
      json.addProperty("preActuationStalledTicks", preActuationStalledTicks);
      json.addProperty("postActuationStalledTicks", postActuationStalledTicks);
      json.addProperty("stallEpisodes", stallEpisodes);
      json.addProperty("maxStallTicks", maxStallTicks);
      json.addProperty("sawPathing", sawPathing);
      json.addProperty("sawBuilder", sawBuilder);
      json.addProperty("sawWater", sawWater);
      json.addProperty("sawBoat", sawBoat);
      json.addProperty("sawHorse", sawHorse);
      json.addProperty("lostHorseAfterEncounter", lostHorseAfterEncounter);
      json.addProperty("sawMacroRoute", sawMacroRoute);
      json.addProperty("sawMacroBiomeRoute", sawMacroBiomeRoute);
      json.addProperty("sawPlannedBoat", sawPlannedBoat);
      json.addProperty("sawPlannedHorse", sawPlannedHorse);
      json.addProperty("maxMacroBoatLegs", maxMacroBoatLegs);
      json.addProperty("maxMacroBoatDistance", maxMacroBoatDistance);
      json.addProperty("bestRouteDestDistance", bestRouteDestDistance == Double.POSITIVE_INFINITY ? null : bestRouteDestDistance);
      json.add("bestRouteDest", bestRouteDest == null ? JsonNull.INSTANCE : block(bestRouteDest.x, bestRouteDest.y, bestRouteDest.z));
      json.addProperty("lastRouteSequence", lastRouteSequence);
      json.addProperty("lastMacroBiomeSequence", lastMacroBiomeSequence);
      json.addProperty("bestMacroBiomeEstimatedTicks", bestMacroBiomeEstimatedTicks == Double.POSITIVE_INFINITY ? null : bestMacroBiomeEstimatedTicks);
      json.addProperty("sawMacroPlanBoat", sawMacroPlanBoat);
      json.addProperty("maxMacroPlanBoatActions", maxMacroPlanBoatActions);
      json.addProperty("maxMacroPlanBoatDistance", maxMacroPlanBoatDistance);
      json.addProperty("sawMacroPlanSurfaceTransition", sawMacroPlanSurfaceTransition);
      json.addProperty("maxMacroPlanSurfaceActions", maxMacroPlanSurfaceActions);
      json.addProperty("maxMacroPlanSurfaceDistance", maxMacroPlanSurfaceDistance);
      json.addProperty("sawMacroPlanPortal", sawMacroPlanPortal);
      json.addProperty("maxMacroPlanPortalActions", maxMacroPlanPortalActions);
      json.addProperty("maxMacroPlanPortalBuildExitActions", maxMacroPlanPortalBuildExitActions);
      json.addProperty("maxMacroPlanNetherDistanceBeforePortalExit", maxMacroPlanNetherDistanceBeforePortalExit);
      json.addProperty("maxMacroBiomeFactualCells", maxMacroBiomeFactualCells);
      json.addProperty("maxMacroBiomeUnknownCells", maxMacroBiomeUnknownCells);
      json.addProperty("maxMacroBiomeLiveCells", maxMacroBiomeLiveCells);
      json.addProperty("maxMacroBiomeCachedCells", maxMacroBiomeCachedCells);
      json.addProperty("maxMacroBiomePredictedCells", maxMacroBiomePredictedCells);
      json.addProperty("maxMacroBiomePriorCells", maxMacroBiomePriorCells);
      json.addProperty("macroBiomeUnknownFraction", macroBiomeUnknownFraction());
      json.addProperty("sawFarfieldRoute", sawFarfieldRoute);
      json.addProperty("bestFarfieldEstimatedTicks", bestFarfieldEstimatedTicks == Double.POSITIVE_INFINITY ? null : bestFarfieldEstimatedTicks);
      json.addProperty("maxFarfieldLiveStates", maxFarfieldLiveStates);
      json.addProperty("maxFarfieldCachedStates", maxFarfieldCachedStates);
      json.addProperty("maxFarfieldPriorStates", maxFarfieldPriorStates);
      json.addProperty("maxFarfieldStates", maxFarfieldStates);
      json.addProperty("lastFarfieldStartStratum", lastFarfieldStartStratum < 0 ? null : lastFarfieldStartStratum);
      json.addProperty("lastFarfieldTargetStratum", lastFarfieldTargetStratum < 0 ? null : lastFarfieldTargetStratum);
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
    double dz = position.z - (scenario.goal().z() + 0.5D);
    if (scenario.goal().xz()) {
      return Math.sqrt(dx * dx + dz * dz);
    }
    double dy = position.y - scenario.goal().y();
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  private static int boatItems(LocalPlayer player) {
    return player.getInventory().getNonEquipmentItems().stream().filter(stack -> stack.getItem() instanceof BoatItem).mapToInt(ItemStack::getCount).sum();
  }

  private JsonObject yawExcessRatio() {
    JsonObject json = new JsonObject();
    nullable(json, "player", ratio(playerYaw.totalVariation(), pathYaw.totalVariation()));
    nullable(json, "playerMoving", ratio(playerYaw.movingTotalVariation(), pathYaw.movingTotalVariation()));
    nullable(json, "vehicle", ratio(vehicleYaw.totalVariation(), pathYaw.totalVariation()));
    nullable(json, "vehicleMoving", ratio(vehicleYaw.movingTotalVariation(), pathYaw.movingTotalVariation()));
    nullable(json, "target", ratio(targetYaw.totalVariation(), pathYaw.totalVariation()));
    nullable(json, "targetMoving", ratio(targetYaw.movingTotalVariation(), pathYaw.movingTotalVariation()));
    return json;
  }

  private static Double ratio(double numerator, double denominator) {
    return denominator <= 1.0E-6D ? null : numerator / denominator;
  }

  private static void nullable(JsonObject json, String name, Double value) {
    if (value == null) {
      json.add(name, JsonNull.INSTANCE);
    } else {
      json.addProperty(name, value);
    }
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

  private JsonObject pathEventCounts() {
    JsonObject json = new JsonObject();
    for (PathEvent event : PathEvent.values()) {
      json.addProperty(event.name(), pathEventCounts.getOrDefault(event, 0));
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

  private static final class YawMetrics {
    private boolean seen;
    private double lastYaw;
    private double lastDelta;
    private double lastMovingDelta;
    private int samples;
    private int movingSamples;
    private double totalVariation;
    private double movingTotalVariation;
    private double maxTickDelta;
    private double movingMaxTickDelta;
    private int reversals;
    private int movingReversals;

    void sample(double yaw, boolean moving) {
      samples++;
      if (moving) {
        movingSamples++;
      }
      if (!seen) {
        seen = true;
        lastYaw = yaw;
        return;
      }
      double delta = normalizeYawDelta(yaw - lastYaw);
      double magnitude = Math.abs(delta);
      totalVariation += magnitude;
      maxTickDelta = Math.max(maxTickDelta, magnitude);
      reversals += reversal(lastDelta, delta);
      if (magnitude >= YAW_REVERSAL_DEGREES) {
        lastDelta = delta;
      }
      if (moving) {
        movingTotalVariation += magnitude;
        movingMaxTickDelta = Math.max(movingMaxTickDelta, magnitude);
        movingReversals += reversal(lastMovingDelta, delta);
        if (magnitude >= YAW_REVERSAL_DEGREES) {
          lastMovingDelta = delta;
        }
      }
      lastYaw = yaw;
    }

    double totalVariation() {
      return totalVariation;
    }

    double movingTotalVariation() {
      return movingTotalVariation;
    }

    JsonObject json() {
      JsonObject json = new JsonObject();
      json.addProperty("samples", samples);
      json.addProperty("movingSamples", movingSamples);
      json.addProperty("totalVariation", totalVariation);
      json.addProperty("movingTotalVariation", movingTotalVariation);
      json.addProperty("maxTickDelta", maxTickDelta);
      json.addProperty("movingMaxTickDelta", movingMaxTickDelta);
      json.addProperty("reversals", reversals);
      json.addProperty("movingReversals", movingReversals);
      return json;
    }

    private static int reversal(double previous, double current) {
      return Math.abs(previous) >= YAW_REVERSAL_DEGREES && Math.abs(current) >= YAW_REVERSAL_DEGREES && Math.signum(previous) != Math.signum(current) ? 1 : 0;
    }

    private static double normalizeYawDelta(double delta) {
      double normalized = delta % 360D;
      if (normalized >= 180D) {
        normalized -= 360D;
      } else if (normalized < -180D) {
        normalized += 360D;
      }
      return normalized;
    }
  }

  enum TerminalReason {
    SUCCESS, TIMEOUT, SETUP_TIMEOUT, DEATH, DISCONNECT, CALC_FAILED, PATH_STOPPED, COMMAND_FAILED, EXCEPTION, STALE_CLIENT, ACCEPTANCE_DAMAGE, ACCEPTANCE_DISMOUNT, ACCEPTANCE_JUMP_BUDGET, ACCEPTANCE_STALL_BUDGET
  }
}
