package baritone.planning;

public record RouteRevision(long value) {
  public static final RouteRevision ZERO = new RouteRevision(0L);
}
