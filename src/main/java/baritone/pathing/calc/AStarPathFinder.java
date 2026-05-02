package baritone.pathing.calc;

import baritone.Baritone;
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
import baritone.pathing.movement.NodeTerrainFacts;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.Favoring;
import java.util.Optional;

/**
 * The actual A* pathfinding
 *
 * @author leijurv
 */
public final class AStarPathFinder extends AbstractNodeCostSearch {
  private final Favoring favoring;
  private final CalculationContext calcContext;

  public AStarPathFinder(BetterBlockPos realStart, int startX, int startY, int startZ, Goal goal, Favoring favoring, CalculationContext context) {
    super(realStart, startX, startY, startZ, goal, context);
    this.favoring = favoring;
    this.calcContext = context;
  }

  @Override
  protected Optional<IPath> calculate0(long primaryTimeout, long failureTimeout) {
    PathingProfiler.Active activeProfile = profile;
    long searchLoopStarted = activeProfile == null ? 0 : System.nanoTime();
    long heapNanos = 0;
    long nodeMapNanos = 0;
    int minY = calcContext.world.dimensionType().minY();
    int height = calcContext.world.dimensionType().height();
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
    BetterWorldBorder worldBorder = new BetterWorldBorder(calcContext.world.getWorldBorder());
    long startTime = System.currentTimeMillis();
    boolean slowPath = Baritone.settings().slowPath.value;
    if (slowPath) {
      logDebug("slowPath is on, path timeout will be " + Baritone.settings().slowPathTimeoutMS.value + "ms instead of " + primaryTimeout + "ms");
    }
    long primaryTimeoutTime = startTime + (slowPath ? Baritone.settings().slowPathTimeoutMS.value : primaryTimeout);
    long failureTimeoutTime = startTime + (slowPath ? Baritone.settings().slowPathTimeoutMS.value : failureTimeout);
    boolean failing = true;
    int numNodes = 0;
    int numMovementsConsidered = 0;
    int numEmptyChunk = 0;
    boolean isFavoring = !favoring.isEmpty();
    int timeCheckInterval = 1 << 6;
    int pathingMaxChunkBorderFetch =
        Baritone.settings().pathingMaxChunkBorderFetch.value; // grab all settings beforehand so that changing settings during pathing doesn't cause a crash or unpredictable behavior
    double minimumImprovement = Baritone.settings().minimumImprovementRepropagation.value ? MIN_IMPROVEMENT : 0;
    MovementCatalog catalog = calcContext.movementCatalog;
    MovementPrimitive[] allMoves = catalog.primitives();
    while (!openSet.isEmpty() && numEmptyChunk < pathingMaxChunkBorderFetch && !cancelRequested) {
      if ((numNodes & (timeCheckInterval - 1)) == 0) { // only call this once every 64 nodes (about half a millisecond)
        long now = System.currentTimeMillis(); // since nanoTime is slow on windows (takes many microseconds)
        if (now - failureTimeoutTime >= 0 || (!failing && now - primaryTimeoutTime >= 0)) {
          break;
        }
      }
      if (slowPath) {
        try {
          Thread.sleep(Baritone.settings().slowPathTimeDelayMS.value);
        } catch (InterruptedException ignored) {}
      }
      heapStart = activeProfile == null ? 0 : System.nanoTime();
      PathNode currentNode = openSet.removeLowest();
      if (activeProfile != null) {
        heapNanos += System.nanoTime() - heapStart;
      }
      mostRecentConsidered = currentNode;
      numNodes++;
      if (goal.isInGoal(currentNode.x, currentNode.y, currentNode.z)) {
        logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered");
        if (activeProfile != null) {
          activeProfile.finishSearchLoop(numNodes, numMovementsConsidered, numEmptyChunk, nodeMapSize(), "goal", System.nanoTime() - searchLoopStarted, heapNanos, nodeMapNanos);
        }
        return Optional.of(new Path(realStart, startNode, currentNode, numNodes, goal, calcContext));
      }
      terrainFacts.load(calcContext, currentNode.x, currentNode.y, currentNode.z);
      for (int primitiveIndex = 0; primitiveIndex < allMoves.length; primitiveIndex++) {
        MovementPrimitive primitive = allMoves[primitiveIndex];
        DestinationSpec spec = primitive.destinationSpec();
        BlockOffset probe = spec.precheckOffset();
        int newX = currentNode.x + probe.dx();
        int newZ = currentNode.z + probe.dz();
        if ((newX >> 4 != currentNode.x >> 4 || newZ >> 4 != currentNode.z >> 4) && !calcContext.isLoaded(newX, newZ)) {
          // only need to check if the destination is a loaded chunk if it's in a different chunk than the start of the movement
          if (!spec.dynamicXZ()) { // only increment the counter if the movement would have gone out of bounds guaranteed
            numEmptyChunk++;
          }
          continue;
        }
        if (!spec.dynamicXZ() && !worldBorder.entirelyContains(newX, newZ)) {
          continue;
        }
        if (currentNode.y + probe.dy() > height || currentNode.y + probe.dy() < minY) {
          continue;
        }
        long blockKey = 0;
        boolean hasStaticBlockKey = false;
        if (!spec.dynamicXZ() && !spec.dynamicY()) {
          blockKey = BlockKey.pack(newX, currentNode.y + probe.dy(), newZ);
          hasStaticBlockKey = true;
          double minimumCost = primitive.minimumCost(calcContext);
          if (minimumCost > 0) {
            nodeMapStart = activeProfile == null ? 0 : System.nanoTime();
            PathNode incumbent = peekNodeAtPosition(blockKey);
            if (activeProfile != null) {
              nodeMapNanos += System.nanoTime() - nodeMapStart;
            }
            if (incumbent != null) {
              long favoringHash = BetterBlockPos.longHash(newX, currentNode.y + probe.dy(), newZ);
              double lowerBoundActionCost = minimumCost * (isFavoring ? favoring.calculate(favoringHash) : 1);
              if (incumbent.cost - (currentNode.cost + lowerBoundActionCost) <= minimumImprovement) {
                if (activeProfile != null && primitive instanceof LegacyMovesPrimitive legacy) {
                  activeProfile.recordLowerBoundPrune(legacy.move());
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
        if (!spec.dynamicY() && eval.y != currentNode.y + probe.dy()) {
          throw new IllegalStateException(String.format("%s from %s %s %s ended at y %s instead of %s", primitive.debugName(), SettingsUtil.maybeCensor(currentNode.x),
              SettingsUtil.maybeCensor(currentNode.y), SettingsUtil.maybeCensor(currentNode.z), SettingsUtil.maybeCensor(eval.y), SettingsUtil.maybeCensor(currentNode.y + probe.dy())));
        }
        long favoringHash = BetterBlockPos.longHash(eval.x, eval.y, eval.z);
        if (isFavoring) {
          // see issue #18
          actionCost *= favoring.calculate(favoringHash);
        }
        if (!hasStaticBlockKey) {
          blockKey = BlockKey.pack(eval.x, eval.y, eval.z);
        }
        nodeMapStart = activeProfile == null ? 0 : System.nanoTime();
        PathNode neighbor = getNodeAtPosition(eval.x, eval.y, eval.z, blockKey);
        if (activeProfile != null) {
          nodeMapNanos += System.nanoTime() - nodeMapStart;
        }
        double tentativeCost = currentNode.cost + actionCost;
        if (neighbor.cost - tentativeCost > minimumImprovement) {
          neighbor.previous = currentNode;
          neighbor.previousPrimitiveIndex = (short) primitiveIndex;
          neighbor.previousEdgePayload = eval.payload;
          neighbor.previousEdgeCost = actionCost;
          neighbor.cost = tentativeCost;
          neighbor.combinedCost = tentativeCost + neighbor.estimatedCostToGoal;
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
    }
    if (activeProfile != null) {
      activeProfile.finishSearchLoop(numNodes, numMovementsConsidered, numEmptyChunk, nodeMapSize(),
          cancelRequested                                   ? "cancel"
              : openSet.isEmpty()                           ? "open_set_empty"
              : numEmptyChunk >= pathingMaxChunkBorderFetch ? "empty_chunk_limit"
                                                            : "timeout",
          System.nanoTime() - searchLoopStarted, heapNanos, nodeMapNanos);
    }
    if (cancelRequested) {
      return Optional.empty();
    }
    System.out.println(numMovementsConsidered + " movements considered");
    System.out.println("Open set size: " + openSet.size());
    System.out.println("PathNode map size: " + mapSize());
    System.out.println((int) (numNodes * 1.0 / ((System.currentTimeMillis() - startTime) / 1000F)) + " nodes per second");
    Optional<IPath> result = bestSoFar(true, numNodes);
    if (result.isPresent()) {
      logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered");
    }
    return result;
  }
}
