package baritone.pathing.mounted;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import baritone.pathing.calc.BestExitGoal;
import baritone.pathing.calc.BlockKey;
import baritone.pathing.calc.PathingProfiler;
import baritone.pathing.goal.GoalTerminalPolicy;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.route.HorseRouteLeg;
import baritone.pathing.route.PlannedTransportState;
import baritone.pathing.route.RouteLeg;
import baritone.pathing.route.RoutePlan;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.AbstractHorse;

public final class HorseRoutePlanner {
  private static final int EXPANSION_BATCH = 128;
  private static final int GOAL_XZ_PLAN_RADIUS = GoalTerminalPolicy.HORSE_GOAL_XZ_RADIUS;
  private static final int OBSTRUCTED_GOAL_XZ_PLAN_RADIUS = GoalTerminalPolicy.HORSE_OBSTRUCTED_GOAL_XZ_RADIUS;
  private static final long DEFAULT_FAILURE_TIMEOUT_MS = 12_000L;
  private static final double ROUTE_LEG_MAX_TICKS = 48D;
  private static final int ROUTE_LEG_MAX_EDGES = 24;
  private static final double MIN_BLOCKS_PER_SECOND = 5.5D;
  private static final double MOVEMENT_SPEED_TO_BLOCKS_PER_SECOND = 43.17D;
  private static final double GRAVITY = 0.08D;
  private static final double VERTICAL_DRAG = 0.98D;
  private static final int WATER_PIT_SCAN_BLOCKS = 8;
  private static final int FATAL_DIAGONAL_STEP_OVERRUN_DROP_BLOCKS = 2;

  private HorseRoutePlanner() {
  }

  private static MountTuning.Planner tuning() {
    return MountTuning.current().planner();
  }

  private static int lineCertificationSamplesPerBlock() {
    return tuning().lineCertificationSamplesPerBlock();
  }

  public static double waterWadeCostMultiplier() {
    return tuning().waterWadeCostMultiplier();
  }

  private static double damageHazardApproachMargin() {
    return tuning().damageHazardApproachMargin();
  }

  public static boolean mountedHorse(CalculationContext context) {
    return context.getBaritone().getPlayerContext().player().getVehicle() instanceof AbstractHorse;
  }

  public static double ticksPerBlock(AbstractHorse horse) {
    return 20D / Math.max(MIN_BLOCKS_PER_SECOND, horse.getAttributeValue(Attributes.MOVEMENT_SPEED) * MOVEMENT_SPEED_TO_BLOCKS_PER_SECOND);
  }

  public static Start physicalStart(AbstractHorse horse) {
    return Start.physical(HorsePath.vehicleFeet(horse), horse.getX(), horse.getZ());
  }

  public static Start certifiedStart(HorsePath.Waypoint waypoint) {
    return Start.certified(waypoint.pos(), waypoint.centerX(), waypoint.centerZ());
  }

  public static Result plan(CalculationContext context, Goal goal, long failureTimeoutMS) {
    return plan(context, goal, failureTimeoutMS, null);
  }

  public static Result plan(CalculationContext context, Goal goal, long failureTimeoutMS, IncumbentSink incumbentSink) {
    Entity vehicle = context.getBaritone().getPlayerContext().player().getVehicle();
    if (!(vehicle instanceof AbstractHorse horse)) {
      return Result.failure("not mounted on a horse");
    }
    return plan(context, HorsePath.vehicleFeet(horse), goal, failureTimeoutMS, incumbentSink);
  }

  public static Result plan(CalculationContext context, BetterBlockPos start, Goal goal, long failureTimeoutMS, IncumbentSink incumbentSink) {
    Entity vehicle = context.getBaritone().getPlayerContext().player().getVehicle();
    if (!(vehicle instanceof AbstractHorse horse)) {
      return Result.failure("not mounted on a horse");
    }
    BetterBlockPos physicalFeet = HorsePath.vehicleFeet(horse);
    boolean physicalStart = nearPhysicalHorseStart(physicalFeet, start);
    start = physicalStart ? physicalFeet : start;
    double startCenterX = physicalStart ? horse.getX() : start.x;
    double startCenterZ = physicalStart ? horse.getZ() : start.z;
    return plan(context, new Start(start, startCenterX, startCenterZ, physicalStart), goal, failureTimeoutMS, incumbentSink);
  }

  public static Result plan(CalculationContext context, Start start, Goal goal, long failureTimeoutMS, IncumbentSink incumbentSink) {
    Entity vehicle = context.getBaritone().getPlayerContext().player().getVehicle();
    if (!(vehicle instanceof AbstractHorse horse)) {
      return Result.failure("not mounted on a horse");
    }
    HorseSpec spec = HorseSpec.capture(horse);
    long timeout = failureTimeoutMS <= 0L ? DEFAULT_FAILURE_TIMEOUT_MS : failureTimeoutMS;
    MountTuning.Planner tuning = tuning();
    if (tuning.groundFirstTimeoutMs() > 0) {
      Search groundSearch = new Search(context, spec, start.pos(), start.centerX(), start.centerZ(), start.physical(), goal, Math.min(timeout, tuning.groundFirstTimeoutMs()), null);
      SearchResult groundPath = groundSearch.calculate();
      if (groundPath.path().isPresent() && pathCompletesGoal(goal, groundPath.path().get())) {
        boolean boatAvailable = ((Baritone) context.getBaritone()).getInventoryBehavior().hasBoat();
        return Result.success(routePlan(goal, groundPath.path().get(), boatAvailable));
      }
    }
    Search search = new Search(context, spec, start.pos(), start.centerX(), start.centerZ(), start.physical(), goal, timeout, incumbentSink);
    SearchResult path = search.calculate();
    if (path.incumbentAccepted()) {
      return Result.acceptedIncumbent();
    }
    boolean boatAvailable = ((Baritone) context.getBaritone()).getInventoryBehavior().hasBoat();
    return path.path().map(horsePath -> Result.success(routePlan(goal, horsePath, boatAvailable))).orElseGet(() -> Result.failure(path.diagnostic()));
  }

  private static boolean pathCompletesGoal(Goal goal, HorsePath path) {
    HorsePath.Waypoint dest = path.waypoints().getLast();
    if (goal instanceof GoalXZ xz) {
      double radius = GoalTerminalPolicy.horseGoalXZPlanningRadius(xz, GOAL_XZ_PLAN_RADIUS);
      double dx = dest.centerX() - xz.getX();
      double dz = dest.centerZ() - xz.getZ();
      return dx * dx + dz * dz <= radius * radius;
    }
    BetterBlockPos pos = dest.pos();
    return goal.isInGoal(pos.x, pos.y, pos.z);
  }

  private static boolean nearPhysicalHorseStart(BetterBlockPos physicalFeet, BetterBlockPos requestedStart) {
    if (physicalFeet.equals(requestedStart)) {
      return true;
    }
    if (Math.abs(physicalFeet.y - requestedStart.y) > 1) {
      return false;
    }
    int dx = physicalFeet.x - requestedStart.x;
    int dz = physicalFeet.z - requestedStart.z;
    return dx * dx + dz * dz <= 1;
  }

  public static RoutePlan routePlan(Goal goal, HorsePath path, boolean boatAvailable) {
    PlannedTransportState state = PlannedTransportState.horse(boatAvailable);
    ArrayList<RouteLeg> legs = new ArrayList<>();
    MountTuning.Planner tuning = tuning();
    for (HorsePath leg : path.split(tuning.routeLegMaxTicks(), tuning.routeLegMaxEdges())) {
      legs.add(new HorseRouteLeg(leg, state, state));
    }
    return RoutePlan.of(legs, state, state, continuation(goal, path.dest()));
  }

  private static double continuation(Goal goal, BetterBlockPos dest) {
    if (goal instanceof BestExitGoal exit) {
      return goal.isInGoal(dest) ? exit.exactGoalExitValue(dest.x, dest.y, dest.z) : exit.exitValue(dest.x, dest.y, dest.z);
    }
    return Double.NaN;
  }

  @FunctionalInterface
  public interface IncumbentSink {
    boolean publish(Incumbent incumbent);
  }

  public record Result(Optional<RoutePlan> route, String diagnostic, boolean incumbentAccepted) {
    private static Result success(RoutePlan route) {
      return new Result(Optional.of(route), "ok", false);
    }

    private static Result failure(String diagnostic) {
      return new Result(Optional.empty(), diagnostic, false);
    }

    private static Result acceptedIncumbent() {
      return new Result(Optional.empty(), "incumbent accepted", true);
    }
  }

  public record Incumbent(HorsePath path, boolean boundaryExit) {
    public Incumbent {
      if (path == null) {
        throw new IllegalArgumentException("horse incumbent path must not be null");
      }
    }
  }

  public record Start(BetterBlockPos pos, double centerX, double centerZ, boolean physical) {
    public Start {
      if (pos == null) {
        throw new IllegalArgumentException("horse route start position must not be null");
      }
      if (!Double.isFinite(centerX) || !Double.isFinite(centerZ)) {
        throw new IllegalArgumentException("horse route start center must be finite: " + centerX + "," + centerZ);
      }
      if (Mth.floor(centerX) != pos.x || Mth.floor(centerZ) != pos.z) {
        throw new IllegalArgumentException("horse route start center " + centerX + "," + centerZ + " outside " + pos);
      }
    }

    private static Start physical(BetterBlockPos pos, double centerX, double centerZ) {
      return new Start(pos, centerX, centerZ, true);
    }

    public static Start corner(BetterBlockPos pos) {
      return new Start(pos, pos.x, pos.z, false);
    }

    private static Start certified(BetterBlockPos pos, double centerX, double centerZ) {
      return new Start(pos, centerX, centerZ, false);
    }
  }

  private record HorseSpec(double ticksPerBlock, double stepHeightBlocks, int stepRiseBlocks, int maxSafeDropBlocks, double halfWidth, double height) {
    private static HorseSpec capture(AbstractHorse horse) {
      double speed = horse.getAttributeValue(Attributes.MOVEMENT_SPEED);
      double step = horse.getAttributeValue(Attributes.STEP_HEIGHT);
      double safeFall = horse.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);
      double blocksPerSecond = 20D / HorseRoutePlanner.ticksPerBlock(horse);
      int stepRise = Math.max(1, Math.min(2, Mth.floor(step + 0.05D)));
      int maxSafeDrop = zeroDamageFallBlocks(safeFall);
      return new HorseSpec(20D / blocksPerSecond, step, stepRise, maxSafeDrop, horse.getBbWidth() * 0.5D, horse.getBbHeight());
    }

    private static int zeroDamageFallBlocks(double safeFallDistance) {
      return Math.max(0, Mth.floor(safeFallDistance + 1.0E-6D));
    }

  }

  private static final class Search {
    private final CalculationContext context;
    private final HorseSpec spec;
    private final BetterBlockPos start;
    private final double startCenterX;
    private final double startCenterZ;
    private final boolean physicalStart;
    private final Goal goal;
    private final double startHeuristic;
    private final long failureTimeoutMS;
    private final long deadlineNanos;
    private final IncumbentSink incumbentSink;
    private final long incumbentIntervalMS;
    private PathingProfiler.Active profile;
    private int expansions;
    private int edgesConsidered;
    private int edgesBlockedBeforeEval;
    private long searchStartedNanos;
    private final long[] edgeRejects = new long[EdgeReject.values().length];
    private final Object2ObjectOpenHashMap<NodeKey, Node> nodes = new Object2ObjectOpenHashMap<>();
    private int nodeCount;
    private final Long2ByteOpenHashMap standableCache = binaryCache();
    private final HorseCollisionOracle oracle;
    private final HorseCruiseRayCertifier cruiseRayCertifier;
    private final PriorityQueue<QueueEntry> open = new PriorityQueue<>(Comparator.comparingDouble(QueueEntry::f).thenComparingDouble(QueueEntry::g));
    private int minX;
    private int maxX;
    private int minZ;
    private int maxZ;
    private Node closest;
    private double closestHeuristic = Double.POSITIVE_INFINITY;
    private Node bestLocalProgress;
    private double bestLocalProgressScore = Double.POSITIVE_INFINITY;
    private int boundaryTouches;
    private Node bestExit;
    private double bestExitScore = Double.POSITIVE_INFINITY;
    private Node lastPublished;
    private long nextIncumbentPublishMS;

    private Search(CalculationContext context, HorseSpec spec, BetterBlockPos start, double startCenterX, double startCenterZ, boolean physicalStart, Goal goal, long failureTimeoutMS,
      IncumbentSink incumbentSink) {
      this.context = context;
      this.spec = spec;
      this.start = start;
      this.startCenterX = startCenterX;
      this.startCenterZ = startCenterZ;
      this.physicalStart = physicalStart;
      this.goal = goal;
      this.startHeuristic = goal instanceof GoalXZ xz ? horseGoalXZHeuristic(xz, startCenterX, startCenterZ) : heuristic(start.x, start.y, start.z);
      this.failureTimeoutMS = failureTimeoutMS;
      this.deadlineNanos = System.nanoTime() + failureTimeoutMS * 1_000_000L;
      this.incumbentSink = incumbentSink;
      this.incumbentIntervalMS = incumbentSink == null ? Long.MAX_VALUE : Math.max(25L, Baritone.settings().pathingIncumbentIntervalMS.value);
      this.nextIncumbentPublishMS = incumbentSink == null ? Long.MAX_VALUE : System.currentTimeMillis() + this.incumbentIntervalMS;
      this.minX = this.maxX = start.x;
      this.minZ = this.maxZ = start.z;
      this.oracle = HorseCollisionOracle.calculation(context, spec.halfWidth(), spec.height(), spec.stepHeightBlocks());
      this.cruiseRayCertifier = new HorseCruiseRayCertifier(context, oracle, spec.halfWidth(), spec.ticksPerBlock(), spec.stepRiseBlocks(), lineCertificationSamplesPerBlock(),
        damageHazardApproachMargin(), tuning().poorSupportPenaltyTicks(), tuning().diagonalStepOverrunPenaltyTicks(), tuning().minCruiseSupportQuadrants());
    }

    private static Long2ByteOpenHashMap binaryCache() {
      Long2ByteOpenHashMap cache = new Long2ByteOpenHashMap();
      cache.defaultReturnValue((byte) 0);
      return cache;
    }

    private static boolean cornerLattice(double x, double z) {
      return cornerLattice(x) && cornerLattice(z);
    }

    private static boolean cornerLattice(double value) {
      return Math.abs(value - Math.rint(value)) < 1.0E-7D;
    }

    private SearchResult calculate() {
      profile = context.pathingProfiler.begin(start, start.x, start.y, start.z, goal, 0L, failureTimeoutMS);
      searchStartedNanos = System.nanoTime();
      SearchResult result = null;
      try {
        result = calculate0();
        return result;
      } finally {
        finishProfile(result);
      }
    }

    private SearchResult calculate0() {
      if (!startStandable()) {
        return SearchResult.failure("start not horse-standable at " + start.x + "," + start.y + "," + start.z);
      }
      Node root = node(start.x, start.y, start.z, startCenterX, startCenterZ);
      root.g = 0D;
      root.f = heuristic(root);
      open.add(new QueueEntry(root, root.g, root.f));
      while (!open.isEmpty()) {
        if (goal instanceof BestExitGoal && bestExit != null && open.peek().f() + 0.01D >= bestExitScore) {
          return success(reconstructCertified(bestExit));
        }
        QueueEntry entry = open.poll();
        Node current = entry.node();
        if (current.closed || entry.g() != current.g) {
          continue;
        }
        if (goalReached(current)) {
          if (goal instanceof BestExitGoal exit) {
            considerBestExit(current, current.g + exit.exactGoalExitValue(current.x, current.y, current.z));
            continue;
          }
          return success(current.parent == null ? stationaryPath(current) : reconstructCertified(current));
        }
        if (goal instanceof BestExitGoal exit && current.parent != null && exit.isExactExit(current.x, current.y, current.z)) {
          considerBestExit(current, current.g + exit.exitValue(current.x, current.y, current.z));
          current.closed = true;
          continue;
        }
        current.closed = true;
        if ((++expansions & (EXPANSION_BATCH - 1)) == 0 && System.nanoTime() > deadlineNanos) {
          SearchResult fallback = progressFallback();
          if (fallback != null) {
            return fallback;
          }
          return SearchResult.failure("timeout after " + expansions + " expansions / " + nodeCount + " nodes " + bounds());
        }
        if ((expansions & (EXPANSION_BATCH - 1)) == 0) {
          SearchResult incumbent = publishIncumbent();
          if (incumbent != null) {
            return incumbent;
          }
        }
        expand(current);
        considerLocalProgress(current);
      }
      if (bestExit != null) {
        return success(reconstructCertified(bestExit));
      }
      SearchResult fallback = progressFallback();
      if (fallback != null) {
        return fallback;
      }
      return SearchResult.failure("exhausted after " + expansions + " expansions / " + nodeCount + " nodes " + bounds());
    }

    private SearchResult progressFallback() {
      if (!(goal instanceof BestExitGoal exit)) {
        return localProgressFallback();
      }
      Node fallback = handoffNode(closest);
      if (fallback == null) {
        return null;
      }
      HorsePath path = reconstructCertified(fallback);
      if (path.flatDistance() < Baritone.settings().pathingMinIncumbentLength.value) {
        return null;
      }
      double startValue = exit.exitValue(start.x, start.y, start.z);
      BetterBlockPos dest = path.dest();
      double destValue = exit.exitValue(dest.x, dest.y, dest.z);
      if (!Double.isFinite(startValue) || !Double.isFinite(destValue) || destValue + Baritone.settings().pathingIncumbentHeuristicMargin.value >= startValue) {
        return null;
      }
      return success(path);
    }

    private SearchResult localProgressFallback() {
      Node best = localProgressNode();
      return best == null ? null : success(reconstructCertified(best));
    }

    private Node localProgressNode() {
      Node closestHandoff = handoffNode(closest);
      Node best = null;
      double bestHeuristic = Double.POSITIVE_INFINITY;
      if (localFallbackCandidate(bestLocalProgress)) {
        best = bestLocalProgress;
        bestHeuristic = heuristic(bestLocalProgress);
      }
      if (closestHandoff != bestLocalProgress && localFallbackCandidate(closestHandoff)) {
        double h = heuristic(closestHandoff);
        if (best == null || h < bestHeuristic) {
          best = closestHandoff;
        }
      }
      return best;
    }

    private boolean localFallbackCandidate(Node node) {
      if (node == null) {
        return false;
      }
      if (!localProgressReady(node)) {
        return false;
      }
      HorsePath path = reconstructCertified(node);
      MountTuning.Planner tuning = tuning();
      double minProgress = goal instanceof GoalXZ ? tuning.minLocalProgressFallbackBlocks() : tuning.minVerticalLocalProgressFallbackBlocks();
      return path.flatDistance() >= minProgress && heuristic(node.x, node.y, node.z) + tuning.localProgressHeuristicEpsilon() < startHeuristic;
    }

    private void finishProfile(SearchResult result) {
      if (profile == null) {
        return;
      }
      profile.finishSearchLoop(expansions, edgesConsidered, edgesBlockedBeforeEval, nodeCount, result == null ? "exception" : result.path().isPresent() ? "success" : result.diagnostic(),
        System.nanoTime() - searchStartedNanos, 0L, 0L);
      profile.finishPathPhases(0L, 0L, 0L);
      profile.finish(
        new PathCalculationResult(result != null && (result.path().isPresent() || result.incumbentAccepted()) ? PathCalculationResult.Type.SUCCESS_SEGMENT : PathCalculationResult.Type.FAILURE));
      profile = null;
    }

    private SearchResult publishIncumbent() {
      if (incumbentSink == null) {
        return null;
      }
      long now = System.currentTimeMillis();
      if (now < nextIncumbentPublishMS) {
        return null;
      }
      nextIncumbentPublishMS = now + incumbentIntervalMS;
      boolean boundary = bestExit != null;
      Node node = boundary ? handoffNode(bestExit) : localProgressNode();
      if (node == null || node.parent == null || node == lastPublished) {
        return null;
      }
      HorsePath path = reconstructCertified(node);
      if (certificateFailure(path) != null) {
        return null;
      }
      lastPublished = node;
      return incumbentSink.publish(new Incumbent(path, boundary)) ? SearchResult.acceptedIncumbent() : null;
    }

    private SearchResult success(HorsePath path) {
      String failure = certificateFailure(path);
      return failure == null ? SearchResult.success(path) : SearchResult.failure(failure);
    }

    private String certificateFailure(HorsePath path) {
      for (int i = 0; i < path.waypoints().size(); i++) {
        HorsePath.Waypoint waypoint = path.waypoints().get(i);
        BetterBlockPos pos = waypoint.pos();
        if (i == 0 && physicalStart && pos.equals(start)) {
          continue;
        }
        boolean terminal = i + 1 == path.waypoints().size();
        if (!(terminal ? stableAnchor(waypoint) : rideableAnchor(waypoint))) {
          return "uncertified horse waypoint " + i + "/" + (path.waypoints().size() - 1) + " at " + pos.x + "," + pos.y + "," + pos.z + " center="
            + String.format(java.util.Locale.ROOT, "%.3f,%.3f", waypoint.centerX(), waypoint.centerZ());
        }
      }
      return null;
    }

    private void expand(Node current) {
      if (physicalStart && current.parent == null) {
        expandPhysicalRoot(current);
      }
      for (HorsePath.CruiseVector vector : HorsePath.CruiseVector.CRUISE8) {
        expandVector(current, vector);
      }
    }

    private void expandPhysicalRoot(Node current) {
      int minY = current.y - spec.maxSafeDropBlocks();
      int maxY = current.y + spec.stepRiseBlocks();
      for (int y = maxY; y >= minY; y--) {
        for (int dz = -1; dz <= 1; dz++) {
          for (int dx = -1; dx <= 1; dx++) {
            int x = current.x + dx;
            int z = current.z + dz;
            Landing landing = physicalRootLandingAt(current, x, y, z);
            if (landing == null || sameContinuousPose(current, landing)) {
              continue;
            }
            EdgePlan edge = physicalRootEdge(current, landing);
            if (edge != null) {
              acceptEdge(current, edge);
            }
          }
        }
      }
      for (HorsePath.CruiseVector vector : HorsePath.CruiseVector.CRUISE8) {
        EdgePlan clipped = clippedAdjacentEdge(current, vector.dx(), vector.dz(), false);
        if (clipped != null) {
          acceptEdge(current, clipped);
        }
      }
    }

    private void expandVector(Node current, HorsePath.CruiseVector vector) {
      expandEdge(current, vector);
    }

    private void expandEdge(Node current, HorsePath.CruiseVector vector) {
      long startNanos = profile == null ? 0L : System.nanoTime();
      EdgePlan edge = edge(current, vector);
      if (profile != null) {
        profile.recordMove("HORSE_CORNER8", System.nanoTime() - startNanos, edge != null);
      }
      edgesConsidered++;
      if (edge == null) {
        return;
      }
      acceptEdge(current, edge);
    }

    private void acceptEdge(Node current, EdgePlan edge) {
      Node next = node(edge.landing().x(), edge.landing().y(), edge.landing().z(), edge.landing().centerX(), edge.landing().centerZ());
      double g = current.g + edge.edge().costTicks();
      if (g >= next.g) {
        return;
      }
      current.acceptedSuccessors++;
      if (heuristic(next) + tuning().localProgressHeuristicEpsilon() < heuristic(current)) {
        current.acceptedImprovingSuccessors++;
      }
      next.g = g;
      next.f = g + heuristic(next);
      next.parent = current;
      next.edgeFromParent = edge.edge();
      open.add(new QueueEntry(next, next.g, next.f));
    }

    private EdgePlan physicalRootEdge(Node current, Landing landing) {
      double distance = Math.hypot(landing.centerX() - current.centerX, landing.centerZ() - current.centerZ);
      int rise = landing.y() - current.y;
      if (Math.abs(rise) <= spec.stepRiseBlocks()) {
        TrackedRide tracked = physicalRootStepRide(current, landing);
        if (tracked != null) {
          return new EdgePlan(landing, new HorsePath.Template(HorsePath.TemplateKind.PHYSICAL_ROOT_STEP, tracked.track(), Math.max(1D, tracked.plan().costTicks() + poorSupportPenalty(landing)),
            tracked.plan().minY(), tracked.plan().maxY()));
        }
        GroundRide ground = groundedRide(current, landing, distance);
        if (ground.clear()) {
          return new EdgePlan(landing, edgeForUntypedRootRide(current, landing, Math.max(1D, rideCost(distance, ground.water(), landing)), ground.water()));
        }
        return null;
      }
      GroundRide ground = groundedRide(current, landing, distance);
      if (ground.clear()) {
        return new EdgePlan(landing, edgeForUntypedRootRide(current, landing, Math.max(1D, rideCost(distance, ground.water(), landing)), ground.water()));
      }
      DropRide waterDrop = descendingWaterDropRide(current, landing, distance, 1);
      if (waterDrop.clear()) {
        return new EdgePlan(landing, new HorsePath.WaterDrop(Math.max(1D, waterDrop.costTicks()), Math.min(current.y, landing.y()), Math.max(current.y, landing.y())));
      }
      return null;
    }

    private HorsePath.Edge edgeForUntypedRootRide(Node current, Landing landing, double costTicks, boolean water) {
      int dx = landing.x() - current.x;
      int dz = landing.z() - current.z;
      if (cruiseEligible(current, landing) && (dx != 0 || dz != 0) && !water) {
        return new HorsePath.Cruise(new HorsePath.CruiseVector(dx, dz), costTicks, Math.min(current.y, landing.y()), Math.max(current.y, landing.y()));
      }
      return water ? new HorsePath.WaterDrop(costTicks, Math.min(current.y, landing.y()), Math.max(current.y, landing.y())) : new HorsePath.Template(HorsePath.TemplateKind.PHYSICAL_ROOT_STEP,
        new HorsePath.Track(current.centerX, current.centerZ, landing.centerX(), landing.centerZ(), false), costTicks, Math.min(current.y, landing.y()), Math.max(current.y, landing.y()));
    }

    private EdgePlan edge(Node current, HorsePath.CruiseVector vector) {
      int dx = vector.dx();
      int dz = vector.dz();
      int x = current.x + dx;
      int z = current.z + dz;
      if (!hasHullPathingData(x, z)) {
        edgesBlockedBeforeEval++;
        boundaryTouches++;
        current.touchedBoundary = true;
        if (goal instanceof BestExitGoal exit) {
          considerBestExit(current, current.g + exit.exitValue(current.x, current.y, current.z));
        }
        return reject(EdgeReject.NO_PATHING_DATA);
      }
      Landing landing = landing(x, z, current, vector.adjacent8());
      if (landing == null) {
        edgesBlockedBeforeEval++;
        EdgePlan clipped = vector.adjacent8() ? clippedAdjacentEdge(current, dx, dz, false) : null;
        return clipped == null ? reject(EdgeReject.NO_LANDING) : clipped;
      }
      double distance = Math.hypot(landing.centerX() - current.centerX, landing.centerZ() - current.centerZ);
      boolean cruise = cruiseEligible(current, landing);
      boolean trackRequired = requiresTrackWitness(current, landing, dx, dz);
      HorseTrackCertifier.Plan simple = directAdjacentRide(current, landing);
      if (simple != null) {
        double overrunPenalty = diagonalStepOverrunPenalty(current, landing, vector, simple.maxY());
        if (Double.isInfinite(overrunPenalty)) {
          return reject(EdgeReject.DIAGONAL_STEP_OVERRUN_LEDGE);
        }
        double cost = Math.max(1D, simple.costTicks() + poorSupportPenalty(landing) + overrunPenalty);
        if (cruise) {
          return new EdgePlan(landing, new HorsePath.Cruise(vector, cost, simple.minY(), simple.maxY()));
        }
        if (!vector.adjacent8()) {
          return reject(EdgeReject.LONG_GROUND_SPAN);
        }
        EdgePlan clipped = clippedAdjacentEdge(current, dx, dz, false);
        return clipped == null ? reject(EdgeReject.TRACK_WITNESS_FAILED) : clipped;
      }
      if (!vector.adjacent8()) {
        return reject(EdgeReject.LONG_GROUND_SPAN);
      }
      GroundRide ground = groundedRide(current, landing, distance);
      if (ground.clear()) {
        double cost = rideCost(distance, ground.water(), landing);
        if (cruise && !ground.water()) {
          double overrunPenalty = diagonalStepOverrunPenalty(current, landing, vector, Math.max(current.y, landing.y()));
          if (Double.isInfinite(overrunPenalty)) {
            return reject(EdgeReject.DIAGONAL_STEP_OVERRUN_LEDGE);
          }
          cost += overrunPenalty;
          return new EdgePlan(landing, new HorsePath.Cruise(vector, cost, Math.min(current.y, landing.y()), Math.max(current.y, landing.y())));
        }
        if (ground.water()) {
          return new EdgePlan(landing, new HorsePath.WaterDrop(cost, Math.min(current.y, landing.y()), Math.max(current.y, landing.y())));
        }
        EdgePlan clipped = clippedAdjacentEdge(current, dx, dz, false);
        return clipped == null ? reject(EdgeReject.TRACK_WITNESS_FAILED) : clipped;
      }
      if (trackRequired) {
        EdgePlan clipped = vector.adjacent8() ? clippedAdjacentEdge(current, dx, dz, false) : null;
        return clipped == null ? reject(EdgeReject.TRACK_WITNESS_FAILED) : clipped;
      }
      DropRide waterDrop = descendingWaterDropRide(current, landing, distance, 1);
      if (waterDrop.clear()) {
        return new EdgePlan(landing, new HorsePath.WaterDrop(waterDrop.costTicks(), Math.min(current.y, landing.y()), Math.max(current.y, landing.y())));
      }
      return reject(EdgeReject.UNSUPPORTED_GROUND);
    }

    private EdgePlan clippedAdjacentEdge(Node current, int dx, int dz, boolean allowFlat) {
      if (dx == 0 && dz == 0 || horseWaterborne(current.centerX, current.y, current.centerZ)) {
        return null;
      }
      HorseCollisionOracle.Move move = oracle.clippedStep(current.centerX, current.y, current.centerZ, dx, dz, true);
      double progress = move.dx() * dx + move.dz() * dz;
      if (progress < 0.35D || move.dx() * move.dx() + move.dz() * move.dz() < 0.16D) {
        return null;
      }
      double centerX = current.centerX + move.dx();
      double centerZ = current.centerZ + move.dz();
      if (!oracle.clearHazardSegment(current.centerX, current.y, current.centerZ, centerX, current.y, centerZ, damageHazardApproachMargin())) {
        return null;
      }
      int x = Mth.floor(centerX);
      int z = Mth.floor(centerZ);
      if (!hasHullPathingData(centerX, centerZ)) {
        return null;
      }
      int y = Mth.floor(current.y + move.dy() + 0.2D);
      Landing landing = new Landing(x, y, z, centerX, centerZ);
      if (y < current.y || y == current.y && !allowFlat || y - current.y > spec.stepRiseBlocks() || sameContinuousPose(current, landing) || !rideableHull(centerX, y, centerZ)) {
        return null;
      }
      double distance = Math.hypot(move.dx(), move.dz());
      HorsePath.Track track = y == current.y ? null : new HorsePath.Track(current.centerX, current.centerZ, centerX, centerZ, false);
      double cost = Math.max(1D, rideCost(distance, horseWaterborne(centerX, y, centerZ), landing));
      return track == null
        ? new EdgePlan(landing, new HorsePath.Template(HorsePath.TemplateKind.CLIPPED_STEP, new HorsePath.Track(current.centerX, current.centerZ, centerX, centerZ, false), cost,
          Math.min(current.y, y), Math.max(current.y, y)))
        : new EdgePlan(landing, new HorsePath.Template(HorsePath.TemplateKind.CLIPPED_STEP, track, cost, Math.min(current.y, y), Math.max(current.y, y)));
    }

    private HorseTrackCertifier.Plan directAdjacentRide(Node current, Landing landing) {
      if (sameContinuousPose(current, landing) || !cruiseEligible(current, landing)) {
        return null;
      }
      int rise = landing.y() - current.y;
      if (Math.abs(rise) > spec.stepRiseBlocks() || horseWaterborne(current.centerX, current.y, current.centerZ) || horseWaterborne(landing.centerX(), landing.y(), landing.centerZ())) {
        return null;
      }
      if (!oracle.clearHazardSegment(current.centerX, current.y, current.centerZ, landing.centerX(), landing.y(), landing.centerZ(), damageHazardApproachMargin())) {
        return null;
      }
      return cruisePlan(current, landing);
    }

    private HorseTrackCertifier.Plan cruisePlan(Node current, Landing landing) {
      double sx = current.centerX;
      double sz = current.centerZ;
      double ex = landing.centerX();
      double ez = landing.centerZ();
      double distance = Math.hypot(ex - sx, ez - sz);
      int samples = Math.max(2, (int) Math.ceil(distance * lineCertificationSamplesPerBlock()));
      double cost = 0D;
      int y = current.y;
      int minY = y;
      int maxY = y;
      double x = sx;
      double z = sz;
      for (int i = 1; i <= samples; i++) {
        double t = i / (double) samples;
        double nx = Mth.lerp(t, sx, ex);
        double nz = Mth.lerp(t, sz, ez);
        int ny = i == samples ? landing.y() : cruiseSampleY(x, y, z, nx, nz, landing.y());
        if (ny == Integer.MIN_VALUE || !cruiseTransitionClear(x, y, z, nx, ny, nz)) {
          return null;
        }
        cost += Math.hypot(nx - x, nz - z) * spec.ticksPerBlock();
        y = ny;
        x = nx;
        z = nz;
        minY = Math.min(minY, y);
        maxY = Math.max(maxY, y);
      }
      return new HorseTrackCertifier.Plan(cost, minY, maxY, false);
    }

    private int cruiseSampleY(double sx, int sy, double sz, double ex, double ez, int targetY) {
      int bestY = Integer.MIN_VALUE;
      int bestScore = Integer.MAX_VALUE;
      for (int dy = -spec.stepRiseBlocks(); dy <= spec.stepRiseBlocks(); dy++) {
        int y = sy + dy;
        if (!cruiseTransitionClear(sx, sy, sz, ex, y, ez)) {
          continue;
        }
        int score = Math.abs(y - targetY) * 8 + Math.abs(dy);
        if (score < bestScore) {
          bestScore = score;
          bestY = y;
        }
      }
      return bestY;
    }

    private boolean cruiseTransitionClear(double sx, int sy, double sz, double ex, int ey, double ez) {
      int rise = ey - sy;
      if (Math.abs(rise) > spec.stepRiseBlocks()) {
        return false;
      }
      HorseCollisionOracle.Move move = oracle.clippedStep(sx, sy, sz, ex - sx, ez - sz, true);
      if (!move.reached(ex - sx, ez - sz)) {
        return false;
      }
      if (rise > 0 && Math.abs(move.dy() - rise) >= 0.2D || rise <= 0 && Math.abs(move.dy()) >= 0.2D) {
        return false;
      }
      if (rise < 0 && !oracle.clearSegment(ex, sy, ez, ex, ey, ez)) {
        return false;
      }
      return oracle.standable(ex, ey, ez);
    }

    private static boolean cruiseEligible(Node current, Landing landing) {
      return cornerLattice(current.centerX) && cornerLattice(current.centerZ) && cornerLattice(landing.centerX()) && cornerLattice(landing.centerZ());
    }

    private boolean requiresTrackWitness(Node current, Landing landing, int dx, int dz) {
      return landing.y() != current.y || !rideableHull(current.centerX, current.y, current.centerZ) || !rideableHull(landing.centerX(), landing.y(), landing.centerZ())
        || dx != 0 && dz != 0 && current.edgeFromParent instanceof HorsePath.Template;
    }

    private TrackedRide physicalRootStepRide(Node current, Landing landing) {
      double sx = current.centerX;
      double sz = current.centerZ;
      double tx = landing.centerX();
      double tz = landing.centerZ();
      double length = Math.hypot(tx - sx, tz - sz);
      if (length < 1.0E-6D) {
        return null;
      }
      double px = -(tz - sz) / length;
      double pz = (tx - sx) / length;
      TrackedRide best = null;
      for (int i = 0; i < HorseTrackLattice.offsetCount(); i++) {
        double offset = HorseTrackLattice.offset(i);
        double trackEndX = tx + px * offset;
        double trackEndZ = tz + pz * offset;
        if (Mth.floor(trackEndX) != landing.x() || Mth.floor(trackEndZ) != landing.z()) {
          continue;
        }
        HorseTrackCertifier.Plan candidate = physicalRootStepPlan(sx, current.y, sz, trackEndX, landing.y(), trackEndZ);
        if (candidate == null || candidate.mixedWater()) {
          continue;
        }
        HorseTrackCertifier.Plan recenter = recenterPlan(trackEndX, landing.y(), trackEndZ, tx, tz);
        if (recenter == null || recenter.mixedWater()) {
          continue;
        }
        candidate = new HorseTrackCertifier.Plan(candidate.costTicks() + recenter.costTicks(), Math.min(candidate.minY(), recenter.minY()), Math.max(candidate.maxY(), recenter.maxY()), false);
        if (best == null || candidate.costTicks() < best.plan().costTicks()) {
          best = new TrackedRide(candidate, new HorsePath.Track(sx, sz, trackEndX, trackEndZ, true, recenterRequired(trackEndX, trackEndZ, tx, tz)));
        }
      }
      return best;
    }

    private HorseTrackCertifier.Plan recenterPlan(double sx, int y, double sz, double ex, double ez) {
      if (!recenterRequired(sx, sz, ex, ez)) {
        return new HorseTrackCertifier.Plan(0D, y, y, false);
      }
      if (!oracle.clearHazardSegment(sx, y, sz, ex, y, ez, damageHazardApproachMargin())) {
        return null;
      }
      return HorseTrackCertifier.certifyHullFromStableSource(oracle, sx, y, sz, ex, y, ez, spec.ticksPerBlock(), spec.stepRiseBlocks(), waterWadeCostMultiplier(), lineCertificationSamplesPerBlock());
    }

    private static boolean recenterRequired(double sx, double sz, double ex, double ez) {
      return Math.abs(sx - ex) > 1.0E-6D || Math.abs(sz - ez) > 1.0E-6D;
    }

    private HorseTrackCertifier.Plan trackedStepPlan(double sx, int sy, double sz, double ex, int ey, double ez) {
      if (ey > sy && !sweptStepClear(sx, sy, sz, ex, ey, ez)) {
        return null;
      }
      return ey > sy ? HorseTrackCertifier.certifyHull(oracle, sx, sy, sz, ex, ey, ez, spec.ticksPerBlock(), spec.stepRiseBlocks(), waterWadeCostMultiplier(), lineCertificationSamplesPerBlock())
        : HorseTrackCertifier.certifyBalanced(oracle, sx, sy, sz, ex, ey, ez, spec.ticksPerBlock(), spec.stepRiseBlocks(), waterWadeCostMultiplier(), lineCertificationSamplesPerBlock());
    }

    private HorseTrackCertifier.Plan physicalRootStepPlan(double sx, int sy, double sz, double ex, int ey, double ez) {
      if (ey > sy && !sweptStepClear(sx, sy, sz, ex, ey, ez)) {
        return null;
      }
      return ey > sy
        ? HorseTrackCertifier.certifyHullFromStableSource(oracle, sx, sy, sz, ex, ey, ez, spec.ticksPerBlock(), spec.stepRiseBlocks(), waterWadeCostMultiplier(), lineCertificationSamplesPerBlock())
        : HorseTrackCertifier.certifyBalancedFromStableSource(oracle, sx, sy, sz, ex, ey, ez, spec.ticksPerBlock(), spec.stepRiseBlocks(), waterWadeCostMultiplier(),
          lineCertificationSamplesPerBlock());
    }

    private double rideCost(double distance, boolean water) {
      return distance * spec.ticksPerBlock() * (water ? waterWadeCostMultiplier() : 1D);
    }

    private double rideCost(double distance, boolean water, Landing landing) {
      return rideCost(distance, water) + (water ? 0D : poorSupportPenalty(landing));
    }

    private double poorSupportPenalty(Landing landing) {
      if (horseWaterborne(landing.centerX(), landing.y(), landing.centerZ())) {
        return 0D;
      }
      int missing = 4 - oracle.groundSupportQuadrants(landing.centerX(), landing.y(), landing.centerZ());
      return missing <= 0 ? 0D : tuning().poorSupportPenaltyTicks() * missing * (missing + 1D) * 0.5D;
    }

    private double diagonalStepOverrunPenalty(Node current, Landing landing, HorsePath.CruiseVector vector, int promotedY) {
      if (vector.dx() == 0 || vector.dz() == 0 || promotedY <= current.y) {
        return 0D;
      }
      return switch (supportDropBlocks(landing.centerX() + Integer.signum(vector.dx()), promotedY, landing.centerZ() + Integer.signum(vector.dz()))) {
        case 0 -> 0D;
        case 1 -> tuning().diagonalStepOverrunPenaltyTicks();
        default -> Double.POSITIVE_INFINITY;
      };
    }

    private int supportDropBlocks(double centerX, int feetY, double centerZ) {
      if (!hasHullPathingData(centerX, centerZ)) {
        return 0;
      }
      for (int drop = 0; drop < FATAL_DIAGONAL_STEP_OVERRUN_DROP_BLOCKS; drop++) {
        int y = feetY - drop;
        if (oracle.groundSupportQuadrants(centerX, y, centerZ) > 0 || waterSupport(centerX, y, centerZ)) {
          return drop;
        }
      }
      return FATAL_DIAGONAL_STEP_OVERRUN_DROP_BLOCKS;
    }

    private EdgePlan reject(EdgeReject reason) {
      edgeRejects[reason.ordinal()]++;
      return null;
    }

    private Landing landing(int x, int z, Node from, boolean allowOffCenter) {
      for (int delta = 0; delta <= Math.max(spec.stepRiseBlocks(), spec.maxSafeDropBlocks()); delta++) {
        if (delta <= spec.stepRiseBlocks()) {
          int y = from.y + delta;
          Landing up = cornerLandingAt(x, y, z);
          if (up != null) {
            return up;
          }
          if (allowOffCenter && y > from.y && !cornerLattice(from.centerX, from.centerZ)) {
            up = offCenterUphillLandingAt(x, y, z, from);
            if (up != null) {
              return up;
            }
          }
        }
        if (delta > 0 && delta <= spec.maxSafeDropBlocks()) {
          int y = from.y - delta;
          Landing down = cornerLandingAt(x, y, z);
          if (down != null) {
            return down;
          }
          if (allowOffCenter && !cornerLattice(from.centerX, from.centerZ)) {
            down = waterSurfaceEntryLandingAt(x, y, z, from);
            if (down != null) {
              return down;
            }
          }
        }
      }
      return null;
    }

    private Landing offCenterUphillLandingAt(int x, int y, int z, Node from) {
      if (y <= from.y) {
        return null;
      }
      Landing translated = landingAt(x, y, z, from.centerX + x - from.x, from.centerZ + z - from.z);
      if (translated != null) {
        return translated;
      }
      double sx = from.centerX;
      double sz = from.centerZ;
      double tx = x + 0.5D;
      double tz = z + 0.5D;
      double dx = tx - sx;
      double dz = tz - sz;
      double length = Math.hypot(dx, dz);
      if (length < 1.0E-6D) {
        return null;
      }
      double px = -dz / length;
      double pz = dx / length;
      for (int i = 1; i < HorseTrackLattice.offsetCount(); i++) {
        double offset = HorseTrackLattice.offset(i);
        Landing candidate = landingAt(x, y, z, tx + px * offset, tz + pz * offset);
        if (candidate != null) {
          return candidate;
        }
      }
      return null;
    }

    private Landing waterSurfaceEntryLandingAt(int x, int y, int z, Node from) {
      if (y >= from.y || !surfaceWaterSupport(x + 0.5D, y, z + 0.5D)) {
        return null;
      }
      double targetX = x + 0.5D;
      double targetZ = z + 0.5D;
      double dx = targetX - from.centerX;
      double dz = targetZ - from.centerZ;
      double length = Math.hypot(dx, dz);
      if (length < 1.0E-6D) {
        return null;
      }
      double ux = dx / length;
      double uz = dz / length;
      for (int i = 1; i <= 4; i++) {
        double offset = 0.49D * i / 4D;
        Landing candidate = landingAt(x, y, z, targetX + ux * offset, targetZ + uz * offset);
        if (candidate != null) {
          return candidate;
        }
      }
      return null;
    }

    private Landing landingAt(int x, int y, int z) {
      return landingAt(x, y, z, x + 0.5D, z + 0.5D);
    }

    private Landing cornerLandingAt(int x, int y, int z) {
      return landingAt(x, y, z, x, z);
    }

    private Landing landingAt(int x, int y, int z, double centerX, double centerZ) {
      return waypointStandable(x, y, z, centerX, centerZ) ? new Landing(x, y, z, centerX, centerZ) : null;
    }

    private Landing physicalRootLandingAt(Node root, int x, int y, int z) {
      Landing corner = cornerLandingAt(x, y, z);
      return corner != null ? corner : y > root.y ? offCenterUphillLandingAt(x, y, z, root) : null;
    }

    private static boolean sameContinuousPose(Node node, Landing landing) {
      return node.x == landing.x() && node.y == landing.y() && node.z == landing.z() && Math.abs(node.centerX - landing.centerX()) < 1.0E-6D && Math.abs(node.centerZ - landing.centerZ()) < 1.0E-6D;
    }

    private GroundRide groundedRide(Node from, Landing landing, double distance) {
      int rise = landing.y() - from.y;
      if (rise > spec.stepRiseBlocks()) {
        return GroundRide.BLOCKED;
      }
      int samples = Math.max(2, (int) Math.ceil(distance * lineCertificationSamplesPerBlock()));
      double sx = from.centerX;
      double sz = from.centerZ;
      double ex = landing.centerX();
      double ez = landing.centerZ();
      if (!sweptStepClear(sx, from.y, sz, ex, landing.y(), ez)) {
        return GroundRide.BLOCKED;
      }
      boolean water = false;
      boolean supportGap = false;
      for (int i = 1; i <= samples; i++) {
        double t = i / (double) samples;
        double x = Mth.lerp(t, sx, ex);
        double z = Mth.lerp(t, sz, ez);
        int y = stepRideSampleY(from.y, landing.y(), t);
        water |= horseWaterborne(x, y, z) || stepRideWater(x, from.y, landing.y(), y, z);
        if (!stepRideSupported(x, from.y, landing.y(), y, z)) {
          supportGap = true;
          return new GroundRide(false, water, true);
        }
      }
      return new GroundRide(true, water, supportGap);
    }

    private boolean sweptStepClear(double sx, int fromY, double sz, double ex, int landingY, double ez) {
      int rise = landingY - fromY;
      if (rise > 0) {
        HorseCollisionOracle.Move move = oracle.clippedStep(sx, fromY, sz, ex - sx, ez - sz, true);
        return move.reached(ex - sx, ez - sz) && Math.abs(move.dy() - rise) < 0.2D && oracle.clearHazardSegment(sx, fromY, sz, ex, fromY, ez, damageHazardApproachMargin())
          && oracle.balancedStandable(ex, landingY, ez);
      }
      if (rise < 0) {
        HorseCollisionOracle.Move move = oracle.clippedStep(sx, fromY, sz, ex - sx, ez - sz, true);
        return move.reached(ex - sx, ez - sz) && Math.abs(move.dy()) < 0.2D && oracle.clearSegment(ex, fromY, ez, ex, landingY, ez)
          && oracle.clearHazardSegment(sx, fromY, sz, ex, fromY, ez, damageHazardApproachMargin()) && oracle.balancedStandable(ex, landingY, ez);
      }
      HorseCollisionOracle.Move move = oracle.clippedStep(sx, fromY, sz, ex - sx, ez - sz, true);
      return move.reached(ex - sx, ez - sz) && Math.abs(move.dy()) < 0.2D && oracle.clearHazardSegment(sx, fromY, sz, ex, landingY, ez, damageHazardApproachMargin())
        && oracle.balancedStandable(ex, landingY, ez);
    }

    private DropRide descendingWaterDropRide(Node from, Landing landing, double distance, int span) {
      int drop = from.y - landing.y();
      double tx = landing.centerX();
      double tz = landing.centerZ();
      if (span != 1 || drop <= spec.stepRiseBlocks() || drop > spec.maxSafeDropBlocks() || horseWaterborne(from.centerX, from.y, from.centerZ) || !horseWaterborne(tx, landing.y(), tz)) {
        return DropRide.BLOCKED;
      }
      if (!clearHorizontalDropApproach(from.centerX, from.y, from.centerZ, tx, tz) || !clearVerticalDropColumn(tx, from.y, landing.y(), tz)) {
        return DropRide.BLOCKED;
      }
      return new DropRide(true, distance * spec.ticksPerBlock() * waterWadeCostMultiplier() + fallTicks(drop));
    }

    private boolean clearHorizontalDropApproach(double sx, int y, double sz, double ex, double ez) {
      HorseCollisionOracle.Move move = oracle.clippedStep(sx, y, sz, ex - sx, ez - sz, true);
      return move.reached(ex - sx, ez - sz) && Math.abs(move.dy()) < 0.2D;
    }

    private boolean clearVerticalDropColumn(double x, int fromY, int landingY, double z) {
      return oracle.clearSegment(x, fromY, z, x, landingY, z) && rideableHull(x, landingY, z);
    }

    private static double fallTicks(int blocks) {
      double fallen = 0D;
      double velocity = 0D;
      for (int ticks = 1; ticks <= 80; ticks++) {
        velocity = (velocity - GRAVITY) * VERTICAL_DRAG;
        fallen -= velocity;
        if (fallen >= blocks) {
          return ticks;
        }
      }
      return 80D;
    }

    private int stepRideSampleY(int fromY, int landingY, double t) {
      int rise = landingY - fromY;
      if (Math.abs(rise) <= spec.stepRiseBlocks()) {
        return Math.max(fromY, landingY);
      }
      return Mth.floor(Mth.lerp(t, fromY, landingY) + 0.5D);
    }

    private boolean stepRideSupported(double x, int fromY, int landingY, int hullY, double z) {
      int rise = landingY - fromY;
      if (Math.abs(rise) <= spec.stepRiseBlocks()) {
        return oracle.balancedGrounded(x, fromY, z) || waterSupport(x, fromY, z) || oracle.balancedGrounded(x, landingY, z) || waterSupport(x, landingY, z);
      }
      return oracle.balancedGrounded(x, hullY, z) || waterSupport(x, hullY, z);
    }

    private boolean stepRideWater(double x, int fromY, int landingY, int hullY, double z) {
      int rise = landingY - fromY;
      if (Math.abs(rise) <= spec.stepRiseBlocks()) {
        return waterSupport(x, fromY, z) || waterSupport(x, landingY, z);
      }
      return waterSupport(x, hullY, z);
    }

    private boolean standable(int x, int y, int z) {
      long key = BlockKey.pack(x, y, z);
      byte cached = standableCache.get(key);
      if (cached != 0) {
        return cached == 1;
      }
      boolean value = centerStandable(x, y, z);
      standableCache.put(key, value ? (byte) 1 : (byte) 2);
      return value;
    }

    private boolean waypointStandable(int x, int y, int z, double centerX, double centerZ) {
      if (!hasHullPathingData(centerX, centerZ)) {
        return false;
      }
      if (Mth.floor(centerX) != x || Mth.floor(centerZ) != z) {
        return false;
      }
      return rideableHull(centerX, y, centerZ) && !waterPitColumn(x, y, z);
    }

    private boolean hasHullPathingData(double centerX, double centerZ) {
      int minX = Mth.floor(centerX - spec.halfWidth() + 1.0E-7D);
      int maxX = Mth.floor(centerX + spec.halfWidth() - 1.0E-7D);
      int minZ = Mth.floor(centerZ - spec.halfWidth() + 1.0E-7D);
      int maxZ = Mth.floor(centerZ + spec.halfWidth() - 1.0E-7D);
      for (int x = minX; x <= maxX; x++) {
        for (int z = minZ; z <= maxZ; z++) {
          if (!context.worldBorder.entirelyContains(x, z) || !context.hasPathingData(x, z)) {
            return false;
          }
        }
      }
      return true;
    }

    private boolean waterPitColumn(int x, int y, int z) {
      for (int dy = 0; dy <= WATER_PIT_SCAN_BLOCKS; dy++) {
        if (context.get(x, y - dy, z).getFluidState().is(FluidTags.WATER)) {
          return true;
        }
      }
      return false;
    }

    private boolean centerStandable(int x, int y, int z) {
      return context.worldBorder.entirelyContains(x, z) && context.hasPathingData(x, z) && oracle.centerStandable(x + 0.5D, y, z + 0.5D);
    }

    private boolean rideableAnchor(HorsePath.Waypoint waypoint) {
      return hasHullPathingData(waypoint.centerX(), waypoint.centerZ()) && oracle.standable(waypoint.centerX(), waypoint.pos().y, waypoint.centerZ());
    }

    private boolean stableAnchor(HorsePath.Waypoint waypoint) {
      return hasHullPathingData(waypoint.centerX(), waypoint.centerZ()) && oracle.standable(waypoint.centerX(), waypoint.pos().y, waypoint.centerZ());
    }

    private boolean stableAnchor(Node node) {
      return hasHullPathingData(node.centerX, node.centerZ) && oracle.standable(node.centerX, node.y, node.centerZ);
    }

    private boolean startStandable() {
      if (!physicalStart) {
        return waypointStandable(start.x, start.y, start.z, startCenterX, startCenterZ);
      }
      return hasHullPathingData(startCenterX, startCenterZ);
    }

    private boolean rideableHull(double centerX, int feetY, double centerZ) {
      return oracle.standable(centerX, feetY, centerZ);
    }

    private boolean waterSupport(double centerX, int feetY, double centerZ) {
      return oracle.waterSupport(centerX, feetY, centerZ);
    }

    private boolean surfaceWaterSupport(double centerX, int feetY, double centerZ) {
      return oracle.surfaceWaterSupport(centerX, feetY, centerZ);
    }

    private boolean wetHull(double centerX, int feetY, double centerZ) {
      return oracle.wetHull(centerX, feetY, centerZ);
    }

    private boolean horseWaterborne(double centerX, int feetY, double centerZ) {
      return wetHull(centerX, feetY, centerZ) || surfaceWaterSupport(centerX, feetY, centerZ);
    }

    private double heuristic(Node node) {
      if (goal instanceof GoalXZ xz) {
        return horseGoalXZHeuristic(xz, node.centerX, node.centerZ);
      }
      return heuristic(node.x, node.y, node.z);
    }

    private double heuristic(int x, int y, int z) {
      if (goal instanceof BestExitGoal) {
        return Math.max(0D, goal.heuristic(x, y, z));
      }
      if (goal instanceof GoalXZ xz) {
        return horseGoalXZHeuristic(xz, x, z);
      }
      return Math.max(0D, goal.heuristic(x, y, z) * 0.45D);
    }

    private boolean goalReached(Node node) {
      if (!stableAnchor(node)) {
        return false;
      }
      if (goal instanceof GoalXZ xz) {
        double radius = GoalTerminalPolicy.horseGoalXZPlanningRadius(xz, GOAL_XZ_PLAN_RADIUS);
        if (horseGoalXZReached(xz, node, radius)) {
          return true;
        }
        double obstructedRadius = GoalTerminalPolicy.horseGoalXZPlanningRadius(xz, OBSTRUCTED_GOAL_XZ_PLAN_RADIUS);
        return obstructedRadius > radius && horseGoalXZReached(xz, node, obstructedRadius) && horseGoalXZObstructed(xz, node.y);
      }
      return goal.isInGoal(node.x, node.y, node.z);
    }

    private double horseGoalXZHeuristic(GoalXZ goal, double centerX, double centerZ) {
      double radius = GoalTerminalPolicy.horseGoalXZPlanningRadius(goal, GOAL_XZ_PLAN_RADIUS);
      double dx = centerX - goal.getX();
      double dz = centerZ - goal.getZ();
      return Math.max(0D, Math.hypot(dx, dz) - radius) * spec.ticksPerBlock();
    }

    private static boolean horseGoalXZReached(GoalXZ goal, Node node, double radius) {
      double dx = node.centerX - goal.getX();
      double dz = node.centerZ - goal.getZ();
      return dx * dx + dz * dz <= radius * radius;
    }

    private boolean horseGoalXZObstructed(GoalXZ goal, int referenceY) {
      boolean known = false;
      int minY = Math.max(context.world.getMinY() + 1, referenceY - 2);
      int maxY = Math.min(context.world.getMaxY() - 2, referenceY + 2);
      for (int x = goal.getX() - GoalTerminalPolicy.HORSE_GOAL_XZ_RADIUS; x <= goal.getX() + GoalTerminalPolicy.HORSE_GOAL_XZ_RADIUS; x++) {
        for (int z = goal.getZ() - GoalTerminalPolicy.HORSE_GOAL_XZ_RADIUS; z <= goal.getZ() + GoalTerminalPolicy.HORSE_GOAL_XZ_RADIUS; z++) {
          if (!context.hasPathingData(x, z)) {
            continue;
          }
          known = true;
          for (int y = minY; y <= maxY; y++) {
            if (standable(x, y, z)) {
              return false;
            }
          }
        }
      }
      return known;
    }

    private Node node(int x, int y, int z) {
      return node(x, y, z, x, z);
    }

    private Node node(int x, int y, int z, double centerX, double centerZ) {
      if (Mth.floor(centerX) != x || Mth.floor(centerZ) != z) {
        throw new IllegalArgumentException("horse node center " + centerX + "," + centerZ + " outside " + x + "," + z);
      }
      NodeKey key = new NodeKey(x, y, z, centerX, centerZ);
      Node existing = nodes.get(key);
      if (existing != null) {
        return existing;
      }
      Node created = new Node(x, y, z, centerX, centerZ);
      nodes.put(key, created);
      nodeCount++;
      minX = Math.min(minX, x);
      maxX = Math.max(maxX, x);
      minZ = Math.min(minZ, z);
      maxZ = Math.max(maxZ, z);
      double h = goal.heuristic(x, y, z);
      if (h < closestHeuristic) {
        closestHeuristic = h;
        closest = created;
      }
      return created;
    }

    private void considerBestExit(Node node, double score) {
      if (node.parent == null || !stableAnchor(node) || !Double.isFinite(score) || score >= bestExitScore) {
        return;
      }
      bestExit = node;
      bestExitScore = score;
    }

    private void considerLocalProgress(Node node) {
      if (!localProgressReady(node)) {
        return;
      }
      double h = heuristic(node);
      MountTuning.Planner tuning = tuning();
      if (h + tuning.localProgressHeuristicEpsilon() >= startHeuristic) {
        return;
      }
      double score = h + tuning.localProgressCostWeight() * node.g;
      if (score < bestLocalProgressScore) {
        bestLocalProgressScore = score;
        bestLocalProgress = node;
      }
    }

    private boolean localProgressReady(Node node) {
      return node != null && node.parent != null && node.closed && stableAnchor(node) && (node.acceptedImprovingSuccessors > 0 || node.touchedBoundary);
    }

    private Node handoffNode(Node node) {
      while (node != null && node.parent != null && !stableAnchor(node)) {
        node = node.parent;
      }
      return node != null && node.parent != null ? node : null;
    }

    private String bounds() {
      return "bounds=[" + minX + ".." + maxX + "," + minZ + ".." + maxZ + "] closest="
        + (closest == null ? "-" : closest.x + "," + closest.y + "," + closest.z + " h=" + String.format(java.util.Locale.ROOT, "%.2f", closestHeuristic)) + " bestExit="
        + (bestExit == null ? "-" : bestExit.x + "," + bestExit.y + "," + bestExit.z + " score=" + String.format(java.util.Locale.ROOT, "%.2f", bestExitScore)) + " local="
        + (bestLocalProgress == null ? "-" : bestLocalProgress.x + "," + bestLocalProgress.y + "," + bestLocalProgress.z) + " boundary=" + boundaryTouches + " rejects=" + rejectSummary();
    }

    private String rejectSummary() {
      StringBuilder out = new StringBuilder(96);
      boolean[] selected = new boolean[EdgeReject.values().length];
      boolean first = true;
      for (int rank = 0; rank < 6; rank++) {
        int best = -1;
        long bestCount = 0;
        for (EdgeReject reason : EdgeReject.values()) {
          long count = edgeRejects[reason.ordinal()];
          if (count <= bestCount || selected[reason.ordinal()]) {
            continue;
          }
          best = reason.ordinal();
          bestCount = count;
        }
        if (best < 0) {
          break;
        }
        selected[best] = true;
        if (!first) {
          out.append(',');
        }
        first = false;
        out.append(EdgeReject.values()[best].label()).append(':').append(bestCount);
      }
      return first ? "-" : out.toString();
    }

    private HorsePath reconstructCertified(Node node) {
      ArrayList<Node> reversed = new ArrayList<>();
      for (Node cursor = node; cursor != null; cursor = cursor.parent) {
        reversed.add(cursor);
      }
      ArrayList<HorsePath.Waypoint> waypoints = new ArrayList<>(reversed.size());
      double estimatedTicks = 0D;
      for (int i = reversed.size() - 1; i >= 0; i--) {
        Node cursor = reversed.get(i);
        BetterBlockPos pos = new BetterBlockPos(cursor.x, cursor.y, cursor.z);
        HorsePath.Edge edge = cursor.parent == null ? new HorsePath.Source(pos.y) : cursor.edgeFromParent;
        HorsePath.Waypoint waypoint = new HorsePath.Waypoint(pos, cursor.centerX, cursor.centerZ, edge);
        estimatedTicks += edge.costTicks();
        waypoints.add(waypoint);
      }
      HorsePath raw = new HorsePath(waypoints, estimatedTicks);
      MountTuning.Planner tuning = tuning();
      if (!tuning.directCruisePulling()) {
        return raw;
      }
      return HorseDirectCruisePuller.pull(raw, tuning.directCruisePullSchedule(), (from, to) -> {
        HorseCruiseRayCertifier.CertifiedCruise cruise = cruiseRayCertifier.certify(from, to);
        return cruise == null ? null : cruise.edge();
      });
    }

    private static HorsePath stationaryPath(Node node) {
      BetterBlockPos pos = new BetterBlockPos(node.x, node.y, node.z);
      return new HorsePath(List.of(HorsePath.Waypoint.source(pos, node.centerX, node.centerZ), new HorsePath.Waypoint(pos, node.centerX, node.centerZ, new HorsePath.Stationary(0D, node.y))), 0D);
    }

  }

  private static final class Node {
    private final int x;
    private final int y;
    private final int z;
    private final double centerX;
    private final double centerZ;
    private double g = Double.POSITIVE_INFINITY;
    private double f = Double.POSITIVE_INFINITY;
    private Node parent;
    private HorsePath.Edge edgeFromParent;
    private int acceptedSuccessors;
    private int acceptedImprovingSuccessors;
    private boolean touchedBoundary;
    private boolean closed;

    private Node(int x, int y, int z) {
      this(x, y, z, x, z);
    }

    private Node(int x, int y, int z, double centerX, double centerZ) {
      this.x = x;
      this.y = y;
      this.z = z;
      this.centerX = centerX;
      this.centerZ = centerZ;
    }
  }

  private record NodeKey(int x, int y, int z, double centerX, double centerZ) {
  }

  private record Landing(int x, int y, int z, double centerX, double centerZ) {
  }

  private record GroundRide(boolean clear, boolean water, boolean supportGap) {
    private static final GroundRide BLOCKED = new GroundRide(false, false, false);
  }

  private record DropRide(boolean clear, double costTicks) {
    private static final DropRide BLOCKED = new DropRide(false, 0D);
  }

  private record EdgePlan(Landing landing, HorsePath.Edge edge) {
  }

  private record TrackedRide(HorseTrackCertifier.Plan plan, HorsePath.Track track) {
  }

  private enum EdgeReject {
    NO_PATHING_DATA("no-data"), NO_LANDING("no-landing"), LONG_GROUND_SPAN("long-ground"), TRACK_WITNESS_FAILED("track-fail"), UNSUPPORTED_GROUND("unsupported"), DIAGONAL_STEP_OVERRUN_LEDGE(
      "diag-step-ledge");

    private final String label;

    EdgeReject(String label) {
      this.label = label;
    }

    private String label() {
      return label;
    }
  }

  private record QueueEntry(Node node, double g, double f) {
  }

  private record SearchResult(Optional<HorsePath> path, String diagnostic, boolean incumbentAccepted) {
    private static SearchResult success(HorsePath path) {
      return new SearchResult(Optional.of(path), "ok", false);
    }

    private static SearchResult failure(String diagnostic) {
      return new SearchResult(Optional.empty(), diagnostic, false);
    }

    private static SearchResult acceptedIncumbent() {
      return new SearchResult(Optional.empty(), "incumbent accepted", true);
    }
  }
}
