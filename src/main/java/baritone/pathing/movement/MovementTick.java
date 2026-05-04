package baritone.pathing.movement;

import baritone.api.pathing.movement.MovementStatus;
import baritone.pathing.control.ControlFrame;

public record MovementTick(MovementStatus status, ControlFrame frame, boolean safeToCancel, double progress) {
}
