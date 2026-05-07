package baritone.pathing.macro.core;

import org.junit.Test;

import static org.junit.Assert.*;

public class MacroCostVectorTest {
  @Test
  public void rawRiskIsConvertedToAdditiveHazard() {
    var low = new baritone.pathing.macro.biome.BiomeSurfaceCost("low", 1, 1, 1, 100D, 4D, 4D, 4D, 0.01D, 0.01D, 0D, 0D, 0D, 0D, 0D);
    var high = new baritone.pathing.macro.biome.BiomeSurfaceCost("high", 1, 1, 1, 100D, 4D, 4D, 4D, 0.10D, 0.10D, 0D, 0D, 0D, 0D, 0D);
    assertTrue(MacroCostVector.surfacePerBlock(high).failureHazard() > MacroCostVector.surfacePerBlock(low).failureHazard());
    assertTrue(MacroCostVector.surfacePerBlock(high).damageHazard() > MacroCostVector.surfacePerBlock(low).damageHazard());
  }
}
