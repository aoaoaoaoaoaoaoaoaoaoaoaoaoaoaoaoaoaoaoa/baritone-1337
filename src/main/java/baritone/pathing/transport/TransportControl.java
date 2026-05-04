package baritone.pathing.transport;

import baritone.api.pathing.movement.MovementStatus;

public record TransportControl(String movement, MovementStatus status, Float targetYaw, Float targetPitch, boolean forceRotations) {
}
