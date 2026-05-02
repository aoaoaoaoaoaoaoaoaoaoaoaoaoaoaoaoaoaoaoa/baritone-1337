package baritone.pathing.movement;

/**
 * Legacy walking primitive order. The enum is now metadata only; execution and evaluation live in {@link MovementPrimitive} implementations.
 *
 * @author leijurv
 */
public enum Moves {
  DOWNWARD(0, -1, 0),
  PILLAR(0, +1, 0),
  TRAVERSE_NORTH(0, 0, -1),
  TRAVERSE_SOUTH(0, 0, +1),
  TRAVERSE_EAST(+1, 0, 0),
  TRAVERSE_WEST(-1, 0, 0),
  ASCEND_NORTH(0, +1, -1),
  ASCEND_SOUTH(0, +1, +1),
  ASCEND_EAST(+1, +1, 0),
  ASCEND_WEST(-1, +1, 0),
  DESCEND_EAST(+1, -1, 0, false, true),
  DESCEND_WEST(-1, -1, 0, false, true),
  DESCEND_NORTH(0, -1, -1, false, true),
  DESCEND_SOUTH(0, -1, +1, false, true),
  DIAGONAL_NORTHEAST(+1, 0, -1, false, true),
  DIAGONAL_NORTHWEST(-1, 0, -1, false, true),
  DIAGONAL_SOUTHEAST(+1, 0, +1, false, true),
  DIAGONAL_SOUTHWEST(-1, 0, +1, false, true),
  PARKOUR_NORTH(0, 0, -4, true, true),
  PARKOUR_SOUTH(0, 0, +4, true, true),
  PARKOUR_EAST(+4, 0, 0, true, true),
  PARKOUR_WEST(-4, 0, 0, true, true);

  public final boolean dynamicXZ;
  public final boolean dynamicY;

  public final int xOffset;
  public final int yOffset;
  public final int zOffset;

  Moves(int x, int y, int z, boolean dynamicXZ, boolean dynamicY) {
    this.xOffset = x;
    this.yOffset = y;
    this.zOffset = z;
    this.dynamicXZ = dynamicXZ;
    this.dynamicY = dynamicY;
  }

  Moves(int x, int y, int z) {
    this(x, y, z, false, false);
  }
}
