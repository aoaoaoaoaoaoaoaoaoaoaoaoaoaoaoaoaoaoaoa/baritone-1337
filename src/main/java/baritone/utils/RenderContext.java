package baritone.utils;

import baritone.api.event.events.RenderEvent;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public record RenderContext(PoseStack stack, float partialTicks, double originX, double originY, double originZ) {

  public static RenderContext capture(RenderEvent event) {
    return new RenderContext(event.getModelViewStack(), event.getPartialTicks(), IRenderer.renderManager.renderPosX(), IRenderer.renderManager.renderPosY(), IRenderer.renderManager.renderPosZ());
  }

  public double x(double worldX) {
    return worldX - originX;
  }

  public double y(double worldY) {
    return worldY - originY;
  }

  public double z(double worldZ) {
    return worldZ - originZ;
  }

  public AABB relative(AABB box) {
    return box.move(-originX, -originY, -originZ);
  }

  public Vec3 cameraPosition() {
    return new Vec3(originX, originY, originZ);
  }
}
