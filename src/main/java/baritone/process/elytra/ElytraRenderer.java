package baritone.process.elytra;

import baritone.api.Settings;
import baritone.api.event.events.RenderEvent;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Pair;
import baritone.utils.IRenderer;
import baritone.utils.PathRenderer;
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
  private List<BetterBlockPos> visiblePath;

  void resetTick() {
    clearLines.clear();
    blockedLines.clear();
    visiblePath = null;
    simulationLine = null;
    aimPos = null;
  }

  void visiblePath(List<BetterBlockPos> visiblePath) {
    this.visiblePath = visiblePath;
  }

  void aim(Vec3 target) {
    this.aimPos = new BetterBlockPos(target.x, target.y, target.z);
  }

  void simulation(List<Vec3> simulationLine) {
    this.simulationLine = simulationLine;
  }

  void rayTrace(Vec3 start, Vec3 dest, boolean clear) {
    (clear ? clearLines : blockedLines).add(new Pair<>(start, dest));
  }

  void render(IPlayerContext ctx, RenderEvent event) {
    Settings settings = baritone.Baritone.settings();
    if (visiblePath != null) {
      PathRenderer.drawPath(event.getModelViewStack(), visiblePath, 0, Color.RED, false, 0, 0, 0.0D);
    }
    if (aimPos != null) {
      PathRenderer.drawGoal(event.getModelViewStack(), ctx, new GoalBlock(aimPos), event.getPartialTicks(), Color.GREEN);
    }
    if (!clearLines.isEmpty() && settings.elytraRenderRaytraces.value) {
      BufferBuilder bufferBuilder = IRenderer.startLines(Color.GREEN);
      for (Pair<Vec3, Vec3> line : clearLines) {
        IRenderer.emitLine(bufferBuilder, event.getModelViewStack(), line.first(), line.second(), settings.pathRenderLineWidthPixels.value);
      }
      IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
    }
    if (!blockedLines.isEmpty() && settings.elytraRenderRaytraces.value) {
      BufferBuilder bufferBuilder = IRenderer.startLines(Color.BLUE);
      for (Pair<Vec3, Vec3> line : blockedLines) {
        IRenderer.emitLine(bufferBuilder, event.getModelViewStack(), line.first(), line.second(), settings.pathRenderLineWidthPixels.value);
      }
      IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
    }
    if (simulationLine != null && settings.elytraRenderSimulation.value) {
      BufferBuilder bufferBuilder = IRenderer.startLines(new Color(0x36CCDC));
      Vec3 offset = ctx.player().getPosition(event.getPartialTicks());
      for (int i = 0; i < simulationLine.size() - 1; i++) {
        IRenderer.emitLine(bufferBuilder, event.getModelViewStack(), simulationLine.get(i).add(offset), simulationLine.get(i + 1).add(offset), settings.pathRenderLineWidthPixels.value);
      }
      IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
    }
  }
}
