package baritone.pathing.macro.core;

public enum MacroActionKind {
  SURFACE_TRAVERSE, SURFACE_ENTER, SURFACE_TRANSIT, SURFACE_EXIT, PORTAL_ENTER, PORTAL_BUILD_ENTER, PORTAL_BUILD_EXIT;

  public boolean portal() {
    return switch (this) {
      case PORTAL_ENTER, PORTAL_BUILD_ENTER, PORTAL_BUILD_EXIT -> true;
      default -> false;
    };
  }
}
