package baritone.behavior;

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
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.calc.BestExitGoal;
import baritone.pathing.control.ControlArbiter;
import baritone.pathing.goal.GoalTerminalPolicy;
import baritone.pathing.macro.core.MacroCoordinator;
import baritone.pathing.macro.core.MacroDirective;
import baritone.pathing.macro.core.MacroNavigator;
import baritone.pathing.macro.core.MacroPlan;
import baritone.pathing.macro.core.MacroProjectedGoal;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.path.RouteExecutor;
import baritone.pathing.route.RouteFactFootprint;
import baritone.pathing.route.RoutePlan;
import baritone.pathing.transport.TransportSnapshot;
import baritone.utils.PathRenderer;
import baritone.utils.PathingCommandContext;
import baritone.utils.pathing.Favoring;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;

public final class PathingBehavior extends Behavior implements IPathingBehavior, Helper {

  private static final int SUFFIX_REPLAN_MIN_ANCHOR_ADVANCE = 12;
  private static final int SUFFIX_REPLAN_TARGET_ANCHOR_ADVANCE = 48;
  private static final int SUFFIX_REPLAN_MAX_ANCHOR_ADVANCE = 96;
  private static final int SAME_ANCHOR_FACT_REPLAN_INTERVAL_TICKS = 20;
  private static final int OPPORTUNISTIC_FACT_REPLAN_INTERVAL_TICKS = 80;
  private static final int OPPORTUNISTIC_FACT_REPLAN_MIN_ADVANCE_BLOCKS = 32;
  private static final double SUFFIX_REPLACEMENT_COST_EPSILON = 2D;

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

  private volatile AbstractNodeCostSearch inProgress;
  private volatile BetterBlockPos activePlanningStart;
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
  private final Object pathCalcLock = new Object();

  private final Object pathPlanLock = new Object();

  private boolean lastAutoJump;

  private BetterBlockPos expectedSegmentStart;

  private final LinkedBlockingQueue<PathEvent> toDispatch = new LinkedBlockingQueue<>();
  private final ControlArbiter controlArbiter;
  private final MacroNavigator macroNavigator = new MacroNavigator();
  private TailPlanTicket lastTailPlanTicket;

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
  }

  private void recordWorldFact(ChunkPos chunk, boolean invalidatesCertifiedFootprint) {
    worldFactEpoch++;
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
      if (current.failed() || current.finished()) {
        clearCurrentRoute();
        if (goalSatisfied(ctx.playerFeet())) {
          logDebug("All done. At " + goal);
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
      if (discardStaleSurfaceTail()) {
        return;
      }
      if (preemptLegacyWaterPlanning()) {
        return;
      }
      if (preemptStaleCurrentRoute()) {
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
        if (goalSatisfied(current.dest())) {
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
          findPathInNewThread(start, false, context);
        }
      }
    }
  }

  private boolean calculationStartIsStillRelevant(BetterBlockPos calcFrom) {
    Optional<BetterBlockPos> certifiedPreemption = certifiedPreemptionStart();
    if (certifiedPreemption.isPresent() && !sameCertificationBucket(certifiedPreemption.get(), calcFrom)) {
      return false;
    }
    if (current != null && current.getPath() == null) {
      return current.planAheadStart().equals(calcFrom);
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

  private boolean bestCalculationPathIsStillRelevant(Optional<IPath> currentBest) {
    if (current != null && current.getPath() == null) {
      return false;
    }
    return currentBest.isPresent() && (currentBest.get().positions().contains(ctx.playerFeet()) || currentBest.get().positions().contains(expectedSegmentStart));
  }

  private boolean shouldStartTailPlanning() {
    if (current != null && current.getPath() == null) {
      return ticksRemainingInSegment(false).orElse(Double.POSITIVE_INFINITY) < Baritone.settings().planningTickLookahead.value;
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
      return current.planAheadStart();
    }
    if (ctx.player().isSwimming() || MovementHelper.isWater(ctx, ctx.playerFeet())) {
      return ctx.playerFeet();
    }
    if (!Baritone.settings().pathingContinuousPlanning.value) {
      return path.getDest();
    }
    int position = Math.max(0, Math.min(current.getPosition(), path.length() - 1));
    int remainingMovements = path.length() - 1 - position;
    if (remainingMovements <= SUFFIX_REPLAN_MIN_ANCHOR_ADVANCE) {
      return path.getDest();
    }
    int advance =
      Math.min(remainingMovements, Math.min(SUFFIX_REPLAN_MAX_ANCHOR_ADVANCE, Math.max(SUFFIX_REPLAN_MIN_ANCHOR_ADVANCE, Math.max(SUFFIX_REPLAN_TARGET_ANCHOR_ADVANCE, remainingMovements / 3))));
    return path.positions().get(Math.min(path.length() - 1, position + advance));
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
      activeMacroPlan = null;
      planningMacroPlan = null;
      clearRouteFactState();
      clearDeferredMacroPlan();
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
    if (goalSatisfied(ctx.playerFeet())) {
      return false;
    }
    synchronized (pathPlanLock) {
      if (current != null) {
        return false;
      }
      synchronized (pathCalcLock) {
        if (calculationActive()) {
          return false;
        }
        queuePathEvent(PathEvent.CALC_STARTED);
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
  public Optional<AbstractNodeCostSearch> getInProgress() { return Optional.ofNullable(inProgress); }

  public Optional<BetterBlockPos> getPlanningStart() { return calculationActive() ? Optional.ofNullable(activePlanningStart) : Optional.empty(); }

  public Optional<MacroPlan> getMacroPlan() {
    MacroPlan active = activeMacroPlan;
    return Optional.ofNullable(active == null ? planningMacroPlan : active);
  }

  public Optional<MacroPlan> getRenderableMacroPlan() {
    if (current != null || next != null) {
      return Optional.empty();
    }
    return Optional.ofNullable(planningMacroPlan);
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
    // do everything BUT clear keys
  }

  // just cancel the current path
  public void secretInternalSegmentCancel() {
    queuePathEvent(PathEvent.CANCELED);
    synchronized (pathPlanLock) {
      synchronized (pathCalcLock) {
        cancelCalculation();
      }
      if (current != null) {
        clearCurrentRoute();
        next = null;
        clearDeferredMacroPlan();
        controlArbiter.clearPathingControls();
      }
    }
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
    BetterBlockPos feet = ctx.playerFeet();
    if (!MovementHelper.canWalkOn(ctx, feet.below())) {
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
          if (MovementHelper.canWalkOn(ctx, possibleSupport.below()) && MovementHelper.canWalkThrough(ctx, possibleSupport) && MovementHelper.canWalkThrough(ctx, possibleSupport.above())) {
            // this is plausible
            //logDebug("Faking path start assuming player is standing off the edge of a block");
            return possibleSupport;
          }
        }

      } else {
        // !onGround
        // we're in the middle of a jump
        if (MovementHelper.canWalkOn(ctx, feet.below().below())) {
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
    planningMacroPlan = null;
    pathCalcLaunching = true;
    long epoch = ++pathCalcEpoch;
    Baritone.getExecutor().execute(() -> {
      CalculationLaunch created;
      try {
        synchronized (macroNavigator) {
          created = createPathfinder(start, goal, previous, calculationContext);
        }
      } catch (Exception e) {
        logDirect("Pathing exception before A*: " + e);
        e.printStackTrace();
        synchronized (pathPlanLock) {
          synchronized (pathCalcLock) {
            if (pathCalcEpoch == epoch && pathCalcLaunching) {
              pathCalcLaunching = false;
              activePlanningStart = null;
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
            deferMacroPlan(deferred.plan());
            pathCalcLaunching = false;
            activePlanningStart = null;
            logDebug("Awaiting factual macro prefix before actuation (seq=" + deferred.plan().sequence() + ")");
          }
        }
        return;
      }
      CreatedPathfinder pathLaunch = (CreatedPathfinder) created;
      AbstractNodeCostSearch pathfinder = pathLaunch.pathfinder();
      if (pathLaunch.immediateRoute().isPresent()) {
        RouteExecutor route = new RouteExecutor(PathingBehavior.this, pathLaunch.immediateRoute().get());
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
              activePlanningStart = null;
              if (talkAboutIt && disposition == CandidateDisposition.EXECUTING) {
                logDebug("Executing immediate macro route from " + route.src() + " towards " + goal + " (seq=" + pathLaunch.macroPlan().sequence() + ")");
              }
              return;
            }
            logDebug("Discarding immediate macro route from " + route.src() + " to " + route.dest() + " while current=" + (current == null ? "none" : current.src() + "->" + current.dest()) + " feet="
              + ctx.playerFeet() + " expected=" + expectedSegmentStart);
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
        synchronized (pathCalcLock) {
          currentCalculation = pathCalcEpoch == epoch && inProgress == pathfinder;
        }
        if (currentCalculation) {
          acceptCalculation(calcResult, start, talkAboutIt, true, calculationContext);
        }
        synchronized (pathCalcLock) {
          if (inProgress == pathfinder) {
            inProgress = null;
            activePlanningStart = null;
            planningMacroPlan = null;
          }
        }
      }
    });
  }

  private boolean calculationActive() {
    return inProgress != null || pathCalcLaunching;
  }

  private boolean preemptLegacyWaterPlanning() {
    if (!Baritone.settings().macroPlanning.value) {
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

  private boolean preemptStaleCurrentRoute() {
    if (current == null || next != null || goalSatisfied(current.dest())) {
      return false;
    }
    BetterBlockPos start = pathStart();
    boolean dirty = currentRouteFactsDirty;
    if (!dirty && !shouldOpportunisticallyReplan(start)) {
      return false;
    }
    synchronized (pathCalcLock) {
      if (calculationActive()) {
        if (planningFrom(start)) {
          return true;
        }
        logDebug(dirty ? "Preempting future-anchor calculation; certified route facts changed near " + start : "Preempting future-anchor calculation; improving route after new facts from " + start);
        cancelPlanningCalculation();
      }
      if (dirty) {
        lastTailPlanTicket = null;
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
    lastTailPlanTicket = new TailPlanTicket(new BetterBlockPos(start), goal, worldFactEpoch, ticksElapsedSoFar);
    return true;
  }

  private Optional<BetterBlockPos> certifiedPreemptionStart() {
    if (current == null || current.getPath() == null || goalSatisfied(ctx.playerFeet())) {
      return Optional.empty();
    }
    if (ctx.player().isSwimming() || MovementHelper.isWater(ctx, ctx.playerFeet())) {
      return Optional.of(ctx.playerFeet());
    }
    return Optional.empty();
  }

  private boolean planningFrom(BetterBlockPos start) {
    if (sameCertificationBucket(start, activePlanningStart)) {
      return true;
    }
    return inProgress != null && sameCertificationBucket(start, inProgress.getStart());
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
    activePlanningStart = null;
    planningMacroPlan = null;
  }

  private void commitMacroPlan(MacroPlan plan) {
    activeMacroPlan = plan;
    planningMacroPlan = null;
    clearDeferredMacroPlan();
  }

  private void cancelCalculation() {
    pathCalcEpoch++;
    pathCalcLaunching = false;
    getInProgress().ifPresent(AbstractNodeCostSearch::cancel);
    activePlanningStart = null;
    activeMacroPlan = null;
    planningMacroPlan = null;
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

  private CalculationContext liveCalculationContext(CalculationContext base) {
    return base != null && base.getClass() != CalculationContext.class ? base : new CalculationContext(baritone, true);
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

  private void acceptIncumbent(AbstractNodeCostSearch pathfinder, PathCalculationResult result) {
    if (!Baritone.settings().pathingEarlyIncumbentExecution.value) {
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

  private void acceptCalculation(PathCalculationResult result, BlockPos requestedStart, boolean talkAboutIt, boolean finalResult, CalculationContext calculationContext) {
    if (!Thread.holdsLock(pathPlanLock)) {
      throw new IllegalStateException("Must hold pathPlanLock while accepting a path calculation");
    }
    Optional<IPath> path = result.getPath();
    MacroPlan macroPlan = planningMacroPlan;
    Optional<RouteExecutor> executor = finalResult && calculationContext != null && macroPlan != null
      ? MacroCoordinator.materialize(calculationContext, macroPlan, path.orElse(null)).map(route -> new RouteExecutor(PathingBehavior.this, route)) : Optional.empty();
    executor = executor.or(() -> path.map(p -> new RouteExecutor(PathingBehavior.this, p)));
    if (executor.isEmpty()) {
      acceptEmptyCalculation(result, requestedStart, finalResult);
      return;
    }
    RouteExecutor candidate = executor.get();
    Goal terminalGoal = calculationContext == null ? goal : GoalTerminalPolicy.project(calculationContext, goal);
    if (!finalResult && !incumbentIsExecutable(candidate, terminalGoal)) {
      return;
    }
    CandidateDisposition disposition = acceptCandidate(candidate, terminalGoal);
    if (disposition == CandidateDisposition.REJECTED) {
      if (finalResult) {
        logDebug("Discarding orphan route segment from " + requestedStart + " to " + candidate.dest());
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

  private void acceptEmptyCalculation(PathCalculationResult result, BlockPos requestedStart, boolean finalResult) {
    if (!finalResult || result.getType() == PathCalculationResult.Type.CANCELLATION || result.getType() == PathCalculationResult.Type.EXCEPTION) {
      return;
    }
    if (current == null) {
      logDirect("Path calculation failed from " + requestedStart + " to " + goal + " (" + result.getType() + ")");
      queuePathEvent(PathEvent.CALC_FAILED);
    } else if (next == null) {
      queuePathEvent(PathEvent.NEXT_CALC_FAILED);
    }
  }

  private CandidateDisposition acceptCandidate(RouteExecutor candidate, Goal terminalGoal) {
    RouteExecutor original = candidate;
    Optional<RouteExecutor> currentAnchor = anchorAtCurrentExecution(candidate);
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
    if (tryReplaceCurrentWithAnchoredRoute(candidate, terminalGoal)) {
      return CandidateDisposition.EXECUTING;
    }
    candidate = original;
    if (candidate.src().equals(current.dest())) {
      return queueFutureCandidate(candidate, terminalGoal);
    }
    if (anchorsFutureExecution(candidate)) {
      return queueFutureCandidate(candidate, terminalGoal);
    }
    return CandidateDisposition.REJECTED;
  }

  private boolean tryReplaceCurrentWithAnchoredRoute(RouteExecutor candidate, Goal terminalGoal) {
    if (candidate.getPath() != null || !anchorsCurrentExecution(candidate)) {
      return false;
    }
    if (!improvesBeyond(candidate, current, terminalGoal) && !executionImproves(candidate, current, terminalGoal)) {
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
    Optional<RouteExecutor> replacement = current.tryReplaceSuffix(candidate, current.getPosition() + SUFFIX_REPLAN_MIN_ANCHOR_ADVANCE);
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
    if (goalSatisfied(terminalGoal, candidate.dest()) || improvesBeyond(candidate, current, terminalGoal)) {
      return true;
    }
    return replacement.estimatedTicksRemainingFromCurrent() + SUFFIX_REPLACEMENT_COST_EPSILON < current.estimatedTicksRemainingFromCurrent();
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
    return candidate.estimatedTicksRemainingFromCurrent() + routeObjective(candidate, terminalGoal) + SUFFIX_REPLACEMENT_COST_EPSILON < incumbent.estimatedTicksRemainingFromCurrent()
      + routeObjective(incumbent, terminalGoal);
  }

  private boolean incumbentIsExecutable(RouteExecutor candidate, Goal terminalGoal) {
    IPath path = candidate.getPath();
    return goalSatisfied(terminalGoal, candidate.dest()) || path != null && path.length() >= Baritone.settings().pathingMinIncumbentLength.value
      || path == null && candidate.size() >= Baritone.settings().pathingMinIncumbentLength.value;
  }

  private boolean anchorsCurrentExecution(RouteExecutor path) {
    return anchorAtCurrentExecution(path).isPresent();
  }

  private Optional<RouteExecutor> anchorAtCurrentExecution(RouteExecutor path) {
    Optional<RouteExecutor> anchored = path.reanchor(ctx.playerFeet());
    return anchored.isPresent() ? anchored : path.reanchor(expectedSegmentStart);
  }

  private boolean anchorsFutureExecution(RouteExecutor path) {
    return anchorsCurrentExecution(path) || current != null && path.src().equals(current.dest());
  }

  private boolean improvesBeyond(RouteExecutor candidate, RouteExecutor incumbent, Goal terminalGoal) {
    return goalSatisfied(terminalGoal, candidate.dest())
      || routeObjective(candidate, terminalGoal) + Baritone.settings().pathingIncumbentHeuristicMargin.value < routeObjective(incumbent, terminalGoal);
  }

  private double routeObjective(RouteExecutor executor, Goal terminalGoal) {
    if (Double.isFinite(executor.estimatedContinuationTicks())) {
      return executor.estimatedContinuationTicks();
    }
    IPath path = executor.getPath();
    if (path != null && path.getGoal() instanceof MacroProjectedGoal macro) {
      return macro.expectedObjective(executor.dest());
    }
    return path != null && path.getGoal() instanceof BestExitGoal projected ? projected.exitValue(executor.dest().x, executor.dest().y, executor.dest().z)
      : terminalGoal.heuristic(executor.dest().x, executor.dest().y, executor.dest().z);
  }

  private boolean goalSatisfied(BetterBlockPos pos) {
    return GoalTerminalPolicy.satisfied(baritone, ctx, goal, pos);
  }

  private static boolean goalSatisfied(Goal terminalGoal, BetterBlockPos pos) {
    return terminalGoal == null || terminalGoal.isInGoal(pos);
  }

  private enum CandidateDisposition {
    EXECUTING, QUEUED, REJECTED
  }

  private CalculationLaunch createPathfinder(BlockPos start, Goal goal, IPath previous, CalculationContext context) {
    Goal terminalGoal = GoalTerminalPolicy.project(context, goal);
    Goal transformed = terminalGoal;
    BetterBlockPos realStart = new BetterBlockPos(start);
    MacroPlan macroPlan = null;
    Optional<RoutePlan> immediateRoute = Optional.empty();
    Optional<MacroDirective> macroDirective = MacroCoordinator.plan(macroNavigator, context, realStart, terminalGoal);
    if (macroDirective.isPresent()) {
      macroPlan = macroDirective.get().plan();
      if (macroDirective.get().deferred()) {
        return new DeferredCalculation(macroPlan);
      }
      immediateRoute = macroDirective.get().certifiedRoute();
      transformed = macroDirective.get().localGoal();
      if (!Objects.equals(transformed, goal)) {
        logDebug("Routing via macro value field " + transformed + " (" + String.format(java.util.Locale.ROOT, "%.1f", macroPlan.totalVector().timeTicks()) + "t expected, seq=" + macroPlan.sequence()
          + ", planner=" + macroPlan.valueTelemetry().planner() + ")");
      }
    }
    if (Baritone.settings().simplifyUnloadedYCoord.value && terminalGoal instanceof IGoalRenderPos) {
      BlockPos pos = ((IGoalRenderPos) terminalGoal).getGoalPos();
      if (transformed == terminalGoal && !context.bsi.hasLiveChunk(pos.getX(), pos.getZ())) {
        transformed = new GoalXZ(pos.getX(), pos.getZ());
      }
    }
    Favoring favoring = new Favoring(context.getBaritone().getPlayerContext(), previous, context);
    BetterBlockPos feet = ctx.playerFeet();
    var sub = feet.subtract(realStart);
    if (feet.getY() == realStart.getY() && Math.abs(sub.getX()) <= 1 && Math.abs(sub.getZ()) <= 1) {
      realStart = feet;
    }
    return new CreatedPathfinder(new AStarPathFinder(realStart, start.getX(), start.getY(), start.getZ(), transformed, favoring, context), terminalGoal, macroPlan, immediateRoute);

  }

  private sealed interface CalculationLaunch permits CreatedPathfinder, DeferredCalculation {
  }

  private record CreatedPathfinder(AbstractNodeCostSearch pathfinder, Goal terminalGoal, MacroPlan macroPlan, Optional<RoutePlan> immediateRoute) implements CalculationLaunch {
    private CreatedPathfinder(AbstractNodeCostSearch pathfinder, Goal terminalGoal, MacroPlan macroPlan, Optional<RoutePlan> immediateRoute) {
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

  private record TailPlanTicket(BetterBlockPos start, Goal goal, long factEpoch, int tick) {
    private boolean matches(BetterBlockPos candidateStart, Goal candidateGoal, long candidateFactEpoch, int candidateTick) {
      if (!Objects.equals(goal, candidateGoal) || !sameCertificationBucket(start, candidateStart)) {
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
