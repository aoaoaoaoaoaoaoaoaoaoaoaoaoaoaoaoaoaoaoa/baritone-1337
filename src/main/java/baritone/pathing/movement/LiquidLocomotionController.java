package baritone.pathing.movement;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.control.AngularGovernor;
import baritone.control.ScalarGovernor;
import baritone.control.ScalarGovernorSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

public final class LiquidLocomotionController implements MovementHelper {
  private static final int SWIM_ENTER_TICKS = 4;
  private static final int PROJECTED_WATER_ADVANCE_LIMIT = 2;
  private static final int MIN_FAST_SWIM_RUNWAY = 3;
  private static final double LOOKAHEAD_BLOCKS = 8D;
  private static final double LOOKAHEAD_MIN_DISTANCE_SQ = 6.25D;
  private static final double SWIM_PROGRESS = 0.98D;
  private static final double SWIM_END_DISTANCE_SQ = 0.36D;
  private static final double SWIM_LATERAL_TOLERANCE_SQ = 1.44D;
  private static final double SWIM_VERTICAL_RECENTER_DISTANCE_SQ = 0.49D;
  private static final double SWIM_VERTICAL_LOOKAHEAD_DISTANCE_SQ = 0.25D;
  private static final double SURFACE_GLIDE_LOW_CLEARANCE = 0.000D;
  private static final double SURFACE_GLIDE_BUMP_CLEARANCE = 0.018D;
  private static final double SURFACE_DROWN_BUMP_CLEARANCE = 0.034D;
  private static final double SURFACE_BUMP_FLOOR = -0.250D;
  private static final double SURFACE_BUMP_CEILING = 0.040D;
  private static final double SURFACE_BUMP_MAX_UPWARD_VELOCITY = -0.002D;
  private static final double SURFACE_NEAR_AIR_RESERVE = 0.10D;
  private static final double SURFACE_DEEP_AIR_RESERVE = 0.30D;
  private static final int SURFACE_GLIDE_BUMP_INTERVAL_TICKS = 8;
  private static final int SURFACE_DROWN_BUMP_INTERVAL_TICKS = 4;
  private static final float SURFACE_ASCENT_PITCH = -4F;
  private static final double SHORE_EXIT_AIM_OVERSHOOT = 0.45D;
  private static final double SHORE_EXIT_JUMP_DISTANCE_SQ = 2.56D;
  private static final double SHORE_EXIT_JUMP_Y_MARGIN = 0.08D;
  private static final ScalarGovernorSpec SWIM_YAW_GOVERNOR = new ScalarGovernorSpec(1.25F, 0.32F, 3.0F, 0.18F, 0.42F, 1.2F);
  private static final ScalarGovernorSpec SWIM_PITCH_GOVERNOR = new ScalarGovernorSpec(0.15F, 0.15F, 1F, 0.15F, 0.12F, 0.85F);

  private final IPlayerContext ctx;
  private final AngularGovernor yaw = new AngularGovernor(SWIM_YAW_GOVERNOR);
  private final ScalarGovernor pitch = new ScalarGovernor(SWIM_PITCH_GOVERNOR);
  private Mode mode = Mode.DRY;
  private int swimTicks;
  private int surfaceBumpCooldown;

  public LiquidLocomotionController(IPlayerContext ctx) {
    this.ctx = ctx;
  }

  public void copyFrom(LiquidLocomotionController source) {
    this.mode = source.mode;
    this.swimTicks = source.swimTicks;
    this.surfaceBumpCooldown = source.surfaceBumpCooldown;
    this.yaw.copyFrom(source.yaw);
    this.pitch.copyFrom(source.pitch);
  }

  public void apply(Movement movement, MovementState state, IPath path, int pathPosition) {
    BlockPos feet = ctx.playerFeet();
    if (!MovementHelper.isLiquid(ctx, feet)) {
      mode = Mode.DRY;
      swimTicks = 0;
      surfaceBumpCooldown = 0;
      resetGovernors();
      return;
    }
    if (!MovementHelper.isWater(ctx, feet)) {
      mode = Mode.WADE;
      swimTicks = 0;
      surfaceBumpCooldown = 0;
      resetGovernors();
      if (movement.getDest().y > movement.getSrc().y && ctx.player().position().y < movement.getDest().y + 0.6) {
        state.setInput(Input.JUMP, true);
      }
      return;
    }
    boolean deepWater = MovementHelper.isDeepWater(ctx, feet);
    boolean verticalRise = verticalRise(movement);
    boolean waterEntry = mode == Mode.DRY || mode == Mode.WATER_EXIT;
    if (shoreExitCandidate(movement)) {
      shoreExit(movement, state);
      return;
    }
    if (!deepWater) {
      if (waterEntry) {
        yaw.reset(waterEntryYaw(movement, path, pathPosition));
      }
      wade(movement, state, path, pathPosition, false);
      return;
    }
    if (!verticalRise && !hasFastSwimRunway(path, pathPosition, movement)) {
      wade(movement, state, path, pathPosition, true);
      return;
    }
    if (verticalRise) {
      verticalSwim(movement, state, path, pathPosition, waterEntry);
      return;
    }
    swim(movement, state, path, pathPosition, feet, waterEntry);
  }

  public int projectedWaterPosition(IPath path, int pathPosition) {
    if (!swimmingNow()) {
      return pathPosition;
    }
    int advanced = pathPosition;
    int max = Math.min(path.movements().size(), pathPosition + PROJECTED_WATER_ADVANCE_LIMIT);
    for (int i = pathPosition; i < max; i++) {
      Movement movement = (Movement) path.movements().get(i);
      if (!projectableWaterMovement(movement)) {
        break;
      }
      if (!passed(movement)) {
        break;
      }
      advanced = i + 1;
    }
    return advanced;
  }

  private void wade(Movement movement, MovementState state, IPath path, int pathPosition, boolean bob) {
    mode = Mode.WADE;
    swimTicks = 0;
    state.setInput(Input.SPRINT, false);
    state.setInput(Input.SNEAK, false);
    state.setInput(Input.JUMP, bob ? ctx.player().isEyeInFluid(FluidTags.WATER) : movement.getDest().y > movement.getSrc().y && ctx.player().position().y < movement.getDest().y + 0.6);
    if (state.getInputStates().getOrDefault(Input.MOVE_FORWARD, false)) {
      state.setTarget(new MovementState.MovementTarget(new Rotation(governYaw(yawToPath(movement, path, pathPosition)), 0F), true));
    }
  }

  private void shoreExit(Movement movement, MovementState state) {
    switchMode(Mode.WATER_EXIT);
    Vec3 target = shoreExitTarget(movement);
    double distanceSq = horizontalDistanceSq(ctx.player().position(), target);
    boolean jump = ctx.player().horizontalCollision || distanceSq <= SHORE_EXIT_JUMP_DISTANCE_SQ || ctx.player().isEyeInFluid(FluidTags.WATER);
    jump &= ctx.player().position().y < movement.getDest().y - SHORE_EXIT_JUMP_Y_MARGIN;
    state.setInput(Input.MOVE_FORWARD, true);
    state.setInput(Input.SPRINT, Baritone.settings().sprintInWater.value);
    state.setInput(Input.SNEAK, false);
    state.setInput(Input.JUMP, jump);
    state.setTarget(new MovementState.MovementTarget(new Rotation(governYaw(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target, ctx.playerRotations()).getYaw()), 0F), true));
  }

  private void verticalSwim(Movement movement, MovementState state, IPath path, int pathPosition, boolean waterEntry) {
    swimTicks++;
    double clearance = ctx.player().getEyeY() - waterSurfaceY(ctx.playerFeet());
    if (mode == Mode.DRY || mode == Mode.WADE) {
      switchMode(Mode.SWIM_ENTER);
      if (waterEntry) {
        yaw.reset(waterEntryYaw(movement, path, pathPosition));
      }
    } else if (mode == Mode.SWIM_ENTER && swimTicks >= SWIM_ENTER_TICKS) {
      switchMode(Mode.SWIM_CRUISE);
    }
    state.setInput(Input.SNEAK, false);
    state.setInput(Input.JUMP, clearance < SURFACE_BUMP_FLOOR);
  }

  private void swim(Movement movement, MovementState state, IPath path, int pathPosition, BlockPos feet, boolean waterEntry) {
    swimTicks++;
    if (surfaceBumpCooldown > 0) {
      surfaceBumpCooldown--;
    }
    boolean forward = state.getInputStates().getOrDefault(Input.MOVE_FORWARD, false);
    double clearance = ctx.player().getEyeY() - waterSurfaceY(feet);
    boolean locomote = forward;
    int maxAir = ctx.player().getMaxAirSupply();
    int air = ctx.player().getAirSupply();

    if (mode == Mode.DRY || mode == Mode.WADE) {
      switchMode(Mode.SWIM_ENTER);
      if (waterEntry) {
        yaw.reset(waterEntryYaw(movement, path, pathPosition));
      }
    } else if (mode == Mode.SWIM_ENTER && swimTicks >= SWIM_ENTER_TICKS) {
      switchMode(Mode.SWIM_CRUISE);
    }

    SurfaceAction surface = surfaceAction(clearance, air, maxAir);
    state.setInput(Input.JUMP, surface.jump());
    if (locomote && Baritone.settings().sprintInWater.value) {
      state.setInput(Input.SPRINT, true);
    }
    state.setInput(Input.SNEAK, false);
    if (locomote && state.getStatus() == MovementStatus.RUNNING) {
      state.setTarget(new MovementState.MovementTarget(new Rotation(governYaw(yawToPath(movement, path, pathPosition)), surfacePitch(clearance)), true));
    }
  }

  private SurfaceAction surfaceAction(double clearance, int air, int maxAir) {
    boolean eyeWet = ctx.player().isEyeInFluid(FluidTags.WATER);
    boolean swimming = ctx.player().isSwimming();
    if (mode == Mode.SWIM_ENTER) {
      return SurfaceAction.NONE;
    }
    if (mode == Mode.SWIM_CRUISE) {
      switchMode(eyeWet || clearance < SURFACE_GLIDE_LOW_CLEARANCE ? Mode.SWIM_SURFACE_DROWNING : Mode.SWIM_SURFACE_GLIDING);
    }
    boolean nearSurface = clearance >= SURFACE_BUMP_FLOOR;
    boolean urgent = air <= oxygenReserve(maxAir, nearSurface);
    if (eyeWet || clearance < SURFACE_GLIDE_LOW_CLEARANCE || urgent) {
      switchMode(Mode.SWIM_SURFACE_DROWNING);
    } else {
      switchMode(Mode.SWIM_SURFACE_GLIDING);
    }
    double bumpClearance = mode == Mode.SWIM_SURFACE_DROWNING ? SURFACE_DROWN_BUMP_CLEARANCE : SURFACE_GLIDE_BUMP_CLEARANCE;
    boolean canBump = swimming || eyeWet && urgent;
    boolean drowningGlide = swimming && eyeWet && urgent;
    boolean breathingBandBump = (urgent || clearance < bumpClearance) && clearance < SURFACE_BUMP_CEILING;
    boolean jump = canBump && nearSurface && (drowningGlide || breathingBandBump) && ctx.player().getDeltaMovement().y <= SURFACE_BUMP_MAX_UPWARD_VELOCITY && surfaceBumpCooldown == 0;
    if (jump) {
      surfaceBumpCooldown = urgent ? SURFACE_DROWN_BUMP_INTERVAL_TICKS : SURFACE_GLIDE_BUMP_INTERVAL_TICKS;
    }
    return new SurfaceAction(jump);
  }

  private static int oxygenReserve(int maxAir, boolean nearSurface) {
    return (int) Math.ceil(Math.max(1, maxAir) * (nearSurface ? SURFACE_NEAR_AIR_RESERVE : SURFACE_DEEP_AIR_RESERVE));
  }

  private float surfacePitch(double clearance) {
    boolean swimming = ctx.player().isSwimming();
    boolean eyeWet = ctx.player().isEyeInFluid(FluidTags.WATER);
    return governPitch(swimming && (eyeWet || clearance < SURFACE_BUMP_CEILING) ? SURFACE_ASCENT_PITCH : 0F);
  }

  private float governPitch(float target) {
    return (float) pitch.govern(target);
  }

  private float governYaw(float target) {
    return (float) yaw.govern(target);
  }

  private void resetGovernors() {
    yaw.reset(ctx.playerRotations().getYaw());
    pitch.reset(ctx.playerRotations().getPitch());
  }

  private void switchMode(Mode next) {
    if (mode == next) {
      return;
    }
    mode = next;
    swimTicks = 0;
    if (!next.isSurface()) {
      surfaceBumpCooldown = 0;
    }
  }

  private boolean shoreExitCandidate(Movement movement) {
    if (movement.getDest().y <= movement.getSrc().y || movement.getDest().x == movement.getSrc().x && movement.getDest().z == movement.getSrc().z) {
      return false;
    }
    BlockPos dest = movement.getDest();
    return !MovementHelper.isWater(ctx, dest) && !MovementHelper.isWater(ctx, dest.above()) && MovementHelper.canWalkOn(ctx, dest.below()) && MovementHelper.canMoveThrough(ctx, dest)
      && MovementHelper.canMoveThrough(ctx, dest.above());
  }

  private Vec3 shoreExitTarget(Movement movement) {
    Vec3 center = VecUtils.getBlockPosCenter(movement.getDest());
    double dx = movement.getDest().x - movement.getSrc().x;
    double dz = movement.getDest().z - movement.getSrc().z;
    double len = Math.hypot(dx, dz);
    if (len < 1.0E-6D) {
      return center;
    }
    return center.add(dx / len * SHORE_EXIT_AIM_OVERSHOOT, 0D, dz / len * SHORE_EXIT_AIM_OVERSHOOT);
  }

  private boolean projectableWaterMovement(Movement movement) {
    return MovementHelper.isWater(ctx, movement.getDest());
  }

  private float yawToPath(Movement movement, IPath path, int pathPosition) {
    Vec3 dest = VecUtils.getBlockPosCenter(movement.getDest());
    if (verticalRise(movement)) {
      if (horizontalDistanceSq(ctx.player().position(), dest) > SWIM_VERTICAL_RECENTER_DISTANCE_SQ) {
        return RotationUtils.calcRotationFromVec3d(ctx.playerHead(), dest, ctx.playerRotations()).getYaw();
      }
      Vec3 target = lookahead(path, pathPosition);
      if (target == null || horizontalDistanceSq(target, dest) <= SWIM_VERTICAL_LOOKAHEAD_DISTANCE_SQ) {
        return ctx.playerRotations().getYaw();
      }
      return RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target, ctx.playerRotations()).getYaw();
    }
    return RotationUtils.calcRotationFromVec3d(ctx.playerHead(), straightRunTarget(movement, path, pathPosition), ctx.playerRotations()).getYaw();
  }

  private float waterEntryYaw(Movement movement, IPath path, int pathPosition) {
    Vec3 target = lookahead(path, pathPosition);
    if (target == null || horizontalDistanceSq(ctx.player().position(), target) <= SWIM_END_DISTANCE_SQ) {
      target = straightRunTarget(movement, path, pathPosition);
    }
    return RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target, ctx.playerRotations()).getYaw();
  }

  private Vec3 straightRunTarget(Movement movement, IPath path, int pathPosition) {
    if (path == null || pathPosition < 0) {
      return VecUtils.getBlockPosCenter(movement.getDest());
    }
    int runDx = movement.getDest().x - movement.getSrc().x;
    int runDz = movement.getDest().z - movement.getSrc().z;
    if (runDx == 0 && runDz == 0) {
      return VecUtils.getBlockPosCenter(movement.getDest());
    }
    BetterBlockPos target = movement.getDest();
    double walked = Math.hypot(runDx, runDz);
    int max = Math.min(path.movements().size(), pathPosition + 10);
    for (int i = pathPosition + 1; i < max && walked < LOOKAHEAD_BLOCKS; i++) {
      Movement next = (Movement) path.movements().get(i);
      int dx = next.getDest().x - next.getSrc().x;
      int dz = next.getDest().z - next.getSrc().z;
      if (dx == 0 && dz == 0 || dx * runDz != dz * runDx || dx * runDx + dz * runDz <= 0) {
        break;
      }
      target = next.getDest();
      walked += Math.hypot(dx, dz);
    }
    return VecUtils.getBlockPosCenter(target);
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
      return surfaceRiseSatisfied(movement);
    }
    double px = player.x - start.x;
    double pz = player.z - start.z;
    double progress = (px * dx + pz * dz) / lenSq;
    if (progress < SWIM_PROGRESS && horizontalDistanceSq(player, end) > SWIM_END_DISTANCE_SQ) {
      return false;
    }
    double closestX = start.x + dx * progress;
    double closestZ = start.z + dz * progress;
    return horizontalDistanceSq(player, new Vec3(closestX, player.y, closestZ)) <= SWIM_LATERAL_TOLERANCE_SQ;
  }

  private static boolean verticalRise(Movement movement) {
    return movement.getDest().x == movement.getSrc().x && movement.getDest().z == movement.getSrc().z && movement.getDest().y > movement.getSrc().y;
  }

  private boolean hasFastSwimRunway(IPath path, int pathPosition, Movement movement) {
    int count = 0;
    BlockPos last = null;
    if (MovementHelper.surfaceSwimEnvelopeCell(ctx, ctx.playerFeet())) {
      count++;
      last = ctx.playerFeet();
    }
    if (path != null && pathPosition >= 0) {
      int max = Math.min(path.positions().size(), pathPosition + MIN_FAST_SWIM_RUNWAY + 3);
      for (int i = pathPosition; i < max && count < MIN_FAST_SWIM_RUNWAY; i++) {
        BetterBlockPos pos = path.positions().get(i);
        if (last != null && pos.getX() == last.getX() && pos.getZ() == last.getZ()) {
          continue;
        }
        if (!MovementHelper.surfaceSwimEnvelopeCell(ctx, pos)) {
          if (count > 0) {
            break;
          }
          continue;
        }
        count++;
        last = pos;
      }
    }
    if (count < MIN_FAST_SWIM_RUNWAY && MovementHelper.surfaceSwimEnvelopeCell(ctx, movement.getDest())
      && (last == null || movement.getDest().x != last.getX() || movement.getDest().z != last.getZ())) {
      count++;
    }
    return count >= MIN_FAST_SWIM_RUNWAY;
  }

  private boolean surfaceRiseSatisfied(Movement movement) {
    if (movement.getDest().x != movement.getSrc().x || movement.getDest().z != movement.getSrc().z || movement.getDest().y <= movement.getSrc().y) {
      return false;
    }
    if (!MovementHelper.isWater(ctx, movement.getSrc()) || !MovementHelper.isWater(ctx, movement.getDest())) {
      return false;
    }
    return ctx.playerFeet().getY() >= movement.getDest().y;
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

  private record SurfaceAction(boolean jump) {
    private static final SurfaceAction NONE = new SurfaceAction(false);
  }

  private enum Mode {
    DRY, WADE, WATER_EXIT, SWIM_ENTER, SWIM_CRUISE, SWIM_SURFACE_DROWNING, SWIM_SURFACE_GLIDING;

    boolean isSwimming() {
      return switch (this) {
        case SWIM_ENTER, SWIM_CRUISE, SWIM_SURFACE_DROWNING, SWIM_SURFACE_GLIDING -> true;
        case DRY, WADE, WATER_EXIT -> false;
      };
    }

    boolean isSurface() {
      return switch (this) {
        case SWIM_SURFACE_DROWNING, SWIM_SURFACE_GLIDING -> true;
        case DRY, WADE, WATER_EXIT, SWIM_ENTER, SWIM_CRUISE -> false;
      };
    }
  }
}
