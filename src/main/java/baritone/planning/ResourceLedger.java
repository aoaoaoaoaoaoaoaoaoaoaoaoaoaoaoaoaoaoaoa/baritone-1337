package baritone.planning;

import java.util.List;

public record ResourceLedger(ResourceVector available, ResourceVector reserved, List<PendingResourceTransaction> pending) {
  public static final ResourceLedger EMPTY = new ResourceLedger(ResourceVector.ZERO, ResourceVector.ZERO, List.of());

  public ResourceLedger {
    pending = List.copyOf(pending);
  }

  public boolean canSpend(ResourceVector cost) {
    return available.minus(reserved).dominates(cost);
  }
}
