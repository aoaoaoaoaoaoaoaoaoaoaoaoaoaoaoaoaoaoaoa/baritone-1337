package baritone.pathing.mounted;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.Test;

public class MountTuningTest {
  @Test
  public void flatValuesOverrideTypedDefaults() {
    MountTuning tuning = MountTuning.fromFlat(Map.ofEntries(Map.entry("planner.water_wade_cost_multiplier", "7.25"), Map.entry("planner.ground-first-timeout-ms", "321"),
      Map.entry("planner.min-vertical-local-progress-fallback-blocks", "1.25"), Map.entry("planner.route-leg-max-edges", "77"), Map.entry("planner.direct-cruise-pull-span-large", "40"),
      Map.entry("planner.direct-cruise-pull-span-mid", "20"), Map.entry("planner.direct-cruise-pull-span-small", "10"), Map.entry("planner.min-cruise-support-quadrants", "3"),
      Map.entry("planner.diagonal-step-overrun-penalty-ticks", "9.5"), Map.entry("controller.stall-grace-ticks", "42"), Map.entry("motion.low-max-forward", "0.25"),
      Map.entry("motion.precision-ride-speed", "0.20")));

    assertEquals(7.25D, tuning.planner().waterWadeCostMultiplier(), 1e-12D);
    assertEquals(321, tuning.planner().groundFirstTimeoutMs());
    assertEquals(1.25D, tuning.planner().minVerticalLocalProgressFallbackBlocks(), 1e-12D);
    assertEquals(77, tuning.planner().routeLegMaxEdges());
    assertEquals(40D, tuning.planner().directCruisePullSpanLarge(), 1e-12D);
    assertEquals(3, tuning.planner().minCruiseSupportQuadrants());
    assertEquals(9.5D, tuning.planner().diagonalStepOverrunPenaltyTicks(), 1e-12D);
    assertEquals(42, tuning.controller().stallGraceTicks());
    assertEquals(0.20D, tuning.motion().precisionRideSpeed(), 1e-12D);
  }

  @Test
  public void unknownKeysAreFatal() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> MountTuning.fromFlat(Map.of("planner.magic", "1")));

    assertTrue(error.getMessage().contains("unknown mounted tuning key"));
  }

  @Test
  public void invalidParameterGeometryIsFatal() {
    IllegalArgumentException speed = assertThrows(IllegalArgumentException.class, () -> MountTuning.fromFlat(Map.of("motion.low-max-forward", "0.10", "motion.precision-ride-speed", "0.20")));
    assertTrue(speed.getMessage().contains("precision ride speed"));

    IllegalArgumentException radius =
      assertThrows(IllegalArgumentException.class, () -> MountTuning.fromFlat(Map.of("controller.waypoint-reached-radius", "0.60", "controller.technical-waypoint-reached-radius", "0.80")));
    assertTrue(radius.getMessage().contains("technical waypoint radius"));
  }

  @Test
  public void explicitProfileParseFailuresAreFatal() throws Exception {
    Path profile = Files.createTempFile("golden-tuning-bad-", ".toml");
    Files.writeString(profile, """
      [planner]
      water-wade-cost-multiplier = "submarine"
      """);

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> MountTuning.load(profile));

    assertTrue(error.getMessage().contains("failed to load mounted tuning projection"));
  }

  @Test
  public void duplicateNormalizedKeysAreFatal() throws Exception {
    Path profile = Files.createTempFile("golden-tuning-duplicate-", ".toml");
    Files.writeString(profile, """
      [controller]
      stall-grace-ticks = 20
      stall_grace_ticks = 21
      """);

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> MountTuning.load(profile));

    assertTrue(error.getMessage().contains("duplicate Golden tuning key"));
  }

  @Test
  public void currentUsesExplicitFabricLaunchProperty() throws Exception {
    Path profile = Files.createTempFile("golden-tuning-current-", ".toml");
    Files.writeString(profile, """
      [controller]
      stall-grace-ticks = 19
      """);
    String previous = System.getProperty("baritone.goldenTuning");
    try {
      System.setProperty("baritone.goldenTuning", profile.toString());
      MountTuning.resetForTests();

      assertEquals(19, MountTuning.current().controller().stallGraceTicks());
    } finally {
      if (previous == null) {
        System.clearProperty("baritone.goldenTuning");
      } else {
        System.setProperty("baritone.goldenTuning", previous);
      }
      MountTuning.resetForTests();
    }
  }

  @Test
  public void installRejectsDigestMismatchAndRecordsMetadata() throws Exception {
    Path profile = Files.createTempFile("golden-tuning-digest-", ".toml");
    String text = """
      [motion]
      low-max-forward = 0.25
      precision-ride-speed = 0.20
      """;
    Files.writeString(profile, text);
    String digest = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    try {
      MountTuning tuning = MountTuning.install(profile, digest);

      assertEquals(0.20D, tuning.motion().precisionRideSpeed(), 1e-12D);
      assertEquals(digest, MountTuning.metadata().digest());
      assertEquals(profile.toAbsolutePath().toString(), MountTuning.metadata().profile());
    } finally {
      MountTuning.resetForTests();
    }

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> MountTuning.install(profile, "sha256:0000000000000000000000000000000000000000000000000000000000000000"));

    assertTrue(error.getMessage().contains("digest mismatch"));
  }
}
