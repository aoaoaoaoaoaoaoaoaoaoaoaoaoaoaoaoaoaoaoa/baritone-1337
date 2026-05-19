package baritone.pathing.mounted;

import baritone.pathing.direct.DirectPullSchedule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record MountTuning(Planner planner, Motion motion, Controller controller) {
  private static final Logger LOGGER = LoggerFactory.getLogger("Baritone");
  private static final String PROFILE_PROPERTY = "baritone.mountTuning";
  private static final String PROFILE_ENV = "BARITONE_MOUNT_TUNING";
  private static final Path DEFAULT_PROFILE = Path.of("config", "baritone", "mount-tuning.toml");
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
  }

  public static MountTuning defaults() {
    return new MountTuning(Planner.defaults(), Motion.defaults(), Controller.defaults());
  }

  private static MountTuning loadConfigured() {
    String explicit = firstNonBlank(System.getProperty(PROFILE_PROPERTY), System.getenv(PROFILE_ENV));
    if (explicit != null) {
      Path path = Path.of(explicit);
      LoadedProfile loaded = loadProfile(path, "");
      MountTuning tuning = loaded.tuning();
      metadata = loaded.metadata();
      LOGGER.info("Loaded mounted tuning profile {}", path.toAbsolutePath());
      return tuning;
    }
    if (Files.isRegularFile(DEFAULT_PROFILE)) {
      LoadedProfile loaded = loadProfile(DEFAULT_PROFILE, "");
      MountTuning tuning = loaded.tuning();
      metadata = loaded.metadata();
      LOGGER.info("Loaded mounted tuning profile {}", DEFAULT_PROFILE.toAbsolutePath());
      return tuning;
    }
    metadata = Metadata.defaults();
    return defaults();
  }

  public static MountTuning load(Path path) {
    return loadProfile(path, "").tuning();
  }

  public static MountTuning install(Path path, String expectedDigest) {
    synchronized (MountTuning.class) {
      LoadedProfile loaded = loadProfile(path, expectedDigest);
      current = loaded.tuning();
      metadata = loaded.metadata();
      LOGGER.info("Installed mounted tuning profile {} digest {}", path.toAbsolutePath(), loaded.metadata().digest());
      return current;
    }
  }

  public static MountTuning reloadConfigured() {
    synchronized (MountTuning.class) {
      current = loadConfigured();
      return current;
    }
  }

  public static Metadata metadata() {
    current();
    return metadata;
  }

  private static LoadedProfile loadProfile(Path path, String expectedDigest) {
    try {
      byte[] bytes = Files.readAllBytes(path);
      String digest = digest(bytes);
      if (expectedDigest != null && !expectedDigest.isBlank() && !expectedDigest.equals(digest)) {
        throw new IllegalArgumentException("digest mismatch: expected " + expectedDigest + ", got " + digest);
      }
      return new LoadedProfile(fromFlat(parseTomlSubset(bytes)), new Metadata(path.toAbsolutePath().toString(), digest, false));
    } catch (IOException | RuntimeException error) {
      throw new IllegalArgumentException("failed to load mounted tuning profile " + path.toAbsolutePath() + ": " + error.getMessage(), error);
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

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) {
      return a.trim();
    }
    if (b != null && !b.isBlank()) {
      return b.trim();
    }
    return null;
  }

  private static Map<String, String> parseTomlSubset(byte[] bytes) {
    LinkedHashMap<String, String> out = new LinkedHashMap<>();
    String section = "";
    int lineNumber = 0;
    for (String raw : new String(bytes, StandardCharsets.UTF_8).lines().toList()) {
      lineNumber++;
      String line = stripComment(raw).trim();
      if (line.isEmpty()) {
        continue;
      }
      if (line.startsWith("[") && line.endsWith("]")) {
        section = line.substring(1, line.length() - 1).trim();
        if (section.isEmpty()) {
          throw new IllegalArgumentException("empty TOML section at line " + lineNumber);
        }
        continue;
      }
      int equals = line.indexOf('=');
      if (equals <= 0) {
        throw new IllegalArgumentException("expected key = value at line " + lineNumber + ": " + raw);
      }
      String key = line.substring(0, equals).trim();
      String value = line.substring(equals + 1).trim();
      if (key.isEmpty() || value.isEmpty()) {
        throw new IllegalArgumentException("empty key or value at line " + lineNumber + ": " + raw);
      }
      if (!section.isEmpty() && key.indexOf('.') < 0) {
        key = section + "." + key;
      }
      key = normalizeKey(key);
      if (out.put(key, unquote(value)) != null) {
        throw new IllegalArgumentException("duplicate mounted tuning key '" + key + "' at line " + lineNumber);
      }
    }
    return out;
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private record LoadedProfile(MountTuning tuning, Metadata metadata) {
  }

  public record Metadata(String profile, String digest, boolean builtin) {
    private static Metadata defaults() {
      return new Metadata("", "builtin:defaults", true);
    }
  }

  private static String stripComment(String raw) {
    boolean quoted = false;
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c == '"' && (i == 0 || raw.charAt(i - 1) != '\\')) {
        quoted = !quoted;
      } else if (c == '#' && !quoted) {
        return raw.substring(0, i);
      }
    }
    return raw;
  }

  private static String unquote(String raw) {
    if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
      return raw.substring(1, raw.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
    }
    return raw;
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
