package baritone.pathing.movement.movements;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MovementCruiseRayTest {

  @Test
  public void acceptsOnlyLongCardinalAndSquareDiagonalRays() {
    assertTrue(MovementCruiseRay.validRay(2, 0));
    assertTrue(MovementCruiseRay.validRay(-12, 0));
    assertTrue(MovementCruiseRay.validRay(0, 3));
    assertTrue(MovementCruiseRay.validRay(7, 7));
    assertTrue(MovementCruiseRay.validRay(-5, 5));

    assertFalse(MovementCruiseRay.validRay(0, 0));
    assertFalse(MovementCruiseRay.validRay(1, 0));
    assertFalse(MovementCruiseRay.validRay(1, 1));
    assertFalse(MovementCruiseRay.validRay(4, 2));
    assertFalse(MovementCruiseRay.validRay(2, 3));
  }
}
