package baritone.pathing.meso.portal;

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.macro.portal.PortalFrame;
import baritone.pathing.macro.portal.PortalFrameSchematic;
import baritone.pathing.meso.MesoTaskBudget;
import baritone.pathing.meso.MesoTaskDomain;
import baritone.pathing.meso.MesoTaskEvidence;
import baritone.pathing.meso.MesoTaskFailure;
import baritone.pathing.meso.MesoTaskQuote;
import baritone.pathing.meso.MesoTaskRequest;
import baritone.pathing.meso.MesoTaskResult;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

public final class PortalMesoSiter implements MesoTaskDomain<PortalTaskIntent, PortalTaskPlan> {
  private static final double DEFAULT_IGNITION_TICKS = 60D;
  private static final double INTERIOR_BREAK_TICKS = 18D;
  private static final double FRAME_CLEAR_TICKS = 12D;
  private static final double POOR_FOOTING_TICKS = 80D;
  private static final double UNKNOWN_TERRAIN_TICKS = 120D;
  private static final double IMPOSSIBLE_TERRAIN_TICKS = 1_000_000D;
  private static final int BARE_SEARCH_HORIZONTAL_RADIUS = 3;
  private static final int BARE_SEARCH_UP = 5;

  @Override
  public MesoTaskQuote quoteFast(MesoTaskRequest<PortalTaskIntent> request) {
    PortalTaskIntent intent = request.intent();
    double use = portalUseCost();
    if (intent.kind() == PortalTaskKind.ENTER_EXISTING || intent.target() instanceof PortalTaskTarget.LitPortal) {
      return MesoTaskQuote.fixedTime(use, use, use + 20D, 0D, 0, MesoTaskEvidence.of("lit portal"));
    }
    int missing = intent.target().missingObsidian();
    double lower = ignitionCost() + use + materialPlacementCost(missing);
    TerrainQuote terrain = terrainQuote(request.calculation(), intent.target());
    double expected = lower + terrain.expectedTicks();
    double pessimistic = expected + Math.max(terrain.expectedTicks() * 0.5D, terrain.uncertaintyTicks());
    return MesoTaskQuote.fixedTime(lower, expected, pessimistic, terrain.uncertaintyTicks(), Math.max(0, missing - request.capabilities().obsidianBlocks()),
      new MesoTaskEvidence(terrain.summary(), terrain.factualSamples(), 0, terrain.unknownSamples()));
  }

  @Override
  public MesoTaskResult<PortalTaskPlan> solve(MesoTaskRequest<PortalTaskIntent> request, MesoTaskBudget budget) {
    MesoTaskQuote quote = quoteBounded(request, budget);
    PortalTaskIntent intent = request.intent();
    if (quote.missingMaterials() > 0) {
      return new MesoTaskResult.Impossible<>(new MesoTaskFailure("missing_obsidian", "missing " + quote.missingMaterials() + " obsidian"), quote);
    }
    return new MesoTaskResult.Solved<>(plan(request.calculation(), intent, quote), quote);
  }

  private static PortalTaskPlan plan(CalculationContext calculation, PortalTaskIntent intent, MesoTaskQuote quote) {
    return switch (intent.target()) {
      case PortalTaskTarget.LitPortal lit -> new PortalTaskPlan.EnterExisting(intent, lit.anchor(), quote.expected(), List.of(lit.anchor(), lit.pairedEstimate()));
      case PortalTaskTarget.Frame frame -> new PortalTaskPlan.BuildPortal(intent, frame.frame(), quote.expected(), frameRender(frame.frame(), frame.pairedEstimate()));
      case PortalTaskTarget.BareRegion bare -> {
        PortalBuildCandidate candidate = bareBuildCandidate(calculation, bare.anchor());
        yield new PortalTaskPlan.BuildPortal(intent, candidate.frame(), quote.expected(), frameRender(candidate.frame(), bare.pairedEstimate()));
      }
    };
  }

  private static TerrainQuote terrainQuote(CalculationContext calculation, PortalTaskTarget target) {
    return switch (target) {
      case PortalTaskTarget.LitPortal ignored -> TerrainQuote.EMPTY;
      case PortalTaskTarget.Frame frame -> frameTerrainQuote(calculation, frame.frame());
      case PortalTaskTarget.BareRegion bare -> bareTerrainQuote(calculation, bare.anchor());
    };
  }

  private static TerrainQuote frameTerrainQuote(CalculationContext calculation, PortalFrame.FrameMatch frame) {
    if (frame.missingObsidian() == 0) {
      return TerrainQuote.EMPTY.withSummary("complete frame");
    }
    double expected = 0D;
    int factual = 0;
    int unknown = 0;
    for (int horizontal = -1; horizontal <= PortalFrame.INNER_WIDTH; horizontal++) {
      for (int vertical = -1; vertical <= PortalFrame.INNER_HEIGHT; vertical++) {
        if (!PortalFrame.requiredFrameOffset(horizontal, vertical)) {
          continue;
        }
        BetterBlockPos pos = offset(frame.lowerLeftInterior(), frame.axis(), horizontal, vertical);
        CellQuote cell = cellQuote(calculation, pos);
        factual += cell.factual() ? 1 : 0;
        unknown += cell.factual() ? 0 : 1;
        if (cell.factual() && !cell.replaceable() && !cell.obsidian()) {
          expected += cell.breakable() ? Math.max(FRAME_CLEAR_TICKS, cell.miningTicks()) : IMPOSSIBLE_TERRAIN_TICKS;
        } else if (!cell.factual()) {
          expected += UNKNOWN_TERRAIN_TICKS / PortalFrame.MINIMAL_FRAME_BLOCKS;
        }
      }
    }
    return new TerrainQuote(expected, unknown * UNKNOWN_TERRAIN_TICKS / PortalFrame.MINIMAL_FRAME_BLOCKS, factual, unknown, "partial frame terrain");
  }

  private static TerrainQuote bareTerrainQuote(CalculationContext calculation, BetterBlockPos anchor) {
    return bareBuildCandidate(calculation, anchor).quote();
  }

  private static PortalBuildCandidate bareBuildCandidate(CalculationContext calculation, BetterBlockPos anchor) {
    PortalBuildCandidate best = null;
    double bestScore = Double.POSITIVE_INFINITY;
    for (int dy = 0; dy <= BARE_SEARCH_UP; dy++) {
      for (int dx = -BARE_SEARCH_HORIZONTAL_RADIUS; dx <= BARE_SEARCH_HORIZONTAL_RADIUS; dx++) {
        for (int dz = -BARE_SEARCH_HORIZONTAL_RADIUS; dz <= BARE_SEARCH_HORIZONTAL_RADIUS; dz++) {
          BetterBlockPos lowerLeft = new BetterBlockPos(anchor.x + dx, anchor.y + dy, anchor.z + dz);
          for (Direction.Axis axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
            PortalBuildCandidate candidate = bareBuildCandidate(calculation, anchor, new PortalFrame.FrameMatch(lowerLeft, axis, 0, PortalFrame.MINIMAL_FRAME_BLOCKS, 0));
            double score = candidate.quote().expectedTicks() + 0.25D * Math.hypot(dx, dz) + dy;
            if (score < bestScore) {
              bestScore = score;
              best = candidate.withSummary("bare portal region axis=" + axis);
            }
          }
        }
      }
    }
    if (best == null) {
      throw new IllegalStateException("bare portal search produced no candidates around " + anchor);
    }
    return best;
  }

  private static PortalBuildCandidate bareBuildCandidate(CalculationContext calculation, BetterBlockPos anchor, PortalFrame.FrameMatch frame) {
    if (solidFrameIntersectsStandingEnvelope(frame, anchor)) {
      return new PortalBuildCandidate(frame, new TerrainQuote(IMPOSSIBLE_TERRAIN_TICKS, 0D, 0, 0, "portal frame intersects builder envelope"));
    }
    double expected = 0D;
    double uncertainty = 0D;
    int factual = 0;
    int unknown = 0;
    for (int horizontal = -1; horizontal <= PortalFrame.INNER_WIDTH; horizontal++) {
      for (int vertical = -1; vertical <= PortalFrame.INNER_HEIGHT; vertical++) {
        BetterBlockPos pos = offset(frame.lowerLeftInterior(), frame.axis(), horizontal, vertical);
        CellQuote cell = cellQuote(calculation, pos);
        factual += cell.factual() ? 1 : 0;
        unknown += cell.factual() ? 0 : 1;
        if (!cell.factual()) {
          uncertainty += UNKNOWN_TERRAIN_TICKS / 2D;
          expected += UNKNOWN_TERRAIN_TICKS / 4D;
        } else if (PortalFrame.interiorOffset(horizontal, vertical) && !cell.replaceable()) {
          expected += cell.breakable() ? Math.max(INTERIOR_BREAK_TICKS, cell.miningTicks()) : IMPOSSIBLE_TERRAIN_TICKS;
        } else if (PortalFrame.requiredFrameOffset(horizontal, vertical) && !cell.replaceable() && !cell.obsidian()) {
          expected += cell.breakable() ? Math.max(FRAME_CLEAR_TICKS, cell.miningTicks()) : IMPOSSIBLE_TERRAIN_TICKS;
        }
      }
    }
    BetterBlockPos floor = frame.lowerLeftInterior().below();
    CellQuote floorCell = cellQuote(calculation, floor);
    if (!floorCell.factual() || !MovementHelper.canWalkOn(calculation.bsi, floor.x, floor.y, floor.z)) {
      expected += POOR_FOOTING_TICKS;
      uncertainty += POOR_FOOTING_TICKS;
    }
    return new PortalBuildCandidate(frame, new TerrainQuote(expected, uncertainty, factual, unknown, "bare portal terrain"));
  }

  private static boolean solidFrameIntersectsStandingEnvelope(PortalFrame.FrameMatch frame, BetterBlockPos anchor) {
    for (int horizontal = -1; horizontal <= PortalFrame.INNER_WIDTH; horizontal++) {
      for (int vertical = -1; vertical <= PortalFrame.INNER_HEIGHT; vertical++) {
        if (!PortalFrame.requiredFrameOffset(horizontal, vertical)) {
          continue;
        }
        BetterBlockPos pos = offset(frame.lowerLeftInterior(), frame.axis(), horizontal, vertical);
        if (pos.x == anchor.x && pos.z == anchor.z && (pos.y == anchor.y || pos.y == anchor.y + 1)) {
          return true;
        }
      }
    }
    return false;
  }

  private static CellQuote cellQuote(CalculationContext calculation, BetterBlockPos pos) {
    if (!calculation.hasPathingData(pos.x, pos.z)) {
      return new CellQuote(false, false, false, false, COST_INF);
    }
    BlockState state = calculation.get(pos);
    boolean replaceable = MovementHelper.isReplaceable(pos.x, pos.y, pos.z, state, calculation.bsi);
    double miningTicks = replaceable ? 0D : calculation.affordances.miningCost(pos.x, pos.y, pos.z, state, true);
    boolean breakable = replaceable || miningTicks < COST_INF;
    return new CellQuote(true, replaceable, state.is(Blocks.OBSIDIAN), breakable, miningTicks);
  }

  private static BetterBlockPos offset(BetterBlockPos lowerLeftInterior, Direction.Axis axis, int horizontal, int vertical) {
    return switch (axis) {
      case X -> new BetterBlockPos(lowerLeftInterior.x + horizontal, lowerLeftInterior.y + vertical, lowerLeftInterior.z);
      case Z -> new BetterBlockPos(lowerLeftInterior.x, lowerLeftInterior.y + vertical, lowerLeftInterior.z + horizontal);
      default -> throw new IllegalArgumentException("portal frame axis must be horizontal: " + axis);
    };
  }

  private static double materialPlacementCost(int missingObsidian) {
    return Baritone.settings().macroNetherPortalBuildCost.value * missingObsidian / (double) PortalFrame.MINIMAL_FRAME_BLOCKS;
  }

  private static double portalUseCost() {
    return Baritone.settings().macroNetherPortalUseCost.value;
  }

  private static double ignitionCost() {
    return Math.max(DEFAULT_IGNITION_TICKS, Baritone.settings().macroNetherPortalUseCost.value * 0.5D);
  }

  private static List<BetterBlockPos> frameRender(PortalFrame.FrameMatch frame, BetterBlockPos pairedEstimate) {
    BetterBlockPos origin = PortalFrameSchematic.originFor(frame.lowerLeftInterior(), frame.axis());
    return List.of(origin, frame.lowerLeftInterior(), pairedEstimate);
  }

  private record CellQuote(boolean factual, boolean replaceable, boolean obsidian, boolean breakable, double miningTicks) {
  }

  private record TerrainQuote(double expectedTicks, double uncertaintyTicks, int factualSamples, int unknownSamples, String summary) {
    private static final TerrainQuote EMPTY = new TerrainQuote(0D, 0D, 0, 0, "none");

    private TerrainQuote withSummary(String summary) {
      return new TerrainQuote(expectedTicks, uncertaintyTicks, factualSamples, unknownSamples, summary);
    }
  }

  private record PortalBuildCandidate(PortalFrame.FrameMatch frame, TerrainQuote quote) {
    private PortalBuildCandidate withSummary(String summary) {
      return new PortalBuildCandidate(frame, quote.withSummary(summary));
    }
  }
}
