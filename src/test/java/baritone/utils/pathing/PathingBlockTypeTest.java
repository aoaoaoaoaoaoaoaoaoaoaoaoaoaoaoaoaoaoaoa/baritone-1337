package baritone.utils.pathing;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class PathingBlockTypeTest {

  @Test
  public void testBits() {
    for (PathingBlockType type : PathingBlockType.values()) {
      assertTrue(type == PathingBlockType.fromBits(type.highBit(), type.lowBit()));
    }
  }
}
