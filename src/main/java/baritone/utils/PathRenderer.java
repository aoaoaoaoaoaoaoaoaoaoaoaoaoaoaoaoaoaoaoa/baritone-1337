package baritone.utils;

import baritone.api.BaritoneAPI;
import baritone.api.event.events.RenderEvent;
import baritone.api.pathing.goals.*;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.behavior.PathingBehavior;
import baritone.pathing.macro.core.MacroCellEvidence;
import baritone.pathing.macro.core.MacroPlan;
import baritone.pathing.macro.core.MacroPlanVertex;
import baritone.pathing.path.RouteExecutor;
import baritone.pathing.route.RouteRenderPlan;
import baritone.pathing.transport.TransportMode;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author Brady
 * @since 8/9/2018
 */
public final class PathRenderer implements IRenderer {

  private PathRenderer() {
  }

  private static final float GOAL_BEACON_INNER_RADIUS = 0.2F;
  private static final float GOAL_BEACON_GLOW_RADIUS = 0.25F;
  private static final int GOAL_BEACON_GLOW_ALPHA = 32;

  public static double posX() {
    return renderManager.renderPosX();
  }

  public static double posY() {
    return renderManager.renderPosY();
  }

  public static double posZ() {
    return renderManager.renderPosZ();
  }

  public static void render(RenderEvent event, PathingBehavior behavior) {
    final IPlayerContext ctx = behavior.ctx;
    if (ctx.world() == null) {
      return;
    }
    RenderContext view = RenderContext.capture(event);
    if (ctx.minecraft().screen instanceof GuiClick) {
      ((GuiClick) ctx.minecraft().screen).onRender(view, event.getProjectionMatrix());
    }

    final Goal goal = behavior.getGoal();

    final DimensionType thisPlayerDimension = ctx.world().dimensionType();
    final DimensionType currentRenderViewDimension = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().world().dimensionType();

    if (thisPlayerDimension != currentRenderViewDimension) {
      // this is a path for a bot in a different dimension, don't render it
      return;
    }

    if (goal != null && settings.renderGoal.value) {
      drawGoal(view, ctx, goal, settings.colorGoalBox.value);
    }

    if (!settings.renderPath.value) {
      return;
    }

    RouteExecutor current = behavior.getCurrent(); // this should prevent most race conditions?
    RouteExecutor next = behavior.getNext(); // like, now it's not possible for current!=null to be true, then suddenly false because of another thread
    if (current != null && settings.renderSelectionBoxes.value) {
      drawManySelectionBoxes(view, ctx.player(), current.toBreak(), settings.colorBlocksToBreak.value);
      drawManySelectionBoxes(view, ctx.player(), current.toPlace(), settings.colorBlocksToPlace.value);
      drawManySelectionBoxes(view, ctx.player(), current.toWalkInto(), settings.colorBlocksToWalkInto.value);
    }

    //drawManySelectionBoxes(player, Collections.singletonList(behavior.pathStart()), partialTicks, Color.WHITE);

    if (settings.renderMacroPlan.value) {
      behavior.getRenderableMacroPlan().ifPresent(plan -> drawMacroPlan(view, ctx.player(), plan));
    }
    if (current != null) {
      drawRoutePlan(view, ctx.player(), current.renderPlan(true), true);
    }
    if (next != null) {
      drawRoutePlan(view, ctx.player(), next.renderPlan(false), false);
    }

    if (!settings.renderPathCalculation.value) {
      return;
    }

    // If there is a path calculation currently running, render volatile search probes. These are diagnostics, not commitments.
    behavior.getInProgress().ifPresent(currentlyRunning -> {
      behavior.getPlanningStart().ifPresent(start -> drawManySelectionBoxes(view, ctx.player(), Collections.singletonList(start), settings.colorBestPathSoFar.value));
      currentlyRunning.bestPathSoFar().ifPresent(p -> {
        drawPath(view, p.positions(), 0, settings.colorBestPathSoFar.value, settings.fadePath.value, 10, 20);
      });
      currentlyRunning.pathToMostRecentNodeConsidered().ifPresent(mr -> {
        drawPath(view, mr.positions(), 0, settings.colorMostRecentConsidered.value, settings.fadePath.value, 10, 20);
        drawManySelectionBoxes(view, ctx.player(), Collections.singletonList(mr.getDest()), settings.colorMostRecentConsidered.value);
      });
    });
  }

  private static void drawMacroPlan(RenderContext view, Entity player, MacroPlan plan) {
    if (plan.vertices().size() >= 2) {
      for (int i = 0; i < plan.vertices().size() - 1; i++) {
        MacroPlanVertex a = plan.vertices().get(i);
        MacroPlanVertex b = plan.vertices().get(i + 1);
        drawPath(view, List.of(a.pos(), b.pos()), 0, macroEvidenceColor(b.evidence()), false, 10, 20, 0.85D);
      }
    } else if (plan.renderPositions().size() >= 2) {
      drawPath(view, plan.renderPositions(), 0, settings.colorMacroBiomePlan.value, false, 10, 20, 0.85D);
    }
    if (settings.renderMacroPlanAnchors.value && plan.vertices().size() >= 2) {
      ArrayList<BlockPos> cells = new ArrayList<>();
      int stride = Math.max(1, plan.vertices().size() / 24);
      for (int i = 0; i < plan.vertices().size(); i += stride) {
        cells.add(plan.vertices().get(i).pos());
      }
      drawManySelectionBoxes(view, player, cells, settings.colorMacroRouteAnchor.value);
    }
  }

  private static Color macroEvidenceColor(MacroCellEvidence evidence) {
    return switch (evidence) {
      case LIVE -> settings.colorMacroLivePlan.value;
      case CACHED -> settings.colorMacroCachedPlan.value;
      case PREDICTED -> settings.colorMacroPredictedPlan.value;
      case PRIOR -> settings.colorMacroPriorPlan.value;
    };
  }

  private static void drawRoutePlan(RenderContext view, Entity player, RouteRenderPlan plan, boolean current) {
    for (RouteRenderPlan.Segment segment : plan.segments()) {
      drawPath(view, segment.positions(), segment.startIndex(), routeColor(segment, current), settings.fadePath.value && current, 10, 20, routeOffset(segment));
    }
    if (settings.renderMacroPlanAnchors.value && current) {
      ArrayList<BlockPos> anchors = new ArrayList<>(plan.anchors().size());
      for (RouteRenderPlan.Anchor anchor : plan.anchors()) {
        anchors.add(anchor.pos());
      }
      drawManySelectionBoxes(view, player, anchors, settings.colorMacroRouteAnchor.value);
    }
  }

  private static Color routeColor(RouteRenderPlan.Segment segment, boolean current) {
    if (segment.mode() == TransportMode.SWIM) {
      return settings.colorMacroSwim.value;
    }
    if (segment.mode() == TransportMode.BOAT) {
      return segment.terminal() ? settings.colorMacroBoatTerminal.value : settings.colorMacroBoatTransit.value;
    }
    return current ? settings.colorCurrentPath.value : settings.colorNextPath.value;
  }

  private static double routeOffset(RouteRenderPlan.Segment segment) {
    return switch (segment.mode()) {
      case BOAT -> 0.70D;
      case SWIM -> 0.58D;
      default -> 0.5D;
    };
  }

  public static void drawPath(RenderContext view, List<BetterBlockPos> positions, int startIndex, Color color, boolean fadeOut, int fadeStart0, int fadeEnd0) {
    drawPath(view, positions, startIndex, color, fadeOut, fadeStart0, fadeEnd0, 0.5D);
  }

  public static void drawPath(RenderContext view, List<BetterBlockPos> positions, int startIndex, Color color, boolean fadeOut, int fadeStart0, int fadeEnd0, double offset) {
    BufferBuilder bufferBuilder = IRenderer.startLines(color);

    int fadeStart = fadeStart0 + startIndex;
    int fadeEnd = fadeEnd0 + startIndex;

    for (int i = startIndex, next; i < positions.size() - 1; i = next) {
      BetterBlockPos start = positions.get(i);
      BetterBlockPos end = positions.get(next = i + 1);

      int dirX = end.x - start.x;
      int dirY = end.y - start.y;
      int dirZ = end.z - start.z;

      while (next + 1 < positions.size() && (!fadeOut || next + 1 < fadeStart)
        && (dirX == positions.get(next + 1).x - end.x && dirY == positions.get(next + 1).y - end.y && dirZ == positions.get(next + 1).z - end.z)) {
        end = positions.get(++next);
      }

      if (fadeOut) {
        float alpha;

        if (i <= fadeStart) {
          alpha = 0.4F;
        } else {
          if (i > fadeEnd) {
            break;
          }
          alpha = 0.4F * (1.0F - (float) (i - fadeStart) / (float) (fadeEnd - fadeStart));
        }
        IRenderer.glColor(color, alpha);
      }

      emitPathLine(bufferBuilder, view, start.x, start.y, start.z, end.x, end.y, end.z, offset);
    }

    IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
  }

  private static void emitPathLine(BufferBuilder bufferBuilder, RenderContext view, double x1, double y1, double z1, double x2, double y2, double z2, double offset) {
    final double extraOffset = offset + 0.03D;

    boolean renderPathAsFrickinThingy = !settings.renderPathAsLine.value;

    IRenderer.emitLine(bufferBuilder, view.stack(), view.x(x1 + offset), view.y(y1 + offset), view.z(z1 + offset), view.x(x2 + offset), view.y(y2 + offset), view.z(z2 + offset),
      settings.pathRenderLineWidthPixels.value);
    if (renderPathAsFrickinThingy) {
      IRenderer.emitLine(bufferBuilder, view.stack(), view.x(x2 + offset), view.y(y2 + offset), view.z(z2 + offset), view.x(x2 + offset), view.y(y2 + extraOffset), view.z(z2 + offset),
        settings.pathRenderLineWidthPixels.value);
      IRenderer.emitLine(bufferBuilder, view.stack(), view.x(x2 + offset), view.y(y2 + extraOffset), view.z(z2 + offset), view.x(x1 + offset), view.y(y1 + extraOffset), view.z(z1 + offset),
        settings.pathRenderLineWidthPixels.value);
      IRenderer.emitLine(bufferBuilder, view.stack(), view.x(x1 + offset), view.y(y1 + extraOffset), view.z(z1 + offset), view.x(x1 + offset), view.y(y1 + offset), view.z(z1 + offset),
        settings.pathRenderLineWidthPixels.value);
    }
  }

  public static void drawManySelectionBoxes(RenderContext view, Entity player, Collection<BlockPos> positions, Color color) {
    BufferBuilder bufferBuilder = IRenderer.startLines(color);

    //BlockPos blockpos = movingObjectPositionIn.getBlockPos();
    BlockStateInterface bsi = new BlockStateInterface(BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext()); // TODO this assumes same dimension between primary baritone and render view? is this safe?

    positions.forEach(pos -> {
      BlockState state = bsi.get0(pos);
      VoxelShape shape = state.getShape(player.level(), pos);
      AABB toDraw = shape.isEmpty() ? Shapes.block().bounds() : shape.bounds();
      toDraw = toDraw.move(pos);
      IRenderer.emitAABB(bufferBuilder, view, toDraw, .002D, settings.pathRenderLineWidthPixels.value);
    });

    IRenderer.endLines(bufferBuilder, settings.renderSelectionBoxesIgnoreDepth.value);
  }

  public static void drawGoal(RenderContext view, IPlayerContext ctx, Goal goal, Color color) {
    drawGoal(null, view, ctx, goal, color, true);
  }

  private static void drawGoal(@Nullable BufferBuilder bufferBuilder, RenderContext view, IPlayerContext ctx, Goal goal, Color color, boolean setupRender) {
    if (!setupRender && bufferBuilder == null) {
      throw new RuntimeException("BufferBuilder must not be null if setupRender is false");
    }
    double minX, maxX;
    double minZ, maxZ;
    double minY, maxY;
    double y, y1, y2;
    if (!settings.renderGoalAnimated.value) {
      // y = 1 causes rendering issues when the player is at the same y as the top of a block for some reason
      y = 0.999F;
    } else {
      y = Mth.cos((float) (((float) ((System.nanoTime() / 100000L) % 20000L)) / 20000F * Math.PI * 2));
    }
    if (goal instanceof IGoalRenderPos) {
      BlockPos goalPos = ((IGoalRenderPos) goal).getGoalPos();
      minX = view.x(goalPos.getX() + 0.002);
      maxX = view.x(goalPos.getX() + 1 - 0.002);
      minZ = view.z(goalPos.getZ() + 0.002);
      maxZ = view.z(goalPos.getZ() + 1 - 0.002);
      if (goal instanceof GoalGetToBlock || goal instanceof GoalTwoBlocks) {
        y /= 2;
      }
      y1 = view.y(1 + y + goalPos.getY());
      y2 = view.y(1 - y + goalPos.getY());
      minY = view.y(goalPos.getY());
      maxY = minY + 2;
      if (goal instanceof GoalGetToBlock || goal instanceof GoalTwoBlocks) {
        y1 -= 0.5;
        y2 -= 0.5;
        maxY--;
      }
      drawDankLitGoalBox(bufferBuilder, view.stack(), color, minX, maxX, minZ, maxZ, minY, maxY, y1, y2, setupRender);
    } else if (goal instanceof GoalXZ) {
      GoalXZ goalPos = (GoalXZ) goal;
      minY = ctx.world().getMinY();
      maxY = ctx.world().getMaxY();

      minX = view.x(goalPos.getX() + 0.002);
      maxX = view.x(goalPos.getX() + 1 - 0.002);
      minZ = view.z(goalPos.getZ() + 0.002);
      maxZ = view.z(goalPos.getZ() + 1 - 0.002);

      y1 = 0;
      y2 = 0;
      minY = view.y(minY);
      maxY = view.y(maxY);
      drawDankLitGoalBox(bufferBuilder, view.stack(), color, minX, maxX, minZ, maxZ, minY, maxY, y1, y2, setupRender);
      drawGoalXZBeacon(view, ctx, (GoalXZ) goal, minY, maxY, color);
    } else if (goal instanceof GoalComposite) {
      // Simple way to determine if goals can be batched, without having some sort of GoalRenderer
      boolean batch = Arrays.stream(((GoalComposite) goal).goals()).allMatch(IGoalRenderPos.class::isInstance);
      BufferBuilder buf = bufferBuilder;
      if (batch) {
        buf = IRenderer.startLines(color, settings.goalRenderLineWidthPixels.value);
      }
      for (Goal g : ((GoalComposite) goal).goals()) {
        drawGoal(buf, view, ctx, g, color, !batch);
      }
      if (batch) {
        IRenderer.endLines(buf, settings.renderGoalIgnoreDepth.value);
      }
    } else if (goal instanceof GoalInverted) {
      drawGoal(view, ctx, ((GoalInverted) goal).origin, settings.colorInvertedGoalBox.value);
    } else if (goal instanceof GoalYLevel) {
      GoalYLevel goalpos = (GoalYLevel) goal;
      minX = view.x(ctx.player().position().x - settings.yLevelBoxSize.value);
      minZ = view.z(ctx.player().position().z - settings.yLevelBoxSize.value);
      maxX = view.x(ctx.player().position().x + settings.yLevelBoxSize.value);
      maxZ = view.z(ctx.player().position().z + settings.yLevelBoxSize.value);
      minY = view.y(((GoalYLevel) goal).level);
      maxY = minY + 2;
      y1 = view.y(1 + y + goalpos.level);
      y2 = view.y(1 - y + goalpos.level);
      drawDankLitGoalBox(bufferBuilder, view.stack(), color, minX, maxX, minZ, maxZ, minY, maxY, y1, y2, setupRender);
    }
  }

  private static void drawDankLitGoalBox(BufferBuilder bufferBuilder, PoseStack stack, Color colorIn, double minX, double maxX, double minZ, double maxZ, double minY, double maxY, double y1,
    double y2, boolean setupRender) {
    if (setupRender) {
      bufferBuilder = IRenderer.startLines(colorIn);
    }

    renderHorizontalQuad(bufferBuilder, stack, minX, maxX, minZ, maxZ, y1, settings.goalRenderLineWidthPixels.value);
    renderHorizontalQuad(bufferBuilder, stack, minX, maxX, minZ, maxZ, y2, settings.goalRenderLineWidthPixels.value);

    for (double y = minY; y < maxY; y += 16) {
      double max = Math.min(maxY, y + 16);
      IRenderer.emitLine(bufferBuilder, stack, minX, y, minZ, minX, max, minZ, 0.0, 1.0, 0.0, settings.goalRenderLineWidthPixels.value);
      IRenderer.emitLine(bufferBuilder, stack, maxX, y, minZ, maxX, max, minZ, 0.0, 1.0, 0.0, settings.goalRenderLineWidthPixels.value);
      IRenderer.emitLine(bufferBuilder, stack, maxX, y, maxZ, maxX, max, maxZ, 0.0, 1.0, 0.0, settings.goalRenderLineWidthPixels.value);
      IRenderer.emitLine(bufferBuilder, stack, minX, y, maxZ, minX, max, maxZ, 0.0, 1.0, 0.0, settings.goalRenderLineWidthPixels.value);
    }

    if (setupRender) {
      IRenderer.endLines(bufferBuilder, settings.renderGoalIgnoreDepth.value);
    }
  }

  private static void renderHorizontalQuad(BufferBuilder bufferBuilder, PoseStack stack, double minX, double maxX, double minZ, double maxZ, double y, float lineWidth) {
    if (y != 0) {
      IRenderer.emitLine(bufferBuilder, stack, minX, y, minZ, maxX, y, minZ, 1.0, 0.0, 0.0, lineWidth);
      IRenderer.emitLine(bufferBuilder, stack, maxX, y, minZ, maxX, y, maxZ, 0.0, 0.0, 1.0, lineWidth);
      IRenderer.emitLine(bufferBuilder, stack, maxX, y, maxZ, minX, y, maxZ, -1.0, 0.0, 0.0, lineWidth);
      IRenderer.emitLine(bufferBuilder, stack, minX, y, maxZ, minX, y, minZ, 0.0, 0.0, -1.0, lineWidth);
    }
  }

  private static void drawGoalXZBeacon(RenderContext view, IPlayerContext ctx, GoalXZ goal, double minY, double maxY, Color color) {
    PoseStack stack = view.stack();
    float time = settings.renderGoalAnimated.value ? (float) ctx.world().getGameTime() + view.partialTicks() : 0.0F;
    int glowColor = (color.getRGB() & 0x00FFFFFF) | GOAL_BEACON_GLOW_ALPHA << 24;
    double height = maxY - minY;

    stack.pushPose();
    stack.translate(view.x(goal.getX()), view.y(minY), view.z(goal.getZ()));
    renderGoalXZBeaconLayer(stack, height, time, color.getRGB(), GOAL_BEACON_INNER_RADIUS, false);
    renderGoalXZBeaconLayer(stack, height, time, glowColor, GOAL_BEACON_GLOW_RADIUS, true);
    stack.popPose();
  }

  private static void renderGoalXZBeaconLayer(PoseStack stack, double height, float time, int color, float radius, boolean translucent) {
    BufferBuilder bufferBuilder = IRenderer.startBlockQuads();
    float scroll = Mth.frac(-time * 0.2F - Mth.floor(-time * 0.1F));

    stack.pushPose();
    stack.translate(0.5D, 0.0D, 0.5D);
    float v0 = -1.0F + scroll;
    float v1 = (float) (translucent ? height + v0 : height * (0.5F / radius) + v0);
    PoseStack.Pose pose = stack.last();
    if (translucent) {
      emitBeaconShell(bufferBuilder, pose, color, 0.0F, (float) height, -radius, -radius, radius, -radius, -radius, radius, radius, radius, v0, v1);
    } else {
      emitRotatingBeaconShell(bufferBuilder, pose, color, (float) height, radius, time, v0, v1);
    }
    stack.popPose();

    IRenderer.endBuffer(bufferBuilder, IRenderer.beaconBeam(BeaconRenderer.BEAM_LOCATION, translucent, settings.renderGoalIgnoreDepth.value));
  }

  private static void emitRotatingBeaconShell(BufferBuilder bufferBuilder, PoseStack.Pose pose, int color, float height, float radius, float time, float v0, float v1) {
    float angle = (time * 2.25F - 45.0F) * Mth.DEG_TO_RAD;
    float sin = Mth.sin(angle);
    float cos = Mth.cos(angle);
    emitBeaconShell(bufferBuilder, pose, color, 0.0F, height, rotateX(0.0F, radius, sin, cos), rotateZ(0.0F, radius, sin, cos), rotateX(radius, 0.0F, sin, cos), rotateZ(radius, 0.0F, sin, cos),
      rotateX(-radius, 0.0F, sin, cos), rotateZ(-radius, 0.0F, sin, cos), rotateX(0.0F, -radius, sin, cos), rotateZ(0.0F, -radius, sin, cos), v0, v1);
  }

  private static float rotateX(float x, float z, float sin, float cos) {
    return x * cos - z * sin;
  }

  private static float rotateZ(float x, float z, float sin, float cos) {
    return x * sin + z * cos;
  }

  private static void emitBeaconShell(BufferBuilder bufferBuilder, PoseStack.Pose pose, int color, float minY, float maxY, float x1, float z1, float x2, float z2, float x3, float z3, float x4,
    float z4, float v0, float v1) {
    emitBeaconFace(bufferBuilder, pose, color, minY, maxY, x1, z1, x2, z2, 0.0F, 1.0F, v0, v1);
    emitBeaconFace(bufferBuilder, pose, color, minY, maxY, x4, z4, x3, z3, 0.0F, 1.0F, v0, v1);
    emitBeaconFace(bufferBuilder, pose, color, minY, maxY, x2, z2, x4, z4, 0.0F, 1.0F, v0, v1);
    emitBeaconFace(bufferBuilder, pose, color, minY, maxY, x3, z3, x1, z1, 0.0F, 1.0F, v0, v1);
  }

  private static void emitBeaconFace(BufferBuilder bufferBuilder, PoseStack.Pose pose, int color, float minY, float maxY, float x1, float z1, float x2, float z2, float u0, float u1, float v0,
    float v1) {
    float nx = z2 - z1;
    float nz = x1 - x2;
    float length = Mth.sqrt(nx * nx + nz * nz);
    if (length != 0.0F) {
      nx /= length;
      nz /= length;
    }

    IRenderer.emitTexturedVertex(bufferBuilder, pose, x1, maxY, z1, color, u1, v0, nx, 0.0F, nz);
    IRenderer.emitTexturedVertex(bufferBuilder, pose, x1, minY, z1, color, u1, v1, nx, 0.0F, nz);
    IRenderer.emitTexturedVertex(bufferBuilder, pose, x2, minY, z2, color, u0, v1, nx, 0.0F, nz);
    IRenderer.emitTexturedVertex(bufferBuilder, pose, x2, maxY, z2, color, u0, v0, nx, 0.0F, nz);
  }
}
