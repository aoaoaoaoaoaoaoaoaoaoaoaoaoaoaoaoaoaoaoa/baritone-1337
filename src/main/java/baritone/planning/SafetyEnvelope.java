package baritone.planning;

public record SafetyEnvelope(int bits) {
  public static final SafetyEnvelope DEFAULT = new SafetyEnvelope(0);
}
