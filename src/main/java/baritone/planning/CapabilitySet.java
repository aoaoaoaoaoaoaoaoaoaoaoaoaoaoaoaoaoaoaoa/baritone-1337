package baritone.planning;

public record CapabilitySet(long bits) {
  public static final CapabilitySet NONE = new CapabilitySet(0L);

  public static CapabilitySet of(CapabilityId... ids) {
    long bits = 0L;
    for (CapabilityId id : ids) {
      bits |= bit(id);
    }
    return new CapabilitySet(bits);
  }

  public boolean has(CapabilityId id) {
    return (bits & bit(id)) != 0L;
  }

  public CapabilitySet with(CapabilityId id) {
    return new CapabilitySet(bits | bit(id));
  }

  private static long bit(CapabilityId id) {
    return 1L << id.ordinal();
  }
}
