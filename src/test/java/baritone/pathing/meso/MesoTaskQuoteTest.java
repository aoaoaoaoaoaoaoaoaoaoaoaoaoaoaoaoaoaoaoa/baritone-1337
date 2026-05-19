package baritone.pathing.meso;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class MesoTaskQuoteTest {
  @Test
  public void rejectsNonmonotoneFixedTimeQuotes() {
    assertThrows(IllegalArgumentException.class, () -> MesoTaskQuote.fixedTime(10D, 9D, 11D, 0D, 0, MesoTaskEvidence.of("test")));
    assertThrows(IllegalArgumentException.class, () -> MesoTaskQuote.fixedTime(10D, 11D, 10.5D, 0D, 0, MesoTaskEvidence.of("test")));
  }

  @Test
  public void createsCostVectorsFromFixedTimeQuote() {
    MesoTaskQuote quote = MesoTaskQuote.fixedTime(1D, 2D, 3D, 4D, 5, MesoTaskEvidence.of("test"));

    assertEquals(1D, quote.lowerBound().timeTicks(), 0D);
    assertEquals(2D, quote.expected().timeTicks(), 0D);
    assertEquals(3D, quote.pessimistic().timeTicks(), 0D);
    assertEquals(4D, quote.uncertainty(), 0D);
    assertEquals(5, quote.missingMaterials());
  }
}
