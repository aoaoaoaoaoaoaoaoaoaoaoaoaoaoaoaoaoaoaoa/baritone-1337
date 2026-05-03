package baritone.pathing.movement;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

public final class LiquidLocomotionController implements MovementHelper {
  private static final int SURFACE_AIR_ENTER = 90;
  private static final int SURFACE_AIR_EXIT = 295;
  private static final int SWIM_ENTER_TICKS = 4;
  private static final double LOOKAHEAD_BLOCKS = 5D;
  private static final double LOOKAHEAD_MIN_DISTANCE_SQ = 2.25D;
  private static final double SWIM_PROGRESS = 0.78D;
  private static final double SWIM_LATERAL_TOLERANCE_SQ = 1.44D;
  private static final double BREATH_CLEARANCE = 0.012D;
  private static final double BREATH_DEADBAND = 0.018D;
  private static final double BREATH_ASCEND_BAND = 0.70D;
  private static final double BREATH_DIVE_BAND = 0.38D;
  private static final float BREATH_ASCEND_MAX_PITCH = 12F;
  private static final float BREATH_DIVE_MAX_PITCH = 22F;
  private static final float BREATH_MIN_PITCH = 1.5F;
  private static final float SWIM_DIVE_PITCH = 26F;
  private static final float SWIM_ASCEND_PITCH = -18F;

  private final IPlayerContext ctx;
  private Mode mode = Mode.DRY;
  private int swimTicks;

  public LiquidLocomotionController(IPlayerContext ctx) {
    this.ctx = ctx;
  }

  public void copyFrom(LiquidLocomotionController source) {
    this.mode = source.mode;
    this.swimTicks = source.swimTicks;
  }

  public void apply(Movement movement, MovementState state, IPath path, int pathPosition) {
    BlockPos feet = ctx.playerFeet();
    if (!MovementHelper.isLiquid(ctx, feet)) {
      mode = Mode.DRY;
      swimTicks = 0;
      return;
    }
    if (!MovementHelper.isWater(ctx, feet)) {
      mode = Mode.WADE;
      swimTicks = 0;
      if (movement.getDest().y > movement.getSrc().y && ctx.player().position().y < movement.getDest().y + 0.6) {
        state.setInput(Input.JUMP, true);
      }
      return;
    }
    boolean deepWater = MovementHelper.isDeepWater(ctx, feet);
    if (!deepWater) {
      wade(movement, state);
      return;
    }
    swim(movement, state, path, pathPosition, feet);
  }

  public int projectedWaterPosition(IPath path, int pathPosition) {
    if (!swimmingNow()) {
      return pathPosition;
    }
    int advanced = pathPosition;
    int max = Math.min(path.movements().size(), pathPosition + 4);
    for (int i = pathPosition; i < max; i++) {
      if (!passed((Movement) path.movements().get(i))) {
        break;
      }
      advanced = i + 1;
    }
    return advanced;
  }

  private void wade(Movement movement, MovementState state) {
    mode = Mode.WADE;
    swimTicks = 0;
    state.setInput(Input.SPRINT, false);
    state.setInput(Input.SNEAK, false);
    state.setInput(Input.JUMP, movement.getDest().y > movement.getSrc().y && ctx.player().position().y < movement.getDest().y + 0.6);
    if (state.getInputStates().getOrDefault(Input.MOVE_FORWARD, false) && !state.getTarget().hasToForceRotations()) {
      Rotation rotation = state.getTarget().getRotation().orElseGet(() -> ctx.playerRotations());
      state.setTarget(new MovementState.MovementTarget(new Rotation(rotation.getYaw(), 0F), false));
    }
  }

  private void swim(Movement movement, MovementState state, IPath path, int pathPosition, BlockPos feet) {
    swimTicks++;
    boolean forward = state.getInputStates().getOrDefault(Input.MOVE_FORWARD, false);
    boolean descending = movement.getDest().y < movement.getSrc().y;
    int maxAir = ctx.player().getMaxAirSupply();
    int air = ctx.player().getAirSupply();

    if (mode == Mode.DRY || mode == Mode.WADE) {
      mode = Mode.SWIM_ENTER;
    } else if (mode == Mode.SWIM_ENTER && swimTicks >= SWIM_ENTER_TICKS) {
      mode = Mode.SWIM_CRUISE;
    }
    if (mode == Mode.SWIM_SURFACE_BREATHE) {
      if (air >= Math.min(maxAir - 1, SURFACE_AIR_EXIT) && !ctx.player().isEyeInFluid(FluidTags.WATER)) {
        mode = Mode.SWIM_CRUISE;
      }
    } else if (air <= SURFACE_AIR_ENTER) {
      mode = Mode.SWIM_SURFACE_BREATHE;
    }

    state.setInput(Input.JUMP, false);
    if (forward && Baritone.settings().sprintInWater.value) {
      state.setInput(Input.SPRINT, true);
    }
    if (descending) {
      state.setInput(Input.SNEAK, true);
    } else {
      state.setInput(Input.SNEAK, false);
    }
    if (forward && !state.getTarget().hasToForceRotations()) {
      state.setTarget(new MovementState.MovementTarget(new Rotation(yawToPath(movement, path, pathPosition), pitch(movement, feet, descending)), false));
    }
  }

  private float pitch(Movement movement, BlockPos feet, boolean descending) {
    if (descending) {
      return SWIM_DIVE_PITCH;
    }
    if (movement.getDest().y > movement.getSrc().y) {
      return SWIM_ASCEND_PITCH;
    }
    double clearance = ctx.player().getEyeY() - waterSurfaceY(feet);
    if (mode == Mode.SWIM_SURFACE_BREATHE) {
      return breathPitch(clearance);
    }
    if (clearance > 0D) {
      return (float) Math.min(BREATH_DIVE_MAX_PITCH, BREATH_DIVE_MAX_PITCH * Math.tanh(clearance / BREATH_DIVE_BAND));
    }
    return 0F;
  }

  private float breathPitch(double clearance) {
    double error = BREATH_CLEARANCE - clearance;
    if (Math.abs(error) <= BREATH_DEADBAND) {
      return 0F;
    }
    if (error > 0D) {
      return -Math.max(BREATH_MIN_PITCH, (float) (BREATH_ASCEND_MAX_PITCH * Math.tanh(error / BREATH_ASCEND_BAND)));
    }
    return (float) Math.min(BREATH_DIVE_MAX_PITCH, BREATH_DIVE_MAX_PITCH * Math.tanh((-error - BREATH_DEADBAND) / BREATH_DIVE_BAND));
  }

  private float yawToPath(Movement movement, IPath path, int pathPosition) {
    Vec3 target = lookahead(path, pathPosition);
    if (target == null) {
      target = VecUtils.getBlockPosCenter(movement.getDest());
    }
    return RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target, ctx.playerRotations()).getYaw();
  }

  private Vec3 lookahead(IPath path, int pathPosition) {
    if (path == null || pathPosition < 0) {
      return null;
    }
    Vec3 player = ctx.player().position();
    Vec3 fallback = null;
    double walked = 0D;
    BetterBlockPos prev = path.positions().get(Math.min(pathPosition, path.positions().size() - 1));
    int max = Math.min(path.positions().size() - 1, pathPosition + 8);
    for (int i = pathPosition + 1; i <= max; i++) {
      BetterBlockPos pos = path.positions().get(i);
      Vec3 center = VecUtils.getBlockPosCenter(pos);
      fallback = center;
      if (horizontalDistanceSq(player, center) >= LOOKAHEAD_MIN_DISTANCE_SQ && walked >= LOOKAHEAD_BLOCKS) {
        return center;
      }
      walked += Math.hypot(pos.x - prev.x, pos.z - prev.z);
      prev = pos;
    }
    return fallback;
  }

  private boolean passed(Movement movement) {
    Vec3 player = ctx.player().position();
    Vec3 start = VecUtils.getBlockPosCenter(movement.getSrc());
    Vec3 end = VecUtils.getBlockPosCenter(movement.getDest());
    double dx = end.x - start.x;
    double dz = end.z - start.z;
    double lenSq = dx * dx + dz * dz;
    if (lenSq < 1.0E-6D) {
      return false;
    }
    double px = player.x - start.x;
    double pz = player.z - start.z;
    double progress = (px * dx + pz * dz) / lenSq;
    if (progress < SWIM_PROGRESS && horizontalDistanceSq(player, end) > 0.49D) {
      return false;
    }
    double closestX = start.x + dx * progress;
    double closestZ = start.z + dz * progress;
    return horizontalDistanceSq(player, new Vec3(closestX, player.y, closestZ)) <= SWIM_LATERAL_TOLERANCE_SQ;
  }

  private boolean swimmingNow() {
    BlockPos feet = ctx.playerFeet();
    return MovementHelper.isWater(ctx, feet) && MovementHelper.isDeepWater(ctx, feet) && mode.isSwimming();
  }

  private double waterSurfaceY(BlockPos feet) {
    BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos(feet.getX(), feet.getY(), feet.getZ());
    int maxY = ctx.world().getMaxY();
    while (scan.getY() < maxY && MovementHelper.isWater(ctx, scan)) {
      scan.move(Direction.UP);
    }
    return scan.getY();
  }

  private static double horizontalDistanceSq(Vec3 a, Vec3 b) {
    double dx = a.x - b.x;
    double dz = a.z - b.z;
    return dx * dx + dz * dz;
  }

  private enum Mode {
    DRY, WADE, SWIM_ENTER, SWIM_CRUISE, SWIM_SURFACE_BREATHE;

    boolean isSwimming() {
      return switch (this) {
        case SWIM_ENTER, SWIM_CRUISE, SWIM_SURFACE_BREATHE -> true;
        case DRY, WADE -> false;
      };
    }
  }
}
