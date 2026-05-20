package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import baritone.pathing.macro.core.MacroPlan;
import baritone.pathing.movement.BlockOffset;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.DestinationSpec;
import baritone.pathing.movement.EdgeEvalScratch;
import baritone.pathing.movement.EdgeEvalStatus;
import baritone.pathing.movement.MovementCatalog;
import baritone.pathing.movement.MovementPrimitive;
import baritone.pathing.movement.NodeTerrainFacts;
import baritone.pathing.route.PathRouteLeg;
import baritone.pathing.route.PlannedTransportState;
import baritone.pathing.route.RoutePlan;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.Favoring;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class PedestrianLocalHotPlanner {
  private PedestrianLocalValueField resident;

  public synchronized HotLocalPathCalculation query(CalculationContext context, BetterBlockPos realStart, int requestedX, int requestedY, int requestedZ, Goal localGoal, Goal terminalGoal,
    MacroPlan macroPlan, Favoring fallbackFavoring, long factEpoch) {
    StateKey key = StateKey.of(context, localGoal, terminalGoal, macroPlan);
    if (resident == null || resident.poisoned() || !resident.key.equals(key) || resident.factEpoch != factEpoch) {
      resident =
        new PedestrianLocalValueField(key, factEpoch, context.movementCatalog.size(), Baritone.settings().pedestrianHotLocalMaxNodes.value, Baritone.settings().pedestrianHotLocalMaxEdges.value);
    }
    return new HotLocalPathCalculation(resident, context, realStart, requestedX, requestedY, requestedZ, localGoal, terminalGoal, fallbackFavoring, factEpoch);
  }

  private record StateKey(String dimension, int minY, int maxYExclusive, String localGoal, String terminalGoal, String macro, String movement, Object placement, Object breaking, Object movementPolicy,
    Object reversibility, Object fall, Object costs, String geofence, String border) {
    static StateKey of(CalculationContext context, Goal localGoal, Goal terminalGoal, MacroPlan macroPlan) {
      StringBuilder movement = new StringBuilder();
      for (MovementPrimitive primitive : context.movementCatalog.primitives()) {
        if (!movement.isEmpty()) {
          movement.append('|');
        }
        movement.append(primitive.debugName()).append(':').append(primitive.destinationSpec());
      }
      String macro = macroPlan == null ? "none" : macroPlan.sequence() + ':' + macroPlan.firstUncertifiedAction() + ':' + macroPlan.totalVector() + ':' + macroPlan.valueTelemetry();
      return new StateKey(context.world.dimension().identifier().toString(), context.world.dimensionType().minY(), context.world.dimensionType().minY() + context.world.dimensionType().height(),
        identity(localGoal), identity(terminalGoal), macro, movement.toString(), context.placement, context.breaking, context.movement, context.reversibility, context.fall, context.costs,
        Baritone.settings().modificationGeofences.value.toString(), context.world.getWorldBorder().toString());
    }

    private static String identity(Goal goal) {
      return goal == null ? "null" : goal.getClass().getName() + ':' + goal;
    }
  }

  public static final class HotLocalPathCalculation implements ActivePathCalculation {
    private final PedestrianLocalValueField field;
    private final CalculationContext context;
    private final BetterBlockPos realStart;
    private final int startX;
    private final int startY;
    private final int startZ;
    private final Goal localGoal;
    private final Goal terminalGoal;
    private final Favoring fallbackFavoring;
    private final long factEpoch;
    private volatile boolean finished;
    private volatile boolean cancelRequested;
    private volatile IPath bestPath;
    private volatile RoutePlan routePlan;
    private PathPublicationSink publicationSink = PathPublicationSink.IGNORE;
    private HotLocalTelemetry telemetry = HotLocalTelemetry.EMPTY;

    private HotLocalPathCalculation(PedestrianLocalValueField field, CalculationContext context, BetterBlockPos realStart, int startX, int startY, int startZ, Goal localGoal, Goal terminalGoal,
      Favoring fallbackFavoring, long factEpoch) {
      this.field = field;
      this.context = context;
      this.realStart = realStart;
      this.startX = startX;
      this.startY = startY;
      this.startZ = startZ;
      this.localGoal = localGoal;
      this.terminalGoal = terminalGoal;
      this.fallbackFavoring = fallbackFavoring;
      this.factEpoch = factEpoch;
    }

    public Optional<RoutePlan> routePlan() {
      return Optional.ofNullable(routePlan);
    }

    public Goal terminalGoal() {
      return terminalGoal;
    }

    public HotLocalTelemetry telemetry() {
      return telemetry;
    }

    @Override
    public BetterBlockPos getStart() { return new BetterBlockPos(startX, startY, startZ); }

    @Override
    public Goal getGoal() { return localGoal; }

    @Override
    public void cancel() {
      cancelRequested = true;
    }

    @Override
    public void setPublicationSink(PathPublicationSink sink) { publicationSink = sink == null ? PathPublicationSink.IGNORE : sink; }

    @Override
    public PathCalculationResult calculate(long primaryTimeout, long failureTimeout) {
      if (finished) {
        throw new IllegalStateException("hot local calculation cannot be reused");
      }
      long startNanos = System.nanoTime();
      long deadline = System.currentTimeMillis() + Math.max(primaryTimeout, failureTimeout);
      try {
        HotLocalPath hot = field.query(context, realStart, startX, startY, startZ, localGoal, terminalGoal, deadline, () -> cancelRequested);
        telemetry = hot.telemetry();
        if (!cancelRequested && hot.path().isPresent()) {
          bestPath = hot.path().get();
          PlannedTransportState pedestrian = PlannedTransportState.pedestrian(context.waterTransport.boatAvailable());
          routePlan = RoutePlan.of(List.of(PathRouteLeg.connector(bestPath, pedestrian, pedestrian)), pedestrian, pedestrian, hot.continuation());
          PathCalculationResult result =
            new PathCalculationResult(terminalGoal.isInGoal(bestPath.getDest()) ? PathCalculationResult.Type.SUCCESS_TO_GOAL : PathCalculationResult.Type.SUCCESS_SEGMENT, bestPath);
          publicationSink.publish(result);
          return result;
        }
        if (cancelRequested) {
          return new PathCalculationResult(PathCalculationResult.Type.CANCELLATION);
        }
        field.poison();
        PathCalculationResult fallback = fallback(primaryTimeout, failureTimeout, startNanos, "no_hot_path");
        if (fallback != null) {
          return fallback;
        }
        return new PathCalculationResult(PathCalculationResult.Type.FAILURE);
      } catch (HotLocalCapacityExceeded e) {
        field.poison();
        System.gc();
        PathCalculationResult fallback = fallback(primaryTimeout, failureTimeout, startNanos, "node_cap");
        return fallback == null ? new PathCalculationResult(PathCalculationResult.Type.FAILURE) : fallback;
      } catch (Throwable t) {
        t.printStackTrace();
        field.poison();
        if (t instanceof OutOfMemoryError) {
          System.gc();
        }
        PathCalculationResult fallback = fallback(primaryTimeout, failureTimeout, startNanos, "exception");
        return fallback == null ? new PathCalculationResult(PathCalculationResult.Type.EXCEPTION) : fallback;
      } finally {
        finished = true;
      }
    }

    private PathCalculationResult fallback(long primaryTimeout, long failureTimeout, long startNanos, String reason) {
      if (!Baritone.settings().pedestrianHotLocalFallbackEnabled.value || cancelRequested) {
        return null;
      }
      AStarPathFinder fallback = new AStarPathFinder(realStart, startX, startY, startZ, localGoal, fallbackFavoring, context, PathingIncumbentPolicy.pedestrian());
      PathCalculationResult result = fallback.calculate(primaryTimeout, failureTimeout);
      bestPath = result.getPath().orElse(null);
      telemetry = telemetry.withFallback(reason + ":" + result.getType().name(), System.nanoTime() - startNanos);
      return result;
    }

    @Override
    public boolean isFinished() { return finished; }

    @Override
    public Optional<IPath> bestPathSoFar() {
      return Optional.ofNullable(bestPath);
    }

    @Override
    public Optional<PlanningProbe> probe() {
      IPath path = bestPath;
      return path == null ? Optional.empty() : Optional.of(PlanningProbe.best(path.positions()));
    }
  }

  public record HotLocalTelemetry(int nodes, int edges, int discoveryExpanded, int terminals, int repairPops, boolean startConsistent, String extractionKind, String fallback, long fallbackNanos) {
    static final HotLocalTelemetry EMPTY = new HotLocalTelemetry(0, 0, 0, 0, 0, false, "none", "", 0);

    HotLocalTelemetry withFallback(String fallback, long nanos) {
      return new HotLocalTelemetry(nodes, edges, discoveryExpanded, terminals, repairPops, startConsistent, extractionKind, fallback, nanos);
    }
  }

  private record HotLocalPath(Optional<IPath> path, double continuation, HotLocalTelemetry telemetry) {
  }

  private static final class HotLocalCapacityExceeded extends RuntimeException {
    HotLocalCapacityExceeded(String resource, int max) {
      super("pedestrian hot local " + resource + " cap exceeded: " + max);
    }
  }

  private static final class PedestrianLocalValueField {
    private static final byte BLOCKED = 1;
    private static final byte REACHABLE = 2;
    private static final byte BOUNDARY = 3;
    private static final double EPS = 0.01D;

    private final StateKey key;
    private final long factEpoch;
    private final int primitiveCount;
    private final int maxNodes;
    private final int maxEdges;
    private final Long2IntOpenHashMap nodeIds = new Long2IntOpenHashMap();
    private long[] nodeKeys = new long[1024];
    private double[] g = new double[1024];
    private double[] rhs = new double[1024];
    private double[] terminal = new double[1024];
    private int[] bestSucc = new int[1024];
    private int[] bestEdge = new int[1024];
    private short[] bestPrimitive = new short[1024];
    private int[] bestPayload = new int[1024];
    private double[] bestEdgeCost = new double[1024];
    private int[] heapIndex = new int[1024];
    private double[] queryCost = new double[1024];
    private int[] queryGeneration = new int[1024];
    private int[] firstOutgoingEdge = new int[1024];
    private short[] outgoingEdgeCount = new short[1024];
    private int[] firstIncomingEdge = new int[1024];
    private final EdgeStore edges;
    private int nodeCount;
    private int edgeCount;
    private int queryGenerationCounter;
    private final DStarHeap open = new DStarHeap(this);
    private final DiscoveryHeap discoveryQueue = new DiscoveryHeap();
    private final EdgeEvalScratch scratch = new EdgeEvalScratch();
    private final NodeTerrainFacts terrainFacts = new NodeTerrainFacts();
    private int terminalCount;
    private boolean poisoned;

    private PedestrianLocalValueField(StateKey key, long factEpoch, int primitiveCount, int maxNodes, int maxEdges) {
      this.key = key;
      this.factEpoch = factEpoch;
      this.primitiveCount = primitiveCount;
      this.maxNodes = Math.max(1024, maxNodes);
      this.maxEdges = Math.max(4096, maxEdges);
      edges = new EdgeStore(this.maxEdges);
      nodeIds.defaultReturnValue(-1);
      scratch.nodeFacts = terrainFacts;
      fillNodeDefaults(0, nodeKeys.length);
    }

    boolean poisoned() {
      return poisoned;
    }

    void poison() {
      poisoned = true;
      nodeIds.clear();
      nodeKeys = new long[0];
      g = rhs = terminal = bestEdgeCost = queryCost = new double[0];
      bestSucc = bestEdge = bestPayload = heapIndex = queryGeneration = firstOutgoingEdge = firstIncomingEdge = new int[0];
      bestPrimitive = outgoingEdgeCount = new short[0];
      edgeCount = 0;
      nodeCount = 0;
      terminalCount = 0;
      open.clear();
      discoveryQueue.clear();
      edges.release();
    }

    HotLocalPath query(CalculationContext context, BetterBlockPos realStart, int startX, int startY, int startZ, Goal localGoal, Goal terminalGoal, long deadlineMillis, CancelFlag cancel) {
      int start = ensureNode(BlockKey.pack(startX, startY, startZ));
      DiscoveryStats discovery = discover(context, start, localGoal, terminalGoal, deadlineMillis, cancel);
      RepairStats repair = repair(start, deadlineMillis, cancel);
      if (cancel.cancelled() || !same(g[start], rhs[start]) || !Double.isFinite(g[start])) {
        return new HotLocalPath(Optional.empty(), Double.POSITIVE_INFINITY,
          new HotLocalTelemetry(nodeCount, edgeCount, discovery.expanded, terminalCount(), repair.pops, same(g[start], rhs[start]), "none", "", 0));
      }
      Extracted extracted = extract(start, Baritone.settings().pedestrianHotLocalExtractionMaxMovements.value);
      if (extracted == null) {
        return new HotLocalPath(Optional.empty(), Double.POSITIVE_INFINITY, new HotLocalTelemetry(nodeCount, edgeCount, discovery.expanded, terminalCount(), repair.pops, true, "none", "", 0));
      }
      Optional<IPath> path = materialize(context, realStart, start, extracted, localGoal);
      return new HotLocalPath(path, extracted.continuation, new HotLocalTelemetry(nodeCount, edgeCount, discovery.expanded, terminalCount(), repair.pops, true, extracted.kind.name(), "", 0));
    }

    private DiscoveryStats discover(CalculationContext context, int start, Goal localGoal, Goal terminalGoal, long deadlineMillis, CancelFlag cancel) {
      int generation = ++queryGenerationCounter;
      int terminalTarget = Math.max(1, Baritone.settings().pedestrianHotLocalTerminalTarget.value);
      int postTerminalExpansionCap = Math.max(0, Baritone.settings().pedestrianHotLocalPostTerminalExpansions.value);
      DiscoveryHeap queue = discoveryQueue;
      queue.clear();
      setQueryCost(start, generation, 0D);
      queue.add(start, localGoal.heuristic(BlockKey.x(nodeKeys[start]), BlockKey.y(nodeKeys[start]), BlockKey.z(nodeKeys[start])), 0D);
      int expanded = 0;
      int terminalHits = 0;
      int firstTerminalExpansion = -1;
      while (!queue.isEmpty() && !cancel.cancelled() && System.currentTimeMillis() <= deadlineMillis && nodeCount < maxNodes) {
        if (terminalHits >= terminalTarget) {
          break;
        }
        if (firstTerminalExpansion >= 0 && expanded - firstTerminalExpansion >= postTerminalExpansionCap) {
          break;
        }
        int node = queue.poll();
        double cost = queue.polledCost;
        if (queryGeneration[node] != generation || Math.abs(queryCost[node] - cost) > EPS) {
          continue;
        }
        expanded++;
        int x = BlockKey.x(nodeKeys[node]);
        int y = BlockKey.y(nodeKeys[node]);
        int z = BlockKey.z(nodeKeys[node]);
        activateDiscoveredTerminals(node, x, y, z, localGoal, terminalGoal);
        for (short primitive = 0; primitive < primitiveCount; primitive++) {
          int edgeId = evaluateOrGet(context, node, primitive);
          byte status = edges.status(edgeId);
          if (status == BOUNDARY) {
            if (localGoal instanceof LocalExitObjective exit && exit.isExactLocalExit(x, y, z)) {
              activateTerminal(node, exit.localExitValue(x, y, z));
              break;
            }
            continue;
          }
          if (status != REACHABLE) {
            continue;
          }
          int dest = edges.dest(edgeId);
          double nextCost = cost + edges.cost(edgeId);
          if (queryGeneration[dest] != generation || queryCost[dest] - nextCost > EPS) {
            setQueryCost(dest, generation, nextCost);
            int dx = BlockKey.x(nodeKeys[dest]);
            int dy = BlockKey.y(nodeKeys[dest]);
            int dz = BlockKey.z(nodeKeys[dest]);
            queue.add(dest, nextCost + localGoal.heuristic(dx, dy, dz), nextCost);
          }
        }
        if (Double.isFinite(terminal[node])) {
          if (firstTerminalExpansion < 0) {
            firstTerminalExpansion = expanded;
          }
          terminalHits++;
        }
      }
      return new DiscoveryStats(expanded);
    }

    private void activateDiscoveredTerminals(int node, int x, int y, int z, Goal localGoal, Goal terminalGoal) {
      if (terminalGoal.isInGoal(x, y, z)) {
        activateTerminal(node, 0D);
      }
      if (localGoal instanceof LocalExitObjective exit && exit.isExactLocalExit(x, y, z)) {
        activateTerminal(node, exit.localExitValue(x, y, z));
      }
    }

    private boolean activateTerminal(int node, double cost) {
      if (Double.isFinite(cost) && cost >= 0D && terminal[node] - cost > EPS) {
        if (!Double.isFinite(terminal[node])) {
          terminalCount++;
        }
        terminal[node] = cost;
        updateVertex(node);
        updatePredecessors(node);
        return true;
      }
      return false;
    }

    private RepairStats repair(int start, long deadlineMillis, CancelFlag cancel) {
      int pops = 0;
      while (!open.isEmpty() && repairNeeded(start) && !cancel.cancelled() && System.currentTimeMillis() <= deadlineMillis) {
        int node = open.poll();
        pops++;
        double gv = g[node];
        double rv = rhs[node];
        if (gv > rv) {
          g[node] = rv;
          updatePredecessors(node);
        } else {
          g[node] = Double.POSITIVE_INFINITY;
          updateVertex(node);
          updatePredecessors(node);
        }
      }
      return new RepairStats(pops);
    }

    private boolean repairNeeded(int start) {
      if (!same(g[start], rhs[start])) {
        return true;
      }
      return !open.isEmpty() && open.peekKey() < Math.min(g[start], rhs[start]) - EPS;
    }

    private Extracted extract(int start, int maxMovements) {
      ArrayList<Integer> edgeIds = new ArrayList<>();
      int u = start;
      while (!selectedTerminal(u) && edgeIds.size() < maxMovements) {
        int v = bestSucc[u];
        if (v < 0) {
          return null;
        }
        int edgeId = bestEdge[u];
        if (edges.status(edgeId) != REACHABLE || edges.dest(edgeId) != v) {
          updateVertex(u);
          return null;
        }
        edgeIds.add(edgeId);
        u = v;
      }
      if (selectedTerminal(u)) {
        return new Extracted(edgeIds, terminal[u], ExtractionKind.SELECTED_TERMINAL);
      }
      if (!same(g[u], rhs[u]) || !Double.isFinite(g[u])) {
        return null;
      }
      return new Extracted(edgeIds, g[u], ExtractionKind.LOCAL_VALUE_CONTINUATION);
    }

    private Optional<IPath> materialize(CalculationContext context, BetterBlockPos realStart, int start, Extracted extracted, Goal localGoal) {
      PathNode startNode = new PathNode(BlockKey.x(nodeKeys[start]), BlockKey.y(nodeKeys[start]), BlockKey.z(nodeKeys[start]), localGoal);
      startNode.cost = 0D;
      PathNode cursor = startNode;
      double cost = 0D;
      int u = start;
      for (int edgeId : extracted.edgeIds) {
        int v = edges.dest(edgeId);
        PathNode next = new PathNode(BlockKey.x(nodeKeys[v]), BlockKey.y(nodeKeys[v]), BlockKey.z(nodeKeys[v]), localGoal);
        cost += edges.cost(edgeId);
        next.cost = cost;
        next.previous = cursor;
        next.previousPrimitiveIndex = edges.primitive(edgeId);
        next.previousEdgePayload = edges.payload(edgeId);
        next.previousEdgeCost = edges.cost(edgeId);
        cursor = next;
        u = v;
      }
      Path raw = new Path(realStart, startNode, cursor, nodeCount, localGoal, context);
      IPath verified = raw.postProcess();
      BetterBlockPos expectedDest = new BetterBlockPos(BlockKey.x(nodeKeys[u]), BlockKey.y(nodeKeys[u]), BlockKey.z(nodeKeys[u]));
      if (!verified.getDest().equals(expectedDest)) {
        return Optional.empty();
      }
      return Optional.of(verified);
    }

    private boolean selectedTerminal(int node) {
      return Double.isFinite(terminal[node]) && bestSucc[node] < 0 && same(rhs[node], terminal[node]);
    }

    private void updateVertex(int node) {
      double best = terminal[node];
      int succ = -1;
      int selectedEdge = -1;
      short primitive = -1;
      int payload = 0;
      double edgeCost = Double.POSITIVE_INFINITY;
      int firstEdge = firstOutgoingEdge[node];
      int limit = firstEdge < 0 ? -1 : firstEdge + outgoingEdgeCount[node];
      for (int edgeId = firstEdge; edgeId < limit; edgeId++) {
        if (edges.status(edgeId) != REACHABLE) {
          continue;
        }
        int dest = edges.dest(edgeId);
        if (!Double.isFinite(g[dest])) {
          continue;
        }
        double candidate = edges.cost(edgeId) + g[dest];
        if (best - candidate > EPS) {
          best = candidate;
          succ = dest;
          selectedEdge = edgeId;
          primitive = edges.primitive(edgeId);
          payload = edges.payload(edgeId);
          edgeCost = edges.cost(edgeId);
        }
      }
      rhs[node] = best;
      bestSucc[node] = succ;
      bestEdge[node] = selectedEdge;
      bestPrimitive[node] = primitive;
      bestPayload[node] = payload;
      bestEdgeCost[node] = edgeCost;
      if (same(g[node], rhs[node])) {
        open.remove(node);
      } else {
        open.upsert(node);
      }
    }

    private void updatePredecessors(int node) {
      for (int edgeId = firstIncomingEdge[node]; edgeId >= 0; edgeId = edges.nextIncoming(edgeId)) {
        if (edges.status(edgeId) == REACHABLE) {
          updateVertex(edges.source(edgeId));
        }
      }
    }

    private int evaluateOrGet(CalculationContext context, int source, short primitiveIndex) {
      int existing = evaluatedEdge(source, primitiveIndex);
      if (existing >= 0) {
        return existing;
      }
      int edgeId = appendEdge(source, primitiveIndex);
      MovementCatalog catalog = context.movementCatalog;
      MovementPrimitive primitive = catalog.primitive(primitiveIndex);
      long sourceKey = nodeKeys[source];
      int x = BlockKey.x(sourceKey);
      int y = BlockKey.y(sourceKey);
      int z = BlockKey.z(sourceKey);
      DestinationSpec spec = primitive.destinationSpec();
      BlockOffset probe = spec.precheckOffset();
      int newX = x + probe.dx();
      int newY = y + probe.dy();
      int newZ = z + probe.dz();
      if ((newX >> 4 != x >> 4 || newZ >> 4 != z >> 4) && !context.hasPathingData(newX, newZ)) {
        return setEdge(edgeId, BOUNDARY, -1, 0D, 0);
      }
      BetterWorldBorder worldBorder = context.worldBorder;
      if (!spec.dynamicXZ() && !worldBorder.entirelyContains(newX, newZ)) {
        return setEdge(edgeId, BLOCKED, -1, 0D, 0);
      }
      if (newY < key.minY() || newY >= key.maxYExclusive()) {
        return setEdge(edgeId, BLOCKED, -1, 0D, 0);
      }
      scratch.blocked();
      terrainFacts.load(context, x, y, z);
      primitive.evaluate(context, x, y, z, scratch);
      if (scratch.status != EdgeEvalStatus.REACHABLE || scratch.cost <= 0D || scratch.cost >= ActionCosts.COST_INF || Double.isNaN(scratch.cost)) {
        return setEdge(edgeId, BLOCKED, -1, 0D, 0);
      }
      if (spec.dynamicXZ() && !worldBorder.entirelyContains(scratch.x, scratch.z)) {
        return setEdge(edgeId, BLOCKED, -1, 0D, 0);
      }
      if (!spec.dynamicXZ() && (scratch.x != newX || scratch.z != newZ)) {
        return setEdge(edgeId, BLOCKED, -1, 0D, 0);
      }
      if (!spec.dynamicY() && scratch.y != newY) {
        return setEdge(edgeId, BLOCKED, -1, 0D, 0);
      }
      if (scratch.y < key.minY() || scratch.y >= key.maxYExclusive()) {
        return setEdge(edgeId, BLOCKED, -1, 0D, 0);
      }
      int dest = ensureNode(BlockKey.pack(scratch.x, scratch.y, scratch.z));
      setEdge(edgeId, REACHABLE, dest, scratch.cost, scratch.payload);
      edges.linkIncoming(edgeId, firstIncomingEdge[dest]);
      firstIncomingEdge[dest] = edgeId;
      updateVertex(source);
      return edgeId;
    }

    private int setEdge(int index, byte status, int dest, double cost, int payload) {
      edges.set(index, status, dest, cost, payload);
      return index;
    }

    private int evaluatedEdge(int source, short primitive) {
      int firstEdge = firstOutgoingEdge[source];
      int count = outgoingEdgeCount[source];
      if (firstEdge < 0 || primitive < 0 || primitive >= count) {
        return -1;
      }
      return firstEdge + primitive;
    }

    private int appendEdge(int source, short primitive) {
      int count = outgoingEdgeCount[source];
      if (primitive != count) {
        throw new IllegalStateException("non-contiguous hot local primitive evaluation for source " + source + ": primitive=" + primitive + " count=" + count);
      }
      int edgeId = edges.append(edgeCount++, source, primitive);
      if (count == 0) {
        firstOutgoingEdge[source] = edgeId;
      }
      outgoingEdgeCount[source] = (short) (count + 1);
      return edgeId;
    }

    private int ensureNode(long key) {
      int existing = nodeIds.get(key);
      if (existing >= 0) {
        return existing;
      }
      if (nodeCount >= maxNodes) {
        throw new HotLocalCapacityExceeded("node", maxNodes);
      }
      ensureNodeCapacity(nodeCount + 1);
      int id = nodeCount++;
      nodeIds.put(key, id);
      nodeKeys[id] = key;
      fillNodeDefaults(id, id + 1);
      return id;
    }

    private void ensureNodeCapacity(int required) {
      if (required <= nodeKeys.length) {
        return;
      }
      int old = nodeKeys.length;
      int next = old == 0 ? 1024 : old;
      while (next < required) {
        next = next < 1_048_576 ? next << 1 : next + (next >>> 1);
        if (next < 0 || next > maxNodes) {
          next = maxNodes;
        }
      }
      nodeKeys = java.util.Arrays.copyOf(nodeKeys, next);
      g = java.util.Arrays.copyOf(g, next);
      rhs = java.util.Arrays.copyOf(rhs, next);
      terminal = java.util.Arrays.copyOf(terminal, next);
      bestSucc = java.util.Arrays.copyOf(bestSucc, next);
      bestEdge = java.util.Arrays.copyOf(bestEdge, next);
      bestPrimitive = java.util.Arrays.copyOf(bestPrimitive, next);
      bestPayload = java.util.Arrays.copyOf(bestPayload, next);
      bestEdgeCost = java.util.Arrays.copyOf(bestEdgeCost, next);
      heapIndex = java.util.Arrays.copyOf(heapIndex, next);
      queryCost = java.util.Arrays.copyOf(queryCost, next);
      queryGeneration = java.util.Arrays.copyOf(queryGeneration, next);
      firstOutgoingEdge = java.util.Arrays.copyOf(firstOutgoingEdge, next);
      firstIncomingEdge = java.util.Arrays.copyOf(firstIncomingEdge, next);
      outgoingEdgeCount = java.util.Arrays.copyOf(outgoingEdgeCount, next);
      fillNodeDefaults(old, next);
    }

    private void fillNodeDefaults(int from, int to) {
      java.util.Arrays.fill(g, from, to, Double.POSITIVE_INFINITY);
      java.util.Arrays.fill(rhs, from, to, Double.POSITIVE_INFINITY);
      java.util.Arrays.fill(terminal, from, to, Double.POSITIVE_INFINITY);
      java.util.Arrays.fill(bestSucc, from, to, -1);
      java.util.Arrays.fill(bestEdge, from, to, -1);
      java.util.Arrays.fill(bestPrimitive, from, to, (short) -1);
      java.util.Arrays.fill(bestEdgeCost, from, to, Double.POSITIVE_INFINITY);
      java.util.Arrays.fill(heapIndex, from, to, -1);
      java.util.Arrays.fill(firstOutgoingEdge, from, to, -1);
      java.util.Arrays.fill(firstIncomingEdge, from, to, -1);
    }

    private void setQueryCost(int node, int generation, double cost) {
      queryGeneration[node] = generation;
      queryCost[node] = cost;
    }

    private int terminalCount() {
      return terminalCount;
    }

    private static boolean same(double a, double b) {
      return a == b || Double.isInfinite(a) && Double.isInfinite(b) || Math.abs(a - b) <= EPS;
    }
  }

  private interface CancelFlag {
    boolean cancelled();
  }

  private record DiscoveryStats(int expanded) {
  }

  private record RepairStats(int pops) {
  }

  private enum ExtractionKind {
    SELECTED_TERMINAL, LOCAL_VALUE_CONTINUATION
  }

  private record Extracted(ArrayList<Integer> edgeIds, double continuation, ExtractionKind kind) {
  }

  private static final class DiscoveryHeap {
    private int[] node = new int[1024];
    private double[] priority = new double[1024];
    private double[] cost = new double[1024];
    private int size;
    private double polledCost;

    boolean isEmpty() { return size == 0; }

    void clear() {
      size = 0;
    }

    void add(int n, double p, double c) {
      ensure(size + 1);
      int index = size++;
      node[index] = n;
      priority[index] = p;
      cost[index] = c;
      siftUp(index);
    }

    int poll() {
      int result = node[0];
      polledCost = cost[0];
      int last = --size;
      if (last > 0) {
        node[0] = node[last];
        priority[0] = priority[last];
        cost[0] = cost[last];
        siftDown(0);
      }
      return result;
    }

    private void ensure(int required) {
      if (required <= node.length) {
        return;
      }
      int next = node.length << 1;
      while (next < required) {
        next <<= 1;
      }
      node = java.util.Arrays.copyOf(node, next);
      priority = java.util.Arrays.copyOf(priority, next);
      cost = java.util.Arrays.copyOf(cost, next);
    }

    private void siftUp(int index) {
      int n = node[index];
      double p = priority[index];
      double c = cost[index];
      while (index > 0) {
        int parent = index - 1 >>> 1;
        if (compare(priority[parent], cost[parent], p, c) <= 0) {
          break;
        }
        node[index] = node[parent];
        priority[index] = priority[parent];
        cost[index] = cost[parent];
        index = parent;
      }
      node[index] = n;
      priority[index] = p;
      cost[index] = c;
    }

    private void siftDown(int index) {
      int n = node[index];
      double p = priority[index];
      double c = cost[index];
      int half = size >>> 1;
      while (index < half) {
        int child = (index << 1) + 1;
        int right = child + 1;
        if (right < size && compare(priority[right], cost[right], priority[child], cost[child]) < 0) {
          child = right;
        }
        if (compare(p, c, priority[child], cost[child]) <= 0) {
          break;
        }
        node[index] = node[child];
        priority[index] = priority[child];
        cost[index] = cost[child];
        index = child;
      }
      node[index] = n;
      priority[index] = p;
      cost[index] = c;
    }

    private static int compare(double ap, double ac, double bp, double bc) {
      int order = Double.compare(ap, bp);
      return order != 0 ? order : Double.compare(ac, bc);
    }
  }

  private static final class EdgeStore {
    private static final int CHUNK_SHIFT = 18;
    private static final int CHUNK_SIZE = 1 << CHUNK_SHIFT;
    private static final int CHUNK_MASK = CHUNK_SIZE - 1;

    private final int maxEdges;
    private int chunkCount;
    private int[][] source = new int[4][];
    private int[][] nextIncoming = new int[4][];
    private short[][] primitive = new short[4][];
    private byte[][] status = new byte[4][];
    private int[][] dest = new int[4][];
    private double[][] cost = new double[4][];
    private int[][] payload = new int[4][];

    EdgeStore(int maxEdges) {
      this.maxEdges = maxEdges;
    }

    int append(int id, int sourceNode, short primitiveId) {
      ensure(id + 1);
      int chunk = chunk(id);
      int offset = offset(id);
      source[chunk][offset] = sourceNode;
      nextIncoming[chunk][offset] = -1;
      primitive[chunk][offset] = primitiveId;
      dest[chunk][offset] = -1;
      status[chunk][offset] = 0;
      cost[chunk][offset] = 0D;
      payload[chunk][offset] = 0;
      return id;
    }

    void set(int id, byte edgeStatus, int destNode, double edgeCost, int edgePayload) {
      int chunk = chunk(id);
      int offset = offset(id);
      status[chunk][offset] = edgeStatus;
      dest[chunk][offset] = destNode;
      cost[chunk][offset] = edgeCost;
      payload[chunk][offset] = edgePayload;
    }

    void linkIncoming(int id, int nextEdge) {
      nextIncoming[chunk(id)][offset(id)] = nextEdge;
    }

    int source(int id) {
      return source[chunk(id)][offset(id)];
    }

    int nextIncoming(int id) {
      return nextIncoming[chunk(id)][offset(id)];
    }

    short primitive(int id) {
      return primitive[chunk(id)][offset(id)];
    }

    byte status(int id) {
      return status[chunk(id)][offset(id)];
    }

    int dest(int id) {
      return dest[chunk(id)][offset(id)];
    }

    double cost(int id) {
      return cost[chunk(id)][offset(id)];
    }

    int payload(int id) {
      return payload[chunk(id)][offset(id)];
    }

    void release() {
      chunkCount = 0;
      source = new int[0][];
      nextIncoming = new int[0][];
      primitive = new short[0][];
      status = new byte[0][];
      dest = new int[0][];
      cost = new double[0][];
      payload = new int[0][];
    }

    private void ensure(int required) {
      if (required > maxEdges) {
        throw new HotLocalCapacityExceeded("edge", maxEdges);
      }
      int requiredChunks = required + CHUNK_MASK >>> CHUNK_SHIFT;
      ensureChunkTable(requiredChunks);
      while (chunkCount < requiredChunks) {
        allocateChunk(chunkCount++);
      }
    }

    private void ensureChunkTable(int requiredChunks) {
      if (requiredChunks <= source.length) {
        return;
      }
      int next = Math.max(4, source.length);
      while (next < requiredChunks) {
        next <<= 1;
      }
      source = java.util.Arrays.copyOf(source, next);
      nextIncoming = java.util.Arrays.copyOf(nextIncoming, next);
      primitive = java.util.Arrays.copyOf(primitive, next);
      status = java.util.Arrays.copyOf(status, next);
      dest = java.util.Arrays.copyOf(dest, next);
      cost = java.util.Arrays.copyOf(cost, next);
      payload = java.util.Arrays.copyOf(payload, next);
    }

    private void allocateChunk(int chunk) {
      source[chunk] = new int[CHUNK_SIZE];
      nextIncoming[chunk] = new int[CHUNK_SIZE];
      primitive[chunk] = new short[CHUNK_SIZE];
      status[chunk] = new byte[CHUNK_SIZE];
      dest[chunk] = new int[CHUNK_SIZE];
      cost[chunk] = new double[CHUNK_SIZE];
      payload[chunk] = new int[CHUNK_SIZE];
    }

    private static int chunk(int id) {
      return id >>> CHUNK_SHIFT;
    }

    private static int offset(int id) {
      return id & CHUNK_MASK;
    }
  }

  private static final class DStarHeap {
    private final PedestrianLocalValueField field;
    private int[] heap = new int[1024];
    private int size;

    DStarHeap(PedestrianLocalValueField field) {
      this.field = field;
    }

    boolean isEmpty() { return size == 0; }

    double peekKey() {
      return size == 0 ? Double.POSITIVE_INFINITY : key(heap[0]);
    }

    void clear() {
      size = 0;
      heap = new int[1024];
    }

    void upsert(int node) {
      ensure(size + 1);
      if (field.heapIndex[node] >= 0) {
        siftUp(field.heapIndex[node]);
        siftDown(field.heapIndex[node]);
        return;
      }
      heap[size] = node;
      field.heapIndex[node] = size;
      siftUp(size++);
    }

    void remove(int node) {
      int index = field.heapIndex[node];
      if (index < 0) {
        return;
      }
      int last = heap[--size];
      heap[index] = last;
      field.heapIndex[last] = index;
      field.heapIndex[node] = -1;
      if (index < size) {
        siftUp(index);
        siftDown(field.heapIndex[last]);
      }
    }

    int poll() {
      int result = heap[0];
      remove(result);
      return result;
    }

    private void ensure(int required) {
      if (required > heap.length) {
        heap = java.util.Arrays.copyOf(heap, heap.length << 1);
      }
    }

    private void siftUp(int index) {
      int node = heap[index];
      while (index > 0) {
        int parent = index - 1 >>> 1;
        int parentNode = heap[parent];
        if (compare(parentNode, node) <= 0) {
          break;
        }
        heap[index] = parentNode;
        field.heapIndex[parentNode] = index;
        index = parent;
      }
      heap[index] = node;
      field.heapIndex[node] = index;
    }

    private void siftDown(int index) {
      int node = heap[index];
      int half = size >>> 1;
      while (index < half) {
        int child = (index << 1) + 1;
        int right = child + 1;
        int childNode = heap[child];
        if (right < size && compare(heap[right], childNode) < 0) {
          child = right;
          childNode = heap[right];
        }
        if (compare(node, childNode) <= 0) {
          break;
        }
        heap[index] = childNode;
        field.heapIndex[childNode] = index;
        index = child;
      }
      heap[index] = node;
      field.heapIndex[node] = index;
    }

    private int compare(int a, int b) {
      int order = Double.compare(key(a), key(b));
      return order != 0 ? order : Integer.compare(a, b);
    }

    private double key(int node) {
      return Math.min(field.g[node], field.rhs[node]);
    }
  }
}
