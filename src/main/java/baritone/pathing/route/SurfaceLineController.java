package baritone.pathing.route;

import baritone.pathing.movement.MovementClientHelper;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.control.ControlFrame;
import baritone.pathing.control.ControlFrame.VehicleCommand;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.water.WaterLineSegment;
import baritone.pathing.transport.TransportMode;
import baritone.pathing.transport.TransportSnapshot;
import java.util.Comparator;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class SurfaceLineController implements MovementHelper {
  private static final double SUCCESS_PROGRESS = 0.985D;
  private static final double SUCCESS_DISTANCE_SQ = 1.44D;
  private static final double LINE_TOLERANCE_SQ = 2.56D;
  private static final double SURFACE_APPROACH_TOLERANCE_SQ = 256D;
  private static final double SURFACE_ACQUIRE_DONE_DISTANCE_SQ = 2.25D;
  private static final double SWIM_TERMINAL_EXIT_LOOKAHEAD_BLOCKS = 4D;
  private static final double SWIM_TERMINAL_EXIT_PROGRESS_FLOOR = 0.82D;
  private static final double SWIM_TERMINAL_EXIT_DEST_DISTANCE_SQ = 16D;
  private static final double BOAT_DISEMBARK_LOOKAHEAD_BLOCKS = 4D;
  private static final double BOAT_DISEMBARK_PROGRESS_FLOOR = 0.86D;
  private static final double BOAT_DISEMBARK_DEST_DISTANCE_SQ = 16D;
  private static final double BOAT_TRANSIT_DEST_DISTANCE_SQ = 9D;
  private static final int BOAT_BRAKE_TICKS = 3;
  private static final int BOAT_FORCE_DISMOUNT_TICKS = 5;
  private static final int BOAT_PICKUP_TIMEOUT_TICKS = 100;
  private static final double BOAT_PICKUP_APPROACH_DISTANCE_SQ = 6.25D;
  private static final double BOAT_ITEM_PICKUP_DISTANCE_SQ = 1.21D;
  private static final double BOAT_SEARCH_RADIUS = 5D;
  private static final double BOAT_LAUNCH_CENTER_DISTANCE_SQ = 0.49D;
  private static final double SWIM_DRIFT_TOLERANCE_SQ = 9D;
  private static final double SWIM_STRAFE_ENGAGE = 0.35D;
  private static final double SWIM_STRAFE_RELEASE = 0.15D;
  private static final double BOAT_DRIFT_TOLERANCE_SQ = 256D;
  private static final double BOAT_TURN_ENGAGE = 4D;
  private static final double BOAT_TURN_RELEASE = 1.25D;
  private static final double BOAT_HARD_TURN = 70D;
  private static final float BOAT_PLACE_ROTATION_TOLERANCE = 5F;
  private static final int BOAT_PLACE_COOLDOWN_TICKS = 4;
  private static final int BOAT_ATTACK_COOLDOWN_TICKS = 4;
  private static final double SURFACE_DIVE_CLEARANCE = -0.20D;
  private static final double SURFACE_GLIDE_TARGET_CLEARANCE = 0.115D;
  private static final double SURFACE_FAST_GLIDE_MIN_CLEARANCE = 0.020D;
  private static final double SURFACE_DROWN_BUMP_CLEARANCE = -0.120D;
  private static final double SURFACE_BUMP_FLOOR = -0.250D;
  private static final double SURFACE_BUMP_CEILING = 0.040D;
  private static final double SURFACE_BUMP_MAX_UPWARD_VELOCITY = 0.020D;
  private static final double SURFACE_GLIDE_HIGH_CLEARANCE = 0.160D;
  private static final double SURFACE_GLIDE_DAMP_UPWARD_VELOCITY = 0.055D;
  private static final double SURFACE_TRIM_BUMP_CLEARANCE = 0.035D;
  private static final double SURFACE_TRIM_BUMP_MAX_UPWARD_VELOCITY = 0.006D;
  private static final double SURFACE_GLIDE_KICK_FLOOR = 0.100D;
  private static final double SURFACE_GLIDE_KICK_CEILING = 0.170D;
  private static final double SURFACE_GLIDE_KICK_MAX_UPWARD_VELOCITY = 0.004D;
  private static final double SURFACE_CLIMB_PULSE_CLEARANCE = -0.180D;
  private static final double SURFACE_CLIMB_PULSE_MAX_UPWARD_VELOCITY = 0.045D;
  private static final double SURFACE_NEAR_AIR_RESERVE = 0.25D;
  private static final double SURFACE_DEEP_AIR_RESERVE = 0.45D;
  private static final int SURFACE_DROWN_BUMP_INTERVAL_TICKS = 4;
  private static final int SURFACE_TRIM_BUMP_INTERVAL_TICKS = 6;
  private static final int SURFACE_GLIDE_KICK_INTERVAL_TICKS = 12;
  private static final int SURFACE_ACQUIRE_BUMP_INTERVAL_TICKS = 3;
  private static final double SURFACE_GLIDE_CLEARANCE_GAIN = 190D;
  private static final double SURFACE_GLIDE_VELOCITY_GAIN = 70D;
  private static final float SURFACE_ASCENT_PITCH = -45F;
  private static final float SURFACE_GLIDE_DAMP_PITCH = 12F;
  private static final float SURFACE_DIVE_PITCH = 35F;

  private final IBaritone baritone;
  private final IPlayerContext ctx;
  private final WaterLineSegment segment;
  private SurfaceLinePhase phase;
  private int boatPlaceCooldown;
  private int boatAttackCooldown;
  private int boatDismountTicks;
  private int boatPickupTicks;
  private int boatSteer;
  private int swimStrafe;
  private int surfaceDiveTicks;
  private int surfaceBumpCooldown;
  private Vec3 lastBoatPickupTarget;
  private boolean clearingBoatForSwim;
  private SurfaceSwimEntry surfaceSwimEntry = SurfaceSwimEntry.DIVE;
  private float surfacePitch;

  public SurfaceLineController(IBaritone baritone, WaterLineSegment segment) {
    this.baritone = baritone;
    this.ctx = baritone.getPlayerContext();
    this.segment = segment;
    this.phase = segment.boat() ? SurfaceLinePhase.PLACE : SurfaceLinePhase.SWIM;
  }

  public WaterLineSegment segment() {
    return segment;
  }

  public SurfaceLinePhase phase() {
    return phase;
  }

  public LegTickResult<SurfaceLinePhase> tick() {
    ControlFrame.Builder frame = ControlFrame.builder().setStatus(MovementStatus.RUNNING);
    if (segment.boat()) {
      updateBoat(frame);
    } else if (clearingBoatForSwim || controlledBoat().isPresent()) {
      clearBoatBeforeSwim(frame);
    } else if (phase == SurfaceLinePhase.FINISH) {
      finishAfterSwim(frame);
    } else {
      updateSwim(frame);
    }
    MovementStatus status = frame.getStatus();
    return LegTickResult.of(status, frame.build(), status != MovementStatus.RUNNING, progress(), phase);
  }

  public boolean acceptsPathingDrift(BlockPos pos) {
    return segment.validPositions().contains(new BetterBlockPos(pos))
      || progress() >= -0.25D && progress() <= 1.25D && offLineDistanceSq() <= (segment.boat() || clearingBoatForSwim ? BOAT_DRIFT_TOLERANCE_SQ : SWIM_DRIFT_TOLERANCE_SQ);
  }

  public TransportSnapshot.Plan transportPlan() {
    return TransportSnapshot.Plan.transport(commandMode(), getClass().getSimpleName(), segment.src(), segment.dest(), phase.name(), segment.terminal(), segment.waterStart(), progress());
  }

  private void updateSwim(ControlFrame.Builder state) {
    phase = SurfaceLinePhase.SWIM;
    if (segment.terminal() && swimTerminalExitPoint()) {
      phase = SurfaceLinePhase.FINISH;
      finishAfterSwim(state);
      return;
    }
    if (lineComplete(SUCCESS_PROGRESS, SUCCESS_DISTANCE_SQ)) {
      if (segment.terminal() && !swimTerminalComplete()) {
        phase = SurfaceLinePhase.FINISH;
        finishAfterSwim(state);
        return;
      }
      state.setStatus(MovementStatus.SUCCESS);
      return;
    }
    if (surfaceAcquisitionNeeded()) {
      acquireSurfaceLine(state);
      return;
    }
    if (!acceptsPathingDrift(ctx.playerFeet()) && !surfaceApproachAllowed() && offLineDistanceSq() > LINE_TOLERANCE_SQ) {
      state.setStatus(MovementStatus.UNREACHABLE);
      return;
    }
    state.setInput(Input.MOVE_FORWARD, true);
    state.setInput(Input.SPRINT, Baritone.settings().sprintInWater.value);
    state.setInput(Input.SNEAK, false);
    surfaceSwim(state);
    swimTrackTrim(state);
    boolean wet = ctx.player().isInWater() || waterProbe().isPresent();
    state.setTarget(new ControlFrame.MovementTarget(new Rotation(yawToLineTarget(), wet ? surfacePitch : ctx.playerRotations().getPitch()), wet));
  }

  private void clearBoatBeforeSwim(ControlFrame.Builder state) {
    clearingBoatForSwim = true;
    Optional<AbstractBoat> controlled = controlledBoat();
    if (controlled.isPresent()) {
      phase = SurfaceLinePhase.DISMOUNT;
      dismountBoat(state, controlled.get());
      return;
    }
    Optional<AbstractBoat> boat = nearestBoat();
    if (boat.isPresent() && boatPickupTicks <= BOAT_PICKUP_TIMEOUT_TICKS) {
      pickupBoatForSwim(state, boat.get());
      return;
    }
    if (((Baritone) baritone).getInventoryBehavior().hasBoat()) {
      clearingBoatForSwim = false;
      boatPickupTicks = 0;
      lastBoatPickupTarget = null;
      phase = SurfaceLinePhase.SWIM;
      updateSwim(state);
      return;
    }
    Optional<ItemEntity> item = nearestBoatItem();
    if (item.isPresent()) {
      boatPickupTicks++;
      phase = SurfaceLinePhase.PICKUP;
      collectBoatItem(state, item.get().position());
      return;
    }
    if (lastBoatPickupTarget != null && boatPickupTicks <= BOAT_PICKUP_TIMEOUT_TICKS) {
      boatPickupTicks++;
      phase = SurfaceLinePhase.PICKUP;
      collectBoatItem(state, lastBoatPickupTarget);
      return;
    }
    state.setStatus(MovementStatus.UNREACHABLE);
  }

  private void pickupBoatForSwim(ControlFrame.Builder state, AbstractBoat target) {
    phase = SurfaceLinePhase.PICKUP;
    boatPickupTicks++;
    attackBoatForPickup(state, target);
  }

  private void finishAfterSwim(ControlFrame.Builder state) {
    BetterBlockPos dest = segment.dest();
    boolean playerWet = ctx.player().isInWater() || waterProbe().isPresent();
    if (!playerWet && terminalShoreReached(dest)) {
      state.setStatus(MovementStatus.SUCCESS);
      return;
    }
    MovementClientHelper.moveTowards(ctx, state, dest);
    state.setInput(Input.JUMP, playerWet || ctx.playerFeet().getY() < dest.y);
    state.setInput(Input.SNEAK, false);
    state.setInput(Input.SPRINT, Baritone.settings().sprintInWater.value && playerWet);
  }

  private boolean swimTerminalComplete() {
    BetterBlockPos dest = segment.dest();
    boolean playerWet = ctx.player().isInWater() || waterProbe().isPresent();
    return !playerWet && terminalShoreReached(dest);
  }

  private boolean terminalShoreReached(BetterBlockPos dest) {
    return ctx.playerFeet().equals(dest) || horizontalDistanceSq(ctx.player().position(), VecUtils.getBlockPosCenter(dest)) <= SUCCESS_DISTANCE_SQ
      || segment.validPositions().contains(new BetterBlockPos(ctx.playerFeet()));
  }

  private boolean surfaceApproachAllowed() {
    return !segment.boat() && progress() < 0.20D && horizontalDistanceSq(ctx.player().position(), VecUtils.getBlockPosCenter(segment.waterStart())) <= SURFACE_APPROACH_TOLERANCE_SQ
      && (ctx.player().isInWater() || waterProbe().isPresent());
  }

  private boolean surfaceAcquisitionNeeded() {
    if (segment.boat() || !surfaceApproachAllowed()) {
      return false;
    }
    double distanceToSurfaceStart = horizontalDistanceSq(ctx.player().position(), VecUtils.getBlockPosCenter(segment.waterStart()));
    return distanceToSurfaceStart > SURFACE_ACQUIRE_DONE_DISTANCE_SQ && offLineDistanceSq() > SWIM_DRIFT_TOLERANCE_SQ;
  }

  private void acquireSurfaceLine(ControlFrame.Builder state) {
    phase = SurfaceLinePhase.ACQUIRE;
    state.setInput(Input.MOVE_FORWARD, true);
    state.setInput(Input.SPRINT, Baritone.settings().sprintInWater.value);
    state.setInput(Input.SNEAK, false);
    surfaceSwim(state);
    boolean wet = ctx.player().isInWater() || waterProbe().isPresent();
    Rotation target = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(segment.waterStart()), ctx.playerRotations());
    state.setTarget(new ControlFrame.MovementTarget(new Rotation(target.getYaw(), wet ? surfacePitch : target.getPitch()), wet));
    swimStrafe = 0;
    state.setInput(Input.MOVE_LEFT, false);
    state.setInput(Input.MOVE_RIGHT, false);
  }

  private void surfaceSwim(ControlFrame.Builder state) {
    Optional<BlockPos> probe = waterProbe();
    surfacePitch = ctx.playerRotations().getPitch();
    if (!ctx.player().isInWater() && probe.isEmpty()) {
      surfaceDiveTicks = 0;
      surfaceBumpCooldown = 0;
      surfaceSwimEntry = SurfaceSwimEntry.DIVE;
      state.setInput(Input.JUMP, false);
      return;
    }
    if (surfaceBumpCooldown > 0) {
      surfaceBumpCooldown--;
    }
    BlockPos water = probe.orElse(ctx.playerFeet());
    double surfaceY = waterSurfaceY(water);
    double clearance = ctx.player().getEyeY() - surfaceY;
    boolean eyeWet = ctx.player().isEyeInFluid(FluidTags.WATER);
    boolean underWater = ctx.player().isUnderWater();
    boolean swimming = ctx.player().isSwimming();
    if (swimming) {
      surfaceDiveTicks = 0;
      surfaceSwimEntry = SurfaceSwimEntry.CRUISE;
    } else if (surfaceSwimEntry == SurfaceSwimEntry.CRUISE || surfaceSwimEntry == SurfaceSwimEntry.ASCEND && !eyeWet && !underWater && clearance >= SURFACE_BUMP_CEILING) {
      surfaceSwimEntry = SurfaceSwimEntry.DIVE;
    }
    if (surfaceSwimEntry == SurfaceSwimEntry.DIVE) {
      surfaceDiveTicks++;
      if (underWater || eyeWet || clearance <= SURFACE_DIVE_CLEARANCE) {
        surfaceDiveTicks = 0;
        surfaceSwimEntry = SurfaceSwimEntry.ASCEND;
      }
    }
    if (surfaceSwimEntry == SurfaceSwimEntry.DIVE) {
      state.setInput(Input.JUMP, false);
      state.setInput(Input.SNEAK, false);
      surfacePitch = SURFACE_DIVE_PITCH;
      return;
    }
    double verticalVelocity = ctx.player().getDeltaMovement().y;
    boolean nearSurface = clearance >= SURFACE_BUMP_FLOOR;
    boolean breathing = !eyeWet && !underWater;
    boolean urgent = ctx.player().getAirSupply() <= oxygenReserve(ctx.player().getMaxAirSupply(), nearSurface);
    boolean acquireSwimPose = surfaceSwimEntry == SurfaceSwimEntry.ASCEND;
    boolean climbToSurface = swimming && (!breathing || clearance < SURFACE_BUMP_FLOOR);
    boolean acquirePulse = acquireSwimPose && surfaceBumpCooldown == 0;
    boolean climbPulse =
      climbToSurface && (urgent || !breathing || clearance < SURFACE_CLIMB_PULSE_CLEARANCE) && (urgent || verticalVelocity <= SURFACE_CLIMB_PULSE_MAX_UPWARD_VELOCITY) && surfaceBumpCooldown == 0;
    boolean bump = !acquireSwimPose && urgent && (swimming || eyeWet) && nearSurface && clearance < SURFACE_DROWN_BUMP_CLEARANCE && clearance < SURFACE_BUMP_CEILING
      && verticalVelocity <= SURFACE_BUMP_MAX_UPWARD_VELOCITY && surfaceBumpCooldown == 0;
    boolean trimBump = !acquireSwimPose && !urgent && swimming && breathing && nearSurface && clearance < SURFACE_TRIM_BUMP_CLEARANCE && clearance < SURFACE_BUMP_CEILING
      && verticalVelocity <= SURFACE_TRIM_BUMP_MAX_UPWARD_VELOCITY && surfaceBumpCooldown == 0;
    boolean glideKick = !acquireSwimPose && !urgent && swimming && breathing && clearance >= SURFACE_GLIDE_KICK_FLOOR && clearance <= SURFACE_GLIDE_KICK_CEILING
      && verticalVelocity <= SURFACE_GLIDE_KICK_MAX_UPWARD_VELOCITY && surfaceBumpCooldown == 0;
    if (acquirePulse) {
      surfaceBumpCooldown = SURFACE_ACQUIRE_BUMP_INTERVAL_TICKS;
    } else if (glideKick) {
      surfaceBumpCooldown = SURFACE_GLIDE_KICK_INTERVAL_TICKS;
    } else if (trimBump) {
      surfaceBumpCooldown = SURFACE_TRIM_BUMP_INTERVAL_TICKS;
    } else if (climbPulse || bump) {
      surfaceBumpCooldown = SURFACE_DROWN_BUMP_INTERVAL_TICKS;
    }
    state.setInput(Input.SNEAK, false);
    state.setInput(Input.JUMP, acquirePulse || climbPulse || bump || trimBump || glideKick);
    surfacePitch = surfacePitch(acquireSwimPose, swimming, breathing, urgent, clearance, verticalVelocity);
  }

  private float surfacePitch(boolean acquireSwimPose, boolean swimming, boolean breathing, boolean urgent, double clearance, double verticalVelocity) {
    if (acquireSwimPose) {
      return SURFACE_ASCENT_PITCH;
    }
    if (!swimming) {
      return SURFACE_DIVE_PITCH;
    }
    if (breathing && !urgent && clearance >= SURFACE_FAST_GLIDE_MIN_CLEARANCE && clearance <= SURFACE_GLIDE_HIGH_CLEARANCE && verticalVelocity <= SURFACE_GLIDE_DAMP_UPWARD_VELOCITY) {
      return 0F;
    }
    if (clearance > SURFACE_GLIDE_HIGH_CLEARANCE || verticalVelocity > SURFACE_GLIDE_DAMP_UPWARD_VELOCITY) {
      return SURFACE_GLIDE_DAMP_PITCH;
    }
    if (urgent) {
      return SURFACE_ASCENT_PITCH;
    }
    double ascentDemand = (SURFACE_GLIDE_TARGET_CLEARANCE - clearance) * SURFACE_GLIDE_CLEARANCE_GAIN - verticalVelocity * SURFACE_GLIDE_VELOCITY_GAIN;
    return (float) Math.max(SURFACE_ASCENT_PITCH, Math.min(SURFACE_GLIDE_DAMP_PITCH, -ascentDemand));
  }

  private void swimTrackTrim(ControlFrame.Builder state) {
    if (!ctx.player().isSwimming()) {
      swimStrafe = 0;
      state.setInput(Input.MOVE_LEFT, false);
      state.setInput(Input.MOVE_RIGHT, false);
      return;
    }
    double error = signedOffLineDistance();
    if (swimStrafe < 0 && error <= SWIM_STRAFE_RELEASE || swimStrafe > 0 && error >= -SWIM_STRAFE_RELEASE) {
      swimStrafe = 0;
    }
    if (swimStrafe == 0) {
      if (error > SWIM_STRAFE_ENGAGE) {
        swimStrafe = -1;
      } else if (error < -SWIM_STRAFE_ENGAGE) {
        swimStrafe = 1;
      }
    }
    state.setInput(Input.MOVE_LEFT, swimStrafe < 0);
    state.setInput(Input.MOVE_RIGHT, swimStrafe > 0);
  }

  private void updateBoat(ControlFrame.Builder state) {
    Optional<AbstractBoat> controlled = controlledBoat();
    if (controlled.isPresent()) {
      if (phase == SurfaceLinePhase.DISMOUNT) {
        dismountBoat(state, controlled.get());
        return;
      }
      phase = SurfaceLinePhase.RIDE;
      rideBoat(state, controlled.get());
      return;
    }
    if (phase == SurfaceLinePhase.DISMOUNT) {
      phase = SurfaceLinePhase.PICKUP;
      boatPickupTicks = 0;
    }
    if (phase == SurfaceLinePhase.FINISH) {
      finishAfterBoat(state);
      return;
    }
    if (phase == SurfaceLinePhase.PICKUP) {
      pickupBoat(state);
      return;
    }
    if (terminalBoatLegShouldFinish()) {
      Optional<AbstractBoat> nearby = nearestBoat();
      if (nearby.isPresent()) {
        phase = SurfaceLinePhase.PICKUP;
        pickupBoat(state);
        return;
      }
      phase = SurfaceLinePhase.FINISH;
      finishAfterBoat(state);
      return;
    }
    Optional<AbstractBoat> nearby = nearestBoat();
    if (nearby.isPresent()) {
      phase = SurfaceLinePhase.MOUNT;
      mountBoat(state, nearby.get());
      return;
    }
    phase = SurfaceLinePhase.PLACE;
    placeBoat(state);
  }

  private void placeBoat(ControlFrame.Builder state) {
    if (!((Baritone) baritone).getInventoryBehavior().hasBoat()) {
      state.setStatus(MovementStatus.UNREACHABLE);
      return;
    }
    if (boatPlaceCooldown > 0) {
      boatPlaceCooldown--;
    }
    if (MovementClientHelper.isWater(ctx, ctx.playerFeet())) {
      placeBoatFromWater(state);
      return;
    }
    if (!boatLaunchReady()) {
      MovementClientHelper.moveTowards(ctx, state, segment.src());
      state.setInput(Input.SPRINT, false);
      state.setInput(Input.CLICK_RIGHT, false);
      return;
    }
    OptionalInt slot = ((Baritone) baritone).getInventoryBehavior().findBoatHotbarSlot();
    Rotation target = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), boatPlacementTarget(), ctx.playerRotations());
    state.setTarget(new ControlFrame.MovementTarget(target, true));
    state.setInput(Input.MOVE_FORWARD, false);
    state.setInput(Input.SPRINT, false);
    state.setInput(Input.CLICK_RIGHT, false);
    slot.ifPresent(state::selectHotbarSlot);
    if (slot.isPresent()) {
      useBoatIfAimed(state, InteractionHand.MAIN_HAND, target);
    }
  }

  private void placeBoatFromWater(ControlFrame.Builder state) {
    OptionalInt slot = ((Baritone) baritone).getInventoryBehavior().findBoatHotbarSlot();
    Rotation target = new Rotation(yawToLineTarget(), ctx.player().isSwimming() || ctx.player().isUnderWater() ? 0F : 8F);
    state.setTarget(new ControlFrame.MovementTarget(target, true));
    state.setInput(Input.MOVE_FORWARD, false);
    state.setInput(Input.JUMP, ctx.player().isUnderWater());
    state.setInput(Input.SPRINT, false);
    state.setInput(Input.CLICK_RIGHT, false);
    slot.ifPresent(state::selectHotbarSlot);
    if (slot.isPresent()) {
      useBoatIfAimed(state, InteractionHand.MAIN_HAND, target);
    }
  }

  private void useBoatIfAimed(ControlFrame.Builder state, InteractionHand hand, Rotation target) {
    if (boatPlaceCooldown == 0 && rotationClose(ctx.playerRotations(), target)) {
      state.useItem(hand);
      boatPlaceCooldown = BOAT_PLACE_COOLDOWN_TICKS;
    }
  }

  private Vec3 boatPlacementTarget() {
    Vec3 shore = VecUtils.getBlockPosCenter(segment.src());
    Vec3 water = VecUtils.getBlockPosCenter(segment.waterStart());
    double dx = water.x - shore.x;
    double dz = water.z - shore.z;
    double inv = 1D / Math.max(1D, Math.hypot(dx, dz));
    return water.add(0.35D * dx * inv, 0.45D, 0.35D * dz * inv);
  }

  private void mountBoat(ControlFrame.Builder state, AbstractBoat boat) {
    lookAtBoat(state, boat);
    state.useEntity(boat, InteractionHand.MAIN_HAND);
  }

  private void rideBoat(ControlFrame.Builder state, AbstractBoat boat) {
    if (!segment.terminal() && progress() >= SUCCESS_PROGRESS) {
      state.setStatus(MovementStatus.SUCCESS);
      return;
    }
    if (segment.terminal() && boatDismountPoint()) {
      phase = SurfaceLinePhase.DISMOUNT;
      boatDismountTicks = 0;
      dismountBoat(state, boat);
      return;
    }
    float targetYaw = yawToLineTarget();
    float error = Rotation.normalizeYaw(targetYaw - boat.getYRot());
    double absError = Math.abs(error);
    int steerSign = sign(error);
    boatSteer = absError >= BOAT_TURN_ENGAGE || boatSteer != 0 && steerSign == boatSteer && absError > BOAT_TURN_RELEASE ? steerSign : 0;
    boolean left = boatSteer < 0;
    boolean right = boatSteer > 0;
    boolean hardTurn = Math.abs(error) > BOAT_HARD_TURN;
    state.setInput(Input.MOVE_FORWARD, !hardTurn);
    state.setInput(Input.MOVE_BACK, false);
    state.setInput(Input.MOVE_LEFT, left);
    state.setInput(Input.MOVE_RIGHT, right);
    state.setInput(Input.SNEAK, false);
    state.setTarget(new ControlFrame.MovementTarget(new Rotation(targetYaw, 0F), true));
    state.vehicle(new VehicleCommand(left, right, !hardTurn, false));
  }

  private void dismountBoat(ControlFrame.Builder state, AbstractBoat boat) {
    boatDismountTicks++;
    boolean brake = boatDismountTicks <= BOAT_BRAKE_TICKS;
    state.setInput(Input.MOVE_FORWARD, false);
    state.setInput(Input.MOVE_BACK, brake);
    state.setInput(Input.MOVE_LEFT, false);
    state.setInput(Input.MOVE_RIGHT, false);
    state.setInput(Input.SNEAK, true);
    state.setTarget(new ControlFrame.MovementTarget(new Rotation(yawToLineTarget(), 0F), true));
    state.vehicle(new VehicleCommand(false, false, false, brake));
    if (boatDismountTicks >= BOAT_FORCE_DISMOUNT_TICKS) {
      state.dismount();
    }
  }

  private void pickupBoat(ControlFrame.Builder state) {
    phase = SurfaceLinePhase.PICKUP;
    boatPickupTicks++;
    Optional<AbstractBoat> boat = nearestBoat();
    if (boat.isPresent()) {
      attackBoatForPickup(state, boat.get());
      return;
    }
    if (((Baritone) baritone).getInventoryBehavior().hasBoat()) {
      phase = SurfaceLinePhase.FINISH;
      finishAfterBoat(state);
      return;
    }
    Optional<ItemEntity> item = nearestBoatItem();
    if (item.isPresent()) {
      collectBoatItem(state, item.get().position());
      return;
    }
    if (lastBoatPickupTarget != null && boatPickupTicks <= BOAT_PICKUP_TIMEOUT_TICKS) {
      collectBoatItem(state, lastBoatPickupTarget);
      return;
    }
    state.setStatus(MovementStatus.UNREACHABLE);
  }

  private void attackBoatForPickup(ControlFrame.Builder state, AbstractBoat target) {
    lastBoatPickupTarget = target.position();
    Rotation rotation = boatRotation(target);
    state.setTarget(new ControlFrame.MovementTarget(rotation, true));
    double distanceSq = horizontalDistanceSq(ctx.player().position(), target.position());
    state.setInput(Input.MOVE_FORWARD, distanceSq > BOAT_PICKUP_APPROACH_DISTANCE_SQ);
    state.setInput(Input.JUMP, distanceSq > BOAT_PICKUP_APPROACH_DISTANCE_SQ && MovementClientHelper.isWater(ctx, ctx.playerFeet()));
    state.setInput(Input.SPRINT, false);
    state.setInput(Input.SNEAK, false);
    if (distanceSq <= BOAT_PICKUP_APPROACH_DISTANCE_SQ && rotationClose(ctx.playerRotations(), rotation)) {
      if (boatAttackCooldown > 0) {
        boatAttackCooldown--;
      } else {
        state.attackEntity(target);
        boatAttackCooldown = BOAT_ATTACK_COOLDOWN_TICKS;
      }
    }
  }

  private void collectBoatItem(ControlFrame.Builder state, Vec3 target) {
    lastBoatPickupTarget = target;
    Rotation rotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target.add(0D, 0.15D, 0D), ctx.playerRotations());
    state.setTarget(new ControlFrame.MovementTarget(rotation, true));
    double distanceSq = horizontalDistanceSq(ctx.player().position(), target);
    state.setInput(Input.MOVE_FORWARD, distanceSq > BOAT_ITEM_PICKUP_DISTANCE_SQ);
    state.setInput(Input.JUMP, distanceSq > BOAT_ITEM_PICKUP_DISTANCE_SQ && MovementClientHelper.isWater(ctx, ctx.playerFeet()));
    state.setInput(Input.SPRINT, false);
    state.setInput(Input.SNEAK, false);
  }

  private void finishAfterBoat(ControlFrame.Builder state) {
    BetterBlockPos dest = segment.dest();
    boolean destWater = MovementClientHelper.isWater(ctx, dest);
    boolean playerWet = MovementClientHelper.isWater(ctx, ctx.playerFeet());
    if ((destWater || !playerWet)
      && (horizontalDistanceSq(ctx.player().position(), VecUtils.getBlockPosCenter(dest)) <= SUCCESS_DISTANCE_SQ || segment.validPositions().contains(new BetterBlockPos(ctx.playerFeet())))) {
      state.setStatus(MovementStatus.SUCCESS);
      return;
    }
    MovementClientHelper.moveTowards(ctx, state, dest);
    state.setInput(Input.JUMP, playerWet || ctx.playerFeet().getY() < dest.y);
    state.setInput(Input.SPRINT, Baritone.settings().sprintInWater.value && playerWet);
    state.setInput(Input.SNEAK, false);
  }

  private Optional<AbstractBoat> controlledBoat() {
    Entity vehicle = ctx.player().getVehicle();
    return vehicle instanceof AbstractBoat boat ? Optional.of(boat) : Optional.empty();
  }

  private Optional<AbstractBoat> nearestBoat() {
    AABB area = ctx.player().getBoundingBox().inflate(BOAT_SEARCH_RADIUS, 2D, BOAT_SEARCH_RADIUS);
    return ctx.world().getEntitiesOfClass(AbstractBoat.class, area, boat -> !boat.isRemoved() && (!boat.isVehicle() || boat.hasPassenger(ctx.player()))).stream()
      .min(Comparator.comparingDouble(boat -> horizontalDistanceSq(ctx.player().position(), boat.position())));
  }

  private Optional<ItemEntity> nearestBoatItem() {
    AABB area = ctx.player().getBoundingBox().inflate(BOAT_SEARCH_RADIUS, 2D, BOAT_SEARCH_RADIUS);
    return ctx.world().getEntitiesOfClass(ItemEntity.class, area, item -> !item.isRemoved() && item.getItem().getItem() instanceof BoatItem).stream()
      .min(Comparator.comparingDouble(item -> horizontalDistanceSq(ctx.player().position(), item.position())));
  }

  private void lookAtBoat(ControlFrame.Builder state, AbstractBoat boat) {
    state.setTarget(new ControlFrame.MovementTarget(boatRotation(boat), true));
  }

  private Rotation boatRotation(AbstractBoat boat) {
    return RotationUtils.calcRotationFromVec3d(ctx.playerHead(), boat.position().add(0D, 0.35D, 0D), ctx.playerRotations());
  }

  private boolean lineComplete(double progressThreshold, double destinationDistanceSq) {
    return progress() >= progressThreshold || horizontalDistanceSq(controlPosition(), VecUtils.getBlockPosCenter(segment.waterEnd())) <= destinationDistanceSq;
  }

  private boolean boatDismountPoint() {
    double progress = progress();
    double progressThreshold = Math.max(BOAT_DISEMBARK_PROGRESS_FLOOR, 1D - BOAT_DISEMBARK_LOOKAHEAD_BLOCKS / Math.max(1D, segment.length()));
    return progress >= progressThreshold || progress > 0.75D && horizontalDistanceSq(controlPosition(), VecUtils.getBlockPosCenter(segment.waterEnd())) <= BOAT_DISEMBARK_DEST_DISTANCE_SQ;
  }

  private boolean swimTerminalExitPoint() {
    double progress = progress();
    double progressThreshold = Math.max(SWIM_TERMINAL_EXIT_PROGRESS_FLOOR, 1D - SWIM_TERMINAL_EXIT_LOOKAHEAD_BLOCKS / Math.max(1D, segment.length()));
    return progress >= progressThreshold || progress > 0.70D && horizontalDistanceSq(controlPosition(), VecUtils.getBlockPosCenter(segment.dest())) <= SWIM_TERMINAL_EXIT_DEST_DISTANCE_SQ;
  }

  private boolean terminalBoatLegShouldFinish() {
    return segment.terminal() && (boatDismountPoint() || progress() >= 1D);
  }

  private TransportMode commandMode() {
    return segment.boat() || clearingBoatForSwim ? TransportMode.BOAT : segment.mode();
  }

  private float yawToLineTarget() {
    return RotationUtils.calcRotationFromVec3d(ctx.playerHead(), lookaheadTarget(), ctx.playerRotations()).getYaw();
  }

  private double waterSurfaceY(BlockPos feet) {
    BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos(feet.getX(), feet.getY(), feet.getZ());
    int maxY = ctx.world().getMaxY();
    while (scan.getY() < maxY && MovementClientHelper.isWater(ctx, scan)) {
      scan.move(Direction.UP);
    }
    return scan.getY();
  }

  private static int oxygenReserve(int maxAir, boolean nearSurface) {
    return (int) Math.ceil(Math.max(1, maxAir) * (nearSurface ? SURFACE_NEAR_AIR_RESERVE : SURFACE_DEEP_AIR_RESERVE));
  }

  private Optional<BlockPos> waterProbe() {
    BlockPos feet = ctx.playerFeet();
    if (MovementClientHelper.isWater(ctx, feet)) {
      return Optional.of(feet);
    }
    BlockPos below = feet.below();
    return MovementClientHelper.isWater(ctx, below) ? Optional.of(below) : Optional.empty();
  }

  private Vec3 lookaheadTarget() {
    Vec3 start = VecUtils.getBlockPosCenter(segment.waterStart());
    Vec3 end = VecUtils.getBlockPosCenter(segment.waterEnd());
    double progress = progress();
    if (progress < 0D) {
      return start;
    }
    double t = Math.max(progress, 0D);
    double lookahead = Math.min(1D, t + 12D / Math.max(1D, segment.length()));
    return start.lerp(end, lookahead);
  }

  public double progress() {
    Vec3 player = controlPosition();
    Vec3 start = VecUtils.getBlockPosCenter(segment.waterStart());
    double dx = segment.waterEnd().x - segment.waterStart().x;
    double dz = segment.waterEnd().z - segment.waterStart().z;
    double lenSq = dx * dx + dz * dz;
    return ((player.x - start.x) * dx + (player.z - start.z) * dz) / lenSq;
  }

  private double offLineDistanceSq() {
    Vec3 player = controlPosition();
    Vec3 start = VecUtils.getBlockPosCenter(segment.waterStart());
    double dx = segment.waterEnd().x - segment.waterStart().x;
    double dz = segment.waterEnd().z - segment.waterStart().z;
    double lenSq = dx * dx + dz * dz;
    double t = Math.max(0D, Math.min(1D, ((player.x - start.x) * dx + (player.z - start.z) * dz) / lenSq));
    double x = start.x + dx * t;
    double z = start.z + dz * t;
    return horizontalDistanceSq(player, new Vec3(x, player.y, z));
  }

  private double signedOffLineDistance() {
    Vec3 player = controlPosition();
    Vec3 start = VecUtils.getBlockPosCenter(segment.waterStart());
    double dx = segment.waterEnd().x - segment.waterStart().x;
    double dz = segment.waterEnd().z - segment.waterStart().z;
    double length = Math.hypot(dx, dz);
    if (length < 1.0E-6D) {
      return 0D;
    }
    return (dx * (player.z - start.z) - dz * (player.x - start.x)) / length;
  }

  private static double horizontalDistanceSq(Vec3 a, Vec3 b) {
    double dx = a.x - b.x;
    double dz = a.z - b.z;
    return dx * dx + dz * dz;
  }

  private static int sign(double x) {
    return x < 0D ? -1 : x > 0D ? 1 : 0;
  }

  private boolean boatLaunchReady() {
    BlockPos feet = ctx.playerFeet();
    return horizontalDistanceSq(ctx.player().position(), VecUtils.getBlockPosCenter(segment.src())) <= BOAT_LAUNCH_CENTER_DISTANCE_SQ && !MovementClientHelper.isWater(ctx, feet)
      && MovementClientHelper.canWalkOn(ctx, feet.below());
  }

  private Vec3 controlPosition() {
    return controlledBoat().map(Entity::position).orElse(ctx.player().position());
  }

  private static boolean rotationClose(Rotation current, Rotation target) {
    return Math.abs(Rotation.normalizeYaw(target.getYaw() - current.getYaw())) <= BOAT_PLACE_ROTATION_TOLERANCE && Math.abs(target.getPitch() - current.getPitch()) <= BOAT_PLACE_ROTATION_TOLERANCE;
  }

  private enum SurfaceSwimEntry {
    DIVE, ASCEND, CRUISE
  }
}
