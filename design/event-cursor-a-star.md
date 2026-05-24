# Event-Cursor A*: Deferred Movement Expansion

Status: design
Scope: exact pedestrian A* expansion
Transit: out of scope
Farfield: constraint only, not an owner of proof keys
Horse: out of scope unless explicitly re-audited

## 1. Purpose

Exact pedestrian A* currently expands a popped node by eagerly looping the whole active movement catalog and evaluating every movement primitive. This is correct but wasteful. Ordinary flat travel still asks expensive questions about bridge placement, mining, parkour, pillar, downward, dynamic descent, oblique strides, and cruise rays even when those alternatives cannot possibly beat already-available cheap edges.

The desired refactor is not merely “try movements in a nicer order.” The search object changes:

```text
from:
  nodes waiting to be eagerly expanded

to:
  certified edge-evaluation opportunities waiting to become competitive
```

Each discovered node owns a source-local ordered set of pending movement-event streams. The global heap contains the cheapest pending event for each live source-node generation. When that event is popped, only that event is evaluated; the source-local cursor advances; the next cheapest event for that source generation is scheduled.

The exact movement graph, legality, cost formulas, parent pointers, route materialization, and controller semantics remain the same unless a later change is explicitly reviewed as a movement-semantics change.

## 2. Current Defect

`MovementPrimitive.minimumCost(...)` currently defaults to `0`. `LegacyMovesPrimitive.minimumCost(...)` overrides it only for cardinal traverse and cardinal ascend. Diagonal, descend, downward, pillar, parkour, and several dynamic families therefore inherit a lazy zero.

That is not conservative rigor. It is a false priority claim.

A zero lower bound says an expensive exotic edge may be as cheap as free. As long as that inherited zero exists, deferred expansion is either toothless or wrong. The first enemy is therefore:

```text
no movement family may inherit an accidental zero lower bound
```

A zero bound is allowed only through an explicit lower-bound certificate:

```text
ZERO_PROVEN
```

or as a short-lived, named implementation wart that cannot be mistaken for a valid proof.

## 3. Non-Goals

This is not Transit. Portals, boats, strategic water, meso-tasks, resource gathering, and mode switching are out of scope.

This is not Farfield. Farfield values may shape terminal/frontier objective values elsewhere, but expected Farfield values must never enter event proof keys.

This is not the pedestrian hot local reverse-value field.

This is not a new movement controller.

This is not an excuse to weaken exact A* route quality. The event engine must search the same exact graph with the same adjusted edge costs.

This is not a permanent two-engine menagerie. A legacy eager fallback may exist while proving equivalence, but the target is one exact pedestrian search engine.

## 4. Core Insight

The naive cursor model is:

```text
for each source node:
  resume movement catalog in static order
```

That is insufficient. Static movement order is only a tie-breaker. The proof key for an unevaluated opportunity is not just the movement's action lower bound. It is:

```text
eventKey =
  source.g
  + adjustedActionLowerBound
  + heuristicLowerBound(destinationEnvelope)
```

The destination heuristic depends on direction and destination. A later movement in static order can have a lower full key than an earlier movement because it moves more directly toward the goal.

Therefore each node owns a source-local k-way event cursor:

```text
node generation
  stream 0 current head
  stream 1 current head
  stream 2 current head
  ...
  choose cheapest head by full event key
  publish that one global heap event for this source generation
```

The global invariant is:

```text
For every discovered source-node generation with remaining unevaluated events,
the global event heap contains exactly one non-stale event:
the cheapest pending event for that source generation by full proof key.
```

This keeps the heap small and preserves correctness without allocating one heap entry per primitive per node.

## 5. Proof Semantics

The search proof is no longer about the lowest open node `g + h`.

The search proof is about the lowest pending unevaluated expansion opportunity:

```text
lowestPendingEventKey
```

For any incumbent score `I`:

```text
I is proven iff I ≤ lowestPendingEventKey + ε
```

This applies to:

```text
final goal incumbents;
exact local-exit incumbents;
frontier-exit incumbents;
oracle upper-bound exhaustion;
timeout best certified candidates.
```

Consequences:

1. Discovering a goal node records an incumbent. It is not a proof by itself.
2. A node being “popped” in the old sense no longer exists as the proof event.
3. `PathNode.combinedCost` becomes diagnostic/cache data, not proof authority.
4. Any remaining use of `openSet.lowestCombinedCost()` in event mode is a correctness bug.
5. Local-exit proof must compare `bestExitScore` against `eventHeap.lowestKey()`.
6. Oracle upper-bound exhaustion must compare `eventHeap.lowestKey()` against the oracle ceiling.

## 6. Cost Metric

The lower-bound key must use the same cost metric accumulated into `PathNode.cost`.

That means lower bounds must conservatively include or account for:

```text
raw movement cost;
reversibility recosting;
destination favoring;
settings that can reduce or increase movement family costs;
movement-profile restrictions;
world-border and y-bound prechecks;
local-exit/frontier boundary scoring opportunities.
```

`adjustedActionLowerBound` must satisfy:

```text
adjustedActionLowerBound ≤ actualAdjustedActionCost
```

where `actualAdjustedActionCost` is the cost that would actually be added to `PathNode.cost` after favoring and recosting.

If favoring can discount costs and no global lower multiplier is available, the event proof must weaken the destination heuristic or action key accordingly. The safe fallback for uncertain dynamic envelopes is:

```text
heuristicLowerBound(destinationEnvelope) = 0
```

Correct but weaker is acceptable. Overstated is forbidden.

Expected Farfield values, empirical risk values, and ordinary objective estimates are not admissible proof inputs. They may appear in terminal/exit objective values, not in event lower-bound proof keys.

## 7. Destination Envelopes

A movement event does not always have one exact destination. The scheduler needs a conservative destination abstraction:

```text
DestinationEnvelope
  ExactOffset(dx, dy, dz)
  FiniteOffsets(offsets[])
  VerticalColumn(dx, dz, minDy, maxDy)
  Unknown
```

Required operations:

```text
heuristicLowerBound(goal, source)
exactDestinationKey if exact
precheck target / legacy-compatible boundary policy
world-border/y-bound legality where statically knowable
```

Rules:

```text
ExactOffset:
  use exact destination heuristic.

FiniteOffsets:
  use minimum heuristic over offsets if the set is tiny and cheap;
  otherwise use 0.

VerticalColumn:
  use a proven minimum over the vertical range only if cheap;
  otherwise use 0.

Unknown:
  use 0.
```

The envelope must never claim a heuristic lower bound larger than the heuristic of a concrete destination that the event may produce.

## 8. Boundary and Frontier Semantics

The current A* loop performs chunk-boundary checks before evaluating a movement. For frontier/local-exit searches, a movement whose destination probe exits pathing data can produce a frontier score without evaluating the exact movement.

Event mode must preserve this proof behavior.

If an unevaluated event can produce a boundary/frontier candidate, that opportunity must be represented in the event key. There are two acceptable encodings:

```text
A. The movement event key lower-bounds both exact edge relaxation and boundary/frontier candidate scoring.

B. Boundary/frontier opportunities are emitted as separate boundary events.
```

What is forbidden:

```text
bestExitScore proven against exact movement events
while lower-key unevaluated boundary opportunities still exist outside the heap
```

Chunk-boundary behavior is especially treacherous for split dynamic families. Example: legacy parkour has a distance-4 precheck offset. Splitting parkour into distance 2/3/4 can change unloaded-chunk/frontier behavior. The first implementation must either preserve legacy precheck policy or explicitly review that change as an Exact/Farfield seam change.

## 9. Types and Data Structures

The conceptual model should be strongly typed. The hot implementation should be dense and allocation-free.

### 9.1 Event Kind

`MovementEventKind` is a proof/profile category, not necessarily a public `Movement` class:

```text
TERMINAL
TRAVERSE_CLEAN_DRY
TRAVERSE_WATER
TRAVERSE_SIDE_PLACE
TRAVERSE_BACKPLACE
TRAVERSE_PASSAGE_MINE
ASCEND_CLEAN
ASCEND_PLACE
ASCEND_HEADROOM_MINE
DIAGONAL_FLAT
DIAGONAL_ASCEND
DIAGONAL_DESCEND
DIAGONAL_EDGE_AROUND
DESCEND_SIMPLE
DESCEND_SIMPLE_WITH_FRONT_MINE
DESCEND_DYNAMIC_FALL
DESCEND_DYNAMIC_FALL_WITH_FRONT_MINE
DOWNWARD_LADDER
DOWNWARD_DROP_OR_BREAK
PILLAR_LADDER
PILLAR_WATER_COLUMN
PILLAR_BLOCK_PLACE
PILLAR_HEADROOM_MINE
PARKOUR_DIST_2
PARKOUR_DIST_3
PARKOUR_DIST_4
PARKOUR_ASCEND
PARKOUR_PLACE
OBLIQUE
CRUISE_RAY
```

The first implementation may have fewer kinds, but every kind must have:

```text
lower-bound certificate;
partition rule;
profiler counters;
execution primitive identity.
```

### 9.2 Event Spec

Conceptual shape:

```java
record MovementEventSpec(
  MovementEventKind kind,
  short executionPrimitiveIndex,
  byte staticTieTier,
  DestinationEnvelope destinationEnvelope,
  PrecheckPolicy precheckPolicy,
  LowerBoundCertificate certificate,
  MovementEventEvaluator evaluator
) {}
```

The hot path should not allocate these per node. Store stable catalog metadata in arrays:

```text
short[] executionPrimitiveIndexByEvent
byte[] staticTieTierByEvent
byte[] kindOrdinalByEvent
LowerBoundFunction[] lowerBounds
Evaluator[] evaluators
PrecheckPolicy[] prechecks
```

`executionPrimitiveIndex` is the identity used by `PathNode.previousPrimitiveIndex` and path materialization. A split event may still instantiate the same concrete `Movement` class as another split event if the payload/destination preserve the certified edge.

### 9.3 Source-Local Cursor State

`PathNode` needs expansion state separate from legacy heap state:

```text
int expansionGeneration
int pendingEventSerial
small consumed masks for compact streams
cursor fields for larger streams
```

Do not use `PathNode.heapPosition` for the event heap. It can remain for legacy eager fallback during migration.

### 9.4 Expansion Event Heap

Conceptual event:

```java
record ExpansionEvent(
  PathNode source,
  int sourceGeneration,
  int sourceEventSerial,
  short streamId,
  int cursor,
  MovementEventKind kind,
  double adjustedActionLowerBound,
  double priorityKey,
  int tie
) {}
```

Hot heap representation should be primitive arrays:

```text
PathNode[] source
int[] generation
int[] serial
short[] streamId
int[] cursor
short[] kindOrdinal
double[] actionLowerBound
double[] key
int[] tie
```

No decrease-key is necessary. Stale events die by generation/serial checks.

### 9.5 Event Heap Invariant

For each live source-node generation:

```text
there is at most one non-stale global event
and if the source has any unevaluated event remaining
that event is the source-local minimum pending event by full proof key
```

If a node's `g` improves:

```text
increment expansionGeneration;
reset source-local cursors;
push fresh cheapest event;
old events become stale.
```

This reset is required because old consumed outgoing edges may now improve downstream nodes.

## 10. Formal Invariants

For every event `e` from source `u`, and every concrete edge `u → v` that `e` may evaluate:

```text
e.adjustedActionLowerBound ≤ adjustedCost(u → v)
```

For every destination envelope:

```text
heuristicLowerBound(e.destinationEnvelope) ≤ goal.heuristic(v)
```

for every concrete destination `v` the event may produce.

Therefore:

```text
event.priorityKey ≤ u.g + adjustedCost(u → v) + goal.heuristic(v)
```

The global heap minimum lower-bounds every unevaluated reachable edge opportunity.

Every old eager movement result must be represented:

```text
For every source and every reachable result produced by old eager evaluation,
exactly one event stream can produce the same destination, adjusted cost, execution primitive, and payload.
```

If a split event family overlaps another split family, that is a bug unless the overlap is deliberate and proven harmless. The target is a partition, not duplicate graph edges.

## 11. Lower-Bound Certificates

Every movement event kind must have a certificate:

```text
CODE_ANALYSIS
EMPIRICAL_MINIMUM
CONSERVATIVE_SYNTHETIC
ZERO_PROVEN
```

`CODE_ANALYSIS`
: Preferred. Derived directly from the concrete cost formula and settings constraints.

`EMPIRICAL_MINIMUM`
: Acceptable when code inspection is too fragile. The empirical minimum must be deterministic, committed, and guarded by tests.

`CONSERVATIVE_SYNTHETIC`
: Acceptable as a short-lived bridge. It must be weaker than desired but still valid.

`ZERO_PROVEN`
: Required for any real zero. It must state why the movement family can be arbitrarily cheap or why settings permit nonpositive edge cost in that family.

Runtime certificate metadata should be cheap:

```text
kindOrdinal → lowerBoundFunction → proof/debug id
```

Detailed proof notes can live in this document, Javadocs, or tests. Do not carry proof prose through the hot loop.

### 11.1 Negative Settings Rule

Negative settings must not poison unrelated families.

Bad:

```text
if breakBlockAdditional < 0 or placeCost < 0:
  every movement lower bound = 0
```

Good:

```text
clean walking ignores negative break/place settings;
placement events weaken only for placement settings;
mining events weaken only for mining settings;
parkour-place weakens for placement;
pillar-block-place weakens for placement;
```

The lower-bound registry must encode which settings a family depends on.

### 11.2 Debug Assertions

In debug/audit mode, every reachable evaluated event should assert:

```text
declaredAdjustedLowerBound ≤ actualAdjustedActionCost + ε
event.priorityKey ≤ source.g + actualAdjustedActionCost + goal.heuristic(actualDest) + ε
```

Violations are correctness bugs.

## 12. Current Cost Constants

Current code-derived values:

```text
SPRINT_ONE_BLOCK_COST       ≈ 3.5638
WALK_ONE_BLOCK_COST         ≈ 4.6328
WALK_OFF_BLOCK_COST         ≈ 3.7063
CENTER_AFTER_FALL_COST      ≈ 0.9266
FALL_N_BLOCKS_COST[1]       = 5
JUMP_ONE_BLOCK_COST         = 3
WALK_ONE_IN_WATER_COST      ≈ 9.0909
SPRINT_SWIM_ONE_BLOCK_COST  = 5
LADDER_UP_ONE_COST          ≈ 8.5106
LADDER_DOWN_ONE_COST        ≈ 6.6667
SNEAK_ONE_BLOCK_COST        ≈ 15.3846
```

Derived optimistic distances:

```text
flat diagonal sprint        ≈ 5.0400
flat diagonal walk          ≈ 6.5518
oblique 2×1 sprint          ≈ 7.9689
oblique 2×1 walk            ≈ 10.3594
simple one-block descend    ≈ 8.7063  (WALK_OFF + FALL_1)
```

These numbers are a starting point. The lower-bound certificates must follow the actual code formulas, not this table if the table drifts.

## 13. Initial Event Families and Bounds

Static tier is only a tie-breaker. Primary order is full event key.

| Tie Tier | Event family | Lower-bound basis | Notes |
|---:|---|---|---|
| 0 | terminal / goal / exact local-exit | `0` | No movement evaluator. Proof still waits on heap minimum. |
| 1 | clean cardinal traverse | `min(sprint/walk, waterMove)` after metric adjustment | Split clean from bridge/mine. |
| 2 | clean cardinal ascend | `max(WALK_ONE, JUMP_ONE) + max(0, jumpPenalty)` or code-proven weaker slab bound | Must follow slab and water-surface details. |
| 3 | water/surface traverse | water movement floor | Split from dry hot path where useful. |
| 4 | clean flat diagonal | `√2 * stepCost` | Currently inherited zero; must be certified. |
| 5 | clean diagonal ascend | code-derived diagonal ascend minimum | Current excerpt adds `JUMP_ONE_BLOCK_COST`; verify jump penalty behavior. |
| 6 | clean diagonal descend | `√2 * stepCost + max(FALL_1, CENTER_AFTER_FALL)` | Verify sprint/edge-around cases. |
| 7 | simple cardinal descend | `WALK_OFF + FALL_1` | Split from dynamic fall and front mining. |
| 8 | ladder/vine vertical | `LADDER_UP` / `LADDER_DOWN` | Split from pillar/downward destruction. |
| 9 | oblique 2×1 / 1×2 | `√5 * stepCost` | Existing bound; verify water/recost/favoring. |
| 10 | cruise ray | `max(stepCost, len * stepCost - dividend)` | Existing bound; special stream required for large fans. |
| 11 | parkour distance 2 | code-derived min over flat/ascend dist2 | Parkour ascend may be cheaper than flat formula. |
| 12 | parkour distance 3 | code-derived min over flat/ascend dist3 | Split by distance. |
| 13 | parkour distance 4 | code-derived dist4 minimum | Sprint-only cases matter. |
| 14 | side-place bridge | base traverse + place lower bound | Placement settings only affect placement events. |
| 15 | backplace bridge | sneak/backplace base + place lower bound | Late. |
| 16 | ascend with placement | clean ascend floor + place lower bound | Split from clean ascend. |
| 17 | parkour-place | parkour distance floor + place lower bound | Late. |
| 18 | passage mining | base motion + mining lower bound | Must not call full mining early. |
| 19 | true pillar block-place | `JUMP_ONE + placeLower + max(0, jumpPenalty)` plus headroom floor | Split ladder/water. |
| 20 | downward drop/break | `min(FALL_1, LADDER_DOWN)` with family-specific constraints | Split ladder from break-underfoot. |
| 21 | dynamic long fall / bucket / exotic descent | `WALK_OFF + fall lower bound` | Expensive vertical scan, late. |

## 14. Movement Splitting Policy

Splitting is mandatory when a cheap case and expensive case currently share one evaluator.

The split must preserve graph semantics:

```text
old evaluator accepted edge E
  → exactly one split event accepts E
```

If the split is too hard initially, use one coarse event for that old primitive while the event engine is brought up. Then split high-value families.

### 14.1 Traverse

Target split:

```text
clean dry traverse
water/surface-swim traverse
side-place bridge
backplace bridge
passage mining
```

The first performance win is that clean dry traverse should not pay bridge placement or mining probes.

### 14.2 Ascend

Target split:

```text
clean step/jump/slab ascend
ascend with placement
headroom mining
```

The lower bound must follow actual slab and water-surface handling.

### 14.3 Diagonal

Target split:

```text
flat clean
ascend
descend
edge-around / obstructed
```

Do not assume diagonal has a true mining-cost regime. In the visible code, `movementPassageCost` appears to classify obstruction/edge-around possibilities rather than add mining cost to the diagonal. Full-repo audit must confirm before adding `DIAGONAL_MINE`.

### 14.4 Descend

Target split:

```text
simple one-block descend without front mining
simple one-block descend with front-column mining
dynamic fall
dynamic fall with front-column mining
```

Dynamic fall should not be evaluated until its late event becomes competitive.

### 14.5 Parkour

Target split:

```text
distance 2
distance 3
distance 4
ascend landing
parkour-place
```

Parkour ascend can be cheaper than flat distance formulas. Its certificate must inspect the actual cost cases.

### 14.6 Pillar

Target split:

```text
ladder/vine climb
water-column surfacing ascent
true block pillar
headroom mining
```

Ladder and water-column cases must not be delayed behind true block placement if their lower bounds are competitive.

### 14.7 Downward

Target split:

```text
ladder/vine down
drop through passable cell
break-underfoot drop
```

## 15. Cruise Rays and Large Streams

Cruise rays can create a large fan. A naive source-local scheduler that scans every ray head per node is a perf trap.

Acceptable strategies:

```text
specialized cruise-ray stream ordered by lower-bound key;
bucketed rays by optimistic key;
coarse grouped event with heuristicLowerBound = 0 until popped;
cap-aware staged fan expansion.
```

The first implementation should prefer safety over cleverness:

```text
do not allocate one heap event per ray per node;
do not scan a huge ray catalog for every source-local minimum;
do not let cruise rays destroy time-to-first-movement.
```

If a weak grouped cruise event is used, it is less sharp but correct.

## 16. Lower-Bound Audit Harness

Add a deterministic verifier:

```text
scripts/audit_movement_lower_bounds
```

or equivalent JUnit/Gradle harness.

Inputs:

```text
movement profile;
cost profile;
terrain generator seed corpus;
movement event kind;
settings sweep for negative/zero special costs;
optional favoring/reversibility profile.
```

Outputs:

```text
declared lower bound;
minimum finite observed adjusted cost;
violations;
representative terrain;
source/destination/payload;
settings profile;
event kind.
```

Modes:

```text
fast CI smoke: small deterministic corpus;
deep ALARA audit: large generated corpus.
```

The harness must fail if an observed finite adjusted cost is below the declared adjusted lower bound.

## 17. Search Loop Shape

Before introducing the event heap, extract current eager A* logic into reusable operations:

```text
precheck event against chunks/world-border/y-bounds;
record boundary/frontier opportunity;
evaluate event into EdgeEvalScratch;
apply or verify reversibility recosting;
apply favoring;
validate finite positive adjusted cost;
resolve/create neighbor PathNode;
relax neighbor and update parent fields;
update bestSoFar/failing;
record profiler counters.
```

This prevents event mode from accidentally changing dynamic destination handling, parent payloads, node-cap behavior, best-so-far behavior, or frontier scoring.

Event-mode loop:

```text
initialize start node with g = 0;
record start goal/local-exit incumbent if applicable;
schedule cheapest event for start generation;

while eventHeap not empty and not cancelled and not nodeMapFull:
  if incumbent exists and incumbent.score ≤ eventHeap.lowestKey + ε:
    return incumbent path;

  event = heap.pop();
  if event is stale:
    continue;

  source = event.source;
  load NodeTerrainFacts for source;

  precheck event;
  if precheck yields boundary/frontier candidate:
    update incumbent candidate;
  else if exact static destination incumbent proves no improvement:
    record skip;
  else:
    evaluate event;
    if reachable:
      adjust cost;
      relax destination;
      if destination improved:
        reset destination expansion generation;
        schedule cheapest event for destination;
        record goal/local-exit incumbent if applicable;

  advance source-local cursor;
  schedule next cheapest event for source generation;
```

Timeout publication must distinguish:

```text
proven incumbent;
unproven incumbent;
best-so-far legacy fallback;
no publishable candidate.
```

For pedestrian Farfield/local-exit planning, ungrounded generic coefficient best-so-far remains suspect. That policy is governed elsewhere, but event mode must not make it worse.

## 18. Exact-Destination Incumbent Pruning

Keep the existing static-destination lower-bound prune, but move it inside event handling:

```text
if event has exact static destination
and destination incumbent exists
and destinationIncumbent.cost ≤ source.g + adjustedActionLowerBound + ε:
  skip evaluator
```

Only apply this when:

```text
the event has one exact destination;
the adjusted lower bound includes all relevant cost multipliers;
the destination key is exact;
the event cannot produce a different dynamic destination.
```

For dynamic or multi-destination events, use dominance only after a family-specific proof.

## 19. Materialization and Payloads

Split events may still instantiate existing concrete `Movement` classes.

`PathNode.previousPrimitiveIndex` should continue to point at an execution primitive that can materialize the edge. `previousEdgePayload` must carry any extra split-event information needed to replay the certified edge.

If a dynamic split event narrows a regime, it must not re-evaluate during materialization and accidentally choose a different dynamic edge. Either:

```text
payload records enough information to instantiate the certified edge;
or the execution primitive revalidates and rejects if the certified destination/regime no longer matches.
```

## 20. Telemetry

Keep existing movement evaluator counters. Add event counters by `MovementEventKind`:

```text
eventsScheduled
eventsPopped
eventsStale
eventsSkippedByExactIncumbent
eventsBoundaryExit
evaluatorsRun
reachableResults
evaluatorNanos
lowerBoundViolations
sourceLocalScans
maxSourceLocalScanWidth
cruiseRayEventsDeferred
```

The important perf signal is:

```text
expensive event kinds mostly remain scheduled-but-unpopped or skipped on ordinary terrain
```

Top-line evaluation:

```text
same route quality or better;
fewer expensive movement evaluations;
lower time to first movement;
lower total stalled time;
no increase in calcFailed;
no death;
no known static damage edges admitted.
```

## 21. Implementation Plan

### Stage 1: Lower-Bound Extermination

1. Replace silent default `MovementPrimitive.minimumCost()` with an explicit requirement or throwing transitional default.
2. Give every active primitive a lower-bound certificate.
3. Fix `LegacyMovesPrimitive.minimumCost(...)` so diagonal, descend, downward, pillar, and parkour do not inherit accidental zero.
4. Scope negative setting uncertainty to affected families only.
5. Add tests asserting no active movement family has accidental zero.

This stage can land before the event heap.

### Stage 2: Search Operation Extraction

Extract current A* loop machinery into reusable operations without changing behavior:

```text
precheck;
boundary/frontier candidate update;
evaluate;
adjust;
relax;
incumbent update;
profile.
```

Run existing tests/playtests to ensure no behavior change.

### Stage 3: Coarse Event Engine

Implement event heap and source-local k-way cursor with one coarse event per existing primitive.

Purpose:

```text
prove event-heap termination semantics;
prove stale generation handling;
prove incumbent proof via heap minimum;
prove materialization compatibility.
```

This stage may not yet deliver the full perf win. It must deliver semantic equivalence.

### Stage 4: Differential Oracle

Keep eager mode as a debug oracle.

For bounded loaded-world fixtures:

```text
run eager;
run event;
compare reached goal;
compare final path cost within epsilon;
compare materialized movement replay cost;
verify every eager reachable edge is produced by exactly one event stream.
```

### Stage 5: High-Value Splits

Split in this order:

```text
Traverse clean/place/mine/water
Diagonal flat/ascend/descend/edge-around
Descend simple/dynamic/front-mine
Parkour by distance/ascend/place
Ascend clean/place/headroom-mine
Pillar ladder/water/block/headroom
Downward ladder/drop/break
```

After each split:

```text
run lower-bound audit;
run differential fixture;
profile event counters;
confirm no duplicate edge production.
```

### Stage 6: Cruise-Ray Stream Optimization

Only after core event semantics are stable:

```text
replace naive cruise fan handling with ordered/bucketed/grouped stream;
measure TTFM and total stalled time;
keep correctness weaker rather than over-clever.
```

### Stage 7: Cleanup

Remove eager fallback only when it stops paying for itself as a differential oracle.

Purge any transitional scalar lower-bound API that lets new movement families inherit zero.

## 22. Acceptance Tests

Unit:

```text
all event kinds have lower-bound certificates;
no active movement family inherits zero;
declared adjusted lower bound never exceeds evaluated adjusted cost on fixtures;
destination envelope heuristic bound never exceeds concrete destination heuristic;
stale event generation is discarded;
source-local scheduler publishes exactly the cheapest pending event for a node;
goal/local-exit proof uses event heap minimum;
oracle upper-bound proof uses event heap minimum.
```

Differential:

```text
event engine equals eager A* path cost on bounded loaded fixtures;
event engine reaches every eager-reachable goal under generous budget;
every eager reachable edge is produced by exactly one event stream;
split events do not duplicate edges.
```

Playtest:

```text
Nether A/B/C view distance 8;
End bridge/island regression cases;
pedestrian ephemeral obstacle/fire corridor;
seed-pure exact A* oracle spot checks;
horse suite only if shared code surfaces moved.
```

Performance:

```text
movement evaluator invocations lower;
mining/place/dynamic fall evaluator invocations materially lower;
TTFM not worse, ideally better;
total stalled time lower;
event heap allocation bounded;
source-local scan width bounded;
cruise-ray handling does not dominate startup.
```

## 23. Forbidden Smells

Forbidden:

```text
new movement family with inherited zero lower bound;
lower bound that calls the full evaluator;
lower bound that performs VoxelShape collision;
lower bound that reads expected Farfield values;
event proof based on node heap minimum;
static catalog order treated as proof order;
split event families with overlapping accepted edges;
global break/place negativity zeroing clean movement bounds;
per-node allocation of event objects for every primitive;
naive per-node scan of huge cruise-ray catalogs;
boundary/frontier opportunities outside the event proof heap.
```

Desired:

```text
lower-bound functions are pure arithmetic/settings snapshots;
event kinds are enum-like and allocation-free;
source-local cursor is a k-way minimum over stream heads;
movement splitting follows actual code cost regimes;
empirical certificates catch future formula drift;
expensive evaluators become rare on open terrain;
proof conditions are stated in terms of incumbent score vs event heap minimum.
```

## 24. Bottom Line

Event-cursor A* is a certified laziness refactor:

```text
evaluate expensive movement edges only when their admissible opportunity key
becomes competitive
```

The revised design has three non-negotiable pillars:

```text
1. no accidental zero lower bounds;
2. source-local k-way event cursor by full proof key;
3. incumbent proof against lowest pending event key.
```

If any of those pillars is missing, the refactor either fails to improve performance or silently corrupts the proof.
