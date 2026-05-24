package baritone.pathing.calc;

import baritone.api.BaritoneAPI;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.SettingsUtil;
import baritone.pathing.calc.openset.BinaryHeapOpenSet;
import baritone.pathing.movement.BlockOffset;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.DestinationSpec;
import baritone.pathing.movement.EdgeEvalScratch;
import baritone.pathing.movement.EdgeEvalStatus;
import baritone.pathing.movement.LegacyMovesPrimitive;
import baritone.pathing.movement.MovementCatalog;
import baritone.pathing.movement.MovementPrimitive;
import baritone.pathing.movement.Moves;
import baritone.pathing.movement.NodeTerrainFacts;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.Favoring;
import java.util.List;
import java.util.Optional;

/**
 * The actual A* pathfinding
 *
 * @author leijurv
 */
public final class AStarPathFinder extends AbstractNodeCostSearch {
  private final Favoring favoring;
  private final CalculationContext calcContext;
  private final double oracleUpperBoundTicks;
  private String lastStopReason = "not_started";
  private int lastMovementsConsidered;
  private int lastEmptyChunkFetches;
  private int lastNodeMapSize;

  public AStarPathFinder(BetterBlockPos realStart, int startX, int startY, int startZ, Goal goal, Favoring favoring, CalculationContext context, PathingIncumbentPolicy incumbentPolicy) {
    this(realStart, startX, startY, startZ, goal, favoring, context, incumbentPolicy, Double.POSITIVE_INFINITY);
  }

  public AStarPathFinder(BetterBlockPos realStart, int startX, int startY, int startZ, Goal goal, Favoring favoring, CalculationContext context, PathingIncumbentPolicy incumbentPolicy,
    double oracleUpperBoundTicks) {
    super(realStart, startX, startY, startZ, goal, context, incumbentPolicy);
    this.favoring = favoring;
    this.calcContext = context;
    this.oracleUpperBoundTicks = oracleUpperBoundTicks;
  }

  @Override
  protected Optional<IPath> calculate0(long primaryTimeout, long failureTimeout) {
    return BaritoneAPI.getSettings().pathingEventCursorAStar.value ? calculateEventCursor0(primaryTimeout, failureTimeout) : calculateEager0(primaryTimeout, failureTimeout);
  }

  private Optional<IPath> calculateEager0(long primaryTimeout, long failureTimeout) {
    recordStop("running", 0, 0);
    PathingProfiler.Active activeProfile = profile;
    long searchLoopStarted = activeProfile == null ? 0 : System.nanoTime();
    long heapNanos = 0;
    long nodeMapNanos = 0;
    int minY = calcContext.world.dimensionType().minY();
    int maxYExclusive = minY + calcContext.world.dimensionType().height();
    long nodeMapStart = activeProfile == null ? 0 : System.nanoTime();
    startNode = getNodeAtPosition(startX, startY, startZ, BlockKey.pack(startX, startY, startZ));
    if (activeProfile != null) {
      nodeMapNanos += System.nanoTime() - nodeMapStart;
    }
    startNode.cost = 0;
    startNode.combinedCost = startNode.estimatedCostToGoal;
    BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
    long heapStart = activeProfile == null ? 0 : System.nanoTime();
    openSet.insert(startNode);
    if (activeProfile != null) {
      heapNanos += System.nanoTime() - heapStart;
    }
    double[] bestHeuristicSoFar = new double[COEFFICIENTS.length]; // keep track of the best node by the metric of (estimatedCostToGoal + cost / COEFFICIENTS[i])
    for (int i = 0; i < bestHeuristicSoFar.length; i++) {
      bestHeuristicSoFar[i] = startNode.estimatedCostToGoal;
      bestSoFar[i] = startNode;
    }
    EdgeEvalScratch eval = new EdgeEvalScratch();
    NodeTerrainFacts terrainFacts = new NodeTerrainFacts();
    eval.nodeFacts = terrainFacts;
    BetterWorldBorder worldBorder = calcContext.worldBorder;
    long startTime = System.currentTimeMillis();
    long primaryTimeoutTime = startTime + primaryTimeout;
    long failureTimeoutTime = startTime + failureTimeout;
    long incumbentInterval = hasPublicationSink() ? incumbentPolicy().intervalMS() : 0L;
    long nextIncumbentPublishTime = incumbentInterval == 0 ? Long.MAX_VALUE : startTime + incumbentInterval;
    boolean failing = true;
    int numNodes = 0;
    int numMovementsConsidered = 0;
    int numEmptyChunk = 0;
    boolean isFavoring = !favoring.isEmpty();
    int timeCheckInterval = 1 << 6;
    int pathingMaxChunkBorderFetch = BaritoneAPI.getSettings().pathingMaxChunkBorderFetch.value; // grab all settings beforehand so that changing settings during pathing doesn't cause a crash or unpredictable behavior
    double minimumImprovement = MIN_IMPROVEMENT;
    MovementCatalog catalog = calcContext.movementCatalog;
    MovementPrimitive[] allMoves = catalog.primitives();
    FrontierValueObjective frontier = goal instanceof FrontierValueObjective value ? value : null;
    LocalExitObjective localExit = frontier == null && goal instanceof LocalExitObjective exit ? exit : null;
    boolean scoredExitSearch = frontier != null || localExit != null;
    PathNode bestExit = null;
    double bestExitScore = Double.POSITIVE_INFINITY;
    boolean nodeCapReached = false;
    boolean upperBoundExhausted = false;
    search : while (!openSet.isEmpty() && numEmptyChunk < pathingMaxChunkBorderFetch && !cancelRequested && !nodeMapFull()) {
      if (scoredExitSearch && bestExit != null && openSet.lowestCombinedCost() + minimumImprovement >= bestExitScore) {
        logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered; proved best local exit");
        recordStop("best_exit", numMovementsConsidered, numEmptyChunk);
        if (activeProfile != null) {
          activeProfile.finishSearchLoop(numNodes, numMovementsConsidered, numEmptyChunk, nodeMapSize(), "best_exit", System.nanoTime() - searchLoopStarted, heapNanos, nodeMapNanos);
        }
        return Optional.of(new Path(realStart, startNode, bestExit, numNodes, goal, calcContext));
      }
      if ((numNodes & (timeCheckInterval - 1)) == 0) { // only call this once every 64 nodes (about half a millisecond)
        long now = System.currentTimeMillis(); // since nanoTime is slow on windows (takes many microseconds)
        if (now - failureTimeoutTime >= 0 || (!failing && now - primaryTimeoutTime >= 0)) {
          break;
        }
        if (now - nextIncumbentPublishTime >= 0) {
          if (scoredExitSearch && bestExit != null) {
            publishPathToNode(bestExit, numNodes);
          } else {
            publishBestSoFar(numNodes);
          }
          nextIncumbentPublishTime = now + incumbentInterval;
        }
      }
      heapStart = activeProfile == null ? 0 : System.nanoTime();
      PathNode currentNode = openSet.removeLowest();
      if (activeProfile != null) {
        heapNanos += System.nanoTime() - heapStart;
      }
      if (currentNode.combinedCost - oracleUpperBoundTicks > minimumImprovement) {
        upperBoundExhausted = true;
        break;
      }
      mostRecentConsidered = currentNode;
      numNodes++;
      if (goal.isInGoal(currentNode.x, currentNode.y, currentNode.z)) {
        if (scoredExitSearch) {
          double score =
            currentNode.cost + (frontier == null ? localExit.terminalExitValue(currentNode.x, currentNode.y, currentNode.z) : frontier.terminalExitValue(currentNode.x, currentNode.y, currentNode.z));
          if (bestExitScore - score > minimumImprovement) {
            bestExit = currentNode;
            bestExitScore = score;
          }
          continue;
        }
        logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered");
        recordStop("goal", numMovementsConsidered, numEmptyChunk);
        if (activeProfile != null) {
          activeProfile.finishSearchLoop(numNodes, numMovementsConsidered, numEmptyChunk, nodeMapSize(), "goal", System.nanoTime() - searchLoopStarted, heapNanos, nodeMapNanos);
        }
        return Optional.of(new Path(realStart, startNode, currentNode, numNodes, goal, calcContext));
      }
      if (localExit != null && currentNode.previous != null && localExit.isExactLocalExit(currentNode.x, currentNode.y, currentNode.z)) {
        double score = currentNode.cost + localExit.localExitValue(currentNode.x, currentNode.y, currentNode.z);
        if (Double.isFinite(score) && bestExitScore - score > minimumImprovement) {
          bestExit = currentNode;
          bestExitScore = score;
        }
        continue;
      }
      terrainFacts.load(calcContext, currentNode.x, currentNode.y, currentNode.z);
      double bestBoundaryValue = Double.POSITIVE_INFINITY;
      for (int primitiveIndex = 0; primitiveIndex < allMoves.length; primitiveIndex++) {
        MovementPrimitive primitive = allMoves[primitiveIndex];
        DestinationSpec spec = primitive.destinationSpec();
        BlockOffset probe = spec.precheckOffset();
        int newX = currentNode.x + probe.dx();
        int newY = currentNode.y + probe.dy();
        int newZ = currentNode.z + probe.dz();
        if ((newX >> 4 != currentNode.x >> 4 || newZ >> 4 != currentNode.z >> 4) && !calcContext.hasPathingData(newX, newZ)) {
          // only need to check if the destination is a live chunk if it's in a different chunk than the start of the movement
          if (frontier != null) {
            bestBoundaryValue = Math.min(bestBoundaryValue, frontier.frontierExitValue(currentNode.x, currentNode.y, currentNode.z, newX, newY, newZ));
          } else if (localExit != null) {
            bestBoundaryValue = Math.min(bestBoundaryValue, localExit.localExitValue(currentNode.x, currentNode.y, currentNode.z));
          }
          if (!scoredExitSearch && !spec.dynamicXZ()) { // only increment the legacy segment cutoff if this is not a scored-boundary search
            numEmptyChunk++;
          }
          continue;
        }
        if (!spec.dynamicXZ() && !worldBorder.entirelyContains(newX, newZ)) {
          continue;
        }
        if (newY < minY || newY >= maxYExclusive) {
          continue;
        }
        long blockKey = 0;
        boolean hasStaticBlockKey = false;
        boolean staticIncumbentKnown = false;
        PathNode staticIncumbent = null;
        if (!spec.dynamicXZ() && !spec.dynamicY()) {
          blockKey = BlockKey.pack(newX, newY, newZ);
          hasStaticBlockKey = true;
          double minimumCost = primitive.minimumCost(calcContext);
          if (minimumCost > 0) {
            nodeMapStart = activeProfile == null ? 0 : System.nanoTime();
            staticIncumbent = peekNodeAtPosition(blockKey);
            staticIncumbentKnown = true;
            if (activeProfile != null) {
              nodeMapNanos += System.nanoTime() - nodeMapStart;
            }
            if (staticIncumbent != null) {
              long favoringHash = BlockKey.pack(newX, newY, newZ);
              double lowerBoundActionCost = minimumCost * (isFavoring ? favoring.calculate(favoringHash) : 1);
              if (staticIncumbent.cost - (currentNode.cost + lowerBoundActionCost) <= minimumImprovement) {
                if (activeProfile != null && primitive instanceof LegacyMovesPrimitive legacy) {
                  activeProfile.recordLowerBoundPrune(legacy.move());
                } else if (activeProfile != null) {
                  activeProfile.recordLowerBoundPrune(primitive.debugName());
                }
                continue;
              }
            }
          }
        }
        eval.blocked();
        if (activeProfile == null) {
          primitive.evaluate(calcContext, currentNode.x, currentNode.y, currentNode.z, eval);
        } else {
          long moveStart = System.nanoTime();
          primitive.evaluate(calcContext, currentNode.x, currentNode.y, currentNode.z, eval);
          if (primitive instanceof LegacyMovesPrimitive legacy) {
            activeProfile.recordMove(legacy.move(), System.nanoTime() - moveStart, eval.status == EdgeEvalStatus.REACHABLE);
          } else {
            activeProfile.recordMove(primitive.debugName(), System.nanoTime() - moveStart, eval.status == EdgeEvalStatus.REACHABLE);
          }
        }
        numMovementsConsidered++;
        if (eval.status != EdgeEvalStatus.REACHABLE) {
          continue;
        }
        double actionCost = eval.cost;
        if (actionCost <= 0 || Double.isNaN(actionCost)) {
          throw new IllegalStateException(String.format("%s from %s %s %s calculated implausible cost %s", primitive.debugName(), SettingsUtil.maybeCensor(currentNode.x),
            SettingsUtil.maybeCensor(currentNode.y), SettingsUtil.maybeCensor(currentNode.z), actionCost));
        }
        // check destination after verifying it's not COST_INF -- some movements return COST_INF without adjusting the destination
        if (spec.dynamicXZ() && !worldBorder.entirelyContains(eval.x, eval.z)) { // see issue #218
          continue;
        }
        if (!spec.dynamicXZ() && (eval.x != newX || eval.z != newZ)) {
          throw new IllegalStateException(
            String.format("%s from %s %s %s ended at x z %s %s instead of %s %s", primitive.debugName(), SettingsUtil.maybeCensor(currentNode.x), SettingsUtil.maybeCensor(currentNode.y),
              SettingsUtil.maybeCensor(currentNode.z), SettingsUtil.maybeCensor(eval.x), SettingsUtil.maybeCensor(eval.z), SettingsUtil.maybeCensor(newX), SettingsUtil.maybeCensor(newZ)));
        }
        if (!spec.dynamicY() && eval.y != newY) {
          throw new IllegalStateException(String.format("%s from %s %s %s ended at y %s instead of %s", primitive.debugName(), SettingsUtil.maybeCensor(currentNode.x),
            SettingsUtil.maybeCensor(currentNode.y), SettingsUtil.maybeCensor(currentNode.z), SettingsUtil.maybeCensor(eval.y), SettingsUtil.maybeCensor(newY)));
        }
        long favoringHash = BlockKey.pack(eval.x, eval.y, eval.z);
        if (isFavoring) {
          // see issue #18
          actionCost *= favoring.calculate(favoringHash);
        }
        if (!hasStaticBlockKey) {
          blockKey = BlockKey.pack(eval.x, eval.y, eval.z);
        }
        PathNode neighbor;
        if (hasStaticBlockKey && staticIncumbentKnown) {
          if (staticIncumbent == null) {
            if (nodeMapFull()) {
              nodeCapReached = true;
              break search;
            }
            nodeMapStart = activeProfile == null ? 0 : System.nanoTime();
            neighbor = createNodeAtKnownAbsentPosition(eval.x, eval.y, eval.z, blockKey);
            if (activeProfile != null) {
              nodeMapNanos += System.nanoTime() - nodeMapStart;
            }
          } else {
            neighbor = staticIncumbent;
          }
        } else {
          nodeMapStart = activeProfile == null ? 0 : System.nanoTime();
          neighbor = peekNodeAtPosition(blockKey);
          if (neighbor == null) {
            if (nodeMapFull()) {
              nodeCapReached = true;
              if (activeProfile != null) {
                nodeMapNanos += System.nanoTime() - nodeMapStart;
              }
              break search;
            }
            neighbor = createNodeAtKnownAbsentPosition(eval.x, eval.y, eval.z, blockKey);
          }
          if (activeProfile != null) {
            nodeMapNanos += System.nanoTime() - nodeMapStart;
          }
        }
        double tentativeCost = currentNode.cost + actionCost;
        if (neighbor.cost - tentativeCost > minimumImprovement) {
          double combinedCost = tentativeCost + neighbor.estimatedCostToGoal;
          if (combinedCost - oracleUpperBoundTicks > minimumImprovement) {
            continue;
          }
          neighbor.previous = currentNode;
          neighbor.previousPrimitiveIndex = (short) primitiveIndex;
          neighbor.previousEdgePayload = eval.payload;
          neighbor.previousEdgeCost = actionCost;
          neighbor.cost = tentativeCost;
          neighbor.combinedCost = combinedCost;
          if (neighbor.isOpen()) {
            heapStart = activeProfile == null ? 0 : System.nanoTime();
            openSet.update(neighbor);
            if (activeProfile != null) {
              heapNanos += System.nanoTime() - heapStart;
            }
          } else {
            heapStart = activeProfile == null ? 0 : System.nanoTime();
            openSet.insert(neighbor); // dont double count, dont insert into open set if it's already there
            if (activeProfile != null) {
              heapNanos += System.nanoTime() - heapStart;
            }
          }
          for (int i = 0; i < COEFFICIENTS.length; i++) {
            double heuristic = neighbor.estimatedCostToGoal + neighbor.cost / COEFFICIENTS[i];
            if (bestHeuristicSoFar[i] - heuristic > minimumImprovement) {
              bestHeuristicSoFar[i] = heuristic;
              bestSoFar[i] = neighbor;
              if (failing && getDistFromStartSq(neighbor) > MIN_DIST_PATH * MIN_DIST_PATH) {
                failing = false;
              }
            }
          }
        }
      }
      if (Double.isFinite(bestBoundaryValue)) {
        double score = currentNode.cost + bestBoundaryValue;
        if (Double.isFinite(score) && bestExitScore - score > minimumImprovement) {
          bestExit = currentNode;
          bestExitScore = score;
        }
      }
    }
    if (activeProfile != null) {
      activeProfile.finishSearchLoop(numNodes, numMovementsConsidered, numEmptyChunk, nodeMapSize(),
        terminalReason(cancelRequested, nodeCapReached, upperBoundExhausted, openSet.isEmpty(), numEmptyChunk, pathingMaxChunkBorderFetch), System.nanoTime() - searchLoopStarted, heapNanos,
        nodeMapNanos);
    }
    recordStop(terminalReason(cancelRequested, nodeCapReached, upperBoundExhausted, openSet.isEmpty(), numEmptyChunk, pathingMaxChunkBorderFetch), numMovementsConsidered, numEmptyChunk);
    if (cancelRequested) {
      return Optional.empty();
    }
    if (scoredExitSearch && bestExit != null) {
      logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered; using best local exit without proof");
      recordStop("best_exit_unproven", numMovementsConsidered, numEmptyChunk);
      return Optional.of(new Path(realStart, startNode, bestExit, numNodes, goal, calcContext));
    }
    logDebug(numMovementsConsidered + " movements considered");
    logDebug("Open set size: " + openSet.size());
    logDebug("PathNode map size: " + mapSize());
    logDebug((int) (numNodes * 1.0 / ((System.currentTimeMillis() - startTime) / 1000F)) + " nodes per second");
    Optional<IPath> result = bestSoFar(true, numNodes);
    if (result.isPresent()) {
      logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered");
    }
    return result;
  }

  private Optional<IPath> calculateEventCursor0(long primaryTimeout, long failureTimeout) {
    recordStop("running_event_cursor", 0, 0);
    PathingProfiler.Active activeProfile = profile;
    long searchLoopStarted = activeProfile == null ? 0 : System.nanoTime();
    long heapNanos = 0;
    long nodeMapNanos = 0;
    int minY = calcContext.world.dimensionType().minY();
    int maxYExclusive = minY + calcContext.world.dimensionType().height();
    long nodeMapStart = activeProfile == null ? 0 : System.nanoTime();
    startNode = getNodeAtPosition(startX, startY, startZ, BlockKey.pack(startX, startY, startZ));
    if (activeProfile != null) {
      nodeMapNanos += System.nanoTime() - nodeMapStart;
    }
    startNode.cost = 0;
    startNode.combinedCost = startNode.estimatedCostToGoal;
    double[] bestHeuristicSoFar = new double[COEFFICIENTS.length];
    for (int i = 0; i < bestHeuristicSoFar.length; i++) {
      bestHeuristicSoFar[i] = startNode.estimatedCostToGoal;
      bestSoFar[i] = startNode;
    }

    EdgeEvalScratch eval = new EdgeEvalScratch();
    NodeTerrainFacts terrainFacts = new NodeTerrainFacts();
    eval.nodeFacts = terrainFacts;
    BetterWorldBorder worldBorder = calcContext.worldBorder;
    long startTime = System.currentTimeMillis();
    long primaryTimeoutTime = startTime + primaryTimeout;
    long failureTimeoutTime = startTime + failureTimeout;
    long incumbentInterval = hasPublicationSink() ? incumbentPolicy().intervalMS() : 0L;
    long nextIncumbentPublishTime = incumbentInterval == 0 ? Long.MAX_VALUE : startTime + incumbentInterval;
    boolean failing = true;
    int numEvents = 0;
    int numMovementsConsidered = 0;
    int numEmptyChunk = 0;
    boolean isFavoring = !favoring.isEmpty();
    double favoringFloor = isFavoring ? Math.max(0D, favoring.minimumCoefficient()) : 1D;
    int timeCheckInterval = 1 << 6;
    int pathingMaxChunkBorderFetch = BaritoneAPI.getSettings().pathingMaxChunkBorderFetch.value;
    double minimumImprovement = MIN_IMPROVEMENT;
    MovementCatalog catalog = calcContext.movementCatalog;
    MovementPrimitive[] allMoves = catalog.primitives();
    FrontierValueObjective frontier = goal instanceof FrontierValueObjective value ? value : null;
    LocalExitObjective localExit = frontier == null && goal instanceof LocalExitObjective exit ? exit : null;
    boolean scoredExitSearch = frontier != null || localExit != null;
    PathNode bestExit = null;
    double bestExitScore = Double.POSITIVE_INFINITY;
    if (goal.isInGoal(startNode.x, startNode.y, startNode.z)) {
      bestExit = startNode;
      bestExitScore = scoredExitSearch ? frontier == null ? localExit.terminalExitValue(startNode.x, startNode.y, startNode.z) : frontier.terminalExitValue(startNode.x, startNode.y, startNode.z) : 0D;
    }
    boolean nodeCapReached = false;
    boolean upperBoundExhausted = false;
    ExpansionEventHeap eventHeap = new ExpansionEventHeap();
    ExpansionEventHeap.Event event = new ExpansionEventHeap.Event();
    resetAndSchedule(startNode, eventHeap, allMoves, scoredExitSearch, frontier, localExit, worldBorder, minY, maxYExclusive, isFavoring, favoringFloor);

    search : while (!eventHeap.isEmpty() && numEmptyChunk < pathingMaxChunkBorderFetch && !cancelRequested && !nodeMapFull()) {
      if (bestExit != null && eventHeap.lowestKey() + minimumImprovement >= bestExitScore) {
        logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered; proved event-cursor incumbent");
        recordStop(scoredExitSearch ? "best_exit" : "goal", numMovementsConsidered, numEmptyChunk);
        if (activeProfile != null) {
          activeProfile.finishSearchLoop(numEvents, numMovementsConsidered, numEmptyChunk, nodeMapSize(), scoredExitSearch ? "best_exit" : "goal", System.nanoTime() - searchLoopStarted, heapNanos,
            nodeMapNanos);
        }
        return Optional.of(new Path(realStart, startNode, bestExit, numEvents, goal, calcContext));
      }
      if ((numEvents & (timeCheckInterval - 1)) == 0) {
        long now = System.currentTimeMillis();
        if (now - failureTimeoutTime >= 0 || (!failing && now - primaryTimeoutTime >= 0)) {
          break;
        }
        if (now - nextIncumbentPublishTime >= 0) {
          if (bestExit != null) {
            publishPathToNode(bestExit, numEvents);
          } else {
            publishBestSoFar(numEvents);
          }
          nextIncumbentPublishTime = now + incumbentInterval;
        }
      }
      long heapStart = activeProfile == null ? 0 : System.nanoTime();
      eventHeap.removeLowest(event);
      if (activeProfile != null) {
        heapNanos += System.nanoTime() - heapStart;
      }
      if (event.stale()) {
        continue;
      }
      if (event.key - oracleUpperBoundTicks > minimumImprovement) {
        upperBoundExhausted = true;
        break;
      }
      PathNode currentNode = event.source;
      mostRecentConsidered = currentNode;
      numEvents++;
      terrainFacts.load(calcContext, currentNode.x, currentNode.y, currentNode.z);
      MovementPrimitive primitive = allMoves[event.primitiveIndex];
      DestinationSpec spec = primitive.destinationSpec();
      BlockOffset probe = spec.precheckOffset();
      int newX = currentNode.x + probe.dx();
      int newY = currentNode.y + probe.dy();
      int newZ = currentNode.z + probe.dz();
      currentNode.consumeExpansion(event.primitiveIndex);

      if ((newX >> 4 != currentNode.x >> 4 || newZ >> 4 != currentNode.z >> 4) && !calcContext.hasPathingData(newX, newZ)) {
        double boundaryValue = boundaryValue(scoredExitSearch, frontier, localExit, currentNode, newX, newY, newZ);
        if (Double.isFinite(boundaryValue)) {
          double score = currentNode.cost + boundaryValue;
          if (Double.isFinite(score) && bestExitScore - score > minimumImprovement) {
            bestExit = currentNode;
            bestExitScore = score;
          }
        }
        if (!scoredExitSearch && !spec.dynamicXZ()) {
          numEmptyChunk++;
        }
      } else if ((!spec.dynamicXZ() && !worldBorder.entirelyContains(newX, newZ)) || newY < minY || newY >= maxYExclusive) {
        // statically impossible; consumed above
      } else {
        long blockKey = 0;
        boolean hasStaticBlockKey = false;
        boolean staticIncumbentKnown = false;
        PathNode staticIncumbent = null;
        if (!spec.dynamicXZ() && !spec.dynamicY()) {
          blockKey = BlockKey.pack(newX, newY, newZ);
          hasStaticBlockKey = true;
          nodeMapStart = activeProfile == null ? 0 : System.nanoTime();
          staticIncumbent = peekNodeAtPosition(blockKey);
          staticIncumbentKnown = true;
          if (activeProfile != null) {
            nodeMapNanos += System.nanoTime() - nodeMapStart;
          }
          if (staticIncumbent != null) {
            double exactFavoring = isFavoring ? favoring.calculate(blockKey) : 1D;
            double lowerBoundActionCost = primitive.minimumCost(calcContext) * exactFavoring;
            if (staticIncumbent.cost - (currentNode.cost + lowerBoundActionCost) <= minimumImprovement) {
              if (activeProfile != null && primitive instanceof LegacyMovesPrimitive legacy) {
                activeProfile.recordLowerBoundPrune(legacy.move());
              } else if (activeProfile != null) {
                activeProfile.recordLowerBoundPrune(primitive.debugName());
              }
              scheduleNextEvent(currentNode, eventHeap, allMoves, scoredExitSearch, frontier, localExit, worldBorder, minY, maxYExclusive, isFavoring, favoringFloor);
              continue;
            }
          }
        }
        eval.blocked();
        if (activeProfile == null) {
          primitive.evaluate(calcContext, currentNode.x, currentNode.y, currentNode.z, eval);
        } else {
          long moveStart = System.nanoTime();
          primitive.evaluate(calcContext, currentNode.x, currentNode.y, currentNode.z, eval);
          if (primitive instanceof LegacyMovesPrimitive legacy) {
            activeProfile.recordMove(legacy.move(), System.nanoTime() - moveStart, eval.status == EdgeEvalStatus.REACHABLE);
          } else {
            activeProfile.recordMove(primitive.debugName(), System.nanoTime() - moveStart, eval.status == EdgeEvalStatus.REACHABLE);
          }
        }
        numMovementsConsidered++;
        if (eval.status == EdgeEvalStatus.REACHABLE) {
          double actionCost = eval.cost;
          if (actionCost <= 0 || Double.isNaN(actionCost)) {
            throw new IllegalStateException(String.format("%s from %s %s %s calculated implausible cost %s", primitive.debugName(), SettingsUtil.maybeCensor(currentNode.x),
              SettingsUtil.maybeCensor(currentNode.y), SettingsUtil.maybeCensor(currentNode.z), actionCost));
          }
          if (spec.dynamicXZ() && !worldBorder.entirelyContains(eval.x, eval.z)) {
            scheduleNextEvent(currentNode, eventHeap, allMoves, scoredExitSearch, frontier, localExit, worldBorder, minY, maxYExclusive, isFavoring, favoringFloor);
            continue;
          }
          if (!spec.dynamicXZ() && (eval.x != newX || eval.z != newZ)) {
            throw new IllegalStateException(
              String.format("%s from %s %s %s ended at x z %s %s instead of %s %s", primitive.debugName(), SettingsUtil.maybeCensor(currentNode.x), SettingsUtil.maybeCensor(currentNode.y),
                SettingsUtil.maybeCensor(currentNode.z), SettingsUtil.maybeCensor(eval.x), SettingsUtil.maybeCensor(eval.z), SettingsUtil.maybeCensor(newX), SettingsUtil.maybeCensor(newZ)));
          }
          if (!spec.dynamicY() && eval.y != newY) {
            throw new IllegalStateException(String.format("%s from %s %s %s ended at y %s instead of %s", primitive.debugName(), SettingsUtil.maybeCensor(currentNode.x),
              SettingsUtil.maybeCensor(currentNode.y), SettingsUtil.maybeCensor(currentNode.z), SettingsUtil.maybeCensor(eval.y), SettingsUtil.maybeCensor(newY)));
          }
          long favoringHash = BlockKey.pack(eval.x, eval.y, eval.z);
          if (isFavoring) {
            actionCost *= favoring.calculate(favoringHash);
          }
          if (event.actionLowerBound - actionCost > minimumImprovement) {
            throw new IllegalStateException(String.format("%s from %s %s %s violated event lower bound: declared %s actual %s", primitive.debugName(), SettingsUtil.maybeCensor(currentNode.x),
              SettingsUtil.maybeCensor(currentNode.y), SettingsUtil.maybeCensor(currentNode.z), event.actionLowerBound, actionCost));
          }
          if (!hasStaticBlockKey) {
            blockKey = BlockKey.pack(eval.x, eval.y, eval.z);
          }
          PathNode neighbor;
          if (hasStaticBlockKey && staticIncumbentKnown) {
            if (staticIncumbent == null) {
              if (nodeMapFull()) {
                nodeCapReached = true;
                break search;
              }
              nodeMapStart = activeProfile == null ? 0 : System.nanoTime();
              neighbor = createNodeAtKnownAbsentPosition(eval.x, eval.y, eval.z, blockKey);
              if (activeProfile != null) {
                nodeMapNanos += System.nanoTime() - nodeMapStart;
              }
            } else {
              neighbor = staticIncumbent;
            }
          } else {
            nodeMapStart = activeProfile == null ? 0 : System.nanoTime();
            neighbor = peekNodeAtPosition(blockKey);
            if (neighbor == null) {
              if (nodeMapFull()) {
                nodeCapReached = true;
                if (activeProfile != null) {
                  nodeMapNanos += System.nanoTime() - nodeMapStart;
                }
                break search;
              }
              neighbor = createNodeAtKnownAbsentPosition(eval.x, eval.y, eval.z, blockKey);
            }
            if (activeProfile != null) {
              nodeMapNanos += System.nanoTime() - nodeMapStart;
            }
          }
          double tentativeCost = currentNode.cost + actionCost;
          if (neighbor.cost - tentativeCost > minimumImprovement) {
            double combinedCost = tentativeCost + neighbor.estimatedCostToGoal;
            neighbor.previous = currentNode;
            neighbor.previousPrimitiveIndex = (short) event.primitiveIndex;
            neighbor.previousEdgePayload = eval.payload;
            neighbor.previousEdgeCost = actionCost;
            neighbor.cost = tentativeCost;
            neighbor.combinedCost = combinedCost;
            resetAndSchedule(neighbor, eventHeap, allMoves, scoredExitSearch, frontier, localExit, worldBorder, minY, maxYExclusive, isFavoring, favoringFloor);
            if (goal.isInGoal(neighbor.x, neighbor.y, neighbor.z)) {
              double score =
                scoredExitSearch ? frontier == null ? localExit.terminalExitValue(neighbor.x, neighbor.y, neighbor.z) : frontier.terminalExitValue(neighbor.x, neighbor.y, neighbor.z) : neighbor.cost;
              if (Double.isFinite(score) && bestExitScore - score > minimumImprovement) {
                bestExit = neighbor;
                bestExitScore = scoredExitSearch ? neighbor.cost + score : score;
              }
            }
            if (localExit != null && neighbor.previous != null && localExit.isExactLocalExit(neighbor.x, neighbor.y, neighbor.z)) {
              double score = neighbor.cost + localExit.localExitValue(neighbor.x, neighbor.y, neighbor.z);
              if (Double.isFinite(score) && bestExitScore - score > minimumImprovement) {
                bestExit = neighbor;
                bestExitScore = score;
              }
            }
            for (int i = 0; i < COEFFICIENTS.length; i++) {
              double heuristic = neighbor.estimatedCostToGoal + neighbor.cost / COEFFICIENTS[i];
              if (bestHeuristicSoFar[i] - heuristic > minimumImprovement) {
                bestHeuristicSoFar[i] = heuristic;
                bestSoFar[i] = neighbor;
                if (failing && getDistFromStartSq(neighbor) > MIN_DIST_PATH * MIN_DIST_PATH) {
                  failing = false;
                }
              }
            }
          }
        }
      }
      if (currentNode.expansionGeneration == event.generation && currentNode.expansionSerial == event.serial) {
        scheduleNextEvent(currentNode, eventHeap, allMoves, scoredExitSearch, frontier, localExit, worldBorder, minY, maxYExclusive, isFavoring, favoringFloor);
      }
    }
    if (activeProfile != null) {
      activeProfile.finishSearchLoop(numEvents, numMovementsConsidered, numEmptyChunk, nodeMapSize(),
        terminalReason(cancelRequested, nodeCapReached, upperBoundExhausted, eventHeap.isEmpty(), numEmptyChunk, pathingMaxChunkBorderFetch), System.nanoTime() - searchLoopStarted, heapNanos,
        nodeMapNanos);
    }
    recordStop(terminalReason(cancelRequested, nodeCapReached, upperBoundExhausted, eventHeap.isEmpty(), numEmptyChunk, pathingMaxChunkBorderFetch), numMovementsConsidered, numEmptyChunk);
    if (cancelRequested) {
      return Optional.empty();
    }
    if (bestExit != null) {
      logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered; using event-cursor incumbent without proof");
      recordStop(scoredExitSearch ? "best_exit_unproven" : "goal_unproven", numMovementsConsidered, numEmptyChunk);
      return Optional.of(new Path(realStart, startNode, bestExit, numEvents, goal, calcContext));
    }
    logDebug(numMovementsConsidered + " movements considered");
    logDebug("Event heap size: " + eventHeap.size());
    logDebug("PathNode map size: " + mapSize());
    logDebug((int) (numEvents * 1.0 / ((System.currentTimeMillis() - startTime) / 1000F)) + " events per second");
    Optional<IPath> result = bestSoFar(true, numEvents);
    if (result.isPresent()) {
      logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered");
    }
    return result;
  }

  private void resetAndSchedule(PathNode node, ExpansionEventHeap heap, MovementPrimitive[] primitives, boolean scoredExitSearch, FrontierValueObjective frontier, LocalExitObjective localExit,
    BetterWorldBorder worldBorder, int minY, int maxYExclusive, boolean isFavoring, double favoringFloor) {
    node.resetExpansion(primitives.length);
    scheduleNextEvent(node, heap, primitives, scoredExitSearch, frontier, localExit, worldBorder, minY, maxYExclusive, isFavoring, favoringFloor);
  }

  private void scheduleNextEvent(PathNode node, ExpansionEventHeap heap, MovementPrimitive[] primitives, boolean scoredExitSearch, FrontierValueObjective frontier, LocalExitObjective localExit,
    BetterWorldBorder worldBorder, int minY, int maxYExclusive, boolean isFavoring, double favoringFloor) {
    int bestPrimitive = -1;
    double bestKey = Double.POSITIVE_INFINITY;
    double bestLower = 0;
    int bestTie = 0;
    for (int i = 0; i < primitives.length; i++) {
      if (node.expansionConsumed(i)) {
        continue;
      }
      MovementPrimitive primitive = primitives[i];
      DestinationSpec spec = primitive.destinationSpec();
      BlockOffset probe = spec.precheckOffset();
      int newX = node.x + probe.dx();
      int newY = node.y + probe.dy();
      int newZ = node.z + probe.dz();
      if ((!spec.dynamicXZ() && !worldBorder.entirelyContains(newX, newZ)) || newY < minY || newY >= maxYExclusive) {
        node.consumeExpansion(i);
        continue;
      }
      double lower = Math.max(0D, primitive.minimumCost(calcContext));
      double key;
      if ((newX >> 4 != node.x >> 4 || newZ >> 4 != node.z >> 4) && !calcContext.hasPathingData(newX, newZ)) {
        double boundaryValue = boundaryValue(scoredExitSearch, frontier, localExit, node, newX, newY, newZ);
        key = Double.isFinite(boundaryValue) ? node.cost + boundaryValue : node.cost + lower * favoringFloor;
      } else {
        double actionMultiplier = 1D;
        double heuristicMultiplier = 1D;
        if (isFavoring) {
          heuristicMultiplier = favoringFloor;
          if (!spec.dynamicXZ() && !spec.dynamicY()) {
            actionMultiplier = favoring.calculate(BlockKey.pack(newX, newY, newZ));
          } else {
            actionMultiplier = favoringFloor;
          }
        }
        lower *= actionMultiplier;
        double heuristic = destinationHeuristicLowerBound(primitive, spec, node, newX, newY, newZ, minY, maxYExclusive) * heuristicMultiplier;
        key = node.cost + lower + heuristic;
      }
      int tie = eventTie(i, primitive);
      if (key < bestKey || key == bestKey && tie < bestTie) {
        bestPrimitive = i;
        bestKey = key;
        bestLower = lower;
        bestTie = tie;
      }
    }
    if (bestPrimitive >= 0 && Double.isFinite(bestKey)) {
      heap.insert(node, bestPrimitive, bestLower, bestKey, bestTie);
    }
  }

  private double boundaryValue(boolean scoredExitSearch, FrontierValueObjective frontier, LocalExitObjective localExit, PathNode node, int newX, int newY, int newZ) {
    if (!scoredExitSearch) {
      return Double.POSITIVE_INFINITY;
    }
    return frontier != null ? frontier.frontierExitValue(node.x, node.y, node.z, newX, newY, newZ) : localExit.localExitValue(node.x, node.y, node.z);
  }

  private double destinationHeuristicLowerBound(MovementPrimitive primitive, DestinationSpec spec, PathNode node, int precheckX, int precheckY, int precheckZ, int minY, int maxYExclusive) {
    if (!spec.dynamicXZ() && !spec.dynamicY()) {
      return nonnegativeHeuristic(precheckX, precheckY, precheckZ);
    }
    if (!(primitive instanceof LegacyMovesPrimitive legacy)) {
      return 0D;
    }
    return switch (legacy.move()) {
      case DIAGONAL_NORTHEAST, DIAGONAL_NORTHWEST, DIAGONAL_SOUTHEAST, DIAGONAL_SOUTHWEST ->
        finiteVerticalHeuristic(precheckX, precheckZ, Math.max(minY, node.y - 1), Math.min(maxYExclusive - 1, node.y + 1));
      case DESCEND_EAST, DESCEND_WEST, DESCEND_NORTH, DESCEND_SOUTH -> finiteVerticalHeuristic(precheckX, precheckZ, minY, Math.min(maxYExclusive - 1, node.y - 1));
      case PARKOUR_NORTH, PARKOUR_SOUTH, PARKOUR_EAST, PARKOUR_WEST -> parkourHeuristicLowerBound(legacy.move(), node, minY, maxYExclusive);
      default -> 0D;
    };
  }

  private double parkourHeuristicLowerBound(Moves move, PathNode node, int minY, int maxYExclusive) {
    int sx = Integer.signum(move.xOffset);
    int sz = Integer.signum(move.zOffset);
    double best = Double.POSITIVE_INFINITY;
    for (int dist = 2; dist <= 4; dist++) {
      int x = node.x + sx * dist;
      int z = node.z + sz * dist;
      if (node.y >= minY && node.y < maxYExclusive) {
        best = Math.min(best, nonnegativeHeuristic(x, node.y, z));
      }
      if (node.y + 1 >= minY && node.y + 1 < maxYExclusive) {
        best = Math.min(best, nonnegativeHeuristic(x, node.y + 1, z));
      }
    }
    return Double.isFinite(best) ? best : 0D;
  }

  private double finiteVerticalHeuristic(int x, int z, int yMin, int yMax) {
    if (yMax < yMin) {
      return 0D;
    }
    double best = Double.POSITIVE_INFINITY;
    for (int y = yMin; y <= yMax; y++) {
      best = Math.min(best, nonnegativeHeuristic(x, y, z));
    }
    return Double.isFinite(best) ? best : 0D;
  }

  private double nonnegativeHeuristic(int x, int y, int z) {
    return Math.max(0D, goal.heuristic(x, y, z));
  }

  private int eventTie(int primitiveIndex, MovementPrimitive primitive) {
    int family = primitive instanceof LegacyMovesPrimitive legacy ? legacy.move().ordinal() : 1024 + primitiveIndex;
    return family << 16 | primitiveIndex;
  }

  public OracleSearchResult calculateForOracle(long timeout) {
    cancelRequested = false;
    Optional<IPath> path = calculate0(timeout, timeout);
    return path.map(raw -> {
      if (!(raw instanceof Path p)) {
        throw new IllegalStateException("A* oracle expected raw Path, got " + raw.getClass().getName());
      }
      return new OracleSearchResult(p.positions(), p.totalCost(), p.getNumNodesConsidered(), goal.isInGoal(p.getDest()), lastStopReason, lastMovementsConsidered, lastEmptyChunkFetches,
        lastNodeMapSize);
    }).orElseGet(() -> new OracleSearchResult(List.of(), Double.POSITIVE_INFINITY, nodeMapSize(), false, lastStopReason, lastMovementsConsidered, lastEmptyChunkFetches, lastNodeMapSize));
  }

  private void recordStop(String reason, int movementsConsidered, int emptyChunkFetches) {
    lastStopReason = reason;
    lastMovementsConsidered = movementsConsidered;
    lastEmptyChunkFetches = emptyChunkFetches;
    lastNodeMapSize = nodeMapSize();
  }

  private String terminalReason(boolean cancelled, boolean nodeCapReached, boolean upperBoundExhausted, boolean openSetEmpty, int emptyChunkFetches, int emptyChunkLimit) {
    return cancelled ? "cancel" : nodeCapReached || nodeMapFull() ? "node_cap"
      : upperBoundExhausted ? "upper_bound_exhausted" : openSetEmpty ? "open_set_empty" : emptyChunkFetches >= emptyChunkLimit ? "empty_chunk_limit" : "timeout";
  }

  public record OracleSearchResult(List<BetterBlockPos> positions, double nominalCostTicks, int nodesExpanded, boolean reachedGoal, String stopReason, int movementsConsidered, int emptyChunkFetches,
    int nodeMapSize) {
  }
}
