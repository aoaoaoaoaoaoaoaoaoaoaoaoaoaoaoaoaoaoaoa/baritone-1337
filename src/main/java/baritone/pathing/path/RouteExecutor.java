package baritone.pathing.path;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.behavior.PathingBehavior;
import baritone.pathing.control.ControlFrame;
import baritone.pathing.mounted.HorsePath;
import baritone.pathing.route.HorseRouteLeg;
import baritone.pathing.route.PathRouteLeg;
import baritone.pathing.route.PlannedTransportState;
import baritone.pathing.route.RouteLeg;
import baritone.pathing.route.RouteFactFootprint;
import baritone.pathing.route.RoutePlan;
import baritone.pathing.route.RouteProgress;
import baritone.pathing.route.RouteRenderPlan;
import baritone.pathing.route.SurfaceRouteLeg;
import baritone.pathing.transport.TransportControl;
import baritone.pathing.transport.TransportMode;
import baritone.pathing.transport.TransportSnapshot;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

/**
 * Executes a heterogeneous route. Legacy paths are represented as a one-leg route; explicit macro routes
 * are ordinary route plans and never masquerade as {@link IPath}s internally.
 */
public final class RouteExecutor implements IPathExecutor, Helper {
  private final PathingBehavior behavior;
  private final RoutePlan route;
  private final IPath legacyPath;
  private final Goal terminalGoal;
  private int legIndex;
  private RouteLegController activeController;
  private ControlFrame controlFrame = ControlFrame.EMPTY;
  private TransportControl transportControl;
  private boolean failed;
  private boolean sprintNextTick;

  public RouteExecutor(PathingBehavior behavior, IPath path) {
    this(behavior, RoutePlan.legacy(path, physicalState(behavior)), null, path.getGoal());
  }

  public RouteExecutor(PathingBehavior behavior, RoutePlan route) {
    this(behavior, route, null, behavior.getGoal());
  }

  public RouteExecutor(PathingBehavior behavior, RoutePlan route, Goal terminalGoal) {
    this(behavior, route, null, terminalGoal);
  }

  private RouteExecutor(PathingBehavior behavior, PathRouteLegController transplantedLegacyController) {
    this(behavior, RoutePlan.legacy(transplantedLegacyController.getPath(), physicalState(behavior)), transplantedLegacyController, transplantedLegacyController.getPath().getGoal());
  }

  private RouteExecutor(PathingBehavior behavior, RoutePlan route, RouteLegController transplantedController, Goal terminalGoal) {
    this(behavior, route, transplantedController, 0, terminalGoal);
  }

  private RouteExecutor(PathingBehavior behavior, RoutePlan route, RouteLegController transplantedController, int legIndex, Goal terminalGoal) {
    this.behavior = behavior;
    this.route = route;
    this.legacyPath = route.soleLegacyPath().orElse(null);
    this.legIndex = legIndex;
    this.terminalGoal = terminalGoal;
    this.activeController = transplantedController == null ? controller(route.legs().get(legIndex), legIndex + 1 >= route.legs().size()) : transplantedController;
  }

  public boolean onTick() {
    controlFrame = ControlFrame.EMPTY;
    transportControl = null;
    sprintNextTick = false;
    if (failed || legIndex >= route.legs().size()) {
      return true;
    }
    while (legIndex < route.legs().size()) {
      boolean safeToCancel = activeController.onTick();
      controlFrame = activeController.controlFrame();
      transportControl = activeController.transportControl();
      sprintNextTick = activeController.isSprinting();
      if (activeController.failed()) {
        failed = true;
        controlFrame = ControlFrame.EMPTY;
        return true;
      }
      if (!activeController.finished()) {
        return safeToCancel;
      }
      legIndex++;
      controlFrame = ControlFrame.EMPTY;
      transportControl = null;
      sprintNextTick = false;
      if (legIndex >= route.legs().size()) {
        return true;
      }
      activeController = controller(route.legs().get(legIndex), legIndex + 1 >= route.legs().size());
    }
    return true;
  }

  private RouteLegController controller(RouteLeg leg, boolean routeTerminal) {
    return switch (leg) {
      case PathRouteLeg pathLeg -> new PathRouteLegController(behavior, pathLeg.path(), pathLeg.surfaceOverlay());
      case SurfaceRouteLeg surfaceLeg -> new SurfaceRouteLegController(behavior, surfaceLeg);
      case HorseRouteLeg horseLeg -> new HorseRouteLegController(behavior, horseLeg, routeTerminal, terminalGoal);
    };
  }

  public RoutePlan route() {
    return route;
  }

  public RouteFactFootprint factFootprint() {
    return RouteFactFootprint.of(route);
  }

  public BetterBlockPos src() {
    return route.src();
  }

  public BetterBlockPos dest() {
    return route.dest();
  }

  public double estimatedTicks() {
    return route.estimatedTicks();
  }

  public double estimatedContinuationTicks() {
    return route.estimatedContinuationTicks();
  }

  public double estimatedTicksRemainingFromCurrent() {
    if (legacyPath != null && activeController instanceof PathRouteLegController) {
      return legacyPath.ticksRemainingFrom(Math.max(0, Math.min(activeController.getPosition(), legacyPath.movements().size())));
    }
    double sum = activeController == null || activeController.finished() ? 0D : activeController.estimatedTicksRemaining(route.legs().get(legIndex));
    for (int i = legIndex + 1; i < route.legs().size(); i++) {
      sum += route.legs().get(i).estimatedTicks();
    }
    return sum;
  }

  public BetterBlockPos planAheadStart() {
    if (legacyPath != null || legIndex >= route.legs().size()) {
      return route.dest();
    }
    return route.legs().get(legIndex).dest();
  }

  @Override
  public int getPosition() {
    int position = 0;
    for (int i = 0; i < legIndex && i < route.legs().size(); i++) {
      position += size(route.legs().get(i));
    }
    return position + (activeController == null ? 0 : activeController.getPosition());
  }

  public int size() {
    int size = 0;
    for (RouteLeg leg : route.legs()) {
      size += size(leg);
    }
    return size;
  }

  private static int size(RouteLeg leg) {
    return leg instanceof PathRouteLeg pathLeg ? pathLeg.path().movements().size() : leg instanceof HorseRouteLeg horseLeg ? horseLeg.path().edgeCount() : 1;
  }

  public int legIndex() {
    return legIndex;
  }

  public RouteLeg activeLeg() {
    return legIndex >= route.legs().size() ? null : route.legs().get(legIndex);
  }

  public TransportControl transportControl() {
    return transportControl;
  }

  public ControlFrame controlFrame() {
    return controlFrame;
  }

  public TransportSnapshot.Plan transportPlan(IPlayerContext ctx) {
    return activeController == null ? null : activeController.transportPlan(ctx);
  }

  public String transportSequence(IPlayerContext ctx, int limit) {
    StringBuilder sequence = new StringBuilder(limit * 2);
    int emitted = 0;
    for (int i = legIndex; i < route.legs().size() && emitted < limit; i++) {
      RouteLeg leg = route.legs().get(i);
      if (i != legIndex) {
        sequence.append('>');
      }
      TransportSnapshot.Plan activePlan = i == legIndex && activeController != null ? activeController.transportPlan(ctx) : null;
      if (activePlan != null) {
        sequence.append(activePlan.token());
      } else if (leg instanceof SurfaceRouteLeg surface) {
        sequence.append(surface.segment().mode() == TransportMode.BOAT ? surface.segment().terminal() ? 'B' : 'b' : 'S');
      } else if (leg instanceof HorseRouteLeg) {
        sequence.append('H');
      } else {
        sequence.append('P');
      }
      emitted++;
    }
    return sequence.toString();
  }

  public RouteRenderPlan renderPlan(boolean current) {
    return renderPlan(current, current ? commitmentEnd(0D, 1) : RouteProgress.origin());
  }

  public RouteRenderPlan renderPlan(boolean current, RouteProgress committedUntil) {
    ArrayList<RouteRenderPlan.Segment> segments = new ArrayList<>();
    ArrayList<RouteRenderPlan.Anchor> anchors = new ArrayList<>();
    RouteProgress cursor = RouteProgress.origin();
    for (int i = 0; i < route.legs().size(); i++) {
      RouteLeg leg = route.legs().get(i);
      int legSize = size(leg);
      if (current && i < legIndex) {
        cursor = cursor.plus(legSize, leg.estimatedTicks());
        continue;
      }
      boolean active = i == legIndex;
      int committedLocalUnits = current ? Math.max(0, Math.min(legSize, committedUntil.units() - cursor.units())) : 0;
      if (leg instanceof PathRouteLeg && activeController instanceof PathRouteLegController pathController && active) {
        pathController.appendRenderPlan(segments, anchors, current, committedLocalUnits);
        cursor = cursor.plus(legSize, leg.estimatedTicks());
        continue;
      }
      if (leg instanceof HorseRouteLeg horse) {
        appendHorseRenderPlan(segments, horse, active, current, committedLocalUnits);
        cursor = cursor.plus(legSize, leg.estimatedTicks());
        continue;
      }
      int startIndex = active && current && leg instanceof PathRouteLeg ? Math.max(activeController.getPosition() - 3, 0) : 0;
      TransportMode mode = leg instanceof SurfaceRouteLeg surface ? surface.segment().mode() : TransportMode.PEDESTRIAN;
      boolean terminal = leg instanceof SurfaceRouteLeg surface && surface.terminal();
      int componentId = leg instanceof SurfaceRouteLeg surface ? surface.componentId() : -1;
      List<BetterBlockPos> positions = leg.renderPositions();
      if (positions.size() >= 2) {
        RouteRenderPlan.SegmentRole role =
          current && cursor.plus(legSize, leg.estimatedTicks()).reaches(committedUntil) ? RouteRenderPlan.SegmentRole.COMMITTED : RouteRenderPlan.SegmentRole.CANDIDATE;
        segments.add(new RouteRenderPlan.Segment(mode, terminal, active, componentId, positions, startIndex, RouteRenderPlan.SegmentKind.NORMAL, role));
      }
      if (leg instanceof SurfaceRouteLeg surface) {
        anchors.add(new RouteRenderPlan.Anchor(RouteRenderPlan.AnchorKind.LAUNCH, surface.src()));
        anchors.add(new RouteRenderPlan.Anchor(RouteRenderPlan.AnchorKind.ENTRY, surface.segment().waterStart()));
        if (surface.terminal()) {
          anchors.add(new RouteRenderPlan.Anchor(RouteRenderPlan.AnchorKind.TERMINAL, surface.dest()));
        }
      }
      cursor = cursor.plus(legSize, leg.estimatedTicks());
    }
    return new RouteRenderPlan(segments, anchors);
  }

  private void appendHorseRenderPlan(ArrayList<RouteRenderPlan.Segment> segments, HorseRouteLeg leg, boolean active, boolean current, int committedUntilPosition) {
    List<HorsePath.Waypoint> waypoints = leg.path().waypoints();
    if (waypoints.size() < 2) {
      return;
    }
    int edge = active && current && activeController != null ? Math.max(0, Math.min(activeController.getPosition(), waypoints.size() - 2)) : 0;
    int initialEdge = edge;
    BetterBlockPos liveStart = active && current && behavior != null && behavior.ctx.player().getVehicle() instanceof AbstractHorse horse ? HorsePath.vehicleFeet(horse) : null;
    while (edge < waypoints.size() - 1) {
      HorsePath.EdgeRegime kind = waypoints.get(edge + 1).edgeFromPrevious().regime();
      int end = edge + 1;
      while (end + 1 < waypoints.size() && waypoints.get(end + 1).edgeFromPrevious().regime() == kind) {
        end++;
      }
      ArrayList<BetterBlockPos> positions = new ArrayList<>(end - edge + 1);
      for (int i = edge; i <= end; i++) {
        positions.add(i == edge && edge == initialEdge && liveStart != null ? liveStart : waypoints.get(i).pos());
      }
      RouteRenderPlan.SegmentRole role = current && end <= committedUntilPosition ? RouteRenderPlan.SegmentRole.COMMITTED : RouteRenderPlan.SegmentRole.CANDIDATE;
      segments.add(new RouteRenderPlan.Segment(TransportMode.HORSE, false, active, -1, positions, 0, RouteRenderPlan.SegmentKind.NORMAL, role));
      edge = end;
    }
  }

  public RouteExecutor trySplice(RouteExecutor next) {
    if (!(activeController instanceof PathRouteLegController currentPath) || !legacy()) {
      return this;
    }
    if (next != null && (!(next.activeController instanceof PathRouteLegController) || !next.legacy())) {
      return this;
    }
    PathRouteLegController spliced = currentPath.trySplice(next == null ? null : (PathRouteLegController) next.activeController);
    return spliced == currentPath ? this : new RouteExecutor(behavior, spliced);
  }

  public boolean snipsnapifpossible() {
    return activeController instanceof PathRouteLegController pathController && pathController.snipsnapifpossible();
  }

  public Optional<RouteExecutor> tryReplaceSuffix(RouteExecutor replacement, RouteProgress minimumAnchor) {
    if (replacement == null) {
      return Optional.empty();
    }
    if (activeController instanceof PathRouteLegController currentPath && replacement.activeController instanceof PathRouteLegController replacementPath && legacy() && replacement.legacy()) {
      return currentPath.tryReplaceSuffix(replacementPath, minimumAnchor.units()).map(controller -> {
        PlannedTransportState state = physicalState(behavior);
        RoutePlan plan = RoutePlan.of(List.of(PathRouteLeg.legacy(controller.getPath(), state)), state, replacement.route.endState(), replacement.route.estimatedContinuationTicks());
        return new RouteExecutor(behavior, plan, controller, terminalGoal);
      });
    }
    if (legacy() || replacement.src().equals(route.src())) {
      return Optional.empty();
    }
    SuffixBoundary boundary = suffixBoundary(replacement.src());
    if (boundary == null || boundary.leg() <= legIndex || boundary.leg() > route.legs().size() || !boundary.progress().reaches(minimumAnchor)) {
      return Optional.empty();
    }
    ArrayList<RouteLeg> legs = new ArrayList<>(route.legs().subList(0, boundary.leg()));
    legs.addAll(replacement.route.legs());
    return Optional
      .of(new RouteExecutor(behavior, RoutePlan.of(legs, route.startState(), replacement.route.endState(), replacement.route.estimatedContinuationTicks()), activeController, legIndex, terminalGoal));
  }

  private SuffixBoundary suffixBoundary(BetterBlockPos anchor) {
    RouteProgress progress = RouteProgress.origin();
    for (int i = 0; i < legIndex && i < route.legs().size(); i++) {
      RouteLeg leg = route.legs().get(i);
      progress = progress.plus(size(leg), leg.estimatedTicks());
    }
    for (int i = legIndex; i < route.legs().size(); i++) {
      RouteLeg leg = route.legs().get(i);
      if (leg.dest().equals(anchor)) {
        return new SuffixBoundary(i + 1, progress.plus(size(leg), leg.estimatedTicks()));
      }
      if (i > legIndex && leg.src().equals(anchor)) {
        return new SuffixBoundary(i, progress);
      }
      progress = progress.plus(size(leg), leg.estimatedTicks());
    }
    return null;
  }

  private boolean legacy() {
    return legacyPath != null && route.legs().size() == 1;
  }

  public boolean containsPathPosition(BlockPos pos) {
    return activeController != null && activeController.containsPathPosition(pos) || route.contains(pos);
  }

  public Optional<RouteExecutor> reanchor(BlockPos pos) {
    Optional<RouteExecutor> active = reanchorAtLeg(pos, legIndex);
    if (active.isPresent()) {
      return active;
    }
    for (int i = legIndex + 1; i < route.legs().size(); i++) {
      Optional<RouteExecutor> anchored = reanchorAtLeg(pos, i);
      if (anchored.isPresent()) {
        return anchored;
      }
    }
    return Optional.empty();
  }

  public Optional<HorsePath.Waypoint> horseWaypointAtOrAfterCurrent(BlockPos pos) {
    long packed = pos.asLong();
    for (int i = legIndex; i < route.legs().size(); i++) {
      RouteLeg leg = route.legs().get(i);
      if (!(leg instanceof HorseRouteLeg horse)) {
        continue;
      }
      List<HorsePath.Waypoint> waypoints = horse.path().waypoints();
      int first = i == legIndex && activeController != null ? Math.max(0, Math.min(activeController.getPosition(), waypoints.size() - 1)) : 0;
      for (int waypoint = first; waypoint < waypoints.size(); waypoint++) {
        HorsePath.Waypoint candidate = waypoints.get(waypoint);
        if (candidate.pos().asLong() == packed) {
          return Optional.of(candidate);
        }
      }
    }
    return Optional.empty();
  }

  private Optional<RouteExecutor> reanchorAtLeg(BlockPos pos, int index) {
    if (index >= route.legs().size()) {
      return Optional.empty();
    }
    RouteLeg leg = route.legs().get(index);
    if (leg instanceof HorseRouteLeg horse) {
      int firstWaypoint = index == legIndex && activeController != null ? activeController.getPosition() : 0;
      return horse.reanchor(pos, firstWaypoint).map(anchoredHorse -> reanchoredRoute(index, anchoredHorse));
    }
    if (index == legIndex && activeController != null && activeController.containsPathPosition(pos)) {
      return Optional.of(this);
    }
    if (leg.contains(pos)) {
      return Optional.of(index == legIndex ? this : new RouteExecutor(behavior, route.suffix(index), terminalGoal));
    }
    if (leg instanceof SurfaceRouteLeg surface) {
      boolean wet =
        behavior.ctx.player().isInWater() || behavior.ctx.player().isEyeInFluid(net.minecraft.tags.FluidTags.WATER) || behavior.ctx.world().getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER);
      if (surface.acceptsCorridor(pos, wet)) {
        return Optional.of(index == legIndex ? this : new RouteExecutor(behavior, route.suffix(index), terminalGoal));
      }
    }
    return Optional.empty();
  }

  private RouteExecutor reanchoredRoute(int index, HorseRouteLeg anchoredHorse) {
    if (index == legIndex && anchoredHorse == route.legs().get(index)) {
      return this;
    }
    ArrayList<RouteLeg> legs = new ArrayList<>(route.legs().size() - index);
    legs.add(anchoredHorse);
    legs.addAll(route.legs().subList(index + 1, route.legs().size()));
    return new RouteExecutor(behavior, RoutePlan.of(legs, anchoredHorse.entryState(), route.endState(), route.estimatedContinuationTicks()), terminalGoal);
  }

  @Override
  public IPath getPath() { return legacyPath; }

  public Optional<IPath> legacyPath() {
    return Optional.ofNullable(legacyPath);
  }

  public boolean failed() {
    return failed || activeController != null && activeController.failed();
  }

  public String failureReason() {
    if (failed) {
      return activeController == null ? "route failed" : activeController.failureReason();
    }
    if (activeController != null && activeController.failed()) {
      return activeController.failureReason();
    }
    return null;
  }

  public boolean finished() {
    return failed() || legIndex >= route.legs().size();
  }

  public Set<BlockPos> toBreak() {
    return activeController == null ? Collections.emptySet() : activeController.toBreak();
  }

  public Set<BlockPos> toPlace() {
    return activeController == null ? Collections.emptySet() : activeController.toPlace();
  }

  public Set<BlockPos> toWalkInto() {
    return activeController == null ? Collections.emptySet() : activeController.toWalkInto();
  }

  public boolean isSprinting() { return activeController != null && activeController.isSprinting() || sprintNextTick; }

  public RouteProgress progress() {
    RouteProgress progress = RouteProgress.origin();
    for (int i = 0; i < legIndex && i < route.legs().size(); i++) {
      RouteLeg leg = route.legs().get(i);
      progress = progress.plus(size(leg), leg.estimatedTicks());
    }
    if (legIndex < route.legs().size() && activeController != null) {
      RouteLeg leg = route.legs().get(legIndex);
      progress = progress.plus(activeController.getPosition(), Math.max(0D, leg.estimatedTicks() - activeController.estimatedTicksRemaining(leg)));
    }
    return progress;
  }

  public Optional<RouteProgress> progressOfExact(BlockPos pos) {
    RouteProgress cursor = RouteProgress.origin();
    long packed = pos.asLong();
    for (RouteLeg leg : route.legs()) {
      int legSize = size(leg);
      if (leg.src().asLong() == packed) {
        return Optional.of(cursor);
      }
      if (leg instanceof HorseRouteLeg horse) {
        RouteProgress edge = cursor;
        List<HorsePath.Waypoint> waypoints = horse.path().waypoints();
        for (int i = 1; i < waypoints.size(); i++) {
          edge = edge.plus(1, waypoints.get(i).costFromPrevious());
          if (waypoints.get(i).pos().asLong() == packed) {
            return Optional.of(edge);
          }
        }
      } else if (leg instanceof PathRouteLeg pathLeg) {
        List<BetterBlockPos> positions = pathLeg.path().positions();
        for (int i = 1; i < positions.size(); i++) {
          if (positions.get(i).asLong() == packed) {
            return Optional.of(cursor.plus(i, Math.max(0D, pathLeg.path().ticksRemainingFrom(0) - pathLeg.path().ticksRemainingFrom(i))));
          }
        }
      } else if (leg.dest().asLong() == packed) {
        return Optional.of(cursor.plus(legSize, leg.estimatedTicks()));
      }
      cursor = cursor.plus(legSize, leg.estimatedTicks());
    }
    return Optional.empty();
  }

  public RouteProgress commitmentEnd(double aheadTicks, int minimumAheadUnits) {
    RouteProgress live = progress();
    RouteProgress byMinimumUnits = progressAtOrAfterUnits(live.units() + Math.max(1, minimumAheadUnits));
    RouteProgress byTicks = progressAtOrAfterTicks(live.ticks() + Math.max(0D, aheadTicks));
    return byTicks.reaches(byMinimumUnits) ? byTicks : byMinimumUnits;
  }

  public BetterBlockPos planAheadStart(double aheadTicks) {
    return planAheadStart(aheadTicks, 1);
  }

  public BetterBlockPos planAheadStart(double aheadTicks, int minimumAheadUnits) {
    RouteProgress target = commitmentEnd(aheadTicks, minimumAheadUnits);
    return legacyPath == null ? legBoundaryAtOrAfterTicks(target.ticks()) : positionAtProgress(target.units());
  }

  private RouteProgress boundaryAtOrAfterTicks(double targetTicks) {
    RouteProgress progress = RouteProgress.origin();
    for (RouteLeg leg : route.legs()) {
      progress = progress.plus(size(leg), leg.estimatedTicks());
      if (progress.ticks() >= targetTicks) {
        return progress;
      }
    }
    return progress;
  }

  private BetterBlockPos legBoundaryAtOrAfterTicks(double targetTicks) {
    double ticks = 0D;
    for (RouteLeg leg : route.legs()) {
      ticks += leg.estimatedTicks();
      if (ticks >= targetTicks) {
        return leg.dest();
      }
    }
    return route.dest();
  }

  private RouteProgress progressAtOrAfterTicks(double targetTicks) {
    RouteProgress progress = RouteProgress.origin();
    for (RouteLeg leg : route.legs()) {
      if (leg instanceof HorseRouteLeg horse) {
        List<HorsePath.Waypoint> waypoints = horse.path().waypoints();
        for (int i = 1; i < waypoints.size(); i++) {
          progress = progress.plus(1, waypoints.get(i).costFromPrevious());
          if (progress.ticks() >= targetTicks) {
            return progress;
          }
        }
      } else if (leg instanceof PathRouteLeg pathLeg) {
        IPath path = pathLeg.path();
        for (int i = 1; i <= size(leg); i++) {
          progress = progress.plus(1, path.movements().get(i - 1).getCost());
          if (progress.ticks() >= targetTicks) {
            return progress;
          }
        }
      } else {
        progress = progress.plus(size(leg), leg.estimatedTicks());
        if (progress.ticks() >= targetTicks) {
          return progress;
        }
      }
    }
    return progress;
  }

  private RouteProgress progressAtOrAfterUnits(int targetUnits) {
    RouteProgress progress = RouteProgress.origin();
    for (RouteLeg leg : route.legs()) {
      if (leg instanceof HorseRouteLeg horse) {
        List<HorsePath.Waypoint> waypoints = horse.path().waypoints();
        for (int i = 1; i < waypoints.size(); i++) {
          progress = progress.plus(1, waypoints.get(i).costFromPrevious());
          if (progress.units() >= targetUnits) {
            return progress;
          }
        }
      } else if (leg instanceof PathRouteLeg pathLeg) {
        IPath path = pathLeg.path();
        for (int i = 1; i <= size(leg); i++) {
          progress = progress.plus(1, path.movements().get(i - 1).getCost());
          if (progress.units() >= targetUnits) {
            return progress;
          }
        }
      } else {
        progress = progress.plus(size(leg), leg.estimatedTicks());
        if (progress.units() >= targetUnits) {
          return progress;
        }
      }
    }
    return progress;
  }

  private BetterBlockPos positionAtProgress(int units) {
    int cursor = 0;
    for (RouteLeg leg : route.legs()) {
      int legSize = size(leg);
      if (units <= cursor + legSize) {
        if (leg instanceof HorseRouteLeg horse) {
          int index = Math.max(0, Math.min(horse.path().waypoints().size() - 1, units - cursor));
          return horse.path().waypoints().get(index).pos();
        }
        List<BetterBlockPos> positions = leg.renderPositions();
        int index = Math.max(0, Math.min(positions.size() - 1, units - cursor));
        return positions.get(index);
      }
      cursor += legSize;
    }
    return route.dest();
  }

  private static PlannedTransportState physicalState(PathingBehavior behavior) {
    IPlayerContext ctx = behavior.ctx;
    boolean boatAvailable = ((Baritone) behavior.baritone).getInventoryBehavior().hasBoat() || ctx.player().getVehicle() instanceof AbstractBoat;
    if (ctx.player().getVehicle() instanceof AbstractBoat) {
      return PlannedTransportState.boat(boatAvailable);
    }
    if (ctx.player().getVehicle() instanceof AbstractHorse) {
      return PlannedTransportState.horse(boatAvailable);
    }
    return PlannedTransportState.pedestrian(boatAvailable);
  }

  private record SuffixBoundary(int leg, RouteProgress progress) {
  }
}
