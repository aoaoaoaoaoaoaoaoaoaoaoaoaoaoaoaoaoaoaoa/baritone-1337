package baritone.playtest;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNearXZ;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PlaytestScenarioCommandTest {
  private static final int MINECRAFT_FILL_BLOCK_LIMIT = 32_768;
  private static final Pattern FILL_COMMAND = Pattern.compile("(?:^|\\s)fill\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)(?:\\s|$)");

  @Test
  public void scenarioFillCommandsFitVanillaCommandLimit() throws Exception {
    ArrayList<String> failures = new ArrayList<>();
    try (var paths = Files.walk(Path.of("scenarios", "playtest"))) {
      for (Path path : paths.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
          JsonObject scenario = JsonParser.parseReader(reader).getAsJsonObject();
          inspect(path, "setupCommands", scenario, failures);
          inspect(path, "postSetupCommands", scenario, failures);
        }
      }
    }
    if (!failures.isEmpty()) {
      fail(String.join("\n", failures));
    }
  }

  @Test
  public void nearxzGoalSpecProducesRadiusAwareHorizontalGoal() {
    PlaytestScenario.GoalSpec spec = new PlaytestScenario.GoalSpec("nearxz", 10, 80, -20, 4);
    assertTrue(spec.xz());
    Goal goal = spec.toGoal();
    assertTrue(goal instanceof GoalNearXZ);
    assertTrue(goal.isInGoal(14, -64, -20));
  }

  @Test
  public void scenarioParsesGoldenTuningSpec() throws Exception {
    Path scenarioFile = Files.createTempFile("playtest-golden-tuning-", ".json");
    try {
      Files.writeString(scenarioFile, """
        {
          "goldenTuning": {
            "profile": "/tmp/golden-tuning.toml",
            "digest": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
          }
        }
        """);

      PlaytestScenario scenario = PlaytestScenario.load(scenarioFile, "run");

      assertEquals("/tmp/golden-tuning.toml", scenario.goldenTuning().profile());
      assertEquals("sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", scenario.goldenTuning().digest());
      assertTrue(scenario.goldenTuning().configured());
    } finally {
      deleteRecursively(scenarioFile);
    }
  }

  @Test
  public void acceptanceEarlyFailureKillsOnlyMonotoneHorseInvalidations() throws Exception {
    Path scenarioFile = minimalScenario("""
      {
        "acceptance": {
          "requireHorseEncountered": true,
          "requireNoDamage": true,
          "maxJumpTicks": 0
        }
      }
      """);
    Path runDir = null;
    try {
      runDir = Files.createTempDirectory("playtest-run-");
      PlaytestScenario scenario = PlaytestScenario.load(scenarioFile, "run");
      PlaytestRun run = new PlaytestRun(scenario, runDir, null, 0);

      set(run, "sawHorse", true);
      set(run, "lostHorseAfterEncounter", true);
      assertSame(PlaytestRun.TerminalReason.ACCEPTANCE_DISMOUNT, scenario.acceptance().earlyFailure(null, run).orElseThrow());

      set(run, "lostHorseAfterEncounter", false);
      set(run, "jumpTicks", 1);
      assertSame(PlaytestRun.TerminalReason.ACCEPTANCE_JUMP_BUDGET, scenario.acceptance().earlyFailure(null, run).orElseThrow());

      set(run, "jumpTicks", 0);
      set(run, "initialHealth", 20F);
      set(run, "minHealth", 19F);
      assertSame(PlaytestRun.TerminalReason.ACCEPTANCE_DAMAGE, scenario.acceptance().earlyFailure(null, run).orElseThrow());
    } finally {
      deleteRecursively(runDir);
      deleteRecursively(scenarioFile);
    }
  }

  private static void inspect(Path path, String section, JsonObject scenario, ArrayList<String> failures) {
    if (!scenario.has(section) || !scenario.get(section).isJsonArray()) {
      return;
    }
    int index = 0;
    for (var command : scenario.getAsJsonArray(section)) {
      Matcher matcher = FILL_COMMAND.matcher(command.getAsString());
      if (matcher.find()) {
        int volume = volume(matcher);
        if (volume > MINECRAFT_FILL_BLOCK_LIMIT) {
          failures.add(path + ":" + section + "[" + index + "] fill touches " + volume + " blocks; split into <= " + MINECRAFT_FILL_BLOCK_LIMIT + ": " + command.getAsString());
        }
      }
      index++;
    }
  }

  private static Path minimalScenario(String json) throws Exception {
    Path path = Files.createTempFile("playtest-scenario-", ".json");
    Files.writeString(path, json);
    return path;
  }

  private static void deleteRecursively(Path path) throws Exception {
    if (path == null || !Files.exists(path)) {
      return;
    }
    try (var paths = Files.walk(path)) {
      for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(entry);
      }
    }
  }

  private static void set(Object target, String field, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(field);
    f.setAccessible(true);
    f.set(target, value);
  }

  private static int volume(Matcher matcher) {
    int x1 = Integer.parseInt(matcher.group(1));
    int y1 = Integer.parseInt(matcher.group(2));
    int z1 = Integer.parseInt(matcher.group(3));
    int x2 = Integer.parseInt(matcher.group(4));
    int y2 = Integer.parseInt(matcher.group(5));
    int z2 = Integer.parseInt(matcher.group(6));
    return (Math.abs(x2 - x1) + 1) * (Math.abs(y2 - y1) + 1) * (Math.abs(z2 - z1) + 1);
  }
}
