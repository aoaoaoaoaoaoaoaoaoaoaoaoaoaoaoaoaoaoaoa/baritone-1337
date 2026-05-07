package baritone.pathing.macro.biome;

import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.*;

public class BiomeTraversalPriorTableTest {
  @Test
  public void priorTableReadsProfilerAggregate() {
    BiomeTraversalPriorTable table = BiomeTraversalPriorTable.parse(JsonParser.parseString("""
      {
        "schema": 1,
        "biomes": {
          "minecraft:plains": {
            "surfacePedestrian": {
              "samples": 3,
              "successfulSamples": 3,
              "successfulTicks": 900,
              "totalBlocks": 240.0,
              "medianTicksPerBlock": 3.75,
              "p25TicksPerBlock": 3.5,
              "p75TicksPerBlock": 4.0,
              "failureRate": 0.0,
              "damageRate": 0.0,
              "jumpTicksPerBlock": 0.02,
              "sprintTicksPerBlock": 0.8,
              "moveForwardTicksPerBlock": 1.0,
              "waterTicksPerBlock": 0.0
            }
          }
        }
      }
      """).getAsJsonObject());
    assertEquals(1, table.size());
    assertEquals(3.75, table.exact("minecraft:plains").orElseThrow().medianTicksPerBlock(), 0D);
    assertTrue(table.surface("minecraft:bogus", 2D).uncertainty() > table.fallback().uncertainty());
  }
}
