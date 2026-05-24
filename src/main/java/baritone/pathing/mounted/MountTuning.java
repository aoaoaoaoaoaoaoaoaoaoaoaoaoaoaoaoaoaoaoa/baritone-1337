package baritone.pathing.mounted;

import baritone.api.utils.GoldenTuning;
import baritone.pathing.direct.DirectPullSchedule;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record MountTuning(Planner planner, Motion motion, Controller controller) {
  private static final Logger LOGGER = LoggerFactory.getLogger("Baritone");
  private static volatile MountTuning current;
  private static volatile Metadata metadata = Metadata.defaults();

  public MountTuning {
    if (planner == null || motion == null || controller == null) {
      throw new IllegalArgumentException("mount tuning subtrees must not be null");
    }
    if (controller.terminalSettledSpeed > motion.lowMaxForward) {
      throw new IllegalArgumentException("terminal settled speed must not exceed low forward speed");
    }
  }

  public static MountTuning current() {
    MountTuning snapshot = current;
    if (snapshot != null) {
      return snapshot;
    }
    synchronized (MountTuning.class) {
      snapshot = current;
      if (snapshot == null) {
        snapshot = loadConfigured();
        current = snapshot;
      }
      return snapshot;
    }
  }

  static void resetForTests() {
    current = null;
    metadata = Metadata.defaults();
    GoldenTuning.resetForTests();
  }

  public static MountTuning defaults() {
    return new MountTuning(Planner.defaults(), Motion.defaults(), Controller.defaults());
  }

  private static MountTuning loadConfigured() {
    LoadedProfile loaded = loadProfile(GoldenTuning.current());
    metadata = loaded.metadata();
    if (!metadata.builtin()) {
      LOGGER.info("Loaded mounted tuning projection from Golden profile {}", metadata.profile());
    }
    return loaded.tuning();
  }

  public static MountTuning load(Path path) {
    return loadProfile(GoldenTuning.load(path)).tuning();
  }

  public static MountTuning install(Path path, String expectedDigest) {
    synchronized (MountTuning.class) {
      LoadedProfile loaded = loadProfile(GoldenTuning.install(path, expectedDigest));
      current = loaded.tuning();
      metadata = loaded.metadata();
      LOGGER.info("Installed mounted tuning profile {} digest {}", path.toAbsolutePath(), loaded.metadata().digest());
      return current;
    }
  }

  public static MountTuning reloadConfigured() {
    synchronized (MountTuning.class) {
      GoldenTuning.reloadConfigured();
      current = loadConfigured();
      return current;
    }
  }

  public static Metadata metadata() {
    current();
    return metadata;
  }

  private static LoadedProfile loadProfile(GoldenTuning.Loaded loaded) {
    try {
      return new LoadedProfile(fromFlat(loaded.mountedValues()), new Metadata(loaded.profile(), loaded.digest(), loaded.builtin()));
    } catch (RuntimeException error) {
      String profile = loaded.profile().isBlank() ? "<builtin>" : loaded.profile();
      throw new IllegalArgumentException("failed to load mounted tuning projection from Golden profile " + profile + ": " + error.getMessage(), error);
    }
  }

  public static MountTuning fromFlat(Map<String, String> values) {
    LinkedHashMap<String, String> remaining = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : values.entrySet()) {
      remaining.put(normalizeKey(entry.getKey()), entry.getValue());
    }
    MountTuning defaults = defaults();
    MountTuning tuning = new MountTuning(Planner.from(remaining, defaults.planner), Motion.from(remaining, defaults.motion), Controller.from(remaining, defaults.controller));
    if (!remaining.isEmpty()) {
      throw new IllegalArgumentException("unknown mounted tuning key(s): " + remaining.keySet());
    }
    return tuning;
  }

  private record LoadedProfile(MountTuning tuning, Metadata metadata) {
  }

  public record Metadata(String profile, String digest, boolean builtin) {
    private static Metadata defaults() {
      return new Metadata("", "builtin:defaults", true);
    }
  }

  private static String normalizeKey(String key) {
    return key.trim().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  private static double finite(Map<String, String> values, String key, double fallback, double min, double max) {
    String raw = values.remove(key);
    if (raw == null) {
      return fallback;
    }
    double value;
    try {
      value = Double.parseDouble(raw);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException("mounted tuning key '" + key + "' must be a number, got " + raw, error);
    }
    if (!Double.isFinite(value) || value < min || value > max) {
      throw new IllegalArgumentException("mounted tuning key '" + key + "' out of range [" + min + ", " + max + "]: " + raw);
    }
    return value;
  }

  private static int integer(Map<String, String> values, String key, int fallback, int min, int max) {
    String raw = values.remove(key);
    if (raw == null) {
      return fallback;
    }
    int value;
    try {
      value = Integer.parseInt(raw);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException("mounted tuning key '" + key + "' must be an integer, got " + raw, error);
    }
    if (value < min || value > max) {
      throw new IllegalArgumentException("mounted tuning key '" + key + "' out of range [" + min + ", " + max + "]: " + raw);
    }
    return value;
  }

  private static boolean bool(Map<String, String> values, String key, boolean fallback) {
    String raw = values.remove(key);
    if (raw == null) {
      return fallback;
    }
    return switch (raw.trim().toLowerCase(Locale.ROOT)) {
      case "true" -> true;
      case "false" -> false;
      default -> throw new IllegalArgumentException("mounted tuning key '" + key + "' must be true or false, got " + raw);
    };
  }

  public record Planner(double minLocalProgressFallbackBlocks, double minVerticalLocalProgressFallbackBlocks, double localProgressHeuristicEpsilon, double localProgressCostWeight,
    int routeLegMaxEdges, double routeLegMaxTicks, int lineCertificationSamplesPerBlock, double waterWadeCostMultiplier, int groundFirstTimeoutMs, boolean directCruisePulling,
    double directCruisePullCrawlBlocks, double directCruisePullSpanLarge, double directCruisePullSpanMid, double directCruisePullSpanSmall, int minCruiseSupportQuadrants,
    double damageHazardApproachMargin, double poorSupportPenaltyTicks, double diagonalStepOverrunPenaltyTicks) {
    public Planner {
      if (directCruisePulling) {
        DirectPullSchedule.descending(directCruisePullCrawlBlocks, directCruisePullSpanLarge, directCruisePullSpanMid, directCruisePullSpanSmall);
      }
    }

    static Planner defaults() {
      return new Planner(6.1497D, 3.2136D, 1.9207D, 0.9758D, 73, 140.7602D, 5, 7.9910D, 0, true, 8.4114D, 26.9281D, 19.7710D, 8.5052D, 1, 0.8081D, 0.0656D, 6D);
    }

    public DirectPullSchedule directCruisePullSchedule() {
      return DirectPullSchedule.descending(directCruisePullCrawlBlocks, directCruisePullSpanLarge, directCruisePullSpanMid, directCruisePullSpanSmall);
    }

    static Planner from(Map<String, String> values, Planner d) {
      return new Planner(finite(values, "planner.min-local-progress-fallback-blocks", d.minLocalProgressFallbackBlocks, 0D, 32D),
        finite(values, "planner.min-vertical-local-progress-fallback-blocks", d.minVerticalLocalProgressFallbackBlocks, 0D, 32D),
        finite(values, "planner.local-progress-heuristic-epsilon", d.localProgressHeuristicEpsilon, 0D, 4D), finite(values, "planner.local-progress-cost-weight", d.localProgressCostWeight, 0D, 4D),
        integer(values, "planner.route-leg-max-edges", d.routeLegMaxEdges, 1, 512), finite(values, "planner.route-leg-max-ticks", d.routeLegMaxTicks, 1D, 2000D),
        integer(values, "planner.line-certification-samples-per-block", d.lineCertificationSamplesPerBlock, 1, 16),
        finite(values, "planner.water-wade-cost-multiplier", d.waterWadeCostMultiplier, 1D, 64D), integer(values, "planner.ground-first-timeout-ms", d.groundFirstTimeoutMs, 0, 120_000),
        bool(values, "planner.direct-cruise-pulling", d.directCruisePulling), finite(values, "planner.direct-cruise-pull-crawl-blocks", d.directCruisePullCrawlBlocks, 0D, 256D),
        finite(values, "planner.direct-cruise-pull-span-large", d.directCruisePullSpanLarge, 1D, 512D), finite(values, "planner.direct-cruise-pull-span-mid", d.directCruisePullSpanMid, 1D, 512D),
        finite(values, "planner.direct-cruise-pull-span-small", d.directCruisePullSpanSmall, 1D, 512D), integer(values, "planner.min-cruise-support-quadrants", d.minCruiseSupportQuadrants, 1, 4),
        finite(values, "planner.damage-hazard-approach-margin", d.damageHazardApproachMargin, 0D, 2D), finite(values, "planner.poor-support-penalty-ticks", d.poorSupportPenaltyTicks, 0D, 4D),
        finite(values, "planner.diagonal-step-overrun-penalty-ticks", d.diagonalStepOverrunPenaltyTicks, 0D, 64D));
    }
  }

  public record Motion(double settledSpeed, double lowMaxForward, double precisionRideSpeed, double precisionRideFinalSpeed, double precisionRideTaperDistance) {
    public Motion {
      if (settledSpeed > lowMaxForward) {
        throw new IllegalArgumentException("settled speed must not exceed low forward speed");
      }
      if (precisionRideSpeed > lowMaxForward) {
        throw new IllegalArgumentException("precision ride speed must not exceed low forward speed");
      }
      if (precisionRideFinalSpeed > precisionRideSpeed) {
        throw new IllegalArgumentException("precision final speed must not exceed precision ride speed");
      }
    }

    static Motion defaults() {
      return new Motion(0.0315D, 0.1573D, 0.1573D, 0.0727D, 1.2597D);
    }

    static Motion from(Map<String, String> values, Motion d) {
      return new Motion(finite(values, "motion.settled-speed", d.settledSpeed, 0D, 1D), finite(values, "motion.low-max-forward", d.lowMaxForward, 0D, 2D),
        finite(values, "motion.precision-ride-speed", d.precisionRideSpeed, 0D, 2D), finite(values, "motion.precision-ride-final-speed", d.precisionRideFinalSpeed, 0D, 2D),
        finite(values, "motion.precision-ride-taper-distance", d.precisionRideTaperDistance, 0D, 8D));
    }
  }

  public record Controller(double cruiseSegmentAimDistance, double precisionSegmentAimDistance, double terminalSettledSpeed, int stallGraceTicks, double stallProgressEpsilon,
    double waypointReachedRadius, double technicalWaypointReachedRadius, double terminalReachedRadius, double physicalWaypointReachedRadius) {
    static Controller defaults() {
      return new Controller(3.4632D, 0.5616D, 0.0403D, 68, 0.1540D, 0.8251D, 0.4272D, 0.5534D, 1.4705D);
    }

    public Controller {
      if (precisionSegmentAimDistance > cruiseSegmentAimDistance) {
        throw new IllegalArgumentException("precision aim distance must not exceed cruise aim distance");
      }
      if (technicalWaypointReachedRadius > waypointReachedRadius) {
        throw new IllegalArgumentException("technical waypoint radius must not exceed ordinary waypoint radius");
      }
      if (physicalWaypointReachedRadius < waypointReachedRadius) {
        throw new IllegalArgumentException("physical waypoint radius must cover ordinary waypoint radius");
      }
    }

    static Controller from(Map<String, String> values, Controller d) {
      return new Controller(finite(values, "controller.cruise-segment-aim-distance", d.cruiseSegmentAimDistance, 0D, 8D),
        finite(values, "controller.precision-segment-aim-distance", d.precisionSegmentAimDistance, 0D, 4D), finite(values, "controller.terminal-settled-speed", d.terminalSettledSpeed, 0D, 1D),
        integer(values, "controller.stall-grace-ticks", d.stallGraceTicks, 1, 400), finite(values, "controller.stall-progress-epsilon", d.stallProgressEpsilon, 0D, 1D),
        finite(values, "controller.waypoint-reached-radius", d.waypointReachedRadius, 0.2D, 1.5D),
        finite(values, "controller.technical-waypoint-reached-radius", d.technicalWaypointReachedRadius, 0.2D, 1.5D),
        finite(values, "controller.terminal-reached-radius", d.terminalReachedRadius, 0.2D, 2.5D),
        finite(values, "controller.physical-waypoint-reached-radius", d.physicalWaypointReachedRadius, 0.2D, 2.5D));
    }
  }
}
