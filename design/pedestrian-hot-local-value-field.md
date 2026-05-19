# Pedestrian Hot Local Value Field

Status: design spec for adversarial review  
Date: 2026-05-19  
Target branch: `pedestrian-and-macro-polish`

## 1. Purpose

Long-distance pedestrian pathing currently recomputes local A* from scratch for each physical or future-anchor launch. The macro layer provides long-range continuation values, and `RouteExecutor` can graft finished candidates onto the currently committed route, but the expensive local search frontier and its exact movement facts are discarded between closely related replans.

This is tolerable in easy overworld terrain and pathological in Nether-scale pedestrian travel: the bot can become compute-constrained, accept weak prefixes, chase short dead ends, and break blocks with no meaningful global improvement because the planner is repeatedly asked to act before it has preserved enough exact local value to make a stable decision.

The required replacement is a pedestrian-only hot local value layer:

```text
macro continuation field
        ↓ terminal values / lower bounds
forward exact local graph discovery
        ↓ discovered nodes, exact directed edges, terminal activations
reverse exact local dynamic value field
        ↓ validated executable prefix
RouteExecutor suffix replacement / future-anchor machinery
```

Acceptance criterion:

```text
No worse than current nether_thisway_500_locked behavior, with materially cleaner and faster replans:
  - no increased failure rate on the three locked Nether 500 pedestrian scenarios;
  - no regression to horse pathing acceptance;
  - no ungrounded best-so-far prefixes caused by local search timeout;
  - lower repeated local pathing compute under equivalent scenario and settings;
  - fewer stale candidate launches / calc-failed churn in Nether runs;
  - route behavior remains explainable as exact local prefix + macro continuation.
```

Nether pathing is the adversarial pedestrian case. If the new layer is healthy in Nether terrain, ordinary pedestrian environments should be strictly easier.

## 2. Existing Code Shape

The relevant current seams are:

```text
PathingBehavior.createPathfinder(...)
  -> MacroCoordinator.plan(...)
  -> AStarPathFinder(realStart, startX, startY, startZ, transformedGoal, favoring, context, pedestrianPolicy)
  -> Path / RouteExecutor
```

Current local A* is one-shot. `AbstractNodeCostSearch.calculate(...)` explicitly rejects reuse after the first call. `AStarPathFinder.calculate0(...)` allocates a fresh `BinaryHeapOpenSet`, fresh `PathNodeArena`, fresh `bestSoFar`, and fresh parent tree per calculation.

Current local-exit behavior is partial:

```text
if LocalExitObjective:
  A* tracks best exact local exit by cost + localExitValue(...)
  A* can return best local exit without proof on timeout
  A* publishes best local exit incumbents when one exists
else:
  A* publishes generic best-so-far coefficient paths
```

The macro layer already has the right abstract skeleton: `MacroValueField` owns `DStarLiteValueField` instances for expected and floor values. However, `MacroValueField.repair(...)` currently retargets and invalidates the whole envelope on each repair, throwing away much of the theoretical incremental advantage.

`RouteExecutor` is good enough to keep. It already owns:

```text
current route
queued next route
route reanchoring
suffix replacement
commitment frontier
future-anchor planning
stale-tail discard
transport-state transitions
render plan construction
```

The new layer should feed better pedestrian candidates into this machinery; it should not replace route execution.

## 3. Core Algorithm

Use a hybrid:

```text
pedestrian-only forward exact graph discovery
        +
reverse dynamic local value repair over discovered/cached exact edges
        +
forward extraction using selected Bellman actions
        +
RouteExecutor objective-aware route acceptance
```

The reverse field is the resident value owner, but the local Minecraft movement graph is not pre-enumerated. It is discovered by forward expansion from physical or certified-future starts, using the same exact `MovementPrimitive.evaluate(...)` logic that legacy A* uses today. Reverse repair then runs over that discovered directed graph.

The value recurrence is multi-terminal:

```text
V(u) = min(
  terminalCost(u),
  min over exact legal movement edges u -> v of edgeCost(u, v) + V(v)
)
```

Implementation state follows the standard `g/rhs/open` discipline:

```text
rhs(u): current one-step Bellman backup
g(u): committed value
open: vertices where g(u) != rhs(u)
bestSucc(u): selected exact forward successor for extraction
```

The field is reverse-resident and forward-extracted:

```text
repair values backward from terminal/local-exit/boundary states
then materialize an executable prefix by walking start -> bestSucc(start) -> ...
```

This is not forward warmed A*. Forward A* stores cost from a particular start:

```text
g_forward(node) = cost(start -> node)
```

That information becomes mostly stale when the live start or certified future anchor moves. Reverse local values store:

```text
g_reverse(node) ≈ cost(node -> best exit/goal)
```

which remains valuable across many nearby physical starts, future anchors, chunk loads, and route graft attempts.

### 3.0 Forward Discovery

Reverse repair needs discovered terminals and discovered exact edges. A query therefore starts by ensuring local graph coverage from the requested start:

```java
DiscoveryResult ensureCoverage(
  int start,
  PlanningBudget budget,
  TerminalDiscoveryPolicy terminals
) {
  forwardSeed(start);

  while (budget.hasTime() && needsMoreCoverage(start)) {
    int u = discoveryQueue.poll();

    if (terminals.finalGoal(u)) {
      activateTerminal(u, 0D, TerminalKind.FINAL_GOAL);
    }
    if (terminals.localExit(u)) {
      activateTerminal(u, localExit.localExitValue(u), TerminalKind.EXACT_LOCAL_EXIT);
    }
    if (terminals.boundaryExit(u)) {
      activateTerminal(u, boundaryPolicy.continuationValue(u), TerminalKind.BOUNDARY_EXIT);
    }

    forEachOutgoingPrimitive(u, primitive -> {
      EdgeRecord edge = edgeCache.evaluateOrGet(u, primitive);
      if (edge.status() == EdgeStatus.REACHABLE) {
        ensureNode(edge.destNode());
        discoveryQueue.addIfNew(edge.destNode());
        updateVertex(u);
        updateVertex(edge.destNode());
      } else if (edge.touchesUnknownBoundary()) {
        activateTerminal(u, boundaryPolicy.continuationValue(u), TerminalKind.BOUNDARY_EXIT);
        updateVertex(u);
      }
    });
  }

  return new DiscoveryResult(...);
}
```

The query flow is:

```text
1. choose/reuse resident pedestrian state;
2. apply world-fact deltas and macro terminal-cost deltas;
3. forward-discover or refresh exact local graph coverage from requested start;
4. repair reverse values until requested start is consistent, or budget says publish nothing;
5. extract a forward prefix through selected Bellman actions;
6. materialize a RoutePlan carrying the hot continuation value.
```

This discovery phase is mandatory. Without it, a reverse field either has no useful terminal seeds, scans far too much loaded terrain, or silently collapses back into an expensive reverse flood over guessed nodes.

### 3.1 Terminal Set

The local field is multi-terminal. A node may terminate the reverse recurrence if it is:

```text
final goal:                   terminal cost 0
exact local exit:             macro expected continuation value
loaded-world boundary exit:   macro expected continuation value
```

Formal terminal function:

```java
double terminalCost(int x, int y, int z) {
  if (terminalGoal.isInGoal(x, y, z)) {
    return 0D;
  }
  if (localExit != null && localExit.isExactLocalExit(x, y, z)) {
    return localExit.localExitValue(x, y, z);
  }
  if (boundaryPolicy.isBoundaryTerminal(x, y, z)) {
    return boundaryPolicy.continuationValue(x, y, z);
  }
  return Double.POSITIVE_INFINITY;
}
```

`localExitValue` is an objective continuation estimate, not an admissible heuristic. It may be empirical and macro-weighted.

Terminal presence and selected terminal action are different facts. A node may have finite `terminalCost(u)` and still have a better successor:

```text
terminalCost(u) > edgeCost(u, v) + V(v)
```

Such a node is terminal-capable but not the selected Bellman terminal. Extraction may stop only when the Bellman backup selected terminal stop:

```java
boolean selectedTerminal(int u) {
  return Double.isFinite(terminalCost[u]) && bestSucc[u] < 0;
}
```

Tie-break:

```text
If terminalCost(u) and the best successor value differ by at most LOCAL_VALUE_EPS,
prefer selected-terminal stop for stability unless an explicit preferred-exit bias breaks the tie.
```

### 3.2 Heuristic and Priority

For a dynamic value field, correctness depends on Bellman repair, not on speculative macro pruning.

For v1, the safest local repair key is:

```text
h = 0
```

That gives Dijkstra/LPA-style repair over discovered local exact edges and removes a whole class of moving-start heap correctness failures. Once predecessor coverage and invalidation are proven, a tighter audited local lower bound may be added.

If a D* Lite moving-start heuristic is used later, priority must use an admissible lower bound only:

```text
key(u) = [min(g, rhs) + h(start, u) + km, min(g, rhs)]
```

where `h` must be no greater than the cheapest possible exact local cost between start and `u`. A Euclidean / Chebyshev floor based on optimistic movement cost is acceptable. `MacroValueField.admissibleFloorAtBlock(...)` may only be used where its lower-bound contract is explicitly preserved. `MacroValueField.expectedContinuationAtBlock(...)` must not enter `h`.

Indexed heaps and moving-start keys interact dangerously: if `start` changes, stored heap keys may all change. Therefore:

```text
v1: use h = 0; queued keys are stable except when g/rhs changes.
v2: if h/km are enabled, moveStart must rebuild the heap or refresh stale top keys until heap order is valid.
```

Any start-agnostic LPA*-style variant must still preserve the invariant:

```text
repair may stop only when requested extraction start is locally consistent or budget policy chooses no publication
```

### 3.3 Directed Movement Graph

Minecraft movement is directed and context-sensitive. Reverse repair must never assume geometric reversibility.

Add predecessor support to `MovementPrimitive`:

```java
public interface MovementPrimitive {
  String debugName();
  DestinationSpec destinationSpec();
  void evaluate(CalculationContext ctx, int x, int y, int z, EdgeEvalScratch out);
  Movement instantiate(CalculationContext ctx, BetterBlockPos src, BetterBlockPos dest, int payload);

  default void predecessorCandidates(int destX, int destY, int destZ, PredecessorSink out) {
    DestinationSpec spec = destinationSpec();
    BlockOffset d = spec.precheckOffset();
    if (!spec.dynamicXZ() && !spec.dynamicY()) {
      out.accept(destX - d.dx(), destY - d.dy(), destZ - d.dz());
    }
  }
}
```

For dynamic primitives, enumerate a small bounded source set and verify each candidate by ordinary forward evaluation:

```text
candidate source p is a predecessor of u iff:
  primitive.evaluate(ctx, p, scratch) returns REACHABLE
  scratch destination equals u
```

This gives exact directed predecessors without duplicating movement semantics.

## 4. Ownership and Isolation

The hot local value field is pedestrian-only.

```java
if (MacroTraversalProfile.physical(context).horse()) {
  return existingHorsePlanner(...);
}
```

No hot local state, setting, incumbent policy, or tuning pool may be shared with horse route planning. Horse pathing was recently stabilized and must not become collateral damage.

Transport macro route legs may still exist around pedestrian local pathing:

```text
pedestrian exact prefix
macro certified transport route
pedestrian exact suffix
```

But the reverse local value field only owns pedestrian block movement edges.

## 5. State Identity

Do not include world fact epoch in the resident key. Fact epoch belongs to invalidation and repair; including it in identity would destroy reuse on every block/chunk update.

Resident state key:

```java
record PedestrianLocalStateKey(
  GoalIdentity terminalGoal,
  LocalObjectiveIdentity localObjective,
  MacroFieldIdentity macroField,
  DimensionIdentity dimension,
  WorldIdentity world,
  MovementProfileIdentity movementProfile,
  CostProfileIdentity costProfile,
  InventoryCapabilityIdentity inventoryCapabilities,
  ToolCapabilityIdentity toolCapabilities,
  AvoidanceIdentity avoidance,
  BorderIdentity worldBorder,
  GeofenceIdentity geofence,
  int minY,
  int maxYExclusive
) {}
```

Query key:

```java
record PedestrianLocalQuery(
  PedestrianLocalStateKey stateKey,
  BetterBlockPos requestedStart,
  BetterBlockPos physicalStart,
  PlanningAnchor anchor,
  long requestedFactEpoch,
  RouteProgress committedUntil,
  QueryKind kind
) {}
```

The resident key says “the same value field can continue existing.” The query says “extract or repair for this particular planning request.”

Identity-changing events create a new state:

```text
dimension changes
terminal goal identity changes
transport mode changes
movement catalog changes
break/place/sprint/parkour/fall policy changes
cost model or inventory/tool capability changes
avoidance policy changes if avoidance affects edge cost or legality
world border/geofence changes
macro field envelope or terminal target changes
```

Fact-changing events repair the existing state:

```text
chunk loaded
chunk unloaded
block changed
fluid/hazard facts changed
macro cell evidence changed inside same macro identity
```

Key notes:

```text
GoalIdentity must be canonical for known goal classes; do not rely on accidental Java object equality.
MovementProfileIdentity must be audited against every MovementPrimitive. If a primitive reads settings directly during evaluate/instantiate, either capture that setting or move it into CalculationContext.
InventoryCapabilityIdentity must cover throwaway availability, water-bucket capability, boat availability where pedestrian transition legality depends on it, sprint/food capability, and resource exhaustion that changes legality.
ToolCapabilityIdentity must cover break-speed-relevant tool state and enchantments; exact durability matters only if current cost/legality uses it.
AvoidanceIdentity must exist if mob/spawner/entity avoidance affects edge cost. For v1, bypass the resident value field when dynamic avoidance is enabled, or apply avoidance only as transient extraction/queue bias.
```

## 6. Data Structures

Use dense primitive arrays for node state, not resident `PathNode` objects. Do not bulk-allocate `node × primitive` edge slabs. Edge storage is append-only and sparse: one compact edge row per actually evaluated `(source, primitive)` pair, with per-source outgoing buckets and per-destination predecessor buckets.

```java
final class PedestrianLocalValueField {
  PedestrianLocalStateKey key;
  long appliedFactEpoch;

  Long2IntOpenHashMap nodeIdByKey;
  LongArrayList nodeKeys;

  double[] g;
  double[] rhs;
  int[] bestSucc;
  short[] bestPrimitive;
  int[] bestPayload;
  double[] bestEdgeCost;
  byte[] flags;
  int[] heapIndex;

  PedestrianEdgeCache edgeCache;
  DStarHeap open;
  DiscoveryFrontier discovery;
  IntArrayQueue dirty;
}
```

Block nodes use packed `long` keys:

```java
long key = BlockKey.pack(x, y, z);
```

The heap should be an indexed binary heap over dense node IDs:

```java
final class DStarHeap {
  int[] heap;
  int[] index;
  double[] k1;
  double[] k2;

  void upsert(int node, double key1, double key2);
  void remove(int node);
  int poll();
  int peek();
}
```

Do not use a duplicate-entry `PriorityQueue` for the hot local field. Duplicate queues are tolerable in small macro graphs, but local block movement graphs can reach hundreds of thousands of vertices and must be cache-friendly.

Low-level invariants:

```text
new node arrays initialize g/rhs to INF, bestSucc to -1, heapIndex to -1;
node IDs are stable for the lifetime of a resident field;
node arrays are not compacted unless every edge record and heap index is rewritten;
sparse edge buckets are invalidated before any source-node eviction;
blocked edge records carry fact fingerprints just like reachable edge records;
resident state stores packed keys, primitive ids, payloads, costs, fingerprints, and scalar metadata;
resident state must not retain CalculationContext or BlockState objects across queries;
all world reads and movement revalidations use the current query's CalculationContext.
```

## 7. Edge Cache

Every exact movement edge is lazy and cached.

```java
final class EdgeRecord {
  byte status;          // UNKNOWN, BLOCKED, REACHABLE
  int sourceNode;
  short primitive;
  int destNode;
  double cost;
  int payload;
  long factFingerprint;
  int generation;
}
```

Cache key:

```text
(source node id, primitive index)
```

The v1 representation should be sparse by default:

```text
edge id -> source node, primitive id, status, dest node, cost, payload, fingerprint
source node -> evaluated outgoing edge ids
dest node -> reachable predecessor edge ids
```

This keeps the resident node cap honest. A graph may reserve millions of potential nodes without paying for all possible outgoing primitives. Dense `sourceNode * primitiveCount + primitiveIndex` edge slabs are forbidden unless profiling proves that primitive count and resident cap make the product benign.

Blocked edges should be cached. Repeated impossible moves are common in Nether terrain; paying full evaluation each time is waste.

### 7.1 Edge Validation

Before an edge contributes to `rhs`, extraction, or route construction:

```java
EdgeRecord edge = edgeCache.getOrEvaluate(source, primitive);
if (!edge.validFor(currentFactFingerprint)) {
  EdgeRecord updated = evaluate(source, primitive);
  if (!sameReachabilityAndCost(edge, updated)) {
    edgeCache.put(updated);
    updateVertex(source);
    forEachPredecessor(source, this::updateVertex);
  }
}
```

First implementation may use coarse invalidation:

```text
block changed     -> invalidate edges whose source/destination/swept neighborhood is within fixed radius
chunk loaded      -> invalidate chunk interior + border halo
chunk unloaded    -> invalidate chunk interior + border halo; create boundary terminals
```

The coarse radius is safe only if it is a proven upper bound on every primitive dependency footprint. Add a primitive-level contract:

```java
interface MovementPrimitive {
  InvalidationFootprint maxInvalidationFootprint(CalculationContext ctx);
  default void actualFootprint(CalculationContext ctx, int x, int y, int z, FootprintSink out) {}
}
```

The v1 global bound must cover:

```text
source block;
destination block;
body/head clearance;
support blocks;
break/place targets;
fall columns;
parkour swept space;
diagonal and oblique clearance;
water/lava/fluid dependencies;
bucket placement dependencies;
world-border and geofence effects.
```

Fine-grained movement footprints are desirable, but not required for v1. Over-invalidation is acceptable; stale acceptance is not. If no safe coarse bound can be proven, mark all resident edges stale on fact change or fall back for that query.

## 8. Bellman Repair

Core update:

```java
void updateVertex(int u) {
  double best = terminalCost(u);
  int bestV = -1;
  short bestMove = -1;
  int bestPayload = 0;
  double bestCost = Double.POSITIVE_INFINITY;

  forEachValidatedSuccessor(u, (v, primitive, cost, payload) -> {
    double candidate = cost + g[v];
    if (candidate < best) {
      best = candidate;
      bestV = v;
      bestMove = primitive;
      bestPayload = payload;
      bestCost = cost;
    }
  });

  rhs[u] = best;
  bestSucc[u] = bestV;
  bestPrimitive[u] = bestMove;
  bestPayload[u] = bestPayload;
  bestEdgeCost[u] = bestCost;

  if (same(g[u], rhs[u])) {
    open.remove(u);
  } else {
    open.upsert(u, key1(u), key2(u));
  }
}
```

Repair to requested start:

```java
RepairResult repairUntilConsistent(int start, PlanningBudget budget) {
  int pops = 0;
  while (budget.hasTime()
      && (open.topKeyLessThan(key(start)) || !same(g[start], rhs[start]))) {
    int u = open.poll();
    pops++;

    if (g[u] > rhs[u]) {
      g[u] = rhs[u];
      forEachPredecessor(u, this::updateVertex);
    } else {
      g[u] = INF;
      updateVertex(u);
      forEachPredecessor(u, this::updateVertex);
    }
  }
  return new RepairResult(same(g[start], rhs[start]), pops, open.size(), g[start]);
}
```

If budget expires before the requested start is consistent, the field may keep state but must not publish an ungrounded candidate as an improvement.

## 9. Path Extraction

Extraction is forward and finite:

```java
Optional<HotLocalPath> extract(int start, ExtractionPolicy policy) {
  if (!consistent(start) || !Double.isFinite(g[start])) {
    return Optional.empty();
  }

  ArrayList<ExtractedEdge> edges = new ArrayList<>();
  int u = start;

  while (!selectedTerminal(u) && edges.size() < policy.maxMovements()) {
    int v = bestSucc[u];
    if (v < 0) {
      return Optional.empty();
    }

    EdgeRecord edge = edgeCache.revalidate(u, bestPrimitive[u]);
    if (edge.status != REACHABLE || edge.destNode != v) {
      updateVertex(u);
      return Optional.empty();
    }

    edges.add(edge.toExtractedEdge());
    u = v;
  }

  if (selectedTerminal(u)) {
    return materialize(edges, terminalCost[u], TerminalKind.SELECTED_TERMINAL);
  }

  if (edges.size() < policy.minMovements()) {
    return Optional.empty();
  }

  if (!consistent(u) || !Double.isFinite(g[u])) {
    return Optional.empty();
  }

  return materialize(edges, g[u], TerminalKind.LOCAL_VALUE_CONTINUATION);
}
```

Materialization may initially build ephemeral `PathNode` chains for compatibility with `Path`, `PathRouteLeg`, and `RouteExecutor`. The resident field must not store `PathNode` objects.

There are two publishable endpoint kinds:

```text
SELECTED_TERMINAL:
  extraction reached the Bellman-selected terminal action; continuation is terminalCost[u].

LOCAL_VALUE_CONTINUATION:
  extraction stopped at policy.maxMovements before a terminal, but u is consistent and g[u] is finite;
  continuation is g[u], a certified local value continuation, not a local-exit handoff.
```

Nonterminal extracted prefixes must be named honestly. They are exact executable prefixes with resident local continuation value; they are not final paths and not local exits.

Generic postprocessing/cutoff must not silently change the objective endpoint. Hot materialization policy for v1:

```text
the value-field extraction policy decides prefix length;
generic static cutoff is disabled for hot extracted prefixes;
if any postprocessing changes the extracted endpoint, reject the hot candidate or recompute continuation at the actual endpoint before publication.
```

The extracted candidate carries objective metadata:

```java
record HotLocalPath(
  IPath path,
  double exactPrefixCost,
  double terminalContinuationCost,
  double totalObjective,
  boolean startConsistent,
  boolean terminalCertified,
  long factEpoch,
  HotLocalTelemetry telemetry
) {}
```

Candidate acceptance should compare `totalObjective` where available instead of reconstructing the objective from only `path.dest()` and `Goal.heuristic`.

## 10. Incumbent Publication

For pedestrian macro/local-exit planning, generic coefficient best-so-far incumbents are not acceptable. They encode “made some progress from start,” not “has a defensible objective continuation.”

The hot local planner may publish only:

```text
path to final goal
path to exact local exit
path to boundary terminal
path whose terminal has finite certified continuation and whose first edge sequence has been revalidated
```

Budget exhaustion result:

```text
consistent start + extracted objective-backed prefix -> publish candidate
inconsistent start + previous route still valid        -> publish nothing
inconsistent start + current route invalid             -> fallback A* or fail closed
```

No “short dead end because the clock rang.”

Definition of publishable objective-backed hot candidate:

```text
1. every executable movement was revalidated under the current query facts;
2. the candidate anchors to physical/current/future execution under RouteExecutor rules;
3. the endpoint is either:
     a selected terminal with finite terminal continuation;
     or a nonterminal node with finite, consistent g[u] local continuation;
4. the RoutePlan carries this continuation through estimatedContinuationTicks;
5. total objective improves the incumbent by the configured margin.
```

## 11. RouteExecutor Integration

`RouteExecutor` remains the authority on:

```text
physical reanchor
current execution anchor
future anchor
suffix replacement
commitment horizon
queued next route
transport mode transitions
```

Add a pedestrian planner store to `PathingBehavior`:

```java
private final PedestrianLocalHotPlanner pedestrianHotPlanner = new PedestrianLocalHotPlanner();
```

Creation flow:

```java
if (profile.horse()) {
  return new HorseCalculation(...);
}

if (settings.pedestrianHotLocalValueField.value) {
  return new CreatedHotPathfinder(
    pedestrianHotPlanner.query(
      context,
      physicalStart,
      requestedStart,
      terminalGoal,
      transformedGoal,
      macroPlan,
      planningAnchor,
      worldFactEpoch,
      current == null ? RouteProgress.origin() : current.commitmentEnd(...)
    ),
    terminalGoal,
    macroPlan,
    immediateRoute
  );
}

return legacy AStarPathFinder(...);
```

`PathingBehavior.inProgress` should be generalized from `AbstractNodeCostSearch` to a dedicated calculation interface, because the hot planner is not a node-cost search and needs richer probes/publications than a plain `IPathFinder`:

```java
interface ActivePathCalculation {
  BetterBlockPos getStart();
  Goal getGoal();
  void cancel();
  boolean isFinished();

  PathCalculationResult calculate(long primaryTimeout, long failureTimeout);

  Optional<IPath> bestPathSoFar();
  Optional<PlanningProbe> probe();
  void setPublicationSink(PathPublicationSink sink);
}
```

Legacy `AStarPathFinder` can adapt to this interface. Hot local calculations should use a distinct publication path that carries objective metadata:

```java
record HotLocalCandidate(
  RoutePlan route,
  Goal terminalGoal,
  double exactPrefixCost,
  double continuationCost,
  double totalObjective,
  PlanningAnchor anchor,
  long factEpoch,
  HotLocalTelemetry telemetry
) {}
```

Do not materialize hot candidates as a plain legacy route:

```java
new RouteExecutor(this, path) // wrong for value-certified nonterminal prefixes
```

That discards hot continuation value. Instead:

```java
RoutePlan route = RoutePlan.of(
  List.of(PathRouteLeg.connector(path, pedestrian, pedestrian)),
  pedestrian,
  pedestrian,
  hotLocalPath.terminalContinuationCost()
);
new RouteExecutor(this, route, terminalGoal);
```

This lets `RouteExecutor.estimatedContinuationTicks()` preserve the resident value field’s continuation cost during acceptance.

Candidate acceptance remains anchored:

```text
candidate must reanchor to physical execution, current execution, committed suffix boundary, or certified future anchor
```

The hot layer improves candidate quality; it does not relax anchoring.

## 12. Macro Interaction

Macro is long-range continuation estimation and certified transport planning. It is not the owner of exact pedestrian executability.

Use macro values in three distinct ways:

```text
expectedContinuationAtBlock -> terminal objective value
admissibleFloorAtBlock      -> lower-bound heuristic only when safe
preferredLocalExitPath      -> rendering / bias / telemetry, never sole target
```

The local value field should consider all legal exact local exits and boundary terminals. `preferredLocalExit()` may bias tie-breaking, but must not collapse the terminal set to one macro-chosen point.

Because expected macro values are terminal objective values, macro changes can change local Bellman backups. The hot local field needs explicit macro-terminal invalidation:

```java
void applyMacroValueDelta(MacroValueDelta delta) {
  for (int node : discoveredNodesIn(delta.changedCells())) {
    if (terminalStatusOrCostMayHaveChanged(node)) {
      updateVertex(node);
      forEachPredecessor(node, this::updateVertex);
    }
  }
}
```

`MacroValueDelta` must include:

```text
factual/nonfactual status changes;
center or adjusted-value changes;
surface/water evidence changes;
expected value changes;
floor value changes;
exactLocalExitAtBlock eligibility changes.
```

In Nether, edge cost can remain uniform while factual status changes still alter which cells are legal local exits.

### 12.1 Macro Repair Fix

Replace full-envelope invalidation in `MacroValueField.repair(...)` with diff repair.

Desired shape:

```java
void repair(MacroAtlas nextAtlas, long nextStart, double expectedTerminal, double floorTerminal) {
  MacroAtlasDiff diff = atlas.diff(nextAtlas, expectedGraph.envelope());

  atlas = nextAtlas;
  expectedGraph.retarget(nextAtlas);
  floorGraph.retarget(nextAtlas);
  expected.moveStart(nextStart);
  floor.moveStart(nextStart);

  if (terminalChanged(expectedTerminal)) {
    expected.setTerminal(target, expectedTerminal);
  }
  if (terminalChanged(floorTerminal)) {
    floor.setTerminal(target, floorTerminal);
  }

  for (long changed : diff.changedCells()) {
    expected.invalidate(changed);
    floor.invalidate(changed);
  }

  expectedRepair = expected.repair(budget);
  floorRepair = floor.repair(budget);
}
```

Full-envelope invalidation remains valid only for identity changes:

```text
target changed
envelope changed
scale/cell size changed
dimension changed
policy/profile changed
```

Horse safety note: the hot local value field itself is horse-isolated by hard profile gate, but `MacroValueField` is shared by pedestrian and horse macro guidance. Therefore macro diff repair needs its own proof:

```text
either gate diff repair to pedestrian profile first;
or prove semantic equivalence to full-envelope invalidation;
or run horse acceptance with macro diff repair enabled before claiming horse isolation.
```

## 13. World Fact Deltas

Introduce an explicit delta type:

```java
record WorldFactDelta(
  long epoch,
  LongList loadedChunks,
  LongList unloadedChunks,
  LongList changedBlocks
) {}
```

The field keeps `appliedFactEpoch`. On query:

```text
collect deltas from appliedFactEpoch exclusive to requestedFactEpoch inclusive
apply invalidations
repair or leave inconsistent under budget
update appliedFactEpoch only for deltas actually applied
```

If the current code lacks precise deltas, v1 may synthesize coarse deltas from the existing global fact epoch and loaded-chunk observations:

```text
same epoch       -> no fact invalidation
epoch advanced with precise/chunk-level deltas
                 -> apply conservative invalidation from those deltas
epoch advanced but precise deltas unavailable
                 -> mark every resident edge stale, discard the resident field, or force legacy fallback for this query
```

Route-footprint-only invalidation is not safe for a resident graph cache. The cache may contain useful edges far outside the currently executing route but inside the reusable local graph. If a block change affects one of those edges and the delta is lost, later extraction may reuse stale reachability.

Safe fallback choices:

```text
precise block/chunk deltas available:
  invalidate touched edge footprints / halo;

epoch advanced but precise deltas unavailable:
  mark all resident edge records stale;
  or discard the resident field;
  or force legacy A* fallback for that query.
```

Unknown deltas may cost performance. They may not cost correctness.

## 14. Memory and Eviction

Resident local fields are powerful and dangerous. They need hard caps:

```text
max resident pedestrian states per Baritone instance: 2
max nodes per state: generous memory fuse, not a planning horizon; default should be millions, not hundreds of thousands
max edge records per state: generous sparse cap; default should assume tens of millions are acceptable if the machine has heap
max idle ticks: LRU eviction
max repair pops per tick/calculation: budgeted
```

Caps are not allocation requests. The resident field must grow lazily and chunk sparsely. A one-gigabyte resident cache is acceptable for serious Nether planning; a one-gigabyte eager allocation on `setGoal` is not. The v1 Java layout is still bounded by ordinary object-array and fastutil-map realities: millions of nodes and tens of millions of evaluated edge rows are reasonable fuses, while 50M resident nodes require a later packed arena with a custom open-addressed `long -> int` map, chunked node columns, and object-free adjacency. Do not advertise 50M-node settings until the actual bytes/node justify it.

On cap pressure:

```text
stop expanding;
preserve already valid values;
publish only if requested start is consistent and extractable;
otherwise fall back or keep current route;
never corrupt arrays or silently drop edges participating in g/rhs without invalidating vertices.
```

## 15. Telemetry

Add telemetry before and during implementation. Required counters:

```text
planner mode: LEGACY_ASTAR / HOT_LOCAL_VALUE / HOT_LOCAL_FALLBACK
state key reuse hits/misses
resident nodes
resident edges
blocked edge cache hits
reachable edge cache hits
edge revalidations
invalidated vertices
repair queue pops
open size after repair
start consistent yes/no
extracted prefix length
exact prefix cost
terminal continuation cost
total objective
candidate accepted/rejected reason
suffix graft success/failure reason
stale candidate discards
macro repair changed cells
macro repair queue pops
discovery nodes expanded
discovery terminals activated by kind
selected-terminal vs value-certified extraction
nonterminal continuation g[u] used yes/no
postprocess endpoint changed yes/no
legacy fallback reason
lost-delta full invalidation count
macro terminal-cost invalidations
predecessor oracle failures by primitive
heap stale-key refreshes or heap rebuilds
edge cache invalidations by cause: block/chunk/macro/profile/cap
hot route objective carried through RoutePlan yes/no
```

These should surface in playtest summaries and profiler output. If the new algorithm works, the Nether 500 traces should show fewer repeated de novo expansions and fewer route candidates rejected for stale or weak anchoring.

## 16. Settings

Do not expose a zoo of user-facing knobs.

Internal settings:

```text
pedestrianHotLocalValueField: boolean, default on once accepted
pedestrianHotLocalMaxNodes
pedestrianHotLocalMaxEdges
pedestrianHotLocalRepairBudget
pedestrianHotLocalExtractionMaxMovements
pedestrianHotLocalFallbackEnabled
```

Not tunable:

```text
heuristic correctness
world fact invalidation correctness
terminal definitions
horse isolation
failure timeout semantics
```

The acceptance threshold is not “find magic constants.” Constants may be tuned only after the algorithmic invariants hold.

Legacy A* fallback should remain available as an emergency path for:

```text
memory cap hit;
predecessor oracle failure;
lost fact deltas;
inconsistent start under failure timeout;
unsupported movement primitive;
debug comparison mode.
```

Fallback is not the primary pedestrian macro path, but deleting it is not worth the operational risk.

## 17. Testing

### 17.1 Required Regression Suite

Run:

```text
all non-frontier pedestrian tests
existing macro smoke tests
horse acceptance suite sufficient to prove isolation
nether_thisway_500_locked trio
```

Nether metric:

```text
all three runs complete or match current success envelope;
geometric mean wallclock no worse than baseline;
calc-failed/stale-launch counts materially lower than baseline;
no repeated motion opposite the macro/local objective unless exact route objective justifies it;
no visible “break blocks aimlessly” behavior from ungrounded timeout prefixes.
```

### 17.2 Comparative Oracle

For bounded loaded-world cases, compare hot field extraction with legacy A*:

```text
if legacy A* finds a path under generous budget and no fact changes occur,
hot field should find an equal-or-better objective path or explain why memory/budget cap stopped it.
```

Because movement graph predecessor enumeration is the riskiest part, add debug mode:

```text
for every reachable edge u -> v produced by forward discovery,
  ask primitive.predecessorCandidates(v, ...)
  require that u is included and forward-evaluates back to v;
sample extracted hot edges
ask legacy forward evaluator to verify each edge
sample nodes with finite hot value
try bounded forward A* from those nodes to a hot terminal
report missing predecessor candidates where legacy finds a cheaper route through an unrepresented predecessor
```

### 17.3 Invalidation Oracle

Synthetic tests:

```text
edge becomes blocked by placed block -> value increases or path changes
blocked edge becomes reachable by broken block -> value decreases or path changes
chunk load removes boundary terminal -> value repairs inward/outward correctly
chunk unload creates boundary terminal -> extraction can terminate at boundary
```

No stale edge may survive revalidation into materialized execution.

The strongest predecessor invariant is:

```text
For every reachable edge u -> v discovered forward, predecessorCandidates(v) for that primitive must include u.
```

The most dangerous primitives are:

```text
fall / dynamic-Y descent;
water-bucket fall;
DOWNWARD and mining-underfoot transitions;
parkour variants;
diagonal descend / ascend;
oblique stride primitives;
any primitive whose instantiate() can revalidate destination differently from evaluate().
```

## 18. Failure Modes

| Failure | Consequence | Guard |
|---|---|---|
| predecessor enumeration misses a legal source | hot field falsely pessimistic | legacy comparison oracle; dynamic predecessors verified forward |
| predecessor enumeration admits impossible source | stale/invalid value | every predecessor candidate is verified with `evaluate(...)` |
| expected macro value used as heuristic | incorrect pruning / bad optimality claims | type split expected objective vs admissible floor |
| edge cache ignores fact changes | walks through changed blocks | fact fingerprints and revalidation before extraction |
| lost fact delta reused through resident graph | stale off-route cached edge becomes executable later | full edge stale mark, resident discard, or fallback |
| graph discovery fails to activate terminals | reverse field never finds useful value | forward discovery coverage metrics and terminal activation telemetry |
| extraction stops at any terminal instead of selected terminal | stops at inferior first exit | selected-terminal invariant |
| nonterminal prefix loses continuation | route acceptance compares wrong objective | carry g[u] continuation through RoutePlan |
| generic static cutoff changes endpoint | continuation value attached to wrong route dest | disable cutoff or reject/recompute when endpoint changes |
| memory cap silently drops live value state | corrupt path extraction | explicit invalidation / fallback on cap |
| generic best-so-far still publishes in macro pedestrian mode | dead-end execution | objective-backed incumbent policy only |
| route acceptance bypasses anchors | teleport-like suffix graft | keep `RouteExecutor` anchoring as authority |
| horse accidentally uses hot local field | mounted regression | hard profile gate; separate settings; horse acceptance tests |
| macro diff repair changes horse macro behavior | mounted regression despite hot-field bypass | profile-gate diff repair or prove equivalence and run horse acceptance |

## 19. Non-Goals

Do not implement these as part of the first accepted version:

```text
global all-dimensions route planning rewrite
transport macro rewrite
horse pathing changes
ARA*/AD*/focal search
speculative learned heuristics
seed-predicted Nether terrain for pedestrian walking
perfect fine-grained block-footprint invalidation
full replacement of RouteExecutor
```

The goal is exact pedestrian hot local value reuse, not a new universal navigation stack.

## 20. Implementation Landmarks

Although this should land as one coherent push rather than a long compatibility procession, the internal dependency order is:

```text
1. Add ActivePathCalculation interface.
2. Add precise WorldFactDelta ring buffer or a safe lost-delta fallback policy.
3. Add hard pedestrian/horse split for hot local entry.
4. Add state/query identity and telemetry shell.
5. Add dense node store, discovery frontier, edge cache, and indexed heap with h = 0.
6. Add MovementPrimitive predecessorCandidates and the discovered-edge predecessor oracle.
7. Add terminal activation and forward exact graph discovery coverage.
8. Add reverse LPA/D* Bellman repair over discovered edges.
9. Add selected-terminal and value-certified extraction.
10. Materialize hot candidates as RoutePlan with explicit continuation metadata.
11. Disable generic pedestrian macro/local-exit coefficient best-so-far publication.
12. Add macro terminal-delta invalidation for local discovered terminals.
13. Add MacroValueField diff repair, profile-gated or equivalence-tested.
14. Add telemetry and playtest summary fields.
15. Enable on the Nether 500 locked trio.
16. Run horse acceptance with hot local bypass and macro diff setting enabled.
17. Keep legacy A* fallback as an emergency/debug path, but not as the primary pedestrian macro planner.
```

The scary diff is acceptable. The final code should look designed top-down for the new invariant, not like A* reuse duct-taped onto `AStarPathFinder`.

## 21. Review Questions

An adversarial reviewer should answer:

```text
1. Is multi-terminal reverse D* Lite/LPA* the right algorithmic family for this exact movement graph?
2. Is forward exact graph discovery sufficient to seed terminals without degenerating into de novo A* every query?
3. Is the terminal-cost decomposition mathematically sound when macro expected values are empirical?
4. Is the expected/floor split sufficient to prevent non-admissible heuristic misuse?
5. Is predecessor generation + forward verification enough for every current MovementPrimitive?
6. What current Baritone movement primitive is most likely to break reverse repair assumptions?
7. Is coarse invalidation safe enough for v1, and where is it likely to over-invalidate catastrophically?
8. Does selected-terminal / value-certified extraction avoid premature local-exit stops and weak prefixes?
9. Are the proposed state keys too broad, too narrow, or missing inventory/tool/world facts?
10. Is `RouteExecutor` integration preserving commitment semantics and hot continuation values cleanly?
11. What is the simplest proof that horse pathing cannot regress from hot local bypass and macro diff repair?
12. Which telemetry counters are mandatory to diagnose Nether failures?
13. What should be deleted from the current A* path after this lands?
```

## 22. Summary

The durable design is:

```text
macro D* continuation values
        +
pedestrian forward exact local graph discovery
        +
pedestrian reverse exact local dynamic value field
        +
forward validated prefix extraction
        +
existing route grafting and commitment
```

This directly targets the observed Nether pathology. It preserves exact local movement semantics, prevents timeout-driven weak prefixes, reuses pathing work across frequent related starts, and lets macro values guide exit selection without letting macro hallucinate executable local geometry.
