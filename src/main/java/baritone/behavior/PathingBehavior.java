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
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.path.PathExecutor;
import baritone.pathing.transport.TransportSnapshot;
import baritone.utils.PathRenderer;
import baritone.utils.PathingCommandContext;
import baritone.utils.pathing.Favoring;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;

public final class PathingBehavior extends Behavior implements IPathingBehavior, Helper {

  private static final int SUFFIX_REPLAN_MIN_ANCHOR_ADVANCE = 12;
  private static final int SUFFIX_REPLAN_TARGET_ANCHOR_ADVANCE = 48;
  private static final int SUFFIX_REPLAN_MAX_ANCHOR_ADVANCE = 96;
  private static final double SUFFIX_REPLACEMENT_COST_EPSILON = 2D;

  private PathExecutor current;
  private PathExecutor next;

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
  private final Object pathCalcLock = new Object();

  private final Object pathPlanLock = new Object();

  private boolean lastAutoJump;

  private BetterBlockPos expectedSegmentStart;

  private final LinkedBlockingQueue<PathEvent> toDispatch = new LinkedBlockingQueue<>();

  public PathingBehavior(Baritone baritone) {
    super(baritone);
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

  private void tickPath() {
    pausedThisTick = false;
    if (pauseRequestedLastTick && safeToCancel) {
      pauseRequestedLastTick = false;
      if (unpausedLastTick) {
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
      }
      unpausedLastTick = false;
      pausedThisTick = true;
      return;
    }
    unpausedLastTick = true;
    if (cancelRequested) {
      cancelRequested = false;
      baritone.getInputOverrideHandler().clearAllKeys();
    }
    synchronized (pathPlanLock) {
      synchronized (pathCalcLock) {
        if (inProgress != null) {
          // we are calculating
          // are we calculating the right thing though? 🤔
          BetterBlockPos calcFrom = inProgress.getStart();
          if (!calculationStartIsStillRelevant(calcFrom) && !bestCalculationPathIsStillRelevant(inProgress.bestPathSoFar())) {
            // when it was *just* started, currentBest will be empty so we need to also check calcFrom since that's always present
            inProgress.cancel(); // cancellation doesn't dispatch any events
          }
        }
      }
      if (current == null) {
        return;
      }
      safeToCancel = current.onTick();
      if (current.failed() || current.finished()) {
        current = null;
        if (goal == null || goal.isInGoal(ctx.playerFeet())) {
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
          current = next;
          next = null;
          current.onTick(); // don't waste a tick doing nothing, get started right away
          return;
        }
        // at this point, current just ended, but we aren't in the goal and have no plan for the future
        synchronized (pathCalcLock) {
          if (inProgress != null) {
            queuePathEvent(PathEvent.PATH_FINISHED_NEXT_STILL_CALCULATING);
            return;
          }
          // we aren't calculating
          queuePathEvent(PathEvent.CALC_STARTED);
          findPathInNewThread(expectedSegmentStart, true, context);
        }
        return;
      }
      // at this point, we know current is in progress
      if (safeToCancel && next != null && next.snipsnapifpossible()) {
        // a movement just ended; jump directly onto the next path
        logDebug("Splicing into planned next path early...");
        queuePathEvent(PathEvent.SPLICING_ONTO_NEXT_EARLY);
        current = next;
        next = null;
        current.onTick();
        return;
      }
      if (Baritone.settings().splicePath.value) {
        current = current.trySplice(next);
      }
      if (next != null && current.getPath().getDest().equals(next.getPath().getDest())) {
        next = null;
      }
      synchronized (pathCalcLock) {
        if (inProgress != null) {
          // if we aren't calculating right now
          return;
        }
        if (next != null) {
          // and we have no plan for what to do next
          return;
        }
        if (goal == null || goal.isInGoal(current.getPath().getDest())) {
          // and this path doesn't get us all the way there
          return;
        }
        if (shouldStartTailPlanning()) {
          BetterBlockPos start = planAheadStart();
          logDebug(start.equals(current.getPath().getDest()) ? "Extending path tail in background..." : "Refining path suffix from future anchor " + start + "...");
          queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_STARTED);
          findPathInNewThread(start, false, context);
        }
      }
    }
  }

  private boolean calculationStartIsStillRelevant(BetterBlockPos calcFrom) {
    if (calcFrom.equals(ctx.playerFeet()) || calcFrom.equals(expectedSegmentStart)) {
      return true;
    }
    if (current != null && (current.getPath().getDest().equals(calcFrom) || current.containsPathPosition(calcFrom))) {
      return true;
    }
    if (next != null && (next.getPath().getDest().equals(calcFrom) || next.containsPathPosition(calcFrom))) {
      return true;
    }
    return false;
  }

  private boolean bestCalculationPathIsStillRelevant(Optional<IPath> currentBest) {
    return currentBest.isPresent() && (currentBest.get().positions().contains(ctx.playerFeet()) || currentBest.get().positions().contains(expectedSegmentStart));
  }

  private boolean shouldStartTailPlanning() {
    if (Baritone.settings().pathingContinuousPlanning.value) {
      return true;
    }
    // Don't include the current movement: a very long final movement should not suppress planning until it completes.
    return ticksRemainingInSegment(false).get() < Baritone.settings().planningTickLookahead.value;
  }

  private BetterBlockPos planAheadStart() {
    IPath path = current.getPath();
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
    if (goal.isInGoal(ctx.playerFeet())) {
      return false;
    }
    synchronized (pathPlanLock) {
      if (current != null) {
        return false;
      }
      synchronized (pathCalcLock) {
        if (inProgress != null) {
          return false;
        }
        queuePathEvent(PathEvent.CALC_STARTED);
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
  public PathExecutor getCurrent() { return current; }

  @Override
  public PathExecutor getNext() { return next; }

  @Override
  public Optional<AbstractNodeCostSearch> getInProgress() { return Optional.ofNullable(inProgress); }

  public Optional<BetterBlockPos> getPlanningStart() { return inProgress == null ? Optional.empty() : Optional.ofNullable(activePlanningStart); }

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
      getInProgress().ifPresent(AbstractNodeCostSearch::cancel); // only cancel ours
      activePlanningStart = null;
      if (!isSafeToCancel()) {
        return;
      }
      current = null;
      next = null;
    }
    cancelRequested = true;
    // do everything BUT clear keys
  }

  // just cancel the current path
  public void secretInternalSegmentCancel() {
    queuePathEvent(PathEvent.CANCELED);
    synchronized (pathPlanLock) {
      getInProgress().ifPresent(AbstractNodeCostSearch::cancel);
      activePlanningStart = null;
      if (current != null) {
        current = null;
        next = null;
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
      }
    }
  }

  @Override
  public void forceCancel() { // exposed on public api because :sob:
    cancelEverything();
    secretInternalSegmentCancel();
    synchronized (pathCalcLock) {
      inProgress = null;
      activePlanningStart = null;
    }
  }

  public CalculationContext secretInternalGetCalculationContext() {
    return context;
  }

  public Optional<Double> estimatedTicksToGoal() {
    BetterBlockPos currentPos = ctx.playerFeet();
    if (goal == null || currentPos == null || startPosition == null) {
      return Optional.empty();
    }
    if (goal.isInGoal(ctx.playerFeet())) {
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
    if (inProgress != null) {
      throw new IllegalStateException("Already doing it"); // should have been checked by caller
    }
    context = liveCalculationContext(context);
    this.context = context;
    if (!context.safeForThreadedUse) {
      throw new IllegalStateException("Improper context thread safety level");
    }
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
    AbstractNodeCostSearch pathfinder = createPathfinder(start, goal, previousPathForFavoring(start), context);
    if (!Objects.equals(pathfinder.getGoal(), goal)) { // will return the exact same object if simplification didn't happen
      logDebug("Simplifying " + goal.getClass() + " to GoalXZ due to distance");
    }
    pathfinder.setPublicationSink(result -> acceptIncumbent(pathfinder, result));
    activePlanningStart = new BetterBlockPos(start);
    inProgress = pathfinder;
    Baritone.getExecutor().execute(() -> {
      if (talkAboutIt) {
        logDebug("Starting to search for path from " + start + " to " + goal);
      }

      PathCalculationResult calcResult = pathfinder.calculate(primaryTimeout, failureTimeout);
      synchronized (pathPlanLock) {
        acceptCalculation(calcResult, start, talkAboutIt, true);
        synchronized (pathCalcLock) {
          if (inProgress == pathfinder) {
            inProgress = null;
            activePlanningStart = null;
          }
        }
      }
    });
  }

  private CalculationContext liveCalculationContext(CalculationContext base) {
    return base != null && base.getClass() != CalculationContext.class ? base : new CalculationContext(baritone, true);
  }

  private IPath previousPathForFavoring(BlockPos start) {
    if (current == null) {
      return null;
    }
    IPath path = current.getPath();
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
      acceptCalculation(result, pathfinder.getStart(), false, false);
    }
  }

  private void acceptCalculation(PathCalculationResult result, BlockPos requestedStart, boolean talkAboutIt, boolean finalResult) {
    if (!Thread.holdsLock(pathPlanLock)) {
      throw new IllegalStateException("Must hold pathPlanLock while accepting a path calculation");
    }
    Optional<PathExecutor> executor = result.getPath().map(p -> new PathExecutor(PathingBehavior.this, p));
    if (executor.isEmpty()) {
      acceptEmptyCalculation(result, requestedStart, finalResult);
      return;
    }
    PathExecutor candidate = executor.get();
    if (!finalResult && !incumbentIsExecutable(candidate.getPath())) {
      return;
    }
    CandidateDisposition disposition = acceptCandidate(candidate);
    if (disposition == CandidateDisposition.REJECTED) {
      if (finalResult) {
        logDebug("Discarding orphan path segment from " + requestedStart + " to " + candidate.getPath().getDest());
      }
      return;
    }
    if (talkAboutIt && disposition == CandidateDisposition.EXECUTING && current != null && current.getPath() != null) {
      if (goal.isInGoal(current.getPath().getDest())) {
        logDebug("Finished finding a path from " + requestedStart + " to " + goal + ". " + current.getPath().getNumNodesConsidered() + " nodes considered");
      } else {
        logDebug("Found path segment from " + requestedStart + " towards " + goal + ". " + current.getPath().getNumNodesConsidered() + " nodes considered");
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

  private CandidateDisposition acceptCandidate(PathExecutor candidate) {
    IPath path = candidate.getPath();
    if (current == null) {
      if (!anchorsCurrentExecution(candidate)) {
        return CandidateDisposition.REJECTED;
      }
      queuePathEvent(PathEvent.CALC_FINISHED_NOW_EXECUTING);
      current = candidate;
      resetEstimatedTicksToGoal(path.getSrc());
      return CandidateDisposition.EXECUTING;
    }
    if (tryReplaceCurrentSuffix(candidate)) {
      return CandidateDisposition.EXECUTING;
    }
    if (path.getSrc().equals(current.getPath().getDest())) {
      return queueFutureCandidate(candidate);
    }
    if (anchorsFutureExecution(candidate)) {
      return queueFutureCandidate(candidate);
    }
    return CandidateDisposition.REJECTED;
  }

  private boolean tryReplaceCurrentSuffix(PathExecutor candidate) {
    if (!Baritone.settings().splicePath.value) {
      return false;
    }
    Optional<PathExecutor> replacement = current.tryReplaceSuffix(candidate, current.getPosition() + SUFFIX_REPLAN_MIN_ANCHOR_ADVANCE);
    if (replacement.isEmpty()) {
      return false;
    }
    if (!suffixReplacementIsWorthwhile(candidate.getPath(), replacement.get().getPath())) {
      return false;
    }
    logDebug("Replacing current path suffix from " + candidate.getPath().getSrc() + " to " + candidate.getPath().getDest());
    current = replacement.get();
    next = null;
    queuePathEvent(PathEvent.SPLICING_ONTO_NEXT_EARLY);
    return true;
  }

  private boolean suffixReplacementIsWorthwhile(IPath candidate, IPath replacement) {
    if (goal.isInGoal(candidate.getDest()) || improvesBeyond(candidate, current.getPath())) {
      return true;
    }
    return replacement.ticksRemainingFrom(current.getPosition()) + SUFFIX_REPLACEMENT_COST_EPSILON < current.getPath().ticksRemainingFrom(current.getPosition());
  }

  private CandidateDisposition queueFutureCandidate(PathExecutor candidate) {
    if (!improvesBeyond(candidate.getPath(), current.getPath())) {
      return CandidateDisposition.REJECTED;
    }
    if (next != null && !improvesBeyond(candidate.getPath(), next.getPath())) {
      return CandidateDisposition.REJECTED;
    }
    next = candidate;
    queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_FINISHED);
    return CandidateDisposition.QUEUED;
  }

  private boolean incumbentIsExecutable(IPath path) {
    return goal.isInGoal(path.getDest()) || path.length() >= Baritone.settings().pathingMinIncumbentLength.value;
  }

  private boolean anchorsCurrentExecution(PathExecutor path) {
    return path.containsPathPosition(ctx.playerFeet()) || path.containsPathPosition(expectedSegmentStart);
  }

  private boolean anchorsFutureExecution(PathExecutor path) {
    if (anchorsCurrentExecution(path)) {
      return true;
    }
    return current != null && path.containsPathPosition(current.getPath().getDest());
  }

  private boolean improvesBeyond(IPath candidate, IPath incumbent) {
    return goal.isInGoal(candidate.getDest()) || heuristic(candidate.getDest()) + Baritone.settings().pathingIncumbentHeuristicMargin.value < heuristic(incumbent.getDest());
  }

  private double heuristic(BetterBlockPos pos) {
    return goal.heuristic(pos.x, pos.y, pos.z);
  }

  private enum CandidateDisposition {
    EXECUTING, QUEUED, REJECTED
  }

  private AbstractNodeCostSearch createPathfinder(BlockPos start, Goal goal, IPath previous, CalculationContext context) {
    Goal transformed = goal;
    if (Baritone.settings().simplifyUnloadedYCoord.value && goal instanceof IGoalRenderPos) {
      BlockPos pos = ((IGoalRenderPos) goal).getGoalPos();
      if (!context.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ())) {
        transformed = new GoalXZ(pos.getX(), pos.getZ());
      }
    }
    Favoring favoring = new Favoring(context.getBaritone().getPlayerContext(), previous, context);
    BetterBlockPos feet = ctx.playerFeet();
    var realStart = new BetterBlockPos(start);
    var sub = feet.subtract(realStart);
    if (feet.getY() == realStart.getY() && Math.abs(sub.getX()) <= 1 && Math.abs(sub.getZ()) <= 1) {
      realStart = feet;
    }
    return new AStarPathFinder(realStart, start.getX(), start.getY(), start.getZ(), transformed, favoring, context);

  }

  @Override
  public void onRenderPass(RenderEvent event) {
    PathRenderer.render(event, this);
  }
}
