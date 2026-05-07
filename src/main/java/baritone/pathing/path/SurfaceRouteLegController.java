package baritone.pathing.path;

import baritone.Baritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.pathing.control.ControlFrame;
import baritone.pathing.route.BoatRecoveryPolicy;
import baritone.pathing.route.LegTickResult;
import baritone.pathing.route.RouteLeg;
import baritone.pathing.route.SurfaceLineController;
import baritone.pathing.route.SurfaceLinePhase;
import baritone.pathing.route.SurfaceRouteLeg;
import baritone.pathing.transport.TransportControl;
import baritone.pathing.transport.TransportSnapshot;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;

final class SurfaceRouteLegController implements RouteLegController {
  private final PathingBehavior behavior;
  private final SurfaceRouteLeg leg;
  private final SurfaceLineController surfaceLine;
  private boolean failed;
  private boolean finished;
  private boolean sprintNextTick;
  private ControlFrame controlFrame = ControlFrame.EMPTY;
  private TransportControl transportControl;

  SurfaceRouteLegController(PathingBehavior behavior, SurfaceRouteLeg leg) {
    this.behavior = behavior;
    this.leg = leg;
    this.surfaceLine = new SurfaceLineController(behavior.baritone, leg.segment());
  }

  @Override
  public boolean onTick() {
    controlFrame = ControlFrame.EMPTY;
    sprintNextTick = false;
    LegTickResult<SurfaceLinePhase> tick = surfaceLine.tick();
    controlFrame = tick.frame();
    transportControl = transportControl("SurfaceLineController", tick.status(), controlFrame);
    sprintNextTick = surfaceLineSprintRequested();
    if (tick.status() == MovementStatus.UNREACHABLE || tick.status() == MovementStatus.FAILED) {
      failed = true;
      finished = true;
      return true;
    }
    if (tick.status() == MovementStatus.SUCCESS) {
      if (leg.recoveryPolicy() == BoatRecoveryPolicy.REQUIRED && leg.segment().boat() && leg.segment().terminal() && !((Baritone) behavior.baritone).getInventoryBehavior().hasBoat()) {
        failed = true;
      }
      finished = true;
      controlFrame = ControlFrame.EMPTY;
      return true;
    }
    return tick.safeToCancel();
  }

  private boolean surfaceLineSprintRequested() {
    return controlFrame.input(Input.SPRINT) && ((Baritone) behavior.baritone).getInventoryBehavior() != null
      && new baritone.pathing.movement.CalculationContext(behavior.baritone, false).movement.canSprint();
  }

  private static TransportControl transportControl(String movement, MovementStatus status, ControlFrame frame) {
    Optional<Rotation> rotation = frame.target().getRotation();
    return new TransportControl(movement, status, rotation.map(Rotation::getYaw).orElse(null), rotation.map(Rotation::getPitch).orElse(null), frame.target().hasToForceRotations());
  }

  @Override
  public boolean failed() {
    return failed;
  }

  @Override
  public boolean finished() {
    return finished;
  }

  @Override
  public int getPosition() { return finished ? 1 : 0; }

  @Override
  public int size() {
    return 1;
  }

  @Override
  public ControlFrame controlFrame() {
    return controlFrame;
  }

  @Override
  public TransportControl transportControl() {
    return transportControl;
  }

  @Override
  public TransportSnapshot.Plan transportPlan(IPlayerContext ctx) {
    return surfaceLine.transportPlan().withRoute(leg.componentId(), leg.exitState());
  }

  @Override
  public double estimatedTicksRemaining(RouteLeg leg) {
    return Math.max(0D, 1D - Math.max(0D, surfaceLine.progress())) * leg.estimatedTicks();
  }

  @Override
  public boolean containsPathPosition(BlockPos pos) {
    return leg.contains(pos) || surfaceLine.acceptsPathingDrift(pos);
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
  public boolean isSprinting() { return sprintNextTick; }
}
