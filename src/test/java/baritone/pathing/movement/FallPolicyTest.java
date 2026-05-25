package baritone.pathing.movement;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FallPolicyTest {
  private static final FallPolicy NO_DAMAGE = new FallPolicy(false, false, 3, 3, 20);
  private static final FallPolicy EXPLICIT_RISK = new FallPolicy(false, false, 3, 4, 20);

  @Test
  public void fullCubeThreeBlockFallIsSafe() {
    assertTrue(NO_DAMAGE.safeNoWaterLanding(4, 16));
  }

  @Test
  public void loweredLandingTopConsumesTheLastNoDamageMargin() {
    assertFalse(NO_DAMAGE.safeNoWaterLanding(4, 14));
    assertFalse(NO_DAMAGE.safeNoWaterLanding(4, 15));
  }

  @Test
  public void explicitHigherNoWaterPolicyStillMeansWhatItSays() {
    assertTrue(EXPLICIT_RISK.safeNoWaterLanding(4, 14));
  }
}
