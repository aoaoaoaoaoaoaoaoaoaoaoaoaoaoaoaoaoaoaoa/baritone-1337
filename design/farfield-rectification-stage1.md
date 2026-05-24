# Farfield Rectification Stage 1: Columns Before Transit

Status: design spec for implementation planning  
Date: 2026-05-21  
Target branch: `pedestrian-kaizen`  
Scope: Farfield only; Transit explicitly excluded

## 1. Purpose

The current long-distance code conflates two different machines under the old word `macro`:

```text
far continuation economics outside exact terrain;
explicit multimodal route actions such as portals, boats, and other mode changes.
```

This has produced broken reasoning and broken behavior. The first rectification stage separates the two and repairs only the first machine.

The target for this stage is:

```text
Exact loaded/cached block graph
  owns every movement through terrain with pathing data
        ↓
Frontier exits
  are discovered by exact graph expansion at the pathing-data boundary
        ↓
Farfield
  supplies scalar continuation values for those exits
        ↓
Hot local repair
  efficiently chooses and refreshes the exact prefix as chunks load
```

Transit is out of scope. Portals, boats, strategic swimming routes, elytra, resource gathering, crafting, and other mode-switching or meso-task route families must not participate in this stage. They can consume the resulting Farfield later, but they are not allowed to shape the first rehabilitation.

The important architectural correction is:

```text
Farfield never chooses an interior local goal.
Farfield only prices exact frontier exits.
```

## 2. Glossary For This Stage

`Regime`
: The non-switchable control ontology for a pathing session. Examples: pedestrian, mounted horse. A regime determines the state lattice, movement edge family, controller doctrine, and acceptance semantics.

`Mode`
: A switchable locomotion state inside a regime. Examples inside the pedestrian regime: walk, swim, boat. Mode transitions are Transit work. Stage 1 does not add strategic mode selection.

`Exact`
: The block-level movement graph over terrain with pathing data. Pathing data means Baritone live pathing chunks or cached pathing chunks, not merely Distant Horizons visuals or minimap awareness.

`Frontier`
: The boundary where an exact movement would leave pathing data.

`FrontierExit`
: An exact graph node, plus outward face/direction metadata, at which the local planner can hand off to Farfield. The node itself is inside Exact. The attempted successor is outside Exact.

`Farfield`
: A scalar continuation-value oracle over coarse 16×16 columns outside Exact. It estimates remaining cost beyond the frontier. It is not an executable route and does not perform mode changes.

`Transit`
: Explicit multimodal or meso-task route machinery: portals, boats, water route sessions, elytra, resource gathering, crafting, bridge-building phases, tunnel phases. Transit is excluded from Stage 1.

`Route`
: The executable plan accepted by `RouteExecutor`.

`Itinerary`
: A future route containing Transit legs. Stage 1 should not produce itineraries.

## 3. Non-Goals

Stage 1 does not solve multimodal planning.

Stage 1 does not make portals compete correctly. It disables or bypasses that question until Exact/Farfield is correct.

Stage 1 does not require a rich biome model.

Stage 1 does not require 3D macro voxels. The 3D voxel work is reduced to 2D column Farfield. If height pressure is needed, it is modeled as a product correction:

```text
farfieldCost(column, exitY) = columnCost(column) + heightPrior(dimension, exitY)
```

not as a full `(x,y,z)` value graph.

Stage 1 does not require perfect global optimality. It requires a correct ownership boundary: Exact owns exact terrain; Farfield prices only exits from exact terrain.

## 4. Diagnosis

The current code violates the desired ownership boundary in two ways.

First, the old `macroPlanning` setting made “macro-looking long-range guidance” incoherent by conflating same-dimension continuation values with route-level transport planning. It has no place in the rectified ontology.

Second, `ValueProjectedExitObjective` currently receives local-exit truth from a coarse value field:

```text
if current macro cell is factual
and selected macro successor is not factual
then this block may be an exact local exit
```

This is backward. Exit generation must be a property of exact graph expansion, not a property of coarse cell factuality.

The End exposes the bug cleanly. A 2D biome/surface value field can see a vague direction and a factual cell boundary, but it does not understand loaded island topology versus void topology. The result can be a long exact bridge across the void even when a sane loaded-island detour exists, because Farfield is allowed to influence the local handoff too early.

The fix is not “make Farfield smarter first.” The fix is:

```text
make Farfield later.
```

## 5. Preserved Ideas From The Voxel Work

The voxel work should be distilled, not burned. The good architecture is the layered value-field split:

```text
Prior → Bellman → Rich
```

`Prior`
: Always-available cheap continuation economics. It must be fast enough to answer immediately and safe enough that pathing does not stall if rich evidence is absent.

`Bellman`
: A resident coarse value solver over the Farfield graph. It turns local per-column costs into continuation values.

`Rich`
: Opportunistic evidence and refinements layered over the prior: loaded/cached terrain summaries, dimension-specific classification, later empirical priors. Rich updates are allowed to lag. Exact pathing must still move.

What changes is the state geometry:

```text
old: dimension × voxelX × voxelY × voxelZ
new: dimension × cellX × cellZ
```

The y dimension is removed from the value graph. Height effects, when needed, are multiplicative/additive priors outside the graph:

```text
exitY correction;
dimension layer prior;
optional regime/mode-specific height preference.
```

This saves compute, reduces state cardinality, and prevents the coarse field from pretending it owns vertical exactness that belongs to block-level planning.

## 6. Column Farfield Model

### 6.1 Key

The primitive Farfield key is:

```java
record FarfieldColumnKey(
  DimensionId dimension,
  int cellX,
  int cellZ
) {}
```

The cell width is 16 blocks. This aligns with chunks, preserves cheap floor division, and is fine enough for the first End/Nether pass.

### 6.2 Profile

A column profile is a compact distribution over coarse continuation regimes:

```java
record FarfieldColumnProfile(
  byte openSurface,
  byte voidOrGap,
  byte lava,
  byte solid,
  FarfieldEvidence evidence,
  int representativeY
) {}
```

The byte fields are normalized weights or probabilities. The exact representation can use `u8` probabilities, fixed-point scores, or small enums; the invariant is that the profile is small enough to store densely.

The initial public regimes are:

```text
OPEN_SURFACE
  Ordinary continuation is plausible.

VOID_OR_GAP
  Continuation likely requires bridging, detour, or no ordinary support.

LAVA
  Continuation likely requires bridging over lava or major risk.

SOLID
  Continuation likely requires macro-scale tunneling.
```

For the End, `VOID_OR_GAP` is first-class. For the Nether, `LAVA` and `SOLID` are first-class. For the Overworld, v0 can use dull priors and simple open/solid/water-ish summaries until empirical biome work returns.

### 6.3 Costs

A Farfield cost model maps a profile and an exit context to:

```java
record FarfieldCost(
  float expectedTicksPerBlock,
  float floorTicksPerBlock
) {}
```

`expectedTicksPerBlock` is the objective estimate.

`floorTicksPerBlock` is a lower-bound heuristic input. It must never contain speculative risk unless proven lower-bound safe:

```text
0 ≤ floor ≤ expected
```

Initial example constants:

```text
OPEN_SURFACE:    low, ordinary walking continuation
VOID_OR_GAP:     high bridge continuation
LAVA:            high bridge/risk continuation
SOLID:           very high tunnel continuation
UNKNOWN:         dimension prior
```

Exact numeric values are tunables, not doctrine. The doctrine is the ordering and the ownership boundary.

### 6.4 Height Prior

Height is not a graph dimension in Stage 1. If a dimension needs layer pressure, use:

```java
interface FarfieldHeightPrior {
  float expectedAdjustment(DimensionId dimension, int exitY);
  float floorAdjustment(DimensionId dimension, int exitY);
}
```

Examples:

```text
End:
  usually near-zero; void/land topology is column-dominated.

Nether:
  high penalty near bedrock floor and ceiling if evidence supports solid mass there;
  optional mild penalty for low strata if lava/solid priors warrant it.
```

This keeps vertical reasoning honest: exact local planning owns actual ascents, descents, ceilings, and supports; Farfield only says some layers tend to have worse continuation.

## 7. Bellman Field

The Farfield value graph is dense and 2D:

```text
node = FarfieldColumnKey
neighbors = 8-way columns for v0
edge cost = distance(column centers) × source/target/profile traversal cost
```

State arrays:

```java
float[] expectedValue;
float[] floorValue;
int[] bestSucc;
byte[] profileFlags;
int[] heapIndex;        // if incremental repair is retained
```

No per-edge objects are stored in the hot loop. Neighbor edges are virtual.

The recurrence is:

```text
V(c) = min(
  terminalCost(c),
  min over neighbors n of edgeCost(c,n) + V(n)
)
```

For Stage 1, the field can be rebuilt cheaply if incremental repair becomes a drag. A 2D 16-block field is small enough that correctness and simplicity of invalidation beat preserving the full 3D D* machinery.

`floorValue` remains separate from `expectedValue`.

```text
Farfield expected value: objective continuation.
Farfield floor value: lower bound only.
```

No expected risk may enter exact A* as an admissible heuristic.

## 8. Exact/Farfield Seam

### 8.1 Frontier Exit Discovery

Frontier exits are discovered by exact graph expansion.

For each exact node `u`, and each movement primitive `p`:

```text
if p would produce reachable exact successor v:
  add exact edge u → v

if p's precheck/destination/swept dependency crosses into absent pathing data:
  do not add guessed edge
  mark u as a FrontierExit in the outward direction
```

The source node `u` is still exact. The missing successor is not guessed.

A `FrontierExit` should retain:

```java
record FrontierExit(
  int sourceNode,
  BetterBlockPos sourcePos,
  DirectionSet outwardFaces,
  int exitY,
  ChunkFactState factStateAtBoundary
) {}
```

The outward direction matters. Exiting a column through its north face should query the Farfield value beyond that face, not a vague value for the current coarse cell interior.

### 8.2 Terminal Costs

The hot local value field has terminals:

```text
final goal:       0
frontier exit:    farfield.expected(exit)
```

There is no coarse `exactLocalExitAtBlock(...)` test in Stage 1.

The terminal function becomes:

```java
double terminalCost(Node u) {
  if (goal.isInGoal(u.pos())) {
    return 0D;
  }
  FrontierExit exit = frontierExits.get(u);
  if (exit != null) {
    return farfield.expectedContinuation(exit);
  }
  return INF;
}
```

For heuristic:

```java
double heuristic(Node u) {
  double ordinary = goal.heuristic(u.pos());
  double floor = frontierLowerBoundIfRelevant(u);
  return min(ordinary, floor);
}
```

Only lower-bound Farfield values may participate in heuristic pruning.

### 8.3 Loaded Terrain Dominance

The invariant:

```text
If pathing data exists for a movement's destination/dependencies,
that movement is exact and Farfield has no vote.
```

Farfield is consulted only when exact graph discovery hits absent pathing data.

This means newly loaded chunks clobber stale Farfield exits. If a chunk loads, any `FrontierExit` that points into that chunk becomes invalid and must be removed or reclassified during the next batch repair.

## 9. Passive Farfield Doctrine

Farfield is passive.

This is not an implementation preference. It is an ownership law:

```text
Farfield publishes continuation values.
Farfield never initiates, cancels, preempts, or commits executable routes.
Farfield never throws away a physical plan.
Farfield never chooses the replanning cadence.
```

The executable path, its controller, and `RouteExecutor` own all execution timing. They may pull a Farfield snapshot when planning from a physical, current, or certified-future anchor. The snapshot is then immutable for that calculation. If Farfield refreshes again while the calculation or route is alive, nothing happens until the pathing loop elects to ask again.

Correct pull structure:

```text
1. Current route estimates its remaining execution/commitment geometry.
2. Pathing chooses a safe replanning anchor beyond the commitment tip.
3. Pathing snapshots the best currently available Farfield values.
4. Exact/hot-local pathing computes a candidate prefix/suffix against that snapshot.
5. RouteExecutor accepts only if the candidate anchors safely and wins under route objective.
6. Otherwise the candidate dies silently; the existing route continues.
```

Incorrect push structure:

```text
Farfield refresh completes
  → Farfield demands a physical replan
  → current route is interrupted because the coarse value changed
```

That structure is forbidden. A coarse value-field refresh is not a world-fact invalidation. Loaded terrain, block changes, hazard discovery, controller failure, and explicit user commands may interrupt execution. Farfield freshness may not.

This also means Farfield improvement thresholds are at most candidate-comparison epsilons. They must not be the trigger for launching replans. Large “Farfield is N ticks better, therefore preempt” thresholds invert authority and recreate the old macro pathology. Replan launch cadence should instead be derived from execution geometry:

```text
anchor lead ≈ safetyFactor × observedReplanLatency + fixedBuffer
```

or the equivalent route-progress model. The point is that the path pulls Farfield at a cadence justified by executable commitment, not that Farfield pushes the path at a cadence justified by value churn.

Farfield is asynchronous by default. Its rich evidence and Bellman repair may lag, stall, or go dark. In those cases the planner sees the latest published snapshot or the prior/mean field. Nothing breaks. The failure mode of Farfield darkness is lower strategic quality, not inactivity, route eviction, or stop-and-ruminate behavior.

### 9.1 Hard Invariants

```text
No Farfield thread may call path cancellation, route replacement, or path launch.
No Farfield refresh flag may be consumed as a physical-replan command.
No running route may become stale solely because Farfield epoch advanced.
No candidate may observe a mutable Farfield object after launch; it observes a snapshot.
No improvement threshold may cause a calculation to start; it may only break numerical ties during acceptance.
```

### 9.2 Allowed Interrupts

These are execution facts and may force immediate action:

```text
current exact movement invalidated by loaded block facts;
current route footprint intersects changed terrain;
controller cannot continue safely;
user changes goal or cancels;
transport/regime safety lock is violated.
```

These are advisory facts and may not force immediate action:

```text
Farfield posterior improved;
Bellman values converged further;
rich evidence arrived outside exact terrain;
coarse exit ranking changed;
prior/mean field was replaced by a richer snapshot.
```

## 10. Chunk Load And Repath Cycle

The intended runtime loop:

```text
1. Player executes current exact route.
2. Chunks load and pathing facts change.
3. Exact graph state receives fact deltas.
4. Farfield receives updated column evidence asynchronously or in batch.
5. Frontier exits into newly exact chunks are removed.
6. New frontier exits beyond the expanded exact region are discovered.
7. Hot local reverse repair updates values.
8. Planner extracts a replacement from a safe backoff anchor.
9. RouteExecutor accepts only valid, objective-improving or safety-required replacements.
```

The current route should not stop merely because Farfield is refreshing. Farfield darkness falls back to prior values. Exact route invalidation is the only hard interrupt.

Backoff anchor policy:

```text
choose the earliest safe point on the current route that:
  is not inside an unsafe current movement;
  remains consistent with current facts;
  is far enough ahead to avoid thrash;
  is close enough behind the new improvement to preserve progress.
```

This is the same “TCP-ish” planning philosophy already used for hot replanning: back off aggressively when tails go stale, claw forward when replacements land cleanly.

## 11. Transit Quarantine

Stage 1 must compile and behave as if Transit does not exist for Farfield decisions.

Forbidden in Stage 1:

```text
portal plan priority;
portal task intents selected by Farfield;
boat route sessions selected by Farfield;
strategic swim/water plans selected by Farfield;
resource-gather same-voxel upgrade edges;
bridge/tunnel meso-task phases beyond ordinary exact movement primitives;
capability product states such as PortalKitBand, BlocksBand, BoatBand.
```

Allowed:

```text
ordinary exact movement primitives that already exist in local pathing;
ordinary exact block placing/breaking costs;
Farfield scalar penalties that make void/lava/solid continuation expensive;
Transit code remaining in the tree if bypassed and clearly named as Transit.
```

The first implementation should have a hard switch:

```text
Transit disabled in Farfield-driven pedestrian planning.
```

After Farfield is stable, Transit can re-enter as explicit candidate routes or edge families that compete against:

```text
exact prefix + Farfield continuation
```

never by priority order.

## 12. Rectifying The Current Voxel Work

### 12.1 Salvage

Keep the ideas and, where useful, the code structure:

```text
MacroContinuationField      → Farfield
MacroVoxelPriorField        → FarfieldPrior
MacroVoxelCosts             → FarfieldCosts
MacroVoxelWorldProfile      → FarfieldWorldProfile or ColumnEvidenceScanner
Dense value-field arrays     → 2D FarfieldBellmanField
expected/floor split         → keep
mean/prior/rich split        → keep
async refresh discipline     → keep
```

### 12.2 Cull

Delete from Stage 1:

```text
MacroVoxelKey
MacroVoxelProfile
MacroVoxelVolumeAtlas
MacroVoxelValueField
DenseMacroVoxelValueField as a 3D object
MacroSelectedEdge
MacroPortalEdgeSnapshot
MacroPortalRoute
PortalKitBand
unified multimodal state-product machinery
```

If a class contains reusable array mechanics, extract the mechanics into a column field and delete the 3D shell. Do not keep a parallel voxel implementation “just in case.” “Parked” means culled; abandoned runtime code is not allowed to linger in a side chamber.

### 12.3 Rename Away From Legacy Macro

The new names should be semantically sharp:

```text
Farfield
FarfieldColumnKey
FarfieldColumnProfile
FarfieldEvidence
FarfieldPrior
FarfieldCosts
FarfieldBellmanField
FarfieldSnapshot
FrontierExit
FrontierValueObjective
Transit
TransitCandidate
TransitIntent
PathingRegime
LocomotionMode
```

The word `macro` should survive only in legacy compatibility code that has not yet been rectified. New Stage 1 code should not introduce it.

## 13. Dimension V0s

### 13.1 End

The End v0 column classifier needs only:

```text
END_ISLAND / OPEN_SURFACE
VOID_OR_GAP
UNKNOWN
```

Loaded/cached columns with substantial end-stone support become `OPEN_SURFACE`. Columns with no support and void-like air become `VOID_OR_GAP`.

This is enough to prevent Farfield from treating island detours and void bridges as identical. It does not prevent an exact bridge if exact cost says abundant blocks make the bridge optimal.

### 13.2 Nether

The Nether v0 column classifier:

```text
OPEN_SURFACE
LAVA
SOLID
UNKNOWN
```

Do not reintroduce full y voxels. If needed, add a weak height prior:

```text
floor/ceiling strata more likely solid;
low strata more likely lava/solid if evidence supports it;
middle strata neutral.
```

### 13.3 Overworld

Overworld v0 can be deliberately dull:

```text
mostly OPEN_SURFACE with mild UNKNOWN penalty
water/lava/solid summaries only when cheap evidence exists
```

The point is API universality, not overworld empirical brilliance.

## 14. Settings

Settings should match the ontology.

They should also obey the Golden Default orthogonality law: Farfield gets a small basis of independent economic and scheduling quantities, not a bag of compensating thresholds. If two values are coupled by mathematics, encode the coupling in code and tune the primitive factor.

Suggested first split:

```text
farfieldPlanning
  Enables scalar continuation values beyond exact terrain.

transitPlanning
  Enables explicit multimodal/meso-task route candidates.

renderFarfield
  Draws Farfield skeleton/value hints.

renderTransit
  Draws Transit candidate/itinerary hints.
```

`macroPlanning` is retired. In new code and docs it must not appear.

The de novo Farfield basis should be approximately:

```text
feature gates:
  farfieldPlanning
  renderFarfield

geometry / solver envelope:
  farfieldHorizonBlocks
  farfieldLateralCells

execution-pull cadence:
  farfieldReplanLatencySafetyFactor
  farfieldReplanFixedBufferTicks

economics:
  farfieldOpenTicksPerBlock
  farfieldVoidMultiplier
  farfieldLavaMultiplier
  farfieldSolidMultiplier
  farfieldUnknownMixtureByDimension
  farfieldVerticalUpMultiplier
  farfieldVerticalDownMultiplier
```

Forbidden as long-term Farfield knobs:

```text
refresh-initiated physical replans;
large Farfield preemption-improvement thresholds;
independent unknown ticks when unknown is a mixture of regimes;
separate waypoint distance if it only mirrors horizon/terminal proximity;
any value that changes exact movement truth or loaded-terrain dominance.
```

For manual testing:

```text
farfieldPlanning=false
```

must mean:

```text
no Farfield local-exit objective;
no Farfield skeleton overlay;
no hidden Farfield value in exact path scoring.
```

## 15. Telemetry

Every accepted path calculation should be able to report:

```text
regime
mode at route start
farfield enabled/disabled
transit enabled/disabled
exact nodes expanded
exact edges evaluated
frontier exits discovered
frontier exits selected
farfield snapshot epoch
farfield profile: PRIOR / BELLMAN / RICH
farfield snapshot age at calculation launch
farfield snapshots pulled by pathing
farfield refreshes published
farfield-triggered replans, which must always be zero
route replans launched from execution geometry
terminal kind: FINAL_GOAL / FRONTIER_EXIT
replacement cause: chunk_load / route_invalid / scheduled_repath / timeout
anchor kind: physical / current_route / future_tail
candidate objective
incumbent objective
accept/reject reason
```

Rendering should make the split obvious:

```text
Exact current route: executable path color
Exact search probe: diagnostic blue/cyan
Frontier exits: small boundary markers
Farfield skeleton: muted continuation hint
Transit: absent in Stage 1
```

No user should see a Farfield line after Farfield is disabled.

## 16. Tests

### 16.1 Unit Tests

```text
FarfieldColumnKey floorDiv roundtrip, including negative x/z.

Column profile normalization.

floor ≤ expected for every profile/cost combination.

End classifier:
  end-stone support column → OPEN_SURFACE
  unsupported void column → VOID_OR_GAP
  unknown column → UNKNOWN prior

Nether classifier:
  lava-heavy column → LAVA
  solid geological column → SOLID
  supported open column → OPEN_SURFACE

FrontierExit discovery:
  exact movement inside pathing data does not create exit
  edge crossing absent chunk creates exit at source node
  newly loaded destination chunk removes old exit

FrontierValueObjective:
  final goal terminal beats Farfield
  frontier terminal uses expected value
  heuristic uses floor only
  no interior coarse-cell exit exists
```

### 16.2 Synthetic Scenarios

End island fork:

```text
loaded detour over islands vs direct void bridge;
Exact should choose by real block cost;
Farfield should not terminate inside loaded island terrain.
```

End abundant-block bridge:

```text
huge inventory makes a direct loaded bridge actually cheaper;
bridge is allowed if Exact chose it honestly.
```

End Farfield-off:

```text
farfieldPlanning=false;
no Farfield route overlay;
no Value/Frontier objective;
legacy exact behavior only.
```

Nether open-vs-lava-vs-solid:

```text
equal-distance exits into open, lava, and solid priors;
Farfield values rank open < lava < solid.
```

Chunk-load repair:

```text
initial route exits at frontier;
load chunk beyond exit;
old exit removed;
new exact route continues through loaded terrain without stop-and-ruminate behavior.
```

Passive Farfield:

```text
advance Farfield epoch repeatedly while an exact route is executing;
no replan launches solely from epoch advance;
next ordinary tail/suffix calculation observes newest snapshot;
stale snapshot candidate may be rejected, but current route is not evicted by Farfield freshness.
```

### 16.3 Live-Fire Acceptance

Initial gates:

```text
No horse regression.
No pedestrian short-distance regression when Farfield disabled.
End manual routes no longer show Farfield overlay when disabled.
End loaded-island detours are available to Exact before Farfield handoff.
Nether A/B/C do not regress catastrophically under trivial Farfield priors.
Time to first movement remains governed by exact/prior pathing, not rich refresh.
No stall or route eviction is caused by Farfield refresh alone.
```

## 17. Implementation Slice

The least damaging first slice:

```text
1. Introduce names/types:
   Farfield, FarfieldSnapshot, FarfieldColumnKey, FrontierExit, FrontierValueObjective.

2. Add settings:
   farfieldPlanning, transitPlanning, renderFarfield.
   Keep old settings only as temporary aliases if required by config migration.

3. Implement 2D column prior field:
   End and Nether basic classifiers; Overworld dull fallback.

4. Implement or adapt dense 2D Bellman field:
   expected/floor arrays; 8-neighbor virtual edges.

5. Change hot local terminal discovery:
   remove coarse exactLocalExitAtBlock;
   create terminals from exact boundary contacts.

6. Wire Farfield snapshot into hot local:
   terminal cost increases/decreases repair correctly;
   lost deltas invalidate conservatively.

7. Disable Transit participation:
   no portal/water priority in Farfield-driven pathing.

8. Render and telemetry:
   make Exact/Farfield split visible.

9. Purge 3D voxel shells:
   keep only distilled column machinery.
```

Do not implement portals in this pass. Do not debug portal costing in this pass. Do not revive water priority in this pass.

## 18. Acceptance Invariant

The Stage 1 implementation is acceptable only when this sentence is true in code, logs, and rendering:

```text
Exact pathing consumes all available pathing data first;
Farfield prices only exact frontier exits;
Transit is absent.
```

Everything else is subordinate.
