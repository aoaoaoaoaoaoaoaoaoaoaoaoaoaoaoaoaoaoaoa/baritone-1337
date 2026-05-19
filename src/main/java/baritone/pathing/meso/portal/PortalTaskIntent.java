package baritone.pathing.meso.portal;

import baritone.pathing.meso.MesoTaskIntent;

public record PortalTaskIntent(PortalTaskKind kind, PortalTaskTarget target, PortalLinkConstraint linkConstraint, PortalSafetyPolicy safetyPolicy) implements MesoTaskIntent {
  public PortalTaskIntent {
    if (kind == null || target == null || linkConstraint == null || safetyPolicy == null) {
      throw new IllegalArgumentException("portal task intent requires kind, target, link constraint, and safety policy");
    }
  }
}
