# Long-Distance Route Planning

Status: design note  
Motivation: reliable 3k+ Chebyshev-scale routing under partial world knowledge  
Primary irritant: Nether walking paths that commit into pockets near magma/lava seas and then recover only through expensive stop-the-world replans

## Thesis

Baritone already has a good local truth machine. Given a bounded, known neighborhood, the walking A* can answer a precise question: which currently legal movement sequence reaches the local target under the current settings snapshot?

Ultra-long routing fails because that is the wrong sole question. At 3k+ blocks, especially in the Nether, correctness is dominated by epistemology: unknown chunks, partially cached chunks, disconnected cave components, lava-ocean basins, bad y-bands, and route commitment into cul-de-sacs. Making local A* search harder only finds deeper dead ends.

The Pareto move is a persistent global route planner that produces a coarse route corridor, while the existing exact movement A* solves short local prefixes inside that corridor. The executor should own only a short committed prefix; a background planner should continuously refine the mutable suffix.

## Non-Goals

This is not Nether-specific. Nether terrain motivates the work, but the abstraction must cover Overworld mountains, oceans, boats, Elytra-adjacent routing, generated terrain, and future movement catalogs.

This is not an optimality relaxation for local movement legality. The walking movement catalog remains authoritative for exact block-level execution.

This is not merely “increase timeouts.” Expensive planning is acceptable, including dedicating one CPU core to route refinement, but compute must buy global route quality rather than longer local flailing.

This is not a seed-prediction feature first. Worldgen prediction is powerful later, but the first abstraction is global route memory plus corridor-constrained exact local execution.

## Current Shape

The current `PathingBehavior` owns:

- `current`: the executing `PathExecutor`
- `next`: one planned-ahead segment
- `inProgress`: a single background `AbstractNodeCostSearch`

This is a tick/tock model:

1. Search for a segment.
2. Execute it mostly frozen.
3. Near the end, plan one next segment.
4. If execution fails or the segment dead-ends, stop and recalculate.

That model is acceptable for local travel, but poor for long-distance traversal through partial terrain knowledge. It can repeatedly discover that the same macro-region is bad only after walking into it.

## Desired Shape

Introduce a persistent planning service:

```java
record PathPlan(
  long revision,
  Goal goal,
  BetterBlockPos anchor,
  MacroRoute macro,
  CommittedPrefix committed,
  Optional<CandidateReplacement> candidate
) {}
```

The executor consumes `CommittedPrefix`: a short, safe, exact local path. The planner mutates everything beyond that prefix:

- discovers or generates terrain knowledge
- summarizes chunks into connected walk components
- plans a macro route through component exits
- asks exact local A* to solve through the next route gates
- proposes replacement prefixes at safe splice points
- records failed macro edges/components so they are not rediscovered indefinitely

This turns pathing into an anytime system. More CPU time improves route quality, but the player need not freeze while the suffix improves.

## Layer 1: World Knowledge

Represent terrain at multiple resolutions.

```java
record ChunkKey(ResourceKey<Level> dimension, int cx, int cz) {}

sealed interface ChunkKnowledge {
  record Observed(ChunkSummary summary) implements ChunkKnowledge {}
  record Generated(ChunkSummary summary) implements ChunkKnowledge {}
  record Unknown() implements ChunkKnowledge {}
}

record ChunkSummary(
  ChunkKey key,
  WalkComponent[] components,
  HazardField hazards,
  long revision
) {}
```

`Observed` means the client has loaded or cached the chunk. `Generated` means Baritone synthesized terrain from trusted worldgen inputs. `Unknown` means the route planner has no local facts and must reason with priors.

`ChunkSummary` should not encode a full block graph. It should answer coarse questions quickly:

- Which standable connected components exist in this chunk?
- Which components touch chunk borders?
- Which y-bands are plausibly traversable?
- Which border intervals are exits?
- Which areas are lava, magma, powder-snow-like hazards, void, water, cliffs, or open air?
- Does this chunk look like a cave pocket, bridgeable ocean, tunnel, shelf, or vertical shaft?

Suggested types:

```java
record ComponentId(ChunkKey chunk, int index) {}

record WalkComponent(
  ComponentId id,
  int minY,
  int maxY,
  BorderPortalSet exits,
  ComponentTraits traits,
  CostVector intrinsicCost
) {}

record BorderPortal(
  Direction direction,
  int from,
  int to,
  int minY,
  int maxY,
  HazardProfile hazard
) {}
```

The summary should be revisioned. Block updates, chunk loads, and repacks invalidate affected summaries and their adjacent border edges.

## Layer 2: Macro Graph

Construct a graph over components and border portals:

- Node: a walkable component within a chunk.
- Edge: an intra-chunk transition or inter-chunk border transition.
- Edge cost: distance, y-change, hazard, uncertainty, required capabilities, and expected local-search difficulty.

```java
record MacroNode(ComponentId component) {}

record MacroEdge(
  MacroNode from,
  MacroNode to,
  RouteGate exitGate,
  RouteGate entryGate,
  CostVector cost,
  KnowledgeConfidence confidence
) {}

record CostVector(
  double distance,
  double verticality,
  double hazard,
  double uncertainty,
  double construction,
  double expectedLocalDifficulty
) {}
```

Use a scalarized cost for A*, but retain the vector for diagnostics and policy. Settings can alter scalarization without losing provenance.

Unknown chunks should be traversable with finite but high uncertainty cost. Generated chunks should be cheaper than unknown if generation is trusted. Known bad components should become very expensive or forbidden depending on failure severity.

The macro planner should produce:

```java
record MacroRoute(
  List<RouteGate> gates,
  RouteCorridor corridor,
  CostVector cost,
  KnowledgeConfidence confidence
) {}
```

The route corridor is more important than the exact gate sequence. It gives local A* freedom to exploit real terrain while preventing it from wandering into unrelated basins.

## Layer 3: Corridor-Constrained Exact A*

Existing block-level A* remains the execution authority. It receives a local goal derived from the macro route:

- next gate
- next few gates
- local waypoint inside the corridor
- final goal if close enough

The local planner should be biased or constrained by the corridor. Biasing is safer as the first step; hard constraints can strand the planner if the macro summary is stale.

Useful modes:

- `Favor`: outside-corridor nodes are legal but costly.
- `Fence`: outside-corridor nodes are illegal except near the current player, goal, or recovery radius.
- `Probe`: local A* tests whether a macro edge is actually realizable and reports structured failure.

Structured local failure matters more than raw cancellation:

```java
sealed interface LocalRouteFailure {
  record NoLoadedPath(RouteGate from, RouteGate to) implements LocalRouteFailure {}
  record DisconnectedComponent(ComponentId component) implements LocalRouteFailure {}
  record HazardBarrier(HazardProfile hazard, RouteGate near) implements LocalRouteFailure {}
  record RequiresConstruction(RouteGate near, ConstructionKind kind) implements LocalRouteFailure {}
  record Timeout(RouteGate from, RouteGate to, int nodesConsidered) implements LocalRouteFailure {}
}
```

These failures feed back into the macro graph. The planner should learn that an edge/component is bad instead of rediscovering the same local impossibility after walking there.

## Layer 4: Anytime Plan Supervisor

Replace the two-slot `current`/`next` mental model with a plan supervisor. The first implementation may internally bridge to `current`/`next`, but the semantic model should be richer:

```java
final class PlanningService {
  PathPlan snapshot();
  void setGoal(Goal goal, PlanningPolicy policy);
  void observe(WorldKnowledgeDelta delta);
  void reportExecutionProgress(ExecutionCursor cursor);
  void reportExecutionFailure(ExecutionFailure failure);
  Optional<CommittedPrefix> pollExecutablePrefix();
}
```

The service owns a planner thread or executor. With a full CPU core available, it can keep several searches alive conceptually:

- macro route refinement
- exact path to the next gate
- exact path through the next several gates
- speculative alternative macro corridor
- recovery route from the current stable point

Do not let this become untyped thread soup. Use a small internal job graph:

```java
sealed interface PlanningJob {
  record RefreshChunkSummaries(Set<ChunkKey> chunks) implements PlanningJob {}
  record ImproveMacroRoute(PlanningRevision revision) implements PlanningJob {}
  record SolveLocalPrefix(PlanningRevision revision, RouteCorridor corridor, BetterBlockPos start) implements PlanningJob {}
  record ValidateMacroEdge(PlanningRevision revision, MacroEdge edge) implements PlanningJob {}
}
```

Every result must carry the revision it was computed against. Stale results may still be diagnostically useful, but must not blindly replace current plan state.

## Execution Semantics

Execution should commit only a short prefix. The prefix length should depend on movement risk:

- flat walking: longer prefix is fine
- parkour, bridging, lava-adjacent movement: shorter prefix
- unknown/generative corridor: shorter prefix
- high-confidence observed corridor: longer prefix

Splicing rules:

- Only splice at safe movement boundaries or explicit movement-defined splice points.
- Never replace the movement currently under the player unless the movement says it is safe.
- Prefer replacing suffixes behind the current local gate, not the current movement.
- If a candidate prefix intersects the current valid position set, snap forward instead of stopping.

This generalizes the current `snipsnapifpossible` behavior into a real contract.

## Terrain Loading And Generation

This feature is allowed to load or generate chunks, but those are separate knowledge sources with different trust levels.

Observed chunk:

- highest confidence
- exact block states
- can be summarized immediately

Cached chunk:

- high confidence, but may be stale
- exact enough for macro planning
- local A* should verify before execution

Generated chunk:

- confidence depends on seed, generator, dimension, version, and server modifications
- good for avoiding enormous Nether basins before they enter render distance
- must not be used as exact execution truth unless verified

Unknown chunk:

- finite uncertainty cost
- routeable only because otherwise long-distance planning becomes impossible
- should trigger exploration/generation probes when near the best route

The first implementation should not require generation. It should work with observed plus cached summaries, then add generated summaries as an optimization.

## Nether Motivation

Nether walking gets stuck because the local planner sees a reachable shelf/cave/pocket and commits toward the goal heuristic. The global fact is that the shelf may be a basin boundary with no acceptable exit except backtracking hundreds of blocks.

The macro summary should make this legible:

- giant lava seas become high-cost hazard fields
- component exits around seas become scarce and valuable
- cave pockets without border exits become dead components
- y-band changes become explicit rather than accidental
- repeated local failures poison macro edges/components

The target behavior is not “never hit lava.” It is “recognize that an apparently goalward shelf is globally bad before spending ten minutes walking into it.”

## Infra Work To Do First

1. Add final path composition profiling.

   Current profiles record movement candidate evaluation. Add selected movement histograms for final paths:

   ```java
   record SelectedMovementStats(String movementClass, int count, double costSum) {}
   ```

   This gives exact attribution for features like oblique walking and future corridor planning.

2. Add structured path calculation outcomes.

   Replace stringly stop reasons with typed reasons that can feed planner memory:

   ```java
   sealed interface SearchStopReason {
     record GoalReached() implements SearchStopReason {}
     record EmptyChunkLimit() implements SearchStopReason {}
     record Timeout(boolean hadAnyPath) implements SearchStopReason {}
     record ExhaustedOpenSet() implements SearchStopReason {}
     record Cancelled() implements SearchStopReason {}
   }
   ```

3. Split planning state from execution state.

   Introduce `PathPlan` and `PlanningService` behind current behavior. Initially preserve behavior: one current prefix and one next prefix. This is scaffolding, not feature behavior yet.

4. Add chunk-summary cache.

   Start with observed/cached chunks only. Produce standable connected components and border exits. Persist summaries only if invalidation is exact enough; otherwise keep them memory-only at first.

5. Add macro-route diagnostics.

   Before it controls execution, render or dump the macro route and chunk-component decisions. If the macro planner cannot explain why it avoids or enters a basin, it is not ready to steer.

6. Add local gate goals.

   Teach exact A* to path to route gates/corridors, not only block or XZ goals. First implementation can use composite goals over gate cells.

7. Add failure memory.

   Convert local A* failures into macro penalties. This is the first correctness improvement that directly attacks Nether pocket repetition.

## Algorithm Choice

For the first version, ordinary A* over the macro graph is sufficient. The graph is much smaller than the block graph, and the first hard problem is summary quality, not incremental optimality.

Once invalidation and continuous refinement matter, move to Lifelong Planning A* or D* Lite for the macro layer. The local exact planner can remain ordinary A* because its planning horizon is deliberately short.

Recommended progression:

1. Macro A* over observed/cached summaries.
2. Continuous replanning from current execution cursor.
3. Failure-memory penalties.
4. Generated terrain summaries.
5. Incremental macro replanning.

Do not begin with D* Lite. It will dignify an immature graph with excessive machinery.

## Policy Surface

Candidate settings:

```java
longDistancePlanning
longDistancePlannerThreads
longDistanceCommittedPrefixTicks
longDistanceCorridorWidth
longDistanceUnknownChunkPenalty
longDistanceGeneratedChunkPenalty
longDistanceHazardPenalty
longDistanceFailureMemoryHalfLifeTicks
longDistanceAllowChunkGeneration
longDistanceAllowSpeculativeExploration
```

These should eventually collapse into a smaller policy object. Settings are useful for playtesting, but a large public knob surface is intellectual litter unless each knob survives profiling and actual use.

## Observability

This feature needs first-class diagnostics:

- current macro route rendered as a translucent corridor
- route gates rendered distinctly from exact path points
- bad components/edges rendered with failure labels
- profile dumps containing macro search stats and local prefix stats
- plan revision numbers visible in logs
- reason a candidate replacement was accepted or rejected

Suggested profile additions:

```json
{
  "planningRevision": 42,
  "macro": {
    "nodesExpanded": 1204,
    "edgesRelaxed": 5091,
    "routeChunks": 188,
    "unknownChunks": 37,
    "generatedChunks": 0,
    "hazardCost": 921.4,
    "uncertaintyCost": 370.0
  },
  "localPrefixes": [
    {
      "fromGate": "...",
      "toGate": "...",
      "result": "success",
      "nodes": 19342,
      "selectedMovements": {"MovementTraverse": 84, "MovementOblique": 31}
    }
  ]
}
```

## Acceptance Criteria

For a 3k+ Chebyshev Nether route:

- Baritone should avoid repeatedly entering the same dead pocket.
- Baritone should keep moving on committed safe prefixes while improving the suffix.
- Local replans should be short and frequent, not rare stop-the-world crises.
- When it fails, it should explain the macro edge/component that failed.
- Increasing planner CPU budget should monotonically improve route confidence or route cost, not merely inflate local A* node counts.
- The system should still work with generation disabled, albeit with lower confidence.

## Open Questions

- How should chunk summaries represent vertical transitions without turning into miniature block graphs?
- What is the right y-band abstraction for Nether walking?
- How much construction should the macro planner model: bridging, mining, staircasing, tunneling?
- Should lava-sea crossing be finite by default if block placement is allowed, or should it require explicit high-risk policy?
- Can Elytra’s native octree packing be reused as a geometry substrate without importing flight-specific semantics?
- How aggressively should unknown chunks be explored/generated ahead of the current route?
- What is the cleanest splice contract for movements more complex than walking?

## First Implementation Slice

The first slice should be deliberately modest:

1. Selected movement histograms in path profiles.
2. Typed search stop reasons.
3. `PathPlan`/`PlanningService` scaffolding that preserves current behavior.
4. Observed chunk summaries with border exits.
5. Macro route debug rendering/dumping only.

If that substrate is clean, the second slice can let macro gates steer local A*. That is the point where long-distance correctness should begin to visibly improve.
