package baritone.pathing.macro.core;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

public final class MacroNavigator {
  private Signature activeSignature;
  private MacroValueField activeField;

  public Optional<MacroDirective> plan(CalculationContext calculation, BetterBlockPos start, Goal goal) {
    return plan(calculation, start, goal, MacroTraversalProfile.physical(calculation));
  }

  public Optional<MacroDirective> plan(CalculationContext calculation, BetterBlockPos start, Goal goal, MacroTraversalProfile profile) {
    if (calculation.world.dimension() == Level.OVERWORLD && buriedBelowSurface(calculation, start)) {
      return Optional.empty();
    }
    Optional<BlockPos> goalPos = MacroGoals.pos(goal);
    if (goalPos.isEmpty()) {
      return Optional.empty();
    }
    MacroAtlas atlas = MacroAtlas.build(calculation, start, false);
    int cellBlocks = atlas.cellBlocks();
    double fullDistance = Math.hypot(goalPos.get().getX() - start.x, goalPos.get().getZ() - start.z);
    if (fullDistance < Math.max(cellBlocks * 3D, Baritone.settings().macroBiomeWaypointBlocks.value)) {
      return Optional.empty();
    }
    MacroPolicy policy = MacroPolicy.configured();
    int horizonCells = Math.max(4, (int) Math.ceil(Baritone.settings().macroBiomeHorizonBlocks.value / (double) cellBlocks));
    int lateralCells = Math.max(8, Math.min(horizonCells, 32));
    int sx = Math.floorDiv(start.x, cellBlocks);
    int sz = Math.floorDiv(start.z, cellBlocks);
    int trueGx = Math.floorDiv(goalPos.get().getX(), cellBlocks);
    int trueGz = Math.floorDiv(goalPos.get().getZ(), cellBlocks);
    Target target = target(start, goalPos.get(), fullDistance, cellBlocks);
    long src = MacroNodeKey.cell(calculation.world.dimension(), MacroStratum.SURFACE, atlas.scale(), sx, sz);
    long dst = MacroNodeKey.cell(calculation.world.dimension(), MacroStratum.SURFACE, atlas.scale(), target.cellX, target.cellZ);
    int minX = Math.min(sx, target.cellX) - lateralCells;
    int maxX = Math.max(sx, target.cellX) + lateralCells;
    int minZ = Math.min(sz, target.cellZ) - lateralCells;
    int maxZ = Math.max(sz, target.cellZ) + lateralCells;
    double expectedTerminal = terminalExpected(policy, atlas, profile, target, trueGx, trueGz);
    double floorTerminal = target.cellX == trueGx && target.cellZ == trueGz ? 0D : terminalFloor(calculation, profile, goalPos.get(), target.center);
    Signature signature = new Signature(calculation.world.dimension().identifier().toString(), atlas.scale(), dst, minX, maxX, minZ, maxZ, policy, profile, trueGx, trueGz);
    MacroValueField field = field(signature, atlas, policy, profile, src, dst, minX, maxX, minZ, maxZ, expectedTerminal, floorTerminal);
    if (!Double.isFinite(field.telemetry().expectedStartValue())) {
      return Optional.empty();
    }
    ValueProjectedExitObjective objective = new ValueProjectedExitObjective(goal, field);
    MacroPlan plan = field.plan(start, new BetterBlockPos(goalPos.get().getX(), goalPos.get().getY(), goalPos.get().getZ()), objective, policy);
    return Optional.of(MacroDirective.localGoal(plan));
  }

  private MacroValueField field(Signature signature, MacroAtlas atlas, MacroPolicy policy, MacroTraversalProfile profile, long src, long dst, int minX, int maxX, int minZ, int maxZ,
    double expectedTerminal, double floorTerminal) {
    if (activeField != null && signature.equals(activeSignature)) {
      activeField.repair(atlas, src, expectedTerminal, floorTerminal);
      return activeField;
    }
    activeSignature = signature;
    activeField = MacroValueField.build(atlas, policy, profile, src, dst, minX, maxX, minZ, maxZ, expectedTerminal, floorTerminal);
    return activeField;
  }

  private static Target target(BetterBlockPos start, BlockPos goal, double fullDistance, int cellBlocks) {
    int horizon = Baritone.settings().macroBiomeHorizonBlocks.value;
    int x = goal.getX();
    int z = goal.getZ();
    if (fullDistance > horizon) {
      double scale = horizon / fullDistance;
      x = start.x + (int) Math.round((goal.getX() - start.x) * scale);
      z = start.z + (int) Math.round((goal.getZ() - start.z) * scale);
    }
    int cellX = Math.floorDiv(x, cellBlocks);
    int cellZ = Math.floorDiv(z, cellBlocks);
    return new Target(cellX, cellZ, new BetterBlockPos(cellX * cellBlocks + cellBlocks / 2, goal.getY(), cellZ * cellBlocks + cellBlocks / 2));
  }

  private static double terminalExpected(MacroPolicy policy, MacroAtlas atlas, MacroTraversalProfile profile, Target target, int trueGx, int trueGz) {
    if (target.cellX == trueGx && target.cellZ == trueGz) {
      return 0D;
    }
    BetterBlockPos from = atlas.center(target.cellX, target.cellZ);
    BetterBlockPos to = atlas.center(trueGx, trueGz);
    double distance = Math.hypot(to.x - from.x, to.z - from.z);
    return policy.score(profile.surfaceCost(atlas, target.cellX, target.cellZ, distance), profile.canonicalSurfaceState(), profile.canonicalSurfaceState());
  }

  private static double terminalFloor(CalculationContext calculation, MacroTraversalProfile profile, BlockPos finalGoal, BetterBlockPos target) {
    return Math.hypot(finalGoal.getX() - target.x, finalGoal.getZ() - target.z) * profile.lowerBoundTicksPerBlock(calculation);
  }

  private static boolean buriedBelowSurface(CalculationContext calculation, BetterBlockPos start) {
    int surfaceY = calculation.world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, start.x, start.z);
    return start.y + 4 < surfaceY;
  }

  private record Target(int cellX, int cellZ, BetterBlockPos center) {
  }

  private record Signature(String dimension, int scale, long target, int minCellX, int maxCellX, int minCellZ, int maxCellZ, MacroPolicy policy, MacroTraversalProfile profile, int finalGoalCellX,
    int finalGoalCellZ) {
  }
}
