package baritone.pathing.macro.core;

public enum MacroCellEvidence {
  LIVE, CACHED, PREDICTED, PRIOR;

  public boolean concrete() {
    return this != PRIOR;
  }
}
