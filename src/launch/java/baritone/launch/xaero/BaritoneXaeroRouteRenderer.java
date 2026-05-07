package baritone.launch.xaero;

import baritone.integration.xaero.XaeroPathingBridge;
import baritone.integration.xaero.XaeroPathingBridge.XaeroMapPoint;
import xaero.map.element.MapElementGraphics;
import xaero.map.element.render.ElementReader;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderProvider;
import xaero.map.element.render.ElementRenderer;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;

import java.util.List;

public final class BaritoneXaeroRouteRenderer extends ElementRenderer<XaeroMapPoint, BaritoneXaeroRouteRenderer.Context, BaritoneXaeroRouteRenderer> {

  BaritoneXaeroRouteRenderer() {
    super(new Context(), new Provider(), new Reader());
  }

  @Override
  public void preRender(ElementRenderInfo info, MultiBufferSource.BufferSource buffer, MultiTextureRenderTypeRendererProvider rendererProvider, boolean shadow) {
  }

  @Override
  public void postRender(ElementRenderInfo info, MultiBufferSource.BufferSource buffer, MultiTextureRenderTypeRendererProvider rendererProvider, boolean shadow) {
  }

  @Override
  public void renderElementShadow(XaeroMapPoint point, boolean hovered, float optionalScale, double partialX, double partialZ, ElementRenderInfo info, MapElementGraphics graphics,
    MultiBufferSource.BufferSource buffer, MultiTextureRenderTypeRendererProvider rendererProvider) {
  }

  @Override
  public boolean renderElement(XaeroMapPoint point, boolean hovered, double depth, float optionalScale, double partialX, double partialZ, ElementRenderInfo info, MapElementGraphics graphics,
    MultiBufferSource.BufferSource buffer, MultiTextureRenderTypeRendererProvider rendererProvider) {
    if (point.dimension() != null && !point.dimension().equals(info.mapDimension)) {
      return false;
    }
    int r = point.radius();
    graphics.fill(-r, -r, r + 1, r + 1, point.argb());
    return true;
  }

  @Override
  public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
    return !shadow && location == ElementRenderLocation.WORLD_MAP;
  }

  @Override
  public int getOrder() { return 100; }

  static final class Context {
    private static final long SNAPSHOT_NS = 100_000_000L;

    private List<XaeroMapPoint> points = List.of();
    private int cursor;
    private long lastSnapshotNs;

    void begin() {
      long now = System.nanoTime();
      if (now - lastSnapshotNs >= SNAPSHOT_NS) {
        points = XaeroPathingBridge.renderPoints();
        lastSnapshotNs = now;
      }
      cursor = 0;
    }

    boolean hasNext() {
      return cursor < points.size();
    }

    XaeroMapPoint next() {
      return points.get(cursor++);
    }
  }

  private static final class Provider extends ElementRenderProvider<XaeroMapPoint, Context> {

    @Override
    public void begin(ElementRenderLocation location, Context context) {
      context.begin();
    }

    @Override
    public boolean hasNext(ElementRenderLocation location, Context context) {
      return context.hasNext();
    }

    @Override
    public XaeroMapPoint getNext(ElementRenderLocation location, Context context) {
      return context.next();
    }

    @Override
    public void end(ElementRenderLocation location, Context context) {
    }
  }

  private static final class Reader extends ElementReader<XaeroMapPoint, Context, BaritoneXaeroRouteRenderer> {

    @Override
    public boolean isHidden(XaeroMapPoint point, Context context) {
      return false;
    }

    @Override
    public double getRenderX(XaeroMapPoint point, Context context, float partialTicks) {
      return point.x() + 0.5D;
    }

    @Override
    public double getRenderZ(XaeroMapPoint point, Context context, float partialTicks) {
      return point.z() + 0.5D;
    }

    @Override
    public int getInteractionBoxLeft(XaeroMapPoint point, Context context, float partialTicks) {
      return -point.radius();
    }

    @Override
    public int getInteractionBoxRight(XaeroMapPoint point, Context context, float partialTicks) {
      return point.radius() + 1;
    }

    @Override
    public int getInteractionBoxTop(XaeroMapPoint point, Context context, float partialTicks) {
      return -point.radius();
    }

    @Override
    public int getInteractionBoxBottom(XaeroMapPoint point, Context context, float partialTicks) {
      return point.radius() + 1;
    }

    @Override
    public int getRenderBoxLeft(XaeroMapPoint point, Context context, float partialTicks) {
      return -point.radius();
    }

    @Override
    public int getRenderBoxRight(XaeroMapPoint point, Context context, float partialTicks) {
      return point.radius() + 1;
    }

    @Override
    public int getRenderBoxTop(XaeroMapPoint point, Context context, float partialTicks) {
      return -point.radius();
    }

    @Override
    public int getRenderBoxBottom(XaeroMapPoint point, Context context, float partialTicks) {
      return point.radius() + 1;
    }

    @Override
    public int getLeftSideLength(XaeroMapPoint point, Minecraft minecraft) {
      return 0;
    }

    @Override
    public String getMenuName(XaeroMapPoint point) {
      return "";
    }

    @Override
    public String getFilterName(XaeroMapPoint point) {
      return "";
    }

    @Override
    public int getMenuTextFillLeftPadding(XaeroMapPoint point) {
      return 0;
    }

    @Override
    public int getRightClickTitleBackgroundColor(XaeroMapPoint point) {
      return 0;
    }

    @Override
    public boolean shouldScaleBoxWithOptionalScale() {
      return false;
    }
  }
}
