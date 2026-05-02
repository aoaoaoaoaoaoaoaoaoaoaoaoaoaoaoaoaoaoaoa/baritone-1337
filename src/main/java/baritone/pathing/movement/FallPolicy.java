package baritone.pathing.movement;

public record FallPolicy(boolean hasWaterBucket, boolean allowIntoLava, int minHeight, int maxNoWater, int maxBucket) {

  public FallPolicy walkOffIntoLava() {
    return new FallPolicy(hasWaterBucket, true, 8, 10000, maxBucket);
  }
}
