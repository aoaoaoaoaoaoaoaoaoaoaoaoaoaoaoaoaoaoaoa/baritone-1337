package baritone.api.utils;

import baritone.api.Settings;
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

public final class GoldenTuning {
  public static final String PROFILE_PROPERTY = "baritone.goldenTuning";
  public static final String PROFILE_ENV = "BARITONE_GOLDEN_TUNING";
  public static final Path DEFAULT_PROFILE = Path.of("config", "baritone", "golden-tuning.toml");
  private static volatile Loaded current;

  private GoldenTuning() {
  }

  public static Loaded current() {
    Loaded snapshot = current;
    if (snapshot != null) {
      return snapshot;
    }
    synchronized (GoldenTuning.class) {
      snapshot = current;
      if (snapshot == null) {
        snapshot = loadConfigured();
        current = snapshot;
      }
      return snapshot;
    }
  }

  public static Loaded reloadConfigured() {
    synchronized (GoldenTuning.class) {
      current = loadConfigured();
      return current;
    }
  }

  public static Loaded install(Path path, String expectedDigest) {
    synchronized (GoldenTuning.class) {
      current = load(path, expectedDigest);
      return current;
    }
  }

  public static void resetForTests() {
    current = null;
  }

  public static Metadata metadata() {
    return current().metadata();
  }

  public static void applyCurrent(Settings settings) {
    current().applySettings(settings);
  }

  public static Loaded load(Path path) {
    return load(path, "");
  }

  public static Loaded load(Path path, String expectedDigest) {
    try {
      byte[] bytes = Files.readAllBytes(path);
      String digest = digest(bytes);
      if (expectedDigest != null && !expectedDigest.isBlank() && !expectedDigest.equals(digest)) {
        throw new IllegalArgumentException("digest mismatch: expected " + expectedDigest + ", got " + digest);
      }
      return new Loaded(path.toAbsolutePath().normalize().toString(), digest, false, parseTomlSubset(bytes));
    } catch (IOException | RuntimeException error) {
      throw new IllegalArgumentException("failed to load Golden tuning profile " + path.toAbsolutePath() + ": " + error.getMessage(), error);
    }
  }

  private static Loaded loadConfigured() {
    String explicit = firstNonBlank(System.getProperty(PROFILE_PROPERTY), System.getenv(PROFILE_ENV));
    if (explicit != null) {
      return load(Path.of(explicit));
    }
    if (Files.isRegularFile(DEFAULT_PROFILE)) {
      return load(DEFAULT_PROFILE);
    }
    return Loaded.builtinLoaded();
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
      if (!knownRoot(key)) {
        throw new IllegalArgumentException("unknown Golden tuning key '" + key + "' at line " + lineNumber);
      }
      if (out.put(key, unquote(value)) != null) {
        throw new IllegalArgumentException("duplicate Golden tuning key '" + key + "' at line " + lineNumber);
      }
    }
    return Map.copyOf(out);
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
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

  private static boolean knownRoot(String key) {
    return key.startsWith("settings.") || key.startsWith("controller.") || key.startsWith("motion.") || key.startsWith("planner.");
  }

  private static Map<String, String> prefixed(Map<String, String> values, String prefix) {
    LinkedHashMap<String, String> out = new LinkedHashMap<>();
    values.forEach((key, value) -> {
      if (key.startsWith(prefix)) {
        out.put(key.substring(prefix.length()), value);
      }
    });
    return out;
  }

  public record Loaded(String profile, String digest, boolean builtin, Map<String, String> values) {
    private static Loaded builtinLoaded() {
      return new Loaded("", "builtin:defaults", true, Map.of());
    }

    public Metadata metadata() {
      return new Metadata(profile, digest, builtin);
    }

    public Map<String, String> mountedValues() {
      LinkedHashMap<String, String> out = new LinkedHashMap<>();
      values.forEach((key, value) -> {
        if (key.startsWith("planner.") || key.startsWith("motion.") || key.startsWith("controller.")) {
          out.put(key, value);
        }
      });
      return out;
    }

    public Map<String, String> settingsValues() {
      return prefixed(values, "settings.");
    }

    public void applySettings(Settings settings) {
      settingsValues().forEach((name, value) -> SettingsUtil.parseAndApply(settings, name, value));
    }
  }

  public record Metadata(String profile, String digest, boolean builtin) {
  }
}
