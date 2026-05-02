package baritone.launch.mixins;

import baritone.utils.accessor.IRenderPipelines;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(RenderPipelines.class)
public class MixinRenderPipelines implements IRenderPipelines {
  @Final
  @Shadow
  private static RenderPipeline.Snippet LINES_SNIPPET;

  @Final
  @Shadow
  private static RenderPipeline.Snippet MATRICES_FOG_SNIPPET;

  @Shadow
  private static RenderPipeline register(final RenderPipeline renderPipeline) {
    return null;
  }

  public RenderPipeline.Snippet getLinesSnippet() { return LINES_SNIPPET; }

  @Override
  public RenderPipeline.Snippet getMatricesFogSnippet() { return MATRICES_FOG_SNIPPET; }

  @Override
  public RenderPipeline baritone$registerPipeline(final RenderPipeline pipeline) {
    return register(pipeline);
  }
}
