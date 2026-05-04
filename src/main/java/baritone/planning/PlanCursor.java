package baritone.planning;

public record PlanCursor(RouteRevision revision, int legIndex, PhaseKey phase, double progress) {
}
