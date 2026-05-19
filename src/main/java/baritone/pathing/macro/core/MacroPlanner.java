package baritone.pathing.macro.core;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.pathing.macro.portal.PortalGeometry;
import baritone.pathing.macro.portal.PortalTransportDomain;
import baritone.pathing.macro.surface.SurfaceTraversalDomain;
import baritone.pathing.macro.water.SurfaceWaterSkeleton;
import baritone.pathing.macro.water.WaterTransportDomain;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.transport.TransportMode;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

public final class MacroPlanner {
  private static final int MAX_EXPANSIONS = 250_000;
  private static final int SURFACE_WATER_RESCUE_RADIUS = 4;

  private MacroPlanner() {
  }

  public static Optional<MacroPlan> plan(CalculationContext calculation, BetterBlockPos start, Goal goal) {
    return plan(calculation, start, goal, MacroTraversalProfile.physical(calculation));
  }

  public static Optional<MacroPlan> plan(CalculationContext calculation, BetterBlockPos start, Goal goal, MacroTraversalProfile profile) {
    return plan(calculation, start, goal, profile, MacroPlanningMode.PREDICTIVE);
  }

  public static Optional<MacroPlan> factualSurfacePrefix(CalculationContext calculation, BetterBlockPos start, Goal goal) {
    return factualSurfacePrefix(calculation, start, goal, MacroTraversalProfile.physical(calculation));
  }

  public static Optional<MacroPlan> factualSurfacePrefix(CalculationContext calculation, BetterBlockPos start, Goal goal, MacroTraversalProfile profile) {
    return plan(calculation, start, goal, profile, MacroPlanningMode.FACTUAL_SURFACE_PREFIX);
  }

  private static Optional<MacroPlan> plan(CalculationContext calculation, BetterBlockPos start, Goal goal, MacroTraversalProfile profile, MacroPlanningMode planningMode) {
    if (!Baritone.settings().macroPlanning.value || !portalSupportedDimension(calculation)) {
      return Optional.empty();
    }
    MacroCapabilities capabilities = MacroCapabilities.physical(calculation);
    if (calculation.world.dimension() == Level.OVERWORLD && !capabilities.canBuildPortal()) {
      int surfaceY = calculation.world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, start.x, start.z);
      if (start.y + 4 < surfaceY && !nearSurfaceWater(calculation, start)) {
        return Optional.empty();
      }
    }
    Optional<BlockPos> goalPos = MacroGoals.pos(goal);
    if (goalPos.isEmpty()) {
      return Optional.empty();
    }
    MacroAtlas atlas = MacroAtlas.build(calculation, start, profile.permitsSurfaceWaterTransitions() && calculation.world.dimension() == Level.OVERWORLD);
    int cellBlocks = atlas.cellBlocks();
    int waypointBlocks = Baritone.settings().macroBiomeWaypointBlocks.value;
    double fullDistance = Math.hypot(goalPos.get().getX() - start.x, goalPos.get().getZ() - start.z);
    double minimumDistance = planningMode.minimumDistance(cellBlocks, waypointBlocks);
    if (fullDistance < minimumDistance) {
      return Optional.empty();
    }
    MacroPolicy policy = MacroPolicy.configured();
    int radius = Math.max(4, (int) Math.ceil(Baritone.settings().macroBiomeHorizonBlocks.value / (double) cellBlocks));
    int sx = Math.floorDiv(start.x, cellBlocks);
    int sz = Math.floorDiv(start.z, cellBlocks);
    int gx = Math.floorDiv(goalPos.get().getX(), cellBlocks);
    int gz = Math.floorDiv(goalPos.get().getZ(), cellBlocks);
    if (Math.max(Math.abs(gx - sx), Math.abs(gz - sz)) > radius) {
      double scale = Baritone.settings().macroBiomeHorizonBlocks.value / fullDistance;
      gx = Math.floorDiv(start.x + (int) Math.round((goalPos.get().getX() - start.x) * scale), cellBlocks);
      gz = Math.floorDiv(start.z + (int) Math.round((goalPos.get().getZ() - start.z) * scale), cellBlocks);
    }
    long src = MacroNodeKey.cell(calculation.world.dimension(), MacroStratum.SURFACE, atlas.scale(), sx, sz);
    long dst = MacroNodeKey.cell(calculation.world.dimension(), MacroStratum.SURFACE, atlas.scale(), gx, gz);
    MacroExpansionContext expansion = new MacroExpansionContext(calculation, atlas, goal, start, policy, capabilities, profile, Math.min(sx, gx) - radius, Math.max(sx, gx) + radius,
      Math.min(sz, gz) - radius, Math.max(sz, gz) + radius, gx, gz, planningMode);
    ArrayList<MacroDomain> domains = new ArrayList<>();
    domains.add(new SurfaceTraversalDomain());
    PortalTransportDomain portalDomain = null;
    if (profile.permitsPortalTransitions() && Baritone.settings().macroNether.value) {
      PortalTransportDomain portal = new PortalTransportDomain(expansion);
      if (!portal.empty()) {
        portalDomain = portal;
        domains.add(portal);
      }
    }
    WaterTransportDomain waterDomain = null;
    if (profile.permitsSurfaceWaterTransitions() && calculation.world.dimension() == Level.OVERWORLD && atlas.water().isPresent()) {
      WaterTransportDomain water = new WaterTransportDomain(expansion);
      if (!water.empty()) {
        waterDomain = water;
        domains.add(water);
        Helper.HELPER
          .logDebug("Macro water skeleton: " + water.anchorCount() + " anchors across " + water.entryCellCount() + " surface cells and " + water.targetComponentCount() + " target components");
      }
    }
    MacroAgentState startState = profile.physicalState(calculation);
    if (profile.permitsSurfaceWaterTransitions() && waterDomain != null) {
      Optional<SurfaceWaterSkeleton.Anchor> waterStart = waterDomain.currentWaterAnchor(startState.boatMounted() ? TransportMode.BOAT : TransportMode.SWIM);
      if (waterStart.isPresent()) {
        src = waterStart.get().node();
        startState = waterStart.get().mode() == TransportMode.BOAT ? MacroAgentState.boat() : MacroAgentState.swim();
      }
    }
    boolean hasWaterDomain = waterDomain != null;
    boolean hasPortalDomain = portalDomain != null;
    if (!hasWaterDomain && !hasPortalDomain) {
      return Optional.empty();
    }
    Optional<MacroPlan> plan = search(expansion, domains, src, dst, startState, goal, goalPos.get(), waypointBlocks);
    Helper.HELPER.logDebug("Macro multimodal result: " + plan
      .map(
        p -> p.sequence() + " surface=" + p.surfaceTransitionActions() + " portals=" + p.portalActions() + " distance=" + String.format(java.util.Locale.ROOT, "%.1f", p.surfaceTransitionDistance()))
      .orElse("empty"));
    if (plan.map(p -> p.surfaceTransitionActions() + p.portalActions()).orElse(0) == 0) {
      return Optional.empty();
    }
    return plan;
  }

  private static Optional<MacroPlan> search(MacroExpansionContext context, List<MacroDomain> domains, long src, long dst, MacroAgentState startState, Goal finalGoal, BlockPos goalPos,
    int waypointBlocks) {
    ArrayList<SearchLabel> labels = new ArrayList<>();
    HashMap<LabelKey, Integer> bestByKey = new HashMap<>();
    PriorityQueue<QueueEntry> open = new PriorityQueue<>(Comparator.comparingDouble(QueueEntry::f));
    labels.add(new SearchLabel(src, startState, 0D, MacroCostVector.ZERO, -1, null, 0D, 0D, false));
    bestByKey.put(new LabelKey(src, startState.bits(), false), 0);
    open.add(new QueueEntry(0, searchHeuristic(context, src)));
    int bestFrontier = -1;
    int expansions = 0;
    while (!open.isEmpty() && expansions++ < MAX_EXPANSIONS) {
      QueueEntry entry = open.poll();
      SearchLabel label = labels.get(entry.label());
      Integer best = bestByKey.get(new LabelKey(label.node, label.state.bits(), label.usefulTransportTransit));
      if (best == null || best != entry.label()) {
        continue;
      }
      if (label.node == dst && label.usefulTransportTransit) {
        return Optional.of(materialize(context, labels, entry.label(), finalGoal, goalPos, waypointBlocks));
      }
      if (label.usefulTransportTransit && (bestFrontier < 0 || frontierScore(context, label) < frontierScore(context, labels.get(bestFrontier)))) {
        bestFrontier = entry.label();
      }
      MacroLabel publicLabel = new MacroLabel(label.node, label.state, label.g, label.vector, entry.label());
      for (MacroDomain domain : domains) {
        domain.expand(context, publicLabel, option -> accept(context, labels, bestByKey, open, entry.label(), option));
      }
    }
    return bestFrontier >= 0 ? Optional.of(materialize(context, labels, bestFrontier, finalGoal, goalPos, waypointBlocks)) : Optional.empty();
  }

  private static void accept(MacroExpansionContext context, ArrayList<SearchLabel> labels, HashMap<LabelKey, Integer> bestByKey, PriorityQueue<QueueEntry> open, int parentId, MacroOption option) {
    SearchLabel parent = labels.get(parentId);
    double edgeScore = context.policy().score(option.cost(), parent.state, option.nextState());
    double g = parent.g + edgeScore;
    double surfaceTransitDistance =
      parent.surfaceTransitDistance + (option.surfaceTransition() != null && option.surfaceTransition().stage() == MacroSurfaceTransitionStage.TRANSIT ? option.surfaceTransition().distance() : 0D);
    boolean usefulTransportTransit = parent.usefulTransportTransit || option.kind().portal() || surfaceTransitDistance >= MacroSurfaceSessions.MIN_USEFUL_DISTANCE;
    LabelKey key = new LabelKey(option.toNode(), option.nextState().bits(), usefulTransportTransit);
    Integer previousId = bestByKey.get(key);
    if (previousId != null && labels.get(previousId).g <= g) {
      return;
    }
    MacroCostVector vector = parent.vector.plus(option.cost());
    int id = labels.size();
    double f = g + searchHeuristic(context, option.toNode());
    labels.add(new SearchLabel(option.toNode(), option.nextState(), g, vector, parentId, option, edgeScore, surfaceTransitDistance, usefulTransportTransit));
    bestByKey.put(key, id);
    open.add(new QueueEntry(id, f));
  }

  private static MacroPlan materialize(MacroExpansionContext context, ArrayList<SearchLabel> labels, int terminal, Goal finalGoal, BlockPos goalPos, int waypointBlocks) {
    IntArrayList reversed = new IntArrayList();
    for (int id = terminal; id >= 0; id = labels.get(id).parent) {
      reversed.add(id);
    }
    ArrayList<MacroActionInstance> actions = new ArrayList<>();
    ArrayList<BetterBlockPos> render = new ArrayList<>();
    ArrayList<MacroPlanVertex> vertices = new ArrayList<>();
    StringBuilder sequence = new StringBuilder();
    int factual = 0;
    int unknown = 0;
    int live = 0;
    int cached = 0;
    int predicted = 0;
    int prior = 0;
    for (int i = reversed.size() - 1; i >= 0; i--) {
      SearchLabel label = labels.get(reversed.getInt(i));
      if (!MacroNodeKey.anchorKey(label.node)) {
        int x = MacroNodeKey.cellX(label.node);
        int z = MacroNodeKey.cellZ(label.node);
        MacroCellEvidence evidence = context.atlas().evidence(x, z);
        switch (evidence) {
          case LIVE -> live++;
          case CACHED -> cached++;
          case PREDICTED -> predicted++;
          case PRIOR -> prior++;
        }
        if (evidence.concrete()) {
          factual++;
        } else {
          unknown++;
        }
        addVertex(vertices, new MacroPlanVertex(nodeCenter(context, label.node), evidence));
      } else {
        anchorVertex(label).ifPresent(pos -> addVertex(vertices, new MacroPlanVertex(pos, MacroCellEvidence.LIVE)));
      }
      if (label.option == null) {
        if (!MacroNodeKey.anchorKey(label.node)) {
          addRender(render, nodeCenter(context, label.node));
        }
        continue;
      }
      SearchLabel parent = labels.get(label.parent);
      MacroActionInstance action = new MacroActionInstance(label.option.kind(), parent.node, label.node, parent.state, label.state, label.option.cost(), label.edgeScore,
        label.option.renderPositions(), label.option.surfaceTransition(), label.option.taskIntent());
      actions.add(action);
      for (BetterBlockPos pos : action.renderPositions()) {
        addRender(render, pos);
      }
      char token =
        action.kind().portal() ? 'N' : action.surfaceTransition() == null ? action.before().horseMounted() || action.after().horseMounted() ? 'H' : 'S' : action.surfaceTransition().boat() ? 'B' : 'W';
      if (sequence.isEmpty() || sequence.charAt(sequence.length() - 1) != token) {
        if (!sequence.isEmpty()) {
          sequence.append('>');
        }
        sequence.append(token);
      }
    }
    SearchLabel last = labels.get(terminal);
    Goal localGoal = localGoal(actions, render, context.physicalStart(), goalPos, waypointBlocks, finalGoal, context.atlas().cellBlocks());
    BetterBlockPos dest = new BetterBlockPos(goalPos.getX(), goalPos.getY(), goalPos.getZ());
    String firstUncertified = actions.stream().filter(action -> action.surfaceTransition() == null).findFirst().map(action -> action.kind().name()).orElse(null);
    return new MacroPlan(context.physicalStart(), dest, localGoal, render, actions, last.vector, frontierScore(context, last), context.atlas().cellBlocks(), factual, unknown, live, cached, predicted,
      prior, vertices, sequence.toString(), firstUncertified, MacroValueTelemetry.EMPTY);
  }

  private static Goal localGoal(List<MacroActionInstance> actions, List<BetterBlockPos> render, BetterBlockPos start, BlockPos goalPos, int waypointBlocks, Goal finalGoal, int cellBlocks) {
    Optional<Goal> portalGoal = portalGoal(actions, start, waypointBlocks);
    if (portalGoal.isPresent()) {
      return portalGoal.get();
    }
    int usefulEnter = MacroSurfaceSessions.firstUsefulEnter(actions, start, new BetterBlockPos(goalPos.getX(), goalPos.getY(), goalPos.getZ()));
    if (usefulEnter >= 0) {
      MacroSurfaceTransition enter = actions.get(usefulEnter).surfaceTransition();
      if (!start.equals(enter.dryStart())) {
        return new GoalXZ(enter.dryStart().x, enter.dryStart().z);
      }
      Optional<BetterBlockPos> advanced = surfaceRunWaypoint(actions, usefulEnter, start, Math.min(waypointBlocks, Math.max(cellBlocks, 48)));
      if (advanced.isPresent()) {
        return new GoalXZ(advanced.get().x, advanced.get().z);
      }
    }
    for (MacroActionInstance action : actions) {
      if (flatDistance(start, action.renderPositions().get(action.renderPositions().size() - 1)) >= waypointBlocks) {
        BetterBlockPos pos = action.renderPositions().get(action.renderPositions().size() - 1);
        return new GoalXZ(pos.x, pos.z);
      }
    }
    if (Math.hypot(goalPos.getX() - start.x, goalPos.getZ() - start.z) <= waypointBlocks * 1.25D) {
      return finalGoal;
    }
    BetterBlockPos previous = start;
    double walked = 0D;
    for (BetterBlockPos pos : render) {
      walked += flatDistance(previous, pos);
      if (walked >= waypointBlocks) {
        return new GoalXZ(pos.x, pos.z);
      }
      previous = pos;
    }
    return render.isEmpty() ? finalGoal : new GoalXZ(render.get(render.size() - 1).x, render.get(render.size() - 1).z);
  }

  private static Optional<Goal> portalGoal(List<MacroActionInstance> actions, BetterBlockPos start, int waypointBlocks) {
    double walked = 0D;
    BetterBlockPos previous = start;
    for (MacroActionInstance action : actions) {
      if (action.renderPositions().isEmpty()) {
        continue;
      }
      BetterBlockPos first = action.renderPositions().get(0);
      walked += flatDistance(previous, first);
      if (action.kind().portal()) {
        if (action.kind() == MacroActionKind.PORTAL_ENTER) {
          return Optional.of(new GoalBlock(first));
        }
        return Optional.of(new GoalXZ(first.x, first.z));
      }
      BetterBlockPos last = action.renderPositions().get(action.renderPositions().size() - 1);
      walked += flatDistance(first, last);
      if (walked >= waypointBlocks) {
        return Optional.empty();
      }
      previous = last;
    }
    return Optional.empty();
  }

  private static Optional<BetterBlockPos> surfaceRunWaypoint(List<MacroActionInstance> actions, int enterIndex, BetterBlockPos start, int targetDistance) {
    MacroSurfaceTransition enter = actions.get(enterIndex).surfaceTransition();
    if (enter == null || enter.stage() != MacroSurfaceTransitionStage.ENTER) {
      return Optional.empty();
    }
    BetterBlockPos best = enter.waterEnd();
    if (flatDistance(start, best) >= targetDistance * 0.5D) {
      return Optional.of(best);
    }
    for (int i = enterIndex + 1; i < actions.size(); i++) {
      MacroSurfaceTransition transition = actions.get(i).surfaceTransition();
      if (transition == null || transition.mode() != enter.mode() || transition.componentId() != enter.componentId()) {
        break;
      }
      best = transition.stage() == MacroSurfaceTransitionStage.EXIT && transition.dryEnd() != null ? transition.dryEnd() : transition.waterEnd();
      if (flatDistance(start, best) >= targetDistance || transition.stage() == MacroSurfaceTransitionStage.EXIT) {
        return Optional.of(best);
      }
    }
    return Optional.ofNullable(best);
  }

  private static void addRender(ArrayList<BetterBlockPos> render, BetterBlockPos pos) {
    if (render.isEmpty() || !render.get(render.size() - 1).equals(pos)) {
      render.add(pos);
    }
  }

  private static void addVertex(ArrayList<MacroPlanVertex> vertices, MacroPlanVertex vertex) {
    if (vertices.isEmpty() || !vertices.get(vertices.size() - 1).pos().equals(vertex.pos())) {
      vertices.add(vertex);
    }
  }

  private static Optional<BetterBlockPos> anchorVertex(SearchLabel label) {
    if (label.option == null || label.option.renderPositions().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(label.option.renderPositions().get(label.option.renderPositions().size() - 1));
  }

  private static BetterBlockPos nodeCenter(MacroExpansionContext context, long node) {
    int cellX = MacroNodeKey.cellX(node);
    int cellZ = MacroNodeKey.cellZ(node);
    return MacroNodeKey.stratum(node) == MacroStratum.WATER_SURFACE ? context.atlas().surfaceWaterCenter(cellX, cellZ) : context.atlas().center(cellX, cellZ);
  }

  private static double searchHeuristic(MacroExpansionContext context, long node) {
    if (MacroNodeKey.anchorKey(node)) {
      return 0D;
    }
    int dimensionId = MacroNodeKey.dimensionId(node);
    int cellX =
      dimensionId == MacroNodeKey.DIMENSION_NETHER ? PortalGeometry.targetCellX(MacroNodeKey.cellX(node), MacroNodeKey.DIMENSION_NETHER, MacroNodeKey.DIMENSION_OVERWORLD) : MacroNodeKey.cellX(node);
    int cellZ =
      dimensionId == MacroNodeKey.DIMENSION_NETHER ? PortalGeometry.targetCellZ(MacroNodeKey.cellZ(node), MacroNodeKey.DIMENSION_NETHER, MacroNodeKey.DIMENSION_OVERWORLD) : MacroNodeKey.cellZ(node);
    int dx = context.targetCellX() - cellX;
    int dz = context.targetCellZ() - cellZ;
    double distance = Math.hypot(dx, dz) * context.atlas().cellBlocks();
    double floor = context.profile().lowerBoundTicksPerBlock(context.calculation());
    if (context.profile().permitsSurfaceWaterTransitions() && context.capabilities().boatAvailable()) {
      floor = Math.min(floor, context.calculation().waterTransport.boatCostPerBlock());
    }
    if (dimensionId == MacroNodeKey.DIMENSION_NETHER) {
      floor = Math.min(floor, Baritone.settings().macroNetherTicksPerBlock.value / PortalGeometry.NETHER_SCALE);
    }
    return distance * floor;
  }

  private static double frontierScore(MacroExpansionContext context, SearchLabel label) {
    return label.g + searchHeuristic(context, label.node);
  }

  private static double flatDistance(BetterBlockPos a, BetterBlockPos b) {
    return Math.hypot(a.x - b.x, a.z - b.z);
  }

  private static boolean portalSupportedDimension(CalculationContext calculation) {
    return calculation.world.dimension() == Level.OVERWORLD || calculation.world.dimension() == Level.NETHER;
  }

  private static boolean nearSurfaceWater(CalculationContext context, BetterBlockPos start) {
    int minY = Math.max(context.world.getMinY() + 1, start.y - 3);
    int maxY = Math.min(context.world.getMaxY() - 2, start.y + 2);
    for (int dx = -SURFACE_WATER_RESCUE_RADIUS; dx <= SURFACE_WATER_RESCUE_RADIUS; dx++) {
      for (int dz = -SURFACE_WATER_RESCUE_RADIUS; dz <= SURFACE_WATER_RESCUE_RADIUS; dz++) {
        int x = start.x + dx;
        int z = start.z + dz;
        if (!context.worldBorder.entirelyContains(x, z) || !context.hasPathingData(x, z)) {
          continue;
        }
        for (int y = minY; y <= maxY; y++) {
          if (MovementHelper.isWater(context.get(x, y, z))) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private record LabelKey(long node, int state, boolean transportTransitSeen) {
  }

  private record QueueEntry(int label, double f) {
  }

  private static final class SearchLabel {
    private final long node;
    private final MacroAgentState state;
    private final double g;
    private final MacroCostVector vector;
    private final int parent;
    private final MacroOption option;
    private final double edgeScore;
    private final double surfaceTransitDistance;
    private final boolean usefulTransportTransit;

    private SearchLabel(long node, MacroAgentState state, double g, MacroCostVector vector, int parent, MacroOption option, double edgeScore, double surfaceTransitDistance,
      boolean usefulTransportTransit) {
      this.node = node;
      this.state = state;
      this.g = g;
      this.vector = vector;
      this.parent = parent;
      this.option = option;
      this.edgeScore = edgeScore;
      this.surfaceTransitDistance = surfaceTransitDistance;
      this.usefulTransportTransit = usefulTransportTransit;
    }
  }
}
