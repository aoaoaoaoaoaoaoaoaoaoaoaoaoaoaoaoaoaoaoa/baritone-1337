package baritone.pathing.movement;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TrailReversibilityPrimitiveTest {

  @Test
  public void pillarAndDownwardAreStructurallyIrreversible() {
    assertEquals(TrailReversibility.IRREVERSIBLE, new LegacyMovesPrimitive(Moves.PILLAR).reversibilityFor(0, 64, 0, 0, 65, 0));
    assertEquals(TrailReversibility.IRREVERSIBLE, new LegacyMovesPrimitive(Moves.DOWNWARD).reversibilityFor(0, 64, 0, 0, 63, 0));
  }

  @Test
  public void ordinaryStairsRemainIntrinsic() {
    assertEquals(TrailReversibility.INTRINSIC, new LegacyMovesPrimitive(Moves.ASCEND_EAST).reversibilityFor(0, 64, 0, 1, 65, 0));
    assertEquals(TrailReversibility.INTRINSIC, new LegacyMovesPrimitive(Moves.DESCEND_WEST).reversibilityFor(0, 64, 0, -1, 63, 0));
  }

  @Test
  public void deepDynamicFallsAreIrreversible() {
    assertEquals(TrailReversibility.IRREVERSIBLE, new LegacyMovesPrimitive(Moves.DESCEND_EAST).reversibilityFor(0, 64, 0, 1, 60, 0));
  }

  @Test
  public void parkourIsSuspectRatherThanIntrinsic() {
    assertEquals(TrailReversibility.SUSPECT, new LegacyMovesPrimitive(Moves.PARKOUR_NORTH).reversibilityFor(0, 64, 0, 0, 64, -3));
  }

  @Test
  public void diagonalCruiseRemainsIntrinsic() {
    assertEquals(TrailReversibility.INTRINSIC, new LegacyMovesPrimitive(Moves.DIAGONAL_NORTHEAST).reversibilityFor(0, 64, 0, 1, 64, -1));
  }
}
