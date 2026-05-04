package baritone.pathing.route;

import baritone.api.pathing.movement.MovementStatus;
import baritone.pathing.control.ControlFrame;

public record LegTickResult<P>(MovementStatus status, ControlFrame frame, boolean safeToCancel, double progress, P phase, ExecutionFailure failure) {
  public static <P> LegTickResult<P> of(MovementStatus status, ControlFrame frame, boolean safeToCancel, double progress, P phase) {
    return new LegTickResult<>(status, status.isComplete() ? ControlFrame.EMPTY : frame, safeToCancel, progress, phase,
      status == MovementStatus.UNREACHABLE || status == MovementStatus.FAILED ? new ExecutionFailure(status, phase == null ? status.name() : phase.toString()) : null);
  }
}
