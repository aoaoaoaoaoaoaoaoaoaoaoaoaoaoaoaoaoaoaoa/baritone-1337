package baritone.pathing.macro.water;

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.macro.core.MacroActionKind;
import baritone.pathing.macro.core.MacroAgentState;
import baritone.pathing.macro.core.MacroCostVector;
import baritone.pathing.macro.core.MacroDomain;
import baritone.pathing.macro.core.MacroExpansionContext;
import baritone.pathing.macro.core.MacroLabel;
import baritone.pathing.macro.core.MacroNodeKey;
import baritone.pathing.macro.core.MacroOption;
import baritone.pathing.macro.core.MacroOptionSink;
import baritone.pathing.macro.core.MacroStratum;
import baritone.pathing.macro.core.MacroSurfaceTransition;
import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.water.WaterLineKernel;
import baritone.pathing.movement.water.WaterLineProfile;
import baritone.pathing.transport.TransportMode;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;

public final class WaterTransportDomain implements MacroDomain {
  private static final int[] D = {-1, 0, 1};
  private static final double SWIM_ENTRY_COST = 30D;
  private static final double SWIM_EXIT_COST = 30D;
  private static final double SWIM_BANK_PRESSURE_COST = 0.18D;
  private static final double BOAT_BANK_PRESSURE_COST = 0.35D;
  private static final int MAX_EXPANSIONS = 30_000;
  private static final int PREDICTED_WATER_COMPONENT = -1;

  private final HashMap<SurfaceWaterSkeleton.ComponentKey, ArrayList<SurfaceWaterSkeleton.Anchor>> transitTargetsByComponent = new HashMap<>();
  private final HashMap<SurfaceWaterSkeleton.ComponentKey, TransitField> transitFieldsByComponent = new HashMap<>();
  private final SurfaceWaterAtlas water;
  private final SurfaceWaterSkeleton skeleton;
  private final boolean predictedWaterPresent;

  public WaterTransportDomain(MacroExpansionContext context) {
    this.water = context.atlas().water().orElse(null);
    this.skeleton = water == null ? null : SurfaceWaterSkeleton.build(context, water);
    this.predictedWaterPresent = predictedWaterPresent(context);
    indexTransitTargets(context);
  }

  private void indexTransitTargets(MacroExpansionContext context) {
    int limit = Math.max(8, Baritone.settings().macroWaterMaxLaunchCandidates.value);
    if (skeleton == null) {
      return;
    }
    for (TransportMode mode : List.of(TransportMode.BOAT, TransportMode.SWIM)) {
      List<WaterComponent> components = mode == TransportMode.BOAT ? water.boatComponents() : water.swimComponents();
      for (WaterComponent waterComponent : components) {
        SurfaceWaterSkeleton.ComponentKey component = new SurfaceWaterSkeleton.ComponentKey(mode, waterComponent.id());
        ArrayList<SurfaceWaterSkeleton.Anchor> anchors = skeleton.anchors(component);
        if (anchors == null || anchors.isEmpty()) {
          continue;
        }
        ArrayList<SurfaceWaterSkeleton.Anchor> targets = new ArrayList<>(anchors.stream().filter(anchor -> !anchor.current()).toList());
        if (targets.isEmpty()) {
          continue;
        }
        targets.sort(Comparator.comparingDouble(anchor -> context.goal().heuristic(anchor.dry().x, anchor.dry().y, anchor.dry().z)));
        if (targets.size() > limit) {
          targets.subList(limit, targets.size()).clear();
        }
        transitTargetsByComponent.put(component, targets);
      }
    }
  }

  @Override
  public String id() {
    return "water";
  }

  public boolean empty() {
    return !predictedWaterPresent && (skeleton == null || skeleton.empty() || transitTargetsByComponent.isEmpty());
  }

  public int entryCellCount() {
    return skeleton == null ? 0 : skeleton.entryCellCount();
  }

  public int anchorCount() {
    return skeleton == null ? 0 : skeleton.anchorCount();
  }

  public int targetComponentCount() {
    return transitTargetsByComponent.size();
  }

  public Optional<SurfaceWaterSkeleton.Anchor> currentWaterAnchor(TransportMode mode) {
    return skeleton == null ? Optional.empty() : skeleton.currentWaterAnchor(mode);
  }

  @Override
  public void expand(MacroExpansionContext context, MacroLabel label, MacroOptionSink out) {
    if (MacroNodeKey.anchorKey(label.node())) {
      if (skeleton == null || water == null) {
        return;
      }
      SurfaceWaterSkeleton.Anchor anchor = skeleton.anchor(label.node());
      if (anchor == null || label.state().mode() != anchor.mode()) {
        return;
      }
      emitTransit(context, label, out, anchor);
      emitExit(context, label, out, anchor);
      return;
    }
    MacroStratum stratum = MacroNodeKey.stratum(label.node());
    if (stratum == MacroStratum.WATER_SURFACE && label.state().surfaceWaterborne()) {
      emitPredictedTransit(context, label, out);
      emitPredictedExit(context, label, out);
      return;
    }
    if (stratum != MacroStratum.SURFACE || !label.state().pedestrianMode()) {
      return;
    }
    if (skeleton != null && water != null) {
      emitEnter(context, label, out, skeleton.entries(label.node()));
    }
    emitPredictedEnter(context, label, out);
  }

  private void emitEnter(MacroExpansionContext context, MacroLabel label, MacroOptionSink out, ArrayList<SurfaceWaterSkeleton.Anchor> anchors) {
    if (anchors == null || anchors.isEmpty()) {
      return;
    }
    for (SurfaceWaterSkeleton.Anchor anchor : selectedEntries(context, anchors)) {
      MacroCostVector cost = anchor.mode() == TransportMode.BOAT ? MacroCostVector.fixedTime(context.calculation().waterTransport.boatSetupCost() + currentDryApproachCost(anchor))
        : MacroCostVector.fixedTime(SWIM_ENTRY_COST + currentDryApproachCost(anchor));
      MacroAgentState next = anchor.mode() == TransportMode.BOAT ? MacroAgentState.boat() : MacroAgentState.swim();
      MacroSurfaceTransition transition = MacroSurfaceTransition.enter(anchor.mode(), anchor.dry(), anchor.water(), anchor.componentId());
      out.accept(new MacroOption(MacroActionKind.SURFACE_ENTER, anchor.node(), next, cost, List.of(anchor.dry(), anchor.water()), transition));
    }
  }

  private static double currentDryApproachCost(SurfaceWaterSkeleton.Anchor anchor) {
    return anchor.current() && !anchor.waterborne() ? flatDistance(anchor.dry(), anchor.water()) * ActionCosts.SPRINT_ONE_BLOCK_COST : 0D;
  }

  private static List<SurfaceWaterSkeleton.Anchor> selectedEntries(MacroExpansionContext context, ArrayList<SurfaceWaterSkeleton.Anchor> anchors) {
    int limit = Baritone.settings().macroWaterMaxLaunchCandidates.value;
    ArrayList<SurfaceWaterSkeleton.Anchor> candidates = new ArrayList<>(anchors.stream().filter(anchor -> anchor.mode() == TransportMode.SWIM || context.capabilities().boatAvailable()).toList());
    ArrayList<SurfaceWaterSkeleton.Anchor> result = new ArrayList<>(Math.min(limit, candidates.size()));
    candidates.stream().filter(SurfaceWaterSkeleton.Anchor::current).forEach(anchor -> addUnique(result, anchor, limit));
    candidates.stream().sorted(Comparator.comparingDouble(anchor -> flatDistance(context.physicalStart(), anchor.dry()))).limit(Math.max(1, limit / 2))
      .forEach(anchor -> addUnique(result, anchor, limit));
    candidates.stream().sorted(Comparator.comparingDouble(anchor -> context.goal().heuristic(anchor.dry().x, anchor.dry().y, anchor.dry().z))).limit(limit)
      .forEach(anchor -> addUnique(result, anchor, limit));
    return result;
  }

  private static void addUnique(ArrayList<SurfaceWaterSkeleton.Anchor> anchors, SurfaceWaterSkeleton.Anchor anchor, int limit) {
    if (anchors.size() < limit && !anchors.contains(anchor)) {
      anchors.add(anchor);
    }
  }

  private void emitTransit(MacroExpansionContext context, MacroLabel label, MacroOptionSink out, SurfaceWaterSkeleton.Anchor from) {
    TransitPath path = transitField(context, new SurfaceWaterSkeleton.ComponentKey(from.mode(), from.componentId())).pathFrom(from.water().asLong(), from.node());
    if (path == null || path.distance() <= 0D || path.target().node() == from.node()) {
      return;
    }
    MacroCostVector cost = from.mode() == TransportMode.BOAT ? MacroCostVector.boatTransit(path.distance(), context.calculation().waterTransport.boatCostPerBlock())
      : MacroCostVector.surfaceSwim(path.distance(), context.calculation().costs.waterMoveCost());
    MacroSurfaceTransition transition = MacroSurfaceTransition.transit(from.mode(), from.water(), path.target().water(), from.componentId(), path.positions(), path.distance());
    out.accept(new MacroOption(MacroActionKind.SURFACE_TRANSIT, path.target().node(), label.state(), cost, path.positions(), transition));
  }

  private void emitExit(MacroExpansionContext context, MacroLabel label, MacroOptionSink out, SurfaceWaterSkeleton.Anchor anchor) {
    MacroCostVector cost = anchor.mode() == TransportMode.BOAT ? MacroCostVector.fixedTime(context.calculation().waterTransport.boatPickupCost()) : MacroCostVector.fixedTime(SWIM_EXIT_COST);
    MacroSurfaceTransition transition = MacroSurfaceTransition.exit(anchor.mode(), anchor.water(), anchor.dry(), anchor.componentId());
    out.accept(new MacroOption(MacroActionKind.SURFACE_EXIT, anchor.surfaceCell(), label.state().afterSurfaceExit(), cost, List.of(anchor.water(), anchor.dry()), transition));
  }

  private static boolean predictedWaterPresent(MacroExpansionContext context) {
    if (!context.predictedWaterAllowed()) {
      return false;
    }
    for (int x = context.minCellX(); x <= context.maxCellX(); x++) {
      for (int z = context.minCellZ(); z <= context.maxCellZ(); z++) {
        if (context.atlas().surfaceWaterPrior(x, z)) {
          return true;
        }
      }
    }
    return false;
  }

  private static void emitPredictedEnter(MacroExpansionContext context, MacroLabel label, MacroOptionSink out) {
    if (!context.predictedWaterAllowed()) {
      return;
    }
    int x = MacroNodeKey.cellX(label.node());
    int z = MacroNodeKey.cellZ(label.node());
    if (predictedWaterCell(context, x, z)) {
      return;
    }
    BetterBlockPos dry = predictedSurfacePos(context, x, z);
    for (int dx : D) {
      for (int dz : D) {
        int wx = x + dx;
        int wz = z + dz;
        if (!predictedWaterCell(context, wx, wz)) {
          continue;
        }
        BetterBlockPos water = context.atlas().surfaceWaterCenter(wx, wz);
        double approach = dryApproachCost(context, x, z, dry, water);
        long node = predictedWaterNode(context, wx, wz);
        if (context.capabilities().boatAvailable()) {
          out.accept(new MacroOption(MacroActionKind.SURFACE_ENTER, node, MacroAgentState.boat(), MacroCostVector.fixedTime(context.calculation().waterTransport.boatSetupCost() + approach),
            List.of(dry, water), MacroSurfaceTransition.enter(TransportMode.BOAT, dry, water, PREDICTED_WATER_COMPONENT)));
        }
        out.accept(new MacroOption(MacroActionKind.SURFACE_ENTER, node, MacroAgentState.swim(), MacroCostVector.fixedTime(SWIM_ENTRY_COST + approach), List.of(dry, water),
          MacroSurfaceTransition.enter(TransportMode.SWIM, dry, water, PREDICTED_WATER_COMPONENT)));
      }
    }
  }

  private static void emitPredictedTransit(MacroExpansionContext context, MacroLabel label, MacroOptionSink out) {
    if (!context.predictedWaterAllowed()) {
      return;
    }
    int x = MacroNodeKey.cellX(label.node());
    int z = MacroNodeKey.cellZ(label.node());
    if (!predictedWaterCell(context, x, z)) {
      return;
    }
    TransportMode mode = label.state().mode();
    BetterBlockPos from = context.atlas().surfaceWaterCenter(x, z);
    for (int dx : D) {
      for (int dz : D) {
        if (dx == 0 && dz == 0) {
          continue;
        }
        int nx = x + dx;
        int nz = z + dz;
        if (!predictedWaterCell(context, nx, nz)) {
          continue;
        }
        BetterBlockPos to = context.atlas().surfaceWaterCenter(nx, nz);
        double distance = Math.hypot(to.x - from.x, to.z - from.z);
        MacroCostVector cost = mode == TransportMode.BOAT ? MacroCostVector.boatTransit(distance, context.calculation().waterTransport.boatCostPerBlock())
          : MacroCostVector.surfaceSwim(distance, context.calculation().costs.waterMoveCost());
        out.accept(new MacroOption(MacroActionKind.SURFACE_TRANSIT, predictedWaterNode(context, nx, nz), label.state(), cost, List.of(from, to),
          MacroSurfaceTransition.transit(mode, from, to, PREDICTED_WATER_COMPONENT, List.of(from, to), distance)));
      }
    }
  }

  private static void emitPredictedExit(MacroExpansionContext context, MacroLabel label, MacroOptionSink out) {
    if (!context.predictedWaterAllowed()) {
      return;
    }
    int x = MacroNodeKey.cellX(label.node());
    int z = MacroNodeKey.cellZ(label.node());
    if (!predictedWaterCell(context, x, z)) {
      return;
    }
    BetterBlockPos water = context.atlas().surfaceWaterCenter(x, z);
    if (x == context.targetCellX() && z == context.targetCellZ()) {
      emitPredictedExitTo(context, label, out, water, water, x, z);
    }
    for (int dx : D) {
      for (int dz : D) {
        int sx = x + dx;
        int sz = z + dz;
        if (dx == 0 && dz == 0 || !context.inBounds(sx, sz) || predictedWaterCell(context, sx, sz)) {
          continue;
        }
        emitPredictedExitTo(context, label, out, water, context.atlas().center(sx, sz), sx, sz);
      }
    }
  }

  private static void emitPredictedExitTo(MacroExpansionContext context, MacroLabel label, MacroOptionSink out, BetterBlockPos water, BetterBlockPos dry, int dryCellX, int dryCellZ) {
    double egress = dryApproachCost(context, dryCellX, dryCellZ, dry, water);
    MacroCostVector cost = label.state().boatMounted() ? MacroCostVector.fixedTime(context.calculation().waterTransport.boatPickupCost() + egress) : MacroCostVector.fixedTime(SWIM_EXIT_COST + egress);
    MacroSurfaceTransition transition = MacroSurfaceTransition.exit(label.state().mode(), water, dry, PREDICTED_WATER_COMPONENT);
    out.accept(new MacroOption(MacroActionKind.SURFACE_EXIT, predictedSurfaceNode(context, dryCellX, dryCellZ), label.state().afterSurfaceExit(), cost, List.of(water, dry), transition));
  }

  private static boolean predictedWaterCell(MacroExpansionContext context, int cellX, int cellZ) {
    return context.inBounds(cellX, cellZ) && context.atlas().surfaceWaterPrior(cellX, cellZ);
  }

  private static BetterBlockPos predictedSurfacePos(MacroExpansionContext context, int cellX, int cellZ) {
    int sx = Math.floorDiv(context.physicalStart().x, context.atlas().cellBlocks());
    int sz = Math.floorDiv(context.physicalStart().z, context.atlas().cellBlocks());
    return cellX == sx && cellZ == sz ? context.physicalStart() : context.atlas().center(cellX, cellZ);
  }

  private static double dryApproachCost(MacroExpansionContext context, int dryCellX, int dryCellZ, BetterBlockPos dry, BetterBlockPos water) {
    return flatDistance(dry, water) * context.atlas().surfaceCost(dryCellX, dryCellZ).medianTicksPerBlock();
  }

  private static long predictedSurfaceNode(MacroExpansionContext context, int cellX, int cellZ) {
    return MacroNodeKey.cell(context.calculation().world.dimension(), MacroStratum.SURFACE, context.atlas().scale(), cellX, cellZ);
  }

  private static long predictedWaterNode(MacroExpansionContext context, int cellX, int cellZ) {
    return MacroNodeKey.cell(context.calculation().world.dimension(), MacroStratum.WATER_SURFACE, context.atlas().scale(), cellX, cellZ);
  }

  private TransitField transitField(MacroExpansionContext context, SurfaceWaterSkeleton.ComponentKey component) {
    return transitFieldsByComponent.computeIfAbsent(component, key -> TransitField.build(context, water, key, transitTargetsByComponent.getOrDefault(key, new ArrayList<>())));
  }

  public static Optional<List<BetterBlockPos>> waterPath(CalculationContext context, SurfaceWaterAtlas atlas, BetterBlockPos start, BetterBlockPos dest, int component, TransportMode mode) {
    long src = start.asLong();
    long dst = dest.asLong();
    Long2DoubleOpenHashMap g = new Long2DoubleOpenHashMap();
    Long2LongOpenHashMap parent = new Long2LongOpenHashMap();
    LongOpenHashSet closed = new LongOpenHashSet();
    g.defaultReturnValue(Double.POSITIVE_INFINITY);
    parent.defaultReturnValue(Long.MIN_VALUE);
    PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
    g.put(src, 0D);
    open.add(new Node(src, flatDistance(start, dest)));
    int expansions = 0;
    while (!open.isEmpty() && expansions++ < MAX_EXPANSIONS) {
      Node node = open.poll();
      long packed = node.packed();
      if (!closed.add(packed)) {
        continue;
      }
      if (packed == dst) {
        return Optional.of(reconstruct(parent, dst));
      }
      BetterBlockPos here = pos(packed);
      for (int dx : D) {
        for (int dz : D) {
          if (dx == 0 && dz == 0) {
            continue;
          }
          long next = BlockPos.asLong(here.x + dx, here.y, here.z + dz);
          BetterBlockPos there = pos(next);
          if (closed.contains(next) || !surfaceCell(atlas, next, component, mode) || !TransitField.surfaceEdge(atlas, here, there, new SurfaceWaterSkeleton.ComponentKey(mode, component))) {
            continue;
          }
          double costPerBlock = mode == TransportMode.BOAT ? context.waterTransport.boatCostPerBlock() : context.costs.waterMoveCost();
          double tentative = g.get(packed) + flatDistance(here, there) * costPerBlock + bankPressure(atlas, next, component, mode);
          if (tentative < g.get(next)) {
            g.put(next, tentative);
            parent.put(next, packed);
            open.add(new Node(next, tentative + flatDistance(there, dest) * costPerBlock));
          }
        }
      }
    }
    return Optional.empty();
  }

  private static boolean surfaceCell(SurfaceWaterAtlas atlas, long packed, int component, TransportMode mode) {
    return SurfaceWaterSkeleton.surfaceCell(atlas, packed, component, mode);
  }

  private static List<BetterBlockPos> reconstruct(Long2LongOpenHashMap parent, long dest) {
    ArrayList<BetterBlockPos> reversed = new ArrayList<>();
    for (long p = dest; p != Long.MIN_VALUE; p = parent.get(p)) {
      reversed.add(pos(p));
    }
    ArrayList<BetterBlockPos> result = new ArrayList<>(reversed.size());
    for (int i = reversed.size() - 1; i >= 0; i--) {
      result.add(reversed.get(i));
    }
    return result;
  }

  private static double distance(List<BetterBlockPos> path) {
    double result = 0D;
    for (int i = 1; i < path.size(); i++) {
      result += flatDistance(path.get(i - 1), path.get(i));
    }
    return result;
  }

  private static BetterBlockPos pos(long packed) {
    return new BetterBlockPos(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
  }

  private static double flatDistance(BetterBlockPos a, BetterBlockPos b) {
    return Math.hypot(a.x - b.x, a.z - b.z);
  }

  private static double bankPressure(SurfaceWaterAtlas atlas, long packed, int componentId, TransportMode mode) {
    boolean boat = mode == TransportMode.BOAT;
    if (!boat && mode != TransportMode.SWIM) {
      return 0D;
    }
    int x0 = BlockPos.getX(packed);
    int y = BlockPos.getY(packed);
    int z0 = BlockPos.getZ(packed);
    int missing = 0;
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        long nearby = BlockPos.asLong(x0 + dx, y, z0 + dz);
        if (boat ? !(atlas.boatCell(nearby) && atlas.boatComponent(nearby) == componentId) : !(atlas.swimCell(nearby) && atlas.swimComponent(nearby) == componentId)) {
          missing++;
        }
      }
    }
    return missing * (boat ? BOAT_BANK_PRESSURE_COST : SWIM_BANK_PRESSURE_COST);
  }

  private record TransitPath(List<BetterBlockPos> positions, SurfaceWaterSkeleton.Anchor target, double distance) {
    private TransitPath {
      positions = List.copyOf(positions);
    }
  }

  private record Node(long packed, long target, double f) {
    private Node(long packed, double f) {
      this(packed, Long.MIN_VALUE, f);
    }
  }

  private static final class TransitField {
    private final Long2DoubleOpenHashMap bestCostByCell;
    private final Long2DoubleOpenHashMap secondCostByCell;
    private final Long2LongOpenHashMap bestNextByCell;
    private final Long2LongOpenHashMap secondNextByCell;
    private final Long2LongOpenHashMap bestTargetByCell;
    private final Long2LongOpenHashMap secondTargetByCell;
    private final Long2ObjectOpenHashMap<SurfaceWaterSkeleton.Anchor> targetByNode;

    private TransitField(Long2DoubleOpenHashMap bestCostByCell, Long2DoubleOpenHashMap secondCostByCell, Long2LongOpenHashMap bestNextByCell, Long2LongOpenHashMap secondNextByCell,
      Long2LongOpenHashMap bestTargetByCell, Long2LongOpenHashMap secondTargetByCell, Long2ObjectOpenHashMap<SurfaceWaterSkeleton.Anchor> targetByNode) {
      this.bestCostByCell = bestCostByCell;
      this.secondCostByCell = secondCostByCell;
      this.bestNextByCell = bestNextByCell;
      this.secondNextByCell = secondNextByCell;
      this.bestTargetByCell = bestTargetByCell;
      this.secondTargetByCell = secondTargetByCell;
      this.targetByNode = targetByNode;
    }

    static TransitField build(MacroExpansionContext context, SurfaceWaterAtlas atlas, SurfaceWaterSkeleton.ComponentKey component, ArrayList<SurfaceWaterSkeleton.Anchor> targets) {
      Long2DoubleOpenHashMap bestCost = new Long2DoubleOpenHashMap();
      Long2DoubleOpenHashMap secondCost = new Long2DoubleOpenHashMap();
      Long2LongOpenHashMap bestNext = new Long2LongOpenHashMap();
      Long2LongOpenHashMap secondNext = new Long2LongOpenHashMap();
      Long2LongOpenHashMap bestTarget = new Long2LongOpenHashMap();
      Long2LongOpenHashMap secondTarget = new Long2LongOpenHashMap();
      Long2ObjectOpenHashMap<SurfaceWaterSkeleton.Anchor> targetByNode = new Long2ObjectOpenHashMap<>();
      bestCost.defaultReturnValue(Double.POSITIVE_INFINITY);
      secondCost.defaultReturnValue(Double.POSITIVE_INFINITY);
      bestNext.defaultReturnValue(Long.MIN_VALUE);
      secondNext.defaultReturnValue(Long.MIN_VALUE);
      bestTarget.defaultReturnValue(Long.MIN_VALUE);
      secondTarget.defaultReturnValue(Long.MIN_VALUE);
      PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
      for (SurfaceWaterSkeleton.Anchor target : targets) {
        long water = target.water().asLong();
        if (!surfaceCell(atlas, water, component.componentId(), component.mode())) {
          continue;
        }
        targetByNode.put(target.node(), target);
        acceptRoute(bestCost, secondCost, bestNext, secondNext, bestTarget, secondTarget, open, water, target.node(), targetPotential(context, target), Long.MIN_VALUE);
      }
      Long2ObjectOpenHashMap<LongOpenHashSet> closed = new Long2ObjectOpenHashMap<>();
      CalculationContext calculation = context.calculation();
      double costPerBlock = component.mode() == TransportMode.BOAT ? calculation.waterTransport.boatCostPerBlock() : calculation.costs.waterMoveCost();
      while (!open.isEmpty()) {
        Node route = open.poll();
        long packed = route.packed();
        long target = route.target();
        double hereCost = route.f();
        if (routeCost(bestCost, secondCost, bestTarget, secondTarget, packed, target) != hereCost || !closeRoute(closed, packed, target)) {
          continue;
        }
        BetterBlockPos here = pos(packed);
        for (int dx : D) {
          for (int dz : D) {
            if (dx == 0 && dz == 0) {
              continue;
            }
            BetterBlockPos there = new BetterBlockPos(here.x + dx, here.y, here.z + dz);
            long neighbor = there.asLong();
            if (routeClosed(closed, neighbor, target) || !surfaceCell(atlas, neighbor, component.componentId(), component.mode()) || !surfaceEdge(atlas, here, there, component)) {
              continue;
            }
            double tentative = hereCost + Math.hypot(dx, dz) * costPerBlock + bankPressure(atlas, neighbor, component.componentId(), component.mode());
            acceptRoute(bestCost, secondCost, bestNext, secondNext, bestTarget, secondTarget, open, neighbor, target, tentative, packed);
          }
        }
      }
      return new TransitField(bestCost, secondCost, bestNext, secondNext, bestTarget, secondTarget, targetByNode);
    }

    private static double targetPotential(MacroExpansionContext context, SurfaceWaterSkeleton.Anchor target) {
      return context.goal().heuristic(target.dry().x, target.dry().y, target.dry().z) * Baritone.settings().costHeuristic.value;
    }

    private static void acceptRoute(Long2DoubleOpenHashMap bestCost, Long2DoubleOpenHashMap secondCost, Long2LongOpenHashMap bestNext, Long2LongOpenHashMap secondNext, Long2LongOpenHashMap bestTarget,
      Long2LongOpenHashMap secondTarget, PriorityQueue<Node> open, long cell, long target, double cost, long next) {
      long best = bestTarget.get(cell);
      if (best == target) {
        if (cost < bestCost.get(cell)) {
          bestCost.put(cell, cost);
          bestNext.put(cell, next);
          open.add(new Node(cell, target, cost));
        }
        return;
      }
      long second = secondTarget.get(cell);
      if (second == target) {
        if (cost < secondCost.get(cell)) {
          secondCost.put(cell, cost);
          secondNext.put(cell, next);
          open.add(new Node(cell, target, cost));
        }
        return;
      }
      double bestValue = bestCost.get(cell);
      if (cost < bestValue) {
        if (best != Long.MIN_VALUE) {
          secondTarget.put(cell, best);
          secondCost.put(cell, bestValue);
          secondNext.put(cell, bestNext.get(cell));
        }
        bestTarget.put(cell, target);
        bestCost.put(cell, cost);
        bestNext.put(cell, next);
        open.add(new Node(cell, target, cost));
        return;
      }
      if (cost < secondCost.get(cell)) {
        secondTarget.put(cell, target);
        secondCost.put(cell, cost);
        secondNext.put(cell, next);
        open.add(new Node(cell, target, cost));
      }
    }

    private static double routeCost(Long2DoubleOpenHashMap bestCost, Long2DoubleOpenHashMap secondCost, Long2LongOpenHashMap bestTarget, Long2LongOpenHashMap secondTarget, long cell, long target) {
      if (bestTarget.get(cell) == target) {
        return bestCost.get(cell);
      }
      return secondTarget.get(cell) == target ? secondCost.get(cell) : Double.POSITIVE_INFINITY;
    }

    private static boolean closeRoute(Long2ObjectOpenHashMap<LongOpenHashSet> closedByTarget, long cell, long target) {
      LongOpenHashSet cells = closedByTarget.get(target);
      if (cells == null) {
        cells = new LongOpenHashSet();
        closedByTarget.put(target, cells);
      }
      return cells.add(cell);
    }

    private static boolean routeClosed(Long2ObjectOpenHashMap<LongOpenHashSet> closedByTarget, long cell, long target) {
      LongOpenHashSet cells = closedByTarget.get(target);
      return cells != null && cells.contains(cell);
    }

    private static boolean surfaceEdge(SurfaceWaterAtlas atlas, BetterBlockPos here, BetterBlockPos there, SurfaceWaterSkeleton.ComponentKey component) {
      if (here.y != there.y) {
        return false;
      }
      if (component.mode() == TransportMode.SWIM) {
        return WaterLineKernel.traceCells(here, there, WaterLineProfile.SWIM_HALF_WIDTH,
          (x, z, centerline) -> surfaceCell(atlas, BlockPos.asLong(x, here.y, z), component.componentId(), TransportMode.SWIM));
      }
      return WaterLineKernel.traceCells(here, there, WaterLineProfile.BOAT_HALF_WIDTH, (x, z, centerline) -> {
        long packed = BlockPos.asLong(x, here.y, z);
        return centerline ? surfaceCell(atlas, packed, component.componentId(), TransportMode.BOAT) : atlas.boatHullCell(packed);
      });
    }

    TransitPath pathFrom(long start, long forbiddenTarget) {
      long target = bestTargetByCell.get(start) == forbiddenTarget ? secondTargetByCell.get(start) : bestTargetByCell.get(start);
      double cost = routeCost(bestCostByCell, secondCostByCell, bestTargetByCell, secondTargetByCell, start, target);
      if (target == Long.MIN_VALUE || !Double.isFinite(cost)) {
        return null;
      }
      ArrayList<BetterBlockPos> path = new ArrayList<>();
      LongOpenHashSet seen = new LongOpenHashSet();
      long cursor = start;
      while (cursor != Long.MIN_VALUE) {
        if (!seen.add(cursor)) {
          return null;
        }
        path.add(pos(cursor));
        long next = routeNext(cursor, target);
        if (next == Long.MIN_VALUE) {
          SurfaceWaterSkeleton.Anchor anchor = targetByNode.get(target);
          return anchor == null ? null : new TransitPath(path, anchor, distance(path));
        }
        cursor = next;
      }
      return null;
    }

    private long routeNext(long cell, long target) {
      if (bestTargetByCell.get(cell) == target) {
        return bestNextByCell.get(cell);
      }
      return secondTargetByCell.get(cell) == target ? secondNextByCell.get(cell) : Long.MIN_VALUE;
    }
  }
}
