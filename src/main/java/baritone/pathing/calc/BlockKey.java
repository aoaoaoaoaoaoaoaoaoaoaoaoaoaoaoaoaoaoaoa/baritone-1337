package baritone.pathing.calc;

public final class BlockKey {
  private static final int X_BITS = 26;
  private static final int Z_BITS = 26;
  private static final int Y_BITS = 64 - X_BITS - Z_BITS;
  private static final int Y_SHIFT = Z_BITS;
  private static final int X_SHIFT = Y_SHIFT + Y_BITS;
  private static final long X_MASK = (1L << X_BITS) - 1L;
  private static final long Y_MASK = (1L << Y_BITS) - 1L;
  private static final long Z_MASK = (1L << Z_BITS) - 1L;

  private BlockKey() {
  }

  public static long pack(int x, int y, int z) {
    return ((long) x & X_MASK) << X_SHIFT | ((long) y & Y_MASK) << Y_SHIFT | ((long) z & Z_MASK);
  }

  public static int x(long key) {
    return signExtend(key >> X_SHIFT, X_BITS);
  }

  public static int y(long key) {
    return signExtend(key >> Y_SHIFT, Y_BITS);
  }

  public static int z(long key) {
    return signExtend(key, Z_BITS);
  }

  private static int signExtend(long value, int bits) {
    long mask = 1L << bits - 1;
    long truncated = value & (1L << bits) - 1L;
    return (int) ((truncated ^ mask) - mask);
  }
}
