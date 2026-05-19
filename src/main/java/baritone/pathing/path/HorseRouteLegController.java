package baritone.pathing.path;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.pathing.control.ControlFrame;
import baritone.pathing.goal.GoalTerminalPolicy;
import baritone.pathing.mounted.HorseCollisionOracle;
import baritone.pathing.mounted.HorsePath;
import baritone.pathing.mounted.HorseTrackCertifier;
import baritone.pathing.mounted.HorseTrackLattice;
import baritone.pathing.mounted.MountTuning;
import baritone.pathing.route.HorseRouteLeg;
import baritone.pathing.transport.TransportControl;
import baritone.pathing.transport.TransportMode;
import baritone.pathing.transport.TransportSnapshot;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class HorseRouteLegController implements RouteLegController {
  private static final double TERMINAL_BRAKE_RADIUS_SQ = 2.25D;
  private static final double STALL_RESET_DISTANCE_SQ = 0.04D;
  private static final double MICRO_STAGE_REACHED_SQ = 0.09D;
  private static final double MICRO_STAGE_PASSED_BLOCKS = 0.05D;
  private static final double MICRO_STAGE_PASSED_LATERAL_SQ = 0.36D;
  private static final double MICRO_PHYSICAL_RECOVERY_MAX_SQ = 6.25D;
  private static final double MICRO_TRACK_FOLLOW_LATERAL_SQ = 0.25D;
  private static final double MICRO_TRACK_FOLLOW_BEHIND_BLOCKS = 0.35D;
  private static final double MICRO_TRACK_FOLLOW_AHEAD_BLOCKS = 0.55D;
  private static final double MICRO_CLEARANCE_MARGIN = HorseTrackLattice.CLEARANCE_MARGIN;
  private static final double PHYSICAL_WAYPOINT_MIN_OVERLAP = 0.125D;
  private static final double TECHNICAL_EDGE_MIN_APPROACH_BLOCKS = 0.16D;
  private static final double TECHNICAL_EDGE_MIN_LOOKAHEAD_BLOCKS = 0.18D;
  private static final double TECHNICAL_EDGE_ENDPOINT_BLOCKS = 0.28D;
  private static final double WATER_ASCENT_TARGET_MARGIN = 0.65D;
  private static final int RECOVERY_STALL_TICKS = 8;
  private static final int RECOVERY_SIDE_HOLD_TICKS = 8;
  private static final double RECOVERY_PROBE_BLOCKS = 0.75D;
  private static final double RECOVERY_MIN_FORWARD_GAIN = 0.04D;
  private static final double RECOVERY_SIDE_SWITCH_GAIN = 0.08D;
  private static final double HINT_APPROACH_RADIUS_SQ = 0.49D;
  private static final boolean STALL_RECOVERY_ENABLED = Boolean.parseBoolean(System.getProperty("baritone.horse.stallRecovery", System.getenv().getOrDefault("BARITONE_HORSE_STALL_RECOVERY", "true")));

  private final PathingBehavior behavior;
  private final HorseRouteLeg leg;
  private final HorsePath path;
  private final boolean routeTerminal;
  private final Goal terminalGoal;
  private int waypoint;
  private double lastX = Double.NaN;
  private double lastZ = Double.NaN;
  private double bestTargetDistanceSq = Double.POSITIVE_INFINITY;
  private int stalledTicks;
  private int microStageEdge = -1;
  private boolean microStageSatisfied;
  private int microRecenterEdge = -1;
  private boolean microRecenterActive;
  private int microTrackEdge = -1;
  private MicroTrack microTrack;
  private int recoveryEdge = -1;
  private int recoverySide;
  private int recoveryHoldTicks;
  private HorseCollisionOracle oracle;
  private double oracleHalfWidth;
  private double oracleHeight;
  private double oracleStepHeight;
  private ControlFrame frame = ControlFrame.EMPTY;
  private boolean failed;
  private String failureReason;
  private boolean finished;

  HorseRouteLegController(PathingBehavior behavior, HorseRouteLeg leg, boolean routeTerminal, Goal terminalGoal) {
    this.behavior = behavior;
    this.leg = leg;
    this.path = leg.path();
    this.routeTerminal = routeTerminal;
    this.terminalGoal = terminalGoal;
  }

  private static MountTuning.Motion motionTuning() {
    return MountTuning.current().motion();
  }

  private static MountTuning.Controller controllerTuning() {
    return MountTuning.current().controller();
  }

  private static double technicalDepartureSettledSpeedSq() {
    return square(motionTuning().settledSpeed());
  }

  private static double waypointReachedSq() {
    return square(controllerTuning().waypointReachedRadius());
  }

  private static double technicalWaypointReachedSq() {
    return square(controllerTuning().technicalWaypointReachedRadius());
  }

  private static double terminalReachedSq() {
    return square(controllerTuning().terminalReachedRadius());
  }

  private static double physicalWaypointReachedSq() {
    return square(controllerTuning().physicalWaypointReachedRadius());
  }

  private static double terminalMaxHorizontalSpeedSq() {
    return square(controllerTuning().terminalSettledSpeed());
  }

  private static double stallProgressEpsilonSq() {
    return square(controllerTuning().stallProgressEpsilon());
  }

  private static double relaxedLookaheadBlocks() {
    return controllerTuning().cruiseSegmentAimDistance();
  }

  private static double technicalLookaheadBlocks() {
    return controllerTuning().precisionSegmentAimDistance();
  }

  private static double technicalFinalApproachBlocks() {
    return motionTuning().precisionRideTaperDistance();
  }

  private static double technicalCruiseSpeed() {
    return motionTuning().precisionRideSpeed();
  }

  private static double technicalFinalSpeed() {
    return motionTuning().precisionRideFinalSpeed();
  }

  private static double technicalBrakeSpeed() {
    return motionTuning().lowMaxForward();
  }

  private static double square(double value) {
    return value * value;
  }

  @Override
  public boolean onTick() {
    frame = ControlFrame.EMPTY;
    if (finished || failed) {
      return true;
    }
    AbstractHorse horse = horse();
    if (horse == null) {
      fail("dismounted while executing horse route");
      return true;
    }
    advance(horse);
    if (finished) {
      return true;
    }
    if (divergedFromCertifiedCorridor(horse)) {
      return true;
    }
    frame = control(horse);
    checkStall(horse);
    return false;
  }

  private void advance(AbstractHorse horse) {
    if (terminalGoalSatisfied(horse)) {
      finished = true;
      return;
    }
    while (waypoint + 1 < path.waypoints().size() && closeEnough(horse, waypoint + 1, terminalGoalWaypoint(waypoint + 1))) {
      advanceTo(horse, waypoint + 1);
      if (terminalGoalSatisfied(horse)) {
        finished = true;
        return;
      }
    }
    finished = waypoint + 1 >= path.waypoints().size();
  }

  private void advanceTo(AbstractHorse horse, int targetWaypoint) {
    while (waypoint < targetWaypoint) {
      waypoint++;
    }
    resetMicroStage();
    resetStall(horse);
  }

  private static boolean physicallyOccupiesWaypoint(AbstractHorse horse, HorsePath.Waypoint target, double maxDistanceSq) {
    BetterBlockPos feet = HorsePath.vehicleFeet(horse);
    if (Math.abs(feet.y - target.pos().y) > 1) {
      return false;
    }
    double dx = horse.getX() - executionX(target);
    double dz = horse.getZ() - executionZ(target);
    if (dx * dx + dz * dz > maxDistanceSq) {
      return false;
    }
    if (feet.x == target.pos().x && feet.z == target.pos().z) {
      return true;
    }
    AABB box = horse.getBoundingBox();
    return overlap1D(box.minX, box.maxX, target.pos().x, target.pos().x + 1D) >= PHYSICAL_WAYPOINT_MIN_OVERLAP
      && overlap1D(box.minZ, box.maxZ, target.pos().z, target.pos().z + 1D) >= PHYSICAL_WAYPOINT_MIN_OVERLAP;
  }

  private static double overlap1D(double aMin, double aMax, double bMin, double bMax) {
    return Math.min(aMax, bMax) - Math.max(aMin, bMin);
  }

  private boolean terminalGoalSatisfied(AbstractHorse horse) {
    return terminalGoal != null && mountedStable(horse) && GoalTerminalPolicy.satisfied(behavior.baritone, behavior.ctx, terminalGoal, HorsePath.vehicleFeet(horse));
  }

  private boolean routeFrontierWaypoint(int waypointIndex) {
    return routeTerminal && waypointIndex == path.waypoints().size() - 1;
  }

  private boolean terminalGoalWaypoint(int waypointIndex) {
    return routeTerminal && terminalGoal != null && waypointIndex == path.waypoints().size() - 1 && terminalGoal.isInGoal(path.waypoints().get(waypointIndex).pos());
  }

  private boolean divergedFromCertifiedCorridor(AbstractHorse horse) {
    if (!mountedStable(horse) || waypoint + 1 >= path.waypoints().size()) {
      return false;
    }
    HorsePath.Waypoint from = path.waypoints().get(waypoint);
    HorsePath.Waypoint target = path.waypoints().get(waypoint + 1);
    BetterBlockPos feet = HorsePath.vehicleFeet(horse);
    if (target.pos().y > from.pos().y && feet.y < from.pos().y) {
      fail("fell below uphill horse edge " + edgeDebug(waypoint, from, target) + " feet=" + feet + " horse=(" + horse.getX() + "," + horse.getY() + "," + horse.getZ() + ")");
      return true;
    }
    if (target.pos().y >= from.pos().y && feet.y < from.pos().y - 1) {
      fail("fell below flat horse edge " + edgeDebug(waypoint, from, target) + " feet=" + feet + " horse=(" + horse.getX() + "," + horse.getY() + "," + horse.getZ() + ")");
      return true;
    }
    int maxY = corridorMaxY(from, target);
    if (feet.y > maxY) {
      fail("climbed above certified horse edge " + edgeDebug(waypoint, from, target) + " feet=" + feet + " horse=(" + horse.getX() + "," + horse.getY() + "," + horse.getZ() + ")");
      return true;
    }
    if (feet.y >= target.minYFromPrevious() && feet.y <= maxY) {
      return false;
    }
    fail("left certified horse corridor " + edgeDebug(waypoint, from, target) + " feet=" + feet + " horse=(" + horse.getX() + "," + horse.getY() + "," + horse.getZ() + ")");
    return true;
  }

  private static String edgeDebug(int fromIndex, HorsePath.Waypoint from, HorsePath.Waypoint to) {
    return "#" + fromIndex + "->" + (fromIndex + 1) + "[" + to.edgeDebugSignature() + "] " + from.pos() + " -> " + to.pos();
  }

  private static int corridorMaxY(HorsePath.Waypoint from, HorsePath.Waypoint target) {
    return target.maxYFromPrevious() + 1;
  }

  private boolean closeEnough(AbstractHorse horse, int waypointIndex, boolean terminal) {
    if (!waypointEnvelopeReached(horse, waypointIndex, terminal)) {
      return successorExecutableFromHere(horse, waypointIndex, terminal);
    }
    return true;
  }

  private boolean technicalDepartureReady(AbstractHorse horse, int waypointIndex) {
    if (!mountedStable(horse)) {
      return false;
    }
    Vec3 velocity = horse.getDeltaMovement();
    double speedSq = velocity.x * velocity.x + velocity.z * velocity.z;
    return speedSq <= technicalDepartureSettledSpeedSq();
  }

  private boolean successorExecutableFromHere(AbstractHorse horse, int waypointIndex, boolean terminal) {
    if (terminal || !technicalHandoff(waypointIndex) || !microControlledDeparture(waypointIndex) || !technicalDepartureReady(horse, waypointIndex)) {
      return false;
    }
    HorsePath.Waypoint waypoint = path.waypoints().get(waypointIndex);
    BetterBlockPos feet = HorsePath.vehicleFeet(horse);
    if (feet.x != waypoint.pos().x || feet.y != waypoint.pos().y || feet.z != waypoint.pos().z) {
      return false;
    }
    double dx = horse.getX() - executionX(waypoint);
    double dz = horse.getZ() - executionZ(waypoint);
    if (dx * dx + dz * dz > technicalWaypointReachedSq()) {
      return false;
    }
    return physicalMicroTrackCandidate(horse, path.waypoints().get(waypointIndex + 1)) != null;
  }

  private boolean waypointEnvelopeReached(AbstractHorse horse, int waypointIndex, boolean terminal) {
    HorsePath.Waypoint waypoint = path.waypoints().get(waypointIndex);
    BetterBlockPos target = waypoint.pos();
    BetterBlockPos feet = HorsePath.vehicleFeet(horse);
    boolean stable = mountedStable(horse);
    if (waypointIndex > 0 && !stable) {
      return false;
    }
    if (!(waypoint.edgeFromPrevious() instanceof HorsePath.Source) && waypointIndex > 0) {
      if (feet.y != target.y) {
        return false;
      }
    }
    Vec3 pos = horse.position();
    double dx = pos.x - executionX(waypoint);
    double dz = pos.z - executionZ(waypoint);
    int yTolerance = terminal ? 2 : 1;
    double radiusSq = terminal ? terminalReachedSq() : technicalHandoff(waypointIndex) ? technicalWaypointReachedSq() : waypointReachedSq();
    boolean radiusReached = dx * dx + dz * dz <= radiusSq;
    boolean footprintReached = !radiusReached && !terminal && immediateFootprintPromotionReady(horse, waypointIndex, stable);
    if ((!radiusReached && !footprintReached) || Math.abs(feet.y - target.y) > yTolerance || routeFrontierWaypoint(waypointIndex) && (!stable || !frontierSettled(horse))) {
      return false;
    }
    return true;
  }

  private boolean immediateFootprintPromotionReady(AbstractHorse horse, int waypointIndex, boolean stable) {
    if (!stable || !physicallyOccupiesWaypoint(horse, path.waypoints().get(waypointIndex), physicalWaypointReachedSq())) {
      return false;
    }
    if (waypointIndex + 1 >= path.waypoints().size()) {
      return true;
    }
    return !microControlledDeparture(waypointIndex);
  }

  private boolean technicalHandoff(int waypointIndex) {
    if (waypointIndex <= 0) {
      return false;
    }
    HorsePath.Waypoint previous = path.waypoints().get(waypointIndex - 1);
    HorsePath.Waypoint current = path.waypoints().get(waypointIndex);
    if (requiresMicroTrack(previous, current)) {
      return true;
    }
    return false;
  }

  private boolean handoffEnvelopeReached(AbstractHorse horse, int waypointIndex) {
    HorsePath.Waypoint waypoint = path.waypoints().get(waypointIndex);
    BetterBlockPos target = waypoint.pos();
    BetterBlockPos feet = HorsePath.vehicleFeet(horse);
    double dx = horse.getX() - executionX(waypoint);
    double dz = horse.getZ() - executionZ(waypoint);
    return dx * dx + dz * dz <= waypointReachedSq() && Math.abs(feet.y - target.y) <= 1;
  }

  private static boolean mountedStable(AbstractHorse horse) {
    return horse.onGround() || surfaceFloating(horse);
  }

  private static boolean surfaceFloating(AbstractHorse horse) {
    return horse.isInWater() && !horse.isEyeInFluid(FluidTags.WATER) && horse.getDeltaMovement().y >= -0.03D;
  }

  private ControlFrame control(AbstractHorse horse) {
    HorsePath.Waypoint from = path.waypoints().get(waypoint);
    HorsePath.Waypoint target = path.waypoints().get(waypoint + 1);
    if (routeFrontierWaypoint(waypoint + 1) && frontierBrake(horse, target)) {
      return ControlFrame.EMPTY;
    }
    Vec3 targetPoint;
    MicroTrack activeTrack = null;
    if (failed) {
      return ControlFrame.EMPTY;
    } else if (target.edgeFromPrevious() instanceof HorsePath.Cruise) {
      resetMicroStage();
      targetPoint = center(target);
    } else if (!requiresMicroTrack(from, target) || horseTouchesWater(horse)) {
      resetMicroStage();
      targetPoint = relaxedTarget(horse, from, target);
    } else {
      MicroTrack track = microTrack(horse, from, target);
      if (track == null) {
        if (!mountedStable(horse)) {
          return ControlFrame.EMPTY;
        }
        if (physicalElevationRecovered(horse, target)) {
          resetMicroStage();
          targetPoint = center(target);
        } else {
          fail("no executable microtrack " + edgeDebug(waypoint, from, target) + " horse=(" + horse.getX() + "," + horse.getY() + "," + horse.getZ() + ")");
          return ControlFrame.EMPTY;
        }
      } else {
        activeTrack = track;
        targetPoint = microTarget(horse, from, target, track, technicalEdge(from, target));
      }
    }
    ControlFrame.Builder builder = ControlFrame.builder().setTarget(new ControlFrame.MovementTarget(movementRotation(targetPoint), true));
    applyRideInput(builder, horse, from, target, targetPoint, activeTrack);
    if (!(target.edgeFromPrevious() instanceof HorsePath.Cruise)) {
      applyStallRecovery(builder, horse, targetPoint);
    }
    return builder.build();
  }

  private void applyRideInput(ControlFrame.Builder builder, AbstractHorse horse, HorsePath.Waypoint from, HorsePath.Waypoint target, Vec3 targetPoint, MicroTrack activeTrack) {
    if (technicalEdge(from, target) && activeTrack != null) {
      applyTechnicalRideInput(builder, horse, targetPoint, activeTrack);
      return;
    }
    builder.setInput(Input.MOVE_FORWARD, true);
  }

  private void applyTechnicalRideInput(ControlFrame.Builder builder, AbstractHorse horse, Vec3 targetPoint, MicroTrack activeTrack) {
    if (!mountedStable(horse)) {
      return;
    }
    double dx = targetPoint.x - horse.getX();
    double dz = targetPoint.z - horse.getZ();
    double distance = Math.hypot(dx, dz);
    if (distance < 1.0E-6D) {
      return;
    }
    Vec3 velocity = horse.getDeltaMovement();
    double speedToTarget = (velocity.x * dx + velocity.z * dz) / distance;
    if (HorsePath.vehicleFeet(horse).y < activeTrack.toY()) {
      if (speedToTarget < technicalCruiseSpeed() && (distance > TECHNICAL_EDGE_MIN_APPROACH_BLOCKS || speedToTarget < -0.02D)) {
        builder.setInput(Input.MOVE_FORWARD, true);
      }
      return;
    }
    double maxSpeed = technicalApproachSpeed(distance, activeTrack.lateralDistanceSq(horse.getX(), horse.getZ()));
    if (speedToTarget > maxSpeed) {
      if (HorsePath.vehicleFeet(horse).y <= activeTrack.toY() && (distance <= technicalFinalApproachBlocks() || speedToTarget >= technicalBrakeSpeed())) {
        builder.setInput(Input.MOVE_BACK, true);
      }
      return;
    }
    if (distance > TECHNICAL_EDGE_MIN_APPROACH_BLOCKS || speedToTarget < -0.02D) {
      builder.setInput(Input.MOVE_FORWARD, true);
    }
  }

  private static boolean technicalEdge(HorsePath.Waypoint from, HorsePath.Waypoint target) {
    return requiresMicroTrack(from, target);
  }

  private static double technicalApproachSpeed(double targetDistance, double lateralDistanceSq) {
    double t = Math.max(0D, Math.min(1D, targetDistance / technicalFinalApproachBlocks()));
    double speed = technicalFinalSpeed() + (technicalCruiseSpeed() - technicalFinalSpeed()) * t;
    return lateralDistanceSq > 0.25D ? speed * 0.65D : speed;
  }

  private Rotation movementRotation(Vec3 targetPoint) {
    return new Rotation(RotationUtils.calcRotationFromVec3d(behavior.ctx.playerHead(), targetPoint, behavior.ctx.playerRotations()).getYaw(), 0F);
  }

  private boolean frontierBrake(AbstractHorse horse, HorsePath.Waypoint target) {
    BetterBlockPos feet = HorsePath.vehicleFeet(horse);
    if (Math.abs(feet.y - target.pos().y) > 1 || frontierSettled(horse)) {
      return false;
    }
    double dx = horse.getX() - executionX(target);
    double dz = horse.getZ() - executionZ(target);
    return dx * dx + dz * dz <= TERMINAL_BRAKE_RADIUS_SQ;
  }

  private boolean frontierSettled(AbstractHorse horse) {
    if (horse.getDeltaMovement().horizontalDistanceSqr() > terminalMaxHorizontalSpeedSq()) {
      return false;
    }
    return !Double.isFinite(lastX) || distanceSq(horse.getX(), horse.getZ(), lastX, lastZ) <= terminalMaxHorizontalSpeedSq();
  }

  private boolean microControlledDeparture(int waypointIndex) {
    if (waypointIndex + 1 >= path.waypoints().size()) {
      return false;
    }
    HorsePath.Waypoint current = path.waypoints().get(waypointIndex);
    HorsePath.Waypoint next = path.waypoints().get(waypointIndex + 1);
    return requiresMicroTrack(current, next);
  }

  private static boolean requiresMicroTrack(HorsePath.Waypoint from, HorsePath.Waypoint to) {
    return to.edgeFromPrevious() instanceof HorsePath.Template && to.trackFromPrevious() != null && !longSpan(from, to) && !majorDrop(from, to);
  }

  private static boolean physicalElevationRecovered(AbstractHorse horse, HorsePath.Waypoint target) {
    BetterBlockPos feet = HorsePath.vehicleFeet(horse);
    if (Math.abs(feet.y - target.pos().y) > 1) {
      return false;
    }
    double dx = horse.getX() - executionX(target);
    double dz = horse.getZ() - executionZ(target);
    return dx * dx + dz * dz <= MICRO_PHYSICAL_RECOVERY_MAX_SQ;
  }

  private void applyStallRecovery(ControlFrame.Builder builder, AbstractHorse horse, Vec3 targetPoint) {
    if (!STALL_RECOVERY_ENABLED || stalledTicks < RECOVERY_STALL_TICKS || horseTouchesWater(horse) || !mountedStable(horse)) {
      clearRecovery();
      return;
    }
    RecoveryProbe probe = recoveryProbe(horse, targetPoint);
    int side = recoverySide(probe);
    if (side == 0) {
      clearRecovery();
      return;
    }
    recoveryEdge = waypoint;
    recoverySide = side;
    recoveryHoldTicks = Math.max(recoveryHoldTicks, RECOVERY_SIDE_HOLD_TICKS);
    builder.setInput(side < 0 ? Input.MOVE_LEFT : Input.MOVE_RIGHT, true);
  }

  private int recoverySide(RecoveryProbe probe) {
    if (recoveryEdge == waypoint && recoveryHoldTicks > 0 && recoverySide != 0) {
      recoveryHoldTicks--;
      if (recoverySide < 0 && probe.leftGain() >= probe.forwardGain() + RECOVERY_MIN_FORWARD_GAIN || recoverySide > 0 && probe.rightGain() >= probe.forwardGain() + RECOVERY_MIN_FORWARD_GAIN) {
        return recoverySide;
      }
    }
    double leftAdvantage = probe.leftGain() - Math.max(probe.forwardGain(), probe.rightGain());
    double rightAdvantage = probe.rightGain() - Math.max(probe.forwardGain(), probe.leftGain());
    if (leftAdvantage > RECOVERY_SIDE_SWITCH_GAIN) {
      return -1;
    }
    if (rightAdvantage > RECOVERY_SIDE_SWITCH_GAIN) {
      return 1;
    }
    if (probe.leftGain() <= probe.forwardGain() + RECOVERY_MIN_FORWARD_GAIN && probe.rightGain() <= probe.forwardGain() + RECOVERY_MIN_FORWARD_GAIN) {
      return 0;
    }
    return probe.leftGain() >= probe.rightGain() ? -1 : 1;
  }

  private RecoveryProbe recoveryProbe(AbstractHorse horse, Vec3 targetPoint) {
    double tx = targetPoint.x - horse.getX();
    double tz = targetPoint.z - horse.getZ();
    double length = Math.hypot(tx, tz);
    if (length < 1.0E-6D) {
      return RecoveryProbe.ZERO;
    }
    double ix = tx / length;
    double iz = tz / length;
    float yaw = RotationUtils.calcRotationFromVec3d(behavior.ctx.playerHead(), targetPoint, behavior.ctx.playerRotations()).getYaw();
    double radians = yaw * Mth.DEG_TO_RAD;
    double fx = Mth.sin((float) radians);
    double fz = Mth.cos((float) radians);
    double lx = -fz;
    double lz = fx;
    return new RecoveryProbe(recoveryGain(horse, fx, fz, ix, iz), recoveryGain(horse, fx + lx, fz + lz, ix, iz), recoveryGain(horse, fx - lx, fz - lz, ix, iz));
  }

  private double recoveryGain(AbstractHorse horse, double dx, double dz, double intendedX, double intendedZ) {
    double length = Math.hypot(dx, dz);
    if (length < 1.0E-9D) {
      return Double.NEGATIVE_INFINITY;
    }
    double scale = RECOVERY_PROBE_BLOCKS / length;
    int feetY = HorsePath.vehicleFeet(horse).y;
    HorseCollisionOracle oracle = oracle(horse, 0D);
    HorseCollisionOracle.Move move = oracle.clippedStep(horse.getX(), feetY, horse.getZ(), dx * scale, dz * scale, true);
    int landingY = Mth.floor(feetY + move.dy() + 0.2D);
    if (!oracle.standable(horse.getX() + move.dx(), landingY, horse.getZ() + move.dz())) {
      return Double.NEGATIVE_INFINITY;
    }
    return move.dx() * intendedX + move.dz() * intendedZ;
  }

  private void clearRecovery() {
    recoveryEdge = -1;
    recoverySide = 0;
    recoveryHoldTicks = 0;
  }

  private static boolean longSpan(HorsePath.Waypoint from, HorsePath.Waypoint to) {
    int dx = to.pos().x - from.pos().x;
    int dz = to.pos().z - from.pos().z;
    return dx * dx + dz * dz > 2;
  }

  private static boolean majorDrop(HorsePath.Waypoint from, HorsePath.Waypoint to) {
    return from.pos().y - to.pos().y > 1;
  }

  private static Vec3 center(HorsePath.Waypoint waypoint) {
    return new Vec3(waypoint.centerX(), waypoint.pos().y + 0.5D, waypoint.centerZ());
  }

  private static double executionX(HorsePath.Waypoint waypoint) {
    HorsePath.Track track = waypoint.trackFromPrevious();
    return track == null || track.recenter() ? waypoint.centerX() : track.endX();
  }

  private static double executionZ(HorsePath.Waypoint waypoint) {
    HorsePath.Track track = waypoint.trackFromPrevious();
    return track == null || track.recenter() ? waypoint.centerZ() : track.endZ();
  }

  private static Vec3 relaxedTarget(AbstractHorse horse, HorsePath.Waypoint from, HorsePath.Waypoint to) {
    double sx = from.centerX();
    double sz = from.centerZ();
    double tx = to.centerX();
    double tz = to.centerZ();
    double dx = tx - sx;
    double dz = tz - sz;
    double lengthSq = dx * dx + dz * dz;
    if (lengthSq < 1.0E-9D) {
      return center(to);
    }
    double length = Math.sqrt(lengthSq);
    double progress = ((horse.getX() - sx) * dx + (horse.getZ() - sz) * dz) / lengthSq;
    double aim = Math.max(0D, Math.min(1D, progress + relaxedLookaheadBlocks() / length));
    if ((1D - progress) * length <= relaxedLookaheadBlocks() * 1.25D) {
      aim = 1D;
    }
    double x = sx + dx * aim;
    double z = sz + dz * aim;
    double y = from.pos().y + (to.pos().y - from.pos().y) * aim + 0.5D;
    return new Vec3(x, y, z);
  }

  private Vec3 microTarget(AbstractHorse horse, HorsePath.Waypoint from, HorsePath.Waypoint to, MicroTrack track, boolean technical) {
    if (!track.stage()) {
      microStageEdge = -1;
      microStageSatisfied = false;
      return recenterOrPursuitTarget(horse, to, track, technical);
    }
    if (microStageEdge != waypoint) {
      microStageEdge = waypoint;
      microStageSatisfied = false;
    }
    if (!microStageSatisfied && (distanceSq(horse.getX(), horse.getZ(), track.sx(), track.sz()) <= MICRO_STAGE_REACHED_SQ
      || track.progressBlocks(horse.getX(), horse.getZ()) >= MICRO_STAGE_PASSED_BLOCKS && track.lateralDistanceSq(horse.getX(), horse.getZ()) <= MICRO_STAGE_PASSED_LATERAL_SQ)) {
      microStageSatisfied = true;
    }
    if (!microStageSatisfied) {
      if (track.progressBlocks(horse.getX(), horse.getZ()) >= MICRO_STAGE_PASSED_BLOCKS) {
        MicroTrack physical = physicalMicroTrackCandidate(horse, to);
        if (physical != null) {
          microTrackEdge = waypoint;
          microTrack = physical;
          microStageSatisfied = true;
          return recenterOrPursuitTarget(horse, to, physical, technical);
        }
        fail("missed staged horse track " + edgeDebug(waypoint, from, to) + " horse=(" + horse.getX() + "," + horse.getY() + "," + horse.getZ() + ")");
        return center(to);
      }
      return new Vec3(track.sx(), track.fromY() + 0.5D, track.sz());
    }
    return recenterOrPursuitTarget(horse, to, track, technical);
  }

  private Vec3 recenterOrPursuitTarget(AbstractHorse horse, HorsePath.Waypoint to, MicroTrack track, boolean technical) {
    if (!track.recenter()) {
      return pursuitTarget(horse, track, technical);
    }
    if (microRecenterEdge != waypoint) {
      microRecenterEdge = waypoint;
      microRecenterActive = false;
    }
    if (!microRecenterActive && trackEndpointReached(horse, track)) {
      microRecenterActive = true;
    }
    return microRecenterActive ? center(to) : pursuitTarget(horse, track, technical);
  }

  private static boolean trackEndpointReached(AbstractHorse horse, MicroTrack track) {
    double dx = horse.getX() - track.tx();
    double dz = horse.getZ() - track.tz();
    return dx * dx + dz * dz <= MICRO_STAGE_REACHED_SQ
      || track.length() - track.progressBlocks(horse.getX(), horse.getZ()) <= MICRO_STAGE_PASSED_BLOCKS && track.lateralDistanceSq(horse.getX(), horse.getZ()) <= MICRO_STAGE_PASSED_LATERAL_SQ;
  }

  private static Vec3 pursuitTarget(AbstractHorse horse, MicroTrack track, boolean technical) {
    double length = track.length();
    if (length < 1.0E-6D) {
      return new Vec3(track.tx(), track.toY() + 0.5D, track.tz());
    }
    double progress = Math.max(0D, Math.min(length, track.progressBlocks(horse.getX(), horse.getZ())));
    double lookahead =
      technical ? Math.min(technicalLookaheadBlocks(), Math.max(TECHNICAL_EDGE_MIN_LOOKAHEAD_BLOCKS, length * 0.35D)) : Math.min(relaxedLookaheadBlocks(), Math.max(1.0D, length * 0.55D));
    double aim = Math.min(length, progress + lookahead);
    if (length - progress <= (technical ? TECHNICAL_EDGE_ENDPOINT_BLOCKS : 1.25D)) {
      aim = length;
    }
    double t = aim / length;
    return new Vec3(track.xAt(aim), Mth.lerp(t, track.fromY(), track.toY()) + 0.5D, track.zAt(aim));
  }

  private MicroTrack microTrack(AbstractHorse horse, HorsePath.Waypoint from, HorsePath.Waypoint to) {
    HorsePath.Track hint = to.trackFromPrevious();
    if (!atWaypointFeet(horse, from)) {
      MicroTrack active = microTrackEdge == waypoint && microTrack != null ? microTrack : hint == null ? null : MicroTrack.of(hint, from.pos().y, to.pos().y);
      if (active != null && activeTrackFollowable(horse, active)) {
        microTrackEdge = waypoint;
        microTrack = active;
        return active;
      }
      MicroTrack physical = physicalMicroTrackCandidate(horse, to);
      microTrackEdge = waypoint;
      microTrack = physical;
      return physical;
    }
    if (hint != null) {
      MicroTrack hinted = microTrackEdge == waypoint && microTrack != null ? microTrack : MicroTrack.of(hint, from.pos().y, to.pos().y);
      if (hintUsable(horse, hinted, microStageSatisfied)) {
        microTrackEdge = waypoint;
        microTrack = hinted;
        return hinted;
      }
      MicroTrack replacement = microTrackCandidateWithoutHint(horse, from, to, microStageSatisfied);
      microTrackEdge = waypoint;
      microTrack = replacement;
      return replacement;
    }
    if (microTrackEdge == waypoint && microTrack != null && trackClear(horse, microTrack, 0D) && (!microTrack.stage() || microStageSatisfied || entryClear(horse, microTrack, 0D))) {
      return microTrack;
    }
    MicroTrack best = microTrackCandidate(horse, from, to, microStageSatisfied);
    microTrackEdge = waypoint;
    microTrack = best;
    return best;
  }

  private MicroTrack microTrackCandidate(AbstractHorse horse, HorsePath.Waypoint from, HorsePath.Waypoint to, boolean stageSatisfied) {
    HorsePath.Track hint = to.trackFromPrevious();
    if (hint != null) {
      MicroTrack hinted = MicroTrack.of(hint, from.pos().y, to.pos().y);
      return hintUsable(horse, hinted, stageSatisfied) ? hinted : microTrackCandidateWithoutHint(horse, from, to, stageSatisfied);
    }
    return microTrackCandidateWithoutHint(horse, from, to, stageSatisfied);
  }

  private MicroTrack microTrackCandidateWithoutHint(AbstractHorse horse, HorsePath.Waypoint from, HorsePath.Waypoint to, boolean stageSatisfied) {
    double sx = from.centerX();
    double sz = from.centerZ();
    double tx = to.centerX();
    double tz = to.centerZ();
    double dx = tx - sx;
    double dz = tz - sz;
    double length = Math.hypot(dx, dz);
    if (length < 1.0E-6D) {
      return null;
    }
    double px = -dz / length;
    double pz = dx / length;
    MicroTrack best = null;
    boolean bestMargin = false;
    double bestScore = Double.POSITIVE_INFINITY;
    for (int i = 0; i < HorseTrackLattice.offsetCount(); i++) {
      double offset = HorseTrackLattice.offset(i);
      double trackStartX = sx + px * offset;
      double trackStartZ = sz + pz * offset;
      double trackEndX = tx + px * offset;
      double trackEndZ = tz + pz * offset;
      if (Mth.floor(trackStartX) != from.pos().x || Mth.floor(trackStartZ) != from.pos().z || Mth.floor(trackEndX) != to.pos().x || Mth.floor(trackEndZ) != to.pos().z) {
        continue;
      }
      MicroTrack candidate = new MicroTrack(trackStartX, trackStartZ, trackEndX, trackEndZ, offset, to.pos().y > from.pos().y, false, from.pos().y, to.pos().y);
      if (!trackClear(horse, candidate, 0D)) {
        continue;
      }
      if (candidate.stage() && !stageSatisfied && !entryClear(horse, candidate, 0D)) {
        continue;
      }
      boolean marginClear = trackClear(horse, candidate, MICRO_CLEARANCE_MARGIN);
      double score = candidateScore(horse, candidate, marginClear);
      if (best == null || candidateBetter(marginClear, score, bestMargin, bestScore)) {
        best = candidate;
        bestMargin = marginClear;
        bestScore = score;
      }
    }
    return best == null ? physicalMicroTrackCandidate(horse, to) : best;
  }

  private static boolean atWaypointFeet(AbstractHorse horse, HorsePath.Waypoint waypoint) {
    BetterBlockPos feet = HorsePath.vehicleFeet(horse);
    return feet.x == waypoint.pos().x && feet.y == waypoint.pos().y && feet.z == waypoint.pos().z;
  }

  private boolean activeTrackFollowable(AbstractHorse horse, MicroTrack track) {
    int feetY = HorsePath.vehicleFeet(horse).y;
    if (feetY < Math.min(track.fromY(), track.toY()) - 1 || feetY > Math.max(track.fromY(), track.toY()) + 1) {
      return false;
    }
    double progress = track.progressBlocks(horse.getX(), horse.getZ());
    if (progress < -MICRO_TRACK_FOLLOW_BEHIND_BLOCKS || progress > track.length() + MICRO_TRACK_FOLLOW_AHEAD_BLOCKS) {
      return false;
    }
    return track.lateralDistanceSq(horse.getX(), horse.getZ()) <= MICRO_TRACK_FOLLOW_LATERAL_SQ && (trackClear(horse, track, 0D) || physicalTrackClear(horse, track, 0D));
  }

  private MicroTrack physicalMicroTrackCandidate(AbstractHorse horse, HorsePath.Waypoint to) {
    int fromY = HorsePath.vehicleFeet(horse).y;
    int toY = executableTargetY(horse, to, fromY);
    double tx = executionX(to);
    double tz = executionZ(to);
    if (distanceSq(horse.getX(), horse.getZ(), tx, tz) > MICRO_PHYSICAL_RECOVERY_MAX_SQ) {
      return null;
    }
    double dx = tx - horse.getX();
    double dz = tz - horse.getZ();
    double length = Math.hypot(dx, dz);
    if (length < 1.0E-6D) {
      return null;
    }
    double px = -dz / length;
    double pz = dx / length;
    MicroTrack best = null;
    boolean bestMargin = false;
    double bestScore = Double.POSITIVE_INFINITY;
    for (int i = 0; i < HorseTrackLattice.offsetCount(); i++) {
      double offset = HorseTrackLattice.offset(i);
      double ex = tx + px * offset;
      double ez = tz + pz * offset;
      if (Mth.floor(ex) != to.pos().x || Mth.floor(ez) != to.pos().z) {
        continue;
      }
      MicroTrack candidate = new MicroTrack(horse.getX(), horse.getZ(), ex, ez, offset, false, false, fromY, toY);
      if (!physicalTrackClear(horse, candidate, 0D) && !uphillClippedStepClear(horse, candidate, 0D)) {
        continue;
      }
      boolean marginClear = physicalTrackClear(horse, candidate, MICRO_CLEARANCE_MARGIN);
      double score = candidateScore(horse, candidate, marginClear);
      if (best == null || candidateBetter(marginClear, score, bestMargin, bestScore)) {
        best = candidate;
        bestMargin = marginClear;
        bestScore = score;
      }
    }
    return best;
  }

  private int executableTargetY(AbstractHorse horse, HorsePath.Waypoint to, int fromY) {
    int nominalY = to.pos().y;
    int stepRiseBlocks = Math.max(1, Math.min(2, Mth.floor(horse.getAttributeValue(Attributes.STEP_HEIGHT) + 0.05D)));
    if (fromY > nominalY && fromY - nominalY <= stepRiseBlocks && oracle(horse, 0D).balancedStandable(executionX(to), fromY, executionZ(to))) {
      return fromY;
    }
    return nominalY;
  }

  private boolean hintUsable(AbstractHorse horse, MicroTrack hinted, boolean stageSatisfied) {
    boolean ordinary = trackClear(horse, hinted, 0D);
    boolean physical = !ordinary && physicalHintUsable(horse, hinted);
    if (!ordinary && !physical) {
      return false;
    }
    if (hinted.stage()) {
      return stageSatisfied || physical || entryClear(horse, hinted, 0D);
    }
    return physical || trackApproachable(horse, hinted);
  }

  private boolean physicalHintUsable(AbstractHorse horse, MicroTrack track) {
    int feetY = HorsePath.vehicleFeet(horse).y;
    if (Math.abs(feetY - track.fromY()) > 1) {
      return false;
    }
    double progress = track.progressBlocks(horse.getX(), horse.getZ());
    return progress >= -MICRO_TRACK_FOLLOW_BEHIND_BLOCKS && progress <= MICRO_TRACK_FOLLOW_AHEAD_BLOCKS && track.lateralDistanceSq(horse.getX(), horse.getZ()) <= HINT_APPROACH_RADIUS_SQ
      && physicalTrackClear(horse, track, 0D);
  }

  private boolean trackApproachable(AbstractHorse horse, MicroTrack track) {
    int feetY = HorsePath.vehicleFeet(horse).y;
    if (Math.abs(feetY - track.fromY()) > 1) {
      return false;
    }
    double progress = Math.max(0D, Math.min(track.length(), track.progressBlocks(horse.getX(), horse.getZ())));
    double x = track.xAt(progress);
    double z = track.zAt(progress);
    if (distanceSq(horse.getX(), horse.getZ(), x, z) > HINT_APPROACH_RADIUS_SQ) {
      return false;
    }
    HorseCollisionOracle oracle = oracle(horse, 0D);
    return oracle.clearSegment(horse.getX(), feetY, horse.getZ(), x, track.fromY(), z) && oracle.standable(x, track.fromY(), z);
  }

  private static double candidateScore(AbstractHorse horse, MicroTrack candidate, boolean marginClear) {
    double sourceDistance = distanceSq(horse.getX(), horse.getZ(), candidate.sx(), candidate.sz());
    double lateral = candidate.lateralDistanceSq(horse.getX(), horse.getZ());
    return (marginClear ? -4D : 0D) + lateral + sourceDistance * 0.15D + Math.abs(candidate.offset()) * 0.05D;
  }

  private static boolean candidateBetter(boolean candidateMargin, double candidateScore, boolean incumbentMargin, double incumbentScore) {
    if (candidateMargin != incumbentMargin) {
      return candidateMargin;
    }
    return candidateScore < incumbentScore;
  }

  private boolean trackClear(AbstractHorse horse, MicroTrack track, double margin) {
    if (track.toY() > track.fromY() && !uphillClippedStepClear(horse, track, margin)) {
      return false;
    }
    HorseCollisionOracle oracle = oracle(horse, margin);
    int stepRiseBlocks = Math.max(1, Math.min(2, Mth.floor(horse.getAttributeValue(Attributes.STEP_HEIGHT) + 0.05D)));
    return track.toY() > track.fromY() ? HorseTrackCertifier.certifyHull(oracle, track.sx(), track.fromY(), track.sz(), track.tx(), track.toY(), track.tz(), 1D, stepRiseBlocks, 1D, 4) != null
      : HorseTrackCertifier.certifyBalanced(oracle, track.sx(), track.fromY(), track.sz(), track.tx(), track.toY(), track.tz(), 1D, stepRiseBlocks, 1D, 4) != null;
  }

  private boolean physicalTrackClear(AbstractHorse horse, MicroTrack track, double margin) {
    if (!mountedStable(horse)) {
      return false;
    }
    if (track.toY() > track.fromY() && !uphillClippedStepClear(horse, track, margin)) {
      return false;
    }
    HorseCollisionOracle oracle = oracle(horse, margin);
    int stepRiseBlocks = Math.max(1, Math.min(2, Mth.floor(horse.getAttributeValue(Attributes.STEP_HEIGHT) + 0.05D)));
    return track.toY() > track.fromY()
      ? HorseTrackCertifier.certifyHullFromStableSource(oracle, track.sx(), track.fromY(), track.sz(), track.tx(), track.toY(), track.tz(), 1D, stepRiseBlocks, 1D, 4) != null
      : HorseTrackCertifier.certifyBalancedFromStableSource(oracle, track.sx(), track.fromY(), track.sz(), track.tx(), track.toY(), track.tz(), 1D, stepRiseBlocks, 1D, 4) != null;
  }

  private boolean uphillClippedStepClear(AbstractHorse horse, MicroTrack track, double margin) {
    int rise = track.toY() - track.fromY();
    int stepRiseBlocks = Math.max(1, Math.min(2, Mth.floor(horse.getAttributeValue(Attributes.STEP_HEIGHT) + 0.05D)));
    if (rise <= 0 || rise > stepRiseBlocks || horseTouchesWater(horse) || !adjacentColumns(track)) {
      return false;
    }
    HorseCollisionOracle oracle = oracle(horse, margin);
    double dx = track.tx() - track.sx();
    double dz = track.tz() - track.sz();
    HorseCollisionOracle.Move move = oracle.clippedStep(track.sx(), track.fromY(), track.sz(), dx, dz, true);
    return move.reached(dx, dz) && Math.abs(move.dy() - rise) < 0.2D && oracle.standable(track.tx(), track.toY(), track.tz());
  }

  private static boolean adjacentColumns(MicroTrack track) {
    return Math.abs(Mth.floor(track.tx()) - Mth.floor(track.sx())) <= 1 && Math.abs(Mth.floor(track.tz()) - Mth.floor(track.sz())) <= 1;
  }

  private boolean entryClear(AbstractHorse horse, MicroTrack track, double margin) {
    int feetY = HorsePath.vehicleFeet(horse).y;
    if (Math.abs(feetY - track.fromY()) > 1) {
      return false;
    }
    HorseCollisionOracle oracle = oracle(horse, margin);
    int stepRiseBlocks = Math.max(1, Math.min(2, Mth.floor(horse.getAttributeValue(Attributes.STEP_HEIGHT) + 0.05D)));
    return HorseTrackCertifier.certifyHullFromStableSource(oracle, horse.getX(), feetY, horse.getZ(), track.sx(), track.fromY(), track.sz(), 1D, stepRiseBlocks, 1D, 4) != null;
  }

  private HorseCollisionOracle oracle(AbstractHorse horse, double margin) {
    double halfWidth = horse.getBbWidth() * 0.5D + margin;
    double height = horse.getBbHeight();
    double stepHeight = horse.getAttributeValue(Attributes.STEP_HEIGHT);
    if (oracle == null || oracleHalfWidth != halfWidth || oracleHeight != height || oracleStepHeight != stepHeight) {
      oracle = HorseCollisionOracle.live(behavior.ctx.world(), halfWidth, height, stepHeight);
      oracleHalfWidth = halfWidth;
      oracleHeight = height;
      oracleStepHeight = stepHeight;
    }
    return oracle;
  }

  private record MicroTrack(double sx, double sz, double tx, double tz, double offset, boolean stage, boolean recenter, int fromY, int toY) {
    private static MicroTrack of(HorsePath.Track track, int fromY, int toY) {
      return new MicroTrack(track.startX(), track.startZ(), track.endX(), track.endZ(), 0D, track.staged(), track.recenter(), fromY, toY);
    }

    double progressBlocks(double x, double z) {
      double dx = tx - sx;
      double dz = tz - sz;
      double length = Math.hypot(dx, dz);
      return length < 1.0E-6D ? 0D : ((x - sx) * dx + (z - sz) * dz) / length;
    }

    double lateralDistanceSq(double x, double z) {
      double dx = tx - sx;
      double dz = tz - sz;
      double lengthSq = dx * dx + dz * dz;
      if (lengthSq < 1.0E-9D) {
        return distanceSq(x, z, sx, sz);
      }
      double t = Math.max(0D, Math.min(1D, ((x - sx) * dx + (z - sz) * dz) / lengthSq));
      double px = sx + dx * t;
      double pz = sz + dz * t;
      return distanceSq(x, z, px, pz);
    }

    double length() {
      return Math.hypot(tx - sx, tz - sz);
    }

    double xAt(double blocks) {
      double length = length();
      return length < 1.0E-6D ? tx : sx + (tx - sx) * Math.max(0D, Math.min(1D, blocks / length));
    }

    double zAt(double blocks) {
      double length = length();
      return length < 1.0E-6D ? tz : sz + (tz - sz) * Math.max(0D, Math.min(1D, blocks / length));
    }
  }

  private void resetMicroStage() {
    microStageEdge = -1;
    microStageSatisfied = false;
    microRecenterEdge = -1;
    microRecenterActive = false;
    microTrackEdge = -1;
    microTrack = null;
    clearRecovery();
  }

  private record RecoveryProbe(double forwardGain, double leftGain, double rightGain) {
    private static final RecoveryProbe ZERO = new RecoveryProbe(0D, 0D, 0D);
  }

  private void checkStall(AbstractHorse horse) {
    if (!mountedStable(horse) || waypoint + 1 >= path.waypoints().size()) {
      resetStall(horse);
      return;
    }
    HorsePath.Waypoint target = path.waypoints().get(waypoint + 1);
    double dx = horse.getX() - target.centerX();
    double dz = horse.getZ() - target.centerZ();
    double distanceSq = dx * dx + dz * dz;
    double movedSq = Double.isFinite(lastX) ? distanceSq(horse.getX(), horse.getZ(), lastX, lastZ) : Double.POSITIVE_INFINITY;
    if (distanceSq + stallProgressEpsilonSq() < bestTargetDistanceSq || movedSq > STALL_RESET_DISTANCE_SQ) {
      bestTargetDistanceSq = distanceSq;
      stalledTicks = 0;
    } else if (++stalledTicks >= controllerTuning().stallGraceTicks()) {
      HorsePath.Waypoint from = path.waypoints().get(waypoint);
      fail("stalled riding to " + edgeDebug(waypoint, from, target) + " at horse=(" + horse.getX() + "," + horse.getY() + "," + horse.getZ() + ")");
    }
    lastX = horse.getX();
    lastZ = horse.getZ();
  }

  private void resetStall(AbstractHorse horse) {
    lastX = horse.getX();
    lastZ = horse.getZ();
    bestTargetDistanceSq = Double.POSITIVE_INFINITY;
    stalledTicks = 0;
  }

  private static double distanceSq(double ax, double az, double bx, double bz) {
    double dx = ax - bx;
    double dz = az - bz;
    return dx * dx + dz * dz;
  }

  private boolean horseTouchesWater(AbstractHorse horse) {
    if (horse.isInWater()) {
      return true;
    }
    AABB box = horse.getBoundingBox();
    int minX = Mth.floor(box.minX + 1.0E-4D);
    int maxX = Mth.floor(box.maxX - 1.0E-4D);
    int minY = Mth.floor(box.minY - 0.95D);
    int maxY = Mth.floor(box.maxY - 1.0E-4D);
    int minZ = Mth.floor(box.minZ + 1.0E-4D);
    int maxZ = Mth.floor(box.maxZ - 1.0E-4D);
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    for (int x = minX; x <= maxX; x++) {
      for (int y = minY; y <= maxY; y++) {
        for (int z = minZ; z <= maxZ; z++) {
          if (behavior.ctx.world().getFluidState(pos.set(x, y, z)).is(FluidTags.WATER)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private AbstractHorse horse() {
    Entity vehicle = behavior.ctx.player().getVehicle();
    return vehicle instanceof AbstractHorse horse ? horse : null;
  }

  @Override
  public boolean failed() {
    return failed;
  }

  @Override
  public String failureReason() {
    return failed ? failureReason == null ? "horse route failed" : failureReason : null;
  }

  private void fail(String reason) {
    failed = true;
    failureReason = reason;
  }

  @Override
  public boolean finished() {
    return finished;
  }

  @Override
  public int getPosition() { return waypoint; }

  @Override
  public int size() {
    return path.edgeCount();
  }

  @Override
  public ControlFrame controlFrame() {
    return frame;
  }

  @Override
  public TransportControl transportControl() {
    Optional<Rotation> rotation = frame.target().getRotation();
    return new TransportControl("HorseRoute", failed ? MovementStatus.UNREACHABLE : finished ? MovementStatus.SUCCESS : MovementStatus.RUNNING, rotation.map(Rotation::getYaw).orElse(null),
      rotation.map(Rotation::getPitch).orElse(null), frame.target().hasToForceRotations());
  }

  @Override
  public TransportSnapshot.Plan transportPlan(baritone.api.utils.IPlayerContext ctx) {
    String phase = "RIDE";
    double progress = path.edgeCount() == 0 ? 1D : Math.min(1D, waypoint / (double) path.edgeCount());
    HorsePath.Waypoint from = path.waypoints().get(Math.min(waypoint, path.waypoints().size() - 1));
    HorsePath.Waypoint to = path.waypoints().get(Math.min(waypoint + 1, path.waypoints().size() - 1));
    String movement = "HorseRoute:" + to.edgeDebugName();
    return TransportSnapshot.Plan.transport(TransportMode.HORSE, movement, from.pos(), to.pos(), phase, terminalGoalWaypoint(Math.min(waypoint + 1, path.waypoints().size() - 1)), null, progress)
      .withRoute(-1, leg.exitState());
  }

  @Override
  public double estimatedTicksRemaining(baritone.pathing.route.RouteLeg leg) {
    return path.estimatedTicksRemaining(waypoint);
  }

  @Override
  public boolean containsPathPosition(BlockPos pos) {
    return leg.contains(pos);
  }

  @Override
  public Set<BlockPos> toBreak() {
    return Collections.emptySet();
  }

  @Override
  public Set<BlockPos> toPlace() {
    return Collections.emptySet();
  }

  @Override
  public Set<BlockPos> toWalkInto() {
    return Collections.emptySet();
  }

  @Override
  public boolean isSprinting() { return false; }
}
