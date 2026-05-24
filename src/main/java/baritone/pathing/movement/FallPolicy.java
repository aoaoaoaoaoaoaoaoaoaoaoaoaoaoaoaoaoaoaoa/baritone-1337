package baritone.pathing.movement;

public record FallPolicy(boolean hasWaterBucket, boolean allowIntoLava, int minHeight, int maxNoWater, int maxBucket, int featherFallingLevel) {
  private static final int TOP_STEPS = 17;
  private static final int MAX_AIR_GAP = 4096;
  private static final int VANILLA_SAFE_FALL_BLOCKS = 3;
  private static final int MAX_FEATHER_FALLING_LEVEL = 4;
  private static final boolean[] VANILLA_ZERO_DAMAGE = vanillaZeroDamageTable();

  public FallPolicy walkOffIntoLava() {
    return new FallPolicy(hasWaterBucket, true, 8, 10000, maxBucket, featherFallingLevel);
  }

  public boolean safeNoWaterLanding(int airGapBlocks, int landingTop16) {
    landingTop16 = Math.clamp(landingTop16, 0, 16);
    if (airGapBlocks < 0) {
      return false;
    }
    if (maxNoWater == VANILLA_SAFE_FALL_BLOCKS && airGapBlocks <= MAX_AIR_GAP) {
      return VANILLA_ZERO_DAMAGE[index(featherFallingLevel, landingTop16, airGapBlocks)];
    }
    return effectiveFallDistance16(airGapBlocks, landingTop16) <= maxNoWater * 16;
  }

  private static boolean[] vanillaZeroDamageTable() {
    boolean[] table = new boolean[(MAX_FEATHER_FALLING_LEVEL + 1) * TOP_STEPS * (MAX_AIR_GAP + 1)];
    for (int featherFalling = 0; featherFalling <= MAX_FEATHER_FALLING_LEVEL; featherFalling++) {
      for (int landingTop16 = 0; landingTop16 < TOP_STEPS; landingTop16++) {
        for (int airGapBlocks = 0; airGapBlocks <= MAX_AIR_GAP; airGapBlocks++) {
          table[index(featherFalling, landingTop16, airGapBlocks)] = effectiveFallDistance16(airGapBlocks, landingTop16) <= VANILLA_SAFE_FALL_BLOCKS * 16;
        }
      }
    }
    return table;
  }

  private static int effectiveFallDistance16(int airGapBlocks, int landingTop16) {
    return airGapBlocks * 16 - landingTop16;
  }

  private static int index(int featherFallingLevel, int landingTop16, int airGapBlocks) {
    int feather = Math.clamp(featherFallingLevel, 0, MAX_FEATHER_FALLING_LEVEL);
    return (feather * TOP_STEPS + landingTop16) * (MAX_AIR_GAP + 1) + airGapBlocks;
  }
}
