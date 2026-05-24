package baritone.behavior;

import baritone.pathing.movement.MovementClientHelper;

import baritone.Baritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.event.events.*;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.PathingCommand;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.PathCalculationResult;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.api.event.events.type.EventState;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.calc.ActivePathCalculation;
import baritone.pathing.calc.FrontierValueObjective;
import baritone.pathing.calc.LocalExitObjective;
import baritone.pathing.calc.PathingIncumbentPolicy;
import baritone.pathing.calc.PedestrianLocalHotPlanner;
import baritone.pathing.calc.PlanningProbe;
import baritone.pathing.control.ControlArbiter;
import baritone.pathing.farfield.FarfieldNavigator;
import baritone.pathing.farfield.FarfieldObjective;
import baritone.pathing.goal.GoalTerminalPolicy;
import baritone.pathing.macro.core.MacroCoordinator;
import baritone.pathing.macro.core.MacroDirective;
import baritone.pathing.macro.core.MacroPlan;
import baritone.pathing.macro.core.MacroTraversalProfile;
import baritone.pathing.mounted.HorsePath;
import baritone.pathing.mounted.HorseRoutePlanner;
import baritone.pathing.mounted.MountTuning;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.path.RouteExecutor;
import baritone.pathing.route.RouteFactFootprint;
import baritone.pathing.route.RoutePlan;
import baritone.pathing.route.RouteProgress;
import baritone.pathing.transport.TransportMode;
import baritone.pathing.transport.TransportSnapshot;
import baritone.utils.PathRenderer;
import baritone.utils.PathingCommandContext;
import baritone.utils.pathing.Favoring;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;

public final class PathingBehavior extends Behavior implements IPathingBehavior, Helper {

  private static final int SUFFIX_REPLAN_MIN_ANCHOR_ADVANCE = 12;
  private static final int SUFFIX_REPLAN_TARGET_ANCHOR_ADVANCE = 48;
  private static final int SAME_ANCHOR_FACT_REPLAN_INTERVAL_TICKS = 20;
  private static final int OPPORTUNISTIC_FACT_REPLAN_INTERVAL_TICKS = 10;
  private static final int OPPORTUNISTIC_FACT_REPLAN_MIN_ADVANCE_BLOCKS = 0;
  private static final double SUFFIX_REPLACEMENT_COST_EPSILON = 2D;
  private static final double INITIAL_REPLAN_WALL_TICKS = 40D;
  private static final double REPLAN_WALL_TICKS_ALPHA = 0.18D;
  private static final double COMMITMENT_MIN_TICKS = 14D;
  private static final double COMMITMENT_MAX_TICKS = 120D;
  private static final double COMMITMENT_LATENCY_MULTIPLIER = 1.35D;
  private static final double COMMITMENT_BUFFER_TICKS = 8D;
  private static final int COMMITMENT_MIN_UNITS = 1;

  private RouteExecutor current;
  private RouteExecutor next;

  private Goal goal;
  private CalculationContext context;

  /*eta*/
  private int ticksElapsedSoFar;
  private BetterBlockPos startPosition;

  private boolean safeToCancel;
  private boolean pauseRequestedLastTick;
  private boolean unpausedLastTick;
  private boolean pausedThisTick;
  private boolean cancelRequested;
  private boolean calcFailedLastTick;
  private boolean horseModeLock;
  private volatile String lastPathingFailure;
  private volatile String lastNextPathingFailure;
  private volatile String lastRouteFailure;
  private final ArrayDeque<String> recentNextPathingFailures = new ArrayDeque<>(16);

  private volatile ActivePathCalculation inProgress;
  private volatile BetterBlockPos activePlanningStart;
  private volatile PlanningAnchor activePlanningAnchor;
  private volatile long activePlanningFactEpoch = -1;
  private volatile PlanningProbe planningProbe;
  private volatile FarfieldObjective planningFarfieldObjective;
  private volatile MacroPlan activeMacroPlan;
  private volatile MacroPlan planningMacroPlan;
  private volatile boolean pathCalcLaunching;
  private long pathCalcEpoch;
  private long worldFactEpoch;
  private long currentRouteFactEpoch = -1;
  private RouteFactFootprint currentRouteFactFootprint = RouteFactFootprint.EMPTY;
  private boolean currentRouteFactsDirty;
  private long lastOpportunityPlanEpoch = -1;
  private int lastOpportunityPlanTick = -1;
  private BetterBlockPos lastOpportunityPlanStart;
  private long deferredMacroFactEpoch = -1;
  private int deferredMacroTick = -1;
  private volatile boolean farfieldRefreshReplanRequested;
  private final Object pathCalcLock = new Object();

  private final Object pathPlanLock = new Object();

  private boolean lastAutoJump;

  private BetterBlockPos expectedSegmentStart;

  private final LinkedBlockingQueue<PathEvent> toDispatch = new LinkedBlockingQueue<>();
  private final ControlArbiter controlArbiter;
  private final FarfieldNavigator farfieldNavigator = new FarfieldNavigator();
  private final PedestrianLocalHotPlanner pedestrianHotPlanner = new PedestrianLocalHotPlanner();
  private TailPlanTicket lastTailPlanTicket;
  private double replanWallTicksEWMA = INITIAL_REPLAN_WALL_TICKS;

  public PathingBehavior(Baritone baritone) {
    super(baritone);
    this.controlArbiter = new ControlArbiter(baritone);
  }

  private void queuePathEvent(PathEvent event) {
    toDispatch.add(event);
  }

  private void dispatchEvents() {
    ArrayList<PathEvent> curr = new ArrayList<>();
    toDispatch.drainTo(curr);
    calcFailedLastTick = curr.contains(PathEvent.CALC_FAILED);
    for (PathEvent event : curr) {
      baritone.getGameEventHandler().onPathEvent(event);
    }
  }

  @Override
  public void onTick(TickEvent event) {
    dispatchEvents();
    if (event.getType() == TickEvent.Type.OUT) {
      secretInternalSegmentCancel();
      baritone.getPathingControlManager().cancelEverything();
      return;
    }

    expectedSegmentStart = pathStart();
    baritone.getPathingControlManager().preTick();
    tickPath();
    transportDebugOverlay();
    ticksElapsedSoFar++;
    dispatchEvents();
  }

  @Override
  public void onPlayerSprintState(SprintStateEvent event) {
    if (isPathing()) {
      event.setState(current.isSprinting());
    }
  }

  @Override
  public void onChunkEvent(ChunkEvent event) {
    if (event.getState() == EventState.POST && (event.getType().isPopulate() || event.getType() == ChunkEvent.Type.LOAD || event.getType() == ChunkEvent.Type.UNLOAD)) {
      recordWorldFact(new ChunkPos(event.getX(), event.getZ()), event.getType() == ChunkEvent.Type.UNLOAD);
    }
  }

  @Override
  public void onBlockChange(BlockChangeEvent event) {
    worldFactEpoch++;
    if (!currentRouteFactFootprint.empty()) {
      if (event.getBlocks().stream().anyMatch(block -> currentRouteFactFootprint.touches(block.first()) && routeInvalidatingBlockChange(block.first(), block.second()))) {
        currentRouteFactsDirty = true;
      }
    }
  }

  @Override
  public void onWorldEvent(WorldEvent event) {
    worldFactEpoch++;
    lastTailPlanTicket = null;
    finishPathProfile("world event");
  }

  private void recordWorldFact(ChunkPos chunk, boolean invalidatesCertifiedFootprint) {
    worldFactEpoch++;
    lastTailPlanTicket = null;
    if (invalidatesCertifiedFootprint && currentRouteFactFootprint.touches(chunk)) {
      currentRouteFactsDirty = true;
    }
  }

  private boolean routeInvalidatingBlockChange(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
    if (context == null) {
      return true;
    }
    return !MovementHelper.isWater(state);
  }

  private void tickPath() {
    pausedThisTick = false;
    if (pauseRequestedLastTick && safeToCancel) {
      pauseRequestedLastTick = false;
      if (unpausedLastTick) {
        controlArbiter.clearPathingControls();
      }
      unpausedLastTick = false;
      pausedThisTick = true;
      return;
    }
    unpausedLastTick = true;
    if (cancelRequested) {
      cancelRequested = false;
      controlArbiter.clearPathingControls();
    }
    synchronized (pathPlanLock) {
      synchronized (pathCalcLock) {
        if (inProgress != null) {
          // we are calculating
          // are we calculating the right thing though? 🤔
          BetterBlockPos calcFrom = inProgress.getStart();
          if (!calculationStartIsStillRelevant(calcFrom) && !bestCalculationPathIsStillRelevant(inProgress.bestPathSoFar())) {
            // when it was *just* started, currentBest will be empty so we need to also check calcFrom since that's always present
            cancelPlanningCalculation(); // cancellation doesn't dispatch any events
          }
        }
      }
      if (current == null) {
        if (restartForMacroValueRefresh()) {
          return;
        }
        if (shouldRestartDeferredMacroPlan()) {
          synchronized (pathCalcLock) {
            if (!calculationActive()) {
              queuePathEvent(PathEvent.CALC_STARTED);
              lastTailPlanTicket = null;
              findPathInNewThread(pathStart(), false, context);
            }
          }
        }
        return;
      }
      safeToCancel = current.onTick();
      controlArbiter.apply(current.controlFrame());
      boolean routeFailed = current.failed();
      String routeFailureReason = routeFailed ? current.failureReason() : null;
      RouteExecutor completedRoute = current;
      if (routeFailed && !fatalRouteFailure(completedRoute, routeFailureReason)) {
        Optional<RouteExecutor> reanchoredCurrent = completedRoute.reanchor(physicalExecutionAnchor());
        if (reanchoredCurrent.isPresent() && reanchoredCurrent.get() != completedRoute) {
          logDebug("Reanchoring failed route at physical execution anchor after: " + routeFailureReason);
          setCurrentRoute(reanchoredCurrent.get());
          current.onTick();
          controlArbiter.apply(current.controlFrame());
          return;
        }
      }
      if (routeFailed || current.finished()) {
        clearCurrentRoute();
        if (routeFailed) {
          lastPathingFailure = routeFailureReason;
          lastRouteFailure = routeFailureReason;
          logDirect("Route execution failed: " + routeFailureReason);
          if (fatalRouteFailure(completedRoute, routeFailureReason)) {
            abortFatalRouteFailure(routeFailureReason);
            return;
          }
        }
        if (goalSatisfied(ctx.playerFeet())) {
          logDebug("All done. At " + goal);
          finishPathProfile("goal reached");
          queuePathEvent(PathEvent.AT_GOAL);
          next = null;
          if (Baritone.settings().disconnectOnArrival.value) {
            if (ctx.world() instanceof ClientLevel clientLevel) {
              clientLevel.disconnect(Component.literal("[Baritone] Arrived at goal!"));
            }
          }
          return;
        }
        if (next != null && !next.containsPathPosition(ctx.playerFeet()) && !next.containsPathPosition(expectedSegmentStart)) { // can contain either one
          // if the current path failed, we may not actually be on the next one, so make sure
          logDebug("Discarding next path as it does not contain current position");
          // for example if we had a nicely planned ahead path that starts where current ends
          // that's all fine and good
          // but if we fail in the middle of current
          // we're nowhere close to our planned ahead path
          // so need to discard it sadly.
          queuePathEvent(PathEvent.DISCARD_NEXT);
          next = null;
        }
        if (next != null) {
          Optional<RouteExecutor> anchoredNext = next.reanchor(physicalExecutionAnchor());
          if (anchoredNext.isEmpty()) {
            logDebug("Discarding next route because it no longer anchors current execution");
            queuePathEvent(PathEvent.DISCARD_NEXT);
            next = null;
          } else {
            next = anchoredNext.get();
          }
        }
        if (next != null) {
          logDebug("Continuing on to planned next path");
          queuePathEvent(PathEvent.CONTINUING_ONTO_PLANNED_NEXT);
          next.snipsnapifpossible();
          setCurrentRoute(next);
          next = null;
          current.onTick(); // don't waste a tick doing nothing, get started right away
          controlArbiter.apply(current.controlFrame());
          return;
        }
        // at this point, current just ended, but we aren't in the goal and have no plan for the future
        synchronized (pathCalcLock) {
          if (calculationActive()) {
            BetterBlockPos start = pathStart();
            if (!planningFrom(start)) {
              logDebug("Preempting stale tail calculation; recertifying from current execution anchor " + start);
              cancelPlanningCalculation();
            } else {
              queuePathEvent(PathEvent.PATH_FINISHED_NEXT_STILL_CALCULATING);
              return;
            }
          }
          // we aren't calculating
          queuePathEvent(PathEvent.CALC_STARTED);
          lastTailPlanTicket = null;
          findPathInNewThread(pathStart(), true, context);
        }
        return;
      }
      // at this point, we know current is in progress
      if (safeToCancel && next != null && next.snipsnapifpossible()) {
        // a movement just ended; jump directly onto the next path
        logDebug("Splicing into planned next path early...");
        queuePathEvent(PathEvent.SPLICING_ONTO_NEXT_EARLY);
        setCurrentRoute(next);
        next = null;
        current.onTick();
        controlArbiter.apply(current.controlFrame());
        return;
      }
      if (Baritone.settings().splicePath.value) {
        RouteExecutor spliced = current.trySplice(next);
        if (spliced != current) {
          setCurrentRoute(spliced);
        }
      }
      if (next != null && current.dest().equals(next.dest())) {
        next = null;
      }
      if (discardStaleQueuedTail()) {
        return;
      }
      if (discardStaleSurfaceTail()) {
        return;
      }
      if (preemptLegacyWaterPlanning()) {
        return;
      }
      if (preemptStaleCurrentRoute()) {
        return;
      }
      if (restartForMacroValueRefresh()) {
        return;
      }
      synchronized (pathCalcLock) {
        if (calculationActive()) {
          // if we aren't calculating right now
          return;
        }
        if (next != null) {
          // and we have no plan for what to do next
          return;
        }
        if (goalSatisfied(goal, current.dest())) {
          // and this path doesn't get us all the way there
          return;
        }
        if (shouldStartTailPlanning()) {
          BetterBlockPos start = planAheadStart();
          if (!reserveTailPlanning(start)) {
            return;
          }
          logDebug(start.equals(current.dest()) ? "Extending path tail in background..." : "Refining path suffix from future anchor " + start + "...");
          queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_STARTED);
          findPathInNewThread(start, false, context, PlanningAnchor.CERTIFIED_FUTURE);
        }
      }
    }
  }

  private boolean restartForMacroValueRefresh() {
    if (!farfieldRefreshReplanRequested || goal == null) {
      return false;
    }
    boolean duringExecution = current != null;
    if (duringExecution && !Baritone.settings().farfieldRefreshPhysicalReplan.value) {
      return false;
    }
    if (duringExecution && current.progress().ticks() < nonnegative(Baritone.settings().farfieldMinCommittedPrefixTicks.value)) {
      return false;
    }
    synchronized (pathCalcLock) {
      if (!calculationActive()) {
        BetterBlockPos start = duringExecution ? physicalExecutionAnchor() : pathStart();
        farfieldRefreshReplanRequested = false;
        queuePathEvent(duringExecution ? PathEvent.NEXT_SEGMENT_CALC_STARTED : PathEvent.CALC_STARTED);
        lastTailPlanTicket = null;
        findPathInNewThread(start, false, context, PlanningAnchor.PHYSICAL);
        return true;
      }
      return false;
    }
  }

  private boolean calculationStartIsStillRelevant(BetterBlockPos calcFrom) {
    if (activeFutureCalculationStale()) {
      return false;
    }
    Optional<BetterBlockPos> certifiedPreemption = workerSafeCertifiedPreemptionStart();
    if (certifiedPreemption.isPresent() && !sameRelevantStart(certifiedPreemption.get(), calcFrom)) {
      return false;
    }
    if (current != null && current.getPath() == null && activePlanningAnchor == PlanningAnchor.CERTIFIED_FUTURE) {
      return current.progressOfExact(calcFrom).filter(progress -> progress.reaches(committedRouteProgress())).isPresent();
    }
    if (calcFrom.equals(ctx.playerFeet()) || calcFrom.equals(expectedSegmentStart)) {
      return true;
    }
    if (current != null && (current.dest().equals(calcFrom) || current.containsPathPosition(calcFrom))) {
      return true;
    }
    if (next != null && (next.dest().equals(calcFrom) || next.containsPathPosition(calcFrom))) {
      return true;
    }
    return false;
  }

  private boolean activeFutureCalculationStale() {
    return activePlanningAnchor == PlanningAnchor.CERTIFIED_FUTURE && worldFactEpoch != activePlanningFactEpoch && current != null && Double.isFinite(current.estimatedContinuationTicks());
  }

  private boolean bestCalculationPathIsStillRelevant(Optional<IPath> currentBest) {
    if (current != null && current.getPath() == null) {
      return false;
    }
    return currentBest.isPresent() && (currentBest.get().positions().contains(ctx.playerFeet()) || currentBest.get().positions().contains(expectedSegmentStart));
  }

  private boolean shouldStartTailPlanning() {
    if (current != null && current.getPath() == null) {
      return true;
    }
    if (Baritone.settings().pathingContinuousPlanning.value) {
      return true;
    }
    // Don't include the current movement: a very long final movement should not suppress planning until it completes.
    return ticksRemainingInSegment(false).get() < Baritone.settings().planningTickLookahead.value;
  }

  private boolean shouldRestartDeferredMacroPlan() {
    if (goal == null || planningMacroPlan == null || calculationActive() || goalSatisfied(ctx.playerFeet())) {
      return false;
    }
    return worldFactEpoch != deferredMacroFactEpoch || ticksElapsedSoFar - deferredMacroTick >= SAME_ANCHOR_FACT_REPLAN_INTERVAL_TICKS;
  }

  private BetterBlockPos planAheadStart() {
    IPath path = current.getPath();
    if (path == null) {
      return current.planAheadStart(replanAnchorAheadTicks(), SUFFIX_REPLAN_TARGET_ANCHOR_ADVANCE);
    }
    if (ctx.player().isSwimming() || MovementClientHelper.isWater(ctx, ctx.playerFeet())) {
      return ctx.playerFeet();
    }
    if (!Baritone.settings().pathingContinuousPlanning.value) {
      return path.getDest();
    }
    return current.planAheadStart(replanAnchorAheadTicks(), SUFFIX_REPLAN_TARGET_ANCHOR_ADVANCE);
  }

  @Override
  public void onPlayerUpdate(PlayerUpdateEvent event) {
    if (current != null) {
      switch (event.getState()) {
        case PRE :
          lastAutoJump = ctx.minecraft().options.autoJump().get();
          ctx.minecraft().options.autoJump().set(false);
          break;
        case POST :
          ctx.minecraft().options.autoJump().set(lastAutoJump);
          break;
        default :
          break;
      }
    }
  }

  public void secretInternalSetGoal(Goal goal) {
    if (!Objects.equals(this.goal, goal)) {
      boolean macroTaskSubgoal =
        baritone.getPortalTaskProcess().isActive() && (planningMacroPlan != null && planningMacroPlan.portalActions() > 0 || activeMacroPlan != null && activeMacroPlan.portalActions() > 0);
      if (!macroTaskSubgoal) {
        activeMacroPlan = null;
        planningMacroPlan = null;
        clearRouteFactState();
        clearDeferredMacroPlan();
        clearNextPathingFailures();
        horseModeLock = false;
      }
    }
    this.goal = goal;
  }

  public boolean secretInternalSetGoalAndPath(PathingCommand command) {
    secretInternalSetGoal(command.goal);
    if (command instanceof PathingCommandContext) {
      context = ((PathingCommandContext) command).desiredCalcContext;
    } else {
      context = new CalculationContext(baritone, true);
    }
    if (goal == null) {
      return false;
    }
    if (baritone.getPortalTaskProcess().isActive() && planningMacroPlan != null && planningMacroPlan.portalActions() > 0) {
      commitMacroPlan(planningMacroPlan);
    }
    if (goalSatisfied(ctx.playerFeet())) {
      return false;
    }
    horseModeLock = ctx.player().getVehicle() instanceof AbstractHorse;
    synchronized (pathPlanLock) {
      if (current != null) {
        return false;
      }
      synchronized (pathCalcLock) {
        if (calculationActive()) {
          return false;
        }
        queuePathEvent(PathEvent.CALC_STARTED);
        clearNextPathingFailures();
        lastTailPlanTicket = null;
        findPathInNewThread(expectedSegmentStart, true, context);
        return true;
      }
    }
  }

  @Override
  public Goal getGoal() { return goal; }

  @Override
  public boolean isPathing() { return hasPath() && !pausedThisTick; }

  @Override
  public RouteExecutor getCurrent() { return current; }

  @Override
  public RouteExecutor getNext() { return next; }

  @Override
  public Optional<ActivePathCalculation> getInProgress() { return Optional.ofNullable(inProgress); }

  public Optional<PlanningProbe> getPlanningProbe() { return calculationActive() ? Optional.ofNullable(planningProbe) : Optional.empty(); }

  public Optional<FarfieldObjective> getRenderableFarfield() { return Baritone.settings().farfieldPlanning.value ? Optional.ofNullable(planningFarfieldObjective) : Optional.empty(); }

  public Optional<BetterBlockPos> getPlanningStart() { return calculationActive() ? Optional.ofNullable(activePlanningStart) : Optional.empty(); }

  public Optional<MacroPlan> getMacroPlan() {
    MacroPlan active = activeMacroPlan;
    if (active != null) {
      return Optional.of(active);
    }
    Optional<MacroPlan> portalTask = baritone.getPortalTaskProcess().activeMacroPlan();
    return portalTask.isPresent() ? portalTask : Optional.ofNullable(planningMacroPlan);
  }

  public Optional<MacroPlan> getRenderableMacroPlan() {
    if (current != null || next != null) {
      return Optional.empty();
    }
    return Optional.ofNullable(planningMacroPlan);
  }

  public void pinMacroPlan(MacroPlan plan) {
    synchronized (pathPlanLock) {
      activeMacroPlan = plan;
      planningMacroPlan = null;
      clearDeferredMacroPlan();
    }
  }

  public RouteProgress committedRouteProgress() {
    return current == null ? RouteProgress.origin() : current.commitmentEnd(commitmentAheadTicks(), COMMITMENT_MIN_UNITS);
  }

  public boolean isSafeToCancel() {
    if (current == null) {
      return !baritone.getElytraProcess().isActive() || baritone.getElytraProcess().isSafeToCancel();
    }
    return safeToCancel;
  }

  public void requestPause() {
    pauseRequestedLastTick = true;
  }

  public boolean cancelSegmentIfSafe() {
    if (isSafeToCancel()) {
      secretInternalSegmentCancel();
      return true;
    }
    return false;
  }

  @Override
  public boolean cancelEverything() {
    secretInternalSegmentCancel();
    baritone.getPathingControlManager().cancelEverything(); // regardless of if we can stop the current segment, we can still stop the processes
    return true;
  }

  public boolean calcFailedLastTick() { // NOT exposed on public api
    return calcFailedLastTick;
  }

  public String lastPathingFailure() {
    return lastPathingFailure;
  }

  public String lastNextPathingFailure() {
    return lastNextPathingFailure;
  }

  public List<String> recentNextPathingFailures() {
    synchronized (recentNextPathingFailures) {
      return List.copyOf(recentNextPathingFailures);
    }
  }

  public String lastRouteFailure() {
    return lastRouteFailure;
  }

  private void transportDebugOverlay() {
    if (!Baritone.settings().transportDebugOverlay.value || ctx.player() == null) {
      return;
    }
    ctx.player().sendOverlayMessage(Component.literal(transportSnapshot().overlayLine()));
  }

  public TransportSnapshot transportSnapshot() {
    return TransportSnapshot.capture(baritone, ctx, current, next, activePlanningStart);
  }

  public void softCancelIfSafe() {
    synchronized (pathPlanLock) {
      synchronized (pathCalcLock) {
        cancelCalculation();
      }
      if (!isSafeToCancel()) {
        return;
      }
      clearCurrentRoute();
      next = null;
    }
    cancelRequested = true;
    finishPathProfile("soft cancel");
    // do everything BUT clear keys
  }

  // just cancel the current path
  public void secretInternalSegmentCancel() {
    boolean canceled = false;
    synchronized (pathPlanLock) {
      synchronized (pathCalcLock) {
        if (!hasCancelableWork()) {
          return;
        }
        cancelCalculation();
      }
      canceled = true;
      if (current != null || next != null) {
        clearCurrentRoute();
        next = null;
        controlArbiter.clearPathingControls();
      }
      horseModeLock = false;
    }
    if (canceled) {
      queuePathEvent(PathEvent.CANCELED);
      finishPathProfile("canceled");
    }
  }

  private boolean hasCancelableWork() {
    return current != null || next != null || inProgress != null || pathCalcLaunching || activePlanningStart != null || planningMacroPlan != null || activeMacroPlan != null
      || lastTailPlanTicket != null || deferredMacroFactEpoch >= 0L;
  }

  @Override
  public void forceCancel() { // exposed on public api because :sob:
    cancelEverything();
    secretInternalSegmentCancel();
    synchronized (pathCalcLock) {
      cancelCalculation();
      inProgress = null;
    }
  }

  public CalculationContext secretInternalGetCalculationContext() {
    return context;
  }

  @Override
  public Optional<Double> ticksRemainingInSegment(boolean includeCurrentMovement) {
    if (current == null) {
      return Optional.empty();
    }
    IPath path = current.getPath();
    if (path != null) {
      int start = includeCurrentMovement ? current.getPosition() : current.getPosition() + 1;
      return Optional.of(path.ticksRemainingFrom(start));
    }
    return Optional.of(current.estimatedTicksRemainingFromCurrent());
  }

  public boolean resourceRepricingHeadroom() {
    if (current == null || next != null) {
      return true;
    }
    if (calculationActive()) {
      return false;
    }
    return ticksRemainingInSegment(false).orElse(0D) >= Baritone.settings().dynamicResourcePricingHeadroomTicks.value;
  }

  public Optional<Double> estimatedTicksToGoal() {
    BetterBlockPos currentPos = ctx.playerFeet();
    if (goal == null || currentPos == null || startPosition == null) {
      return Optional.empty();
    }
    if (goalSatisfied(ctx.playerFeet())) {
      resetEstimatedTicksToGoal();
      return Optional.of(0.0);
    }
    if (ticksElapsedSoFar == 0) {
      return Optional.empty();
    }
    double current = goal.heuristic(currentPos.x, currentPos.y, currentPos.z);
    double start = goal.heuristic(startPosition.x, startPosition.y, startPosition.z);
    if (current == start) {// can't check above because current and start can be equal even if currentPos and startPosition are not
      return Optional.empty();
    }
    double eta = Math.abs(current - goal.heuristic()) * ticksElapsedSoFar / Math.abs(start - current);
    return Optional.of(eta);
  }

  private void resetEstimatedTicksToGoal() {
    resetEstimatedTicksToGoal(expectedSegmentStart);
  }

  private void resetEstimatedTicksToGoal(BetterBlockPos start) {
    ticksElapsedSoFar = 0;
    startPosition = start;
  }

  private void setCurrentRoute(RouteExecutor route) {
    current = route;
    currentRouteFactEpoch = worldFactEpoch;
    currentRouteFactFootprint = route.factFootprint();
    currentRouteFactsDirty = false;
    lastOpportunityPlanEpoch = worldFactEpoch;
    lastOpportunityPlanTick = ticksElapsedSoFar;
    lastOpportunityPlanStart = route.src();
  }

  private void clearCurrentRoute() {
    current = null;
    clearRouteFactState();
  }

  private boolean fatalRouteFailure(RouteExecutor route, String reason) {
    if (route == null || route.route().startState().mode() != TransportMode.HORSE && route.route().endState().mode() != TransportMode.HORSE) {
      return false;
    }
    return !(ctx.player().getVehicle() instanceof AbstractHorse) || reason != null && reason.startsWith("dismounted");
  }

  private void abortFatalRouteFailure(String reason) {
    lastPathingFailure = reason;
    lastRouteFailure = reason;
    synchronized (pathPlanLock) {
      synchronized (pathCalcLock) {
        cancelPlanningCalculation();
      }
      next = null;
      activeMacroPlan = null;
      clearDeferredMacroPlan();
      controlArbiter.clearPathingControls();
    }
    finishPathProfile("fatal route failure: " + reason);
    queuePathEvent(PathEvent.CALC_FAILED);
  }

  private void finishPathProfile(String reason) {
    if (baritone.getPathProfileController().active()) {
      logDirect(baritone.getPathProfileController().finish(reason));
    }
  }

  private void clearRouteFactState() {
    currentRouteFactEpoch = -1;
    currentRouteFactFootprint = RouteFactFootprint.EMPTY;
    currentRouteFactsDirty = false;
    lastOpportunityPlanEpoch = -1;
    lastOpportunityPlanTick = -1;
    lastOpportunityPlanStart = null;
  }

  /**
   * See issue #209
   *
   * @return The starting {@link BlockPos} for a new path
   */
  public BetterBlockPos pathStart() { // TODO move to a helper or util class
    if (ctx.player().getVehicle() instanceof AbstractHorse horse) {
      return HorsePath.vehicleFeet(horse);
    }
    BetterBlockPos feet = ctx.playerFeet();
    if (!MovementClientHelper.canWalkOn(ctx, feet.below())) {
      if (ctx.player().onGround()) {
        double playerX = ctx.player().position().x;
        double playerZ = ctx.player().position().z;
        ArrayList<BetterBlockPos> closest = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
          for (int dz = -1; dz <= 1; dz++) {
            closest.add(new BetterBlockPos(feet.x + dx, feet.y, feet.z + dz));
          }
        }
        closest.sort(Comparator.comparingDouble(pos -> ((pos.x + 0.5D) - playerX) * ((pos.x + 0.5D) - playerX) + ((pos.z + 0.5D) - playerZ) * ((pos.z + 0.5D) - playerZ)));
        for (int i = 0; i < 4; i++) {
          BetterBlockPos possibleSupport = closest.get(i);
          double xDist = Math.abs((possibleSupport.x + 0.5D) - playerX);
          double zDist = Math.abs((possibleSupport.z + 0.5D) - playerZ);
          if (xDist > 0.8 && zDist > 0.8) {
            // can't possibly be sneaking off of this one, we're too far away
            continue;
          }
          if (MovementClientHelper.canWalkOn(ctx, possibleSupport.below()) && MovementClientHelper.canWalkThrough(ctx, possibleSupport)
            && MovementClientHelper.canWalkThrough(ctx, possibleSupport.above())) {
            // this is plausible
            //logDebug("Faking path start assuming player is standing off the edge of a block");
            return possibleSupport;
          }
        }

      } else {
        // !onGround
        // we're in the middle of a jump
        if (MovementClientHelper.canWalkOn(ctx, feet.below().below())) {
          //logDebug("Faking path start assuming player is midair and falling");
          return feet.below();
        }
      }
    }
    return feet;
  }

  /**
   * In a new thread, pathfind to target blockpos
   *
   * @param start
   * @param talkAboutIt
   */
  private void findPathInNewThread(final BlockPos start, final boolean talkAboutIt, CalculationContext context) {
    findPathInNewThread(start, talkAboutIt, context, PlanningAnchor.PHYSICAL);
  }

  private void findPathInNewThread(final BlockPos start, final boolean talkAboutIt, CalculationContext context, PlanningAnchor anchor) {
    // this must be called with synchronization on pathCalcLock!
    // actually, we can check this, muahaha
    if (!Thread.holdsLock(pathCalcLock)) {
      throw new IllegalStateException("Must be called with synchronization on pathCalcLock");
      // why do it this way? it's already indented so much that putting the whole thing in a synchronized(pathCalcLock) was just too much lol
    }
    if (calculationActive()) {
      throw new IllegalStateException("Already doing it"); // should have been checked by caller
    }
    context = liveCalculationContext(context);
    this.context = context;
    if (!context.safeForThreadedUse) {
      throw new IllegalStateException("Improper context thread safety level");
    }
    CalculationContext calculationContext = context;
    Goal goal = this.goal;
    if (goal == null) {
      logDebug("no goal"); // TODO should this be an exception too? definitely should be checked by caller
      return;
    }
    long primaryTimeout;
    long failureTimeout;
    if (current == null) {
      primaryTimeout = Baritone.settings().primaryTimeoutMS.value;
      failureTimeout = Baritone.settings().failureTimeoutMS.value;
    } else {
      primaryTimeout = Baritone.settings().planAheadPrimaryTimeoutMS.value;
      failureTimeout = Baritone.settings().planAheadFailureTimeoutMS.value;
    }
    IPath previous = previousPathForFavoring(start);
    activePlanningStart = new BetterBlockPos(start);
    activePlanningAnchor = anchor;
    activePlanningFactEpoch = worldFactEpoch;
    planningProbe = null;
    baritone.getPathProfileController().beginIfArmed(activePlanningStart, goal);
    planningMacroPlan = null;
    pathCalcLaunching = true;
    long epoch = ++pathCalcEpoch;
    long launchedNanos = System.nanoTime();
    Baritone.getExecutor().execute(() -> {
      try {
        CalculationLaunch created;
        try {
          created = createPathfinder(start, goal, previous, calculationContext, failureTimeout, anchor);
        } catch (Exception e) {
          logDirect("Pathing exception before A*: " + e);
          e.printStackTrace();
          synchronized (pathPlanLock) {
            synchronized (pathCalcLock) {
              if (pathCalcEpoch == epoch && pathCalcLaunching) {
                pathCalcLaunching = false;
                clearActivePlanningAnchor();
                planningMacroPlan = null;
                acceptCalculation(new PathCalculationResult(PathCalculationResult.Type.EXCEPTION), start, talkAboutIt, true, calculationContext);
              }
            }
          }
          return;
        }
        if (created instanceof DeferredCalculation deferred) {
          synchronized (pathPlanLock) {
            synchronized (pathCalcLock) {
              if (pathCalcEpoch != epoch || !pathCalcLaunching || this.goal != goal) {
                return;
              }
              if (deferred.plan().portalActions() > 0) {
                activeMacroPlan = deferred.plan();
                planningMacroPlan = null;
                clearDeferredMacroPlan();
              } else {
                deferMacroPlan(deferred.plan());
              }
              pathCalcLaunching = false;
              clearActivePlanningAnchor();
              logDebug((deferred.plan().portalActions() > 0 ? "Awaiting macro portal task handoff" : "Awaiting factual macro prefix before actuation") + " (seq=" + deferred.plan().sequence() + ")");
            }
          }
          return;
        }
        if (created instanceof HorseCalculation horse) {
          synchronized (pathPlanLock) {
            synchronized (pathCalcLock) {
              if (pathCalcEpoch != epoch || !pathCalcLaunching || this.goal != goal) {
                return;
              }
              planningMacroPlan = horse.macroPlan();
              if (talkAboutIt && horse.macroPlan() != null) {
                logDebug("Searching horse route over macro value field " + horse.localGoal() + " (seq=" + horse.macroPlan().sequence() + ")");
              }
            }
          }
          HorseRoutePlanner.Result horseRoute =
            HorseRoutePlanner.plan(calculationContext, horse.routeStart(), horse.localGoal(), horse.failureTimeoutMS(), incumbent -> publishHorseIncumbent(epoch, goal, horse, incumbent));
          synchronized (pathPlanLock) {
            synchronized (pathCalcLock) {
              if (pathCalcEpoch != epoch || !pathCalcLaunching || this.goal != goal) {
                return;
              }
              if (horseRoute.incumbentAccepted()) {
                pathCalcLaunching = false;
                clearActivePlanningAnchor();
                planningMacroPlan = null;
                return;
              }
              if (activeFutureCalculationStale()) {
                pathCalcLaunching = false;
                clearActivePlanningAnchor();
                planningMacroPlan = null;
                return;
              }
              RouteExecutor rejectedRoute = null;
              if (horseRoute.route().isPresent()) {
                RouteExecutor route = new RouteExecutor(PathingBehavior.this, horseRoute.route().get(), horse.terminalGoal());
                CandidateDisposition disposition = acceptCandidate(route, horse.terminalGoal());
                if (disposition != CandidateDisposition.REJECTED) {
                  if (disposition == CandidateDisposition.EXECUTING && horse.macroPlan() != null) {
                    commitMacroPlan(horse.macroPlan());
                  }
                  pathCalcLaunching = false;
                  clearActivePlanningAnchor();
                  if (talkAboutIt && disposition == CandidateDisposition.EXECUTING) {
                    logDebug("Executing horse route from " + route.src() + " towards " + goal);
                  }
                  return;
                }
                rejectedRoute = route;
              }
              PlanningAnchor rejectedAnchor = activePlanningAnchor;
              pathCalcLaunching = false;
              clearActivePlanningAnchor();
              planningMacroPlan = null;
              String diagnostic;
              if (rejectedRoute == null) {
                diagnostic = "Failed to calculate horse route: " + horseRoute.diagnostic() + " from " + horse.routeStart().pos()
                  + (horse.macroPlan() == null ? "" : " after macro " + horse.macroPlan().sequence() + " localGoal=" + horse.localGoal());
                logDirect(diagnostic);
              } else {
                diagnostic = "Discarding horse route " + rejectedRoute.src() + " -> " + rejectedRoute.dest() + " from " + horse.routeStart().pos() + " for " + rejectedAnchor
                  + (current == null ? "" : "; current " + current.src() + " -> " + current.dest());
                logDebug(diagnostic);
              }
              if (restartRejectedPhysicalRoute(rejectedAnchor, start, talkAboutIt, calculationContext)) {
                return;
              }
              if (restartOrphanCalculation(rejectedAnchor, start, talkAboutIt, calculationContext)) {
                return;
              }
              acceptCalculation(new PathCalculationResult(PathCalculationResult.Type.FAILURE), start, talkAboutIt, true, calculationContext, diagnostic);
            }
          }
          return;
        }
        if (created instanceof ImmediateRouteCalculation immediate) {
          RouteExecutor route = new RouteExecutor(PathingBehavior.this, immediate.route(), immediate.terminalGoal());
          synchronized (pathPlanLock) {
            synchronized (pathCalcLock) {
              if (pathCalcEpoch != epoch || !pathCalcLaunching || this.goal != goal) {
                return;
              }
              planningMacroPlan = immediate.macroPlan();
              CandidateDisposition disposition = acceptCandidate(route, immediate.terminalGoal());
              if (disposition != CandidateDisposition.REJECTED) {
                if (disposition == CandidateDisposition.EXECUTING && immediate.macroPlan() != null) {
                  commitMacroPlan(immediate.macroPlan());
                }
                pathCalcLaunching = false;
                clearActivePlanningAnchor();
                if (talkAboutIt && disposition == CandidateDisposition.EXECUTING) {
                  logDebug("Executing immediate " + immediate.kind() + " route from " + route.src() + " towards " + goal);
                }
                return;
              }
              pathCalcLaunching = false;
              clearActivePlanningAnchor();
              acceptCalculation(new PathCalculationResult(PathCalculationResult.Type.FAILURE), start, talkAboutIt, true, calculationContext,
                "discarded immediate " + immediate.kind() + " route from " + route.src() + " to " + route.dest());
            }
          }
          return;
        }
        if (created instanceof FailedCalculation failed) {
          synchronized (pathPlanLock) {
            synchronized (pathCalcLock) {
              if (pathCalcEpoch == epoch && pathCalcLaunching && this.goal == goal) {
                pathCalcLaunching = false;
                clearActivePlanningAnchor();
                planningMacroPlan = null;
                String diagnostic = "Failed to calculate " + failed.kind() + " route from " + start;
                logDirect(diagnostic);
                acceptCalculation(new PathCalculationResult(PathCalculationResult.Type.FAILURE), start, talkAboutIt, true, calculationContext, diagnostic);
              }
            }
          }
          return;
        }
        CreatedPathfinder pathLaunch = (CreatedPathfinder) created;
        ActivePathCalculation pathfinder = pathLaunch.pathfinder();
        if (pathLaunch.immediateRoute().isPresent()) {
          RouteExecutor route = new RouteExecutor(PathingBehavior.this, pathLaunch.immediateRoute().get(), pathLaunch.terminalGoal());
          synchronized (pathPlanLock) {
            synchronized (pathCalcLock) {
              if (pathCalcEpoch != epoch || !pathCalcLaunching || this.goal != goal) {
                return;
              }
              planningMacroPlan = pathLaunch.macroPlan();
              CandidateDisposition disposition = acceptCandidate(route, pathLaunch.terminalGoal());
              if (disposition != CandidateDisposition.REJECTED) {
                if (disposition == CandidateDisposition.EXECUTING) {
                  commitMacroPlan(pathLaunch.macroPlan());
                }
                pathCalcLaunching = false;
                clearActivePlanningAnchor();
                if (talkAboutIt && disposition == CandidateDisposition.EXECUTING) {
                  logDebug("Executing immediate macro route from " + route.src() + " towards " + goal + " (seq=" + pathLaunch.macroPlan().sequence() + ")");
                }
                return;
              }
              logDebug("Discarding immediate macro route from " + route.src() + " to " + route.dest() + " while current=" + (current == null ? "none" : current.src() + "->" + current.dest())
                + " feet=" + ctx.playerFeet() + " expected=" + expectedSegmentStart);
            }
          }
        }
        pathfinder.setPublicationSink(result -> acceptIncumbent(pathfinder, result));
        synchronized (pathPlanLock) {
          synchronized (pathCalcLock) {
            if (pathCalcEpoch != epoch || !pathCalcLaunching || this.goal != goal) {
              pathfinder.cancel();
              return;
            }
            planningMacroPlan = pathLaunch.macroPlan();
            inProgress = pathfinder;
            pathCalcLaunching = false;
          }
        }
        if (!Objects.equals(pathfinder.getGoal(), goal) && pathLaunch.macroPlan() == null) { // will return the exact same object if simplification didn't happen
          logDebug("Simplifying " + goal.getClass() + " to GoalXZ due to distance");
        }
        if (talkAboutIt) {
          logDebug("Starting to search for path from " + start + " to " + goal);
        }

        PathCalculationResult calcResult = pathfinder.calculate(primaryTimeout, failureTimeout);
        synchronized (pathPlanLock) {
          boolean currentCalculation;
          PlanningAnchor finishedAnchor;
          synchronized (pathCalcLock) {
            currentCalculation = pathCalcEpoch == epoch && inProgress == pathfinder && calculationStartIsStillRelevant(pathfinder.getStart());
            finishedAnchor = activePlanningAnchor;
          }
          if (currentCalculation) {
            synchronized (pathCalcLock) {
              if (calculationOrphaned(finishedAnchor, start)) {
                inProgress = null;
                clearActivePlanningAnchor();
                planningMacroPlan = null;
                if (restartOrphanCalculation(finishedAnchor, start, talkAboutIt, calculationContext)) {
                  return;
                }
              }
            }
            if (!(pathfinder instanceof PedestrianLocalHotPlanner.HotLocalPathCalculation hot) || !acceptHotLocalCalculation(hot, start, talkAboutIt)) {
              acceptCalculation(calcResult, start, talkAboutIt, true, calculationContext);
            }
          }
          synchronized (pathCalcLock) {
            if (inProgress == pathfinder) {
              inProgress = null;
              clearActivePlanningAnchor();
              planningMacroPlan = null;
            }
          }
        }
      } finally {
        recordReplanWallTime(launchedNanos);
      }
    });
  }

  private boolean calculationActive() {
    return inProgress != null || pathCalcLaunching;
  }

  private void recordReplanWallTime(long launchedNanos) {
    double ticks = Math.max(0D, (System.nanoTime() - launchedNanos) / 50_000_000D);
    replanWallTicksEWMA = replanWallTicksEWMA * (1D - REPLAN_WALL_TICKS_ALPHA) + ticks * REPLAN_WALL_TICKS_ALPHA;
  }

  private boolean preemptLegacyWaterPlanning() {
    if (!transitPlanningEnabled()) {
      return false;
    }
    Optional<BetterBlockPos> start = certifiedPreemptionStart();
    if (start.isEmpty()) {
      return false;
    }
    if (next != null && !next.containsPathPosition(start.get()) && !next.containsPathPosition(expectedSegmentStart)) {
      logDebug("Discarding speculative tail route; recertifying from current water anchor");
      queuePathEvent(PathEvent.DISCARD_NEXT);
      next = null;
    }
    synchronized (pathCalcLock) {
      if (calculationActive()) {
        if (planningFrom(start.get())) {
          return true;
        }
        logDebug("Preempting future-anchor calculation; recertifying from current water anchor " + start.get());
        cancelPlanningCalculation();
      }
      if (!reserveTailPlanning(start.get())) {
        return true;
      }
      queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_STARTED);
      findPathInNewThread(start.get(), false, context);
    }
    return true;
  }

  private boolean discardStaleSurfaceTail() {
    if (current == null || current.getPath() != null || next == null || next.src().equals(current.dest())) {
      return false;
    }
    logDebug("Discarding non-frontier queued tail while executing certified surface route: next=" + next.src() + "->" + next.dest() + ", current=" + current.src() + "->" + current.dest() + ", feet="
      + ctx.playerFeet());
    queuePathEvent(PathEvent.DISCARD_NEXT);
    next = null;
    lastTailPlanTicket = null;
    return true;
  }

  private boolean discardStaleQueuedTail() {
    if (current == null || next == null || current.getPath() != null || !Double.isFinite(current.estimatedContinuationTicks()) || worldFactEpoch <= currentRouteFactEpoch) {
      return false;
    }
    logDebug("Discarding queued tail after new world facts; recertifying beyond refreshed frontier");
    queuePathEvent(PathEvent.DISCARD_NEXT);
    next = null;
    lastTailPlanTicket = null;
    return true;
  }

  private boolean preemptStaleCurrentRoute() {
    if (current == null || next != null || goalSatisfied(goal, current.dest())) {
      return false;
    }
    BetterBlockPos start = pathStart();
    boolean dirty = currentRouteFactsDirty;
    if (!dirty && !shouldOpportunisticallyReplan(start)) {
      return false;
    }
    synchronized (pathCalcLock) {
      if (calculationActive()) {
        if (activeFutureCalculationStale()) {
          logDebug("Preempting stale future-anchor calculation; world frontier advanced from epoch " + activePlanningFactEpoch + " to " + worldFactEpoch);
          cancelPlanningCalculation();
        } else if (planningFrom(start)) {
          return true;
        } else {
          logDebug(dirty ? "Preempting future-anchor calculation; certified route facts changed near " + start : "Preempting future-anchor calculation; improving route after new facts from " + start);
          cancelPlanningCalculation();
        }
      }
      if (dirty) {
        lastTailPlanTicket = null;
        currentRouteFactsDirty = false;
        currentRouteFactEpoch = worldFactEpoch;
      }
      if (!reserveTailPlanning(start)) {
        return true;
      }
      lastOpportunityPlanEpoch = worldFactEpoch;
      lastOpportunityPlanTick = ticksElapsedSoFar;
      lastOpportunityPlanStart = start;
      logDebug(dirty ? "Recertifying current route because its factual footprint changed near " + start + "..." : "Opportunistically refining current route after new facts from " + start + "...");
      queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_STARTED);
      findPathInNewThread(start, false, context);
      return true;
    }
  }

  private boolean shouldOpportunisticallyReplan(BetterBlockPos start) {
    if (worldFactEpoch <= currentRouteFactEpoch || current.getPath() != null || !Double.isFinite(current.estimatedContinuationTicks())) {
      return false;
    }
    if (lastOpportunityPlanEpoch == worldFactEpoch) {
      return false;
    }
    if (lastOpportunityPlanTick >= 0 && ticksElapsedSoFar - lastOpportunityPlanTick < OPPORTUNISTIC_FACT_REPLAN_INTERVAL_TICKS) {
      return false;
    }
    if (lastOpportunityPlanStart != null) {
      int dx = start.x - lastOpportunityPlanStart.x;
      int dz = start.z - lastOpportunityPlanStart.z;
      if (dx * dx + dz * dz < OPPORTUNISTIC_FACT_REPLAN_MIN_ADVANCE_BLOCKS * OPPORTUNISTIC_FACT_REPLAN_MIN_ADVANCE_BLOCKS) {
        return false;
      }
    }
    return true;
  }

  private boolean reserveTailPlanning(BetterBlockPos start) {
    TailPlanTicket previous = lastTailPlanTicket;
    if (previous != null && previous.matches(start, goal, worldFactEpoch, ticksElapsedSoFar)) {
      return false;
    }
    lastTailPlanTicket = new TailPlanTicket(new BetterBlockPos(start), goal, worldFactEpoch, ticksElapsedSoFar, strictStartBuckets());
    return true;
  }

  private Optional<BetterBlockPos> certifiedPreemptionStart() {
    return certifiedPreemptionStart(true);
  }

  private Optional<BetterBlockPos> workerSafeCertifiedPreemptionStart() {
    return certifiedPreemptionStart(false);
  }

  private Optional<BetterBlockPos> certifiedPreemptionStart(boolean liveWorldTerminalPolicy) {
    BetterBlockPos feet = ctx.playerFeet();
    if (current == null || current.getPath() == null || (liveWorldTerminalPolicy ? goalSatisfied(feet) : goalSatisfied(goal, feet))) {
      return Optional.empty();
    }
    if (ctx.player().isSwimming() || liveWorldTerminalPolicy && MovementClientHelper.isWater(ctx, feet)) {
      return Optional.of(feet);
    }
    return Optional.empty();
  }

  private boolean planningFrom(BetterBlockPos start) {
    if (sameRelevantStart(start, activePlanningStart)) {
      return true;
    }
    return inProgress != null && sameRelevantStart(start, inProgress.getStart());
  }

  private boolean sameRelevantStart(BetterBlockPos a, BetterBlockPos b) {
    return strictStartBuckets() ? sameHorseExecutionStart(a, b) : sameCertificationBucket(a, b);
  }

  private boolean strictStartBuckets() {
    return horseModeLock || ctx != null && ctx.player().getVehicle() instanceof AbstractHorse;
  }

  private static boolean sameHorseExecutionStart(BetterBlockPos a, BetterBlockPos b) {
    return a != null && b != null && a.x == b.x && a.y == b.y && a.z == b.z;
  }

  private static boolean sameCertificationBucket(BetterBlockPos a, BetterBlockPos b) {
    if (a == null || b == null || Math.abs(a.y - b.y) > 1) {
      return false;
    }
    int dx = a.x - b.x;
    int dz = a.z - b.z;
    return dx * dx + dz * dz <= 36;
  }

  private void cancelPlanningCalculation() {
    pathCalcEpoch++;
    pathCalcLaunching = false;
    if (inProgress != null) {
      inProgress.cancel();
      inProgress = null;
    }
    clearActivePlanningAnchor();
    planningMacroPlan = null;
    planningFarfieldObjective = null;
  }

  private void commitMacroPlan(MacroPlan plan) {
    activeMacroPlan = plan;
    planningMacroPlan = null;
    clearDeferredMacroPlan();
  }

  private void cancelCalculation() {
    pathCalcEpoch++;
    pathCalcLaunching = false;
    getInProgress().ifPresent(ActivePathCalculation::cancel);
    clearActivePlanningAnchor();
    activeMacroPlan = null;
    planningMacroPlan = null;
    planningFarfieldObjective = null;
    lastTailPlanTicket = null;
    clearDeferredMacroPlan();
  }

  private void deferMacroPlan(MacroPlan plan) {
    planningMacroPlan = plan;
    deferredMacroFactEpoch = worldFactEpoch;
    deferredMacroTick = ticksElapsedSoFar;
  }

  private void clearDeferredMacroPlan() {
    deferredMacroFactEpoch = -1;
    deferredMacroTick = -1;
  }

  private void clearActivePlanningAnchor() {
    activePlanningStart = null;
    activePlanningAnchor = null;
    activePlanningFactEpoch = -1;
    planningProbe = null;
  }

  private boolean publishHorseIncumbent(long epoch, Goal capturedGoal, HorseCalculation horse, HorseRoutePlanner.Incumbent incumbent) {
    synchronized (pathPlanLock) {
      synchronized (pathCalcLock) {
        if (pathCalcEpoch != epoch || !pathCalcLaunching || this.goal != capturedGoal) {
          return false;
        }
        if (activeFutureCalculationStale()) {
          return false;
        }
        planningProbe = PlanningProbe.best(incumbent.path().positions());
        PathingIncumbentPolicy policy = PathingIncumbentPolicy.horse();
        if (!policy.earlyExecution() || !horseIncumbentExecutableBeforeFrontier(horse, incumbent, policy)) {
          return false;
        }
        if (horse.macroPlan() != null && horse.macroPlan().surfaceTransitionActions() > 0) {
          return false;
        }
        boolean boatAvailable = ((Baritone) baritone).getInventoryBehavior().hasBoat();
        RouteExecutor candidate = new RouteExecutor(PathingBehavior.this, HorseRoutePlanner.routePlan(horse.localGoal(), incumbent.path(), boatAvailable), horse.terminalGoal());
        CandidateDisposition disposition = acceptCandidate(candidate, horse.terminalGoal());
        if (disposition == CandidateDisposition.EXECUTING && horse.macroPlan() != null) {
          activeMacroPlan = horse.macroPlan();
          clearDeferredMacroPlan();
        }
        return disposition != CandidateDisposition.REJECTED;
      }
    }
  }

  private boolean horseIncumbentExecutableBeforeFrontier(HorseCalculation horse, HorseRoutePlanner.Incumbent incumbent, PathingIncumbentPolicy policy) {
    HorsePath path = incumbent.path();
    if (goalSatisfied(horse.terminalGoal(), path.dest())) {
      return true;
    }
    if (incumbent.boundaryExit()) {
      return path.flatDistance() >= policy.minLength();
    }
    if (!(horse.localGoal() instanceof LocalExitObjective exit)) {
      BetterBlockPos dest = path.dest();
      MountTuning.Planner tuning = MountTuning.current().planner();
      boolean meaningfulDistance = path.flatDistance() >= tuning.minLocalProgressFallbackBlocks();
      boolean meaningfulHeuristic = horse.localGoal().heuristic(dest.x, dest.y, dest.z) + tuning.localProgressHeuristicEpsilon() < horse.localGoal().heuristic(horse.routeStart().pos().x,
        horse.routeStart().pos().y, horse.routeStart().pos().z);
      return meaningfulDistance && meaningfulHeuristic;
    }
    if (path.flatDistance() < policy.minLength()) {
      return false;
    }
    if (horse.macroPlan() == null) {
      return false;
    }
    BetterBlockPos dest = path.dest();
    double startValue = exit.localExitValue(horse.routeStart().pos().x, horse.routeStart().pos().y, horse.routeStart().pos().z);
    double destValue = exit.localExitValue(dest.x, dest.y, dest.z);
    return Double.isFinite(startValue) && Double.isFinite(destValue) && destValue + policy.heuristicMargin() < startValue;
  }

  private CalculationContext liveCalculationContext(CalculationContext base) {
    if (base != null && (base.getClass() != CalculationContext.class || !ctx.minecraft().isSameThread())) {
      return base;
    }
    return new CalculationContext(baritone, true);
  }

  private IPath previousPathForFavoring(BlockPos start) {
    if (current == null) {
      return null;
    }
    IPath path = current.getPath();
    if (path == null) {
      return null;
    }
    BetterBlockPos startPos = new BetterBlockPos(start);
    return !path.getDest().equals(startPos) && current.containsPathPosition(startPos) ? null : path;
  }

  private void acceptIncumbent(ActivePathCalculation pathfinder, PathCalculationResult result) {
    if (!PathingIncumbentPolicy.pedestrian().earlyExecution()) {
      return;
    }
    synchronized (pathPlanLock) {
      if (inProgress != pathfinder) {
        return;
      }
      if (planningMacroPlan != null && planningMacroPlan.surfaceTransitionActions() > 0) {
        return;
      }
      acceptCalculation(result, pathfinder.getStart(), false, false, null);
    }
  }

  private boolean acceptHotLocalCalculation(PedestrianLocalHotPlanner.HotLocalPathCalculation pathfinder, BlockPos requestedStart, boolean talkAboutIt) {
    if (!Thread.holdsLock(pathPlanLock)) {
      throw new IllegalStateException("Must hold pathPlanLock while accepting a hot local calculation");
    }
    Optional<RoutePlan> routePlan = pathfinder.routePlan();
    if (routePlan.isEmpty()) {
      return false;
    }
    RouteExecutor candidate = new RouteExecutor(PathingBehavior.this, routePlan.get(), pathfinder.terminalGoal());
    CandidateDisposition disposition = acceptCandidate(candidate, pathfinder.terminalGoal());
    if (disposition == CandidateDisposition.REJECTED) {
      if (activePlanningAnchor == PlanningAnchor.PHYSICAL && current != null) {
        logDebug("Ignoring non-improving physical hot local route segment from " + requestedStart + " to " + candidate.dest() + " while current=" + current.src() + "->" + current.dest());
        return true;
      }
      if (activePlanningAnchor == PlanningAnchor.CERTIFIED_FUTURE) {
        logDebug("Discarding stale speculative hot local route segment from " + requestedStart + " to " + candidate.dest());
        return true;
      }
      logDebug("Discarding hot local route segment from " + requestedStart + " to " + candidate.dest());
      acceptEmptyCalculation(new PathCalculationResult(PathCalculationResult.Type.FAILURE), requestedStart, true,
        "discarded hot local route segment from " + requestedStart + " to " + candidate.dest());
      return true;
    }
    if (disposition == CandidateDisposition.EXECUTING) {
      commitMacroPlan(planningMacroPlan);
    }
    if (talkAboutIt && disposition == CandidateDisposition.EXECUTING && current != null) {
      logDebug("Found hot local route segment from " + requestedStart + " towards " + goal + ". " + pathfinder.telemetry());
    }
    return true;
  }

  private void acceptCalculation(PathCalculationResult result, BlockPos requestedStart, boolean talkAboutIt, boolean finalResult, CalculationContext calculationContext) {
    acceptCalculation(result, requestedStart, talkAboutIt, finalResult, calculationContext, "");
  }

  private void acceptCalculation(PathCalculationResult result, BlockPos requestedStart, boolean talkAboutIt, boolean finalResult, CalculationContext calculationContext, String emptyDiagnostic) {
    if (!Thread.holdsLock(pathPlanLock)) {
      throw new IllegalStateException("Must hold pathPlanLock while accepting a path calculation");
    }
    Optional<IPath> path = result.getPath();
    MacroPlan macroPlan = planningMacroPlan;
    Goal terminalGoal = calculationContext == null ? goal : GoalTerminalPolicy.project(calculationContext, goal);
    Optional<RouteExecutor> executor = finalResult && calculationContext != null && macroPlan != null
      ? MacroCoordinator.materialize(calculationContext, macroPlan, path.orElse(null)).map(route -> new RouteExecutor(PathingBehavior.this, route, terminalGoal)) : Optional.empty();
    executor = executor.or(() -> path.map(p -> new RouteExecutor(PathingBehavior.this, p)));
    if (executor.isEmpty()) {
      acceptEmptyCalculation(result, requestedStart, finalResult, emptyDiagnostic);
      return;
    }
    RouteExecutor candidate = executor.get();
    if (!finalResult && !incumbentIsExecutable(candidate, terminalGoal)) {
      return;
    }
    CandidateDisposition disposition = acceptCandidate(candidate, terminalGoal);
    if (disposition == CandidateDisposition.REJECTED) {
      if (finalResult) {
        if (activePlanningAnchor == PlanningAnchor.CERTIFIED_FUTURE) {
          logDebug("Discarding stale speculative route segment from " + requestedStart + " to " + candidate.dest());
          return;
        }
        logDebug("Discarding orphan route segment from " + requestedStart + " to " + candidate.dest());
        acceptEmptyCalculation(new PathCalculationResult(PathCalculationResult.Type.FAILURE), requestedStart, true,
          "discarded orphan route segment from " + requestedStart + " to " + candidate.dest());
      }
      return;
    }
    if (finalResult && disposition == CandidateDisposition.EXECUTING) {
      commitMacroPlan(macroPlan);
    }
    if (talkAboutIt && disposition == CandidateDisposition.EXECUTING && current != null) {
      IPath currentPath = current.getPath();
      if (goalSatisfied(terminalGoal, current.dest())) {
        logDebug("Finished finding a route from " + requestedStart + " to " + goal + ". " + (currentPath == null ? "macro" : currentPath.getNumNodesConsidered() + " nodes considered"));
      } else {
        logDebug("Found route segment from " + requestedStart + " towards " + goal + ". " + (currentPath == null ? "macro" : currentPath.getNumNodesConsidered() + " nodes considered"));
      }
    }
  }

  private void acceptEmptyCalculation(PathCalculationResult result, BlockPos requestedStart, boolean finalResult, String diagnostic) {
    if (!finalResult || result.getType() == PathCalculationResult.Type.CANCELLATION) {
      return;
    }
    if (current == null) {
      if (deferMacroDarkFailure(requestedStart, diagnostic)) {
        return;
      }
      lastPathingFailure = "Path calculation failed from " + requestedStart + " to " + goal + " (" + result.getType() + ")";
      logDirect(lastPathingFailure);
      finishPathProfile("path calculation failed");
      queuePathEvent(PathEvent.CALC_FAILED);
    } else if (next == null) {
      recordNextPathingFailure(result, requestedStart, diagnostic);
      queuePathEvent(PathEvent.NEXT_CALC_FAILED);
    }
  }

  private boolean deferMacroDarkFailure(BlockPos requestedStart, String diagnostic) {
    return false;
  }

  private void recordNextPathingFailure(PathCalculationResult result, BlockPos requestedStart, String diagnostic) {
    String message =
      "Next calculation failed from " + new BetterBlockPos(requestedStart) + " to " + goal + " (" + result.getType() + ")" + (diagnostic == null || diagnostic.isBlank() ? "" : ": " + diagnostic)
        + "; current=" + (current == null ? "none" : current.src() + "->" + current.dest()) + "; physical=" + physicalExecutionAnchor() + "; anchor=" + activePlanningAnchor;
    lastNextPathingFailure = message;
    synchronized (recentNextPathingFailures) {
      if (recentNextPathingFailures.size() >= 16) {
        recentNextPathingFailures.removeFirst();
      }
      recentNextPathingFailures.addLast(message);
    }
  }

  private void clearNextPathingFailures() {
    lastNextPathingFailure = null;
    synchronized (recentNextPathingFailures) {
      recentNextPathingFailures.clear();
    }
  }

  private boolean calculationOrphaned(PlanningAnchor finishedAnchor, BlockPos requestedStart) {
    if (current != null) {
      return false;
    }
    if (finishedAnchor == PlanningAnchor.CERTIFIED_FUTURE) {
      return true;
    }
    return !sameRelevantStart(new BetterBlockPos(requestedStart), physicalExecutionAnchor());
  }

  private boolean restartOrphanCalculation(PlanningAnchor finishedAnchor, BlockPos requestedStart, boolean talkAboutIt, CalculationContext calculationContext) {
    if (!calculationOrphaned(finishedAnchor, requestedStart)) {
      return false;
    }
    BetterBlockPos physicalStart = physicalExecutionAnchor();
    logDebug("Discarding orphan " + finishedAnchor + " calculation from " + requestedStart + "; restarting from physical execution anchor " + physicalStart);
    queuePathEvent(PathEvent.CALC_STARTED);
    lastTailPlanTicket = null;
    findPathInNewThread(physicalStart, talkAboutIt, calculationContext, PlanningAnchor.PHYSICAL);
    return true;
  }

  private boolean restartRejectedPhysicalRoute(PlanningAnchor rejectedAnchor, BlockPos requestedStart, boolean talkAboutIt, CalculationContext calculationContext) {
    if (current != null || rejectedAnchor != PlanningAnchor.PHYSICAL) {
      return false;
    }
    BetterBlockPos physicalStart = physicalExecutionAnchor();
    if (physicalStart.equals(new BetterBlockPos(requestedStart))) {
      return false;
    }
    logDebug("Rejected stale physical route from " + requestedStart + "; restarting from execution anchor " + physicalStart);
    queuePathEvent(PathEvent.CALC_STARTED);
    lastTailPlanTicket = null;
    findPathInNewThread(physicalStart, talkAboutIt, calculationContext, PlanningAnchor.PHYSICAL);
    return true;
  }

  private CandidateDisposition acceptCandidate(RouteExecutor candidate, Goal terminalGoal) {
    if (!transportLockAccepts(candidate)) {
      return CandidateDisposition.REJECTED;
    }
    RouteExecutor original = candidate;
    boolean futureAnchor = activePlanningAnchor == PlanningAnchor.CERTIFIED_FUTURE;
    Optional<RouteExecutor> currentAnchor = current == null ? anchorAtPhysicalExecution(candidate) : futureAnchor ? Optional.empty() : anchorAtCurrentExecution(candidate);
    if (current == null) {
      if (currentAnchor.isEmpty()) {
        return CandidateDisposition.REJECTED;
      }
      candidate = currentAnchor.get();
      queuePathEvent(PathEvent.CALC_FINISHED_NOW_EXECUTING);
      setCurrentRoute(candidate);
      resetEstimatedTicksToGoal(candidate.src());
      return CandidateDisposition.EXECUTING;
    }
    if (currentAnchor.isPresent()) {
      candidate = currentAnchor.get();
    }
    if (tryReplaceCurrentSuffix(candidate, terminalGoal)) {
      return CandidateDisposition.EXECUTING;
    }
    if (!futureAnchor && tryReplaceCurrentWithAnchoredRoute(candidate, terminalGoal)) {
      return CandidateDisposition.EXECUTING;
    }
    candidate = original;
    if (candidate.src().equals(current.dest())) {
      return queueFutureCandidate(candidate, terminalGoal);
    }
    if (futureAnchor) {
      return CandidateDisposition.REJECTED;
    }
    if (anchorsFutureExecution(candidate)) {
      return queueFutureCandidate(candidate, terminalGoal);
    }
    return CandidateDisposition.REJECTED;
  }

  private boolean transportLockAccepts(RouteExecutor candidate) {
    return !horseModeLock || candidate.route().startState().mode() == TransportMode.HORSE && candidate.route().endState().mode() == TransportMode.HORSE;
  }

  private boolean tryReplaceCurrentWithAnchoredRoute(RouteExecutor candidate, Goal terminalGoal) {
    if (candidate.getPath() != null || !anchorsCurrentExecution(candidate)) {
      return false;
    }
    if (!physicalMacroPreemptionAllowed(candidate, terminalGoal)) {
      return false;
    }
    if (!goalSatisfied(terminalGoal, candidate.dest()) && !executionImproves(candidate, current, terminalGoal)) {
      return false;
    }
    logDebug("Replacing current route with anchored macro route from " + candidate.src() + " to " + candidate.dest());
    setCurrentRoute(candidate);
    next = null;
    queuePathEvent(PathEvent.SPLICING_ONTO_NEXT_EARLY);
    return true;
  }

  private boolean tryReplaceCurrentSuffix(RouteExecutor candidate, Goal terminalGoal) {
    if (!Baritone.settings().splicePath.value) {
      return false;
    }
    Optional<RouteExecutor> replacement = current.tryReplaceSuffix(candidate, current.commitmentEnd(commitmentAheadTicks(), SUFFIX_REPLAN_MIN_ANCHOR_ADVANCE));
    if (replacement.isEmpty()) {
      return false;
    }
    if (!suffixReplacementIsWorthwhile(candidate, replacement.get(), terminalGoal)) {
      return false;
    }
    logDebug("Replacing current route suffix from " + candidate.src() + " to " + candidate.dest());
    setCurrentRoute(replacement.get());
    next = null;
    queuePathEvent(PathEvent.SPLICING_ONTO_NEXT_EARLY);
    return true;
  }

  private boolean suffixReplacementIsWorthwhile(RouteExecutor candidate, RouteExecutor replacement, Goal terminalGoal) {
    if (goalSatisfied(terminalGoal, candidate.dest()) && !goalSatisfied(terminalGoal, current.dest())) {
      return true;
    }
    return executionImproves(replacement, current, terminalGoal);
  }

  private CandidateDisposition queueFutureCandidate(RouteExecutor candidate, Goal terminalGoal) {
    if (!improvesBeyond(candidate, current, terminalGoal) && !executionImproves(candidate, current, terminalGoal)) {
      return CandidateDisposition.REJECTED;
    }
    if (next != null && !improvesBeyond(candidate, next, terminalGoal) && !executionImproves(candidate, next, terminalGoal)) {
      return CandidateDisposition.REJECTED;
    }
    next = candidate;
    queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_FINISHED);
    return CandidateDisposition.QUEUED;
  }

  private boolean executionImproves(RouteExecutor candidate, RouteExecutor incumbent, Goal terminalGoal) {
    double candidateObjective = candidate.estimatedTicksRemainingFromCurrent() + routeObjective(candidate, terminalGoal);
    double incumbentObjective = incumbent.estimatedTicksRemainingFromCurrent() + routeObjective(incumbent, terminalGoal);
    return candidateObjective + preemptionImprovementMargin(candidate, incumbent, incumbentObjective) < incumbentObjective;
  }

  private double commitmentAheadTicks() {
    double dynamic = net.minecraft.util.Mth.clamp(replanWallTicksEWMA * COMMITMENT_LATENCY_MULTIPLIER + COMMITMENT_BUFFER_TICKS, COMMITMENT_MIN_TICKS, COMMITMENT_MAX_TICKS);
    return macroAdvisoryRoute(current) ? Math.max(dynamic, nonnegative(Baritone.settings().farfieldMinCommittedPrefixTicks.value)) : dynamic;
  }

  private double replanAnchorAheadTicks() {
    return commitmentAheadTicks() + COMMITMENT_BUFFER_TICKS;
  }

  private boolean incumbentIsExecutable(RouteExecutor candidate, Goal terminalGoal) {
    IPath path = candidate.getPath();
    PathingIncumbentPolicy policy = incumbentPolicy(candidate);
    return goalSatisfied(terminalGoal, candidate.dest()) || path != null && path.length() >= policy.minLength() || path == null && candidate.size() >= policy.minLength();
  }

  private boolean anchorsCurrentExecution(RouteExecutor path) {
    return anchorAtCurrentExecution(path).isPresent();
  }

  private Optional<RouteExecutor> anchorAtCurrentExecution(RouteExecutor path) {
    Optional<RouteExecutor> anchored = anchorAtPhysicalExecution(path);
    return anchored.isPresent() || current == null ? anchored : path.reanchor(expectedSegmentStart);
  }

  private Optional<RouteExecutor> anchorAtPhysicalExecution(RouteExecutor path) {
    return path.reanchor(physicalExecutionAnchor());
  }

  private BetterBlockPos physicalExecutionAnchor() {
    if (ctx.player().getVehicle() instanceof AbstractHorse horse) {
      return HorsePath.vehicleFeet(horse);
    }
    return ctx.minecraft().isSameThread() ? pathStart() : ctx.playerFeet();
  }

  private boolean anchorsFutureExecution(RouteExecutor path) {
    return anchorsCurrentExecution(path) || current != null && path.src().equals(current.dest());
  }

  private boolean improvesBeyond(RouteExecutor candidate, RouteExecutor incumbent, Goal terminalGoal) {
    return goalSatisfied(terminalGoal, candidate.dest()) || routeObjective(candidate, terminalGoal) + incumbentPolicy(candidate, incumbent).heuristicMargin() < routeObjective(incumbent, terminalGoal);
  }

  private static PathingIncumbentPolicy incumbentPolicy(RouteExecutor route) {
    return PathingIncumbentPolicy.forMode(route.route().startState().mode()).combinedWith(PathingIncumbentPolicy.forMode(route.route().endState().mode()));
  }

  private static PathingIncumbentPolicy incumbentPolicy(RouteExecutor left, RouteExecutor right) {
    return incumbentPolicy(left).combinedWith(incumbentPolicy(right));
  }

  private double routeObjective(RouteExecutor executor, Goal terminalGoal) {
    if (Double.isFinite(executor.estimatedContinuationTicks())) {
      return executor.estimatedContinuationTicks();
    }
    IPath path = executor.getPath();
    if (path != null && path.getGoal() instanceof FarfieldObjective farfield) {
      return farfield.expectedObjective(executor.dest());
    }
    return path != null && path.getGoal() instanceof LocalExitObjective projected ? projected.localExitValue(executor.dest().x, executor.dest().y, executor.dest().z)
      : terminalGoal.heuristic(executor.dest().x, executor.dest().y, executor.dest().z);
  }

  private boolean physicalMacroPreemptionAllowed(RouteExecutor candidate, Goal terminalGoal) {
    if (!macroAdvisoryRoute(current) || goalSatisfied(terminalGoal, candidate.dest())) {
      return true;
    }
    return current.progress().ticks() >= nonnegative(Baritone.settings().farfieldMinCommittedPrefixTicks.value);
  }

  private double preemptionImprovementMargin(RouteExecutor candidate, RouteExecutor incumbent, double incumbentObjective) {
    if (!macroAdvisoryRoute(candidate) && !macroAdvisoryRoute(incumbent)) {
      return SUFFIX_REPLACEMENT_COST_EPSILON;
    }
    double absolute = nonnegative(Baritone.settings().farfieldPhysicalPreemptMinImprovementTicks.value);
    double relative = Double.isFinite(incumbentObjective) ? Math.max(0D, incumbentObjective) * nonnegative(Baritone.settings().farfieldPhysicalPreemptMinImprovementRatio.value) : 0D;
    return Math.max(absolute, relative);
  }

  private static boolean macroAdvisoryRoute(RouteExecutor route) {
    return route != null && Double.isFinite(route.estimatedContinuationTicks()) && route.route().startState().mode() != TransportMode.HORSE && route.route().endState().mode() != TransportMode.HORSE;
  }

  private static double nonnegative(double value) {
    return Double.isFinite(value) && value > 0D ? value : 0D;
  }

  private boolean goalSatisfied(BetterBlockPos pos) {
    return GoalTerminalPolicy.satisfied(baritone, ctx, goal, pos);
  }

  private boolean goalSatisfied(Goal terminalGoal, BetterBlockPos pos) {
    return horseModeLock ? GoalTerminalPolicy.plannedHorseSatisfied(terminalGoal, pos) : terminalGoal == null || terminalGoal.isInGoal(pos);
  }

  private enum CandidateDisposition {
    EXECUTING, QUEUED, REJECTED
  }

  private CalculationLaunch createPathfinder(BlockPos start, Goal goal, IPath previous, CalculationContext context, long failureTimeoutMS, PlanningAnchor planningAnchor) {
    Goal terminalGoal = GoalTerminalPolicy.project(context, goal);
    Goal transformed = terminalGoal;
    BetterBlockPos realStart = new BetterBlockPos(start);
    MacroTraversalProfile profile = MacroTraversalProfile.physical(context);
    HorseRoutePlanner.Start horseStart = null;
    if (horseModeLock && !profile.horse()) {
      return new FailedCalculation(terminalGoal, "horse mode-locked");
    }
    if (profile.horse()) {
      horseStart = horseRouteStart(realStart, planningAnchor);
      realStart = horseStart.pos();
    }
    MacroPlan macroPlan = null;
    FarfieldObjective farfieldObjective = null;
    planningFarfieldObjective = null;
    Optional<RoutePlan> immediateRoute = Optional.empty();
    if (!profile.horse() && transitPlanningEnabled()) {
      Optional<MacroDirective> macroDirective = MacroCoordinator.plan(context, realStart, terminalGoal, profile);
      if (macroDirective.isPresent()) {
        macroPlan = macroDirective.get().plan();
        if (macroDirective.get().deferred()) {
          return new DeferredCalculation(macroPlan);
        }
        immediateRoute = macroDirective.get().certifiedRoute();
        transformed = macroDirective.get().localGoal();
        if (!Objects.equals(transformed, goal)) {
          logDebug("Routing via Transit legacy directive " + transformed + " (" + String.format(java.util.Locale.ROOT, "%.1f", macroPlan.totalVector().timeTicks()) + "t expected, seq="
            + macroPlan.sequence() + ", planner=" + macroPlan.valueTelemetry().planner() + ")");
        }
      }
    }
    if (macroPlan == null) {
      Optional<FarfieldObjective> farfield = farfieldNavigator.objective(context, realStart, terminalGoal, profile);
      if (farfield.isPresent()) {
        farfieldObjective = farfield.get();
        transformed = farfieldObjective;
        if (!Objects.equals(transformed, goal)) {
          logDebug("Routing with Farfield frontier values " + transformed);
        }
      }
    }
    planningFarfieldObjective = farfieldObjective;
    if (Baritone.settings().simplifyUnloadedYCoord.value && terminalGoal instanceof IGoalRenderPos) {
      BlockPos pos = ((IGoalRenderPos) terminalGoal).getGoalPos();
      if (transformed == terminalGoal && !context.bsi.hasLiveChunk(pos.getX(), pos.getZ())) {
        transformed = new GoalXZ(pos.getX(), pos.getZ());
      }
    }
    Favoring favoring = new Favoring(context.getBaritone().getPlayerContext(), previous, context);
    if (!profile.horse()) {
      BetterBlockPos feet = ctx.playerFeet();
      var sub = feet.subtract(realStart);
      if (feet.getY() == realStart.getY() && Math.abs(sub.getX()) <= 1 && Math.abs(sub.getZ()) <= 1) {
        realStart = feet;
      }
    }
    if (profile.horse()) {
      return new HorseCalculation(horseStart, transformed, terminalGoal, macroPlan, failureTimeoutMS);
    }
    if (Baritone.settings().pedestrianHotLocalValueField.value && hotLocalValueFieldApplies(transformed, macroPlan)) {
      return new CreatedPathfinder(pedestrianHotPlanner.query(context, realStart, start.getX(), start.getY(), start.getZ(), transformed, terminalGoal, macroPlan, favoring, worldFactEpoch),
        terminalGoal, macroPlan, immediateRoute);
    }
    return new CreatedPathfinder(new AStarPathFinder(realStart, start.getX(), start.getY(), start.getZ(), transformed, favoring, context, PathingIncumbentPolicy.pedestrian()), terminalGoal, macroPlan,
      immediateRoute);

  }

  private static boolean hotLocalValueFieldApplies(Goal localGoal, MacroPlan macroPlan) {
    return macroPlan != null || localGoal instanceof FrontierValueObjective || localGoal instanceof LocalExitObjective;
  }

  private static boolean transitPlanningEnabled() {
    return Baritone.settings().transitPlanning.value;
  }

  private sealed interface CalculationLaunch permits CreatedPathfinder, DeferredCalculation, HorseCalculation, ImmediateRouteCalculation, FailedCalculation {
  }

  private record CreatedPathfinder(ActivePathCalculation pathfinder, Goal terminalGoal, MacroPlan macroPlan, Optional<RoutePlan> immediateRoute) implements CalculationLaunch {
    private CreatedPathfinder(ActivePathCalculation pathfinder, Goal terminalGoal, MacroPlan macroPlan, Optional<RoutePlan> immediateRoute) {
      this.pathfinder = Objects.requireNonNull(pathfinder);
      this.terminalGoal = Objects.requireNonNull(terminalGoal);
      this.macroPlan = macroPlan;
      this.immediateRoute = Objects.requireNonNull(immediateRoute);
    }
  }

  private record DeferredCalculation(MacroPlan plan) implements CalculationLaunch {
    private DeferredCalculation {
      Objects.requireNonNull(plan);
    }
  }

  private HorseRoutePlanner.Start horseRouteStart(BetterBlockPos requestedStart, PlanningAnchor planningAnchor) {
    if (planningAnchor == PlanningAnchor.PHYSICAL && ctx.player().getVehicle() instanceof AbstractHorse horse) {
      return HorseRoutePlanner.physicalStart(horse);
    }
    if (planningAnchor == PlanningAnchor.CERTIFIED_FUTURE) {
      Optional<HorsePath.Waypoint> waypoint = certifiedHorseWaypoint(requestedStart);
      if (waypoint.isPresent()) {
        return HorseRoutePlanner.certifiedStart(waypoint.get());
      }
    }
    return HorseRoutePlanner.Start.corner(requestedStart);
  }

  private Optional<HorsePath.Waypoint> certifiedHorseWaypoint(BlockPos pos) {
    if (current != null) {
      Optional<HorsePath.Waypoint> currentWaypoint = current.horseWaypointAtOrAfterCurrent(pos);
      if (currentWaypoint.isPresent()) {
        return currentWaypoint;
      }
    }
    return next == null ? Optional.empty() : next.horseWaypointAtOrAfterCurrent(pos);
  }

  private enum PlanningAnchor {
    PHYSICAL, CERTIFIED_FUTURE
  }

  private record HorseCalculation(HorseRoutePlanner.Start routeStart, Goal localGoal, Goal terminalGoal, MacroPlan macroPlan, long failureTimeoutMS) implements CalculationLaunch {
    private HorseCalculation {
      Objects.requireNonNull(routeStart);
      Objects.requireNonNull(localGoal);
      Objects.requireNonNull(terminalGoal);
    }
  }

  private record ImmediateRouteCalculation(RoutePlan route, Goal terminalGoal, MacroPlan macroPlan, String kind) implements CalculationLaunch {
    private ImmediateRouteCalculation {
      Objects.requireNonNull(route);
      Objects.requireNonNull(terminalGoal);
      Objects.requireNonNull(kind);
    }
  }

  private record FailedCalculation(Goal terminalGoal, String kind) implements CalculationLaunch {
    private FailedCalculation {
      Objects.requireNonNull(terminalGoal);
      Objects.requireNonNull(kind);
    }
  }

  private record TailPlanTicket(BetterBlockPos start, Goal goal, long factEpoch, int tick, boolean strictStart) {
    private boolean matches(BetterBlockPos candidateStart, Goal candidateGoal, long candidateFactEpoch, int candidateTick) {
      if (!Objects.equals(goal, candidateGoal) || !(strictStart ? sameHorseExecutionStart(start, candidateStart) : sameCertificationBucket(start, candidateStart))) {
        return false;
      }
      return factEpoch == candidateFactEpoch || candidateTick - tick < SAME_ANCHOR_FACT_REPLAN_INTERVAL_TICKS;
    }
  }

  @Override
  public void onRenderPass(RenderEvent event) {
    PathRenderer.render(event, this);
  }
}
