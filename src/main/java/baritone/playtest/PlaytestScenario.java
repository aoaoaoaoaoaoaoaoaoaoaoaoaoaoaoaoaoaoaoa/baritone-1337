package baritone.playtest;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.SettingsUtil;
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
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.phys.Vec3;

public record PlaytestScenario(String id, String runId, String worldKey, String seed, String dimension, Start start, GoalSpec goal, List<LoadoutItem> loadout, List<String> setupCommands,
  int selectedSlot, int timeoutTicks, double successRadius, boolean plannerOnly, boolean saturationBoost, Acceptance acceptance, Map<String, String> settings, boolean trace) {
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
      int selectedSlot = integer(json, "selectedSlot", loadout.stream().filter(i -> i.slot() >= 0).mapToInt(LoadoutItem::slot).findFirst().orElse(0));
      int timeoutTicks = integer(json, "timeoutTicks", DEFAULT_TIMEOUT_TICKS);
      double successRadius = decimal(json, "successRadius", DEFAULT_SUCCESS_RADIUS);
      boolean plannerOnly = bool(json, "plannerOnly", false);
      boolean saturationBoost = bool(json, "saturationBoost", true);
      Acceptance acceptance = Acceptance.parse(object(json, "acceptance"));
      Map<String, String> settings = settings(json.getAsJsonObject("settings"));
      boolean trace = bool(json, "trace", true);
      return new PlaytestScenario(id, runId, worldKey, seed, dimension, start, goal, List.copyOf(loadout), List.copyOf(setupCommands), selectedSlot, timeoutTicks, successRadius, plannerOnly,
        saturationBoost, acceptance, Map.copyOf(settings), trace);
    }
  }

  public Goal baritoneGoal() {
    return goal.toGoal();
  }

  public boolean succeeded(LocalPlayer player, boolean pathingActive, PlaytestRun run) {
    return (plannerOnly || goal.succeeded(player.position(), successRadius)) && acceptance.satisfied(player, pathingActive, run, goal);
  }

  public void applySettings() {
    settings.forEach((name, value) -> SettingsUtil.parseAndApply(BaritoneAPI.getSettings(), name.toLowerCase(Locale.US), value));
  }

  public record Start(double x, double y, double z, float yaw, float pitch) {
    static Start parse(JsonObject json) {
      return new Start(decimal(json, "x", 0D), decimal(json, "y", 80D), decimal(json, "z", 0D), (float) decimal(json, "yaw", 0D), (float) decimal(json, "pitch", 0D));
    }
  }

  public record GoalSpec(String type, int x, int y, int z) {
    static GoalSpec parse(JsonObject json) {
      return new GoalSpec(string(json, "type", "block"), integer(json, "x", 0), integer(json, "y", 80), integer(json, "z", 0));
    }

    Goal toGoal() {
      return switch (type.toLowerCase(Locale.US)) {
        case "block" -> new GoalBlock(x, y, z);
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
        case "xz", "goalxz", "pointxz" -> true;
        default -> false;
      };
    }
  }

  public record LoadoutItem(int slot, String item, int count) {
  }

  public record Acceptance(boolean requirePathComplete, boolean requireOnGround, boolean requireNotInWater, boolean requireNoVehicle, boolean requireWaterEncountered, boolean requireBoatEncountered,
    boolean requireBoatRecovered, boolean requirePathingSeen, boolean requireNoDamage, boolean requireMacroRoute, boolean requirePlannedBoat, int minMacroBoatLegs, double minMacroBoatDistance,
    double maxRouteDestDistance, boolean requireMacroBiomeRoute, boolean requireMacroPlanBoat, int minMacroPlanBoatActions, double minMacroPlanBoatDistance, boolean requireMacroPlanSurfaceTransition,
    int minMacroPlanSurfaceActions, double minMacroPlanSurfaceDistance, boolean requireMacroPlanPortal, int minMacroPlanPortalActions, double maxMacroBiomeUnknownFraction, int maxTicksToPlanning,
    int maxTicksToActuation, int maxTicksToPathingSeen, int maxTicksToMacroPlan) {
    static Acceptance parse(JsonObject json) {
      return new Acceptance(bool(json, "requirePathComplete", false), bool(json, "requireOnGround", false), bool(json, "requireNotInWater", false), bool(json, "requireNoVehicle", false),
        bool(json, "requireWaterEncountered", false), bool(json, "requireBoatEncountered", false), bool(json, "requireBoatRecovered", false), bool(json, "requirePathingSeen", false),
        bool(json, "requireNoDamage", true), bool(json, "requireMacroRoute", false), bool(json, "requirePlannedBoat", false), integer(json, "minMacroBoatLegs", 0),
        decimal(json, "minMacroBoatDistance", 0D), decimal(json, "maxRouteDestDistance", -1D), bool(json, "requireMacroBiomeRoute", false), bool(json, "requireMacroPlanBoat", false),
        integer(json, "minMacroPlanBoatActions", 0), decimal(json, "minMacroPlanBoatDistance", 0D), bool(json, "requireMacroPlanSurfaceTransition", false),
        integer(json, "minMacroPlanSurfaceActions", 0), decimal(json, "minMacroPlanSurfaceDistance", 0D), bool(json, "requireMacroPlanPortal", false), integer(json, "minMacroPlanPortalActions", 0),
        decimal(json, "maxMacroBiomeUnknownFraction", 1D), integer(json, "maxTicksToPlanning", -1), integer(json, "maxTicksToActuation", -1), integer(json, "maxTicksToPathingSeen", -1),
        integer(json, "maxTicksToMacroPlan", -1));
    }

    boolean satisfied(LocalPlayer player, boolean pathingActive, PlaytestRun run, GoalSpec goal) {
      return (!requirePathComplete || !pathingActive) && (!requireOnGround || player.onGround()) && (!requireNotInWater || !(player.isInWater() || player.isUnderWater() || player.isSwimming()))
        && (!requireNoVehicle || player.getVehicle() == null) && (!requireWaterEncountered || run.sawWater()) && (!requireBoatEncountered || run.sawBoat())
        && (!requireBoatRecovered || hasBoat(player)) && (!requirePathingSeen || run.sawPathing()) && (!requireNoDamage || !run.tookDamage()) && (!requireMacroRoute || run.sawMacroRoute())
        && (!requirePlannedBoat || run.sawPlannedBoat()) && run.maxMacroBoatLegs() >= minMacroBoatLegs && run.maxMacroBoatDistance() + 1.0E-4D >= minMacroBoatDistance
        && (maxRouteDestDistance < 0D || run.routeDestDistance(goal) <= maxRouteDestDistance) && (!requireMacroBiomeRoute || run.sawMacroBiomeRoute())
        && (!requireMacroPlanBoat || run.sawMacroPlanBoat()) && run.maxMacroPlanBoatActions() >= minMacroPlanBoatActions && run.maxMacroPlanBoatDistance() + 1.0E-4D >= minMacroPlanBoatDistance
        && (!requireMacroPlanSurfaceTransition || run.sawMacroPlanSurfaceTransition()) && run.maxMacroPlanSurfaceActions() >= minMacroPlanSurfaceActions
        && run.maxMacroPlanSurfaceDistance() + 1.0E-4D >= minMacroPlanSurfaceDistance && (!requireMacroPlanPortal || run.sawMacroPlanPortal())
        && run.maxMacroPlanPortalActions() >= minMacroPlanPortalActions && run.macroBiomeUnknownFraction() <= maxMacroBiomeUnknownFraction
        && (maxTicksToPlanning < 0 || run.firstPlanningTick() >= 0 && run.firstPlanningTick() <= maxTicksToPlanning)
        && (maxTicksToActuation < 0 || run.firstActuationTick() >= 0 && run.firstActuationTick() <= maxTicksToActuation)
        && (maxTicksToPathingSeen < 0 || run.firstPathingTick() >= 0 && run.firstPathingTick() <= maxTicksToPathingSeen)
        && (maxTicksToMacroPlan < 0 || run.firstMacroPlanTick() >= 0 && run.firstMacroPlanTick() <= maxTicksToMacroPlan);
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
