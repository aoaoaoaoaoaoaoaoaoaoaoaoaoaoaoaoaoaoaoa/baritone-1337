package baritone.utils.accessor;

import com.mojang.blaze3d.pipeline.RenderPipeline;

public interface IRenderPipelines {
  RenderPipeline.Snippet getLinesSnippet();

  RenderPipeline.Snippet getMatricesFogSnippet();

  RenderPipeline baritone$registerPipeline(RenderPipeline pipeline);
}
