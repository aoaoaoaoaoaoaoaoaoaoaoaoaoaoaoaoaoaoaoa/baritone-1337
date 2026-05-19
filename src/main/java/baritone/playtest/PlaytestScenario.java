package baritone.playtest;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalNearXZ;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.SettingsUtil;
import baritone.pathing.mounted.MountTuning;
import baritone.playtest.PlaytestRun.TerminalReason;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.phys.Vec3;

public record PlaytestScenario(String id, String runId, String worldKey, String seed, String dimension, Start start, GoalSpec goal, List<LoadoutItem> loadout, List<String> setupCommands,
  List<String> postSetupCommands, List<String> baritoneCommands, RunAction action, int selectedSlot, int timeoutTicks, double successRadius, boolean plannerOnly, boolean saturationBoost,
  Acceptance acceptance, Map<String, String> settings, boolean trace, MountTuningSpec mountTuning, HarnessContract harness) {
  private static final int DEFAULT_TIMEOUT_TICKS = 20 * 120;
  private static final double DEFAULT_SUCCESS_RADIUS = 1.5D;

  public static PlaytestScenario load(Path path, String runId) throws IOException {
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
      String id = string(json, "id", runId);
      String worldKey = string(json, "worldKey", "default");
      String seed = string(json, "seed", "baritone-playtest");
      String dimension = string(json, "dimension", "minecraft:overworld");
      Start start = Start.parse(object(json, "start"));
      GoalSpec goal = GoalSpec.parse(object(json, "goal"));
      List<LoadoutItem> loadout = loadout(json.get("loadout"));
      List<String> setupCommands = strings(json.get("setupCommands"));
      List<String> postSetupCommands = strings(json.get("postSetupCommands"));
      List<String> baritoneCommands = strings(json.get("baritoneCommands"));
      RunAction action = RunAction.parse(string(json, "action", baritoneCommands.isEmpty() ? "goto" : "commands"));
      int selectedSlot = integer(json, "selectedSlot", loadout.stream().filter(i -> i.slot() >= 0).mapToInt(LoadoutItem::slot).findFirst().orElse(0));
      int timeoutTicks = integer(json, "timeoutTicks", DEFAULT_TIMEOUT_TICKS);
      double successRadius = decimal(json, "successRadius", DEFAULT_SUCCESS_RADIUS);
      boolean plannerOnly = bool(json, "plannerOnly", false);
      boolean saturationBoost = bool(json, "saturationBoost", true);
      Acceptance acceptance = Acceptance.parse(object(json, "acceptance"));
      Map<String, String> settings = settings(json.getAsJsonObject("settings"));
      boolean trace = bool(json, "trace", true);
      MountTuningSpec mountTuning = MountTuningSpec.parse(object(json, "mountTuning"));
      HarnessContract harness = HarnessContract.parse(object(json, "harness"));
      return new PlaytestScenario(id, runId, worldKey, seed, dimension, start, goal, List.copyOf(loadout), List.copyOf(setupCommands), List.copyOf(postSetupCommands), List.copyOf(baritoneCommands),
        action, selectedSlot, timeoutTicks, successRadius, plannerOnly, saturationBoost, acceptance, Map.copyOf(settings), trace, mountTuning, harness);
    }
  }

  public Goal baritoneGoal() {
    return goal.toGoal();
  }

  public boolean succeeded(LocalPlayer player, boolean processActive, PlaytestRun run) {
    return (plannerOnly || action == RunAction.COMMANDS && !acceptance.requirePathComplete() || goal.succeeded(player.position(), successRadius)
      || player.getVehicle() != null && goal.succeeded(player.getVehicle().position(), successRadius)) && acceptance.satisfied(player, processActive, run, goal);
  }

  public void applySettings() {
    settings.forEach((name, value) -> SettingsUtil.parseAndApply(BaritoneAPI.getSettings(), name.toLowerCase(Locale.US), value));
  }

  public record Start(double x, double y, double z, float yaw, float pitch) {
    static Start parse(JsonObject json) {
      return new Start(decimal(json, "x", 0D), decimal(json, "y", 80D), decimal(json, "z", 0D), (float) decimal(json, "yaw", 0D), (float) decimal(json, "pitch", 0D));
    }
  }

  public record GoalSpec(String type, int x, int y, int z, int radius) {
    static GoalSpec parse(JsonObject json) {
      return new GoalSpec(string(json, "type", "block"), integer(json, "x", 0), integer(json, "y", 80), integer(json, "z", 0), integer(json, "radius", 1));
    }

    Goal toGoal() {
      return switch (type.toLowerCase(Locale.US)) {
        case "block" -> new GoalBlock(x, y, z);
        case "near", "goalnear" -> new GoalNear(new net.minecraft.core.BlockPos(x, y, z), radius);
        case "nearxz", "goalnearxz" -> new GoalNearXZ(x, z, radius);
        case "xz", "goalxz", "pointxz" -> new GoalXZ(x, z);
        default -> throw new IllegalArgumentException("Unsupported playtest goal type: " + type);
      };
    }

    boolean succeeded(Vec3 position, double radius) {
      if (radius <= 0D) {
        return toGoal().isInGoal((int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z));
      }
      double dx = position.x - (x + 0.5D);
      double dz = position.z - (z + 0.5D);
      if (xz()) {
        return dx * dx + dz * dz <= radius * radius;
      }
      double dy = position.y - y;
      return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    boolean xz() {
      return switch (type.toLowerCase(Locale.US)) {
        case "xz", "goalxz", "pointxz", "nearxz", "goalnearxz" -> true;
        default -> false;
      };
    }
  }

  public record LoadoutItem(int slot, String item, int count) {
  }

  public record HarnessContract(String expectedCodeStamp) {
    static HarnessContract parse(JsonObject json) {
      return new HarnessContract(string(json, "expectedCodeStamp", ""));
    }

    boolean acceptsCodeStamp(String actual) {
      return wellFormedCodeStamp(expectedCodeStamp) && expectedCodeStamp.equals(actual);
    }

    private static boolean wellFormedCodeStamp(String stamp) {
      if (stamp == null || !stamp.startsWith("sha256:") || stamp.length() != 71) {
        return false;
      }
      for (int i = 7; i < stamp.length(); i++) {
        char c = stamp.charAt(i);
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
          return false;
        }
      }
      return true;
    }
  }

  public record MountTuningSpec(String profile, String digest) {
    static MountTuningSpec parse(JsonObject json) {
      return new MountTuningSpec(string(json, "profile", ""), string(json, "digest", ""));
    }

    boolean configured() {
      return profile != null && !profile.isBlank();
    }

    void installIfConfigured() {
      if (configured()) {
        MountTuning.install(Path.of(profile), digest == null ? "" : digest);
      } else {
        MountTuning.reloadConfigured();
      }
    }
  }

  public enum RunAction {
    GOTO, COMMANDS;

    static RunAction parse(String raw) {
      return switch (raw.toLowerCase(Locale.US)) {
        case "goto", "path", "pathing" -> GOTO;
        case "commands", "command", "baritonecommands", "baritone_commands", "builder", "build" -> COMMANDS;
        default -> throw new IllegalArgumentException("Unsupported playtest action: " + raw);
      };
    }
  }

  public record Acceptance(boolean requirePathComplete, boolean requireOnGround, boolean requireNotInWater, boolean requireNoVehicle, boolean requireWaterEncountered, boolean requireBoatEncountered,
    boolean requireHorseEncountered, boolean requireBoatRecovered, boolean requirePathingSeen, boolean requireNoDamage, boolean requireMacroRoute, boolean requirePlannedBoat,
    boolean requirePlannedHorse, int minMacroBoatLegs, double minMacroBoatDistance, double maxRouteDestDistance, boolean requireMacroBiomeRoute, boolean requireMacroPlanBoat,
    int minMacroPlanBoatActions, double minMacroPlanBoatDistance, boolean requireMacroPlanSurfaceTransition, int minMacroPlanSurfaceActions, double minMacroPlanSurfaceDistance,
    boolean requireMacroPlanPortal, int minMacroPlanPortalActions, int minMacroPlanPortalBuildExitActions, double minMacroPlanNetherDistanceBeforePortalExit, double maxMacroBiomeUnknownFraction,
    int minJumpTicks, int maxJumpTicks, int maxTicksToPlanning, int maxTicksToActuation, int maxTicksToPathingSeen, int maxTicksToMacroPlan, boolean requireBuilderSeen,
    boolean requireDimensionChange) {
    static Acceptance parse(JsonObject json) {
      return new Acceptance(bool(json, "requirePathComplete", false), bool(json, "requireOnGround", false), bool(json, "requireNotInWater", false), bool(json, "requireNoVehicle", false),
        bool(json, "requireWaterEncountered", false), bool(json, "requireBoatEncountered", false), bool(json, "requireHorseEncountered", false), bool(json, "requireBoatRecovered", false),
        bool(json, "requirePathingSeen", false), bool(json, "requireNoDamage", true), bool(json, "requireMacroRoute", false), bool(json, "requirePlannedBoat", false),
        bool(json, "requirePlannedHorse", false), integer(json, "minMacroBoatLegs", 0), decimal(json, "minMacroBoatDistance", 0D), decimal(json, "maxRouteDestDistance", -1D),
        bool(json, "requireMacroBiomeRoute", false), bool(json, "requireMacroPlanBoat", false), integer(json, "minMacroPlanBoatActions", 0), decimal(json, "minMacroPlanBoatDistance", 0D),
        bool(json, "requireMacroPlanSurfaceTransition", false), integer(json, "minMacroPlanSurfaceActions", 0), decimal(json, "minMacroPlanSurfaceDistance", 0D),
        bool(json, "requireMacroPlanPortal", false), integer(json, "minMacroPlanPortalActions", 0), integer(json, "minMacroPlanPortalBuildExitActions", 0),
        decimal(json, "minMacroPlanNetherDistanceBeforePortalExit", 0D), decimal(json, "maxMacroBiomeUnknownFraction", 1D), integer(json, "minJumpTicks", 0), integer(json, "maxJumpTicks", -1),
        integer(json, "maxTicksToPlanning", -1), integer(json, "maxTicksToActuation", -1), integer(json, "maxTicksToPathingSeen", -1), integer(json, "maxTicksToMacroPlan", -1),
        bool(json, "requireBuilderSeen", false), bool(json, "requireDimensionChange", false));
    }

    boolean satisfied(LocalPlayer player, boolean processActive, PlaytestRun run, GoalSpec goal) {
      return (!requirePathComplete || !processActive) && (!requireOnGround || player.onGround()) && (!requireNotInWater || !(player.isInWater() || player.isUnderWater() || player.isSwimming()))
        && (!requireNoVehicle || player.getVehicle() == null) && (!requireWaterEncountered || run.sawWater()) && (!requireBoatEncountered || run.sawBoat())
        && (!requireHorseEncountered || run.sawHorse() && !run.lostHorseAfterEncounter()) && (!requireBoatRecovered || hasBoat(player)) && (!requirePathingSeen || run.sawPathing())
        && (!requireNoDamage || !run.tookDamage()) && (!requireMacroRoute || run.sawMacroRoute()) && (!requirePlannedBoat || run.sawPlannedBoat()) && (!requirePlannedHorse || run.sawPlannedHorse())
        && run.maxMacroBoatLegs() >= minMacroBoatLegs && run.maxMacroBoatDistance() + 1.0E-4D >= minMacroBoatDistance
        && (maxRouteDestDistance < 0D || run.routeDestDistance(goal) <= maxRouteDestDistance) && (!requireMacroBiomeRoute || run.sawMacroBiomeRoute())
        && (!requireMacroPlanBoat || run.sawMacroPlanBoat()) && run.maxMacroPlanBoatActions() >= minMacroPlanBoatActions && run.maxMacroPlanBoatDistance() + 1.0E-4D >= minMacroPlanBoatDistance
        && (!requireMacroPlanSurfaceTransition || run.sawMacroPlanSurfaceTransition()) && run.maxMacroPlanSurfaceActions() >= minMacroPlanSurfaceActions
        && run.maxMacroPlanSurfaceDistance() + 1.0E-4D >= minMacroPlanSurfaceDistance && (!requireMacroPlanPortal || run.sawMacroPlanPortal())
        && run.maxMacroPlanPortalActions() >= minMacroPlanPortalActions && run.maxMacroPlanPortalBuildExitActions() >= minMacroPlanPortalBuildExitActions
        && run.maxMacroPlanNetherDistanceBeforePortalExit() + 1.0E-4D >= minMacroPlanNetherDistanceBeforePortalExit && run.macroBiomeUnknownFraction() <= maxMacroBiomeUnknownFraction
        && run.jumpTicks() >= minJumpTicks && (maxJumpTicks < 0 || run.jumpTicks() <= maxJumpTicks)
        && (maxTicksToPlanning < 0 || run.firstPlanningTick() >= 0 && run.firstPlanningTick() <= maxTicksToPlanning)
        && (maxTicksToActuation < 0 || run.firstActuationTick() >= 0 && run.firstActuationTick() <= maxTicksToActuation)
        && (maxTicksToPathingSeen < 0 || run.firstPathingTick() >= 0 && run.firstPathingTick() <= maxTicksToPathingSeen)
        && (maxTicksToMacroPlan < 0 || run.firstMacroPlanTick() >= 0 && run.firstMacroPlanTick() <= maxTicksToMacroPlan) && (!requireBuilderSeen || run.sawBuilder())
        && (!requireDimensionChange || run.sawDimensionChange());
    }

    Optional<TerminalReason> earlyFailure(LocalPlayer player, PlaytestRun run) {
      if (requireNoDamage && run.tookDamage()) {
        return Optional.of(TerminalReason.ACCEPTANCE_DAMAGE);
      }
      if (requireHorseEncountered && run.sawHorse() && run.lostHorseAfterEncounter()) {
        return Optional.of(TerminalReason.ACCEPTANCE_DISMOUNT);
      }
      if (maxJumpTicks >= 0 && run.jumpTicks() > maxJumpTicks) {
        return Optional.of(TerminalReason.ACCEPTANCE_JUMP_BUDGET);
      }
      return Optional.empty();
    }

    private static boolean hasBoat(LocalPlayer player) {
      return player.getInventory().getNonEquipmentItems().stream().anyMatch(stack -> stack.getItem() instanceof BoatItem);
    }
  }

  private static List<LoadoutItem> loadout(JsonElement element) {
    List<LoadoutItem> items = new ArrayList<>();
    if (element == null || !element.isJsonArray()) {
      return items;
    }
    for (JsonElement child : element.getAsJsonArray()) {
      JsonObject json = child.getAsJsonObject();
      items.add(new LoadoutItem(integer(json, "slot", -1), string(json, "item", "minecraft:air"), integer(json, "count", 1)));
    }
    return items;
  }

  private static List<String> strings(JsonElement element) {
    List<String> strings = new ArrayList<>();
    if (element == null || !element.isJsonArray()) {
      return strings;
    }
    for (JsonElement child : element.getAsJsonArray()) {
      strings.add(child.getAsString());
    }
    return strings;
  }

  private static Map<String, String> settings(JsonObject json) {
    Map<String, String> settings = new LinkedHashMap<>();
    if (json == null) {
      return settings;
    }
    for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
      JsonElement value = entry.getValue();
      settings.put(entry.getKey(), value == null || value.isJsonNull() ? "" : value.isJsonPrimitive() ? value.getAsString() : value.toString());
    }
    return settings;
  }

  private static JsonObject object(JsonObject json, String name) {
    JsonElement value = json.get(name);
    return value == null || !value.isJsonObject() ? new JsonObject() : value.getAsJsonObject();
  }

  private static String string(JsonObject json, String name, String defaultValue) {
    JsonElement value = json.get(name);
    return value == null || value.isJsonNull() ? defaultValue : value.getAsString();
  }

  private static int integer(JsonObject json, String name, int defaultValue) {
    JsonElement value = json.get(name);
    return value == null || value.isJsonNull() ? defaultValue : value.getAsInt();
  }

  private static double decimal(JsonObject json, String name, double defaultValue) {
    JsonElement value = json.get(name);
    return value == null || value.isJsonNull() ? defaultValue : value.getAsDouble();
  }

  private static boolean bool(JsonObject json, String name, boolean defaultValue) {
    JsonElement value = json.get(name);
    return value == null || value.isJsonNull() ? defaultValue : value.getAsBoolean();
  }
}
