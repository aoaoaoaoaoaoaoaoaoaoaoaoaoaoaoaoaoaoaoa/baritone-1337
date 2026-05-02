package baritone.process;

import baritone.Baritone;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

final class InteractionPlan {
  private InteractionPlan() {}

  static Optional<BlockClick> reachable(IPlayerContext ctx, BlockPos pos, Click click) {
    return RotationUtils.reachable(ctx, pos).map(rotation -> new BlockClick(pos, rotation, click));
  }

  static Optional<BlockClick> reachable(IPlayerContext ctx, BlockPos pos, double reach, Click click) {
    return RotationUtils.reachable(ctx, pos, reach).map(rotation -> new BlockClick(pos, rotation, click));
  }

  static Optional<BlockClick> reachableOffset(IPlayerContext ctx, BlockPos pos, Vec3 target, double reach, boolean wouldSneak, Click click) {
    return RotationUtils.reachableOffset(ctx, pos, target, reach, wouldSneak).map(rotation -> new BlockClick(pos, rotation, click));
  }

  enum Click {
    LEFT(Input.CLICK_LEFT),
    RIGHT(Input.CLICK_RIGHT);

    private final Input input;

    Click(Input input) {
      this.input = input;
    }
  }

  record BlockClick(BlockPos pos, Rotation rotation, Click click) {
    PathingCommand pause(Baritone baritone, Runnable prepare, BooleanSupplier ready) {
      baritone.getLookBehavior().updateTarget(rotation, true);
      prepare.run();
      if (ready.getAsBoolean()) {
        baritone.getInputOverrideHandler().setInputForceState(click.input, true);
      }
      return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }
  }
}
