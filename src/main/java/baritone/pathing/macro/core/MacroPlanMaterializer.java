package baritone.pathing.macro.core;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.water.WaterLineKernel;
import baritone.pathing.movement.water.WaterLineProfile;
import baritone.pathing.movement.water.WaterLineSegment;
import baritone.pathing.route.BoatRecoveryPolicy;
import baritone.pathing.route.PathRouteLeg;
import baritone.pathing.route.PlannedTransportState;
import baritone.pathing.route.RouteLeg;
import baritone.pathing.route.RoutePlan;
import baritone.pathing.route.SurfaceRouteLeg;
import baritone.pathing.transport.TransportMode;
import java.util.ArrayList;
import java.util.Optional;

public final class MacroPlanMaterializer {
  private static final double SURFACE_EXIT_GOAL_COMMIT_BLOCKS = 32D;

  private MacroPlanMaterializer() {
  }

  public static Optional<RoutePlan> materialize(CalculationContext context, MacroPlan plan, IPath localPrefix) {
    if (plan.actions().isEmpty()) {
      return Optional.empty();
    }
    int first = firstSurfaceTransition(plan);
    if (first < 0) {
      return Optional.empty();
    }
    MacroSurfaceTransition enter = plan.actions().get(first).surfaceTransition();
    if (enter.stage() != MacroSurfaceTransitionStage.ENTER && enter.stage() != MacroSurfaceTransitionStage.TRANSIT) {
      return Optional.empty();
    }
    boolean waterborneStart = false;
    if (enter.stage() == MacroSurfaceTransitionStage.TRANSIT) {
      if (localPrefix != null) {
        return Optional.empty();
      }
      waterborneStart = true;
    } else if (localPrefix != null && !localPrefix.getDest().equals(enter.dryStart())) {
      return Optional.empty();
    } else if (localPrefix == null) {
      if (plan.src().equals(enter.dryStart())) {
        waterborneStart = enter.swim() && factualWaterStart(context, plan.src());
      } else if (certifiesWetAcquisition(context, plan.src(), enter)) {
        waterborneStart = true;
      } else {
        return Optional.empty();
      }
    }
    boolean wet = waterborneStart;
    return collectSurfaceRun(plan, first).flatMap(run -> materializeSurfaceRun(context, plan, localPrefix, wet ? run.fromWater(plan.src()) : run));
  }

  public static boolean certifiesFirstSurfaceRun(CalculationContext context, MacroPlan plan) {
    int first = firstSurfaceTransition(plan);
    return first >= 0 && collectSurfaceRun(plan, first).map(run -> run.waterborneStart() ? run.fromWater(plan.src()) : run).flatMap(run -> certifySurfaceRun(context, run)).isPresent();
  }

  public static Optional<Goal> firstFactualSurfaceGoal(MacroPlan plan) {
    int forward = MacroSurfaceSessions.firstUsefulEnter(plan.actions(), plan.src(), plan.dest());
    if (forward < 0) {
      return Optional.empty();
    }
    for (int i = forward; i < plan.actions().size(); i++) {
      MacroSurfaceTransition transition = plan.actions().get(i).surfaceTransition();
      if (transition != null && transition.stage() == MacroSurfaceTransitionStage.ENTER && transition.componentId() >= 0) {
        if (transition.dryStart().equals(plan.src())) {
          return Optional.empty();
        }
        return Optional.of(new GoalBlock(transition.dryStart()));
      }
    }
    return Optional.empty();
  }

  public static String diagnostic(CalculationContext context, MacroPlan plan) {
    int first = firstSurfaceTransition(plan);
    if (first < 0) {
      return "no useful surface run";
    }
    MacroSurfaceTransition enter = plan.actions().get(first).surfaceTransition();
    Optional<SurfaceRun> run = collectSurfaceRun(plan, first);
    if (run.isEmpty()) {
      return "cannot collect contiguous " + enter.mode() + " run from " + enter.waterEnd() + " component=" + enter.componentId();
    }
    SurfaceRun surfaceRun = run.get();
    if (surfaceRun.waterborneStart()) {
      surfaceRun = surfaceRun.fromWater(plan.src());
    }
    if (surfaceRun.componentId() < 0) {
      return "first useful surface run is predicted " + surfaceRun.mode() + " from " + surfaceRun.launchDry() + " to " + surfaceRun.landing();
    }
    if (certifySurfaceRun(context, surfaceRun).isEmpty()) {
      return "factual " + surfaceRun.mode() + " run failed WaterLineKernel certification from " + surfaceRun.launchDry() + " via " + surfaceRun.waterPath().get(0) + " to " + surfaceRun.landing()
        + " component=" + surfaceRun.componentId() + " points=" + surfaceRun.waterPath().size();
    }
    return "materializable";
  }

  private static int firstSurfaceTransition(MacroPlan plan) {
    return MacroSurfaceSessions.firstUsefulStart(plan.actions(), plan.src(), plan.dest());
  }

  private static Optional<SurfaceRun> collectSurfaceRun(MacroPlan plan, int first) {
    MacroSurfaceTransition start = plan.actions().get(first).surfaceTransition();
    TransportMode mode = start.mode();
    ArrayList<BetterBlockPos> waterPath = new ArrayList<>();
    int componentId = start.componentId();
    BetterBlockPos launch;
    boolean waterborneStart;
    int cursor;
    switch (start.stage()) {
      case ENTER -> {
        waterPath.add(start.waterEnd());
        launch = start.dryStart();
        waterborneStart = false;
        cursor = first + 1;
      }
      case TRANSIT -> {
        waterPath.addAll(start.waterPath());
        launch = start.waterStart();
        waterborneStart = true;
        cursor = first + 1;
      }
      default -> {
        return Optional.empty();
      }
    }
    for (int i = cursor; i < plan.actions().size(); i++) {
      MacroSurfaceTransition transition = plan.actions().get(i).surfaceTransition();
      if (transition == null || transition.mode() != mode || transition.componentId() != componentId) {
        break;
      }
      switch (transition.stage()) {
        case TRANSIT -> {
          if (!appendTransit(waterPath, transition)) {
            return Optional.empty();
          }
        }
        case EXIT -> {
          if (waterPath.isEmpty() || !waterPath.get(waterPath.size() - 1).equals(transition.waterStart())) {
            return Optional.empty();
          }
          boolean terminal = terminalSurfaceExit(plan, i, transition);
          BetterBlockPos landing = terminal ? transition.dryEnd() : transition.waterStart();
          return waterPath.size() >= 2 ? Optional.of(new SurfaceRun(mode, launch, landing, terminal, componentId, waterPath, first, i + 1, waterborneStart)) : Optional.empty();
        }
        case ENTER -> {
          return Optional.empty();
        }
      }
    }
    return waterPath.size() >= 2 ? Optional.of(new SurfaceRun(mode, launch, waterPath.get(waterPath.size() - 1), false, componentId, waterPath, first, plan.actions().size(), waterborneStart))
      : Optional.empty();
  }

  private static boolean terminalSurfaceExit(MacroPlan plan, int actionIndex, MacroSurfaceTransition exit) {
    if (flatDistance(exit.dryEnd(), plan.dest()) <= SURFACE_EXIT_GOAL_COMMIT_BLOCKS || flatDistance(exit.waterStart(), plan.dest()) <= SURFACE_EXIT_GOAL_COMMIT_BLOCKS) {
      return true;
    }
    if (actionIndex >= plan.actions().size() - 1) {
      return true;
    }
    MacroSurfaceTransition next = plan.actions().get(actionIndex + 1).surfaceTransition();
    return next == null || next.mode() != exit.mode() || next.componentId() != exit.componentId() || next.stage() != MacroSurfaceTransitionStage.TRANSIT;
  }

  private static boolean appendTransit(ArrayList<BetterBlockPos> waterPath, MacroSurfaceTransition transition) {
    if (waterPath.isEmpty() || !waterPath.get(waterPath.size() - 1).equals(transition.waterStart())) {
      return false;
    }
    for (int i = 1; i < transition.waterPath().size(); i++) {
      BetterBlockPos pos = transition.waterPath().get(i);
      if (waterPath.isEmpty() || !waterPath.get(waterPath.size() - 1).equals(pos)) {
        waterPath.add(pos);
      }
    }
    return true;
  }

  private static Optional<RoutePlan> materializeSurfaceRun(CalculationContext context, MacroPlan plan, IPath prefix, SurfaceRun run) {
    if (run.componentId() < 0) {
      return Optional.empty();
    }
    ArrayList<RouteLeg> legs = new ArrayList<>();
    boolean boatAvailable = context.waterTransport.boatAvailable() || context.waterTransport.boatMounted();
    PlannedTransportState pedestrian = PlannedTransportState.pedestrian(boatAvailable);
    PlannedTransportState startState = run.waterborneStart() ? PlannedTransportState.swim(boatAvailable) : pedestrian;
    if (prefix != null && prefix.length() > 1) {
      legs.add(PathRouteLeg.connector(prefix, pedestrian, pedestrian));
    }
    ArrayList<WaterLineSegment> segments = certifySurfaceRun(context, run).orElse(null);
    if (segments == null) {
      return Optional.empty();
    }
    for (WaterLineSegment segment : segments) {
      PlannedTransportState inTransit = run.mode() == TransportMode.BOAT ? PlannedTransportState.boat(boatAvailable) : PlannedTransportState.swim(boatAvailable);
      PlannedTransportState entry = legs.stream().noneMatch(SurfaceRouteLeg.class::isInstance) ? startState : inTransit;
      PlannedTransportState exit = segment.terminal() ? pedestrian : inTransit;
      BoatRecoveryPolicy recovery = segment.terminal() && run.mode() == TransportMode.BOAT ? BoatRecoveryPolicy.REQUIRED : BoatRecoveryPolicy.OPTIONAL;
      legs.add(new SurfaceRouteLeg(segment, entry, exit, recovery, run.componentId()));
    }
    PlannedTransportState end = legs.get(legs.size() - 1) instanceof SurfaceRouteLeg surface ? surface.exitState()
      : run.terminal() ? pedestrian : run.mode() == TransportMode.BOAT ? PlannedTransportState.boat(boatAvailable) : PlannedTransportState.swim(boatAvailable);
    return Optional.of(RoutePlan.of(legs, startState, end, remainingMacroScore(plan, run.firstActionIndex(), run.nextActionIndex())));
  }

  private static double remainingMacroScore(MacroPlan plan, int firstMaterializedAction, int nextActionIndex) {
    double consumed = 0D;
    int end = Math.max(firstMaterializedAction, Math.min(nextActionIndex, plan.actions().size()));
    for (int i = 0; i < end; i++) {
      consumed += plan.actions().get(i).score();
    }
    return Math.max(0D, plan.totalScore() - consumed);
  }

  private static boolean certifiesWetAcquisition(CalculationContext context, BetterBlockPos start, MacroSurfaceTransition enter) {
    if (!enter.swim() || enter.componentId() < 0) {
      return false;
    }
    return certifiesWetStart(context, start, enter.waterEnd(), enter.mode());
  }

  private static boolean certifiesWetStart(CalculationContext context, BetterBlockPos start, BetterBlockPos water, TransportMode mode) {
    if (mode != TransportMode.SWIM) {
      return start.equals(water);
    }
    if (flatDistance(start, water) > 16D || Math.abs(start.y - water.y) > 2) {
      return false;
    }
    BetterBlockPos surfaceStart = new BetterBlockPos(start.x, water.y, start.z);
    WaterLineProfile profile = WaterLineProfile.overlaySwim(context.costs.waterMoveCost());
    return WaterLineKernel.traceCells(surfaceStart, water, profile.halfWidth(), (x, z, centerline) -> !centerline || profile.mode().legal(context, x, water.y, z));
  }

  private static boolean factualWaterStart(CalculationContext context, BetterBlockPos start) {
    return profile(context, TransportMode.SWIM, false, false).mode().legal(context, start.x, start.y, start.z);
  }

  private static Optional<ArrayList<WaterLineSegment>> certifySurfaceRun(CalculationContext context, SurfaceRun run) {
    ArrayList<WaterLineSegment> segments = new ArrayList<>();
    int cursor = 0;
    boolean first = true;
    while (cursor < run.waterPath().size() - 1) {
      int best = -1;
      WaterLineSegment bestSegment = null;
      int hardCap = cursor + 1;
      while (hardCap < run.waterPath().size() && flatDistance(run.waterPath().get(cursor), run.waterPath().get(hardCap)) <= Baritone.settings().macroWaterMaxSegmentBlocks.value) {
        hardCap++;
      }
      hardCap = Math.min(run.waterPath().size() - 1, hardCap - 1);
      for (int candidate = hardCap; candidate > cursor; candidate--) {
        boolean terminal = run.terminal() && candidate == run.waterPath().size() - 1;
        BetterBlockPos src = first ? run.launchDry() : run.waterPath().get(cursor);
        BetterBlockPos dest = terminal ? run.landing() : run.waterPath().get(candidate);
        WaterLineProfile profile = profile(context, run.mode(), first, terminal);
        Optional<WaterLineSegment> segment = WaterLineKernel.connect(context, src, run.waterPath().get(cursor), run.waterPath().get(candidate), dest, profile, terminal);
        if (segment.isPresent()) {
          best = candidate;
          bestSegment = segment.get();
          break;
        }
      }
      if (best < 0) {
        return Optional.empty();
      }
      segments.add(bestSegment);
      cursor = best;
      first = false;
    }
    return Optional.of(segments);
  }

  private static WaterLineProfile profile(CalculationContext context, TransportMode mode, boolean first, boolean terminal) {
    if (mode == TransportMode.BOAT) {
      return first ? WaterLineProfile.overlayBoatLaunch(context.waterTransport, terminal) : WaterLineProfile.overlayMountedBoat(context.waterTransport, terminal);
    }
    return WaterLineProfile.overlaySwim(context.costs.waterMoveCost());
  }

  private static double flatDistance(BetterBlockPos a, BetterBlockPos b) {
    return Math.hypot(a.x - b.x, a.z - b.z);
  }

  private record SurfaceRun(TransportMode mode, BetterBlockPos launchDry, BetterBlockPos landing, boolean terminal, int componentId, ArrayList<BetterBlockPos> waterPath, int firstActionIndex,
    int nextActionIndex, boolean waterborneStart) {

    private SurfaceRun fromWater(BetterBlockPos src) {
      return new SurfaceRun(mode, src, landing, terminal, componentId, waterPath, firstActionIndex, nextActionIndex, true);
    }
  }
}
