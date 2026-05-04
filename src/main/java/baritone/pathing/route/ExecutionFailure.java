package baritone.pathing.route;

import baritone.api.pathing.movement.MovementStatus;

public record ExecutionFailure(MovementStatus status, String reason) {
}
