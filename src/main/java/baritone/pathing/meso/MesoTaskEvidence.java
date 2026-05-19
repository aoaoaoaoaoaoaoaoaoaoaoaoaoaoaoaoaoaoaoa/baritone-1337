package baritone.pathing.meso;

public record MesoTaskEvidence(String summary, int factualSamples, int cachedSamples, int predictedSamples) {
  public static MesoTaskEvidence of(String summary) {
    return new MesoTaskEvidence(summary, 0, 0, 0);
  }

  public MesoTaskEvidence {
    if (summary == null || summary.isBlank()) {
      throw new IllegalArgumentException("meso task evidence needs a nonblank summary");
    }
    if (factualSamples < 0 || cachedSamples < 0 || predictedSamples < 0) {
      throw new IllegalArgumentException("meso task evidence sample counts must be nonnegative");
    }
  }
}
