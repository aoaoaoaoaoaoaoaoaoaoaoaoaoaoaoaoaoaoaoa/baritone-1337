package baritone.launch.mixins;

import baritone.utils.accessor.IRenderType;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(RenderType.class)
public abstract class MixinRenderType implements IRenderType {

  @Shadow
  static RenderType create(final String string, final RenderSetup renderSetup) {
    return null;
  }

  @Override
  public RenderType createRenderType(final String name, final RenderSetup renderSetup) {
    return create(name, renderSetup);
  }
}
