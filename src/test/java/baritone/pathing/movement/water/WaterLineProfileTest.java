package baritone.pathing.movement.water;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WaterLineProfileTest {
  @Test
  public void macroBoatProfilesUseSessionEconomicsAndSegmentGeometry() {
    WaterTransportPolicy policy = new WaterTransportPolicy(true, false, 40, 35, 35, 2.55, 0, 0);
    WaterLineProfile launch = WaterLineProfile.macroBoatLaunch(policy, false);
    WaterLineProfile terminal = WaterLineProfile.macroMountedBoat(policy, true);
    assertEquals(1, launch.minimumBlocks());
    assertEquals(35, launch.setupCost(), 0);
    assertEquals(0, launch.pickupCost(), 0);
    assertEquals(1, terminal.minimumBlocks());
    assertEquals(0, terminal.setupCost(), 0);
    assertEquals(35, terminal.pickupCost(), 0);
  }
}
