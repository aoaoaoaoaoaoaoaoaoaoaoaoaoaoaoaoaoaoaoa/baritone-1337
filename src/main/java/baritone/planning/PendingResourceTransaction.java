package baritone.planning;

public record PendingResourceTransaction(long id, ResourceDelta delta, long startedTick, int timeoutTicks) {
}
