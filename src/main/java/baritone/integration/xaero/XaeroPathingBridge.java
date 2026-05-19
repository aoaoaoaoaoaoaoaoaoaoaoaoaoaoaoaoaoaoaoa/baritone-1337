package baritone.integration.xaero;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.behavior.PathingBehavior;
import baritone.pathing.macro.core.MacroCellEvidence;
import baritone.pathing.macro.core.MacroPlan;
import baritone.pathing.macro.core.MacroPlanVertex;
import baritone.pathing.path.RouteExecutor;
import baritone.pathing.route.RouteRenderPlan;
import baritone.pathing.transport.TransportMode;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public final class XaeroPathingBridge {

  public static final int XAERO_NO_Y = 32767;
  private static final int MAX_RENDER_POINTS = 1400;
  private static final int MACRO_VERTEX_RADIUS = 2;
  private static final int ROUTE_VERTEX_RADIUS = 2;
  private static final int WATER_VERTEX_RADIUS = 3;

  private XaeroPathingBridge() {
  }

  public static void pathHere(int x, int y, int z, ResourceKey<Level> dimension) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.player == null || minecraft.level == null) {
      Helper.HELPER.logDirect("Xaero path request ignored: no client world is active");
      return;
    }
    ResourceKey<Level> current = minecraft.level.dimension();
    if (dimension != null && !dimension.equals(current)) {
      Helper.HELPER.logDirect("Xaero path request ignored: clicked " + dimension.identifier() + " while standing in " + current.identifier());
      return;
    }
    IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(minecraft.player);
    if (baritone == null) {
      baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
    }
    Goal goal = y == XAERO_NO_Y ? new GoalXZ(x, z) : new GoalBlock(x, y, z);
    Helper.HELPER.logDirect("Xaero path request: " + goal);
    baritone.getCustomGoalProcess().setGoalAndPath(goal);
  }

  public static List<XaeroMapPoint> renderPoints() {
    if (!BaritoneAPI.getSettings().renderPath.value) {
      return List.of();
    }
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.player == null || minecraft.level == null) {
      return List.of();
    }
    IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(minecraft.player);
    if (baritone == null) {
      baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
    }
    if (!(baritone.getPathingBehavior() instanceof PathingBehavior behavior)) {
      return List.of();
    }
    ResourceKey<Level> dimension = minecraft.level.dimension();
    ArrayList<XaeroMapPoint> points = new ArrayList<>(Math.min(MAX_RENDER_POINTS, 256));
    appendRoute(points, dimension, behavior.getNext(), false);
    appendRoute(points, dimension, behavior.getCurrent(), true);
    if (BaritoneAPI.getSettings().renderMacroPlan.value) {
      behavior.getRenderableMacroPlan().ifPresent(plan -> appendMacro(points, dimension, plan));
    }
    return List.copyOf(points);
  }

  private static void appendRoute(ArrayList<XaeroMapPoint> points, ResourceKey<Level> dimension, RouteExecutor executor, boolean current) {
    if (executor == null || points.size() >= MAX_RENDER_POINTS) {
      return;
    }
    RouteRenderPlan plan = executor.renderPlan(current);
    for (RouteRenderPlan.Segment segment : plan.segments()) {
      int argb = argb(routeColor(segment, current), 224);
      int radius = segment.mode() == TransportMode.PEDESTRIAN || segment.mode() == TransportMode.HORSE ? ROUTE_VERTEX_RADIUS : WATER_VERTEX_RADIUS;
      appendPositions(points, dimension, segment.positions(), Math.max(segment.startIndex(), 0), argb, radius);
      if (points.size() >= MAX_RENDER_POINTS) {
        return;
      }
    }
  }

  private static void appendMacro(ArrayList<XaeroMapPoint> points, ResourceKey<Level> dimension, MacroPlan plan) {
    if (points.size() >= MAX_RENDER_POINTS) {
      return;
    }
    if (plan.vertices().size() >= 2) {
      int stride = stride(plan.vertices().size(), remaining(points));
      for (int i = 0; i < plan.vertices().size() && points.size() < MAX_RENDER_POINTS; i += stride) {
        MacroPlanVertex vertex = plan.vertices().get(i);
        points.add(point(dimension, vertex.pos(), argb(macroEvidenceColor(vertex.evidence()), 210), MACRO_VERTEX_RADIUS));
      }
      return;
    }
    appendPositions(points, dimension, plan.renderPositions(), 0, argb(BaritoneAPI.getSettings().colorMacroBiomePlan.value, 210), MACRO_VERTEX_RADIUS);
  }

  private static void appendPositions(ArrayList<XaeroMapPoint> points, ResourceKey<Level> dimension, List<BetterBlockPos> positions, int startIndex, int argb, int radius) {
    if (positions.isEmpty() || points.size() >= MAX_RENDER_POINTS) {
      return;
    }
    int first = Math.min(startIndex, positions.size() - 1);
    int stride = stride(positions.size() - first, remaining(points));
    for (int i = first; i < positions.size() && points.size() < MAX_RENDER_POINTS; i += stride) {
      points.add(point(dimension, positions.get(i), argb, radius));
    }
  }

  private static int remaining(ArrayList<XaeroMapPoint> points) {
    return Math.max(1, MAX_RENDER_POINTS - points.size());
  }

  private static int stride(int count, int budget) {
    return Math.max(1, (int) Math.ceil(count / (double) Math.max(1, budget)));
  }

  private static XaeroMapPoint point(ResourceKey<Level> dimension, BetterBlockPos pos, int argb, int radius) {
    return new XaeroMapPoint(dimension, pos.x, pos.z, argb, radius);
  }

  private static Color routeColor(RouteRenderPlan.Segment segment, boolean current) {
    Settings settings = BaritoneAPI.getSettings();
    if (segment.mode() == TransportMode.SWIM) {
      return settings.colorMacroSwim.value;
    }
    if (segment.mode() == TransportMode.BOAT) {
      return segment.terminal() ? settings.colorMacroBoatTerminal.value : settings.colorMacroBoatTransit.value;
    }
    if (segment.mode() == TransportMode.HORSE) {
      return settings.colorBestPathSoFar.value;
    }
    return current ? settings.colorCurrentPath.value : settings.colorNextPath.value;
  }

  private static Color macroEvidenceColor(MacroCellEvidence evidence) {
    Settings settings = BaritoneAPI.getSettings();
    return switch (evidence) {
      case LIVE -> settings.colorMacroLivePlan.value;
      case CACHED -> settings.colorMacroCachedPlan.value;
      case PREDICTED -> settings.colorMacroPredictedPlan.value;
      case PRIOR -> settings.colorMacroPriorPlan.value;
    };
  }

  private static int argb(Color color, int alpha) {
    int a = Math.clamp(alpha, 0, 255);
    return (a << 24) | (color.getRGB() & 0x00FFFFFF);
  }

  public record XaeroMapPoint(ResourceKey<Level> dimension, int x, int z, int argb, int radius) {
  }
}
