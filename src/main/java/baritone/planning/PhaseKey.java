package baritone.planning;

public record PhaseKey(String controller, String phase) {
  public static final PhaseKey NONE = new PhaseKey("none", "none");
}
