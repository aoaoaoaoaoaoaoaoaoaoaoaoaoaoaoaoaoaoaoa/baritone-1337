package baritone.launch.xaero;

import xaero.map.WorldMap;
import xaero.map.element.MapElementRenderHandler;

public final class BaritoneXaeroOverlay {

  private static MapElementRenderHandler installedInto;

  private BaritoneXaeroOverlay() {
  }

  public static void install() {
    MapElementRenderHandler handler = WorldMap.mapElementRenderHandler;
    if (handler == null || handler == installedInto) {
      return;
    }
    handler.add(new BaritoneXaeroRouteRenderer());
    installedInto = handler;
  }
}
