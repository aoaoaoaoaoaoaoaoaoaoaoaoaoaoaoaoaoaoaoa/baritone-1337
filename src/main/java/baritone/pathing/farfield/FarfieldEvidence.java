package baritone.pathing.farfield;

public enum FarfieldEvidence {
  LIVE, CACHED, PRIOR;

  public boolean factual() {
    return this != PRIOR;
  }
}
