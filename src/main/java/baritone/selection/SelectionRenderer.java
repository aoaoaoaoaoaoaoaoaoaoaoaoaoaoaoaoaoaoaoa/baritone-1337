package baritone.selection;

import baritone.Baritone;
import baritone.api.event.events.RenderEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.selection.ISelection;
import baritone.utils.IRenderer;
import baritone.utils.RenderContext;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.world.phys.AABB;
import java.awt.Color;

public class SelectionRenderer implements IRenderer, AbstractGameEventListener {

  public static final double SELECTION_BOX_EXPANSION = .005D;
  private static final Color HALO_COLOR = Color.BLACK;
  private static final float HALO_WIDTH = 3.0F;

  private final SelectionManager manager;

  SelectionRenderer(Baritone baritone, SelectionManager manager) {
    this.manager = manager;
    baritone.getGameEventHandler().registerEventListener(this);
  }

  public static void renderSelections(RenderContext view, ISelection[] selections) {
    float opacity = settings.selectionOpacity.value;
    boolean ignoreDepth = settings.renderSelectionIgnoreDepth.value;
    float lineWidth = settings.selectionLineWidth.value;

    if (!settings.renderSelection.value || selections.length == 0) {
      return;
    }

    BufferBuilder halo = IRenderer.startLines(HALO_COLOR, opacity);
    for (ISelection selection : selections) {
      IRenderer.emitAABB(halo, view, selection.aabb(), SELECTION_BOX_EXPANSION, lineWidth + HALO_WIDTH);
    }
    if (settings.renderSelectionCorners.value) {
      for (ISelection selection : selections) {
        IRenderer.emitAABB(halo, view, new AABB(selection.pos1()), lineWidth + HALO_WIDTH);
        IRenderer.emitAABB(halo, view, new AABB(selection.pos2()), lineWidth + HALO_WIDTH);
      }
    }
    IRenderer.endLines(halo, ignoreDepth);

    BufferBuilder bufferBuilder = IRenderer.startLines(settings.colorSelection.value, opacity);

    for (ISelection selection : selections) {
      IRenderer.emitAABB(bufferBuilder, view, selection.aabb(), SELECTION_BOX_EXPANSION, lineWidth);
    }

    if (settings.renderSelectionCorners.value) {
      IRenderer.glColor(settings.colorSelectionPos1.value, opacity);

      for (ISelection selection : selections) {
        IRenderer.emitAABB(bufferBuilder, view, new AABB(selection.pos1()), lineWidth);
      }

      IRenderer.glColor(settings.colorSelectionPos2.value, opacity);

      for (ISelection selection : selections) {
        IRenderer.emitAABB(bufferBuilder, view, new AABB(selection.pos2()), lineWidth);
      }
    }

    IRenderer.endLines(bufferBuilder, ignoreDepth);
  }

  public static void renderBox(RenderContext view, AABB aabb, Color color, float opacity, float lineWidth, boolean ignoreDepth) {
    BufferBuilder halo = IRenderer.startLines(HALO_COLOR, opacity);
    IRenderer.emitAABB(halo, view, aabb, lineWidth + HALO_WIDTH);
    IRenderer.endLines(halo, ignoreDepth);

    BufferBuilder box = IRenderer.startLines(color, opacity);
    IRenderer.emitAABB(box, view, aabb, lineWidth);
    IRenderer.endLines(box, ignoreDepth);
  }

  @Override
  public void onRenderPass(RenderEvent event) {
    renderSelections(RenderContext.capture(event), manager.getSelections());
  }
}
