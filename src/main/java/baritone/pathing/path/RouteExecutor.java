package baritone.pathing.path;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.behavior.PathingBehavior;
import baritone.pathing.control.ControlFrame;
import baritone.pathing.route.PathRouteLeg;
import baritone.pathing.route.PlannedTransportState;
import baritone.pathing.route.RouteLeg;
import baritone.pathing.route.RouteFactFootprint;
import baritone.pathing.route.RoutePlan;
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
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

/**
 * Executes a heterogeneous route. Legacy paths are represented as a one-leg route; explicit macro routes
 * are ordinary route plans and never masquerade as {@link IPath}s internally.
 */
public final class RouteExecutor implements IPathExecutor, Helper {
  private final PathingBehavior behavior;
  private final RoutePlan route;
  private final IPath legacyPath;
  private int legIndex;
  private RouteLegController activeController;
  private ControlFrame controlFrame = ControlFrame.EMPTY;
  private TransportControl transportControl;
  private boolean failed;
  private boolean sprintNextTick;

  public RouteExecutor(PathingBehavior behavior, IPath path) {
    this(behavior, RoutePlan.legacy(path, physicalState(behavior)), null);
  }

  public RouteExecutor(PathingBehavior behavior, RoutePlan route) {
    this(behavior, route, null);
  }

  private RouteExecutor(PathingBehavior behavior, PathRouteLegController transplantedLegacyController) {
    this(behavior, RoutePlan.legacy(transplantedLegacyController.getPath(), physicalState(behavior)), transplantedLegacyController);
  }

  private RouteExecutor(PathingBehavior behavior, RoutePlan route, RouteLegController transplantedController) {
    this(behavior, route, transplantedController, 0);
  }

  private RouteExecutor(PathingBehavior behavior, RoutePlan route, RouteLegController transplantedController, int legIndex) {
    this.behavior = behavior;
    this.route = route;
    this.legacyPath = route.soleLegacyPath().orElse(null);
    this.legIndex = legIndex;
    this.activeController = transplantedController == null ? controller(route.legs().get(legIndex)) : transplantedController;
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
      activeController = controller(route.legs().get(legIndex));
    }
    return true;
  }

  private RouteLegController controller(RouteLeg leg) {
    return switch (leg) {
      case PathRouteLeg pathLeg -> new PathRouteLegController(behavior, pathLeg.path(), pathLeg.surfaceOverlay());
      case SurfaceRouteLeg surfaceLeg -> new SurfaceRouteLegController(behavior, surfaceLeg);
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
    return leg instanceof PathRouteLeg pathLeg ? pathLeg.path().movements().size() : 1;
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
      } else {
        sequence.append('P');
      }
      emitted++;
    }
    return sequence.toString();
  }

  public RouteRenderPlan renderPlan(boolean current) {
    ArrayList<RouteRenderPlan.Segment> segments = new ArrayList<>();
    ArrayList<RouteRenderPlan.Anchor> anchors = new ArrayList<>();
    for (int i = 0; i < route.legs().size(); i++) {
      RouteLeg leg = route.legs().get(i);
      boolean active = i == legIndex;
      if (leg instanceof PathRouteLeg && activeController instanceof PathRouteLegController pathController && active) {
        pathController.appendRenderPlan(segments, anchors, current);
        continue;
      }
      int startIndex = active && current && leg instanceof PathRouteLeg ? Math.max(activeController.getPosition() - 3, 0) : 0;
      TransportMode mode = leg instanceof SurfaceRouteLeg surface ? surface.segment().mode() : TransportMode.PEDESTRIAN;
      boolean terminal = leg instanceof SurfaceRouteLeg surface && surface.terminal();
      int componentId = leg instanceof SurfaceRouteLeg surface ? surface.componentId() : -1;
      List<BetterBlockPos> positions = leg.renderPositions();
      if (positions.size() >= 2) {
        segments.add(new RouteRenderPlan.Segment(mode, terminal, active, componentId, positions, startIndex));
      }
      if (leg instanceof SurfaceRouteLeg surface) {
        anchors.add(new RouteRenderPlan.Anchor(RouteRenderPlan.AnchorKind.LAUNCH, surface.src()));
        anchors.add(new RouteRenderPlan.Anchor(RouteRenderPlan.AnchorKind.ENTRY, surface.segment().waterStart()));
        if (surface.terminal()) {
          anchors.add(new RouteRenderPlan.Anchor(RouteRenderPlan.AnchorKind.TERMINAL, surface.dest()));
        }
      }
    }
    return new RouteRenderPlan(segments, anchors);
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

  public Optional<RouteExecutor> tryReplaceSuffix(RouteExecutor replacement, int minimumAnchorIndex) {
    if (replacement == null) {
      return Optional.empty();
    }
    if (activeController instanceof PathRouteLegController currentPath && replacement.activeController instanceof PathRouteLegController replacementPath && legacy() && replacement.legacy()) {
      return currentPath.tryReplaceSuffix(replacementPath, minimumAnchorIndex).map(controller -> new RouteExecutor(behavior, controller));
    }
    if (legacy() || replacement.src().equals(route.src())) {
      return Optional.empty();
    }
    int boundary = suffixBoundary(replacement.src());
    if (boundary <= legIndex || boundary > route.legs().size()) {
      return Optional.empty();
    }
    ArrayList<RouteLeg> legs = new ArrayList<>(route.legs().subList(0, boundary));
    legs.addAll(replacement.route.legs());
    return Optional.of(new RouteExecutor(behavior, RoutePlan.of(legs, route.startState(), replacement.route.endState(), replacement.route.estimatedContinuationTicks()), activeController, legIndex));
  }

  private int suffixBoundary(BetterBlockPos anchor) {
    for (int i = legIndex; i < route.legs().size(); i++) {
      RouteLeg leg = route.legs().get(i);
      if (leg.dest().equals(anchor)) {
        return i + 1;
      }
      if (i > legIndex && leg.src().equals(anchor)) {
        return i;
      }
    }
    return -1;
  }

  private boolean legacy() {
    return legacyPath != null && route.legs().size() == 1;
  }

  public boolean containsPathPosition(BlockPos pos) {
    return activeController != null && activeController.containsPathPosition(pos) || route.contains(pos);
  }

  public Optional<RouteExecutor> reanchor(BlockPos pos) {
    if (containsExecutableAnchor(pos, legIndex)) {
      return Optional.of(this);
    }
    for (int i = legIndex + 1; i < route.legs().size(); i++) {
      if (containsExecutableAnchor(pos, i)) {
        return Optional.of(new RouteExecutor(behavior, route.suffix(i)));
      }
    }
    return Optional.empty();
  }

  private boolean containsExecutableAnchor(BlockPos pos, int index) {
    if (index >= route.legs().size()) {
      return false;
    }
    RouteLeg leg = route.legs().get(index);
    if (index == legIndex && activeController != null && activeController.containsPathPosition(pos)) {
      return true;
    }
    if (leg.contains(pos)) {
      return true;
    }
    if (leg instanceof SurfaceRouteLeg surface) {
      return surface.acceptsCorridor(pos,
        behavior.ctx.player().isInWater() || behavior.ctx.player().isEyeInFluid(net.minecraft.tags.FluidTags.WATER) || behavior.ctx.world().getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER));
    }
    return false;
  }

  @Override
  public IPath getPath() { return legacyPath; }

  public Optional<IPath> legacyPath() {
    return Optional.ofNullable(legacyPath);
  }

  public boolean failed() {
    return failed || activeController != null && activeController.failed();
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

  private static PlannedTransportState physicalState(PathingBehavior behavior) {
    IPlayerContext ctx = behavior.ctx;
    boolean boatAvailable = ((Baritone) behavior.baritone).getInventoryBehavior().hasBoat() || ctx.player().getVehicle() instanceof AbstractBoat;
    if (ctx.player().getVehicle() instanceof AbstractBoat) {
      return PlannedTransportState.boat(boatAvailable);
    }
    return PlannedTransportState.pedestrian(boatAvailable);
  }
}
