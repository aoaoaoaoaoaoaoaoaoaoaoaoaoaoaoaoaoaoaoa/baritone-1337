package baritone.pathing.macro.portal;

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.macro.core.MacroExpansionContext;
import baritone.pathing.macro.core.MacroNodeKey;
import baritone.pathing.macro.core.MacroStratum;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.meso.portal.PortalTaskTarget;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class PortalSiteIndex {
  private static final int MAX_SITES = 256;
  private final int dimensionId;
  private final Long2ObjectOpenHashMap<ArrayList<PortalSite>> bySourceCell;
  private final LongOpenHashSet litPortals;

  private PortalSiteIndex(int dimensionId, Long2ObjectOpenHashMap<ArrayList<PortalSite>> bySourceCell, LongOpenHashSet litPortals) {
    this.dimensionId = dimensionId;
    this.bySourceCell = bySourceCell;
    this.litPortals = litPortals;
  }

  static PortalSiteIndex build(MacroExpansionContext context) {
    Long2ObjectOpenHashMap<ArrayList<PortalSite>> byCell = new Long2ObjectOpenHashMap<>();
    LongOpenHashSet siteKeys = new LongOpenHashSet();
    ArrayList<BetterBlockPos> obsidian = new ArrayList<>();
    LongOpenHashSet litPortals = new LongOpenHashSet();
    addCachedPortals(context, byCell, siteKeys, litPortals);
    addCachedObsidian(context, obsidian);
    addLiveBlocks(context, byCell, siteKeys, obsidian, litPortals);
    addFrameSites(context, byCell, siteKeys, obsidian);
    byCell.values().forEach(sites -> sites.sort(Comparator.comparingInt(PortalSite::missingObsidian)));
    return new PortalSiteIndex(MacroNodeKey.dimensionId(context.calculation().world.dimension()), byCell, litPortals);
  }

  boolean empty() {
    return bySourceCell.isEmpty();
  }

  List<PortalSite> sites(long sourceCell) {
    ArrayList<PortalSite> sites = bySourceCell.get(sourceCell);
    return sites == null ? List.of() : sites;
  }

  boolean wouldRelinkToKnownPortal(BetterBlockPos sourcePortal, int sourceDimensionId) {
    int destinationDimensionId = PortalGeometry.pairedDimension(sourceDimensionId);
    if (destinationDimensionId != dimensionId) {
      return false;
    }
    for (long packed : litPortals) {
      BetterBlockPos existing = new BetterBlockPos(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
      if (PortalGeometry.wouldSourcePortalRelink(sourcePortal, sourceDimensionId, existing)) {
        return true;
      }
    }
    return false;
  }

  private static void addCachedPortals(MacroExpansionContext context, Long2ObjectOpenHashMap<ArrayList<PortalSite>> byCell, LongOpenHashSet siteKeys, LongOpenHashSet litPortals) {
    CalculationContext calculation = context.calculation();
    int radius = Baritone.settings().macroNetherPortalScanBlocks.value;
    int regionRadius = Math.max(1, (radius + 511) >> 9);
    for (BlockPos pos : calculation.worldData.getCachedWorld().getLocationsOf("nether_portal", MAX_SITES, context.physicalStart().x, context.physicalStart().z, regionRadius * regionRadius)) {
      if (flatDistance(context.physicalStart(), pos) <= radius) {
        addLitSite(context, byCell, siteKeys, litPortals, new BetterBlockPos(pos));
      }
    }
  }

  private static void addCachedObsidian(MacroExpansionContext context, ArrayList<BetterBlockPos> obsidian) {
    CalculationContext calculation = context.calculation();
    int radius = Baritone.settings().macroNetherPortalScanBlocks.value;
    int regionRadius = Math.max(1, (radius + 511) >> 9);
    for (BlockPos pos : calculation.worldData.getCachedWorld().getLocationsOf("obsidian", MAX_SITES * PortalFrame.MINIMAL_FRAME_BLOCKS, context.physicalStart().x, context.physicalStart().z,
      regionRadius * regionRadius)) {
      if (flatDistance(context.physicalStart(), pos) <= radius) {
        obsidian.add(new BetterBlockPos(pos));
      }
    }
  }

  private static void addLiveBlocks(MacroExpansionContext context, Long2ObjectOpenHashMap<ArrayList<PortalSite>> byCell, LongOpenHashSet siteKeys, ArrayList<BetterBlockPos> obsidian,
    LongOpenHashSet litPortals) {
    int radius = Baritone.settings().macroNetherPortalScanBlocks.value;
    if (radius <= 0) {
      return;
    }
    CalculationContext calculation = context.calculation();
    BetterBlockPos start = context.physicalStart();
    int minX = start.x - radius;
    int maxX = start.x + radius;
    int minZ = start.z - radius;
    int maxZ = start.z + radius;
    int yRadius = Math.min(96, calculation.world.getMaxY() - calculation.world.getMinY());
    int minY = Math.max(calculation.world.getMinY(), start.y - yRadius);
    int maxY = Math.min(calculation.world.getMaxY() - 1, start.y + yRadius);
    int radiusSq = radius * radius;
    int minCx = minX >> 4;
    int maxCx = maxX >> 4;
    int minCz = minZ >> 4;
    int maxCz = maxZ >> 4;
    for (int cx = minCx; cx <= maxCx && siteKeys.size() < MAX_SITES; cx++) {
      for (int cz = minCz; cz <= maxCz && siteKeys.size() < MAX_SITES; cz++) {
        if (!calculation.world.getChunkSource().hasChunk(cx, cz)) {
          continue;
        }
        scanLiveChunk(context, byCell, siteKeys, obsidian, litPortals, calculation.world.getChunkSource().getChunk(cx, cz, false), minX, maxX, minY, maxY, minZ, maxZ, start, radiusSq);
      }
    }
  }

  private static void scanLiveChunk(MacroExpansionContext context, Long2ObjectOpenHashMap<ArrayList<PortalSite>> byCell, LongOpenHashSet siteKeys, ArrayList<BetterBlockPos> obsidian,
    LongOpenHashSet litPortals, LevelChunk chunk, int minX, int maxX, int minY, int maxY, int minZ, int maxZ, BetterBlockPos start, int radiusSq) {
    if (chunk == null || chunk.isEmpty()) {
      return;
    }
    LevelChunkSection[] sections = chunk.getSections();
    int chunkMinY = chunk.getMinY();
    for (int sectionIndex = 0; sectionIndex < sections.length && siteKeys.size() < MAX_SITES; sectionIndex++) {
      LevelChunkSection section = sections[sectionIndex];
      int sectionY = chunkMinY + (sectionIndex << 4);
      if (section == null || section.hasOnlyAir() || sectionY > maxY || sectionY + 15 < minY || !section.maybeHas(state -> state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.OBSIDIAN))) {
        continue;
      }
      scanLiveSection(context, byCell, siteKeys, obsidian, litPortals, chunk, section, sectionY, minX, maxX, minY, maxY, minZ, maxZ, start, radiusSq);
    }
  }

  private static void scanLiveSection(MacroExpansionContext context, Long2ObjectOpenHashMap<ArrayList<PortalSite>> byCell, LongOpenHashSet siteKeys, ArrayList<BetterBlockPos> obsidian,
    LongOpenHashSet litPortals, LevelChunk chunk, LevelChunkSection section, int sectionY, int minX, int maxX, int minY, int maxY, int minZ, int maxZ, BetterBlockPos start, int radiusSq) {
    int baseX = chunk.getPos().x() << 4;
    int baseZ = chunk.getPos().z() << 4;
    int fromX = Math.max(0, minX - baseX);
    int toX = Math.min(15, maxX - baseX);
    int fromY = Math.max(0, minY - sectionY);
    int toY = Math.min(15, maxY - sectionY);
    int fromZ = Math.max(0, minZ - baseZ);
    int toZ = Math.min(15, maxZ - baseZ);
    for (int y = fromY; y <= toY && siteKeys.size() < MAX_SITES; y++) {
      int worldY = sectionY + y;
      for (int z = fromZ; z <= toZ && siteKeys.size() < MAX_SITES; z++) {
        int worldZ = baseZ + z;
        int dz = worldZ - start.z;
        for (int x = fromX; x <= toX && siteKeys.size() < MAX_SITES; x++) {
          int worldX = baseX + x;
          int dx = worldX - start.x;
          if (dx * dx + dz * dz > radiusSq) {
            continue;
          }
          BlockState state = section.getBlockState(x, y, z);
          if (state.is(Blocks.NETHER_PORTAL)) {
            addLitSite(context, byCell, siteKeys, litPortals, new BetterBlockPos(worldX, worldY, worldZ));
          } else if (state.is(Blocks.OBSIDIAN)) {
            obsidian.add(new BetterBlockPos(worldX, worldY, worldZ));
          }
        }
      }
    }
  }

  private static void addFrameSites(MacroExpansionContext context, Long2ObjectOpenHashMap<ArrayList<PortalSite>> byCell, LongOpenHashSet siteKeys, ArrayList<BetterBlockPos> obsidian) {
    LongOpenHashSet evaluated = new LongOpenHashSet();
    for (BetterBlockPos block : obsidian) {
      for (Direction.Axis axis : List.of(Direction.Axis.X, Direction.Axis.Z)) {
        for (BetterBlockPos lowerLeft : PortalFrame.candidateLowerLefts(block, axis)) {
          long key = frameKey(lowerLeft, axis);
          if (!evaluated.add(key)) {
            continue;
          }
          PortalFrame.FrameMatch frame = PortalFrame.evaluate(lowerLeft, axis, pos -> context.calculation().get(pos).is(Blocks.OBSIDIAN), pos -> openInterior(context.calculation().get(pos)));
          if (!frame.usableCandidate()) {
            continue;
          }
          addFrameSite(context, byCell, siteKeys, frame);
        }
      }
    }
  }

  private static boolean openInterior(BlockState state) {
    return state.isAir() || state.is(Blocks.NETHER_PORTAL) || state.canBeReplaced();
  }

  private static void addLitSite(MacroExpansionContext context, Long2ObjectOpenHashMap<ArrayList<PortalSite>> byCell, LongOpenHashSet siteKeys, LongOpenHashSet litPortals,
    BetterBlockPos interaction) {
    litPortals.add(interaction.asLong());
    BetterBlockPos pairedEstimate = pairedEstimate(context, interaction);
    addSite(context, byCell, siteKeys, new PortalTaskTarget.LitPortal(interaction, pairedEstimate));
  }

  private static void addFrameSite(MacroExpansionContext context, Long2ObjectOpenHashMap<ArrayList<PortalSite>> byCell, LongOpenHashSet siteKeys, PortalFrame.FrameMatch frame) {
    BetterBlockPos pairedEstimate = pairedEstimate(context, frame.lowerLeftInterior());
    addSite(context, byCell, siteKeys, new PortalTaskTarget.Frame(frame, pairedEstimate));
  }

  private static void addSite(MacroExpansionContext context, Long2ObjectOpenHashMap<ArrayList<PortalSite>> byCell, LongOpenHashSet siteKeys, PortalTaskTarget target) {
    int dimensionId = MacroNodeKey.dimensionId(context.calculation().world.dimension());
    if (!PortalGeometry.portalDimension(dimensionId)) {
      return;
    }
    int targetDimensionId = PortalGeometry.pairedDimension(dimensionId);
    int cellBlocks = context.atlas().cellBlocks();
    int scale = context.atlas().scale();
    BetterBlockPos interaction = target.anchor();
    long sourceCell = MacroNodeKey.cell(dimensionId, MacroStratum.SURFACE, scale, Math.floorDiv(interaction.x, cellBlocks), Math.floorDiv(interaction.z, cellBlocks));
    BetterBlockPos pairedEstimate = target.pairedEstimate();
    long targetCell = MacroNodeKey.cell(targetDimensionId, MacroStratum.SURFACE, scale, Math.floorDiv(pairedEstimate.x, cellBlocks), Math.floorDiv(pairedEstimate.z, cellBlocks));
    long siteKey = siteKey(sourceCell, interaction, target.missingObsidian(), target.siteKind());
    if (!siteKeys.add(siteKey)) {
      return;
    }
    byCell.computeIfAbsent(sourceCell, ignored -> new ArrayList<>())
      .add(new PortalSite(target.siteKind(), interaction, pairedEstimate, sourceCell, targetCell, dimensionId, targetDimensionId, target.missingObsidian(), target));
  }

  private static BetterBlockPos pairedEstimate(MacroExpansionContext context, BetterBlockPos interaction) {
    int dimensionId = MacroNodeKey.dimensionId(context.calculation().world.dimension());
    return PortalGeometry.transform(interaction, dimensionId, PortalGeometry.pairedDimension(dimensionId));
  }

  private static long frameKey(BetterBlockPos lowerLeft, Direction.Axis axis) {
    long key = lowerLeft.asLong();
    return axis == Direction.Axis.X ? key : ~key;
  }

  private static long siteKey(long sourceCell, BetterBlockPos interaction, int missingObsidian, PortalSiteKind kind) {
    long key = interaction.asLong();
    key ^= sourceCell * 0x9E37_79B9_7F4A_7C15L;
    key ^= (long) missingObsidian << 56;
    key ^= (long) kind.ordinal() << 60;
    return key;
  }

  private static double flatDistance(BetterBlockPos a, BlockPos b) {
    return Math.hypot(a.x - b.getX(), a.z - b.getZ());
  }
}
