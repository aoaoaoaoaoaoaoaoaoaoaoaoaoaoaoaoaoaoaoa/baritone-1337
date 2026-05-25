package baritone.pathing.calc;

import baritone.api.BaritoneAPI;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.SettingsUtil;
import baritone.pathing.movement.BlockOffset;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.DestinationSpec;
import baritone.pathing.movement.EdgeEvalScratch;
import baritone.pathing.movement.EdgeEvalStatus;
import baritone.pathing.movement.LegacyMovesPrimitive;
import baritone.pathing.movement.MovementCatalog;
import baritone.pathing.movement.MovementPrimitive;
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
  private static final int NODE_ARENA_EXPECTED_SIZE = 2_000_000;
  private static final float NODE_ARENA_LOAD_FACTOR = 0.75f;

  private final Favoring favoring;
  private final double oracleUpperBoundTicks;
  private volatile DensePathNodeArena denseNodes;
  private volatile int denseStartNode;
  private volatile int denseMostRecentNode;
  private final int[] denseBestSoFar = new int[COEFFICIENTS.length];
  private int denseLastPublishedNode;
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
    this.oracleUpperBoundTicks = oracleUpperBoundTicks;
  }

  @Override
  protected Optional<IPath> calculate0(long primaryTimeout, long failureTimeout) {
    return calculateEager0(primaryTimeout, failureTimeout);
  }

  private Optional<IPath> calculateEager0(long primaryTimeout, long failureTimeout) {
    recordStop("running", 0, 0);
    PathingProfiler.Active activeProfile = profile;
    long searchLoopStarted = activeProfile == null ? 0 : System.nanoTime();
    long heapNanos = 0;
    long nodeMapNanos = 0;
    int minY = context.world.dimensionType().minY();
    int maxYExclusive = minY + context.world.dimensionType().height();
    int maxNodes = BaritoneAPI.getSettings().pathingMaxNodes.value;
    DensePathNodeArena nodes = new DensePathNodeArena(goal, Math.min(maxNodes, NODE_ARENA_EXPECTED_SIZE), NODE_ARENA_LOAD_FACTOR, maxNodes);
    denseNodes = nodes;
    long nodeMapStart = activeProfile == null ? 0 : System.nanoTime();
    int startNode = nodes.getOrCreate(startX, startY, startZ, BlockKey.pack(startX, startY, startZ));
    denseStartNode = startNode;
    if (activeProfile != null) {
      nodeMapNanos += System.nanoTime() - nodeMapStart;
    }
    nodes.cost(startNode, 0D);
    nodes.combinedCost(startNode, nodes.heuristic(startNode));
    IntBinaryHeapOpenSet openSet = new IntBinaryHeapOpenSet(nodes);
    long heapStart = activeProfile == null ? 0 : System.nanoTime();
    openSet.insert(startNode);
    if (activeProfile != null) {
      heapNanos += System.nanoTime() - heapStart;
    }
    double[] bestHeuristicSoFar = new double[COEFFICIENTS.length]; // keep track of the best node by the metric of (estimatedCostToGoal + cost / COEFFICIENTS[i])
    for (int i = 0; i < bestHeuristicSoFar.length; i++) {
      bestHeuristicSoFar[i] = nodes.heuristic(startNode);
      denseBestSoFar[i] = startNode;
    }
    denseLastPublishedNode = 0;
    EdgeEvalScratch eval = new EdgeEvalScratch();
    NodeTerrainFacts terrainFacts = new NodeTerrainFacts();
    eval.nodeFacts = terrainFacts;
    BetterWorldBorder worldBorder = context.worldBorder;
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
    MovementCatalog catalog = context.movementCatalog;
    MovementPrimitive[] allMoves = catalog.primitives();
    double[] minimumCosts = minimumCosts(allMoves);
    FrontierValueObjective frontier = goal instanceof FrontierValueObjective value ? value : null;
    LocalExitObjective localExit = frontier == null && goal instanceof LocalExitObjective exit ? exit : null;
    boolean scoredExitSearch = frontier != null || localExit != null;
    int bestExit = 0;
    double bestExitScore = Double.POSITIVE_INFINITY;
    boolean nodeCapReached = false;
    boolean upperBoundExhausted = false;
    search : while (!openSet.isEmpty() && numEmptyChunk < pathingMaxChunkBorderFetch && !cancelRequested && !nodes.full()) {
      if (scoredExitSearch && bestExit != 0 && openSet.lowestCombinedCost() + minimumImprovement >= bestExitScore) {
        logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered; proved best local exit");
        recordStop("best_exit", numMovementsConsidered, numEmptyChunk);
        if (activeProfile != null) {
          activeProfile.finishSearchLoop(numNodes, numMovementsConsidered, numEmptyChunk, nodes.size(), "best_exit", System.nanoTime() - searchLoopStarted, heapNanos, nodeMapNanos);
        }
        return pathTo(nodes, bestExit, numNodes);
      }
      if ((numNodes & (timeCheckInterval - 1)) == 0) { // only call this once every 64 nodes (about half a millisecond)
        long now = System.currentTimeMillis(); // since nanoTime is slow on windows (takes many microseconds)
        if (now - failureTimeoutTime >= 0 || ((!failing || scoredExitSearch && bestExit != 0) && now - primaryTimeoutTime >= 0)) {
          break;
        }
        if (now - nextIncumbentPublishTime >= 0) {
          // Frontier searches must earn an exit proof; unproved exit incumbents caused ledge-running myopia.
          if (!scoredExitSearch) {
            publishDenseBestSoFar(numNodes);
          }
          nextIncumbentPublishTime = now + incumbentInterval;
        }
      }
      heapStart = activeProfile == null ? 0 : System.nanoTime();
      int currentNode = openSet.removeLowest();
      if (activeProfile != null) {
        heapNanos += System.nanoTime() - heapStart;
      }
      if (nodes.combinedCost(currentNode) - oracleUpperBoundTicks > minimumImprovement) {
        upperBoundExhausted = true;
        break;
      }
      denseMostRecentNode = currentNode;
      numNodes++;
      int currentX = nodes.x(currentNode);
      int currentY = nodes.y(currentNode);
      int currentZ = nodes.z(currentNode);
      double currentCost = nodes.cost(currentNode);
      if (goal.isInGoal(currentX, currentY, currentZ)) {
        if (scoredExitSearch) {
          double score = currentCost + (frontier == null ? localExit.terminalExitValue(currentX, currentY, currentZ) : frontier.terminalExitValue(currentX, currentY, currentZ));
          if (bestExitScore - score > minimumImprovement) {
            bestExit = currentNode;
            bestExitScore = score;
          }
          continue;
        }
        logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered");
        recordStop("goal", numMovementsConsidered, numEmptyChunk);
        if (activeProfile != null) {
          activeProfile.finishSearchLoop(numNodes, numMovementsConsidered, numEmptyChunk, nodes.size(), "goal", System.nanoTime() - searchLoopStarted, heapNanos, nodeMapNanos);
        }
        return pathTo(nodes, currentNode, numNodes);
      }
      if (localExit != null && nodes.previous(currentNode) != 0 && localExit.isExactLocalExit(currentX, currentY, currentZ)) {
        double score = currentCost + localExit.localExitValue(currentX, currentY, currentZ);
        if (Double.isFinite(score) && bestExitScore - score > minimumImprovement) {
          bestExit = currentNode;
          bestExitScore = score;
        }
        continue;
      }
      terrainFacts.load(context, currentX, currentY, currentZ);
      double bestBoundaryValue = Double.POSITIVE_INFINITY;
      for (int primitiveIndex = 0; primitiveIndex < allMoves.length; primitiveIndex++) {
        MovementPrimitive primitive = allMoves[primitiveIndex];
        DestinationSpec spec = primitive.destinationSpec();
        BlockOffset probe = spec.precheckOffset();
        int newX = currentX + probe.dx();
        int newY = currentY + probe.dy();
        int newZ = currentZ + probe.dz();
        if ((newX >> 4 != currentX >> 4 || newZ >> 4 != currentZ >> 4) && !context.hasPathingData(newX, newZ)) {
          // only need to check if the destination is a live chunk if it's in a different chunk than the start of the movement
          if (frontier != null) {
            bestBoundaryValue = Math.min(bestBoundaryValue, frontier.frontierExitValue(currentX, currentY, currentZ, newX, newY, newZ));
          } else if (localExit != null) {
            bestBoundaryValue = Math.min(bestBoundaryValue, localExit.localExitValue(currentX, currentY, currentZ));
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
        int staticIncumbent = 0;
        if (!spec.dynamicXZ() && !spec.dynamicY()) {
          blockKey = BlockKey.pack(newX, newY, newZ);
          hasStaticBlockKey = true;
          double minimumCost = minimumCosts[primitiveIndex];
          if (minimumCost > 0) {
            nodeMapStart = activeProfile == null ? 0 : System.nanoTime();
            staticIncumbent = nodes.peek(blockKey);
            staticIncumbentKnown = true;
            if (activeProfile != null) {
              nodeMapNanos += System.nanoTime() - nodeMapStart;
            }
            if (staticIncumbent != 0) {
              double lowerBoundActionCost = minimumCost * (isFavoring ? favoring.calculate(blockKey) : 1);
              if (nodes.cost(staticIncumbent) - (currentCost + lowerBoundActionCost) <= minimumImprovement) {
                recordLowerBoundPrune(activeProfile, primitive);
                continue;
              }
            }
          }
        }
        if (activeProfile == null) {
          primitive.evaluate(context, currentX, currentY, currentZ, eval);
        } else {
          long moveStart = System.nanoTime();
          primitive.evaluate(context, currentX, currentY, currentZ, eval);
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
          throw new IllegalStateException(String.format("%s from %s %s %s calculated implausible cost %s", primitive.debugName(), SettingsUtil.maybeCensor(currentX),
            SettingsUtil.maybeCensor(currentY), SettingsUtil.maybeCensor(currentZ), actionCost));
        }
        if (spec.dynamicXZ() && !worldBorder.entirelyContains(eval.x, eval.z)) { // see issue #218
          continue;
        }
        if (!spec.dynamicXZ() && (eval.x != newX || eval.z != newZ)) {
          throw new IllegalStateException(
            String.format("%s from %s %s %s ended at x z %s %s instead of %s %s", primitive.debugName(), SettingsUtil.maybeCensor(currentX), SettingsUtil.maybeCensor(currentY),
              SettingsUtil.maybeCensor(currentZ), SettingsUtil.maybeCensor(eval.x), SettingsUtil.maybeCensor(eval.z), SettingsUtil.maybeCensor(newX), SettingsUtil.maybeCensor(newZ)));
        }
        if (!spec.dynamicY() && eval.y != newY) {
          throw new IllegalStateException(String.format("%s from %s %s %s ended at y %s instead of %s", primitive.debugName(), SettingsUtil.maybeCensor(currentX), SettingsUtil.maybeCensor(currentY),
            SettingsUtil.maybeCensor(currentZ), SettingsUtil.maybeCensor(eval.y), SettingsUtil.maybeCensor(newY)));
        }
        long favoringHash = BlockKey.pack(eval.x, eval.y, eval.z);
        if (isFavoring) {
          // see issue #18
          actionCost *= favoring.calculate(favoringHash);
        }
        if (!hasStaticBlockKey) {
          blockKey = favoringHash;
        }
        int neighbor;
        if (hasStaticBlockKey && staticIncumbentKnown) {
          if (staticIncumbent == 0) {
            if (nodes.full()) {
              nodeCapReached = true;
              break search;
            }
            nodeMapStart = activeProfile == null ? 0 : System.nanoTime();
            neighbor = nodes.createAbsent(eval.x, eval.y, eval.z, blockKey);
            if (activeProfile != null) {
              nodeMapNanos += System.nanoTime() - nodeMapStart;
            }
          } else {
            neighbor = staticIncumbent;
          }
        } else {
          nodeMapStart = activeProfile == null ? 0 : System.nanoTime();
          neighbor = nodes.peek(blockKey);
          if (neighbor == 0) {
            if (nodes.full()) {
              nodeCapReached = true;
              if (activeProfile != null) {
                nodeMapNanos += System.nanoTime() - nodeMapStart;
              }
              break search;
            }
            neighbor = nodes.createAbsent(eval.x, eval.y, eval.z, blockKey);
          }
          if (activeProfile != null) {
            nodeMapNanos += System.nanoTime() - nodeMapStart;
          }
        }
        double tentativeCost = currentCost + actionCost;
        if (nodes.cost(neighbor) - tentativeCost > minimumImprovement) {
          double combinedCost = tentativeCost + nodes.heuristic(neighbor);
          if (combinedCost - oracleUpperBoundTicks > minimumImprovement) {
            continue;
          }
          nodes.predecessor(neighbor, currentNode, primitiveIndex, eval.payload);
          nodes.cost(neighbor, tentativeCost);
          nodes.combinedCost(neighbor, combinedCost);
          if (nodes.isOpen(neighbor)) {
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
            double heuristic = nodes.heuristic(neighbor) + nodes.cost(neighbor) / COEFFICIENTS[i];
            if (bestHeuristicSoFar[i] - heuristic > minimumImprovement) {
              bestHeuristicSoFar[i] = heuristic;
              denseBestSoFar[i] = neighbor;
              if (!scoredExitSearch && failing && getDistFromStartSq(nodes, neighbor) > MIN_DIST_PATH * MIN_DIST_PATH) {
                failing = false;
              }
            }
          }
        }
      }
      if (Double.isFinite(bestBoundaryValue)) {
        double score = currentCost + bestBoundaryValue;
        if (Double.isFinite(score) && bestExitScore - score > minimumImprovement) {
          bestExit = currentNode;
          bestExitScore = score;
        }
      }
    }
    if (activeProfile != null) {
      activeProfile.finishSearchLoop(numNodes, numMovementsConsidered, numEmptyChunk, nodes.size(),
        terminalReason(cancelRequested, nodeCapReached, upperBoundExhausted, openSet.isEmpty(), numEmptyChunk, pathingMaxChunkBorderFetch, nodes.full()), System.nanoTime() - searchLoopStarted,
        heapNanos, nodeMapNanos);
    }
    recordStop(terminalReason(cancelRequested, nodeCapReached, upperBoundExhausted, openSet.isEmpty(), numEmptyChunk, pathingMaxChunkBorderFetch, nodes.full()), numMovementsConsidered, numEmptyChunk);
    if (cancelRequested) {
      return Optional.empty();
    }
    if (scoredExitSearch && bestExit != 0) {
      logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered; using best frontier exit without proof");
      recordStop("best_exit_unproven", numMovementsConsidered, numEmptyChunk);
      return pathTo(nodes, bestExit, numNodes);
    }
    if (scoredExitSearch) {
      logDebug(numMovementsConsidered + " movements considered; no frontier exit found");
      return Optional.empty();
    }
    logDebug(numMovementsConsidered + " movements considered");
    logDebug("Open set size: " + openSet.size());
    logDebug("Path node arena size: " + nodes.size());
    logDebug((int) (numNodes * 1.0 / ((System.currentTimeMillis() - startTime) / 1000F)) + " nodes per second");
    Optional<IPath> result = bestSoFar(true, numNodes);
    if (result.isPresent()) {
      logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered");
    }
    return result;
  }

  @Override
  public Optional<IPath> pathToMostRecentNodeConsidered() {
    DensePathNodeArena nodes = denseNodes;
    int start = denseStartNode;
    int current = denseMostRecentNode;
    return nodes == null || start == 0 || current == 0 ? Optional.empty() : pathTo(nodes, current, 0);
  }

  @Override
  public Optional<IPath> bestPathSoFar() {
    return bestSoFar(false, 0);
  }

  private Optional<IPath> bestSoFar(boolean logInfo, int numNodes) {
    DensePathNodeArena nodes = denseNodes;
    if (nodes == null || denseStartNode == 0) {
      return Optional.empty();
    }
    double bestDist = 0;
    for (int i = 0; i < COEFFICIENTS.length; i++) {
      int candidate = denseBestSoFar[i];
      if (candidate == 0) {
        continue;
      }
      double dist = getDistFromStartSq(nodes, candidate);
      if (dist > bestDist) {
        bestDist = dist;
      }
      if (dist > MIN_DIST_PATH * MIN_DIST_PATH) { // square the comparison since distFromStartSq is squared
        if (logInfo) {
          if (COEFFICIENTS[i] >= 3) {
            logDebug("Warning: cost coefficient is greater than three! Probably means that");
            logDebug("the path I found is pretty terrible (like sneak-bridging for dozens of blocks)");
            logDebug("Executing the best available partial path.");
          }
          logDebug("Path goes for " + Math.sqrt(dist) + " blocks");
          logDebug("A* cost coefficient " + COEFFICIENTS[i]);
        }
        return pathTo(nodes, candidate, numNodes);
      }
    }
    // instead of returning bestSoFar[0], be less misleading
    // if it actually won't find any path, don't make them think it will by rendering a dark blue that will never actually happen
    if (logInfo) {
      logDebug("Even with a cost coefficient of " + COEFFICIENTS[COEFFICIENTS.length - 1] + ", I couldn't get more than " + Math.sqrt(bestDist) + " blocks");
      logDebug("No path found =(");
      logNotification("No path found =(", true);
    }
    return Optional.empty();
  }

  private void publishDenseBestSoFar(int numNodes) {
    DensePathNodeArena nodes = denseNodes;
    int node = bestPublishableNode(nodes);
    if (node == 0 || node == denseLastPublishedNode) {
      return;
    }
    denseLastPublishedNode = node;
    publishPath(new Path(realStart, nodes, denseStartNode, node, numNodes, goal, context));
  }

  private int bestPublishableNode(DensePathNodeArena nodes) {
    if (nodes == null || denseStartNode == 0) {
      return 0;
    }
    for (int candidate : denseBestSoFar) {
      if (candidate != 0 && getDistFromStartSq(nodes, candidate) > MIN_DIST_PATH * MIN_DIST_PATH) {
        return candidate;
      }
    }
    return 0;
  }

  private Optional<IPath> pathTo(DensePathNodeArena nodes, int node, int numNodes) {
    return Optional.of(new Path(realStart, nodes, denseStartNode, node, numNodes, goal, context));
  }

  private double getDistFromStartSq(DensePathNodeArena nodes, int node) {
    int xDiff = nodes.x(node) - startX;
    int yDiff = nodes.y(node) - startY;
    int zDiff = nodes.z(node) - startZ;
    return xDiff * xDiff + yDiff * yDiff + zDiff * zDiff;
  }

  private int nodeMapSize() {
    DensePathNodeArena nodes = denseNodes;
    return nodes == null ? 0 : nodes.size();
  }

  private double[] minimumCosts(MovementPrimitive[] primitives) {
    double[] result = new double[primitives.length];
    for (int i = 0; i < primitives.length; i++) {
      result[i] = Math.max(0D, primitives[i].minimumCost(context));
    }
    return result;
  }

  private void recordLowerBoundPrune(PathingProfiler.Active activeProfile, MovementPrimitive primitive) {
    if (activeProfile == null) {
      return;
    }
    if (primitive instanceof LegacyMovesPrimitive legacy) {
      activeProfile.recordLowerBoundPrune(legacy.move());
    } else {
      activeProfile.recordLowerBoundPrune(primitive.debugName());
    }
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

  private String terminalReason(boolean cancelled, boolean nodeCapReached, boolean upperBoundExhausted, boolean openSetEmpty, int emptyChunkFetches, int emptyChunkLimit, boolean nodeMapFull) {
    return cancelled ? "cancel" : nodeCapReached || nodeMapFull ? "node_cap"
      : upperBoundExhausted ? "upper_bound_exhausted" : openSetEmpty ? "open_set_empty" : emptyChunkFetches >= emptyChunkLimit ? "empty_chunk_limit" : "timeout";
  }

  public record OracleSearchResult(List<BetterBlockPos> positions, double nominalCostTicks, int nodesExpanded, boolean reachedGoal, String stopReason, int movementsConsidered, int emptyChunkFetches,
    int nodeMapSize) {
  }
}
