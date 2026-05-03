package baritone.process.elytra;

import baritone.api.Settings;
import baritone.api.event.events.RenderEvent;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Pair;
import baritone.utils.IRenderer;
import baritone.utils.PathRenderer;
import baritone.utils.RenderContext;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class ElytraRenderer {

  private final List<Pair<Vec3, Vec3>> clearLines = new CopyOnWriteArrayList<>();
  private final List<Pair<Vec3, Vec3>> blockedLines = new CopyOnWriteArrayList<>();
  private List<Vec3> simulationLine;
  private BlockPos aimPos;
  private Vec3 aimTarget;
  private List<BetterBlockPos> visiblePath;

  void resetTick() {
    clearLines.clear();
    blockedLines.clear();
    visiblePath = null;
    simulationLine = null;
    aimPos = null;
    aimTarget = null;
  }

  void visiblePath(List<BetterBlockPos> visiblePath) {
    this.visiblePath = visiblePath;
  }

  void aim(Vec3 target) {
    this.aimPos = new BetterBlockPos(target.x, target.y, target.z);
    this.aimTarget = target;
  }

  void simulation(List<Vec3> simulationLine) {
    this.simulationLine = simulationLine;
  }

  void rayTrace(Vec3 start, Vec3 dest, boolean clear) {
    (clear ? clearLines : blockedLines).add(new Pair<>(start, dest));
  }

  void render(IPlayerContext ctx, RenderEvent event) {
    Settings settings = baritone.Baritone.settings();
    RenderContext view = RenderContext.capture(event);
    if (visiblePath != null) {
      PathRenderer.drawPath(view, visiblePath, 0, Color.RED, false, 0, 0, 0.0D);
      renderRouteLine(view, visiblePath, settings);
    }
    if (aimPos != null) {
      PathRenderer.drawGoal(view, ctx, new GoalBlock(aimPos), Color.GREEN);
    }
    if (aimTarget != null) {
      BufferBuilder bufferBuilder = IRenderer.startLines(new Color(0xFFD400), 0.75F);
      IRenderer.emitLine(bufferBuilder, view, ctx.playerHead(), aimTarget, settings.pathRenderLineWidthPixels.value * 1.5F);
      IRenderer.endLines(bufferBuilder, true);
    }
    if (!clearLines.isEmpty() && settings.elytraRenderRaytraces.value) {
      BufferBuilder bufferBuilder = IRenderer.startLines(Color.GREEN);
      for (Pair<Vec3, Vec3> line : clearLines) {
        IRenderer.emitLine(bufferBuilder, view, line.first(), line.second(), settings.pathRenderLineWidthPixels.value);
      }
      IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
    }
    if (!blockedLines.isEmpty() && settings.elytraRenderRaytraces.value) {
      BufferBuilder bufferBuilder = IRenderer.startLines(Color.BLUE);
      for (Pair<Vec3, Vec3> line : blockedLines) {
        IRenderer.emitLine(bufferBuilder, view, line.first(), line.second(), settings.pathRenderLineWidthPixels.value);
      }
      IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
    }
    if (simulationLine != null && settings.elytraRenderSimulation.value) {
      BufferBuilder bufferBuilder = IRenderer.startLines(new Color(0x36CCDC));
      Vec3 offset = ctx.player().getPosition(event.getPartialTicks());
      for (int i = 0; i < simulationLine.size() - 1; i++) {
        IRenderer.emitLine(bufferBuilder, view, simulationLine.get(i).add(offset), simulationLine.get(i + 1).add(offset), settings.pathRenderLineWidthPixels.value);
      }
      IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
    }
  }

  private static void renderRouteLine(RenderContext view, List<BetterBlockPos> path, Settings settings) {
    if (path.size() < 2) {
      return;
    }
    BufferBuilder bufferBuilder = IRenderer.startLines(new Color(0xFF2BD6), 0.8F);
    for (int i = 0; i < path.size() - 1; i++) {
      BetterBlockPos a = path.get(i);
      BetterBlockPos b = path.get(i + 1);
      IRenderer.emitLine(bufferBuilder, view, a.getCenter(), b.getCenter(), settings.pathRenderLineWidthPixels.value * 1.5F);
    }
    IRenderer.endLines(bufferBuilder, true);
  }
}
