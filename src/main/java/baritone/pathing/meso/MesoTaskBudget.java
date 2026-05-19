package baritone.pathing.meso;

public record MesoTaskBudget(long nanos, int candidateLimit) {
  public static final MesoTaskBudget FAST = new MesoTaskBudget(0L, 0);

  public MesoTaskBudget {
    if (nanos < 0 || candidateLimit < 0) {
      throw new IllegalArgumentException("meso task budget must be nonnegative: nanos=" + nanos + " candidateLimit=" + candidateLimit);
    }
  }
}
