# Seed-Pure Exact A* Oracle

Status: implemented v0 with exact Nether A/B/C golden references  
Date: 2026-05-22  
Target branch: `pedestrian-kaizen`  
Scope: offline measurement oracle for long-distance pathing

## 1. Purpose

Farfield is a lossy continuation-value system. Its quality cannot be judged by success alone. A run that succeeds in 5000 ticks may be excellent if the omniscient local planner would require 4900 ticks, and poor if the omniscient local planner would require 3000 ticks.

This spec defines the reference measurement:

```text
true optimum relative to Baritone's exact A* movement graph and nominal edge-cost model
```

It is not the mathematical optimum over all possible Minecraft controls. It is not a full physics execution proof. It is the best route our ordinary exact A* would choose if it had perfect world geometry, unlimited chunk access, and no client load-distance blindness.

The core metric is regret:

```text
Farfield route objective / seed-pure exact-A* oracle objective
```

The oracle exists to answer:

```text
How much quality do we lose by replacing omniscient exact terrain knowledge with Farfield?
```

If the oracle chooses a path whose nominal cost is excellent but execution is slow, damaging, or impossible, that is a movement-cost, movement-certification, or controller bug. It is not a Farfield bug.

Implementation note, 2026-05-22: the v0 harness exists as a dedicated-server inbox runner (`scripts/oracle_a_star` plus `baritone.oracle.AStarOracleHarness`). It is seed-pure, uses real server chunk generation to `FULL`, feeds ordinary A*, disables loaded-chunk cutoffs, disables the server watchdog for oracle runs, and emits stop-reason telemetry. The initial h=10/h=12 guided runs are now only admissible as pruning ceilings and sanity checks. The accepted A/B/C references below are ordinary-heuristic `costHeuristic=3.563` runs that reached the real final goal with `quality.class = ORDINARY_ASTAR_GOAL`. Their optimality is therefore as strong as Baritone's ordinary A* heuristic contract.

## 2. Hard Doctrine

Minecraft world generation is a volatile black box.

The oracle must not reimplement, approximate, shortcut, or outguess chunk generation:

```text
no direct noise sampling;
no hand-modeled Nether terrain;
no inferred carvers/features;
no copied "equivalent" generator logic;
no use of Baritone's simplified cached-region format as truth.
```

Chunks must be produced by the real Minecraft/Fabric server generation pipeline for the exact target version, mod set, datapacks, registry, seed, and dimension. The oracle treats that machinery as an opaque function:

```text
GenerateFullChunk(worldSpec, chunkX, chunkZ) -> exact block/fluid/biome facts
```

The only allowed optimization is caching the resulting immutable chunk snapshot inside one oracle run.

## 3. Pure Function Contract

Semantically, the oracle is a pure function:

```text
Oracle(
  MinecraftVersion,
  ModSetHash,
  DatapackHash,
  RegistryHash,
  WorldSeed,
  LevelStem / GeneratorOptions,
  Dimension,
  Start,
  Goal,
  BaritoneSettings,
  MovementProfile,
  SearchBounds
) -> OracleResult
```

No persisted world is an input. A temp save directory may be used only because Minecraft's server code expects storage. It is scratch:

```text
create temp level from OracleWorldSpec
run oracle
dump result JSON
delete temp level
```

Repeated runs with the same `OracleWorldSpec` and same search bounds must produce the same result. If they do not, the oracle has a determinism bug.

## 4. Architecture

The oracle is a headless server-world fact provider feeding unchanged exact A*:

```text
oracle-a-star CLI
        ↓
EphemeralOracleServer
  boots a headless ServerLevel from seed/spec
        ↓
OracleChunkService
  lazily asks vanilla/Fabric to generate requested chunks to FULL status
        ↓
Immutable OracleChunk snapshots
  compact exact block/fluid/height facts
        ↓
OracleBlockStateInterface
  never reports "unloaded"; on miss, requests a chunk snapshot
        ↓
ordinary CalculationContext
        ↓
ordinary AStarPathFinder to the real final Goal
        ↓
OracleResult JSON
```

A* is not special-cased. It must believe it is talking to an ordinary world fact surface. The special behavior belongs entirely below `BlockStateInterface`: chunk misses cause exact lazy generation instead of an unloaded-chunk result.

### 4.1 Process Shape

Implementation adds a separate dedicated-server entrypoint rather than a client mode:

```text
scripts/oracle_a_star
src/main/java/baritone/oracle/AStarOracleHarness.java
```

The script launches the playtest Fabric dedicated server with oracle inbox/results system properties. `AStarOracleHarness` consumes one JSON request from the server tick, runs the search against a `ServerLevel`, writes the result JSON, and the wrapper tears the scratch world down. Rendering, sound, particles, input, and the client loop are out of scope.

### 4.2 Thread Ownership

Direct live `ServerLevel` access from the A* hot loop is forbidden.

Use a two-surface model:

```text
server/chunkgen owner thread:
  owns ServerLevel / ChunkSource access
  fulfills chunk generation requests
  snapshots full chunks

A* thread:
  reads immutable OracleChunk arrays
  blocks only on a chunk-cache miss
```

This avoids mixing pathing expansion with Minecraft's world-thread assumptions and keeps hot block queries as array reads after the first chunk miss.

If the first implementation is single-threaded for expedience, the same ownership boundary should still be expressed in types:

```text
only OracleChunkService touches ServerLevel;
only OracleBlockStateInterface touches snapshots.
```

## 5. World Fact Surface

### 5.1 OracleWorldSpec

`OracleWorldSpec` is the stable identity of generated terrain:

```java
record OracleWorldSpec(
  String minecraftVersion,
  String modSetHash,
  String datapackHash,
  String registryHash,
  long seed,
  ResourceKey<LevelStem> levelStem,
  ResourceKey<Level> dimension,
  String generatorOptionsHash
) {}
```

The exact field set can follow what Fabric/Minecraft exposes, but the invariant is non-negotiable: every input that could change generated terrain must be represented in the identity or the oracle result is not reproducible.

### 5.2 Chunk Generation

`OracleChunkService` exposes:

```java
OracleChunk snapshotFullChunk(int chunkX, int chunkZ);
```

The implementation requests the real chunk pipeline to produce a chunk at `FULL` status. Neighbor pulls, structures, carvers, decorations, lava pockets, biome features, and all other generation dependencies are left to vanilla/Fabric.

Important: requesting one target chunk may cause Minecraft to generate or load neighboring chunks internally. That is acceptable. The oracle result should count both:

```text
chunks requested by A*
chunks generated as side effects by the generator
```

but A* only sees snapshots for chunks it actually queries unless the service chooses to opportunistically snapshot generated neighbors.

### 5.3 OracleChunk

`OracleChunk` is immutable and compact:

```java
final class OracleChunk {
  int chunkX;
  int chunkZ;
  int minY;
  int maxYExclusive;

  BlockState state(int localX, int y, int localZ);
  FluidState fluid(int localX, int y, int localZ);
  int height(Heightmap.Types type, int localX, int localZ);
}
```

The first version may store direct `BlockState` and `FluidState` references if registry lifetime makes that safe. A later memory pass may palette-pack states by section. The oracle is not a playtest hot path, but it still must be fast enough for overnight suites.

### 5.4 OracleBlockStateInterface

This is the adapter consumed by pathing:

```java
final class OracleBlockStateInterface extends BlockStateInterface {
  BlockState get0(int x, int y, int z) {
    OracleChunk chunk = chunks.getOrGenerate(floorDiv(x, 16), floorDiv(z, 16));
    return chunk.state(floorMod(x, 16), y, floorMod(z, 16));
  }
}
```

The real code should respect the existing `BlockStateInterface` design rather than literally subclass if composition is cleaner. The invariant is:

```text
within dimension y-bounds, exact A* never receives "unloaded" from the oracle.
```

Out-of-world y remains out-of-world.

## 6. Search Semantics

The oracle runs ordinary exact A* to the final goal:

```text
Farfield: disabled
Transit: disabled
local-exit objectives: disabled
static/live-chunk cutoff: disabled
execution: disabled
controller: disabled
ordinary movement primitives: enabled according to the supplied MovementProfile
```

The search should use the same movement catalog and cost policy as playtests. Differences must be explicit in the result JSON.

### 6.1 Bounds

"Arbitrarily far" is operationally bounded. Exact A* over a break/place Nether graph can explode. The oracle therefore accepts defensive bounds:

```text
max generated chunks
max expanded nodes
max wall-clock time
optional corridor/envelope
```

Bounds are not semantic terrain inputs; they are proof limits. A bounded oracle result must say whether it is clean:

```text
SUCCESS_CLEAN
  reached the goal and the selected path has clearance from the imposed envelope/bounds

SUCCESS_BOUNDARY_TOUCHED
  reached the goal but the path touches or nearly touches the imposed envelope

SEARCH_EXHAUSTED
  failed by node/time/chunk cap
```

The current v0 JSON status names are:

```text
SUCCESS_TO_GOAL
  A* reached the final goal.

SUCCESS_SEGMENT
  A* produced a best-so-far segment but stopped before the final goal.

SEARCH_EXHAUSTED
  A* produced no usable path before the stop condition.
```

`search.stopReason` distinguishes `goal`, `node_cap`, `timeout`, `empty_chunk_limit`, `open_set_empty`, `cancel`, and related implementation stops.

### 6.2 Envelope Widening

For Nether A/B/C reference values, use iterative widening:

```text
direct start-goal capsule width 128
direct start-goal capsule width 256
direct start-goal capsule width 512
larger only if needed
```

Stop when:

```text
best nominal cost stabilizes within tolerance;
selected path has a healthy boundary margin;
no cap was hit.
```

This gives a defensible common-sense oracle without pretending the infinite world graph was proven globally exhausted.

## 7. Result Format

The oracle writes a single JSON artifact per run. The v0 artifact is deliberately self-labeling: a guided run with an overdriven `costHeuristic` must say that it is a guided upper bound or guided segment, never an optimality proof.

```json
{
  "schema": "baritone.oracle-a-star.v1",
  "status": "SUCCESS_TO_GOAL",
  "world": {
    "minecraftVersion": "26.1.2",
    "minecraftVersionId": "26.1.2",
    "dataVersion": 12345,
    "dataSeries": "main",
    "stableVersion": true,
    "dimension": "minecraft:the_nether",
    "scenarioSeed": "baritone-nether-thisway-500-0",
    "serverSeed": 12345
  },
  "scenario": "nether_thisway_500_a",
  "settingsCrc32": 2134257373,
  "bounds": {
    "timeoutMS": 180000,
    "pathingMaxNodes": 8000000,
    "oracleUpperBoundTicks": 4032.922
  },
  "quality": {
    "class": "GUIDED_UPPER_BOUND",
    "ordinaryCostHeuristic": 3.563,
    "appliedCostHeuristic": 10.0,
    "guided": true,
    "goalReached": true,
    "stopReason": "goal",
    "globalOptimalityProof": "none"
  },
  "objective": {
    "nominalCostTicks": 3712.4,
    "pathLengthBlocks": 684.2,
    "positionCount": 502
  },
  "search": {
    "stopReason": "goal",
    "nodesExpanded": 912344,
    "movementsConsidered": 1824688,
    "emptyChunkFetches": 0,
    "nodeMapSize": 1412230,
    "chunksRequestedByAStar": 487,
    "chunksSnapshotted": 487
  },
  "path": {
    "start": [0, 80, 0],
    "goal": [500, 80, 0],
    "crc32": 2385899346,
    "positions": [[0, 80, 0], [1, 80, 0]]
  }
}
```

The full path may be optionally elided or compressed for huge runs, but the reference artifact must preserve enough information to render and hand-check it.

## 8. Harness Integration

Add an oracle phase to long-run analysis:

```text
1. Compute or load seed-pure exact-A* oracle result for scenario/settings.
2. Run Farfield playtest normally.
3. Compare:
   executed ticks / oracle nominal ticks;
   Farfield planned nominal cost / oracle nominal cost when available;
   total stalled time;
   next-calc-failed count;
   tool damage;
   block break/place count;
   damage events.
```

The oracle result is not rerun for every tuning trial unless settings that affect movement legality/cost change. When settings change only Farfield economics, the same exact-A* oracle remains valid.

If movement costs, movement primitives, inventory capability, break/place policy, or tool assumptions change, the oracle key changes and the oracle must be recomputed.

## 9. Acceptance Test

The first accepted implementation must produce clean, sane-looking exact-A* oracle results for:

```text
scenarios/playtest/nether_thisway_500_a.json
scenarios/playtest/nether_thisway_500_b.json
scenarios/playtest/nether_thisway_500_c.json
```

Acceptance is not a formal proof of global optimality. It is a thorough common-sense review:

```text
the path reaches the intended goal;
the path is generated from seed, not a persisted semantic world;
the path uses real generated Minecraft chunks;
the path does not depend on Farfield or Transit;
the path has no static/live cutoff truncation;
the path does not hug the artificial envelope boundary;
the nominal cost is stable under at least one envelope widening;
the rendered/trace path looks like a plausible omniscient exact-A* route;
tool damage, break count, place count, and route shape are sane;
the result JSON is stable across repeated same-spec runs.
```

These oracle values then become the reference baseline for Nether A/B/C Farfield playtests.

Current exact golden references:

```text
Scenario  Heuristic  Status           Nominal ticks  Path length  Nodes popped  Generated chunks  Path CRC32
A         3.563      SUCCESS_TO_GOAL  3377.539       676.201      13530703      767               4293217852
B         3.563      SUCCESS_TO_GOAL  4213.904       767.154      25596821      1301              3664427594
C         3.563      SUCCESS_TO_GOAL  4366.273       774.027      40975147      1857              1143308554
```

Artifact paths:

```text
run/oracle-a-exact-50m/oracle-results/nether_thisway_500_a-oracle-20260522T233513Z-2.json
run/oracle-b-exact-50m-ub/oracle-results/nether_thisway_500_b-oracle-20260522T233932Z-2.json
run/oracle-c-exact-80m-ub/oracle-results/nether_thisway_500_c-oracle-20260522T234658Z-2.json
```

B and C used the earlier guided route costs as `oracleUpperBoundTicks` branch-and-bound ceilings. This does not change ordinary A* edge costs or heuristic semantics; it only prunes nodes whose admissible `g+h` already cannot improve a known valid route.

Determinism check:

```text
A repeated with the same seed/scenario/settings/bounds:
run/oracle-a-exact-repeat/oracle-results/nether_thisway_500_a-oracle-20260522T235916Z-2.json

cost, path length, position count, chunk count, path CRC, and full position list matched the original A artifact exactly.
Node pops differed by 3 due to equivalent-cost queue ordering after the same terminal path had become provable.
```

## 10. Failure Interpretation

Oracle failure modes are diagnostic:

`oracle cannot find a path`
: Either bounds are too tight, exact A* is too expensive, movement primitives are insufficient, or the scenario is genuinely unreachable under the supplied movement profile.

`oracle path executes badly`
: Movement cost, movement certification, or controller bug.

`Farfield succeeds but is much worse than oracle`
: Farfield profile/value/exit-selection bug.

`Farfield matches oracle nominally but playtest is slow`
: Controller, replanning, stall, or execution-cost accounting bug.

`oracle result changes across identical runs`
: Seed purity or generation determinism bug.

## 11. Implementation Notes

The likely lowest-risk implementation sequence is:

```text
1. Add the oracle request/result JSON schema.
2. Add a CLI skeleton that parses a playtest scenario and prints the resolved spec.
3. Boot an ephemeral headless ServerLevel from seed/spec.
4. Implement OracleChunkService with FULL-status generation and immutable snapshots.
5. Implement OracleBlockStateInterface / world fact adapter.
6. Run ordinary AStarPathFinder with Farfield/Transit/cutoff disabled.
7. Dump JSON and optional path trace.
8. Add envelope widening driver.
9. Add Nether A/B/C oracle scripts and hand-check rendering.
```

Do not begin by building a second A*. Do not begin by teaching Farfield about oracle mode. The oracle is a world-fact emulator for the existing exact planner.

## 12. Non-Goals

This spec does not require:

```text
full client boot;
rendering;
physics rollout;
entity simulation;
random tick simulation;
persisted world reuse;
globally proven infinite-world optimality;
Transit/portal/boat comparison;
Farfield implementation changes.
```

Those are separate machines. The oracle's job is narrower and sharper:

```text
same exact A*, same nominal movement model, perfect seed-generated world facts.
```
