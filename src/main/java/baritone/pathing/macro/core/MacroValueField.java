package baritone.pathing.macro.core;

import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.macro.value.DStarLiteValueField;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class MacroValueField {
  private static final int MAX_RENDER_VERTICES = 512;

  private MacroAtlas atlas;
  private final MacroValueGraph expectedGraph;
  private final MacroValueGraph floorGraph;
  private final DStarLiteValueField expected;
  private final DStarLiteValueField floor;
  private long start;
  private final long target;
  private DStarLiteValueField.RepairResult expectedRepair;
  private DStarLiteValueField.RepairResult floorRepair;

  private MacroValueField(MacroAtlas atlas, MacroValueGraph expectedGraph, MacroValueGraph floorGraph, DStarLiteValueField expected, DStarLiteValueField floor, long start, long target,
    DStarLiteValueField.RepairResult expectedRepair, DStarLiteValueField.RepairResult floorRepair) {
    this.atlas = atlas;
    this.expectedGraph = expectedGraph;
    this.floorGraph = floorGraph;
    this.expected = expected;
    this.floor = floor;
    this.start = start;
    this.target = target;
    this.expectedRepair = expectedRepair;
    this.floorRepair = floorRepair;
  }

  static MacroValueField build(MacroAtlas atlas, MacroPolicy policy, MacroTraversalProfile profile, long start, long target, int minCellX, int maxCellX, int minCellZ, int maxCellZ,
    double expectedTerminal, double floorTerminal) {
    MacroValueGraph expectedGraph = new MacroValueGraph(atlas, policy, profile, minCellX, maxCellX, minCellZ, maxCellZ, false);
    MacroValueGraph floorGraph = new MacroValueGraph(atlas, policy, profile, minCellX, maxCellX, minCellZ, maxCellZ, true);
    DStarLiteValueField expected = new DStarLiteValueField(expectedGraph, start);
    DStarLiteValueField floor = new DStarLiteValueField(floorGraph, start);
    expected.setTerminal(target, expectedTerminal);
    floor.setTerminal(target, floorTerminal);
    DStarLiteValueField.RepairResult expectedRepair = expected.repairAll();
    DStarLiteValueField.RepairResult floorRepair = floor.repairAll();
    return new MacroValueField(atlas, expectedGraph, floorGraph, expected, floor, start, target, expectedRepair, floorRepair);
  }

  void repair(MacroAtlas nextAtlas, long nextStart, double expectedTerminal, double floorTerminal) {
    this.atlas = nextAtlas;
    this.start = nextStart;
    expectedGraph.retarget(nextAtlas);
    floorGraph.retarget(nextAtlas);
    expected.moveStart(nextStart);
    floor.moveStart(nextStart);
    expected.setTerminal(target, expectedTerminal);
    floor.setTerminal(target, floorTerminal);
    invalidateEnvelope(expected);
    invalidateEnvelope(floor);
    expectedRepair = expected.repairAll();
    floorRepair = floor.repairAll();
  }

  public double expectedContinuationAtBlock(int x, int y, int z) {
    return adjusted(value(expected, x, z), x, z, false);
  }

  public double admissibleFloorAtBlock(int x, int y, int z) {
    return adjusted(value(floor, x, z), x, z, true);
  }

  public boolean exactLocalExitAtBlock(int x, int y, int z) {
    int cellX = Math.floorDiv(x, atlas.cellBlocks());
    int cellZ = Math.floorDiv(z, atlas.cellBlocks());
    if (!atlas.factual(cellX, cellZ)) {
      return false;
    }
    long key = MacroNodeKey.cell(atlas.dimension(), MacroStratum.SURFACE, atlas.scale(), cellX, cellZ);
    var successor = expected.bestSuccessor(key);
    if (successor.isEmpty()) {
      return false;
    }
    long next = successor.getAsLong();
    return !atlas.factual(MacroNodeKey.cellX(next), MacroNodeKey.cellZ(next));
  }

  public Optional<BetterBlockPos> preferredLocalExit() {
    List<BetterBlockPos> path = preferredLocalExitPath();
    return path.isEmpty() ? Optional.empty() : Optional.of(path.getLast());
  }

  public List<BetterBlockPos> preferredLocalExitPath() {
    ArrayList<BetterBlockPos> path = new ArrayList<>();
    long cursor = start;
    for (int i = 0; i < MAX_RENDER_VERTICES && cursor != target; i++) {
      var successor = expected.bestSuccessor(cursor);
      if (successor.isEmpty()) {
        break;
      }
      long next = successor.getAsLong();
      int cellX = MacroNodeKey.cellX(next);
      int cellZ = MacroNodeKey.cellZ(next);
      if (!atlas.factual(cellX, cellZ)) {
        break;
      }
      BetterBlockPos pos = atlas.center(cellX, cellZ);
      if (!path.isEmpty() && path.getLast().equals(pos)) {
        cursor = next;
        continue;
      }
      path.add(pos);
      cursor = next;
    }
    return path;
  }

  public MacroPlan plan(BetterBlockPos physicalStart, BetterBlockPos physicalDest, Goal localGoal, MacroPolicy policy) {
    ArrayList<MacroPlanVertex> vertices = skeleton();
    ArrayList<BetterBlockPos> render = new ArrayList<>(vertices.size());
    int live = 0;
    int cached = 0;
    int predicted = 0;
    int prior = 0;
    for (MacroPlanVertex vertex : vertices) {
      render.add(vertex.pos());
      switch (vertex.evidence()) {
        case LIVE -> live++;
        case CACHED -> cached++;
        case PREDICTED -> predicted++;
        case PRIOR -> prior++;
      }
    }
    int factual = live + cached + predicted;
    int unknown = prior;
    MacroCostVector vector = MacroCostVector.fixedTime(expected.value(start));
    return new MacroPlan(physicalStart, physicalDest, localGoal, render, List.of(), vector, expected.value(start), atlas.cellBlocks(), factual, unknown, live, cached, predicted, prior, vertices, "V",
      "VALUE_FIELD", telemetry());
  }

  public MacroValueTelemetry telemetry() {
    return new MacroValueTelemetry(expected.value(start), floor.value(start), expectedRepair.queuePops(), floorRepair.queuePops(), expectedRepair.queued(), floorRepair.queued(),
      atlas.center(MacroNodeKey.cellX(target), MacroNodeKey.cellZ(target)), "dstar-lite");
  }

  private double value(DStarLiteValueField field, int blockX, int blockZ) {
    int cellX = Math.floorDiv(blockX, atlas.cellBlocks());
    int cellZ = Math.floorDiv(blockZ, atlas.cellBlocks());
    long key = MacroNodeKey.cell(atlas.dimension(), MacroStratum.SURFACE, atlas.scale(), cellX, cellZ);
    return field.value(key);
  }

  private double adjusted(double cellValue, int blockX, int blockZ, boolean lower) {
    if (!Double.isFinite(cellValue)) {
      return Double.POSITIVE_INFINITY;
    }
    int cellX = Math.floorDiv(blockX, atlas.cellBlocks());
    int cellZ = Math.floorDiv(blockZ, atlas.cellBlocks());
    BetterBlockPos center = atlas.center(cellX, cellZ);
    double discount = Math.hypot(blockX - center.x, blockZ - center.z) * baritone.Baritone.settings().costHeuristic.value;
    return lower ? Math.max(0D, cellValue - discount) : Math.max(0D, cellValue - discount * 0.5D);
  }

  private ArrayList<MacroPlanVertex> skeleton() {
    ArrayList<MacroPlanVertex> vertices = new ArrayList<>();
    long cursor = start;
    for (int i = 0; i < MAX_RENDER_VERTICES; i++) {
      addVertex(vertices, cursor);
      if (cursor == target) {
        break;
      }
      var next = expected.bestSuccessor(cursor);
      if (next.isEmpty()) {
        break;
      }
      cursor = next.getAsLong();
    }
    return vertices;
  }

  private void addVertex(ArrayList<MacroPlanVertex> vertices, long node) {
    int x = MacroNodeKey.cellX(node);
    int z = MacroNodeKey.cellZ(node);
    MacroPlanVertex vertex = new MacroPlanVertex(atlas.center(x, z), atlas.evidence(x, z));
    if (vertices.isEmpty() || !vertices.get(vertices.size() - 1).pos().equals(vertex.pos())) {
      vertices.add(vertex);
    }
  }

  private void invalidateEnvelope(DStarLiteValueField field) {
    for (int x = expectedGraph.minCellX(); x <= expectedGraph.maxCellX(); x++) {
      for (int z = expectedGraph.minCellZ(); z <= expectedGraph.maxCellZ(); z++) {
        field.invalidate(MacroNodeKey.cell(atlas.dimension(), MacroStratum.SURFACE, atlas.scale(), x, z));
      }
    }
  }
}
