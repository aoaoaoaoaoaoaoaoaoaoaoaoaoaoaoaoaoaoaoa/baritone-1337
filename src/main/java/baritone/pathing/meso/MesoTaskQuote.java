package baritone.pathing.meso;

import baritone.pathing.macro.core.MacroCostVector;

public record MesoTaskQuote(MacroCostVector lowerBound, MacroCostVector expected, MacroCostVector pessimistic, double uncertainty, int missingMaterials, MesoTaskEvidence evidence) {
  public MesoTaskQuote {
    if (lowerBound == null || expected == null || pessimistic == null || evidence == null) {
      throw new IllegalArgumentException("meso task quote requires all cost vectors and evidence");
    }
    if (!Double.isFinite(uncertainty) || uncertainty < 0D || missingMaterials < 0) {
      throw new IllegalArgumentException("invalid meso task quote uncertainty/materials: uncertainty=" + uncertainty + " missingMaterials=" + missingMaterials);
    }
  }

  public static MesoTaskQuote fixedTime(double lowerBoundTicks, double expectedTicks, double pessimisticTicks, double uncertainty, int missingMaterials, MesoTaskEvidence evidence) {
    if (lowerBoundTicks > expectedTicks || expectedTicks > pessimisticTicks) {
      throw new IllegalArgumentException("meso quote must be monotone: lower=" + lowerBoundTicks + " expected=" + expectedTicks + " pessimistic=" + pessimisticTicks);
    }
    return new MesoTaskQuote(MacroCostVector.fixedTime(lowerBoundTicks), MacroCostVector.fixedTime(expectedTicks), MacroCostVector.fixedTime(pessimisticTicks), uncertainty, missingMaterials,
      evidence);
  }
}
