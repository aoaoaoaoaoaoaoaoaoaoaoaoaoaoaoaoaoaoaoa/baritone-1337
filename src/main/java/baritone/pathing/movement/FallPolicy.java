package baritone.pathing.movement;

public record FallPolicy(boolean hasWaterBucket, boolean allowIntoLava, int minHeight, int maxNoWater, int maxBucket) {
  private static final int TOP_STEPS = 17;
  private static final int MAX_AIR_GAP = 4096;
  private static final int VANILLA_SAFE_FALL_BLOCKS = 3;
  private static final boolean[] VANILLA_ZERO_DAMAGE = vanillaZeroDamageTable();

  public FallPolicy walkOffIntoLava() {
    return new FallPolicy(hasWaterBucket, true, 8, 10000, maxBucket);
  }

  public boolean safeNoWaterLanding(int airGapBlocks, int landingTop16) {
    landingTop16 = Math.clamp(landingTop16, 0, 16);
    if (airGapBlocks < 0) {
      return false;
    }
    if (maxNoWater == VANILLA_SAFE_FALL_BLOCKS && airGapBlocks <= MAX_AIR_GAP) {
      return VANILLA_ZERO_DAMAGE[index(landingTop16, airGapBlocks)];
    }
    return effectiveFallDistance16(airGapBlocks, landingTop16) <= maxNoWater * 16;
  }

  private static boolean[] vanillaZeroDamageTable() {
    boolean[] table = new boolean[TOP_STEPS * (MAX_AIR_GAP + 1)];
    for (int landingTop16 = 0; landingTop16 < TOP_STEPS; landingTop16++) {
      for (int airGapBlocks = 0; airGapBlocks <= MAX_AIR_GAP; airGapBlocks++) {
        table[index(landingTop16, airGapBlocks)] = effectiveFallDistance16(airGapBlocks, landingTop16) <= VANILLA_SAFE_FALL_BLOCKS * 16;
      }
    }
    return table;
  }

  private static int effectiveFallDistance16(int airGapBlocks, int landingTop16) {
    return airGapBlocks * 16 - landingTop16;
  }

  private static int index(int landingTop16, int airGapBlocks) {
    return landingTop16 * (MAX_AIR_GAP + 1) + airGapBlocks;
  }
}
