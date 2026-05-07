package baritone.pathing.macro.water;

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.transport.TransportMode;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public final class SurfaceWaterAtlas {
  private static final int[] D = {-1, 0, 1};
  private static final int[] DRY_Y_OFFSETS = {0, 1};
  private static final byte BLOCKED = 0;
  private static final byte DRY = 1;
  private static final byte SWIM = 2;
  private static final byte BOAT = 3;

  private final Long2ByteOpenHashMap cellKindByPos = new Long2ByteOpenHashMap();
  private final Long2IntOpenHashMap boatComponentByPos = new Long2IntOpenHashMap();
  private final Long2IntOpenHashMap swimComponentByPos = new Long2IntOpenHashMap();
  private final List<WaterComponent> boatComponents;
  private final List<WaterComponent> swimComponents;
  private final LongOpenHashSet boatCells;
  private final LongOpenHashSet boatHullCells;
  private final LongOpenHashSet swimCells;
  private final LongOpenHashSet dryCells;

  private SurfaceWaterAtlas(LongOpenHashSet boatCells, LongOpenHashSet boatHullCells, LongOpenHashSet swimCells, LongOpenHashSet dryCells, List<WaterComponent> boatComponents,
    List<WaterComponent> swimComponents) {
    this.boatCells = boatCells;
    this.boatHullCells = boatHullCells;
    this.swimCells = swimCells;
    this.dryCells = dryCells;
    this.boatComponents = List.copyOf(boatComponents);
    this.swimComponents = List.copyOf(swimComponents);
    cellKindByPos.defaultReturnValue(BLOCKED);
    boatComponentByPos.defaultReturnValue(-1);
    swimComponentByPos.defaultReturnValue(-1);
    dryCells.forEach(p -> cellKindByPos.put(p, DRY));
    swimCells.forEach(p -> cellKindByPos.put(p, SWIM));
    boatCells.forEach(p -> cellKindByPos.put(p, BOAT));
    index(boatComponents, boatComponentByPos);
    index(swimComponents, swimComponentByPos);
  }

  public static SurfaceWaterAtlas build(CalculationContext context, BetterBlockPos start, int horizon) {
    LongOpenHashSet boat = new LongOpenHashSet();
    LongOpenHashSet boatHull = new LongOpenHashSet();
    LongOpenHashSet swim = new LongOpenHashSet();
    LongOpenHashSet dry = new LongOpenHashSet();
    int minX = start.x - horizon;
    int maxX = start.x + horizon;
    int minZ = start.z - horizon;
    int maxZ = start.z + horizon;
    int minY = Math.max(context.world.getMinY() + 2, start.y - 8);
    int maxY = Math.min(context.world.getMaxY() - 3, start.y + 8);
    boolean requireBelow = Baritone.settings().macroBoatRequiresWaterBelow.value;
    LongOpenHashSet dryCandidates = new LongOpenHashSet();
    for (int chunkX = Math.floorDiv(minX, 16); chunkX <= Math.floorDiv(maxX, 16); chunkX++) {
      for (int chunkZ = Math.floorDiv(minZ, 16); chunkZ <= Math.floorDiv(maxZ, 16); chunkZ++) {
        int chunkMinX = Math.max(minX, chunkX << 4);
        int chunkMaxX = Math.min(maxX, (chunkX << 4) + 15);
        int chunkMinZ = Math.max(minZ, chunkZ << 4);
        int chunkMaxZ = Math.min(maxZ, (chunkZ << 4) + 15);
        if (!knownChunk(context, chunkMinX, chunkMaxX, chunkMinZ, chunkMaxZ)) {
          continue;
        }
        for (int x = chunkMinX; x <= chunkMaxX; x++) {
          for (int z = chunkMinZ; z <= chunkMaxZ; z++) {
            if (!context.worldBorder.entirelyContains(x, z)) {
              continue;
            }
            for (int y = minY; y <= maxY; y++) {
              if (boatHull(context, x, y, z)) {
                boatHull.add(BlockPos.asLong(x, y, z));
              }
              byte kind = waterKind(context, x, y, z, requireBelow);
              if (kind == BLOCKED) {
                continue;
              }
              long packed = BlockPos.asLong(x, y, z);
              if (kind == BOAT) {
                boat.add(packed);
                if (stableSwimCell(context, x, y, z)) {
                  swim.add(packed);
                }
              } else if (kind == SWIM) {
                swim.add(packed);
              }
              addDryCandidates(dryCandidates, x, y, z);
            }
          }
        }
      }
    }
    dryCandidates.forEach(packed -> {
      if (dryStable(context, BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed))) {
        dry.add(packed);
      }
    });
    ArrayList<WaterComponent> boatComponents = components(boat, dry, TransportMode.BOAT);
    ArrayList<WaterComponent> swimComponents = components(swim, dry, TransportMode.SWIM);
    return new SurfaceWaterAtlas(boat, boatHull, swim, dry, boatComponents, swimComponents);
  }

  public SurfaceCellKind kind(long packed) {
    return switch (cellKindByPos.get(packed)) {
      case DRY -> SurfaceCellKind.DRY_STABLE;
      case SWIM -> SurfaceCellKind.SWIM_SURFACE;
      case BOAT -> SurfaceCellKind.BOAT_POINT_CLEAR;
      default -> SurfaceCellKind.BLOCKED;
    };
  }

  public boolean boatCell(long packed) {
    return boatCells.contains(packed);
  }

  public boolean boatHullCell(long packed) {
    return boatHullCells.contains(packed);
  }

  public boolean swimCell(long packed) {
    return swimCells.contains(packed);
  }

  public boolean dryCell(long packed) {
    return dryCells.contains(packed);
  }

  public int boatComponent(long packed) {
    return boatComponentByPos.get(packed);
  }

  public int swimComponent(long packed) {
    return swimComponentByPos.get(packed);
  }

  public List<WaterComponent> boatComponents() {
    return boatComponents;
  }

  public List<WaterComponent> swimComponents() {
    return swimComponents;
  }

  public LongOpenHashSet boatCells() {
    return boatCells;
  }

  public LongOpenHashSet dryCells() {
    return dryCells;
  }

  private static void index(List<WaterComponent> components, Long2IntOpenHashMap out) {
    for (WaterComponent component : components) {
      component.cells().forEach(p -> out.put(p, component.id()));
    }
  }

  private static ArrayList<WaterComponent> components(LongOpenHashSet cells, LongOpenHashSet dry, TransportMode mode) {
    LongOpenHashSet seen = new LongOpenHashSet(cells.size());
    ArrayList<WaterComponent> result = new ArrayList<>();
    LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
    LongIterator seeds = cells.iterator();
    while (seeds.hasNext()) {
      long seed = seeds.nextLong();
      if (!seen.add(seed)) {
        continue;
      }
      queue.enqueue(seed);
      LongArrayList component = new LongArrayList();
      LongOpenHashSet boundary = new LongOpenHashSet();
      LongOpenHashSet launches = new LongOpenHashSet();
      LongOpenHashSet landings = new LongOpenHashSet();
      LongOpenHashSet frontier = new LongOpenHashSet();
      LongOpenHashSet chokes = new LongOpenHashSet();
      int y = BlockPos.getY(seed);
      while (!queue.isEmpty()) {
        long packed = queue.dequeueLong();
        component.add(packed);
        int x = BlockPos.getX(packed);
        int z = BlockPos.getZ(packed);
        boolean edge = false;
        for (int dx : D) {
          for (int dz : D) {
            if (dx == 0 && dz == 0) {
              continue;
            }
            long neighbor = BlockPos.asLong(x + dx, y, z + dz);
            if (cells.contains(neighbor)) {
              if (seen.add(neighbor)) {
                queue.enqueue(neighbor);
              }
            } else {
              edge = true;
              for (int dy : DRY_Y_OFFSETS) {
                long dryNeighbor = BlockPos.asLong(x + dx, y + dy, z + dz);
                if (dry.contains(dryNeighbor)) {
                  launches.add(dryNeighbor);
                  landings.add(dryNeighbor);
                }
              }
            }
          }
        }
        if (edge) {
          boundary.add(packed);
        }
      }
      result.add(new WaterComponent(result.size(), mode, y, component, new LongArrayList(boundary), new LongArrayList(launches), new LongArrayList(landings), new LongArrayList(frontier),
        new LongArrayList(chokes)));
    }
    return result;
  }

  private static boolean dryStable(CalculationContext context, int x, int y, int z) {
    BlockState feet = context.get(x, y, z);
    return !MovementHelper.isWater(feet) && MovementHelper.canWalkOn(context, x, y - 1, z) && MovementHelper.canWalkThrough(context, x, y, z, feet)
      && MovementHelper.canWalkThrough(context, x, y + 1, z);
  }

  private static byte waterKind(CalculationContext context, int x, int y, int z, boolean requireBoatWaterBelow) {
    BlockState feet = context.get(x, y, z);
    if (!MovementHelper.isWater(feet) || !MovementHelper.canSwimThrough(context, feet)) {
      return BLOCKED;
    }
    BlockState below = context.get(x, y - 1, z);
    BlockState head = context.get(x, y + 1, z);
    if (MovementHelper.isWater(head) || !MovementHelper.canMoveThrough(context, x, y + 1, z, head)) {
      return BLOCKED;
    }
    boolean waterBelow = MovementHelper.isWater(below);
    BlockState canopy = context.get(x, y + 2, z);
    if ((!requireBoatWaterBelow || waterBelow) && MovementHelper.canMoveThrough(context, x, y + 2, z, canopy)) {
      return BOAT;
    }
    return waterBelow ? SWIM : BLOCKED;
  }

  private static boolean stableSwimCell(CalculationContext context, int x, int y, int z) {
    return MovementHelper.surfaceSwimCell(context, x, y, z);
  }

  private static boolean boatHull(CalculationContext context, int x, int y, int z) {
    BlockState body = context.get(x, y, z);
    BlockState head = context.get(x, y + 1, z);
    BlockState canopy = context.get(x, y + 2, z);
    return (MovementHelper.isWater(body) && MovementHelper.canSwimThrough(context, body) || MovementHelper.canMoveThrough(context, x, y, z, body)) && !MovementHelper.isWater(head)
      && MovementHelper.canMoveThrough(context, x, y + 1, z, head) && MovementHelper.canMoveThrough(context, x, y + 2, z, canopy);
  }

  private static void addDryCandidates(LongOpenHashSet dryCandidates, int waterX, int waterY, int waterZ) {
    for (int dx : D) {
      for (int dz : D) {
        if (dx == 0 && dz == 0) {
          continue;
        }
        for (int dy : DRY_Y_OFFSETS) {
          dryCandidates.add(BlockPos.asLong(waterX + dx, waterY + dy, waterZ + dz));
        }
      }
    }
  }

  private static boolean knownChunk(CalculationContext context, int minX, int maxX, int minZ, int maxZ) {
    if (context.bsi.worldContainsLoadedChunk(minX, minZ)) {
      return true;
    }
    int centerX = Math.floorDiv(minX + maxX, 2);
    int centerZ = Math.floorDiv(minZ + maxZ, 2);
    return context.isLoaded(centerX, centerZ) || context.isLoaded(minX, minZ) || context.isLoaded(maxX, minZ) || context.isLoaded(minX, maxZ) || context.isLoaded(maxX, maxZ);
  }
}
