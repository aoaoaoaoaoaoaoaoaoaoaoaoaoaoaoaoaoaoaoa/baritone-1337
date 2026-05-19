package baritone.pathing.mounted;

public final class HorseTrackLattice {
  public static final double CLEARANCE_MARGIN = 0.14D;
  private static final double[] OFFSETS = {0D, 0.28D, -0.28D, 0.40D, -0.40D};

  private HorseTrackLattice() {
  }

  public static int offsetCount() {
    return OFFSETS.length;
  }

  public static double offset(int index) {
    return OFFSETS[index];
  }
}
