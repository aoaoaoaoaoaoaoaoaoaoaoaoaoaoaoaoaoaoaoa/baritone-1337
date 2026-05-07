package baritone.pathing.macro.biome;

import baritone.pathing.macro.core.MacroCellEvidence;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

public class SeedlessBiomePredictorTest {
  @Test
  public void observedClimateBoxesProducePredictedBiomeFacts() {
    SeedlessBiomePredictor predictor = SeedlessBiomePredictor.of(16, List.of(new BiomeMacroCell(0, 0, "minecraft:plains", 64, MacroCellEvidence.CACHED),
      new BiomeMacroCell(1, 0, "minecraft:plains", 64, MacroCellEvidence.CACHED), new BiomeMacroCell(0, 1, "minecraft:plains", 64, MacroCellEvidence.CACHED)));

    assertTrue(predictor.useful());
    BiomeMacroCell prediction = predictor.predict(4, 0).orElseThrow();
    assertEquals(MacroCellEvidence.PREDICTED, prediction.evidence());
    assertTrue(prediction.biome().startsWith("minecraft:"));
  }
}
