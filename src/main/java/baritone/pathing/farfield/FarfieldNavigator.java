package baritone.pathing.farfield;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.macro.core.MacroTraversalProfile;
import baritone.pathing.movement.CalculationContext;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class FarfieldNavigator {
  public Optional<FarfieldObjective> objective(CalculationContext context, BetterBlockPos start, Goal goal, MacroTraversalProfile profile) {
    if (!Baritone.settings().farfieldPlanning.value || profile.horse()) {
      return Optional.empty();
    }
    Optional<BlockPos> goalPos = FarfieldSnapshot.goalPosition(goal);
    if (goalPos.isPresent() && context.bsi.hasLiveChunk(goalPos.get().getX(), goalPos.get().getZ())) {
      return Optional.empty();
    }
    if (goalPos.isPresent()) {
      double dx = goalPos.get().getX() - start.x;
      double dz = goalPos.get().getZ() - start.z;
      int renderDistance = Minecraft.getInstance().options.renderDistance().get();
      // With a huge loaded-truth radius, let exact A* own the whole visible problem instead of pricing an artificial frontier.
      if (Math.hypot(dx, dz) <= renderDistance * 16D) {
        return Optional.empty();
      }
    }
    return FarfieldSnapshot.build(context, start, goal).map(snapshot -> new FarfieldObjective(goal, snapshot, start));
  }
}
