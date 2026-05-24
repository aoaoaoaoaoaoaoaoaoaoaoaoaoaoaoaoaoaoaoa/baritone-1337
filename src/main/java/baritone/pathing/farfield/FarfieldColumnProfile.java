package baritone.pathing.farfield;

public record FarfieldColumnProfile(byte openSurface, byte voidOrGap, byte lava, byte solid, FarfieldEvidence evidence, int representativeY) {

  public FarfieldColumnProfile {
    if (evidence == null) {
      throw new IllegalArgumentException("farfield evidence required");
    }
    int open = unsigned(openSurface);
    int gap = unsigned(voidOrGap);
    int fire = unsigned(lava);
    int rock = unsigned(solid);
    int sum = open + gap + fire + rock;
    if (sum <= 0) {
      openSurface = (byte) 255;
      voidOrGap = lava = solid = 0;
    } else if (sum != 255) {
      openSurface = (byte) Math.round(open * 255F / sum);
      voidOrGap = (byte) Math.round(gap * 255F / sum);
      lava = (byte) Math.round(fire * 255F / sum);
      int remainder = 255 - unsigned(openSurface) - unsigned(voidOrGap) - unsigned(lava);
      solid = (byte) Math.max(0, Math.min(255, remainder));
    }
  }

  public static FarfieldColumnProfile open(FarfieldEvidence evidence, int y) {
    return new FarfieldColumnProfile((byte) 255, (byte) 0, (byte) 0, (byte) 0, evidence, y);
  }

  public static FarfieldColumnProfile voidGap(FarfieldEvidence evidence, int y) {
    return new FarfieldColumnProfile((byte) 0, (byte) 255, (byte) 0, (byte) 0, evidence, y);
  }

  public static FarfieldColumnProfile lava(FarfieldEvidence evidence, int y) {
    return new FarfieldColumnProfile((byte) 0, (byte) 0, (byte) 255, (byte) 0, evidence, y);
  }

  public static FarfieldColumnProfile solid(FarfieldEvidence evidence, int y) {
    return new FarfieldColumnProfile((byte) 0, (byte) 0, (byte) 0, (byte) 255, evidence, y);
  }

  static FarfieldColumnProfile mixed(int open, int gap, int lava, int solid, FarfieldEvidence evidence, int y) {
    return new FarfieldColumnProfile((byte) clamp255(open), (byte) clamp255(gap), (byte) clamp255(lava), (byte) clamp255(solid), evidence, y);
  }

  public int openSurface255() {
    return unsigned(openSurface);
  }

  public int void255() {
    return unsigned(voidOrGap);
  }

  public int lava255() {
    return unsigned(lava);
  }

  public int solid255() {
    return unsigned(solid);
  }

  static int unsigned(byte value) {
    return value & 0xFF;
  }

  private static int clamp255(int value) {
    return Math.max(0, Math.min(255, value));
  }
}
