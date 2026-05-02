package baritone.utils.accessor;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

public interface IRenderType {
  RenderType createRenderType(String name, RenderSetup renderSetup);
}
