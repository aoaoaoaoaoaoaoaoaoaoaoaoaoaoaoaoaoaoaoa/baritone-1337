package baritone.pathing.movement.movements;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.water.WaterLineSegment;
import baritone.pathing.transport.TransportMode;
import baritone.pathing.transport.TransportSnapshot;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

public final class MovementWaterLine extends Movement {
  private static final double SUCCESS_PROGRESS = 0.985D;
  private static final double SUCCESS_DISTANCE_SQ = 1.44D;
  private static final double LINE_TOLERANCE_SQ = 2.56D;
  private static final double BOAT_DISEMBARK_LOOKAHEAD_BLOCKS = 4D;
  private static final double BOAT_DISEMBARK_PROGRESS_FLOOR = 0.86D;
  private static final double BOAT_DISEMBARK_DEST_DISTANCE_SQ = 16D;
  private static final double BOAT_TRANSIT_DEST_DISTANCE_SQ = 9D;
  private static final int BOAT_BRAKE_TICKS = 3;
  private static final int BOAT_FORCE_DISMOUNT_TICKS = 5;
  private static final int BOAT_PICKUP_TIMEOUT_TICKS = 100;
  private static final double BOAT_PICKUP_APPROACH_DISTANCE_SQ = 6.25D;
  private static final double BOAT_SEARCH_RADIUS = 5D;
  private static final double BOAT_LAUNCH_CENTER_DISTANCE_SQ = 0.49D;
  private static final double SWIM_DRIFT_TOLERANCE_SQ = 9D;
  private static final double BOAT_DRIFT_TOLERANCE_SQ = 256D;
  private static final double BOAT_TURN_ENGAGE = 4D;
  private static final double BOAT_TURN_RELEASE = 1.25D;
  private static final double BOAT_HARD_TURN = 70D;
  private static final float BOAT_PLACE_ROTATION_TOLERANCE = 5F;
  private static final int BOAT_PLACE_COOLDOWN_TICKS = 4;
  private static final int BOAT_ATTACK_COOLDOWN_TICKS = 4;

  private final WaterLineSegment segment;
  private BoatPhase boatPhase = BoatPhase.PLACE;
  private int boatPlaceCooldown;
  private int boatAttackCooldown;
  private int boatDismountTicks;
  private int boatPickupTicks;
  private int boatSteer;

  public MovementWaterLine(IBaritone baritone, WaterLineSegment segment) {
    super(baritone, segment.src(), segment.dest(), new BetterBlockPos[0]);
    this.segment = segment;
  }

  public WaterLineSegment segment() {
    return segment;
  }

  public TransportMode transportMode() {
    return segment.mode();
  }

  public String transportPhase() {
    return segment.boat() ? boatPhase.name() : "SWIM";
  }

  public double transportProgress() {
    return progress();
  }

  @Override
  public TransportSnapshot.Plan transportPlan() {
    return TransportSnapshot.Plan.transport(segment.mode(), getClass().getSimpleName(), src, dest, transportPhase(), segment.terminal(), segment.waterStart(), transportProgress());
  }

  @Override
  public void reset() {
    super.reset();
    boatPhase = BoatPhase.PLACE;
    boatPlaceCooldown = 0;
    boatAttackCooldown = 0;
    boatDismountTicks = 0;
    boatPickupTicks = 0;
    boatSteer = 0;
  }

  @Override
  public double calculateCost(CalculationContext context) {
    return segment.cost();
  }

  @Override
  protected Set<BetterBlockPos> calculateValidPositions() {
    return segment.validPositions().stream().collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public boolean acceptsPathingDrift(BlockPos pos) {
    return acceptsPosition(pos) || progress() >= -0.25D && progress() <= 1.25D && offLineDistanceSq() <= (segment.boat() ? BOAT_DRIFT_TOLERANCE_SQ : SWIM_DRIFT_TOLERANCE_SQ);
  }

  @Override
  public double sustainedPathDistanceTolerance() {
    return segment.boat() ? 16D : 4D;
  }

  @Override
  public double immediatePathDistanceTolerance() {
    return segment.boat() ? 28D : 7D;
  }

  @Override
  public MovementState updateState(MovementState state) {
    super.updateState(state);
    if (state.getStatus() != MovementStatus.RUNNING) {
      return state;
    }
    return segment.boat() ? updateBoat(state) : updateSwim(state);
  }

  private MovementState updateSwim(MovementState state) {
    if (lineComplete(SUCCESS_PROGRESS, SUCCESS_DISTANCE_SQ)) {
      return state.setStatus(MovementStatus.SUCCESS);
    }
    if (!playerInValidPosition() && offLineDistanceSq() > LINE_TOLERANCE_SQ) {
      return state.setStatus(MovementStatus.UNREACHABLE);
    }
    state.setInput(Input.MOVE_FORWARD, true);
    state.setInput(Input.SPRINT, Baritone.settings().sprintInWater.value);
    state.setInput(Input.SNEAK, false);
    state.setTarget(new MovementState.MovementTarget(new Rotation(yawToLineTarget(), ctx.playerRotations().getPitch()), false));
    return state;
  }

  private MovementState updateBoat(MovementState state) {
    Optional<AbstractBoat> controlled = controlledBoat();
    if (controlled.isPresent()) {
      if (boatPhase == BoatPhase.DISMOUNT) {
        return dismountBoat(state, controlled.get());
      }
      boatPhase = BoatPhase.RIDE;
      return rideBoat(state, controlled.get());
    }
    if (boatPhase == BoatPhase.DISMOUNT) {
      boatPhase = BoatPhase.PICKUP;
      boatPickupTicks = 0;
    }
    if (boatPhase == BoatPhase.FINISH) {
      return finishAfterBoat(state);
    }
    if (boatPhase == BoatPhase.PICKUP) {
      return pickupBoat(state);
    }
    if (terminalBoatLegShouldFinish()) {
      Optional<AbstractBoat> nearby = nearestBoat();
      if (nearby.isPresent()) {
        boatPhase = BoatPhase.PICKUP;
        return pickupBoat(state);
      }
      boatPhase = BoatPhase.FINISH;
      return finishAfterBoat(state);
    }
    Optional<AbstractBoat> nearby = nearestBoat();
    if (nearby.isPresent()) {
      boatPhase = BoatPhase.MOUNT;
      return mountBoat(state, nearby.get());
    }
    boatPhase = BoatPhase.PLACE;
    return placeBoat(state);
  }

  private MovementState placeBoat(MovementState state) {
    if (!((Baritone) baritone).getInventoryBehavior().hasBoat()) {
      return state.setStatus(MovementStatus.UNREACHABLE);
    }
    if (boatPlaceCooldown > 0) {
      boatPlaceCooldown--;
    }
    if (MovementHelper.isWater(ctx, ctx.playerFeet())) {
      return placeBoatFromWater(state);
    }
    if (!boatLaunchReady()) {
      MovementHelper.moveTowards(ctx, state, src);
      state.setInput(Input.SPRINT, false);
      state.setInput(Input.CLICK_RIGHT, false);
      return state;
    }
    Optional<InteractionHand> hand = ((Baritone) baritone).getInventoryBehavior().selectBoat();
    Rotation target = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), boatPlacementTarget(), ctx.playerRotations());
    state.setTarget(new MovementState.MovementTarget(target, true));
    state.setInput(Input.MOVE_FORWARD, false);
    state.setInput(Input.SPRINT, false);
    state.setInput(Input.CLICK_RIGHT, false);
    hand.ifPresent(h -> useBoatIfAimed(h, target));
    return state;
  }

  private MovementState placeBoatFromWater(MovementState state) {
    Optional<InteractionHand> hand = ((Baritone) baritone).getInventoryBehavior().selectBoat();
    Rotation target = new Rotation(yawToLineTarget(), ctx.player().isSwimming() || ctx.player().isUnderWater() ? 0F : 8F);
    state.setTarget(new MovementState.MovementTarget(target, true));
    state.setInput(Input.MOVE_FORWARD, false);
    state.setInput(Input.JUMP, ctx.player().isUnderWater());
    state.setInput(Input.SPRINT, false);
    state.setInput(Input.CLICK_RIGHT, false);
    hand.ifPresent(h -> useBoatIfAimed(h, target));
    return state;
  }

  private void useBoatIfAimed(InteractionHand hand, Rotation target) {
    if (boatPlaceCooldown == 0 && rotationClose(ctx.playerRotations(), target)) {
      ctx.playerController().processRightClick(ctx.player(), ctx.world(), hand);
      ctx.player().swing(hand);
      boatPlaceCooldown = BOAT_PLACE_COOLDOWN_TICKS;
    }
  }

  private Vec3 boatPlacementTarget() {
    Vec3 shore = VecUtils.getBlockPosCenter(src);
    Vec3 water = VecUtils.getBlockPosCenter(segment.waterStart());
    double dx = water.x - shore.x;
    double dz = water.z - shore.z;
    double inv = 1D / Math.max(1D, Math.hypot(dx, dz));
    return water.add(0.35D * dx * inv, 0.45D, 0.35D * dz * inv);
  }

  private MovementState mountBoat(MovementState state, AbstractBoat boat) {
    lookAtBoat(state, boat);
    ctx.minecraft().gameMode.interact(ctx.player(), boat, new EntityHitResult(boat), InteractionHand.MAIN_HAND);
    ctx.player().swing(InteractionHand.MAIN_HAND);
    return state;
  }

  private MovementState rideBoat(MovementState state, AbstractBoat boat) {
    if (!segment.terminal() && lineComplete(SUCCESS_PROGRESS, BOAT_TRANSIT_DEST_DISTANCE_SQ)) {
      return state.setStatus(MovementStatus.SUCCESS);
    }
    if (segment.terminal() && boatDismountPoint()) {
      boatPhase = BoatPhase.DISMOUNT;
      boatDismountTicks = 0;
      return dismountBoat(state, boat);
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
    state.setTarget(new MovementState.MovementTarget(new Rotation(targetYaw, 0F), true));
    boat.setInput(left, right, !hardTurn, false);
    return state;
  }

  private MovementState dismountBoat(MovementState state, AbstractBoat boat) {
    boatDismountTicks++;
    boolean brake = boatDismountTicks <= BOAT_BRAKE_TICKS;
    state.setInput(Input.MOVE_FORWARD, false);
    state.setInput(Input.MOVE_BACK, brake);
    state.setInput(Input.MOVE_LEFT, false);
    state.setInput(Input.MOVE_RIGHT, false);
    state.setInput(Input.SNEAK, true);
    state.setTarget(new MovementState.MovementTarget(new Rotation(yawToLineTarget(), 0F), true));
    boat.setInput(false, false, false, brake);
    if (boatDismountTicks >= BOAT_FORCE_DISMOUNT_TICKS) {
      ctx.player().stopRiding();
    }
    return state;
  }

  private MovementState pickupBoat(MovementState state) {
    boatPhase = BoatPhase.PICKUP;
    boatPickupTicks++;
    Optional<AbstractBoat> boat = nearestBoat();
    if (boat.isEmpty()) {
      boatPhase = BoatPhase.FINISH;
      return finishAfterBoat(state);
    }
    if (boatPickupTicks > BOAT_PICKUP_TIMEOUT_TICKS) {
      boatPhase = BoatPhase.FINISH;
      return finishAfterBoat(state);
    }
    AbstractBoat target = boat.get();
    Rotation rotation = boatRotation(target);
    state.setTarget(new MovementState.MovementTarget(rotation, true));
    double distanceSq = horizontalDistanceSq(ctx.player().position(), target.position());
    state.setInput(Input.MOVE_FORWARD, distanceSq > BOAT_PICKUP_APPROACH_DISTANCE_SQ);
    state.setInput(Input.JUMP, distanceSq > BOAT_PICKUP_APPROACH_DISTANCE_SQ && MovementHelper.isWater(ctx, ctx.playerFeet()));
    state.setInput(Input.SPRINT, false);
    state.setInput(Input.SNEAK, false);
    if (distanceSq <= BOAT_PICKUP_APPROACH_DISTANCE_SQ && rotationClose(ctx.playerRotations(), rotation)) {
      if (boatAttackCooldown > 0) {
        boatAttackCooldown--;
      } else {
        ctx.minecraft().gameMode.attack(ctx.player(), target);
        ctx.player().swing(InteractionHand.MAIN_HAND);
        boatAttackCooldown = BOAT_ATTACK_COOLDOWN_TICKS;
      }
    }
    return state;
  }

  private MovementState finishAfterBoat(MovementState state) {
    boolean destWater = MovementHelper.isWater(ctx, dest);
    boolean playerWet = MovementHelper.isWater(ctx, ctx.playerFeet());
    if ((destWater || !playerWet) && (horizontalDistanceSq(ctx.player().position(), VecUtils.getBlockPosCenter(dest)) <= SUCCESS_DISTANCE_SQ || acceptsPosition(ctx.playerFeet()))) {
      return state.setStatus(MovementStatus.SUCCESS);
    }
    MovementHelper.moveTowards(ctx, state, dest);
    state.setInput(Input.JUMP, playerWet || ctx.playerFeet().getY() < dest.y);
    state.setInput(Input.SPRINT, Baritone.settings().sprintInWater.value && playerWet);
    state.setInput(Input.SNEAK, false);
    return state;
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

  private void lookAtBoat(MovementState state, AbstractBoat boat) {
    state.setTarget(new MovementState.MovementTarget(boatRotation(boat), true));
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

  private boolean terminalBoatLegShouldFinish() {
    return segment.terminal() && (boatDismountPoint() || progress() >= 1D);
  }

  private float yawToLineTarget() {
    Vec3 target = lookaheadTarget();
    return RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target, ctx.playerRotations()).getYaw();
  }

  private Vec3 lookaheadTarget() {
    Vec3 start = VecUtils.getBlockPosCenter(segment.waterStart());
    Vec3 end = VecUtils.getBlockPosCenter(segment.waterEnd());
    double t = Math.max(progress(), 0D);
    double lookahead = Math.min(1D, t + 12D / Math.max(1D, segment.length()));
    return start.lerp(end, lookahead);
  }

  private double progress() {
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
    return horizontalDistanceSq(ctx.player().position(), VecUtils.getBlockPosCenter(src)) <= BOAT_LAUNCH_CENTER_DISTANCE_SQ && !MovementHelper.isWater(ctx, feet)
      && MovementHelper.canWalkOn(ctx, feet.below());
  }

  private Vec3 controlPosition() {
    return controlledBoat().map(Entity::position).orElse(ctx.player().position());
  }

  private static boolean rotationClose(Rotation current, Rotation target) {
    return Math.abs(Rotation.normalizeYaw(target.getYaw() - current.getYaw())) <= BOAT_PLACE_ROTATION_TOLERANCE && Math.abs(target.getPitch() - current.getPitch()) <= BOAT_PLACE_ROTATION_TOLERANCE;
  }

  private enum BoatPhase {
    PLACE, MOUNT, RIDE, DISMOUNT, PICKUP, FINISH
  }
}
