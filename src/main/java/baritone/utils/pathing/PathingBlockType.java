package baritone.utils.pathing;

import java.util.BitSet;

/**
 * @author Brady
 * @since 8/4/2018
 */
public enum PathingBlockType {

  AIR(0b00), WATER(0b01), AVOID(0b10), SOLID(0b11);

  private final int bits;

  PathingBlockType(int bits) {
    this.bits = bits;
  }

  public boolean highBit() {
    return (bits & 0b10) != 0;
  }

  public boolean lowBit() {
    return (bits & 0b01) != 0;
  }

  public void writeTo(BitSet data, int index) {
    data.set(index, highBit());
    data.set(index + 1, lowBit());
  }

  public static PathingBlockType fromBits(boolean b1, boolean b2) {
    return b1 ? b2 ? SOLID : AVOID : b2 ? WATER : AIR;
  }
}
