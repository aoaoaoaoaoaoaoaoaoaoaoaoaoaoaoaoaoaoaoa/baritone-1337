package baritone.pathing.macro.water;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.macro.core.MacroExpansionContext;
import baritone.pathing.macro.core.MacroNodeKey;
import baritone.pathing.macro.core.MacroStratum;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.water.WaterLineKernel;
import baritone.pathing.movement.water.WaterLineProfile;
import baritone.pathing.transport.TransportMode;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sparse, route-level extraction of a surface-water component.
 *
 * The atlas is the factual bitmap. The skeleton is the commitment surface exposed to macro search:
 * one shoreline portal per macro cell/component/mode plus synthetic current-water portals. Dense
 * water routing remains the certifier; this class prevents macro search from treating every bank
 * block as a semantically distinct launch.
 */
public final class SurfaceWaterSkeleton {
  private static final int[] D = {-1, 0, 1};
  private static final int[] WATER_Y_OFFSETS_FROM_DRY = {-1, 0};
  private static final int CURRENT_DRY_WATER_SCAN_RADIUS = 40;
  private static final int CURRENT_DRY_ANCHOR_LIMIT = 8;
  private static final int CURRENT_WATER_SCAN_RADIUS = 12;
  private static final int[] CURRENT_WATER_Y_OFFSETS = {-1, 0, 1, 2, 3, 4};
  private static final int ANCHOR_CLEARANCE_RADIUS = 2;
  private static final long WATER_ANCHOR_PREFIX = 1L << 56;

  private final Long2ObjectOpenHashMap<ArrayList<Anchor>> entriesBySurfaceCell = new Long2ObjectOpenHashMap<>();
  private final HashMap<ComponentKey, ArrayList<Anchor>> anchorsByComponent = new HashMap<>();
  private final Long2ObjectOpenHashMap<Anchor> anchorsByNode = new Long2ObjectOpenHashMap<>();
  private final EnumMap<TransportMode, Anchor> currentWaterAnchors = new EnumMap<>(TransportMode.class);
  private long nextAnchorId;

  private SurfaceWaterSkeleton() {
  }

  public static SurfaceWaterSkeleton build(MacroExpansionContext context, SurfaceWaterAtlas atlas) {
    SurfaceWaterSkeleton skeleton = new SurfaceWaterSkeleton();
    skeleton.index(context, atlas, atlas.boatComponents(), TransportMode.BOAT);
    skeleton.index(context, atlas, atlas.swimComponents(), TransportMode.SWIM);
    skeleton.indexCurrentDryLaunch(context, atlas, TransportMode.BOAT);
    skeleton.indexCurrentDryLaunch(context, atlas, TransportMode.SWIM);
    skeleton.indexCurrentWater(context, atlas, TransportMode.BOAT);
    skeleton.indexCurrentWater(context, atlas, TransportMode.SWIM);
    skeleton.sort();
    return skeleton;
  }

  public ArrayList<Anchor> entries(long surfaceCell) {
    return entriesBySurfaceCell.get(surfaceCell);
  }

  public ArrayList<Anchor> anchors(ComponentKey component) {
    return anchorsByComponent.get(component);
  }

  public Anchor anchor(long node) {
    return anchorsByNode.get(node);
  }

  public Optional<Anchor> currentWaterAnchor(TransportMode mode) {
    return Optional.ofNullable(currentWaterAnchors.get(mode));
  }

  public boolean empty() {
    return entriesBySurfaceCell.isEmpty() || anchorsByComponent.isEmpty();
  }

  public int entryCellCount() {
    return entriesBySurfaceCell.size();
  }

  public int anchorCount() {
    return anchorsByNode.size();
  }

  private void index(MacroExpansionContext context, SurfaceWaterAtlas atlas, List<WaterComponent> components, TransportMode mode) {
    HashMap<EntryKey, Candidate> best = new HashMap<>();
    for (WaterComponent component : components) {
      for (long dryPacked : component.launchCells()) {
        BetterBlockPos dry = pos(dryPacked);
        long water = adjacentSurfaceCell(atlas, dry, component.id(), mode);
        if (water == Long.MIN_VALUE) {
          continue;
        }
        long surfaceCell = MacroNodeKey.cellContaining(context.calculation().world.dimension(), MacroStratum.SURFACE, context.atlas().scale(), dry);
        Candidate candidate = new Candidate(mode, dry, pos(water), component.id(), surfaceCell, clearance(atlas, water, component.id(), mode), false, false);
        EntryKey key = new EntryKey(mode, component.id(), surfaceCell);
        Candidate previous = best.get(key);
        if (previous == null || candidate.score(context) > previous.score(context)) {
          best.put(key, candidate);
        }
      }
    }
    best.values().forEach(this::add);
  }

  private void indexCurrentWater(MacroExpansionContext context, SurfaceWaterAtlas atlas, TransportMode mode) {
    if (mode == TransportMode.BOAT && !context.capabilities().boatAvailable()) {
      return;
    }
    BetterBlockPos start = context.physicalStart();
    if (!currentWaterborne(context, start)) {
      return;
    }
    Candidate best = null;
    for (int dx = -CURRENT_WATER_SCAN_RADIUS; dx <= CURRENT_WATER_SCAN_RADIUS; dx++) {
      for (int dz = -CURRENT_WATER_SCAN_RADIUS; dz <= CURRENT_WATER_SCAN_RADIUS; dz++) {
        for (int dy : CURRENT_WATER_Y_OFFSETS) {
          long packed = BlockPos.asLong(start.x + dx, start.y + dy, start.z + dz);
          int component = mode == TransportMode.BOAT ? atlas.boatComponent(packed) : atlas.swimComponent(packed);
          if (component < 0 || !surfaceCell(atlas, packed, component, mode)) {
            continue;
          }
          long surfaceCell = MacroNodeKey.cellContaining(context.calculation().world.dimension(), MacroStratum.SURFACE, context.atlas().scale(), start);
          Candidate candidate = new Candidate(mode, start, pos(packed), component, surfaceCell, clearance(atlas, packed, component, mode), true, true);
          if (best == null || candidate.score(context) > best.score(context)) {
            best = candidate;
          }
        }
      }
    }
    if (best != null) {
      add(best);
    }
  }

  private void indexCurrentDryLaunch(MacroExpansionContext context, SurfaceWaterAtlas atlas, TransportMode mode) {
    if (mode == TransportMode.BOAT && !context.capabilities().boatAvailable()) {
      return;
    }
    BetterBlockPos start = context.physicalStart();
    if (!currentDryStable(context, start)) {
      return;
    }
    ArrayList<Candidate> candidates = new ArrayList<>();
    for (int dx = -CURRENT_DRY_WATER_SCAN_RADIUS; dx <= CURRENT_DRY_WATER_SCAN_RADIUS; dx++) {
      for (int dz = -CURRENT_DRY_WATER_SCAN_RADIUS; dz <= CURRENT_DRY_WATER_SCAN_RADIUS; dz++) {
        if (dx == 0 && dz == 0) {
          continue;
        }
        if (dx * dx + dz * dz > CURRENT_DRY_WATER_SCAN_RADIUS * CURRENT_DRY_WATER_SCAN_RADIUS) {
          continue;
        }
        for (int dy : WATER_Y_OFFSETS_FROM_DRY) {
          long water = BlockPos.asLong(start.x + dx, start.y + dy, start.z + dz);
          int component = mode == TransportMode.BOAT ? atlas.boatComponent(water) : atlas.swimComponent(water);
          if (component < 0 || !surfaceCell(atlas, water, component, mode) || !currentLaunchCorridor(context, atlas, start, water, mode)) {
            continue;
          }
          long surfaceCell = MacroNodeKey.cellContaining(context.calculation().world.dimension(), MacroStratum.SURFACE, context.atlas().scale(), start);
          Candidate candidate = new Candidate(mode, start, pos(water), component, surfaceCell, clearance(atlas, water, component, mode), true, false);
          addCurrentDryCandidate(context, candidates, candidate);
        }
      }
    }
    candidates.sort(Comparator.comparingDouble((Candidate candidate) -> candidate.score(context)).reversed());
    candidates.stream().limit(CURRENT_DRY_ANCHOR_LIMIT).forEach(this::add);
  }

  private static boolean currentWaterborne(MacroExpansionContext context, BetterBlockPos start) {
    if (!context.calculation().worldBorder.entirelyContains(start.x, start.z) || !context.calculation().isLoaded(start.x, start.z)) {
      return false;
    }
    for (int dy = -1; dy <= 1; dy++) {
      if (MovementHelper.isWater(context.calculation().get(start.x, start.y + dy, start.z))) {
        return true;
      }
    }
    return false;
  }

  private static boolean currentDryStable(MacroExpansionContext context, BetterBlockPos start) {
    if (!context.calculation().worldBorder.entirelyContains(start.x, start.z) || !context.calculation().isLoaded(start.x, start.z)) {
      return false;
    }
    BlockState feet = context.calculation().get(start.x, start.y, start.z);
    return !MovementHelper.isWater(feet) && MovementHelper.canWalkOn(context.calculation(), start.x, start.y - 1, start.z)
      && MovementHelper.canWalkThrough(context.calculation(), start.x, start.y, start.z, feet) && MovementHelper.canWalkThrough(context.calculation(), start.x, start.y + 1, start.z);
  }

  private static void addCurrentDryCandidate(MacroExpansionContext context, ArrayList<Candidate> candidates, Candidate candidate) {
    for (int i = 0; i < candidates.size(); i++) {
      Candidate previous = candidates.get(i);
      if (previous.water().equals(candidate.water())) {
        if (candidate.score(context) > previous.score(context)) {
          candidates.set(i, candidate);
        }
        return;
      }
    }
    candidates.add(candidate);
  }

  private void add(Candidate candidate) {
    Anchor anchor = new Anchor(anchorNode(), candidate.mode(), candidate.dry(), candidate.water(), candidate.componentId(), candidate.surfaceCell(), candidate.clearance(), candidate.current(),
      candidate.waterborne());
    anchorsByNode.put(anchor.node(), anchor);
    entriesBySurfaceCell.computeIfAbsent(anchor.surfaceCell(), ignored -> new ArrayList<>()).add(anchor);
    anchorsByComponent.computeIfAbsent(new ComponentKey(anchor.mode(), anchor.componentId()), ignored -> new ArrayList<>()).add(anchor);
    if (anchor.current() && anchor.waterborne()) {
      currentWaterAnchors.merge(anchor.mode(), anchor, (a, b) -> a.clearance() >= b.clearance() ? a : b);
    }
  }

  private long anchorNode() {
    return MacroNodeKey.anchor(WATER_ANCHOR_PREFIX | nextAnchorId++);
  }

  private void sort() {
    Comparator<Anchor> order = Comparator.comparing(Anchor::current).reversed().thenComparing(Comparator.comparingInt(Anchor::clearance).reversed());
    entriesBySurfaceCell.values().forEach(anchors -> anchors.sort(order));
    anchorsByComponent.values().forEach(anchors -> anchors.sort(order));
  }

  static long adjacentSurfaceCell(SurfaceWaterAtlas atlas, BetterBlockPos dry, int component, TransportMode mode) {
    long best = Long.MIN_VALUE;
    int bestScore = Integer.MIN_VALUE;
    for (int dx : D) {
      for (int dz : D) {
        if (dx == 0 && dz == 0) {
          continue;
        }
        for (int dy : WATER_Y_OFFSETS_FROM_DRY) {
          long water = BlockPos.asLong(dry.x + dx, dry.y + dy, dry.z + dz);
          if (surfaceCell(atlas, water, component, mode)) {
            int score = clearance(atlas, water, component, mode);
            if (score > bestScore) {
              best = water;
              bestScore = score;
            }
          }
        }
      }
    }
    return best;
  }

  static boolean surfaceCell(SurfaceWaterAtlas atlas, long packed, int component, TransportMode mode) {
    return mode == TransportMode.BOAT ? atlas.boatCell(packed) && atlas.boatComponent(packed) == component : atlas.swimCell(packed) && atlas.swimComponent(packed) == component;
  }

  static int clearance(SurfaceWaterAtlas atlas, long packed, int component, TransportMode mode) {
    int score = 0;
    int x0 = BlockPos.getX(packed);
    int y = BlockPos.getY(packed);
    int z0 = BlockPos.getZ(packed);
    for (int dx = -ANCHOR_CLEARANCE_RADIUS; dx <= ANCHOR_CLEARANCE_RADIUS; dx++) {
      for (int dz = -ANCHOR_CLEARANCE_RADIUS; dz <= ANCHOR_CLEARANCE_RADIUS; dz++) {
        long candidate = BlockPos.asLong(x0 + dx, y, z0 + dz);
        score += surfaceCell(atlas, candidate, component, mode) ? 1 : 0;
      }
    }
    return score;
  }

  private static boolean currentLaunchCorridor(MacroExpansionContext context, SurfaceWaterAtlas atlas, BetterBlockPos start, long water, TransportMode mode) {
    BetterBlockPos target = pos(water);
    return WaterLineKernel.traceCells(start, target, mode == TransportMode.BOAT ? WaterLineProfile.BOAT_HALF_WIDTH : WaterLineProfile.SWIM_HALF_WIDTH,
      (x, z, centerline) -> !centerline || corridorCell(context, atlas, x, z, start.y, target.y, mode));
  }

  private static boolean corridorCell(MacroExpansionContext context, SurfaceWaterAtlas atlas, int x, int z, int dryY, int waterY, TransportMode mode) {
    for (int dy = -2; dy <= 2; dy++) {
      if (atlas.dryCell(BlockPos.asLong(x, dryY + dy, z))) {
        return true;
      }
    }
    for (int dy = -1; dy <= 1; dy++) {
      long water = BlockPos.asLong(x, waterY + dy, z);
      if (mode == TransportMode.BOAT ? atlas.boatHullCell(water) || atlas.boatCell(water) : atlas.swimCell(water)) {
        return true;
      }
    }
    if (!context.calculation().worldBorder.entirelyContains(x, z) || !context.calculation().isLoaded(x, z)) {
      return false;
    }
    BlockState feet = context.calculation().get(x, dryY, z);
    return MovementHelper.canWalkOn(context.calculation(), x, dryY - 1, z) && MovementHelper.canWalkThrough(context.calculation(), x, dryY, z, feet)
      && MovementHelper.canWalkThrough(context.calculation(), x, dryY + 1, z);
  }

  private static BetterBlockPos pos(long packed) {
    return new BetterBlockPos(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
  }

  public record ComponentKey(TransportMode mode, int componentId) {
  }

  public record Anchor(long node, TransportMode mode, BetterBlockPos dry, BetterBlockPos water, int componentId, long surfaceCell, int clearance, boolean current, boolean waterborne) {
  }

  private record EntryKey(TransportMode mode, int componentId, long surfaceCell) {
  }

  private record Candidate(TransportMode mode, BetterBlockPos dry, BetterBlockPos water, int componentId, long surfaceCell, int clearance, boolean current, boolean waterborne) {
    double score(MacroExpansionContext context) {
      double startDistance = Math.hypot(dry.x - context.physicalStart().x, dry.z - context.physicalStart().z) + Math.hypot(water.x - context.physicalStart().x, water.z - context.physicalStart().z);
      double startBias = current ? 1_000D - 8D * startDistance : -0.05D * startDistance;
      double goalBias = -0.005D * context.goal().heuristic(dry.x, dry.y, dry.z);
      return clearance + startBias + goalBias;
    }
  }
}
