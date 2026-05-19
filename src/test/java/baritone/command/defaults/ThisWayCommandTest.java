package baritone.command.defaults;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ThisWayCommandTest {
  @Test
  public void toleranceIsOnePercentCappedAtTwentyBlocks() {
    assertEquals(0, ThisWayCommand.tolerance(99.99D));
    assertEquals(1, ThisWayCommand.tolerance(100D));
    assertEquals(10, ThisWayCommand.tolerance(1000D));
    assertEquals(20, ThisWayCommand.tolerance(2000D));
    assertEquals(20, ThisWayCommand.tolerance(-9000D));
  }
}
