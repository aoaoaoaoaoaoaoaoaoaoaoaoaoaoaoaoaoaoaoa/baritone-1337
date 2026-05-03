package baritone.api.process;

import baritone.api.pathing.goals.Goal;
import net.minecraft.core.BlockPos;

public interface IElytraProcess extends IBaritoneProcess {

  void repackChunks();

  /**
   * @return Where it is currently flying to, null if not active
   */
  BlockPos currentDestination();

  void pathTo(BlockPos destination);

  void pathTo(BlockPos destination, ElytraLaunchMode launchMode);

  void pathTo(BlockPos destination, ElytraLaunchMode launchMode, Goal continuationGoal);

  void pathTo(Goal destination);

  /**
   * Resets the state of the process but will maintain the same destination and will try to keep flying
   */
  void resetState();

  /**
   * @return {@code true} if an elytra backend is available
   */
  boolean isLoaded();

  /*
   * FOR INTERNAL USE ONLY. MAY BE REMOVED AT ANY TIME.
   */
  boolean isSafeToCancel();
}
